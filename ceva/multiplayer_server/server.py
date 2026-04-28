#!/usr/bin/env python3
"""
Michi's Adventure - Multiplayer Server v2

Reprogrammed from scratch with the same security model as the cloud-save
server:

  - RSA-OAEP-SHA256 license-bound handshake (per connection)
  - AES-256-GCM authenticated encryption for every gameplay frame
  - HKDF-SHA256 for delivery & session key derivation
  - Anti-replay: signed timestamp + per-handshake nonces + nonce cache
  - Per-IP sliding-window rate limit
  - Bounded write queues + connection cap
  - Server-authoritative life clamp + name sanitization
  - Bind IP/port asked interactively at startup (overridable via env or argv)

Wire format:
    All lines are newline-terminated UTF-8.

    Handshake (first message C->S; last is server-encrypted session key):
        C -> "HELLO v2 <base64(client_nonce_16)>"
        S -> "OK <base64(server_nonce_16)>"
        C -> "AUTH <base64(rsa_oaep_sha256(handshake_json))>"
            handshake_json = {
              "license":      "XXXXXXXX-YYYY",
              "ts":           <unix epoch s>,
              "client_nonce": <hex>,
              "server_nonce": <hex>,
              "name":         "<player display name>",
              "class":        "<player class>"
            }
        S -> "AUTH_OK <base64(aesgcm(session_key, key=delivery_key,
                                     nonce=client_nonce[:12],
                                     aad='MichiMpSession'))>"

    Session frames (after handshake), each direction has its own counter:
        wire = "DATA <base64(seq_8 || nonce_12 || ciphertext || tag_16)>"
        AAD  = direction_byte (0x01 server->client, 0x02 client->server) || seq_8

    Plaintext payloads are JSON. Same envelope as the legacy MP server, so
    the surface contract stays familiar.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import collections
import hashlib
import hmac
import ipaddress
import json
import logging
import os
import signal
import sqlite3
import struct
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


# ── Constants & defaults ────────────────────────────────────────────────────
BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "mp_config.json"
DB_PATH = BASE_DIR / "mp_licenses.db"

PROTOCOL_TAG = "v2"
HANDSHAKE_TS_WINDOW = 60
NONCE_REPLAY_WINDOW = 300

DEFAULT_PORT = 7777
DEFAULT_PRIVATE_KEY = "server_private_key.pem"

MAX_LINE_BYTES = 64 * 1024
MAX_CHAT_LEN = 200
MAX_NAME_LEN = 24
MAX_CLASS_LEN = 16
MAX_PAYLOAD_BYTES = 16 * 1024
MAX_LIFE_CAP = 9999
MAX_PLAYERS_DEFAULT = 8
TICK_RATE = 20
PING_TIMEOUT = 15.0
SESSION_IDLE_TIMEOUT = 60.0
RATE_LIMIT_PER_IP_PER_MIN = 30
MAX_CHAT_PER_10S = 5

DEFAULT_CONFIG = {
    "host": None,
    "port": None,
    "private_key_path": DEFAULT_PRIVATE_KEY,
    # Crypto identity — set these in mp_config.json on the server.
    # They must match the values used when the license keys were generated.
    "license_salt":   "MichiCloudSalt2026",
    "license_pepper": "michi-license-pepper-v2",
    "max_players": MAX_PLAYERS_DEFAULT,
    "rate_limit_per_ip_per_minute": RATE_LIMIT_PER_IP_PER_MIN,
}

ALLOWED_NAME = set(
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789_-. "
)


# ── Per-license DB (sync; called via run_in_executor from async code) ──────
_db_lock = threading.Lock()
ROTATION_INTERVAL = 5


def init_mp_db() -> None:
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("""
            CREATE TABLE IF NOT EXISTS licenses (
                license_key  TEXT    PRIMARY KEY,
                salt_hex     TEXT    NOT NULL,
                pepper_hex   TEXT    NOT NULL,
                login_count  INTEGER NOT NULL DEFAULT 0,
                rotated_at   TEXT    NOT NULL,
                created_at   TEXT    NOT NULL
            )
        """)
        con.commit()
        con.close()


def _db_get_or_create(license_key: str) -> tuple[bytes, bytes, int]:
    now = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        row = con.execute(
            "SELECT salt_hex, pepper_hex, login_count FROM licenses WHERE license_key = ?",
            (license_key,),
        ).fetchone()
        if row is None:
            salt   = os.urandom(32)
            pepper = os.urandom(32)
            con.execute(
                """
                INSERT INTO licenses
                    (license_key, salt_hex, pepper_hex, login_count, rotated_at, created_at)
                VALUES (?, ?, ?, 0, ?, ?)
                """,
                (license_key, salt.hex(), pepper.hex(), now, now),
            )
            con.commit()
            login_count = 0
            log.info("New MP license registered: %s", license_key)
        else:
            salt        = bytes.fromhex(row[0])
            pepper      = bytes.fromhex(row[1])
            login_count = int(row[2])
        con.close()
    return salt, pepper, login_count


def _db_increment_and_rotate(license_key: str, login_count: int) -> None:
    new_count = login_count + 1
    now = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        if new_count % ROTATION_INTERVAL == 0:
            new_salt   = os.urandom(32)
            new_pepper = os.urandom(32)
            con.execute(
                """
                UPDATE licenses
                SET login_count = ?, salt_hex = ?, pepper_hex = ?, rotated_at = ?
                WHERE license_key = ?
                """,
                (new_count, new_salt.hex(), new_pepper.hex(), now, license_key),
            )
            log.info("Rotated MP salt/pepper for %s (login #%d)", license_key, new_count)
        else:
            con.execute(
                "UPDATE licenses SET login_count = ? WHERE license_key = ?",
                (new_count, license_key),
            )
        con.commit()
        con.close()


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        try:
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
        except Exception as exc:
            print(f"[WARN] Could not parse {CONFIG_PATH}: {exc} — using defaults")
    cfg["host"] = os.environ.get("MICHI_MP_HOST") or cfg["host"]
    raw_port = os.environ.get("MICHI_MP_PORT")
    if raw_port:
        try:
            cfg["port"] = int(raw_port)
        except ValueError:
            pass
    return cfg


# ── Logging ────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("michi-mp")


# ── Helpers ────────────────────────────────────────────────────────────────
def license_is_valid(license_key: str, salt: str) -> bool:
    if not isinstance(license_key, str) or len(license_key) != 13 or license_key[8] != "-":
        return False
    prefix, suffix = license_key[:8], license_key[9:]
    if not prefix.isalnum() or not suffix.isalnum() or len(suffix) != 4:
        return False
    expected = hashlib.sha256(
        f"{prefix}{salt}".encode("utf-8")
    ).hexdigest()[:4].upper()
    return hmac.compare_digest(expected, suffix.upper())


def hkdf(secret: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(secret)


def sanitize_name(raw: str, fallback: str) -> str:
    if not isinstance(raw, str):
        return fallback
    cleaned = "".join(c for c in raw if c in ALLOWED_NAME).strip()
    if not cleaned:
        return fallback
    return cleaned[:MAX_NAME_LEN]


def clamp_int(value, lo: int, hi: int, default: int) -> int:
    try:
        v = int(value)
    except (TypeError, ValueError):
        return default
    if v < lo:
        return lo
    if v > hi:
        return hi
    return v


# ── Anti-replay nonce cache ────────────────────────────────────────────────
class NonceCache:
    def __init__(self, ttl: int = NONCE_REPLAY_WINDOW):
        self.ttl = ttl
        self._seen: dict[bytes, float] = {}
        self._lock = asyncio.Lock()

    async def check_and_store(self, nonce: bytes) -> bool:
        now = time.monotonic()
        async with self._lock:
            cutoff = now - self.ttl
            if len(self._seen) > 4096:
                self._seen = {k: v for k, v in self._seen.items() if v >= cutoff}
            if nonce in self._seen and self._seen[nonce] >= cutoff:
                return False
            self._seen[nonce] = now
            return True


# ── Per-IP rate limiter ────────────────────────────────────────────────────
class IpRateLimiter:
    def __init__(self, max_per_minute: int):
        self.max_per_minute = max_per_minute
        self._buckets: dict[str, collections.deque] = collections.defaultdict(collections.deque)

    def allow(self, ip: str) -> bool:
        now = time.monotonic()
        cutoff = now - 60.0
        q = self._buckets[ip]
        while q and q[0] < cutoff:
            q.popleft()
        if len(q) >= self.max_per_minute:
            return False
        q.append(now)
        return True


# ── Player state ───────────────────────────────────────────────────────────
@dataclass
class PlayerState:
    player_id: int
    name: str = "Player"
    player_class: str = "Fighter"
    x: int = 0
    y: int = 0
    direction: int = 0
    sprite_num: int = 1
    attacking: bool = False
    life: int = 6
    max_life: int = 6
    last_seen: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return {
            "id": self.player_id,
            "name": self.name,
            "class": self.player_class,
            "x": self.x, "y": self.y, "dir": self.direction,
            "sprite": self.sprite_num, "attacking": self.attacking,
            "life": self.life, "maxLife": self.max_life,
        }

    def to_update_dict(self) -> dict:
        return {
            "type": "player_update",
            "id": self.player_id,
            "x": self.x, "y": self.y, "dir": self.direction,
            "sprite": self.sprite_num, "attacking": self.attacking,
            "life": self.life, "maxLife": self.max_life,
        }


# ── Encrypted client connection ────────────────────────────────────────────
class ClientConnection:
    DIR_S2C = b"\x01"
    DIR_C2S = b"\x02"

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter,
                 session_key: bytes, player: PlayerState):
        self.reader = reader
        self.writer = writer
        self.session_key = session_key
        self.player = player
        self.recv_seq = 0
        self.send_seq = 0
        self._queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=256)
        self._writer_task: Optional[asyncio.Task] = None
        self._chat_times: collections.deque = collections.deque()

    def start_writer(self) -> None:
        self._writer_task = asyncio.create_task(self._writer_loop())

    async def _writer_loop(self) -> None:
        try:
            while True:
                data = await self._queue.get()
                self.writer.write(data)
                await self.writer.drain()
        except (ConnectionError, asyncio.CancelledError):
            pass
        except Exception as exc:
            log.warning("writer error pid=%s: %s", self.player.player_id, exc)

    async def close(self) -> None:
        if self._writer_task:
            self._writer_task.cancel()
        try:
            self.writer.close()
            await self.writer.wait_closed()
        except Exception:
            pass

    def send_json(self, obj: dict) -> None:
        try:
            plaintext = json.dumps(obj, separators=(",", ":")).encode("utf-8")
            nonce = os.urandom(12)
            seq = struct.pack(">Q", self.send_seq)
            aad = self.DIR_S2C + seq
            ct = AESGCM(self.session_key).encrypt(nonce, plaintext, aad)
            wire = b"DATA " + base64.b64encode(seq + nonce + ct) + b"\n"
            self._queue.put_nowait(wire)
            self.send_seq += 1
        except asyncio.QueueFull:
            log.warning("queue full pid=%s, dropping message", self.player.player_id)

    async def recv_json(self) -> dict:
        line = await self.reader.readline()
        if not line:
            raise ConnectionError("peer closed")
        if len(line) > MAX_LINE_BYTES:
            raise ValueError("frame too large")
        text = line.decode("utf-8").rstrip("\r\n")
        if not text.startswith("DATA "):
            raise ValueError("expected DATA frame")
        raw = base64.b64decode(text[5:], validate=True)
        if len(raw) < 8 + 12 + 16:
            raise ValueError("frame too short")
        seq = struct.unpack(">Q", raw[:8])[0]
        nonce = raw[8:20]
        ct = raw[20:]
        if seq != self.recv_seq:
            raise ValueError(f"seq mismatch (got {seq}, want {self.recv_seq})")
        aad = self.DIR_C2S + raw[:8]
        try:
            plaintext = AESGCM(self.session_key).decrypt(nonce, ct, aad)
        except InvalidTag as exc:
            raise ValueError("AEAD authentication failed") from exc
        self.recv_seq += 1
        if len(plaintext) > MAX_PAYLOAD_BYTES:
            raise ValueError("payload too big")
        return json.loads(plaintext.decode("utf-8"))

    def chat_allowed_now(self) -> bool:
        now = time.monotonic()
        cutoff = now - 10.0
        while self._chat_times and self._chat_times[0] < cutoff:
            self._chat_times.popleft()
        if len(self._chat_times) >= MAX_CHAT_PER_10S:
            return False
        self._chat_times.append(now)
        return True


# ── Game server ────────────────────────────────────────────────────────────
class GameServer:
    def __init__(self, host: str, port: int, max_players: int,
                 private_key, nonce_cache: NonceCache,
                 rate_limiter: IpRateLimiter, cfg: dict):
        self.host = host
        self.port = port
        self.max_players = max_players
        self.private_key = private_key
        self.nonce_cache = nonce_cache
        self.rate_limiter = rate_limiter
        self.cfg = cfg
        self.clients: dict[int, ClientConnection] = {}
        self._next_id = 1
        self._running = False
        self._server: Optional[asyncio.AbstractServer] = None

    def _allocate_id(self) -> int:
        pid = self._next_id
        self._next_id += 1
        return pid

    def broadcast_json(self, obj: dict, exclude_id: int = -1) -> None:
        for pid, client in self.clients.items():
            if pid != exclude_id:
                client.send_json(obj)

    # ── Handshake ──
    async def _handshake(self, reader: asyncio.StreamReader,
                         writer: asyncio.StreamWriter,
                         peer: tuple[str, int]) -> Optional[tuple[bytes, dict]]:
        # Stage 1: HELLO
        line = await asyncio.wait_for(reader.readline(), timeout=10.0)
        if not line:
            return None
        text = line.decode("utf-8", errors="replace").rstrip("\r\n")
        parts = text.split(" ")
        if len(parts) != 3 or parts[0] != "HELLO" or parts[1] != PROTOCOL_TAG:
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        try:
            client_nonce = base64.b64decode(parts[2], validate=True)
            if len(client_nonce) != 16:
                raise ValueError
        except Exception:
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None

        server_nonce = os.urandom(16)
        writer.write(b"OK " + base64.b64encode(server_nonce) + b"\n")
        await writer.drain()

        # Stage 2: AUTH
        line = await asyncio.wait_for(reader.readline(), timeout=10.0)
        if not line:
            return None
        text = line.decode("utf-8", errors="replace").rstrip("\r\n")
        if not text.startswith("AUTH "):
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        try:
            enc = base64.b64decode(text[5:], validate=True)
            plaintext = self.private_key.decrypt(
                enc,
                rsa_padding.OAEP(
                    mgf=rsa_padding.MGF1(algorithm=hashes.SHA256()),
                    algorithm=hashes.SHA256(),
                    label=None,
                ),
            )
            payload = json.loads(plaintext.decode("utf-8"))
        except Exception as exc:
            log.info("AUTH decrypt failed from %s: %s", peer, type(exc).__name__)
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None

        license_key = str(payload.get("license", ""))[:32]
        ts = int(payload.get("ts", 0))
        cn_check = bytes.fromhex(str(payload.get("client_nonce", "")) or "")
        sn_check = bytes.fromhex(str(payload.get("server_nonce", "")) or "")

        if not license_is_valid(license_key, self.cfg["license_salt"]):
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            log.info("AUTH bad license from %s", peer)
            return None
        if abs(int(time.time()) - ts) > HANDSHAKE_TS_WINDOW:
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            log.info("AUTH stale timestamp from %s", peer)
            return None
        if not hmac.compare_digest(cn_check, client_nonce):
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        if not hmac.compare_digest(sn_check, server_nonce):
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        if not await self.nonce_cache.check_and_store(client_nonce):
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            log.info("AUTH replay rejected from %s", peer)
            return None

        # Per-license crypto material (auto-created on first connect, rotated every 5 logins)
        loop = asyncio.get_event_loop()
        lic_salt, lic_pepper, login_count = await loop.run_in_executor(
            None, _db_get_or_create, license_key
        )

        delivery_key = hkdf(
            secret=license_key.encode("utf-8") + lic_pepper,
            salt=lic_salt + server_nonce,
            info=b"michi-delivery-v2",
            length=32,
        )
        session_key = os.urandom(32)
        enc_session = AESGCM(delivery_key).encrypt(
            client_nonce[:12], session_key, b"MichiMpSession"
        )
        writer.write(b"AUTH_OK " + base64.b64encode(enc_session) + b"\n")
        await writer.drain()

        return session_key, payload, login_count

    # ── Per-client lifecycle ──
    async def handle_client(self, reader: asyncio.StreamReader,
                            writer: asyncio.StreamWriter):
        peer = writer.get_extra_info("peername") or ("?", 0)
        ip = peer[0]

        if not self.rate_limiter.allow(ip):
            log.warning("Rate-limited %s", ip)
            writer.write(b"RATE_LIMIT\n")
            try:
                await writer.drain()
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except Exception:
                    pass
            return

        if len(self.clients) >= self.max_players:
            log.info("Server full, rejecting %s", ip)
            writer.write(b"SERVER_FULL\n")
            try:
                await writer.drain()
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except Exception:
                    pass
            return

        try:
            handshake = await asyncio.wait_for(
                self._handshake(reader, writer, peer), timeout=12.0
            )
        except (asyncio.TimeoutError, ValueError, ConnectionError) as exc:
            log.info("handshake failed from %s: %s", peer, exc)
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            return

        if handshake is None:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            return

        session_key, payload, login_count = handshake
        license_key = str(payload.get("license", ""))[:32]

        player = PlayerState(player_id=self._allocate_id())
        player.name = sanitize_name(payload.get("name", ""), "Player")
        cls_raw = str(payload.get("class", "Fighter"))[:MAX_CLASS_LEN]
        player.player_class = "".join(c for c in cls_raw if c.isalnum()) or "Fighter"

        client = ClientConnection(reader, writer, session_key, player)
        client.start_writer()
        self.clients[player.player_id] = client
        log.info("Player %d (%s) joined from %s — %d/%d",
                 player.player_id, player.name, ip,
                 len(self.clients), self.max_players)

        # Send welcome
        existing = [c.player.to_dict() for pid, c in self.clients.items()
                    if pid != player.player_id]
        client.send_json({
            "type": "welcome",
            "id": player.player_id,
            "players": existing,
        })

        # Notify others
        self.broadcast_json({
            "type": "player_join",
            "id": player.player_id,
            "name": player.name,
            "class": player.player_class,
        }, exclude_id=player.player_id)

        # Main loop — track login for rotation on exit
        _session_ok = True
        try:
            while self._running:
                try:
                    msg = await asyncio.wait_for(client.recv_json(), timeout=PING_TIMEOUT)
                except asyncio.TimeoutError:
                    log.info("Player %d timed out", player.player_id)
                    break
                except (ConnectionError, ValueError) as exc:
                    log.info("Player %d dropped: %s", player.player_id, exc)
                    break

                player.last_seen = time.time()
                t = msg.get("type")

                if t == "move":
                    player.x = clamp_int(msg.get("x"), -10**7, 10**7, player.x)
                    player.y = clamp_int(msg.get("y"), -10**7, 10**7, player.y)
                    player.direction = clamp_int(msg.get("dir"), 0, 7, player.direction)
                    player.sprite_num = clamp_int(msg.get("sprite"), 0, 32, player.sprite_num)
                    player.attacking = bool(msg.get("attacking", False))
                    player.life = clamp_int(msg.get("life"), 0, MAX_LIFE_CAP, player.life)
                    player.max_life = clamp_int(msg.get("maxLife"), 1, MAX_LIFE_CAP, player.max_life)

                elif t == "chat":
                    if not client.chat_allowed_now():
                        client.send_json({"type": "chat_throttled"})
                        continue
                    chat_msg = str(msg.get("msg", ""))[:MAX_CHAT_LEN]
                    chat_msg = chat_msg.replace("\r", " ").replace("\n", " ")
                    if chat_msg:
                        log.info("[CHAT] %s: %s", player.name, chat_msg)
                        self.broadcast_json({
                            "type": "chat",
                            "from": player.name,
                            "msg": chat_msg,
                        })

                elif t == "ping":
                    client.send_json({"type": "pong"})

                else:
                    # Ignore unknown messages, do not punish — protocol may grow
                    log.debug("Unknown msg type from pid=%s: %s",
                              player.player_id, t)

        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            if _session_ok:
                loop = asyncio.get_event_loop()
                try:
                    await loop.run_in_executor(
                        None, _db_increment_and_rotate, license_key, login_count
                    )
                except Exception:
                    log.exception("Failed to update login counter for %s", license_key)
            if player.player_id in self.clients:
                del self.clients[player.player_id]
            await client.close()
            log.info("Player %d (%s) disconnected — %d/%d",
                     player.player_id, player.name,
                     len(self.clients), self.max_players)
            self.broadcast_json({
                "type": "player_leave",
                "id": player.player_id,
            })

    # ── Tick loop (broadcasts positions at fixed rate) ──
    async def tick_loop(self) -> None:
        interval = 1.0 / TICK_RATE
        while self._running:
            start = time.monotonic()
            for pid, client in list(self.clients.items()):
                update = client.player.to_update_dict()
                for other_pid, other_client in self.clients.items():
                    if other_pid != pid:
                        other_client.send_json(update)
            elapsed = time.monotonic() - start
            await asyncio.sleep(max(0.0, interval - elapsed))

    # ── Server start/stop ──
    async def start(self) -> None:
        self._running = True
        self._server = await asyncio.start_server(
            self.handle_client, self.host, self.port,
        )
        addrs = ", ".join(str(s.getsockname()) for s in self._server.sockets)
        log.info("Multiplayer server v2 listening on %s — max %d players, tick %d Hz",
                 addrs, self.max_players, TICK_RATE)
        asyncio.create_task(self.tick_loop())
        async with self._server:
            await self._server.serve_forever()

    async def stop(self) -> None:
        self._running = False
        for pid, client in list(self.clients.items()):
            client.send_json({"type": "kick", "reason": "Server shutting down"})
            await client.close()
        self.clients.clear()
        if self._server:
            self._server.close()


# ── Startup helpers ────────────────────────────────────────────────────────
def prompt_bind_address(default_host: str, default_port: int) -> tuple[str, int]:
    print("══════════════════════════════════════════════════════")
    print("  Michi's Adventure — Multiplayer Server v2")
    print("══════════════════════════════════════════════════════")
    print("  The server needs a bind IP and port.")
    print("  - Use 0.0.0.0 to accept connections from any network.")
    print("  - Use 127.0.0.1 for local-only testing.")
    print("  - Use a specific LAN IP to bind only that interface.")
    print("──────────────────────────────────────────────────────")
    while True:
        raw_host = input(f"Bind IP [{default_host}]: ").strip() or default_host
        try:
            ipaddress.ip_address(raw_host)
            host = raw_host
            break
        except ValueError:
            print("  → That isn't a valid IP. Try again.")
    while True:
        raw_port = input(f"Bind port [{default_port}]: ").strip() or str(default_port)
        try:
            port = int(raw_port)
            if 1 <= port <= 65535:
                break
        except ValueError:
            pass
        print("  → Port must be an integer 1..65535.")
    return host, port


def load_private_key(path: Path):
    if not path.exists():
        log.error("Private key not found at %s", path)
        log.error("Run generate_keys.py (in ceva/server/) and copy "
                  "server_private_key.pem next to this script.")
        sys.exit(2)
    pem = path.read_bytes()
    key = serialization.load_pem_private_key(pem, password=None)
    if key.key_size < 2048:
        log.error("RSA key must be at least 2048 bits.")
        sys.exit(2)
    return key


# ── Entry point ────────────────────────────────────────────────────────────
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Michi's Adventure multiplayer server v2")
    p.add_argument("--host", help="Bind IP (skips prompt).")
    p.add_argument("--port", type=int, help="Bind port (skips prompt).")
    p.add_argument("--max-players", type=int, default=MAX_PLAYERS_DEFAULT,
                   help=f"Maximum concurrent players (default {MAX_PLAYERS_DEFAULT}).")
    p.add_argument("--private-key", default=DEFAULT_PRIVATE_KEY,
                   help="Path to RSA private key PEM.")
    p.add_argument("--no-prompt", action="store_true",
                   help="Do not prompt; require --host and --port (or env).")
    return p.parse_args()


async def amain(host: str, port: int, max_players: int, private_key, cfg: dict) -> None:
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, init_mp_db)

    nonce_cache = NonceCache()
    rate_limiter = IpRateLimiter(cfg.get("rate_limit_per_ip_per_minute", RATE_LIMIT_PER_IP_PER_MIN))
    server = GameServer(
        host=host, port=port, max_players=max_players,
        private_key=private_key, nonce_cache=nonce_cache,
        rate_limiter=rate_limiter, cfg=cfg,
    )

    def shutdown_handler():
        log.info("Shutdown signal received...")
        asyncio.create_task(server.stop())

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, shutdown_handler)
        except NotImplementedError:
            signal.signal(sig, lambda s, f: shutdown_handler())

    try:
        await server.start()
    except asyncio.CancelledError:
        pass
    finally:
        await server.stop()


def main() -> None:
    args = parse_args()
    cfg = load_config()

    # Resolve private key path (argv > config > default)
    pk_path = Path(args.private_key if args.private_key != DEFAULT_PRIVATE_KEY
                   else cfg.get("private_key_path", DEFAULT_PRIVATE_KEY))
    if not pk_path.is_absolute():
        pk_path = BASE_DIR / pk_path
    private_key = load_private_key(pk_path)

    # Resolve host/port — argv > env (already in cfg) > config file > prompt
    host = args.host or cfg.get("host")
    port = args.port or cfg.get("port")

    if (host is None or port is None) and not args.no_prompt:
        host_p, port_p = prompt_bind_address(host or "0.0.0.0", port or DEFAULT_PORT)
        host = host or host_p
        port = port or port_p

    if host is None or port is None:
        log.error("Must specify --host and --port when --no-prompt is set.")
        sys.exit(2)

    max_players = args.max_players if args.max_players != MAX_PLAYERS_DEFAULT \
        else cfg.get("max_players", MAX_PLAYERS_DEFAULT)

    log.info("Starting on %s:%d (key=%s, max_players=%d)",
             host, port, pk_path, max_players)

    try:
        asyncio.run(amain(host, port, max_players, private_key, cfg))
    except KeyboardInterrupt:
        log.info("Server stopped.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Michi's Adventure - Multiplayer Server v2

Format comunicare:

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
import asyncio #pentru citire/scriere intr-o conexiune TCP, am renuntat la sockets
import base64
import collections
import hashlib #momentan nefolosit...
import hmac
import ipaddress
import json
import logging
import os
import signal
import struct
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from world import MapCollection, TmxMap

import license_verify


BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "mp_config.json" #config file

PROTOCOL_TAG = "v2"
HANDSHAKE_TS_WINDOW = 60
NONCE_REPLAY_WINDOW = 300

DEFAULT_PORT = 7777 #INTRE 0 SI 65535
DEFAULT_PRIVATE_KEY = "server_private_key.pem"
DEFAULT_MAPS_DIR = "maps"
DEFAULT_CHUNK_SIZE = 32
DEFAULT_MAX_CHUNKS_PER_TICK = 4

DEFAULT_MAX_TILE_STEP = 4

DEFAULT_HITBOX_W = 24 #latimea playerului, echivalenta cu cea din client
DEFAULT_HITBOX_H = 24 #inaltimea playerului

MAX_LINE_BYTES = 64 * 1024
MAX_CHAT_LEN = 200
MAX_NAME_LEN = 24
MAX_CLASS_LEN = 16
MAX_PAYLOAD_BYTES = 16 * 1024
MAX_LIFE_CAP = 9999
MAX_PLAYERS = 8
TICK_RATE = 20
PING_TIMEOUT = 15.0
SESSION_IDLE_TIMEOUT = 60.0
RATE_LIMIT_PER_IP_PER_MIN = 30
MAX_CHAT_PER_10S = 5


'''
======================================
initializare
======================================
'''

INITIAL_CONFIG = {
    "host": None,
    "port": None,
    "private_key_path": DEFAULT_PRIVATE_KEY, #cheia publica primita la instalare. criptata cu RSA-2048, cheia care a fost folosita pentru a semna license.proprieties. TREBUIE sa se potriveasca cu LicenseManager.PUBLIC_KEY_B64.
    "license_public_key_b64": "REPLACE_WITH_PUBLIC_KEY_FROM_generate_license_keys.py",
    #aici tinem locul de citire licenses.json, am renuntat la lista
    "dev_mode": False, #MOD DE DEBUG. NU PORNI DECAT IN CAZ DE SERVER HOSTUIT LA LOOPBACK. opreste verificarea de licenta
    "max_players": MAX_PLAYERS,
    "rate_limit_per_ip_per_minute": RATE_LIMIT_PER_IP_PER_MIN,

    # ---- World streaming ----
    "maps_dir": DEFAULT_MAPS_DIR,
    "active_map": None,                   # required — chosen by server operator
    "chunk_size_tiles": DEFAULT_CHUNK_SIZE,
    "max_chunks_per_tick": DEFAULT_MAX_CHUNKS_PER_TICK,
    "max_tile_step_per_move": DEFAULT_MAX_TILE_STEP,
    "player_hitbox_w": DEFAULT_HITBOX_W,
    "player_hitbox_h": DEFAULT_HITBOX_H,
    # Optional declared dimensions per map id — used to cross-check TMX files.
    # "maps": { "harta": { "width": 100, "height": 100 }, ... }
    "maps": {},
}

ALLOWED_NAME = set(
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789_-. "
) #caractere care pot aparea in numele playerului. numele nu va fi folosit inca. numele este default Player


#definim configul
def load_config() -> dict:
    cfg = dict(INITIAL_CONFIG)
    if CONFIG_PATH.exists():
        try:
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8"))) #marea citire
        except Exception as exc:
            print(f"[WARN] Could not parse {CONFIG_PATH}: {exc} — using defaults")
    cfg["host"] = os.environ.get("MICHI_MP_HOST") or cfg["host"] #ia din environment variable sau din config file
    raw_port = os.environ.get("MICHI_MP_PORT")
    if raw_port:
        try:
            cfg["port"] = int(raw_port)
        except ValueError:
            pass
    return cfg


#loguri, nu logging :)). engleza asta...
logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("michi-mp")


# ── Helpers ────────────────────────────────────────────────────────────────
# Cheap structural pre-filter — real trust comes from RSA signature.
# Accepts any printable alphanumeric/dash token.
_LICENSE_KEY_RE = __import__("re").compile(r"^[A-Z0-9][A-Z0-9\-]{3,63}$")

def license_is_well_formed(license_key: str) -> bool:
    return isinstance(license_key, str) and bool(_LICENSE_KEY_RE.match(license_key))


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


#Anti-replay nonce cache. iubim playerii, iubim si atacatorii.... . Dar nu se poate cu amandoi...
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


#Rate limiter pe ip. In caz de orice atac ddos/dos
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


#Player state
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
    # World-streaming state
    map_id: str = ""
    chunks_sent: set = field(default_factory=set)   # {(layer_idx, cx, cy)}
    chunk_requests: collections.deque = field(default_factory=collections.deque)
    last_valid_x: int = 0
    last_valid_y: int = 0

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


# Mob state for multiplayer synchronization
@dataclass
class MobState:
    mob_id: int           # Array index on client
    mob_type: str         # Monster type identifier
    life: int
    max_life: int
    alive: bool = True
    dying: bool = False
    world_x: int = 0
    world_y: int = 0
    last_attacker_pid: int = -1

    def to_damage_dict(self) -> dict:
        return {
            "type": "mob_damage",
            "mob_id": self.mob_id,
            "life": self.life,
            "max_life": self.max_life,
            "alive": self.alive,
            "dying": self.dying,
            "attacker_pid": self.last_attacker_pid,
        }

    def to_death_dict(self) -> dict:
        return {
            "type": "mob_death",
            "mob_id": self.mob_id,
            "killer_pid": self.last_attacker_pid,
        }


#Encrypted client connection

#da wrap la conexiunea tcp si are grija de comunicarea post-handshake
class ClientConnection:
    DIR_S2C = b"\x01" #Server to Client. Semnifica directional tags. Previne unele replay/reflect attacks destul de eficient
    DIR_C2S = b"\x02" #Client to Server

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

#for future development: se poate folosi functia de chat. se va lansa in urmatorul client update. am declarat mai jos ce inseamna un frame de chat
    def chat_allowed_now(self) -> bool:
        now = time.monotonic()
        cutoff = now - 10.0
        while self._chat_times and self._chat_times[0] < cutoff:
            self._chat_times.popleft()
        if len(self._chat_times) >= MAX_CHAT_PER_10S:
            return False
        self._chat_times.append(now)
        return True



#Game Server
class GameServer:
    def __init__(self, host: str, port: int, max_players: int,
                 private_key, nonce_cache: NonceCache,
                 rate_limiter: IpRateLimiter, cfg: dict,
                 maps: MapCollection, active_map_id: str):
        self.host = host
        self.port = port
        self.max_players = max_players
        self.private_key = private_key
        self.nonce_cache = nonce_cache
        self.rate_limiter = rate_limiter
        self.cfg = cfg
        self.maps = maps # foloseste world.py, aici e integrarea dintre serverul efectiv si world.py
        self.active_map_id = active_map_id
        self.world: TmxMap = maps.get(active_map_id)
        if self.world is None:
            raise RuntimeError(
                f"Active map '{active_map_id}' not found in maps_dir. "
                f"Available: {maps.list_ids()}"
            )
        self.clients: dict[int, ClientConnection] = {}
        self._next_id = 1
        self._running = False
        self._server: Optional[asyncio.AbstractServer] = None
        # Mob tracking for multiplayer synchronization
        self.mobs: dict[int, MobState] = {}

    def _allocate_id(self) -> int:
        pid = self._next_id
        self._next_id += 1
        return pid

    def broadcast_json(self, obj: dict, exclude_id: int = -1) -> None:
        for pid, client in self.clients.items():
            if pid != exclude_id:
                client.send_json(obj)

    #Handshake
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
        # Format: "AUTH <enc_b64> <machine_fp> <sig_b64>"
        # machine_fp and sig are outside the OAEP envelope (plaintext is fine —
        # sig is already in license.properties on the client machine).
        auth_parts = text.split(" ")
        if len(auth_parts) != 4:
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        machine_fp  = auth_parts[2][:64]
        license_sig = auth_parts[3][:512]
        try:
            enc = base64.b64decode(auth_parts[1], validate=True)
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
        ts = int(payload.get("ts", 0)) #folosim Unix timestamps
        cn_check = bytes.fromhex(str(payload.get("client_nonce", "")) or "")
        sn_check = bytes.fromhex(str(payload.get("server_nonce", "")) or "")

        # Server-side dev_mode (ONLY localhost dev): bypass all license checks.
        dev_mode = bool(self.cfg.get("dev_mode", False))

        #FARA DEV
        if not dev_mode:
            # Step 1: structural sanity check
            if not license_is_well_formed(license_key):
                writer.write(b"AUTH_FAIL\n")
                await writer.drain()
                log.info("AUTH bad license format from %s", peer)
                return None
            # Step 2: RSA signature over "license|machine_fp" (if pubkey configured).
            if self.license_pub is not None:
                if not license_verify.verify_license_signature(
                        self.license_pub, license_key, machine_fp, license_sig):
                    writer.write(b"AUTH_FAIL\n")
                    await writer.drain()
                    log.info("AUTH bad signature from %s (license=%s)", peer, license_key)
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

        #CU DEV

        if dev_mode:
            log.warning("dev_mode auth bypass accepted from %s", peer)
        delivery_key = hkdf(
            secret=license_key.encode("utf-8") + b"michi-license-pepper-v2",
            salt=server_nonce,
            info=b"michi-delivery-v2",
            length=32,
        )
        session_key = os.urandom(32)
        enc_session = AESGCM(delivery_key).encrypt(
            client_nonce[:12], session_key, b"MichiMpSession"
        )
        writer.write(b"AUTH_OK " + base64.b64encode(enc_session) + b"\n")
        await writer.drain()

        return session_key, payload

    #Per-client lifecycle
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

        session_key, payload = handshake

        player = PlayerState(player_id=self._allocate_id())
        player.name = sanitize_name(payload.get("name", ""), "Player")
        cls_raw = str(payload.get("class", "Fighter"))[:MAX_CLASS_LEN]
        player.player_class = "".join(c for c in cls_raw if c.isalnum()) or "Fighter"
        player.map_id = self.active_map_id

        # Spawn arbitrar din TMX
        spawn_col, spawn_row = self.world.default_spawn
        player.x = spawn_col * self.world.tilewidth
        player.y = spawn_row * self.world.tileheight
        player.last_valid_x = player.x
        player.last_valid_y = player.y

        client = ClientConnection(reader, writer, session_key, player)
        client.start_writer()
        self.clients[player.player_id] = client
        log.info("Player %d (%s) joined from %s — %d/%d",
                 player.player_id, player.name, ip,
                 len(self.clients), self.max_players)

        # 1) Welcome (id, existing players, spawn)
        existing = [c.player.to_dict() for pid, c in self.clients.items()
                    if pid != player.player_id]
        client.send_json({
            "type": "welcome",
            "id": player.player_id,
            "players": existing,
            "spawn_x": player.x,
            "spawn_y": player.y,
            "map_id": self.active_map_id,
        })

        # 2) World info — sent immediately so the client can begin loading
        #    its TMX skeleton and start requesting chunks. The skeleton is
        #    typically a few KB; chunks follow on demand.
        client.send_json(self.world.info_message())

        # Notify others — include current position so joining clients can
        # render the new player immediately without waiting for the first tick.
        self.broadcast_json({
            "type": "player_join",
            "id": player.player_id,
            "name": player.name,
            "class": player.player_class,
            "x": player.x,
            "y": player.y,
        }, exclude_id=player.player_id)

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
                    self._handle_move(client, msg)

                elif t == "chunk_request":
                    self._handle_chunk_request(client, msg)

                elif t == "world_ready":
                    # Client has finished loading the skeleton TMX; record it.
                    log.info("Player %d ready in world '%s'",
                             player.player_id, player.map_id)

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

                elif t == "mob_damage":
                    self._handle_mob_damage(client, msg)

                elif t == "mob_death":
                    self._handle_mob_death(client, msg)

                else:
                    # Ignore unknown messages, do not punish — protocol may grow
                    log.debug("Unknown msg type from pid=%s: %s",
                              player.player_id, t)

        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
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

    # ── Tick loop (broadcasts positions + drains chunk requests) ──
    async def tick_loop(self) -> None:
        interval = 1.0 / TICK_RATE
        max_chunks = max(1, int(self.cfg.get("max_chunks_per_tick",
                                             DEFAULT_MAX_CHUNKS_PER_TICK)))
        while self._running:
            start = time.monotonic()
            # Player position broadcast
            for pid, client in list(self.clients.items()):
                update = client.player.to_update_dict()
                for other_pid, other_client in self.clients.items():
                    if other_pid != pid:
                        other_client.send_json(update)
            #stergem chunk-ul din coada, limitam rata pentru a opri un singur client de a da flood la toata coada criptata 
            for pid, client in list(self.clients.items()):
                self._drain_chunks(client, max_chunks)
            elapsed = time.monotonic() - start
            await asyncio.sleep(max(0.0, interval - elapsed))

    #Authoritative move validation
    def _handle_move(self, client: "ClientConnection", msg: dict) -> None:
        player = client.player
        world = self.world
        max_step_px = int(self.cfg.get("max_tile_step_per_move",
                                       DEFAULT_MAX_TILE_STEP)) * world.tilewidth

        new_x = clamp_int(msg.get("x"), 0, world.width  * world.tilewidth  - 1, player.x)
        new_y = clamp_int(msg.get("y"), 0, world.height * world.tileheight - 1, player.y)

        #Anti-teleport: cap the per-packet displacement.
        dx = new_x - player.last_valid_x
        dy = new_y - player.last_valid_y
        if abs(dx) > max_step_px or abs(dy) > max_step_px:
            new_x = player.last_valid_x + clamp_int(dx, -max_step_px, max_step_px, 0)
            new_y = player.last_valid_y + clamp_int(dy, -max_step_px, max_step_px, 0)
            log.debug("Player %d step exceeded cap; clamped", player.player_id)

        #Collision: validate the destination box.
        hb_w = int(self.cfg.get("player_hitbox_w", DEFAULT_HITBOX_W))
        hb_h = int(self.cfg.get("player_hitbox_h", DEFAULT_HITBOX_H))
        #Player coords are top-left of sprite; hitbox is centred horizontally
        #and aligned to the bottom 75% of the sprite (matches client default).
        hb_off_x = (world.tilewidth - hb_w) // 2
        hb_off_y = world.tileheight - hb_h
        bx = new_x + hb_off_x
        by = new_y + hb_off_y
        if not world.is_box_walkable(bx, by, hb_w, hb_h):
            # Snap back to last valid pos and notify the client.
            new_x, new_y = player.last_valid_x, player.last_valid_y
            client.send_json({
                "type": "pos_correction",
                "x": new_x, "y": new_y,
                "reason": "collision",
            })

        # Triggers — only fire on freshly-entered rectangles.
        triggers = world.find_triggers(
            player.last_valid_x + hb_off_x, player.last_valid_y + hb_off_y,
            new_x + hb_off_x, new_y + hb_off_y,
            hb_w, hb_h,
        )
        for trig in triggers:
            self._dispatch_trigger(client, trig)

        player.x = new_x
        player.y = new_y
        player.last_valid_x = new_x
        player.last_valid_y = new_y
        player.direction = clamp_int(msg.get("dir"), 0, 7, player.direction)
        player.sprite_num = clamp_int(msg.get("sprite"), 0, 32, player.sprite_num)
        player.attacking = bool(msg.get("attacking", False))
        player.life = clamp_int(msg.get("life"), 0, MAX_LIFE_CAP, player.life)
        player.max_life = clamp_int(msg.get("maxLife"), 1, MAX_LIFE_CAP, player.max_life)

    def _dispatch_trigger(self, client: "ClientConnection", trig) -> None:
        """Send a trigger event to the client. Server enforces map
        transitions to known maps; everything else is informational."""
        out = {
            "type": "trigger",
            "id": trig.obj_id,
            "name": trig.name,
            "trigger_type": trig.obj_type,
            "x": trig.x, "y": trig.y,
            "w": trig.w, "h": trig.h,
            "props": trig.properties,
        }
        client.send_json(out)
        # If this is a map-transition pointing to a map we host, broadcast
        # the change for the player and load it server-side.
        target = trig.properties.get("targetMap") or trig.properties.get("target_map")
        if target:
            target_id = str(target).lower()
            target_world = self.maps.get(target_id)
            if target_world is not None:
                self._move_player_to_map(client, target_world, trig)

    def _move_player_to_map(self, client: "ClientConnection", new_world: TmxMap, trig) -> None:
        """
        Switch this player's authoritative world. We do NOT change the
        server's globally active map: a single MP server hosts ONE world at a
        time in this version. Cross-map transitions are recognised but the
        client is informed via a 'map_change' message and is expected to
        disconnect / reconnect to the appropriate server. (Multi-world per
        server is a future extension.)
        """
        client.send_json({
            "type": "map_change",
            "map_id": new_world.map_id,
            "reason": "trigger",
            "trigger_id": trig.obj_id,
        })
        log.info("Player %d hit cross-map trigger -> %s",
                 client.player.player_id, new_world.map_id)

    #Mob synchronization handlers
    def _handle_mob_damage(self, client: "ClientConnection", msg: dict) -> None:
        """Process mob damage from a client and broadcast to all other clients."""
        mob_id = clamp_int(msg.get("mob_id"), 0, 999, -1)
        damage = clamp_int(msg.get("damage"), 0, 9999, 0)
        life = clamp_int(msg.get("life"), 0, 9999, 0)
        max_life = clamp_int(msg.get("max_life"), 1, 9999, 1)
        mob_type = str(msg.get("mob_type", "unknown"))[:32]

        if mob_id < 0 or damage <= 0:
            return

        # Get or create mob state
        mob = self.mobs.get(mob_id)
        if mob is None:
            mob = MobState(mob_id=mob_id, mob_type=mob_type, life=max_life, max_life=max_life)
            self.mobs[mob_id] = mob

        # Update mob state from client report
        mob.life = life
        mob.last_attacker_pid = client.player.player_id

        # Broadcast damage to all other clients
        self.broadcast_json({
            "type": "mob_damage",
            "mob_id": mob_id,
            "life": life,
            "max_life": max_life,
            "damage": damage,
            "attacker_pid": client.player.player_id,
            "mob_type": mob.mob_type,
        }, exclude_id=client.player.player_id)

    def _handle_mob_death(self, client: "ClientConnection", msg: dict) -> None:
        """Process mob death from a client and broadcast to all other clients."""
        mob_id = clamp_int(msg.get("mob_id"), 0, 999, -1)
        mob_type = str(msg.get("mob_type", "unknown"))[:32]

        if mob_id < 0:
            return

        # Update mob state
        mob = self.mobs.get(mob_id)
        if mob is None:
            mob = MobState(mob_id=mob_id, mob_type=mob_type, life=0, max_life=1)
            self.mobs[mob_id] = mob

        mob.alive = False
        mob.dying = True
        mob.life = 0
        mob.last_attacker_pid = client.player.player_id

        # Broadcast death to all clients (including sender for confirmation)
        self.broadcast_json({
            "type": "mob_death",
            "mob_id": mob_id,
            "killer_pid": client.player.player_id,
            "mob_type": mob.mob_type,
        })

        # Clean up dead mob after a delay (keep for respawn logic)
        # For now, just mark as dead; respawn logic can be added later
        log.debug("Mob %d (%s) killed by player %d", mob_id, mob.mob_type, client.player.player_id)

    #Chunk delivery
    def _handle_chunk_request(self, client: "ClientConnection", msg: dict) -> None:
        """Queue a (layer_idx, cx, cy) request for the per-player drainer."""
        layer_idx = clamp_int(msg.get("layer_idx"), 0, len(self.world.layers) - 1, -1)
        cx = clamp_int(msg.get("cx"), 0, self.world.num_chunks_x - 1, -1)
        cy = clamp_int(msg.get("cy"), 0, self.world.num_chunks_y - 1, -1)
        if layer_idx < 0 or cx < 0 or cy < 0:
            return
        key = (layer_idx, cx, cy)
        if key in client.player.chunks_sent:
            return
        # Cap the queue length to prevent a malicious client from making the
        # server hold an unbounded backlog.
        if len(client.player.chunk_requests) >= 4096:
            return
        client.player.chunk_requests.append(key)

    def _drain_chunks(self, client: "ClientConnection", max_per_tick: int) -> None:
        sent = 0
        q = client.player.chunk_requests
        while q and sent < max_per_tick:
            key = q.popleft()
            if key in client.player.chunks_sent:
                continue
            payload = self.world.get_chunk(*key)
            if payload is None:
                continue
            payload["type"] = "chunk"
            client.send_json(payload)
            client.player.chunks_sent.add(key)
            sent += 1

    #Server start/stop
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


#STARTUP MESSAGES and helpers
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
        log.error("Run generate_keys.py (in SERVERS/save_server/) and copy "
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
    p.add_argument("--max-players", type=int, default=MAX_PLAYERS,
                   help=f"Maximum concurrent players (default {MAX_PLAYERS}).")
    p.add_argument("--private-key", default=DEFAULT_PRIVATE_KEY,
                   help="Path to RSA private key PEM.")
    p.add_argument("--no-prompt", action="store_true",
                   help="Do not prompt; require --host and --port (or env).")
    return p.parse_args()


async def amain(host: str, port: int, max_players: int, private_key, cfg: dict) -> None:
    nonce_cache = NonceCache()
    rate_limiter = IpRateLimiter(cfg.get("rate_limit_per_ip_per_minute", RATE_LIMIT_PER_IP_PER_MIN))

    # Load the map collection. Failure here is fatal — a multiplayer server
    # without a world is meaningless.
    maps_dir = Path(cfg.get("maps_dir", DEFAULT_MAPS_DIR))
    if not maps_dir.is_absolute():
        maps_dir = BASE_DIR / maps_dir
    chunk_size = int(cfg.get("chunk_size_tiles", DEFAULT_CHUNK_SIZE))
    declared_maps = cfg.get("maps", {}) or {}
    map_collection = await asyncio.get_event_loop().run_in_executor(
        None, lambda: MapCollection(maps_dir, chunk_size, declared_maps),
    )
    active_map_id = (cfg.get("active_map") or "").lower().strip()
    if not active_map_id:
        # Fall back to the first map in alphabetical order so the server
        # still boots in a development environment without an explicit pick.
        active_map_id = map_collection.list_ids()[0]
        log.warning("active_map not set in mp_config.json — defaulting to '%s'",
                    active_map_id)

    # Load license public key. Stored on GameServer so the per-connection
    # handshake can reach it. No allow-list — RSA signature is the sole trust.
    license_pub = license_verify.load_public_key(cfg.get("license_public_key_b64", ""))
    if license_pub is None and not cfg.get("dev_mode", False):
        log.warning(
            "license_public_key_b64 is unset/placeholder — signature verification "
            "DISABLED. Set it in mp_config.json for production."
        )
    if cfg.get("dev_mode", False):
        log.warning("dev_mode=True — ALL license checks bypassed. Do NOT use in production.")

    server = GameServer(
        host=host, port=port, max_players=max_players,
        private_key=private_key, nonce_cache=nonce_cache,
        rate_limiter=rate_limiter, cfg=cfg,
        maps=map_collection, active_map_id=active_map_id,
    )
    server.license_pub = license_pub

    def shutdown_handler():
        log.info("Shutdown signal received...")
        asyncio.create_task(server.stop())

    _loop = asyncio.get_event_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            _loop.add_signal_handler(sig, shutdown_handler)
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

    max_players = args.max_players if args.max_players != MAX_PLAYERS \
        else cfg.get("max_players", MAX_PLAYERS)

    log.info("Starting on %s:%d (key=%s, max_players=%d)",
             host, port, pk_path, max_players)

    try:
        asyncio.run(amain(host, port, max_players, private_key, cfg))
    except KeyboardInterrupt:
        log.info("Server stopped.")


if __name__ == "__main__":
    main()

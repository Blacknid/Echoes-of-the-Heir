#!/usr/bin/env python3
"""
Michi's Adventure - Multiplayer Server v2

Handshake (first message C->S):
    C -> "HELLO v2 <base64(client_nonce_16)>"
    S -> "OK <base64(server_nonce_16)>"
    C -> "AUTH <base64(rsa_oaep_sha256(handshake_json))> <machine_fp> <sig_b64>"
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
CONFIG_PATH = BASE_DIR / "mp_config.json"

PROTOCOL_TAG = "v2"
HANDSHAKE_TS_WINDOW = 60
NONCE_REPLAY_WINDOW = 300

DEFAULT_PORT = 7777
DEFAULT_PRIVATE_KEY = "server_private_key.pem"
DEFAULT_MAPS_DIR = "maps"
DEFAULT_CHUNK_SIZE = 32
DEFAULT_MAX_CHUNKS_PER_TICK = 4

DEFAULT_MAX_TILE_STEP = 4

DEFAULT_HITBOX_W = 24
DEFAULT_HITBOX_H = 24

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


INITIAL_CONFIG = {
    "host": None,
    "port": None,
    "private_key_path": DEFAULT_PRIVATE_KEY,
    "license_public_key_b64": "REPLACE_WITH_PUBLIC_KEY_FROM_generate_license_keys.py",
    "dev_mode": False,
    "max_players": MAX_PLAYERS,
    "rate_limit_per_ip_per_minute": RATE_LIMIT_PER_IP_PER_MIN,

    "maps_dir": DEFAULT_MAPS_DIR,
    "active_map": None,
    "chunk_size_tiles": DEFAULT_CHUNK_SIZE,
    "max_chunks_per_tick": DEFAULT_MAX_CHUNKS_PER_TICK,
    "max_tile_step_per_move": DEFAULT_MAX_TILE_STEP,
    "player_hitbox_w": DEFAULT_HITBOX_W,
    "player_hitbox_h": DEFAULT_HITBOX_H,
    "maps": {},

    "dashboard_port": 8888,
    "admin_password": "",
    "saves_db_path": "",
}

# Characters allowed in player display names.
ALLOWED_NAME = set(
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789_-. "
)

import re as _re
import socket as _socket

_USERNAME_RE = _re.compile(r"^[A-Za-z0-9_\-]{1,20}$")

def _username_valid(name: str) -> bool:
    return isinstance(name, str) and bool(_USERNAME_RE.match(name))


def _save_server_check_username(cfg: dict, private_key, license_key: str,
                                username: str) -> str:
    """Returns "AVAILABLE", "TAKEN", "INVALID", or "SKIP"."""
    return _save_server_internal(cfg, "CHECK_USERNAME", license_key, username)


def _save_server_claim_username(cfg: dict, private_key, license_key: str,
                                username: str) -> str:
    """Returns "CLAIMED", "TAKEN", "INVALID", or "SKIP"."""
    return _save_server_internal(cfg, "CLAIM_USERNAME", license_key, username)


def _save_server_internal(cfg: dict, cmd: str, license_key: str, username: str,
                           timeout: float = 5.0) -> str:
    """Send a single internal command to the save server.

    Both servers must share the same "internal_api_key" in their configs.
    Returns "SKIP" if save_server_host/port/internal_api_key aren't set.
    Runs in a thread-pool executor so it doesn't block the event loop.
    """
    host = cfg.get("save_server_host")
    port = cfg.get("save_server_port")
    api_key = cfg.get("internal_api_key", "")
    if not host or not port or not api_key:
        return "SKIP"

    try:
        port = int(port)
        payload = json.dumps({"cmd": cmd, "license": license_key, "username": username})
        line = f"INTERNAL {api_key} {payload}\n"

        with _socket.create_connection((host, port), timeout=timeout) as sock:
            sock.settimeout(timeout)
            sock.sendall(line.encode("utf-8"))
            buf = b""
            while True:
                c = sock.recv(1)
                if not c or c == b"\n":
                    break
                buf += c
                if len(buf) > 4096:
                    break
            resp = json.loads(buf.decode("utf-8"))
            return resp.get("status", "ERROR")
    except Exception as exc:
        log.warning("save_server internal %s failed: %s", cmd, exc)
        return "SKIP"


def load_config() -> dict:
    cfg = dict(INITIAL_CONFIG)
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


logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("michi-mp")


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


class NonceCache:
    """Keeps seen nonces for NONCE_REPLAY_WINDOW seconds to reject replays."""
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


# Default starting stats — must match Player.java setDefaultValues()
DEFAULT_LEVEL       = 1
DEFAULT_MAX_LIFE    = 4
DEFAULT_STRENGTH    = 2
DEFAULT_DEXTERITY   = 1
DEFAULT_MAX_MANA    = 3
DEFAULT_EXP         = 0
DEFAULT_NEXT_LVL    = 5
DEFAULT_COIN        = 0
DEFAULT_SKILL_PTS   = 100


@dataclass
class PlayerState:
    player_id: int
    name: str = "Player"
    player_class: str = "Fighter"
    license_key: str = ""
    x: int = 0
    y: int = 0
    direction: int = 0
    sprite_num: int = 1
    attacking: bool = False
    level: int = DEFAULT_LEVEL
    life: int = DEFAULT_MAX_LIFE
    max_life: int = DEFAULT_MAX_LIFE
    strength: int = DEFAULT_STRENGTH
    dexterity: int = DEFAULT_DEXTERITY
    mana: int = DEFAULT_MAX_MANA
    max_mana: int = DEFAULT_MAX_MANA
    exp: int = DEFAULT_EXP
    next_level_exp: int = DEFAULT_NEXT_LVL
    coin: int = DEFAULT_COIN
    skill_points: int = DEFAULT_SKILL_PTS
    last_seen: float = field(default_factory=time.time)
    connect_time: float = field(default_factory=time.time)
    map_id: str = ""
    chunks_sent: set = field(default_factory=set)
    chunk_requests: collections.deque = field(default_factory=collections.deque)
    last_valid_x: int = 0
    last_valid_y: int = 0
    vx: float = 0.0  # velocity in px/tick at last broadcast
    vy: float = 0.0

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
            "vx": round(self.vx, 2), "vy": round(self.vy, 2),
        }

    def stats_to_dict(self) -> dict:
        return {
            "level":        self.level,
            "life":         self.life,
            "maxLife":      self.max_life,
            "strength":     self.strength,
            "dexterity":    self.dexterity,
            "mana":         self.mana,
            "maxMana":      self.max_mana,
            "exp":          self.exp,
            "nextLevelExp": self.next_level_exp,
            "coin":         self.coin,
            "skillPoints":  self.skill_points,
        }

    def load_from_dict(self, d: dict) -> None:
        self.level         = clamp_int(d.get("level"),         1,    9999, DEFAULT_LEVEL)
        self.max_life      = clamp_int(d.get("maxLife"),       1,    9999, DEFAULT_MAX_LIFE)
        self.life          = clamp_int(d.get("life"),          0,    self.max_life, self.max_life)
        self.strength      = clamp_int(d.get("strength"),      1,    9999, DEFAULT_STRENGTH)
        self.dexterity     = clamp_int(d.get("dexterity"),     1,    9999, DEFAULT_DEXTERITY)
        self.max_mana      = clamp_int(d.get("maxMana"),       1,    9999, DEFAULT_MAX_MANA)
        self.mana          = clamp_int(d.get("mana"),          0,    self.max_mana, self.max_mana)
        self.exp           = clamp_int(d.get("exp"),           0,    999999999, DEFAULT_EXP)
        self.next_level_exp= clamp_int(d.get("nextLevelExp"),  1,    999999999, DEFAULT_NEXT_LVL)
        self.coin          = clamp_int(d.get("coin"),          0,    999999999, DEFAULT_COIN)
        self.skill_points  = clamp_int(d.get("skillPoints"),   0,    9999,      DEFAULT_SKILL_PTS)


@dataclass
class MobState:
    mob_id: int
    mob_type: str
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
        self.maps = maps
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
        self.mobs: dict[int, MobState] = {}
        self._bans: dict[str, float] = {}
        self._bans_lock = asyncio.Lock()
        self._player_data_path = BASE_DIR / "player_data.json"
        self._player_data: dict[str, dict] = self._load_player_data()
        self._player_data_lock = asyncio.Lock()

    def _allocate_id(self) -> int:
        pid = self._next_id
        self._next_id += 1
        return pid

    def _load_player_data(self) -> dict:
        try:
            if self._player_data_path.exists():
                return json.loads(self._player_data_path.read_text(encoding="utf-8"))
        except Exception as exc:
            log.warning("Could not load player_data.json: %s", exc)
        return {}

    async def _save_player_data(self) -> None:
        async with self._player_data_lock:
            try:
                self._player_data_path.write_text(
                    json.dumps(self._player_data, indent=2), encoding="utf-8"
                )
            except Exception as exc:
                log.warning("Could not save player_data.json: %s", exc)

    def _apply_saved_stats(self, player: PlayerState) -> None:
        saved = self._player_data.get(player.license_key)
        if saved:
            player.load_from_dict(saved)

    async def _persist_stats(self, player: PlayerState) -> None:
        self._player_data[player.license_key] = player.stats_to_dict()
        await self._save_player_data()

    def broadcast_json(self, obj: dict, exclude_id: int = -1) -> None:
        for pid, client in self.clients.items():
            if pid != exclude_id:
                client.send_json(obj)

    async def is_banned(self, license_key: str) -> bool:
        async with self._bans_lock:
            exp = self._bans.get(license_key)
            if exp is None:
                return False
            if exp == 0 or time.time() < exp:
                return True
            del self._bans[license_key]
            return False

    async def admin_ban(self, target: str, duration_seconds: int) -> str:
        """Ban by player name or id. duration_seconds=0 means permanent."""
        client = self._find_client(target)
        if client is None:
            return f"No player found: {target!r}"
        exp = 0 if duration_seconds <= 0 else time.time() + duration_seconds
        async with self._bans_lock:
            self._bans[client.player.license_key] = exp
        reason = f"Banned for {duration_seconds}s" if exp else "Permanently banned"
        client.send_json({"type": "kick", "reason": reason})
        await client.close()
        return f"Banned {client.player.name} (license={client.player.license_key}) — {reason}"

    async def admin_kick(self, target: str, reason: str = "Kicked by admin") -> str:
        client = self._find_client(target)
        if client is None:
            return f"No player found: {target!r}"
        client.send_json({"type": "kick", "reason": reason})
        await client.close()
        return f"Kicked {client.player.name}"

    def admin_teleport(self, target: str, col: int, row: int) -> str:
        client = self._find_client(target)
        if client is None:
            return f"No player found: {target!r}"
        tile_w = self.world.tilewidth
        tile_h = self.world.tileheight
        new_x = col * tile_w
        new_y = row * tile_h
        client.player.x = new_x
        client.player.y = new_y
        client.player.last_valid_x = new_x
        client.player.last_valid_y = new_y
        client.send_json({"type": "pos_correction", "x": new_x, "y": new_y, "reason": "admin_teleport"})
        return f"Teleported {client.player.name} to col={col} row={row}"

    def admin_list(self) -> str:
        if not self.clients:
            return "No players connected."
        lines = [f"  id={pid} name={c.player.name!r} map={c.player.map_id}"
                 for pid, c in self.clients.items()]
        return "\n".join(lines)

    def _find_client(self, target: str) -> "Optional[ClientConnection]":
        """Find by numeric id or name (case-insensitive prefix match)."""
        try:
            pid = int(target)
            return self.clients.get(pid)
        except ValueError:
            low = target.lower()
            for c in self.clients.values():
                if c.player.name.lower().startswith(low):
                    return c
            return None

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
        ts = int(payload.get("ts", 0))
        cn_check = bytes.fromhex(str(payload.get("client_nonce", "")) or "")
        sn_check = bytes.fromhex(str(payload.get("server_nonce", "")) or "")

        dev_mode = bool(self.cfg.get("dev_mode", False))

        if not dev_mode:
            if not license_is_well_formed(license_key):
                writer.write(b"AUTH_FAIL\n")
                await writer.drain()
                log.info("AUTH bad license format from %s", peer)
                return None
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

        if await self.is_banned(license_key):
            writer.write(b"BANNED\n")
            await writer.drain()
            log.info("AUTH rejected banned license %s from %s", license_key, peer)
            return None

        requested_name = str(payload.get("name", ""))[:MAX_NAME_LEN]
        if _username_valid(requested_name):
            username_status = await asyncio.get_event_loop().run_in_executor(
                None,
                lambda: _save_server_check_username(self.cfg, self.private_key,
                                                    license_key, requested_name)
            )
            if username_status == "TAKEN":
                writer.write(b"USERNAME_TAKEN\n")
                await writer.drain()
                log.info("AUTH rejected: username %r already taken (license=%s from %s)",
                         requested_name, license_key, peer)
                return None
            if username_status == "AVAILABLE":
                await asyncio.get_event_loop().run_in_executor(
                    None,
                    lambda: _save_server_claim_username(self.cfg, self.private_key,
                                                        license_key, requested_name)
                )

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
        player.license_key = str(payload.get("license", ""))[:32]
        cls_raw = str(payload.get("class", "Fighter"))[:MAX_CLASS_LEN]
        player.player_class = "".join(c for c in cls_raw if c.isalnum()) or "Fighter"
        player.map_id = self.active_map_id

        spawn_col, spawn_row = self.world.default_spawn
        hb_w = int(self.cfg.get("player_hitbox_w", DEFAULT_HITBOX_W))
        hb_h = int(self.cfg.get("player_hitbox_h", DEFAULT_HITBOX_H))
        spawn_col, spawn_row = self.world.safe_spawn(spawn_col, spawn_row, hb_w, hb_h)
        player.x = spawn_col * self.world.tilewidth
        player.y = spawn_row * self.world.tileheight
        player.last_valid_x = player.x
        player.last_valid_y = player.y

        self._apply_saved_stats(player)

        client = ClientConnection(reader, writer, session_key, player)
        client.start_writer()
        self.clients[player.player_id] = client
        log.info("Player %d (%s) joined from %s — %d/%d",
                 player.player_id, player.name, ip,
                 len(self.clients), self.max_players)

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

        # Server is authoritative on stats — client must use these, not its singleplayer values.
        client.send_json({"type": "player_stats", **player.stats_to_dict()})

        client.send_json(self.world.info_message())

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
                    log.debug("Unknown msg type from pid=%s: %s",
                              player.player_id, t)

        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            if player.player_id in self.clients:
                del self.clients[player.player_id]
            await client.close()
            await self._persist_stats(player)
            log.info("Player %d (%s) disconnected — %d/%d",
                     player.player_id, player.name,
                     len(self.clients), self.max_players)
            self.broadcast_json({
                "type": "player_leave",
                "id": player.player_id,
            })

    async def tick_loop(self) -> None:
        interval = 1.0 / TICK_RATE
        max_chunks = max(1, int(self.cfg.get("max_chunks_per_tick",
                                             DEFAULT_MAX_CHUNKS_PER_TICK)))
        while self._running:
            start = time.monotonic()
            for pid, client in list(self.clients.items()):
                update = client.player.to_update_dict()
                for other_pid, other_client in self.clients.items():
                    if other_pid != pid:
                        other_client.send_json(update)
            for pid, client in list(self.clients.items()):
                self._drain_chunks(client, max_chunks)
            elapsed = time.monotonic() - start
            await asyncio.sleep(max(0.0, interval - elapsed))

    def _handle_move(self, client: "ClientConnection", msg: dict) -> None:
        player = client.player
        world = self.world
        max_step_px = int(self.cfg.get("max_tile_step_per_move",
                                       DEFAULT_MAX_TILE_STEP)) * world.tilewidth

        new_x = clamp_int(msg.get("x"), 0, world.width  * world.tilewidth  - 1, player.x)
        new_y = clamp_int(msg.get("y"), 0, world.height * world.tileheight - 1, player.y)

        dx = new_x - player.last_valid_x
        dy = new_y - player.last_valid_y
        if abs(dx) > max_step_px or abs(dy) > max_step_px:
            new_x = player.last_valid_x + clamp_int(dx, -max_step_px, max_step_px, 0)
            new_y = player.last_valid_y + clamp_int(dy, -max_step_px, max_step_px, 0)
            log.debug("Player %d step exceeded cap; clamped", player.player_id)

        hb_w = int(self.cfg.get("player_hitbox_w", DEFAULT_HITBOX_W))
        hb_h = int(self.cfg.get("player_hitbox_h", DEFAULT_HITBOX_H))
        # Hitbox is centred horizontally, aligned to the bottom 75% of the sprite.
        hb_off_x = (world.tilewidth - hb_w) // 2
        hb_off_y = world.tileheight - hb_h
        bx = new_x + hb_off_x
        by = new_y + hb_off_y
        if not world.is_box_walkable(bx, by, hb_w, hb_h):
            new_x, new_y = player.last_valid_x, player.last_valid_y
            client.send_json({
                "type": "pos_correction",
                "x": new_x, "y": new_y,
                "reason": "collision",
            })

        triggers = world.find_triggers(
            player.last_valid_x + hb_off_x, player.last_valid_y + hb_off_y,
            new_x + hb_off_x, new_y + hb_off_y,
            hb_w, hb_h,
        )
        for trig in triggers:
            self._dispatch_trigger(client, trig)

        # Compute velocity (px per server tick) from displacement since last accepted position.
        # Alpha-blended to smooth out jitter between move messages.
        alpha = 0.6
        player.vx = alpha * (new_x - player.x) + (1.0 - alpha) * player.vx
        player.vy = alpha * (new_y - player.y) + (1.0 - alpha) * player.vy

        player.x = new_x
        player.y = new_y
        player.last_valid_x = new_x
        player.last_valid_y = new_y
        player.direction = clamp_int(msg.get("dir"), 0, 7, player.direction)
        player.sprite_num = clamp_int(msg.get("sprite"), 0, 32, player.sprite_num)
        player.attacking = bool(msg.get("attacking", False))

    def _dispatch_trigger(self, client: "ClientConnection", trig) -> None:
        """Forward trigger data to the client; enforce map transitions on our side."""
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
        target = trig.properties.get("targetMap") or trig.properties.get("target_map")
        if target:
            target_id = str(target).lower()
            target_world = self.maps.get(target_id)
            if target_world is not None:
                self._move_player_to_map(client, target_world, trig)

    def _move_player_to_map(self, client: "ClientConnection", new_world: TmxMap, trig) -> None:
        """
        This server hosts one world at a time. Cross-map transitions tell the client to
        reconnect to the right server; multi-world per server is a future thing.
        """
        client.send_json({
            "type": "map_change",
            "map_id": new_world.map_id,
            "reason": "trigger",
            "trigger_id": trig.obj_id,
        })
        log.info("Player %d hit cross-map trigger -> %s",
                 client.player.player_id, new_world.map_id)

    def _handle_mob_damage(self, client: "ClientConnection", msg: dict) -> None:
        mob_id = clamp_int(msg.get("mob_id"), 0, 999, -1)
        damage = clamp_int(msg.get("damage"), 0, 9999, 0)
        life = clamp_int(msg.get("life"), 0, 9999, 0)
        max_life = clamp_int(msg.get("max_life"), 1, 9999, 1)
        mob_type = str(msg.get("mob_type", "unknown"))[:32]

        if mob_id < 0 or damage <= 0:
            return

        mob = self.mobs.get(mob_id)
        if mob is None:
            mob = MobState(mob_id=mob_id, mob_type=mob_type, life=max_life, max_life=max_life)
            self.mobs[mob_id] = mob

        mob.life = life
        mob.last_attacker_pid = client.player.player_id

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
        mob_id = clamp_int(msg.get("mob_id"), 0, 999, -1)
        mob_type = str(msg.get("mob_type", "unknown"))[:32]

        if mob_id < 0:
            return

        mob = self.mobs.get(mob_id)
        if mob is None:
            mob = MobState(mob_id=mob_id, mob_type=mob_type, life=0, max_life=1)
            self.mobs[mob_id] = mob

        mob.alive = False
        mob.dying = True
        mob.life = 0
        mob.last_attacker_pid = client.player.player_id

        self.broadcast_json({
            "type": "mob_death",
            "mob_id": mob_id,
            "killer_pid": client.player.player_id,
            "mob_type": mob.mob_type,
        })

        log.debug("Mob %d (%s) killed by player %d", mob_id, mob.mob_type, client.player.player_id)

    def _handle_chunk_request(self, client: "ClientConnection", msg: dict) -> None:
        layer_idx = clamp_int(msg.get("layer_idx"), 0, len(self.world.layers) - 1, -1)
        cx = clamp_int(msg.get("cx"), 0, self.world.num_chunks_x - 1, -1)
        cy = clamp_int(msg.get("cy"), 0, self.world.num_chunks_y - 1, -1)
        if layer_idx < 0 or cx < 0 or cy < 0:
            return
        key = (layer_idx, cx, cy)
        if key in client.player.chunks_sent:
            return
        # Cap the backlog so a misbehaving client can't make us hold unbounded state.
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
        active_map_id = map_collection.list_ids()[0]
        log.warning("active_map not set in mp_config.json — defaulting to '%s'",
                    active_map_id)

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

    if cfg.get("admin_password"):
        from dashboard import AdminDashboard
        dash = AdminDashboard(server, cfg)
        await dash.start()
    else:
        log.warning("admin_password not set in mp_config.json — dashboard disabled")

    asyncio.create_task(_admin_console(server))

    try:
        await server.start()
    except asyncio.CancelledError:
        pass
    finally:
        await server.stop()


async def _admin_console(server: "GameServer") -> None:
    """Stdin loop for admin commands.

    Commands:
      list                           — show connected players
      kick  <name|id> [reason]       — disconnect a player
      ban   <name|id> <seconds>      — ban for N seconds (0 = permanent)
      teleport <name|id> <col> <row> — move player to tile col,row
      help                           — show this list
    """
    print("[Admin] Console ready. Type 'help' for commands.")
    loop = asyncio.get_event_loop()

    while True:
        try:
            line = await loop.run_in_executor(None, sys.stdin.readline)
        except Exception:
            break
        if not line:
            break
        line = line.strip()
        if not line:
            continue

        parts = line.split()
        cmd = parts[0].lower()

        try:
            if cmd == "help":
                print(
                    "  list\n"
                    "  kick  <name|id> [reason]\n"
                    "  ban   <name|id> <seconds>   (0 = permanent)\n"
                    "  teleport <name|id> <col> <row>\n"
                    "  help"
                )

            elif cmd == "list":
                print(server.admin_list())

            elif cmd == "kick":
                if len(parts) < 2:
                    print("Usage: kick <name|id> [reason]")
                    continue
                target = parts[1]
                reason = " ".join(parts[2:]) if len(parts) > 2 else "Kicked by admin"
                result = await server.admin_kick(target, reason)
                print(result)

            elif cmd == "ban":
                if len(parts) < 3:
                    print("Usage: ban <name|id> <seconds>  (0 = permanent)")
                    continue
                target = parts[1]
                try:
                    secs = int(parts[2])
                except ValueError:
                    print("seconds must be an integer")
                    continue
                result = await server.admin_ban(target, secs)
                print(result)

            elif cmd == "teleport":
                if len(parts) < 4:
                    print("Usage: teleport <name|id> <col> <row>")
                    continue
                target = parts[1]
                try:
                    col, row = int(parts[2]), int(parts[3])
                except ValueError:
                    print("col and row must be integers")
                    continue
                result = server.admin_teleport(target, col, row)
                print(result)

            else:
                print(f"Unknown command: {cmd!r}. Type 'help'.")

        except Exception as exc:
            print(f"[Admin] Error: {exc}")


def main() -> None:
    args = parse_args()
    cfg = load_config()

    pk_path = Path(args.private_key if args.private_key != DEFAULT_PRIVATE_KEY
                   else cfg.get("private_key_path", DEFAULT_PRIVATE_KEY))
    if not pk_path.is_absolute():
        pk_path = BASE_DIR / pk_path
    private_key = load_private_key(pk_path)

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

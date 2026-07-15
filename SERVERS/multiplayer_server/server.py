#!/usr/bin/env python3
"""
Michi's Adventure - Multiplayer Server v2

Handshake (first message C->S):
    C -> "HELLO v2 <base64(client_nonce_16)>"
    S -> "OK <base64(server_nonce_16)> <server_public_key_fingerprint_hex16>"
        The fingerprint is sha256(DER SubjectPublicKeyInfo of this server's RSA public key)[:16
        hex chars]. It lets the client detect "wrong server / stale embedded key" up front and
        report it distinctly from a real auth rejection, instead of failing opaquely at LOGIN.
    C -> "LOGIN <base64(rsa_oaep_sha256(handshake_json))> <activation_id> <base64(enc_blob)>"
        handshake_json = {
          "ts":           <unix epoch s>,
          "client_nonce": <hex>,
          "server_nonce": <hex>,
          "name":         "<player display name>",
          "class":        "<player class>"
        }
    S -> "AUTH_OK <base64(aesgcm(session_key, key=delivery_key,
                                 nonce=client_nonce[:12],
                                 aad='MichiMpSession'))>"

    There is no client-side license signing key. activation_id/enc_blob are
    exactly what save_server's ACTIVATE handshake handed the client on its
    first-ever run (see save_server/server.py). This server never talks to
    a database of licenses directly — it asks save_server to resolve the
    pair into a trusted license_key via the loopback-only internal
    VERIFY_ACTIVATION command (see _save_server_verify_activation).

    If that internal link itself is broken (save_server down, wrong host/port,
    mismatched internal_api_key) the server replies "LICENSE_SERVER_UNAVAILABLE\n"
    instead of "AUTH_FAIL\n", so it's not confused with a real bad license/ban.

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

from game_engine import GameEngine
from npc import NpcCatalog, NpcWorld, PlayerProgress
from world import MapCollection, TmxMap


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
    return _save_server_internal_raw(
        cfg, {"cmd": cmd, "license": license_key, "username": username}, timeout=timeout
    )


def _in_container() -> bool:
    """Best-effort: are we running inside a Docker container? Used only to make a fatal
    misconfiguration (loopback save_server_host) loud, so a false negative is harmless."""
    if os.path.exists("/.dockerenv"):
        return True
    try:
        with open("/proc/1/cgroup", "r", encoding="utf-8", errors="replace") as fh:
            return any(marker in fh.read() for marker in ("docker", "containerd", "kubepods"))
    except OSError:
        return False


class SaveServerUnreachable(Exception):
    """Raised when the mp<->save server internal link itself is broken (bad host/port/
    api_key, connection refused, timeout) as opposed to a clean reject from save_server.
    Kept distinct from "license invalid" so the client can be told to check server config
    instead of being told its license/account is bad."""


def _save_server_verify_activation(cfg: dict, activation_id: str, enc_blob_b64: str,
                                    timeout: float = 5.0) -> Optional[str]:
    """Resolve a client's (activation_id, enc_blob) into a trusted license_key via save_server's
    online-issued licenses table. Returns the license_key on success, or None if save_server
    cleanly rejected it (auth must fail — unlike the username helpers, there's no online license
    to trust without this call). Raises SaveServerUnreachable if the link itself is broken."""
    resp = _save_server_internal_raw(
        cfg, {"cmd": "VERIFY_ACTIVATION", "activation_id": activation_id, "enc_blob": enc_blob_b64},
        timeout=timeout, want_full_response=True,
    )
    if not isinstance(resp, dict) or resp.get("status") != "OK":
        return None
    return resp.get("license") or None


def _save_server_internal_raw(cfg: dict, payload: dict, timeout: float = 5.0,
                               want_full_response: bool = False):
    """Send a single internal JSON command to the save server over its loopback-only INTERNAL
    protocol. Both servers must share the same "internal_api_key" in their configs.
    Returns "SKIP" (or None if want_full_response) if save_server_host/port/internal_api_key
    aren't set. Raises SaveServerUnreachable if they're set but the connection/handshake itself
    fails (host down, wrong port, refused, timeout) — distinct from a clean reject.
    Runs in a thread-pool executor so it doesn't block the event loop.
    """
    host = cfg.get("save_server_host")
    port = cfg.get("save_server_port")
    api_key = cfg.get("internal_api_key", "")
    if not host or not port or not api_key:
        return None if want_full_response else "SKIP"

    try:
        port = int(port)
        line = f"INTERNAL {api_key} {json.dumps(payload)}\n"

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
            return resp if want_full_response else resp.get("status", "ERROR")
    except (OSError, _socket.timeout) as exc:
        log.error("save_server internal %s: link unreachable (%s:%s): %s",
                   payload.get("cmd"), host, port, exc)
        raise SaveServerUnreachable(str(exc)) from exc
    except Exception as exc:
        log.warning("save_server internal %s failed: %s", payload.get("cmd"), exc)
        return None if want_full_response else "SKIP"


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

    # Secrets from the environment (SERVERS/.env), never the committed config.
    cfg["internal_api_key"] = (os.environ.get("MICHI_INTERNAL_API_KEY", "").strip()
                               or cfg.get("internal_api_key", ""))
    cfg["admin_password"] = (os.environ.get("MICHI_ADMIN_PASSWORD", "").strip()
                             or cfg.get("admin_password", ""))
    cfg["save_server_host"] = (os.environ.get("MICHI_SAVE_SERVER_HOST", "").strip()
                               or cfg.get("save_server_host", ""))
    raw_save_port = os.environ.get("MICHI_SAVE_SERVER_PORT", "").strip()
    if raw_save_port:
        try:
            cfg["save_server_port"] = int(raw_save_port)
        except ValueError:
            pass
    return cfg


logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("michi-mp")


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
    # Everything an NPC condition can ask about this player (level, quests, bosses,
    # fragments, met-NPCs) plus their shop stock — held HERE so a client cannot claim
    # progress it doesn't have. See npc.PlayerProgress.
    progress: PlayerProgress = field(default_factory=PlayerProgress)
    # Instance id of the NPC this player is currently talking to / shopping with, set by
    # npc_interact. Buy/sell are only honoured against this NPC, so a client can't shop at
    # a vendor it never walked up to.
    active_npc_id: int = -1

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

    def progress_to_dict(self) -> dict:
        return self.progress.to_dict()

    def load_progress_from_dict(self, d: dict) -> None:
        self.progress.load_from_dict(d)
        # PlayerState.level is the authoritative one (it drives combat/XP); mirror it into
        # progress so "requiredLevel" states read the same number rather than a stale copy.
        self.progress.level = self.level

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
        self.progress.level = self.level


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
                 maps: MapCollection, active_map_id: str,
                 engine: "Optional[GameEngine]" = None):
        self.host = host
        # The authoritative gameplay engine (Java jar), or a no-op stand-in if it isn't running.
        # Its calls return None when unavailable, and every caller treats None as "no ruling,
        # fall back", so the server works with or without it. See game_engine.GameEngine.
        self.engine = engine or GameEngine(BASE_DIR / "engine.jar")
        self.port = port
        self.max_players = max_players
        self.private_key = private_key
        self.public_key_fingerprint = hashlib.sha256(
            private_key.public_key().public_bytes(
                encoding=serialization.Encoding.DER,
                format=serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        ).hexdigest()[:16]
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
        # NPCs are hosted here, not on the client: definitions come from this server's own
        # npcs.json and placements from the active map's "NPCs" objectgroup. The client is sent
        # only what it needs to draw them (see NpcInstance.spawn_message).
        self.npc_catalog = NpcCatalog()
        self.npc_world = NpcWorld(self.npc_catalog, self.world)
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
            # load_from_dict first: it sets level, which load_progress_from_dict mirrors.
            player.load_progress_from_dict(saved.get("progress", {}))

    async def _persist_stats(self, player: PlayerState) -> None:
        record = player.stats_to_dict()
        record["progress"] = player.progress_to_dict()
        self._player_data[player.license_key] = record
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
        writer.write(b"OK " + base64.b64encode(server_nonce)
                     + b" " + self.public_key_fingerprint.encode("ascii") + b"\n")
        await writer.drain()

        # Stage 2: LOGIN — the client proves it holds a license this deployment's save_server
        # issued online, by presenting the (activation_id, enc_blob) pair it was given at
        # ACTIVATE time. There is no client-side signing key anymore; save_server is asked to
        # decrypt enc_blob (it alone holds enc_key) and hand back the recovered license_key.
        line = await asyncio.wait_for(reader.readline(), timeout=10.0)
        if not line:
            return None
        text = line.decode("utf-8", errors="replace").rstrip("\r\n")
        if not text.startswith("LOGIN "):
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        # Format: "LOGIN <enc_b64> <activation_id> <enc_blob_b64>"
        auth_parts = text.split(" ")
        if len(auth_parts) != 4:
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None
        activation_id = auth_parts[2][:64]
        enc_blob_b64  = auth_parts[3][:256]
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
            log.info("LOGIN decrypt failed from %s: %s", peer, type(exc).__name__)
            writer.write(b"AUTH_FAIL\n")
            await writer.drain()
            return None

        ts = int(payload.get("ts", 0))
        cn_check = bytes.fromhex(str(payload.get("client_nonce", "")) or "")
        sn_check = bytes.fromhex(str(payload.get("server_nonce", "")) or "")

        dev_mode = bool(self.cfg.get("dev_mode", False))

        license_key = ""
        if not dev_mode:
            try:
                license_key = await asyncio.get_event_loop().run_in_executor(
                    None,
                    lambda: _save_server_verify_activation(self.cfg, activation_id, enc_blob_b64)
                )
            except SaveServerUnreachable:
                writer.write(b"LICENSE_SERVER_UNAVAILABLE\n")
                await writer.drain()
                log.error("LOGIN from %s could not be verified: save_server link is down. "
                          "Check save_server_host/port/internal_api_key in mp_config.json.", peer)
                return None
            if not license_key:
                writer.write(b"AUTH_FAIL\n")
                await writer.drain()
                log.info("LOGIN activation not verified from %s", peer)
                return None
        else:
            # dev_mode: no save_server round trip — use the activation_id itself so sessions
            # are still distinguishable from one another during local testing.
            license_key = f"DEV-{activation_id[:16]}"

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

        # NPCs are ours, not the client's. Send the presentation half of each placed NPC so
        # the client can draw it; its dialogue, its shop and which state it's in stay here
        # and are answered per-interaction (see _handle_npc_interact).
        for spawn in self.npc_world.spawn_messages():
            client.send_json(spawn)

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
                    await self._handle_mob_damage(client, msg)

                elif t == "mob_death":
                    await self._handle_mob_death(client, msg)

                elif t == "progress_sync":
                    self._handle_progress_sync(client, msg)

                elif t == "npc_interact":
                    self._handle_npc_interact(client, msg)

                elif t == "npc_leave":
                    player.active_npc_id = -1

                elif t == "shop_buy":
                    self._handle_shop_buy(client, msg)

                elif t == "shop_sell":
                    self._handle_shop_sell(client, msg)

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

        # Velocity from displacement, then apply quadratic air drag (F_D = k*v^2, a = -F_D/m)
        # matching the client physics: mass=60 kg, drag_k=1.44 (tuned pixel-space units).
        _PLAYER_MASS = 60.0
        _DRAG_K = 1.44
        raw_vx = float(new_x - player.x)
        raw_vy = float(new_y - player.y)
        speed = (raw_vx ** 2 + raw_vy ** 2) ** 0.5
        if speed > 0:
            drag_decel = _DRAG_K * speed * speed / _PLAYER_MASS
            damped_speed = max(0.0, speed - drag_decel)
            scale = damped_speed / speed
        else:
            scale = 0.0
        alpha = 0.6
        player.vx = alpha * raw_vx * scale + (1.0 - alpha) * player.vx
        player.vy = alpha * raw_vy * scale + (1.0 - alpha) * player.vy

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

    async def _handle_mob_damage(self, client: "ClientConnection", msg: dict) -> None:
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

        # Let the authoritative Java engine rule on the hit if it's running: it owns the mob's
        # real life pool (from the shared monster definitions) and decides how much damage a
        # claim actually does, so a modified client can't just declare a mob near-dead. If the
        # engine isn't available, fall back to trusting the client's reported life, exactly as
        # this server did before the engine existed.
        ruling = await self.engine.mob_hit(mob_id, mob_type, damage, client.player.player_id)
        if ruling is not None and "life" in ruling:
            life = clamp_int(ruling.get("life"), 0, 9999, life)
            max_life = clamp_int(ruling.get("maxLife"), 1, 9999, max_life)

        mob.life = life
        mob.max_life = max_life
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

        # If the engine says this hit killed the mob, drive the death here rather than waiting for
        # the client to claim it — the whole point is that the server, not the client, decides a
        # kill (and, later, awards the XP the ruling carries).
        if ruling is not None and ruling.get("killed"):
            await self._finish_mob_death(client, mob, ruling)

    async def _handle_mob_death(self, client: "ClientConnection", msg: dict) -> None:
        mob_id = clamp_int(msg.get("mob_id"), 0, 999, -1)
        mob_type = str(msg.get("mob_type", "unknown"))[:32]

        if mob_id < 0:
            return

        mob = self.mobs.get(mob_id)
        if mob is None:
            mob = MobState(mob_id=mob_id, mob_type=mob_type, life=0, max_life=1)
            self.mobs[mob_id] = mob

        # If the engine is authoritative, a client-claimed death is only honoured when the engine
        # agrees the mob is actually dead — otherwise a client can't announce a kill it didn't
        # earn. With no engine, the claim is trusted as before.
        if self.engine.available and mob.alive:
            log.debug("Ignoring unverified mob_death claim for mob %d from player %d",
                      mob_id, client.player.player_id)
            return

        await self._finish_mob_death(client, mob, None)

    async def _finish_mob_death(self, client: "ClientConnection", mob: "MobState",
                                ruling: "Optional[dict]") -> None:
        """Mark a mob dead once and tell everyone. Idempotent: a mob already broadcast as dead
        won't be announced twice (so the engine's kill and a client's later claim don't double up)."""
        if mob.dying and not mob.alive:
            return
        mob.alive = False
        mob.dying = True
        mob.life = 0
        mob.last_attacker_pid = client.player.player_id

        self.broadcast_json({
            "type": "mob_death",
            "mob_id": mob.mob_id,
            "killer_pid": client.player.player_id,
            "mob_type": mob.mob_type,
        })

        exp = ruling.get("exp") if ruling else None
        log.debug("Mob %d (%s) killed by player %d%s", mob.mob_id, mob.mob_type,
                  client.player.player_id,
                  f" (+{exp} xp, engine-ruled)" if exp else "")

    # =====================================================================
    #  SERVER-HOSTED NPCs
    # =====================================================================

    # An interaction is only honoured if the player is actually standing next to the NPC.
    # The client asks "let me talk to NPC 7" and we check that for ourselves — otherwise a
    # client could shop from across the map, or from a vendor it has never met.
    NPC_INTERACT_RANGE_PX = 160

    def _npc_in_reach(self, player: PlayerState, inst) -> bool:
        dx = player.x - inst.world_x
        dy = player.y - inst.world_y
        return dx * dx + dy * dy <= self.NPC_INTERACT_RANGE_PX ** 2

    def _handle_progress_sync(self, client: "ClientConnection", msg: dict) -> None:
        """The client reporting its quest/boss/fragment progress, which NPC states gate on
        (requiredQuestComplete, requiredBoss, requiredFragments, ...).

        Be clear about what this is and isn't. XP, quests and boss kills are still simulated on
        the client, so this is REPORTED progress, not verified progress: a modified client can
        claim it finished a quest and unlock the dialogue behind it. What it CANNOT do is give
        itself gold or items — coin balance and shop stock live on the server and are only ever
        changed by _handle_shop_buy/_handle_shop_sell, which never read anything from here.

        So the valuable half (economy) is authoritative today; closing the rest means moving
        quests and XP server-side too, which is a much bigger change than hosting the NPCs.
        """
        progress = client.player.progress

        quests = msg.get("quests")
        if isinstance(quests, dict):
            progress.quests = {str(k)[:64]: bool(v) for k, v in list(quests.items())[:256]}

        bosses = msg.get("bossesDefeated")
        if isinstance(bosses, list):
            progress.bosses_defeated = {
                b for b in (clamp_int(x, 0, 99, -1) for x in bosses[:32]) if b >= 0
            }

        fragments = msg.get("fragments")
        if isinstance(fragments, list):
            progress.fragments = {str(f)[:64] for f in fragments[:256]}

        met = msg.get("metNPCs")
        if isinstance(met, list):
            progress.met_npcs = {str(n)[:64] for n in met[:256]}

        progress.story_act = clamp_int(msg.get("storyAct"), 0, 99, progress.story_act)
        # Level is the server's own number (it drives combat), not the client's claim.
        progress.level = client.player.level

    def _handle_npc_interact(self, client: "ClientConnection", msg: dict) -> None:
        """The player walked up to an NPC and pressed talk. WE decide what it says: the
        activity state is resolved against this player's server-held progress, and the
        chosen dialogue lines are the only ones that ever go over the wire."""
        player = client.player
        npc_id = clamp_int(msg.get("npc_id"), 0, 99999, -1)
        inst = self.npc_world.get(npc_id)
        if inst is None:
            return
        if not self._npc_in_reach(player, inst):
            log.debug("pid=%s tried to interact with NPC %s out of range",
                      player.player_id, npc_id)
            # Answer anyway, with no lines. The client blocks further interacts on this NPC
            # until it hears back (NPC_Generic.awaitingServerDialogue), so staying silent here
            # would leave a legitimately-out-of-range player permanently unable to talk to it.
            client.send_json({"type": "npc_dialogue", "id": npc_id, "lines": []})
            return

        player.active_npc_id = npc_id
        # Level lives on PlayerState (it drives combat and is persisted with the stats); mirror
        # the current value in before resolving, so a "requiredLevel" state sees the real number
        # rather than whatever it was at login.
        player.progress.level = player.level
        state, lines = self.npc_world.dialogue_for(inst, player.progress)

        # A quest step on the client can ask for a specific named line ("thanks", "cave_done").
        # Honour it only if THIS NPC actually defines that key — the client names a set, it never
        # supplies text, so the worst a modified client can do is play one of this NPC's own
        # lines out of order.
        requested = str(msg.get("dialogue", ""))[:64]
        if requested:
            quest_lines = inst.definition.dialogue_lines(requested)
            if quest_lines:
                lines = quest_lines
            else:
                log.debug("pid=%s requested unknown dialogue %r on NPC %s",
                          player.player_id, requested, inst.object_id)

        if state is not None and state.marks_npc_met:
            player.progress.met_npcs.add(inst.object_id)

        out = {
            "type": "npc_dialogue",
            "id": npc_id,
            "lines": lines,
            "state": state.state_id if state is not None else "",
            "animation": (state.animation if state is not None else None) or "",
            "direction": state.direction if state is not None else -1,
            "stationary": bool(state.stationary) if state is not None else False,
        }
        # Where the state wants this NPC to stand (a blacksmith steps to the anvil once the
        # ore quest is done). Offsets are relative to the spawn tile, matching npcs.json.
        if state is not None:
            if state.pos_col >= 0 and state.pos_row >= 0:
                out["pos_col"] = state.pos_col
                out["pos_row"] = state.pos_row
            elif state.offset_col or state.offset_row:
                out["pos_col"] = inst.spawn_col + state.offset_col
                out["pos_row"] = inst.spawn_row + state.offset_row
        client.send_json(out)

        shop = self.npc_world.shop_message(inst, player.progress)
        if shop is not None:
            client.send_json(shop)

    def _active_shop_npc(self, client: "ClientConnection"):
        """The NPC this player is currently shopping with, or None. Buy/sell are only valid
        against the NPC the player opened via npc_interact and is still standing next to."""
        player = client.player
        if player.active_npc_id < 0:
            return None
        inst = self.npc_world.get(player.active_npc_id)
        if inst is None or inst.definition.shop is None:
            return None
        if not self._npc_in_reach(player, inst):
            return None
        return inst

    def _handle_shop_buy(self, client: "ClientConnection", msg: dict) -> None:
        player = client.player
        inst = self._active_shop_npc(client)
        if inst is None:
            client.send_json({"type": "shop_result", "ok": False,
                              "msg": "You're not at a shop."})
            return
        item_id = str(msg.get("itemId", ""))[:64]
        qty = clamp_int(msg.get("qty"), 1, 99, 1)

        ok, message = self.npc_world.buy(inst, player.progress, player, item_id, qty)
        client.send_json({"type": "shop_result", "ok": ok, "msg": message,
                          "action": "buy", "itemId": item_id, "qty": qty if ok else 0})
        if ok:
            # Gold and stock both changed server-side — push the truth back rather than
            # letting the client compute it. This is the whole point of the exercise.
            client.send_json({"type": "player_stats", **player.stats_to_dict()})
            shop = self.npc_world.shop_message(inst, player.progress)
            if shop is not None:
                client.send_json(shop)
            log.info("[SHOP] %s bought %dx %s from %s (coin=%d)",
                     player.name, qty, item_id, inst.object_id, player.coin)

    def _handle_shop_sell(self, client: "ClientConnection", msg: dict) -> None:
        player = client.player
        inst = self._active_shop_npc(client)
        if inst is None:
            client.send_json({"type": "shop_result", "ok": False,
                              "msg": "You're not at a shop."})
            return
        item_id = str(msg.get("itemId", ""))[:64]
        qty = clamp_int(msg.get("qty"), 1, 99, 1)

        ok, message, payout = self.npc_world.sell(inst, player.progress, player, item_id, qty)
        client.send_json({"type": "shop_result", "ok": ok, "msg": message,
                          "action": "sell", "itemId": item_id,
                          "qty": qty if ok else 0, "payout": payout})
        if ok:
            client.send_json({"type": "player_stats", **player.stats_to_dict()})
            log.info("[SHOP] %s sold %dx %s to %s for %d (coin=%d)",
                     player.name, qty, item_id, inst.object_id, payout, player.coin)

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
        if self.engine is not None:
            await self.engine.shutdown()


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

    if cfg.get("dev_mode", False):
        log.warning("dev_mode=True — ALL license checks bypassed. Do NOT use in production.")
    else:
        if not (cfg.get("save_server_host") and cfg.get("save_server_port")
                and cfg.get("internal_api_key")):
            log.error(
                "save_server_host/save_server_port/internal_api_key not fully set — this server "
                "cannot verify any client's online-issued license and every LOGIN will fail with "
                "LICENSE_SERVER_UNAVAILABLE. Set them in mp_config.json / SERVERS/.env."
            )
        # Running in a container and pointing the license link at loopback means pointing it at
        # OURSELVES — save_server is a different container. This exact misconfiguration is what
        # silently broke multiplayer: every LOGIN got LICENSE_SERVER_UNAVAILABLE. Fail loudly
        # rather than serve a game nobody can join.
        elif (_in_container()
              and str(cfg.get("save_server_host")) in ("127.0.0.1", "localhost", "::1")):
            log.error(
                "save_server_host is %r, but this server is running inside a container — "
                "loopback there means THIS container, not the save server, so every license "
                "verification will fail. Use the save service's network name (e.g. 'save').",
                cfg.get("save_server_host"),
            )
        # Verify the link actually works before we start accepting players, so a broken
        # deployment is obvious at boot instead of only when the first player tries to join.
        else:
            try:
                await asyncio.get_event_loop().run_in_executor(
                    None, lambda: _save_server_internal_raw(cfg, {"cmd": "PING_LINK"}, timeout=5.0)
                )
                log.info("save_server license link OK (%s:%s).",
                         cfg.get("save_server_host"), cfg.get("save_server_port"))
            except SaveServerUnreachable as exc:
                log.error(
                    "save_server license link is DOWN (%s:%s): %s — multiplayer logins will fail "
                    "until this is fixed.",
                    cfg.get("save_server_host"), cfg.get("save_server_port"), exc,
                )

    # Launch the authoritative Java gameplay engine (engine.jar). This never raises: if the jar
    # is missing or Java isn't installed, `launch` logs a warning and returns an unavailable
    # handle, and the server runs with client-reported gameplay exactly as it did before.
    engine_jar = cfg.get("engine_jar")
    engine = await GameEngine.launch(
        jar_path=Path(engine_jar) if engine_jar else None,
        java_bin=cfg.get("java_bin"),
    )

    server = GameServer(
        host=host, port=port, max_players=max_players,
        private_key=private_key, nonce_cache=nonce_cache,
        rate_limiter=rate_limiter, cfg=cfg,
        maps=map_collection, active_map_id=active_map_id,
        engine=engine,
    )

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

#!/usr/bin/env python3
"""
Michi's Adventure - Cloud Save Server v2

Wire format (newline-terminated):
  PING  ->  PONG

  Licensing is issued entirely online — there is no client-side signing key.
  A brand-new install has no license yet and calls ACTIVATE once; every later
  connection (from that same install) calls LOGIN with what ACTIVATE returned.

  First run (no local credentials yet):
    C -> "HELLO v2 <base64(client_nonce_16)>"
    S -> "OK <base64(server_nonce_16)>"
    C -> "ACTIVATE <base64(rsa_oaep_sha256(handshake_json))>"
         handshake_json = { "ts", "client_nonce" (hex), "server_nonce" (hex) }
    S -> "AUTH_OK <base64(aesgcm(session_key,...))> <activation_id> <base64(nonce_12||aesgcm(license_key, key=enc_key, aad='MichiLicenseBlob'))> <base64(aesgcm(license_key, key=delivery_key, nonce=cn[:12], aad='MichiIssuedLicense'))>"
      or "AUTH_FAIL"
    The server generates license_key + activation_id + enc_key and stores all
    three (license_key, activation_id, enc_key) in the `licenses` table.
    The client is told its own plaintext license_key exactly once, here
    (AEAD-wrapped in transit, never sent in the clear) — every later run
    instead recovers it via LOGIN, without the server ever repeating it.
    What the client PERSISTS to disk is only activation_id + the
    enc_key-encrypted blob; it never stores enc_key or (after this first
    run) the plaintext license_key.

  Every subsequent run:
    C -> "HELLO v2 <base64(client_nonce_16)>"
    S -> "OK <base64(server_nonce_16)>"
    C -> "LOGIN <base64(rsa_oaep_sha256(handshake_json))> <activation_id> <base64(enc_blob)>"
         handshake_json = { "ts", "client_nonce" (hex), "server_nonce" (hex) }
    S -> "AUTH_OK <base64(aesgcm(session_key, key=delivery_key, nonce=cn[:12], aad='MichiCloudSession'))>"
      or "AUTH_FAIL"
    The server looks up enc_key by activation_id, decrypts enc_blob to
    recover license_key, and verifies the decrypted value matches — proving
    the client holds a blob this server actually issued.

  Session frames (AEAD):
    wire = "DATA <base64(seq_8 || nonce_12 || ciphertext || tag_16)>"
    AAD  = direction_byte (0x01 S->C, 0x02 C->S) || seq_8_BE

  Commands (JSON inside AEAD):
    C -> { "cmd": "UPLOAD",   "data": <base64> }
    C -> { "cmd": "DOWNLOAD" }
    C -> { "cmd": "CLAIM_USERNAME", "username": "..." }
    C -> { "cmd": "CHECK_USERNAME", "username": "..." }
    C -> { "cmd": "SEND_FRIEND_REQUEST", "username": "..." }
    C -> { "cmd": "LIST_FRIEND_REQUESTS" }
    C -> { "cmd": "RESPOND_FRIEND_REQUEST", "username": "...", "accept": true|false }
    C -> { "cmd": "LIST_FRIENDS" }
    C -> { "cmd": "REMOVE_FRIEND", "username": "..." }
    C -> { "cmd": "GET_MY_FRIEND_ID" }
      Returns { "status": "OK", "friend_id": "..." } — an opaque per-account token issued
      alongside the claimed username, unrelated to license_key. This is what NFC add-friend
      taps exchange (core/src/main/java/platform, Android HCE) instead of the license key,
      so a sniffed/replayed NFC payload can only ever resolve to "send this username a friend
      request", never anything license-scoped.
    C -> { "cmd": "RESOLVE_FRIEND_ID", "friend_id": "..." }
      Returns { "status": "OK", "username": "..." } or { "status": "NOT_FOUND" }.
"""
from __future__ import annotations

import base64
import collections
import hashlib
import hmac
import json
import logging
import os
import secrets
import socket
import sqlite3
import struct
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "server_config.json"
SAVE_DIR = BASE_DIR / "saves"
LOG_PATH = BASE_DIR / "server.log"
DB_PATH = BASE_DIR / "saves.db"

PROTOCOL_TAG = "v2"
HANDSHAKE_TS_WINDOW = 60
NONCE_REPLAY_WINDOW = 300
MAX_LINE_BYTES = 16 * 1024 * 1024

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 5005,
    "private_key_path": "server_private_key.pem",
    # Set true ONLY on a dev/localhost instance. Currently unused by the online-activation
    # flow itself (every license is issued fresh by this server) but kept for any future
    # dev-only bypass needs.
    "dev_mode": False,
    "rate_limit_per_ip_per_minute": 30,
    "max_concurrent_connections": 200,
    "handshake_timeout_seconds": 10,
    "session_timeout_seconds": 30,
    "max_payload_bytes": 10 * 1024 * 1024,
}


def configure_logging() -> None:
    BASE_DIR.mkdir(parents=True, exist_ok=True)
    SAVE_DIR.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")

    fh = logging.FileHandler(str(LOG_PATH))
    fh.setFormatter(fmt)
    sh = logging.StreamHandler()
    sh.setFormatter(fmt)

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()
    root.addHandler(fh)
    root.addHandler(sh)


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        try:
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
        except Exception as exc:
            logging.warning("Could not parse %s: %s — using defaults", CONFIG_PATH, exc)
    # Allow overriding host/port via env (handy for systemd drop-ins)
    cfg["host"] = os.environ.get("MICHI_SAVE_HOST", cfg["host"])
    cfg["port"] = int(os.environ.get("MICHI_SAVE_PORT", cfg["port"]))
    return cfg


_private_key = None


def load_private_key(path: Path) -> None:
    global _private_key
    pem = path.read_bytes()
    _private_key = serialization.load_pem_private_key(pem, password=None)
    if _private_key.key_size < 2048:
        raise RuntimeError("RSA private key must be >= 2048 bits")


def rsa_oaep_decrypt(ct: bytes) -> bytes:
    return _private_key.decrypt(
        ct,
        rsa_padding.OAEP(
            mgf=rsa_padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


class IpRateLimiter:
    def __init__(self, max_per_minute: int):
        self.max_per_minute = max_per_minute
        self._buckets: dict[str, collections.deque] = collections.defaultdict(collections.deque)
        self._lock = threading.Lock()

    def allow(self, ip: str) -> bool:
        now = time.monotonic()
        cutoff = now - 60.0
        with self._lock:
            q = self._buckets[ip]
            while q and q[0] < cutoff:
                q.popleft()
            if len(q) >= self.max_per_minute:
                return False
            q.append(now)
            return True


class NonceCache:
    def __init__(self, ttl: int = NONCE_REPLAY_WINDOW):
        self.ttl = ttl
        self._seen: dict[bytes, float] = {}
        self._lock = threading.Lock()

    def check_and_store(self, nonce: bytes) -> bool:
        now = time.monotonic()
        with self._lock:
            cutoff = now - self.ttl
            if len(self._seen) > 4096:
                self._seen = {k: v for k, v in self._seen.items() if v >= cutoff}
            if nonce in self._seen and self._seen[nonce] >= cutoff:
                return False
            self._seen[nonce] = now
            return True


_db_lock = threading.Lock()


def init_db() -> None:
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("""
            CREATE TABLE IF NOT EXISTS saves (
                license_key    TEXT    PRIMARY KEY,
                save_data      BLOB    NOT NULL,
                game_timestamp INTEGER NOT NULL DEFAULT 0,
                size_bytes     INTEGER NOT NULL DEFAULT 0,
                updated_at     TEXT    NOT NULL
            )
        """)
        # Online activation: each row is one issued license. activation_id is the opaque
        # pointer the client stores and presents on every future LOGIN; enc_key is the
        # random AES-256 key (server-side only, never sent to the client) used to encrypt
        # license_key into the blob the client is given at ACTIVATE time and echoes back at
        # LOGIN time. Kept in its own table (not columns on `saves`) since a license can exist
        # before any save data does.
        con.execute("""
            CREATE TABLE IF NOT EXISTS licenses (
                license_key    TEXT    PRIMARY KEY,
                activation_id  TEXT    NOT NULL UNIQUE,
                enc_key_b64    TEXT    NOT NULL,
                created_at     TEXT    NOT NULL
            )
        """)
        con.execute("""
            CREATE TABLE IF NOT EXISTS events (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                ts          TEXT    NOT NULL,
                client_ip   TEXT    NOT NULL,
                license_key TEXT    NOT NULL,
                status      TEXT    NOT NULL
            )
        """)
        # One username per license, globally unique; claim_username() is atomic.
        con.execute("""
            CREATE TABLE IF NOT EXISTS usernames (
                username     TEXT PRIMARY KEY,
                license_key  TEXT NOT NULL UNIQUE,
                claimed_at   TEXT NOT NULL,
                friend_id    TEXT UNIQUE
            )
        """)
        # Older DBs created before friend_id existed — add the column if missing.
        existing_cols = {row[1] for row in con.execute("PRAGMA table_info(usernames)").fetchall()}
        if "friend_id" not in existing_cols:
            con.execute("ALTER TABLE usernames ADD COLUMN friend_id TEXT UNIQUE")
        # Friendship/request edges, keyed by the stable license_key identity (not username,
        # which can be re-claimed). requester_key is who sent the original request; status
        # transitions PENDING -> ACCEPTED (never re-created — see send_friend_request()).
        con.execute("""
            CREATE TABLE IF NOT EXISTS friends (
                license_key_a  TEXT NOT NULL,
                license_key_b  TEXT NOT NULL,
                requester_key  TEXT NOT NULL,
                status         TEXT NOT NULL,
                created_at     TEXT NOT NULL,
                PRIMARY KEY (license_key_a, license_key_b)
            )
        """)
        con.commit()
        con.close()


_LICENSE_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"


def _random_license_key() -> str:
    """Fresh `XXXXXXXX-YYYY` key, same shape the old offline issue_license.py used."""
    prefix = "".join(secrets.choice(_LICENSE_CHARSET) for _ in range(8))
    suffix = "".join(secrets.choice(_LICENSE_CHARSET) for _ in range(4))
    return f"{prefix}-{suffix}"


def create_license() -> tuple[str, str, bytes]:
    """
    Issue a brand-new license online: random license_key + activation_id + enc_key,
    persisted in the `licenses` table. Returns (license_key, activation_id, enc_key).
    """
    enc_key = os.urandom(32)
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            while True:
                license_key = _random_license_key()
                activation_id = secrets.token_urlsafe(24)
                try:
                    con.execute(
                        """INSERT INTO licenses (license_key, activation_id, enc_key_b64, created_at)
                           VALUES (?, ?, ?, ?)""",
                        (license_key, activation_id, base64.b64encode(enc_key).decode("ascii"),
                         time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())),
                    )
                    con.commit()
                    return license_key, activation_id, enc_key
                except sqlite3.IntegrityError:
                    continue  # extremely unlikely collision — retry with fresh random values
        finally:
            con.close()


def resolve_activation(activation_id: str) -> Optional[tuple[str, bytes]]:
    """Look up (license_key, enc_key) for a previously issued activation_id, or None."""
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            row = con.execute(
                "SELECT license_key, enc_key_b64 FROM licenses WHERE activation_id = ?",
                (activation_id,),
            ).fetchone()
        finally:
            con.close()
    if row is None:
        return None
    return row[0], base64.b64decode(row[1])


def _friend_pair(license_a: str, license_b: str) -> tuple[str, str]:
    """Canonical (a,b) ordering so each friendship has exactly one row regardless of direction."""
    return (license_a, license_b) if license_a < license_b else (license_b, license_a)


def username_for_license(con: sqlite3.Connection, license_key: str) -> Optional[str]:
    row = con.execute(
        "SELECT username FROM usernames WHERE license_key = ?", (license_key,)
    ).fetchone()
    return row[0] if row else None


def friend_id_for_license(license_key: str) -> Optional[str]:
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            row = con.execute(
                "SELECT friend_id FROM usernames WHERE license_key = ?", (license_key,)
            ).fetchone()
        finally:
            con.close()
    return row[0] if row else None


def username_for_friend_id(friend_id: str) -> Optional[str]:
    if not isinstance(friend_id, str) or not friend_id:
        return None
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            row = con.execute(
                "SELECT username FROM usernames WHERE friend_id = ?", (friend_id,)
            ).fetchone()
        finally:
            con.close()
    return row[0] if row else None


def license_for_username(con: sqlite3.Connection, username: str) -> Optional[str]:
    row = con.execute(
        "SELECT license_key FROM usernames WHERE username = ?", (username,)
    ).fetchone()
    return row[0] if row else None


def send_friend_request(username: str, license_key: str) -> str:
    """Returns "SENT", "ALREADY_FRIENDS", "ALREADY_PENDING", "NOT_FOUND", "SELF", or "INVALID"."""
    if not username_is_valid(username):
        return "INVALID"
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            target_key = license_for_username(con, username)
            if target_key is None:
                return "NOT_FOUND"
            if target_key == license_key:
                return "SELF"
            a, b = _friend_pair(license_key, target_key)
            row = con.execute(
                "SELECT status FROM friends WHERE license_key_a = ? AND license_key_b = ?",
                (a, b),
            ).fetchone()
            if row is not None:
                return "ALREADY_FRIENDS" if row[0] == "ACCEPTED" else "ALREADY_PENDING"
            now = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
            con.execute(
                """INSERT INTO friends (license_key_a, license_key_b, requester_key, status, created_at)
                   VALUES (?, ?, ?, 'PENDING', ?)""",
                (a, b, license_key, now),
            )
            con.commit()
            return "SENT"
        except sqlite3.IntegrityError:
            return "ALREADY_PENDING"
        finally:
            con.close()


def list_friend_requests(license_key: str) -> list[str]:
    """Usernames of people who sent license_key a still-pending request."""
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            rows = con.execute(
                """SELECT requester_key FROM friends
                   WHERE status = 'PENDING' AND requester_key != ?
                     AND (license_key_a = ? OR license_key_b = ?)""",
                (license_key, license_key, license_key),
            ).fetchall()
            names = []
            for (requester_key,) in rows:
                uname = username_for_license(con, requester_key)
                if uname:
                    names.append(uname)
            return names
        finally:
            con.close()


def respond_friend_request(username: str, license_key: str, accept: bool) -> str:
    """Returns "ACCEPTED", "DECLINED", or "NOT_FOUND"."""
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            requester_key = license_for_username(con, username)
            if requester_key is None:
                return "NOT_FOUND"
            a, b = _friend_pair(license_key, requester_key)
            row = con.execute(
                """SELECT status FROM friends WHERE license_key_a = ? AND license_key_b = ?
                   AND requester_key = ?""",
                (a, b, requester_key),
            ).fetchone()
            if row is None or row[0] != "PENDING":
                return "NOT_FOUND"
            if accept:
                con.execute(
                    """UPDATE friends SET status = 'ACCEPTED'
                       WHERE license_key_a = ? AND license_key_b = ?""",
                    (a, b),
                )
                con.commit()
                return "ACCEPTED"
            else:
                con.execute(
                    "DELETE FROM friends WHERE license_key_a = ? AND license_key_b = ?",
                    (a, b),
                )
                con.commit()
                return "DECLINED"
        finally:
            con.close()


def list_friends(license_key: str) -> list[str]:
    """Usernames of accepted friends."""
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            rows = con.execute(
                """SELECT license_key_a, license_key_b FROM friends
                   WHERE status = 'ACCEPTED' AND (license_key_a = ? OR license_key_b = ?)""",
                (license_key, license_key),
            ).fetchall()
            names = []
            for a, b in rows:
                other = b if a == license_key else a
                uname = username_for_license(con, other)
                if uname:
                    names.append(uname)
            return names
        finally:
            con.close()


def remove_friend(username: str, license_key: str) -> str:
    """Returns "REMOVED" or "NOT_FOUND"."""
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            other_key = license_for_username(con, username)
            if other_key is None:
                return "NOT_FOUND"
            a, b = _friend_pair(license_key, other_key)
            cur = con.execute(
                "DELETE FROM friends WHERE license_key_a = ? AND license_key_b = ? AND status = 'ACCEPTED'",
                (a, b),
            )
            con.commit()
            return "REMOVED" if cur.rowcount > 0 else "NOT_FOUND"
        finally:
            con.close()


_USERNAME_RE = __import__("re").compile(r"^[A-Za-z0-9_\-]{1,20}$")

def username_is_valid(name: str) -> bool:
    return isinstance(name, str) and bool(_USERNAME_RE.match(name))


def claim_username(username: str, license_key: str) -> str:
    """Returns "CLAIMED", "TAKEN", or "INVALID"."""
    if not username_is_valid(username):
        return "INVALID"
    now = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            # Check current owner
            row = con.execute(
                "SELECT license_key FROM usernames WHERE username = ?",
                (username,),
            ).fetchone()
            if row is not None:
                if row[0] == license_key:
                    return "CLAIMED"
                return "TAKEN"
            con.execute("DELETE FROM usernames WHERE license_key = ?", (license_key,))
            friend_id = secrets.token_urlsafe(9)  # short opaque token for NFC payload size
            con.execute(
                "INSERT INTO usernames (username, license_key, claimed_at, friend_id) VALUES (?, ?, ?, ?)",
                (username, license_key, now, friend_id),
            )
            con.commit()
            return "CLAIMED"
        except sqlite3.IntegrityError:
            return "TAKEN"
        finally:
            con.close()


def check_username(username: str, license_key: str) -> str:
    """Returns "AVAILABLE", "TAKEN", or "INVALID"."""
    if not username_is_valid(username):
        return "INVALID"
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        try:
            row = con.execute(
                "SELECT license_key FROM usernames WHERE username = ?",
                (username,),
            ).fetchone()
            if row is None:
                return "AVAILABLE"
            return "AVAILABLE" if row[0] == license_key else "TAKEN"
        finally:
            con.close()


def save_path(license_key: str) -> Path:
    safe = "".join(c for c in license_key if c.isalnum() or c == "-")
    return SAVE_DIR / f"{safe}.bin"


def persist_payload(license_key: str, payload: bytes) -> None:
    canonical = save_path(license_key)
    safe = canonical.stem
    for old_file in SAVE_DIR.glob(f"{safe}*.bin"):
        if old_file != canonical:
            try:
                old_file.unlink()
            except OSError:
                pass

    canonical.write_bytes(payload)

    game_ts = 0
    try:
        game_ts = json.loads(payload).get("timestamp", 0)
    except (json.JSONDecodeError, AttributeError, UnicodeDecodeError):
        pass

    now = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        con.execute(
            """
            INSERT INTO saves (license_key, save_data, game_timestamp, size_bytes, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(license_key) DO UPDATE SET
                save_data      = excluded.save_data,
                game_timestamp = excluded.game_timestamp,
                size_bytes     = excluded.size_bytes,
                updated_at     = excluded.updated_at
            """,
            (license_key, payload, game_ts, len(payload), now),
        )
        con.commit()
        con.close()


def load_payload(license_key: str) -> Optional[bytes]:
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        row = con.execute(
            "SELECT save_data FROM saves WHERE license_key = ?",
            (license_key,),
        ).fetchone()
        con.close()
    if row:
        return bytes(row[0])
    p = save_path(license_key)
    if p.exists():
        data = p.read_bytes()
        persist_payload(license_key, data)
        return data
    return None


def log_event(client_ip: str, license_key: str, status: str) -> None:
    now = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
    logging.info("ip=%s license=%s status=%s", client_ip, license_key, status)
    try:
        with _db_lock:
            con = sqlite3.connect(str(DB_PATH))
            con.execute("PRAGMA journal_mode=WAL")
            con.execute(
                "INSERT INTO events (ts, client_ip, license_key, status) VALUES (?, ?, ?, ?)",
                (now, client_ip, license_key, status),
            )
            con.commit()
            con.close()
    except Exception:
        pass


def hkdf(secret: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(secret)


def aesgcm_encrypt(plaintext: bytes, key: bytes, nonce: bytes, aad: bytes) -> bytes:
    if len(nonce) != 12:
        raise ValueError("AES-GCM nonce must be 12 bytes")
    return AESGCM(key).encrypt(nonce, plaintext, aad)


def aesgcm_decrypt(ciphertext: bytes, key: bytes, nonce: bytes, aad: bytes) -> bytes:
    if len(nonce) != 12:
        raise ValueError("AES-GCM nonce must be 12 bytes")
    return AESGCM(key).decrypt(nonce, ciphertext, aad)


def send_line(conn: socket.socket, msg: str) -> None:
    conn.sendall((msg + "\n").encode("utf-8"))


def recv_line(conn: socket.socket, max_bytes: int = MAX_LINE_BYTES) -> str:
    buf = bytearray()
    while len(buf) < max_bytes:
        chunk = conn.recv(1)
        if not chunk:
            raise ValueError("connection closed before newline")
        if chunk == b"\n":
            return buf.rstrip(b"\r").decode("utf-8")
        buf.extend(chunk)
    raise ValueError("line too long")


class Session:
    DIR_S2C = b"\x01"
    DIR_C2S = b"\x02"

    def __init__(self, conn: socket.socket, key: bytes, max_payload: int):
        self.conn = conn
        self.key = key
        self.recv_seq = 0
        self.send_seq = 0
        self.max_payload = max_payload

    def send_json(self, obj: dict) -> None:
        plaintext = json.dumps(obj, separators=(",", ":")).encode("utf-8")
        nonce = os.urandom(12)
        seq = struct.pack(">Q", self.send_seq)
        aad = self.DIR_S2C + seq
        ct = aesgcm_encrypt(plaintext, self.key, nonce, aad)
        wire = base64.b64encode(seq + nonce + ct).decode("ascii")
        send_line(self.conn, "DATA " + wire)
        self.send_seq += 1

    def recv_json(self) -> dict:
        line = recv_line(self.conn, max_bytes=self.max_payload + 4096)
        if not line.startswith("DATA "):
            raise ValueError("expected DATA frame")
        raw = base64.b64decode(line[5:], validate=True)
        if len(raw) < 8 + 12 + 16:
            raise ValueError("frame too short")
        seq = struct.unpack(">Q", raw[:8])[0]
        nonce = raw[8:20]
        ct = raw[20:]
        if seq != self.recv_seq:
            raise ValueError(f"sequence mismatch (got {seq}, expected {self.recv_seq})")
        aad = self.DIR_C2S + raw[:8]
        try:
            plaintext = aesgcm_decrypt(ct, self.key, nonce, aad)
        except InvalidTag as exc:
            raise ValueError("AEAD authentication failed") from exc
        self.recv_seq += 1
        if len(plaintext) > self.max_payload:
            raise ValueError("decoded payload too large")
        return json.loads(plaintext.decode("utf-8"))


def _handle_internal(conn: socket.socket, client_ip: str, cfg: dict,
                     cmd_json: dict) -> str:
    """Handle a loopback-only command from the MP server (no RSA handshake needed)."""
    cmd = str(cmd_json.get("cmd", "")).upper()
    license_key = str(cmd_json.get("license", ""))[:32]
    username    = str(cmd_json.get("username", ""))[:32]

    if cmd == "CLAIM_USERNAME":
        result = claim_username(username, license_key)
        send_line(conn, json.dumps({"status": result}))
        return f"INTERNAL_CLAIM:{result}:{username}"
    elif cmd == "CHECK_USERNAME":
        result = check_username(username, license_key)
        send_line(conn, json.dumps({"status": result}))
        return f"INTERNAL_CHECK:{result}:{username}"
    elif cmd == "VERIFY_ACTIVATION":
        # Lets multiplayer_server resolve a client's (activation_id, enc_blob) into a trusted
        # license_key without duplicating the AES/db logic — same trust boundary as the other
        # internal commands (loopback + shared internal_api_key only).
        activation_id = str(cmd_json.get("activation_id", ""))[:64]
        enc_blob_b64  = str(cmd_json.get("enc_blob", ""))[:256]
        resolved = resolve_activation(activation_id)
        if resolved is None:
            send_line(conn, json.dumps({"status": "UNKNOWN_ACTIVATION"}))
            return "INTERNAL_VERIFY:UNKNOWN_ACTIVATION"
        resolved_key, enc_key = resolved
        try:
            raw_blob = base64.b64decode(enc_blob_b64, validate=True)
            blob_nonce, enc_license_blob = raw_blob[:12], raw_blob[12:]
            decrypted = aesgcm_decrypt(enc_license_blob, enc_key, blob_nonce, b"MichiLicenseBlob")
            if decrypted.decode("utf-8") != resolved_key:
                raise ValueError("blob mismatch")
        except Exception:
            send_line(conn, json.dumps({"status": "BAD_BLOB"}))
            return "INTERNAL_VERIFY:BAD_BLOB"
        send_line(conn, json.dumps({"status": "OK", "license": resolved_key}))
        return f"INTERNAL_VERIFY:OK:{resolved_key}"
    else:
        send_line(conn, json.dumps({"status": "ERROR", "msg": "unknown internal cmd"}))
        return f"INTERNAL_UNKNOWN:{cmd}"


def handle_client(conn: socket.socket, addr: tuple[str, int],
                  cfg: dict, nonce_cache: NonceCache,
                  semaphore: threading.BoundedSemaphore) -> None:
    client_ip = addr[0]
    license_key = ""
    status = "INIT"
    try:
        first = recv_line(conn, max_bytes=512)

        if first == "PING":
            send_line(conn, "PONG")
            status = "PING"
            return

        if first.startswith("INTERNAL "):
            is_loopback = client_ip in ("127.0.0.1", "::1", "localhost")
            internal_api_key = cfg.get("internal_api_key", "")
            parts_int = first.split(" ", 2)
            if (is_loopback and internal_api_key
                    and len(parts_int) == 3
                    and hmac.compare_digest(parts_int[1], internal_api_key)):
                try:
                    cmd_json = json.loads(parts_int[2])
                except json.JSONDecodeError:
                    send_line(conn, json.dumps({"status": "ERROR", "msg": "bad json"}))
                    status = "INTERNAL_BAD_JSON"
                    return
                status = _handle_internal(conn, client_ip, cfg, cmd_json)
            else:
                send_line(conn, json.dumps({"status": "ERROR", "msg": "forbidden"}))
                status = "INTERNAL_FORBIDDEN"
            return

        parts = first.split(" ")
        if len(parts) != 3 or parts[0] != "HELLO" or parts[1] != PROTOCOL_TAG:
            send_line(conn, "AUTH_FAIL")
            status = "BAD_HELLO"
            return

        try:
            client_nonce = base64.b64decode(parts[2], validate=True)
            if len(client_nonce) != 16:
                raise ValueError("bad nonce length")
        except Exception:
            send_line(conn, "AUTH_FAIL")
            status = "BAD_HELLO"
            return

        server_nonce = os.urandom(16)
        send_line(conn, "OK " + base64.b64encode(server_nonce).decode("ascii"))

        try:
            auth = recv_line(conn, max_bytes=4096)
            is_activate = auth.startswith("ACTIVATE ")
            is_login = auth.startswith("LOGIN ")
            if not (is_activate or is_login):
                raise ValueError("missing ACTIVATE/LOGIN")
            auth_parts = auth.split(" ")
            if is_activate:
                # Format: "ACTIVATE <enc_b64>" — no account exists yet, envelope only
                # carries ts/nonces (no license/activation fields to check).
                if len(auth_parts) != 2:
                    raise ValueError("bad ACTIVATE format")
                activation_id = None
                enc_blob_b64 = None
                enc = base64.b64decode(auth_parts[1], validate=True)
            else:
                # Format: "LOGIN <enc_b64> <activation_id> <enc_blob_b64>"
                if len(auth_parts) != 4:
                    raise ValueError("bad LOGIN format")
                activation_id = auth_parts[2][:64]
                enc_blob_b64 = auth_parts[3][:256]
                enc = base64.b64decode(auth_parts[1], validate=True)
            plaintext = rsa_oaep_decrypt(enc)
            payload = json.loads(plaintext.decode("utf-8"))
        except Exception as exc:
            send_line(conn, "AUTH_FAIL")
            status = f"AUTH_DECRYPT:{type(exc).__name__}"
            return

        ts = int(payload.get("ts", 0))
        try:
            cn_check = bytes.fromhex(str(payload.get("client_nonce", "")))
            sn_check = bytes.fromhex(str(payload.get("server_nonce", "")))
        except ValueError:
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_NONCE_FORMAT"
            return

        if abs(int(time.time()) - ts) > HANDSHAKE_TS_WINDOW:
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_TS_WINDOW"
            return
        if not hmac.compare_digest(cn_check, client_nonce):
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_CN_MISMATCH"
            return
        if not hmac.compare_digest(sn_check, server_nonce):
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_SN_MISMATCH"
            return
        if not nonce_cache.check_and_store(client_nonce):
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_REPLAY"
            return

        if is_activate:
            # Brand-new account: issue license_key + activation_id + enc_key, encrypt the
            # license_key with enc_key, and hand the client back only the ciphertext —
            # the plaintext license_key never leaves the server on this path.
            license_key, new_activation_id, enc_key = create_license()
            blob_nonce = os.urandom(12)
            enc_license_blob = aesgcm_encrypt(
                plaintext=license_key.encode("utf-8"),
                key=enc_key,
                nonce=blob_nonce,
                aad=b"MichiLicenseBlob",
            )
            enc_blob_wire = base64.b64encode(blob_nonce + enc_license_blob).decode("ascii")
            status_prefix = f"ACTIVATE:{license_key}"
        else:
            resolved = resolve_activation(activation_id)
            if resolved is None:
                send_line(conn, "AUTH_FAIL")
                status = "LOGIN_UNKNOWN_ACTIVATION"
                return
            license_key, enc_key = resolved
            try:
                raw_blob = base64.b64decode(enc_blob_b64, validate=True)
                blob_nonce, enc_license_blob = raw_blob[:12], raw_blob[12:]
                decrypted = aesgcm_decrypt(enc_license_blob, enc_key, blob_nonce, b"MichiLicenseBlob")
                if decrypted.decode("utf-8") != license_key:
                    raise ValueError("blob does not match activation_id's license")
            except Exception:
                send_line(conn, "AUTH_FAIL")
                status = "LOGIN_BAD_BLOB"
                return
            new_activation_id = None
            status_prefix = f"LOGIN:{license_key}"

        delivery_key = hkdf(
            secret=license_key.encode("utf-8") + b"michi-license-pepper-v2",
            salt=server_nonce,
            info=b"michi-delivery-v2",
            length=32,
        )
        session_key = os.urandom(32)
        enc_session = aesgcm_encrypt(
            plaintext=session_key,
            key=delivery_key,
            nonce=client_nonce[:12],
            aad=b"MichiCloudSession",
        )
        if is_activate:
            # The client needs its own freshly issued license_key in-hand this run (it's the
            # account identifier used for every save/friend/MP call) — send it once, AEAD-wrapped.
            # Can't use delivery_key here: it's derived FROM license_key, which the client doesn't
            # know yet (that's the value being delivered). Use a key derived purely from the
            # nonces instead — both sides already share those at this point in the handshake.
            issuance_key = hkdf(
                secret=client_nonce + server_nonce,
                salt=server_nonce,
                info=b"michi-issuance-v2",
                length=32,
            )
            enc_license_key = aesgcm_encrypt(
                plaintext=license_key.encode("utf-8"),
                key=issuance_key,
                nonce=client_nonce[:12],
                aad=b"MichiIssuedLicense",
            )
            send_line(conn, "AUTH_OK " + base64.b64encode(enc_session).decode("ascii")
                       + " " + new_activation_id + " " + enc_blob_wire
                       + " " + base64.b64encode(enc_license_key).decode("ascii"))
        else:
            send_line(conn, "AUTH_OK " + base64.b64encode(enc_session).decode("ascii"))
        status = status_prefix

        conn.settimeout(cfg["session_timeout_seconds"])
        sess = Session(conn, session_key, cfg["max_payload_bytes"])

        try:
            req = sess.recv_json()
        except Exception as exc:
            status = f"SESSION_BAD:{type(exc).__name__}"
            return

        cmd = str(req.get("cmd", "")).upper()

        if cmd == "UPLOAD":
            try:
                payload_b64 = req.get("data", "")
                save_bytes = base64.b64decode(payload_b64, validate=True)
            except Exception:
                sess.send_json({"status": "ERROR", "msg": "bad payload"})
                status = "UPLOAD_BAD_PAYLOAD"
                return

            if len(save_bytes) > cfg["max_payload_bytes"]:
                sess.send_json({"status": "ERROR", "msg": "payload too large"})
                status = "UPLOAD_TOO_LARGE"
                return

            incoming_ts = 0
            try:
                incoming_ts = int(json.loads(save_bytes).get("timestamp", 0))
            except Exception:
                pass

            existing = load_payload(license_key)
            if existing is not None:
                existing_ts = 0
                try:
                    existing_ts = int(json.loads(existing).get("timestamp", 0))
                except Exception:
                    pass
                if existing_ts > incoming_ts:
                    sess.send_json({
                        "status": "SYNC",
                        "data": base64.b64encode(existing).decode("ascii"),
                    })
                    status = "SYNC_SERVER_NEWER"
                    return

            persist_payload(license_key, save_bytes)
            sess.send_json({"status": "SAVED"})
            status = f"SAVED:{len(save_bytes)}"

        elif cmd == "DOWNLOAD":
            existing = load_payload(license_key)
            if existing is None:
                sess.send_json({"status": "NO_SAVE"})
                status = "NO_SAVE"
            else:
                sess.send_json({
                    "status": "DOWNLOADED",
                    "data": base64.b64encode(existing).decode("ascii"),
                })
                status = f"DOWNLOADED:{len(existing)}"

        elif cmd == "CLAIM_USERNAME":
            uname = str(req.get("username", ""))[:32]
            result = claim_username(uname, license_key)
            sess.send_json({"status": result})
            status = f"CLAIM_USERNAME:{result}:{uname}"

        elif cmd == "CHECK_USERNAME":
            uname = str(req.get("username", ""))[:32]
            result = check_username(uname, license_key)
            sess.send_json({"status": result})
            status = f"CHECK_USERNAME:{result}:{uname}"

        elif cmd == "SEND_FRIEND_REQUEST":
            uname = str(req.get("username", ""))[:32]
            result = send_friend_request(uname, license_key)
            sess.send_json({"status": result})
            status = f"SEND_FRIEND_REQUEST:{result}:{uname}"

        elif cmd == "LIST_FRIEND_REQUESTS":
            names = list_friend_requests(license_key)
            sess.send_json({"status": "OK", "requests": names})
            status = f"LIST_FRIEND_REQUESTS:{len(names)}"

        elif cmd == "RESPOND_FRIEND_REQUEST":
            uname = str(req.get("username", ""))[:32]
            accept = bool(req.get("accept", False))
            result = respond_friend_request(uname, license_key, accept)
            sess.send_json({"status": result})
            status = f"RESPOND_FRIEND_REQUEST:{result}:{uname}"

        elif cmd == "LIST_FRIENDS":
            names = list_friends(license_key)
            sess.send_json({"status": "OK", "friends": names})
            status = f"LIST_FRIENDS:{len(names)}"

        elif cmd == "REMOVE_FRIEND":
            uname = str(req.get("username", ""))[:32]
            result = remove_friend(uname, license_key)
            sess.send_json({"status": result})
            status = f"REMOVE_FRIEND:{result}:{uname}"

        elif cmd == "GET_MY_FRIEND_ID":
            fid = friend_id_for_license(license_key)
            if fid is None:
                sess.send_json({"status": "NO_USERNAME"})
                status = "GET_MY_FRIEND_ID:NO_USERNAME"
            else:
                sess.send_json({"status": "OK", "friend_id": fid})
                status = "GET_MY_FRIEND_ID:OK"

        elif cmd == "RESOLVE_FRIEND_ID":
            fid = str(req.get("friend_id", ""))[:32]
            uname = username_for_friend_id(fid)
            if uname is None:
                sess.send_json({"status": "NOT_FOUND"})
                status = "RESOLVE_FRIEND_ID:NOT_FOUND"
            else:
                sess.send_json({"status": "OK", "username": uname})
                status = f"RESOLVE_FRIEND_ID:OK:{uname}"

        else:
            sess.send_json({"status": "ERROR", "msg": "unknown command"})
            status = f"UNKNOWN_CMD:{cmd}"

    except (UnicodeDecodeError, ValueError) as exc:
        status = f"MALFORMED:{exc}"
    except PermissionError as exc:
        status = f"DENIED:{exc}"
        try:
            send_line(conn, "AUTH_FAIL")
        except OSError:
            pass
    except OSError as exc:
        status = f"ERROR:{exc.__class__.__name__}"
    except Exception as exc:
        status = f"INTERNAL:{exc.__class__.__name__}"
        logging.exception("Unhandled error from %s", client_ip)
    finally:
        log_event(client_ip, license_key, status)
        try:
            conn.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            conn.close()
        except OSError:
            pass
        try:
            semaphore.release()
        except ValueError:
            pass


def serve_forever() -> None:
    configure_logging()
    cfg = load_config()
    init_db()

    pk_path = Path(cfg["private_key_path"])
    if not pk_path.is_absolute():
        pk_path = BASE_DIR / pk_path
    if not pk_path.exists():
        logging.error(
            "Private key not found at %s. Run generate_keys.py first.", pk_path
        )
        sys.exit(2)
    load_private_key(pk_path)

    rate_limiter = IpRateLimiter(cfg["rate_limit_per_ip_per_minute"])
    nonce_cache = NonceCache()
    semaphore = threading.BoundedSemaphore(cfg["max_concurrent_connections"])

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((cfg["host"], cfg["port"]))
        srv.listen(64)

        logging.info(
            "Cloud-save server v2 listening on %s:%d (db=%s, rl=%d/min, max=%d)",
            cfg["host"], cfg["port"], DB_PATH,
            cfg["rate_limit_per_ip_per_minute"],
            cfg["max_concurrent_connections"],
        )

        while True:
            conn, addr = srv.accept()
            client_ip = addr[0]

            if not rate_limiter.allow(client_ip):
                try:
                    conn.sendall(b"RATE_LIMIT\n")
                except OSError:
                    pass
                conn.close()
                logging.warning("Rate-limited connection from %s", client_ip)
                continue

            if not semaphore.acquire(blocking=False):
                try:
                    conn.sendall(b"BUSY\n")
                except OSError:
                    pass
                conn.close()
                logging.warning("Server busy, dropped connection from %s", client_ip)
                continue

            t = threading.Thread(
                target=handle_client,
                args=(conn, addr, cfg, nonce_cache, semaphore),
                daemon=True,
            )
            t.start()


if __name__ == "__main__":
    try:
        serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")

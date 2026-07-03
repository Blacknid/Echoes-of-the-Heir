#!/usr/bin/env python3
"""
Michi's Adventure - Cloud Save Server v2

Wire format (newline-terminated):
  PING  ->  PONG

  Authenticated path:
    C -> "HELLO v2 <base64(client_nonce_16)>"
    S -> "OK <base64(server_nonce_16)>"
    C -> "AUTH <base64(rsa_oaep_sha256(handshake_json))> <machine_fp> <sig_b64>"
         handshake_json = { "license", "ts", "client_nonce" (hex), "server_nonce" (hex) }
    S -> "AUTH_OK <base64(aesgcm(session_key, key=delivery_key, nonce=cn[:12], aad='MichiCloudSession'))>"
      or "AUTH_FAIL"

  Session frames (AEAD):
    wire = "DATA <base64(seq_8 || nonce_12 || ciphertext || tag_16)>"
    AAD  = direction_byte (0x01 S->C, 0x02 C->S) || seq_8_BE

  Commands (JSON inside AEAD):
    C -> { "cmd": "UPLOAD",   "data": <base64> }
    C -> { "cmd": "DOWNLOAD" }
    C -> { "cmd": "CLAIM_USERNAME", "username": "..." }
    C -> { "cmd": "CHECK_USERNAME", "username": "..." }
"""
from __future__ import annotations

import base64
import collections
import hashlib
import hmac
import json
import logging
import os
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

import license_verify

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
    # RSA-2048 public key (DER/SPKI base64) that the installer uses to
    # sign license.properties. MUST match LicenseManager.PUBLIC_KEY_B64.
    # If left as the placeholder, signature verification is DISABLED and
    # the server falls back to registry-only auth — never deploy that way.
    "license_public_key_b64": "REPLACE_WITH_PUBLIC_KEY_FROM_generate_license_keys.py",
    # Path to the license allow-list (relative to this server's dir).
    # See licenses.example.json for the schema.
    "licenses_db": "licenses.json",
    # Set true ONLY on a dev/localhost instance. Disables license verification
    # entirely so you can connect without a signed license.properties.
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
_LICENSE_PUB = None


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


# Cheap pre-filter before doing RSA work — real trust is the RSA signature.
_LICENSE_KEY_RE = __import__("re").compile(r"^[A-Z0-9][A-Z0-9\-]{3,63}$")

def license_is_well_formed(license_key: str) -> bool:
    return isinstance(license_key, str) and bool(_LICENSE_KEY_RE.match(license_key))


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
                claimed_at   TEXT NOT NULL
            )
        """)
        con.commit()
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
            con.execute(
                "INSERT INTO usernames (username, license_key, claimed_at) VALUES (?, ?, ?)",
                (username, license_key, now),
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
            if not auth.startswith("AUTH "):
                raise ValueError("missing AUTH")
            # Format: "AUTH <enc_b64> <machine_fp> <sig_b64>"
            auth_parts = auth.split(" ")
            if len(auth_parts) != 4:
                raise ValueError("bad AUTH format")
            enc = base64.b64decode(auth_parts[1], validate=True)
            machine_fp  = auth_parts[2][:64]
            license_sig = auth_parts[3][:512]
            plaintext = rsa_oaep_decrypt(enc)
            payload = json.loads(plaintext.decode("utf-8"))
        except Exception as exc:
            send_line(conn, "AUTH_FAIL")
            status = f"AUTH_DECRYPT:{type(exc).__name__}"
            return

        license_key = str(payload.get("license", ""))[:32]
        ts = int(payload.get("ts", 0))
        try:
            cn_check = bytes.fromhex(str(payload.get("client_nonce", "")))
            sn_check = bytes.fromhex(str(payload.get("server_nonce", "")))
        except ValueError:
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_NONCE_FORMAT"
            return

        if not license_is_well_formed(license_key):
            send_line(conn, "AUTH_FAIL")
            status = "AUTH_BAD_LICENSE"
            return

        if not cfg.get("dev_mode", False):
            pub = _LICENSE_PUB
            if pub is not None:
                if not license_verify.verify_license_signature(
                        pub, license_key, machine_fp, license_sig):
                    send_line(conn, "AUTH_FAIL")
                    status = "AUTH_BAD_SIGNATURE"
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
        send_line(conn, "AUTH_OK " + base64.b64encode(enc_session).decode("ascii"))

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

    global _LICENSE_PUB
    _LICENSE_PUB = license_verify.load_public_key(cfg.get("license_public_key_b64", ""))
    if _LICENSE_PUB is None and not cfg.get("dev_mode", False):
        logging.warning(
            "license_public_key_b64 is unset/placeholder — signature verification "
            "DISABLED. Set it in server_config.json for production."
        )
    if cfg.get("dev_mode", False):
        logging.warning("dev_mode=True — ALL license checks bypassed. Do NOT use in production.")

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

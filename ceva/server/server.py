#!/usr/bin/env python3
"""
Michi's Adventure — Cloud Save Server
RSA/AES hybrid encryption, license validation, timestamp-based sync.

Protocol
--------
  PING path:
    Client sends "PING\n" (no auth) → Server replies "PONG\n"

  Authenticated path:
    1. Client → BASE64(RSA(license_key)) + "\n"
    2. Server validates license
       - Invalid: "AUTH_FAIL\n"
       - Valid:   "AUTH_OK\n"  +  BASE64(AES_delivery(session_key_32b)) + "\n"
    3. Client → COMMAND + "\n"   (UPLOAD | DOWNLOAD)
    4a. UPLOAD:  Client → BASE64(IV + AES_session(JSON)) + "\n"
                 Server → "SAVED\n"  or  "SYNC\n" + BASE64(IV + AES_session(newer_JSON)) + "\n"
    4b. DOWNLOAD: Server → BASE64(IV + AES_session(JSON)) + "\n"  or  "NO_SAVE\n"
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import socket
import sqlite3
import struct
import threading
import time
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# ── Configuration ─────────────────────────────────────────────────────────────
HOST = "0.0.0.0"
PORT = 5005
BUFFER_SIZE = 4096
MAX_LINE_BYTES = 8192
LICENSE_SALT = "MichiCloudSalt2026"
BASE_DIR = Path(__file__).resolve().parent
SAVE_DIR = BASE_DIR / "saves"
LOG_PATH = BASE_DIR / "server.log"
PRIVATE_KEY_PATH = BASE_DIR / "server_private_key.pem"
DB_PATH = BASE_DIR / "saves.db"


# ── Logging ───────────────────────────────────────────────────────────────────
def configure_logging() -> None:
    BASE_DIR.mkdir(parents=True, exist_ok=True)
    SAVE_DIR.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")

    file_handler = logging.FileHandler(str(LOG_PATH))
    file_handler.setFormatter(fmt)

    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(fmt)

    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(file_handler)
    root.addHandler(stream_handler)


# ── RSA key loading ──────────────────────────────────────────────────────────
_private_key = None


def load_private_key():
    global _private_key
    pem_data = PRIVATE_KEY_PATH.read_bytes()
    _private_key = serialization.load_pem_private_key(pem_data, password=None)


def rsa_decrypt(ciphertext: bytes) -> bytes:
    return _private_key.decrypt(ciphertext, rsa_padding.PKCS1v15())


# ── AES helpers ─────────────────────────────────────────────────────────────
def aes_encrypt(plaintext: bytes, key: bytes) -> bytes:
    iv = os.urandom(16)
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv))
    encryptor = cipher.encryptor()
    pad_len = 16 - (len(plaintext) % 16)
    padded = plaintext + bytes([pad_len] * pad_len)
    ct = encryptor.update(padded) + encryptor.finalize()
    return iv + ct


def aes_decrypt(data: bytes, key: bytes) -> bytes:
    iv, ct = data[:16], data[16:]
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv))
    decryptor = cipher.decryptor()
    padded = decryptor.update(ct) + decryptor.finalize()
    pad_len = padded[-1]
    if pad_len < 1 or pad_len > 16:
        raise ValueError("Bad PKCS7 padding")
    return padded[:-pad_len]


# ── License validation ──────────────────────────────────────────────────────
def verify_license_key(license_key: str) -> bool:
    if len(license_key) != 13 or license_key[8] != "-":
        return False
    prefix, suffix = license_key[:8], license_key[9:]
    if not prefix.isalnum() or not suffix.isalnum() or len(suffix) != 4:
        return False
    expected = hashlib.sha256(
        f"{prefix}{LICENSE_SALT}".encode("utf-8")
    ).hexdigest()[:4].upper()
    return hmac.compare_digest(expected, suffix.upper())


# ── Database ─────────────────────────────────────────────────────────────────
_db_lock = threading.Lock()


def init_db() -> None:
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("""
            CREATE TABLE IF NOT EXISTS saves (
                license_key  TEXT    PRIMARY KEY,
                save_data    BLOB    NOT NULL,
                game_timestamp INTEGER NOT NULL DEFAULT 0,
                size_bytes   INTEGER NOT NULL DEFAULT 0,
                updated_at   TEXT    NOT NULL
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
        con.commit()
        con.close()


# ── Persistence ─────────────────────────────────────────────────────────────
def save_path(license_key: str) -> Path:
    safe = "".join(c for c in license_key if c.isalnum() or c == "-")
    return SAVE_DIR / f"{safe}.bin"


def persist_payload(license_key: str, payload: bytes) -> None:
    # Write .bin file (backwards compat)
    save_path(license_key).write_bytes(payload)

    # Parse game timestamp from JSON
    game_ts = 0
    try:
        game_ts = json.loads(payload).get("timestamp", 0)
    except (json.JSONDecodeError, AttributeError):
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


def load_payload(license_key: str) -> bytes | None:
    with _db_lock:
        con = sqlite3.connect(str(DB_PATH))
        con.execute("PRAGMA journal_mode=WAL")
        row = con.execute(
            "SELECT save_data FROM saves WHERE license_key = ?", (license_key,)
        ).fetchone()
        con.close()
    if row:
        return bytes(row[0])
    # Fallback: check legacy .bin file
    p = save_path(license_key)
    if p.exists():
        data = p.read_bytes()
        persist_payload(license_key, data)  # migrate into DB
        return data
    return None


def log_attempt(client_ip: str, license_key: str, status: str) -> None:
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


# ── Network helpers ──────────────────────────────────────────────────────────
def send_line(conn: socket.socket, msg: str) -> None:
    conn.sendall(f"{msg}\n".encode("utf-8"))


def recv_line(conn: socket.socket, max_bytes: int = MAX_LINE_BYTES) -> str:
    buf = bytearray()
    while len(buf) < max_bytes:
        chunk = conn.recv(1)
        if not chunk:
            raise ValueError("Malformed Data: connection closed before newline")
        if chunk == b"\n":
            return buf.rstrip(b"\r").decode("utf-8")
        buf.extend(chunk)
    raise ValueError("Malformed Data: line too long")


# ── Client handler ──────────────────────────────────────────────────────────
def handle_client(conn: socket.socket, addr: tuple[str, int]) -> None:
    client_ip = addr[0]
    license_key = "<missing>"
    status = "UNKNOWN"

    try:
        conn.settimeout(15)
        first_line = recv_line(conn)

        # ── Fast PING path (no auth) ──
        if first_line == "PING":
            send_line(conn, "PONG")
            status = "PING"
            print(f"[{client_ip}] PING received (heartbeat)")
            return

        # ── Authenticated path ──
        try:
            enc_license = base64.b64decode(first_line)
            license_key = rsa_decrypt(enc_license).decode("utf-8")
        except Exception:
            status = "MALFORMED_DATA"
            print(f"[{client_ip}] Connection received — could not decrypt license (malformed data)")
            send_line(conn, "AUTH_FAIL")
            return

        if not verify_license_key(license_key):
            status = "AUTH_FAIL"
            print(f"[{client_ip}] Connection received — invalid license: {license_key}")
            send_line(conn, "AUTH_FAIL")
            return

        print(f"[{client_ip}] Client authenticated — license: {license_key}")

        # Generate AES-256 session key
        session_key = os.urandom(32)

        # Encrypt session key with SHA-256(license) as delivery key
        delivery_key = hashlib.sha256(license_key.encode("utf-8")).digest()
        encrypted_session_key = aes_encrypt(session_key, delivery_key)

        send_line(conn, "AUTH_OK")
        send_line(conn, base64.b64encode(encrypted_session_key).decode("ascii"))

        # ── Command dispatch ──
        command = recv_line(conn)

        if command == "UPLOAD":
            payload_b64 = recv_line(conn, max_bytes=10 * 1024 * 1024)
            payload_enc = base64.b64decode(payload_b64)
            payload_json = aes_decrypt(payload_enc, session_key)

            # Parse timestamp for sync comparison
            incoming_ts = 0
            try:
                incoming_data = json.loads(payload_json)
                incoming_ts = incoming_data.get("timestamp", 0)
            except json.JSONDecodeError:
                pass

            # Check existing save
            existing_raw = load_payload(license_key)
            if existing_raw is not None:
                existing_ts = 0
                try:
                    existing_data = json.loads(existing_raw)
                    existing_ts = existing_data.get("timestamp", 0)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pass

                if existing_ts > incoming_ts:
                    # Server has newer — send it back
                    enc_existing = aes_encrypt(existing_raw, session_key)
                    send_line(conn, "SYNC")
                    send_line(conn, base64.b64encode(enc_existing).decode("ascii"))
                    status = "SYNC_SERVER_NEWER"
                    print(f"[{client_ip}] UPLOAD received from license {license_key} — server has newer save, sent back to client")
                    return

            # Save incoming (store raw JSON for future sync comparison)
            persist_payload(license_key, payload_json)
            send_line(conn, "SAVED")
            status = "SAVED"
            print(f"[{client_ip}] UPLOAD received from license {license_key} — data saved ({len(payload_json)} bytes)")

        elif command == "DOWNLOAD":
            existing_raw = load_payload(license_key)
            if existing_raw is None:
                send_line(conn, "NO_SAVE")
                status = "NO_SAVE"
                print(f"[{client_ip}] DOWNLOAD requested by license {license_key} — no save found")
            else:
                enc_data = aes_encrypt(existing_raw, session_key)
                send_line(conn, base64.b64encode(enc_data).decode("ascii"))
                status = "DOWNLOADED"
                print(f"[{client_ip}] DOWNLOAD requested by license {license_key} — save sent ({len(existing_raw)} bytes)")
        else:
            status = "UNKNOWN_COMMAND"
            print(f"[{client_ip}] Unknown command from license {license_key}: {command}")

    except (UnicodeDecodeError, ValueError) as exc:
        status = f"MALFORMED_DATA:{exc}"
        print(f"[{client_ip}] Error — malformed data from license {license_key}: {exc}")
        try:
            send_line(conn, "MALFORMED_DATA")
        except OSError:
            pass
    except OSError as exc:
        status = f"ERROR:{exc.__class__.__name__}"
        print(f"[{client_ip}] Connection error from license {license_key}: {exc}")
    finally:
        log_attempt(client_ip, license_key, status)
        try:
            conn.close()
        except OSError:
            pass


# ── Server main loop ────────────────────────────────────────────────────────
def serve_forever() -> None:
    configure_logging()
    init_db()
    load_private_key()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((HOST, PORT))
        srv.listen()
        logging.info("Server started — listening on %s:%s  (DB: %s)", HOST, PORT, DB_PATH)

        while True:
            conn, addr = srv.accept()
            t = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
            t.start()


if __name__ == "__main__":
    serve_forever()
<<<<<<< HEAD
=======
    
>>>>>>> 144e1ece49117478d634e1ebedadf1983c1020a0

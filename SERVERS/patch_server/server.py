#!/usr/bin/env python3
"""
Michi's Adventure - Patch Server

Wire format (line-terminated text + length-prefixed binary for FETCH):

  PING\n
    -> PONG\n

  CHECK <current_version>\n
    -> UPTODATE\n
       (or)
    -> UPDATE <to_version> <patch_size_bytes> <sha256_hex> <sig_b64>\n
       The signature covers SHA-256(patch_bytes) || from_version || to_version
       so a malicious mirror cannot serve an unrelated signed patch.

  FETCH <from_version>\n
    -> 8 bytes big-endian uint64 size, then `size` raw bytes of the patch ZIP
    -> on error: "ERROR <msg>\n"

The signing key is RSA-2048 (PKCS#1 v1.5 over SHA-256). The matching public
key (Base64 DER SubjectPublicKeyInfo) is pasted into UpdateClient.java.
"""
from __future__ import annotations

import collections
import hashlib
import json
import logging
import socket
import struct
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "patch_config.json"

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 5006,
    "private_key_path": "patch_private_key.pem",
    "patches_dir": "patches",
    "manifest_path": "manifest.json",
    "max_concurrent_connections": 32,
    "rate_limit_per_ip_per_minute": 60,
    "max_patch_bytes": 256 * 1024 * 1024,
}

MAX_LINE_BYTES = 4096

log = logging.getLogger("patch")


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        try:
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
        except Exception as exc:
            print(f"[WARN] Could not parse {CONFIG_PATH}: {exc} — using defaults")
    return cfg


def load_private_key(path: Path) -> RSAPrivateKey:
    pem = path.read_bytes()
    key = serialization.load_pem_private_key(pem, password=None)
    if not isinstance(key, rsa.RSAPrivateKey):
        raise SystemExit("Private key must be RSA")
    return key


def load_manifest(path: Path) -> dict:
    if not path.exists():
        return {"latest_version": "0.0.0", "patches": []}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        log.error("Bad manifest %s: %s", path, exc)
        return {"latest_version": "0.0.0", "patches": []}


def find_patch(manifest: dict, from_version: str) -> Optional[dict]:
    """Return the patch entry for from_version -> latest, or None if up-to-date."""
    latest = manifest.get("latest_version", "0.0.0")
    if from_version == latest:
        return None
    for entry in manifest.get("patches", []):
        if entry.get("from") == from_version and entry.get("to") == latest:
            return entry
    return None


def signature_payload(patch_sha256: bytes, from_version: str, to_version: str) -> bytes:
    return patch_sha256 + b"|" + from_version.encode("ascii") + b"|" + to_version.encode("ascii")


class IpRateLimiter:
    def __init__(self, max_per_minute: int):
        self.max = max_per_minute
        self.buckets: dict[str, collections.deque] = collections.defaultdict(collections.deque)
        self.lock = threading.Lock()

    def allow(self, ip: str) -> bool:
        now = time.monotonic()
        cutoff = now - 60.0
        with self.lock:
            q = self.buckets[ip]
            while q and q[0] < cutoff:
                q.popleft()
            if len(q) >= self.max:
                return False
            q.append(now)
            return True


def recv_line(conn: socket.socket, max_bytes: int = MAX_LINE_BYTES) -> str:
    buf = bytearray()
    while len(buf) < max_bytes:
        chunk = conn.recv(1)
        if not chunk:
            raise ValueError("connection closed")
        if chunk == b"\n":
            return buf.rstrip(b"\r").decode("utf-8")
        buf.extend(chunk)
    raise ValueError("line too long")


def send_line(conn: socket.socket, msg: str) -> None:
    conn.sendall((msg + "\n").encode("utf-8"))


def send_blob(conn: socket.socket, data: bytes) -> None:
    conn.sendall(struct.pack(">Q", len(data)))
    conn.sendall(data)


def send_file_blob(conn: socket.socket, path: Path, size: int) -> None:
    """Stream the patch instead of loading it whole: 32 concurrent FETCHes of a
    256 MB patch would otherwise hold up to 8 GB of RAM at once."""
    conn.sendall(struct.pack(">Q", size))
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(64 * 1024)
            if not chunk:
                break
            conn.sendall(chunk)


def resolve_patch_path(cfg: dict, entry_file: str) -> Optional[Path]:
    """Resolve a manifest 'file' entry and refuse anything that escapes BASE_DIR
    (defense in depth if the manifest is ever attacker-influenced)."""
    p = (BASE_DIR / entry_file).resolve()
    try:
        p.relative_to(BASE_DIR)
    except ValueError:
        log.error("Manifest entry escapes base dir: %s", entry_file)
        return None
    return p


def handle_client(conn: socket.socket, addr: tuple[str, int],
                  cfg: dict, private_key: RSAPrivateKey,
                  semaphore: threading.BoundedSemaphore) -> None:
    ip = addr[0]
    try:
        conn.settimeout(15.0)
        line = recv_line(conn, max_bytes=512)

        if line == "PING":
            send_line(conn, "PONG")
            return

        manifest = load_manifest(BASE_DIR / cfg["manifest_path"])

        if line.startswith("CHECK "):
            current = line[6:].strip()[:32]
            entry = find_patch(manifest, current)
            if entry is None:
                send_line(conn, "UPTODATE")
                log.info("CHECK from %s: %s up-to-date", ip, current)
                return

            patch_path = resolve_patch_path(cfg, entry["file"])
            if patch_path is None or not patch_path.exists():
                send_line(conn, "ERROR patch file missing")
                log.error("Manifest references missing/invalid file %s", entry.get("file"))
                return

            sha = entry.get("sha256_hex", "")
            sig_b64 = entry.get("signature_b64", "")
            size = entry.get("size_bytes") or patch_path.stat().st_size
            send_line(
                conn,
                f"UPDATE {entry['to']} {size} {sha} {sig_b64}",
            )
            log.info("CHECK from %s: %s -> %s", ip, current, entry["to"])
            return

        if line.startswith("FETCH "):
            from_version = line[6:].strip()[:32]
            entry = find_patch(manifest, from_version)
            if entry is None:
                send_line(conn, "ERROR no patch available")
                return

            patch_path = resolve_patch_path(cfg, entry["file"])
            if patch_path is None or not patch_path.exists():
                send_line(conn, "ERROR patch file missing")
                return

            size = patch_path.stat().st_size
            if size > cfg["max_patch_bytes"]:
                send_line(conn, "ERROR patch too large")
                return

            send_file_blob(conn, patch_path, size)
            log.info("FETCH from %s: served %s -> %s (%d bytes)",
                     ip, from_version, entry["to"], size)
            return

        send_line(conn, "ERROR unknown command")

    except (ValueError, OSError) as exc:
        log.info("Connection from %s ended: %s", ip, exc)
    except Exception:
        log.exception("Unhandled error from %s", ip)
    finally:
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


def maybe_sign_unsigned_patches(cfg: dict, private_key: RSAPrivateKey) -> None:
    manifest_path = BASE_DIR / cfg["manifest_path"]
    if not manifest_path.exists():
        return
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return
    changed = False
    for entry in manifest.get("patches", []):
        patch_path = BASE_DIR / entry["file"]
        if not patch_path.exists():
            continue
        if entry.get("signature_b64") and entry.get("sha256_hex"):
            continue
        data = patch_path.read_bytes()
        sha = hashlib.sha256(data).digest()
        sig = private_key.sign(
            signature_payload(sha, entry["from"], entry["to"]),
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
        import base64 as _b64
        entry["sha256_hex"] = sha.hex()
        entry["signature_b64"] = _b64.b64encode(sig).decode("ascii")
        entry["size_bytes"] = len(data)
        changed = True
        log.info("Signed patch %s -> %s", entry["from"], entry["to"])
    if changed:
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=False),
            encoding="utf-8",
        )


def serve_forever() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        handlers=[logging.StreamHandler(sys.stdout)],
    )
    cfg = load_config()

    pk_path = Path(cfg["private_key_path"])
    if not pk_path.is_absolute():
        pk_path = BASE_DIR / pk_path
    if not pk_path.exists():
        log.error("Private key not found at %s. Run generate_patch_keys.py.", pk_path)
        sys.exit(2)

    private_key = load_private_key(pk_path)
    maybe_sign_unsigned_patches(cfg, private_key)

    rate_limiter = IpRateLimiter(cfg["rate_limit_per_ip_per_minute"])
    semaphore = threading.BoundedSemaphore(cfg["max_concurrent_connections"])

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((cfg["host"], cfg["port"]))
        srv.listen(64)

        log.info("Patch server listening on %s:%d (patches=%s)",
                 cfg["host"], cfg["port"], BASE_DIR / cfg["patches_dir"])

        while True:
            conn, addr = srv.accept()
            ip = addr[0]
            if not rate_limiter.allow(ip):
                try:
                    conn.sendall(b"RATE_LIMIT\n")
                except OSError:
                    pass
                conn.close()
                continue
            if not semaphore.acquire(blocking=False):
                try:
                    conn.sendall(b"BUSY\n")
                except OSError:
                    pass
                conn.close()
                continue
            t = threading.Thread(
                target=handle_client,
                args=(conn, addr, cfg, private_key, semaphore),
                daemon=True,
            )
            t.start()


if __name__ == "__main__":
    try:
        serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")

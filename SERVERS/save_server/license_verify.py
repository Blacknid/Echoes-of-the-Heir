"""
Server-side license verification.

Verifies the RSA-2048 PKCS#1v15 SHA-256 signature embedded in
license.properties (signed by the installer's private key over
"<license_key>|<machine_fp>") against the SAME public key that the
Java LicenseManager carries.

Also enforces an allow-list registry (`licenses.json`):

    {
      "ABCDEFGH-1234": { "machine_fp": null, "first_seen": null },
      "QRSTUVWX-9F2A": { "machine_fp": "abc...", "first_seen": "2026-04-..." }
    }

Workflow:
  - When the dev issues a new license, they append a row with machine_fp=null.
  - On first successful auth the server TOFU-pins the connecting machine_fp.
  - On every later auth the machine_fp must match — license sharing is
    automatically detected and rejected.

Public functions:
  load_public_key(b64)        -> RSAPublicKey
  load_registry(path)         -> dict (auto-creates an empty file)
  save_registry(path, reg)    -> None  (atomic-ish write)
  verify_license_signature(...) -> bool
  authorize(...)              -> (ok: bool, reason: str)
"""
from __future__ import annotations

import base64
import json
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey


_lock = threading.Lock()


# ── Public-key loader ─────────────────────────────────────────────────────
def load_public_key(b64: str) -> Optional[RSAPublicKey]:
    """Return the RSA public key, or None if unset/placeholder."""
    if not b64 or b64.startswith("REPLACE_WITH"):
        return None
    try:
        der = base64.b64decode(b64)
        return serialization.load_der_public_key(der)
    except Exception:
        return None


# ── Signature verification ────────────────────────────────────────────────
def verify_license_signature(pub_key: RSAPublicKey,
                             license_key: str,
                             machine_fp: str,
                             signature_b64: str) -> bool:
    """Verify SHA256-with-RSA over "license_key|machine_fp"."""
    if not (license_key and machine_fp and signature_b64):
        return False
    try:
        sig = base64.b64decode(signature_b64)
        msg = f"{license_key}|{machine_fp}".encode("utf-8")
        pub_key.verify(sig, msg, rsa_padding.PKCS1v15(), hashes.SHA256())
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


# ── Registry I/O ──────────────────────────────────────────────────────────
def load_registry(path: Path) -> dict:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{}", encoding="utf-8")
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_registry(path: Path, reg: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(reg, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


# ── Authorisation policy ──────────────────────────────────────────────────
def authorize(registry_path: Path,
              registry: dict,
              license_key: str,
              machine_fp: str,
              dev_mode: bool = False) -> tuple[bool, str]:
    """
    Decide whether (license_key, machine_fp) is authorised.

    Policy:
      * dev_mode=True             -> always accept (use ONLY on dev/localhost).
      * license_key not in regs   -> reject (HARD allow-list).
      * stored fp is None         -> accept; TOFU-pin this fp; persist.
      * stored fp == incoming fp  -> accept.
      * stored fp != incoming fp  -> reject (license was copied).
    """
    if dev_mode:
        return True, "DEV_MODE"

    if not license_key or not machine_fp:
        return False, "MISSING_FIELDS"

    entry = registry.get(license_key)
    if entry is None:
        return False, "UNREGISTERED"

    pinned = entry.get("machine_fp")
    if pinned is None:
        # TOFU pin
        with _lock:
            entry["machine_fp"] = machine_fp
            entry["first_seen"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
            registry[license_key] = entry
            try:
                save_registry(registry_path, registry)
            except Exception:
                # Don't reject just because disk write failed — but log via caller
                pass
        return True, "TOFU_PINNED"

    if pinned.lower() == machine_fp.lower():
        return True, "OK"

    return False, "FP_MISMATCH"

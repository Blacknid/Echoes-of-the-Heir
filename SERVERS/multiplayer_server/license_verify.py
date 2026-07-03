"""
Server-side license verification.

Verifies the RSA-2048 PKCS#1v15 SHA-256 signature embedded in
license.properties (signed by the installer's private key over
"<license_key>|<machine_fp>") against the same public key that the
Java LicenseManager carries.

No allow-list registry — any installer-signed key is accepted.
The RSA signature is the sole source of trust.

Public functions:
  load_public_key(b64)          -> RSAPublicKey | None
  verify_license_signature(...) -> bool
"""
from __future__ import annotations

import base64
from typing import Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey


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

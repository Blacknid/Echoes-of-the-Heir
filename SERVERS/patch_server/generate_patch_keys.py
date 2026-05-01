#!/usr/bin/env python3
"""
Generate the RSA-2048 keypair used to sign patches.
Outputs:
  patch_private_key.pem   (mode 0600 — keep on the patch server only)
  patch_public_key.b64    (paste into UpdateClient.PATCH_PUBLIC_KEY_B64)
"""
from __future__ import annotations

import base64
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

BASE_DIR = Path(__file__).resolve().parent


def main() -> None:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    priv_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    priv_path = BASE_DIR / "patch_private_key.pem"
    priv_path.write_bytes(priv_pem)
    try:
        priv_path.chmod(0o600)
    except Exception:
        pass

    pub_der = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    pub_b64 = base64.b64encode(pub_der).decode("ascii")
    (BASE_DIR / "patch_public_key.b64").write_text(pub_b64 + "\n")

    print(f"Private key: {priv_path}")
    print("Public key (Base64 DER) — paste into UpdateClient.PATCH_PUBLIC_KEY_B64:")
    print(pub_b64)


if __name__ == "__main__":
    main()

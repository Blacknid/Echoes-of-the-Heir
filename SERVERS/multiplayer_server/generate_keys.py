#!/usr/bin/env python3
"""Generate RSA-2048 key pair for the Michi cloud save server.

Outputs:
  server_private_key.pem  — RSA private key  (keep on server only)
  server_public_key.b64   — Base64(DER) public key (embed in Java client)
"""
from __future__ import annotations

import base64
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

BASE_DIR = Path(__file__).resolve().parent


def main() -> None:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    # Save private key PEM
    priv_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    priv_path = BASE_DIR / "server_private_key.pem"
    priv_path.write_bytes(priv_pem)
    priv_path.chmod(0o600)

    # Save public key as Base64(DER) for Java embedding
    pub_der = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    pub_b64 = base64.b64encode(pub_der).decode("ascii")
    (BASE_DIR / "server_public_key.b64").write_text(pub_b64 + "\n")

    print(f"Private key: {priv_path}")
    print(f"Public key (Base64 DER) — paste into CloudSaveService.java:")
    print(pub_b64)


if __name__ == "__main__":
    main()

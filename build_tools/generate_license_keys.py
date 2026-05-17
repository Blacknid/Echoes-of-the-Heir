#!/usr/bin/env python3
"""
Generate RSA-2048 keypair for Michi license signing.

Run ONCE before building the installer:
    python build_tools/generate_license_keys.py

Outputs:
    license_private.xml  — private key in RSA XML format (.NET compatible)
                           Paste the content into setup_init.iss where it says
                           REPLACE_WITH_YOUR_PRIVATE_KEY_XML
    license_public.b64   — public key DER/SPKI base64
                           Paste the content into LicenseManager.java where it
                           says REPLACE_WITH_PUBLIC_KEY_FROM_generate_license_keys.py

SECURITY NOTE:
    Keep license_private.xml secret.
    Never commit it to a public repository.
    Add it (and setup_init.iss) to .gitignore.
"""

import base64
import os
import sys

try:
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.backends import default_backend
except ImportError:
    print("ERROR: 'cryptography' package not found.")
    print("Install it with:  pip install cryptography")
    sys.exit(1)


def int_to_b64(n: int) -> str:
    """Encode a big integer as base64, big-endian, minimal bytes."""
    byte_len = (n.bit_length() + 7) // 8
    return base64.b64encode(n.to_bytes(byte_len, "big")).decode()


def main():
    print("Generating RSA-2048 keypair ...")
    key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
        backend=default_backend(),
    )
    pub = key.public_key()
    priv_n = key.private_numbers()
    pub_n  = pub.public_numbers()

    # ── Private key: RSA XML format (for .NET RSACryptoServiceProvider.FromXmlString) ──
    xml_key = (
        "<RSAKeyValue>"
        f"<Modulus>{int_to_b64(pub_n.n)}</Modulus>"
        f"<Exponent>{int_to_b64(pub_n.e)}</Exponent>"
        f"<P>{int_to_b64(priv_n.p)}</P>"
        f"<Q>{int_to_b64(priv_n.q)}</Q>"
        f"<DP>{int_to_b64(priv_n.dmp1)}</DP>"
        f"<DQ>{int_to_b64(priv_n.dmq1)}</DQ>"
        f"<InverseQ>{int_to_b64(priv_n.iqmp)}</InverseQ>"
        f"<D>{int_to_b64(priv_n.d)}</D>"
        "</RSAKeyValue>"
    )

    # ── Public key: DER SPKI base64 (for Java KeyFactory / X509EncodedKeySpec) ──
    pub_der = pub.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    pub_b64 = base64.b64encode(pub_der).decode()

    # ── Private key: PKCS#8 DER base64 (for generate_dev_license.py) ──
    priv_pkcs8_der = key.private_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    priv_pkcs8_b64 = base64.b64encode(priv_pkcs8_der).decode()

    # Write files
    script_dir = os.path.dirname(os.path.abspath(__file__))
    priv_xml_path    = os.path.join(script_dir, "license_private.xml")
    priv_pkcs8_path  = os.path.join(script_dir, "license_private_pkcs8.b64")
    pub_path         = os.path.join(script_dir, "license_public.b64")

    with open(priv_xml_path, "w", encoding="utf-8") as f:
        f.write(xml_key)
    with open(priv_pkcs8_path, "w", encoding="utf-8") as f:
        f.write(priv_pkcs8_b64)
    with open(pub_path, "w", encoding="utf-8") as f:
        f.write(pub_b64)

    print(f"[OK] Private key (RSA XML)    → {priv_xml_path}")
    print(f"[OK] Private key (PKCS#8 b64) → {priv_pkcs8_path}")
    print(f"[OK] Public key  (DER b64)    → {pub_path}")
    print()
    print("=" * 70)
    print("NEXT STEPS")
    print("=" * 70)
    print()
    print("1. Paste the PUBLIC KEY into LicenseManager.java:")
    print(f"   Replace the placeholder string with:")
    print(f'   "{pub_b64}"')
    print()
    print("2. Paste the PRIVATE KEY XML into setup_init.iss:")
    print("   Replace REPLACE_WITH_YOUR_PRIVATE_KEY_XML with the single line")
    print("   found in license_private.xml  (no newlines — it's already one line)")
    print()
    print("3. For DEV signing, run:")
    print(f"   python build_tools/generate_dev_license.py")
    print(f"   (Reads {priv_pkcs8_path} automatically.)")
    print()
    print("4. Keep all private key files SECRET — add to .gitignore:")
    print("   license_private.xml")
    print("   license_private_pkcs8.b64")
    print("   build_tools/setup_init.iss  (contains embedded private key)")
    print()
    print("5. Rebuild the installer (recompile setup_init.iss with Inno Setup).")


if __name__ == "__main__":
    main()

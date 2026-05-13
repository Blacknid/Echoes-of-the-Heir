#!/usr/bin/env python3
"""
Issue a new Michi license key and append it to one or more server registries.

Workflow:
  1. Run this script when a customer buys the game.
  2. It generates a fresh `XXXXXXXX-YYYY` key (same format the installer
     and servers expect; suffix = sha256(prefix + license_salt)[:4]).
  3. It appends a stub row {"machine_fp": null, "first_seen": null, "note": ...}
     to each registry file you pass via --registry.
  4. You give the printed license_key to the customer. They paste it into
     the installer (or the installer auto-generates the file). On their
     first connect, the server TOFU-pins their machine_fp.

Usage:
    python build_tools/issue_license.py \\
        --salt MichiCloudSalt2026 \\
        --note "alice@example.com" \\
        --registry SERVERS/save_server/licenses.json \\
        --registry SERVERS/multiplayer_server/licenses.json

After running, scp/rsync each updated `licenses.json` to its corresponding
server (or commit + pull if you keep them in git on the Pis).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import string
import sys
from pathlib import Path

CHARSET = string.ascii_uppercase + string.digits


def generate_license_key(salt: str) -> str:
    prefix = "".join(secrets.choice(CHARSET) for _ in range(8))
    suffix = hashlib.sha256(f"{prefix}{salt}".encode("utf-8")).hexdigest()[:4].upper()
    return f"{prefix}-{suffix}"


def append_to_registry(path: Path, license_key: str, note: str) -> None:
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            print(f"[ERROR] {path}: cannot parse: {exc}", file=sys.stderr)
            sys.exit(2)
        if not isinstance(data, dict):
            print(f"[ERROR] {path}: top-level must be a JSON object", file=sys.stderr)
            sys.exit(2)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {}

    if license_key in data:
        print(f"[WARN]  {path}: {license_key} already present, skipping")
        return

    data[license_key] = {
        "machine_fp": None,
        "first_seen": None,
        "note":       note,
    }
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)
    print(f"[OK]    {path}: appended {license_key}")


def main() -> None:
    p = argparse.ArgumentParser(description="Issue a new Michi license.")
    p.add_argument("--salt", default="MichiCloudSalt2026",
                   help="license_salt — must match every server's config.")
    p.add_argument("--note", default="",
                   help="Free-form note (e.g. customer email).")
    p.add_argument("--registry", action="append", default=[],
                   help="Path to a server licenses.json. Repeat per server.")
    p.add_argument("--key", default=None,
                   help="(Optional) reuse an existing key instead of generating one.")
    args = p.parse_args()

    license_key = args.key or generate_license_key(args.salt)

    # Validate the resulting key against the server-side checker (mirror).
    prefix, _, suffix = license_key.partition("-")
    if len(prefix) != 8 or len(suffix) != 4:
        print(f"[ERROR] invalid key format: {license_key}", file=sys.stderr)
        sys.exit(2)
    expected = hashlib.sha256(f"{prefix}{args.salt}".encode("utf-8")).hexdigest()[:4].upper()
    if expected != suffix.upper():
        print(f"[ERROR] suffix does not match salt — wrong --salt?", file=sys.stderr)
        sys.exit(2)

    print()
    print("=" * 60)
    print(f"  License key: {license_key}")
    print("=" * 60)
    if args.note:
        print(f"  Note:        {args.note}")
    print()

    if not args.registry:
        print("[INFO] No --registry given — printing key only. Add --registry "
              "to append the row to a server's licenses.json.")
        return

    for r in args.registry:
        append_to_registry(Path(r), license_key, args.note)

    print()
    print("Next steps:")
    print("  1. Send the license key to the customer.")
    print("  2. Push each updated licenses.json to its server "
          "(scp / rsync / git pull).")
    print("  3. On first connect the server TOFU-pins the customer's machine_fp.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Issue a new Michi license key and append it to one or more server registries.

Workflow:
  1. Run this script when a customer buys the game.
  2. It generates a fresh `XXXXXXXX-YYYY` random base32 key (the dashes are
     cosmetic — the RSA signature on the client side is the actual proof).
  3. It appends a row to each registry file you pass via --registry. By
     default the row is TOFU (machine_fp=null — server pins on first
     connect). Pass --machine-fp to PRE-BIND the license to a specific
     machine (recommended when the customer's MachineGuid is known).
  4. You give the printed license_key to the customer.

Optional fields:
  --machine-fp FP    Pre-bind the license to a 16-hex MachineGuid hash.
                     If set, the server NEVER TOFU-binds it — only an
                     exact match passes. Closes the TOFU race.
  --expires YYYY-MM-DD   Set an expiry date (UTC). Server rejects after.
  --revoke           Issue a row with revoked=true (kill-switch for an
                     existing key that leaked).

Usage (can be run from any directory):
    python build_tools/issue_license.py \\
        --note "alice@example.com" \\
        --registry SERVERS/save_server/licenses.json \\
        --registry SERVERS/multiplayer_server/licenses.json

Paths are resolved relative to the repository root automatically.

After running, scp/rsync each updated `licenses.json` to its corresponding
server (or commit + pull if you keep them in git on the Pis).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import string
import sys
from datetime import datetime, timezone
from pathlib import Path

CHARSET = string.ascii_uppercase + string.digits

# Resolve registry paths relative to repo root (where this script is located)
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent


def resolve_registry_path(path_str: str) -> Path:
    """Resolve path relative to repo root if not absolute."""
    p = Path(path_str)
    if p.is_absolute():
        return p
    # Try repo root first, then CWD as fallback
    repo_path = REPO_ROOT / p
    if repo_path.exists() or not (Path.cwd() / p).exists():
        return repo_path
    return Path.cwd() / p


def generate_license_key() -> str:
    """Generate a fresh `XXXXXXXX-YYYY` random base32 key (62 bits of entropy)."""
    prefix = "".join(secrets.choice(CHARSET) for _ in range(8))
    suffix = "".join(secrets.choice(CHARSET) for _ in range(4))
    return f"{prefix}-{suffix}"


def append_to_registry(path: Path, license_key: str, entry: dict) -> None:
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

    existing = data.get(license_key)
    if existing is not None:
        # Merge: refuse to overwrite a pinned machine_fp unless --revoke,
        # but always allow updating the revoked/expires fields.
        merged = dict(existing)
        for k, v in entry.items():
            if k == "machine_fp" and v is None and existing.get("machine_fp"):
                continue  # never wipe a pinned fp by accident
            merged[k] = v
        data[license_key] = merged
        print(f"[OK]    {path}: updated {license_key}")
    else:
        data[license_key] = entry
        print(f"[OK]    {path}: appended {license_key}")

    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


_FP_RE = re.compile(r"^[0-9a-f]{16}$")
_KEY_RE = re.compile(r"^[A-Z0-9][A-Z0-9\-]{3,63}$")


def main() -> None:
    p = argparse.ArgumentParser(description="Issue a new Michi license.")
    p.add_argument("--note", default="",
                   help="Free-form note (e.g. customer email).")
    p.add_argument("--registry", action="append", default=[],
                   help="Path to a server licenses.json. Repeat per server.")
    p.add_argument("--key", default=None,
                   help="(Optional) reuse an existing key instead of generating one.")
    p.add_argument("--machine-fp", default=None,
                   help="Pre-bind to this 16-hex MachineGuid hash (closes TOFU race).")
    p.add_argument("--expires", default=None,
                   help="Expiry date in YYYY-MM-DD format (UTC).")
    p.add_argument("--revoke", action="store_true",
                   help="Mark the key as revoked (used with --key to kill a leaked license).")
    args = p.parse_args()

    license_key = args.key or generate_license_key()

    if not _KEY_RE.match(license_key):
        print(f"[ERROR] invalid key format: {license_key}", file=sys.stderr)
        sys.exit(2)

    if args.machine_fp is not None and not _FP_RE.match(args.machine_fp.lower()):
        print(f"[ERROR] --machine-fp must be 16 lowercase hex chars", file=sys.stderr)
        sys.exit(2)

    expires_iso = None
    if args.expires:
        try:
            d = datetime.strptime(args.expires, "%Y-%m-%d").replace(tzinfo=timezone.utc)
            expires_iso = d.isoformat()
        except ValueError:
            print(f"[ERROR] --expires must be YYYY-MM-DD", file=sys.stderr)
            sys.exit(2)

    entry = {
        "machine_fp": args.machine_fp.lower() if args.machine_fp else None,
        "first_seen": None,
        "note":       args.note,
    }
    if expires_iso:
        entry["expires"] = expires_iso
    if args.revoke:
        entry["revoked"] = True

    print()
    print("=" * 60)
    print(f"  License key: {license_key}")
    print("=" * 60)
    if args.note:        print(f"  Note:        {args.note}")
    if args.machine_fp: print(f"  Pre-bound:   {args.machine_fp}")
    if expires_iso:     print(f"  Expires:     {expires_iso}")
    if args.revoke:     print(f"  REVOKED:     yes")
    print()

    if not args.registry:
        print("[INFO] No --registry given — printing key only. Add --registry "
              "to append the row to a server's licenses.json.")
        return

    for r in args.registry:
        reg_path = resolve_registry_path(r)
        append_to_registry(reg_path, license_key, entry)

    print()
    print("Next steps:")
    print("  1. Send the license key to the customer.")
    print("  2. Push each updated licenses.json to its server "
          "(scp / rsync / git pull).")
    if args.machine_fp:
        print("  3. License is PRE-BOUND — only the specified machine can use it.")
    else:
        print("  3. On first connect the server TOFU-pins the customer's machine_fp.")


if __name__ == "__main__":
    main()

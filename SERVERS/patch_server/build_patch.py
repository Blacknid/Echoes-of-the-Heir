#!/usr/bin/env python3
"""
Build a signed patch from two game-JAR snapshots.

Usage:
    python3 build_patch.py <old.jar> <new.jar> <from_version> <to_version>

Produces:
    patches/v<from>_to_<to>.zip   — the patch file
    Updates manifest.json         — adds entry, sets latest_version
    Signs the patch with patch_private_key.pem

Patch ZIP layout:
    manifest.json     {from, to, replace[], add[], delete[]}
    replace/<path>    new bytes for files whose content changed
    add/<path>        new files that didn't exist before
    (deletions are listed in manifest only)

This is "full-jump" patching: each release publishes one patch from each
supported old version straight to the new one. Keep <=N old versions in
the manifest to bound storage.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import io
import json
import shutil
import sys
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

BASE_DIR = Path(__file__).resolve().parent


def read_jar_entries(jar_path: Path) -> dict[str, bytes]:
    """Return {entry_name: raw_bytes} for every file in the JAR."""
    out: dict[str, bytes] = {}
    with zipfile.ZipFile(jar_path, "r") as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            with z.open(info) as f:
                out[info.filename] = f.read()
    return out


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def signature_payload(patch_sha256: bytes, from_version: str, to_version: str) -> bytes:
    return patch_sha256 + b"|" + from_version.encode("ascii") + b"|" + to_version.encode("ascii")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("old_jar")
    ap.add_argument("new_jar")
    ap.add_argument("from_version")
    ap.add_argument("to_version")
    ap.add_argument("--key", default="patch_private_key.pem",
                    help="Path to RSA signing key (default: patch_private_key.pem)")
    args = ap.parse_args()

    old_jar = Path(args.old_jar).resolve()
    new_jar = Path(args.new_jar).resolve()
    if not old_jar.exists() or not new_jar.exists():
        print(f"[ERR] old or new JAR missing")
        return 2

    print(f"[*] Diffing {old_jar.name} -> {new_jar.name}")
    old_entries = read_jar_entries(old_jar)
    new_entries = read_jar_entries(new_jar)

    replace: list[str] = []
    add:     list[str] = []
    delete:  list[str] = []

    for name, new_bytes in new_entries.items():
        if name not in old_entries:
            add.append(name)
        elif old_entries[name] != new_bytes:
            replace.append(name)

    for name in old_entries:
        if name not in new_entries:
            delete.append(name)

    print(f"    add={len(add)}  replace={len(replace)}  delete={len(delete)}")

    # Build the patch ZIP into memory
    manifest = {
        "from": args.from_version,
        "to": args.to_version,
        "replace": sorted(replace),
        "add": sorted(add),
        "delete": sorted(delete),
    }
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for name in add:
            z.writestr(f"add/{name}", new_entries[name])
        for name in replace:
            z.writestr(f"replace/{name}", new_entries[name])
    patch_bytes = buf.getvalue()

    patches_dir = BASE_DIR / "patches"
    patches_dir.mkdir(exist_ok=True)
    patch_filename = f"v{args.from_version}_to_{args.to_version}.zip"
    patch_path = patches_dir / patch_filename
    patch_path.write_bytes(patch_bytes)
    print(f"[*] Wrote patch: {patch_path} ({len(patch_bytes)} bytes)")

    # Sign
    key_path = Path(args.key)
    if not key_path.is_absolute():
        key_path = BASE_DIR / key_path
    if not key_path.exists():
        print(f"[ERR] Signing key missing: {key_path}")
        return 2

    private_key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    if not isinstance(private_key, rsa.RSAPrivateKey):
        print("[ERR] Key is not RSA")
        return 2

    sha = hashlib.sha256(patch_bytes).digest()
    sig = private_key.sign(
        signature_payload(sha, args.from_version, args.to_version),
        padding.PKCS1v15(),
        hashes.SHA256(),
    )
    sig_b64 = base64.b64encode(sig).decode("ascii")

    # Update manifest.json
    manifest_path = BASE_DIR / "manifest.json"
    server_manifest: dict
    if manifest_path.exists():
        server_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    else:
        server_manifest = {"latest_version": args.to_version, "patches": []}

    new_entry = {
        "from": args.from_version,
        "to": args.to_version,
        "file": f"patches/{patch_filename}",
        "sha256_hex": sha.hex(),
        "signature_b64": sig_b64,
        "size_bytes": len(patch_bytes),
        "created_at": dt.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

    # Replace any existing entry with same (from, to)
    server_manifest["patches"] = [
        e for e in server_manifest.get("patches", [])
        if not (e.get("from") == args.from_version and e.get("to") == args.to_version)
    ]
    server_manifest["patches"].append(new_entry)
    server_manifest["latest_version"] = args.to_version

    manifest_path.write_text(
        json.dumps(server_manifest, indent=2),
        encoding="utf-8",
    )
    print(f"[*] manifest.json updated. latest_version = {args.to_version}")
    print(f"[*] sha256 = {sha.hex()}")
    print(f"[*] signature_b64 = {sig_b64}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

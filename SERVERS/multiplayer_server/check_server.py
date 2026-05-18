#!/usr/bin/env python3
"""Server-side diagnostic - run this on the Pi to verify config."""
import json
import base64
from pathlib import Path

print("=== Server Configuration Check ===\n")

# Check mp_config.json
config_path = Path("mp_config.json")
config = json.loads(config_path.read_text())

pub_key = config.get("license_public_key_b64", "")
if pub_key.startswith("MIIB"):
    print(f"✅ Public key present: {pub_key[:50]}...")
else:
    print(f"❌ Public key missing or placeholder: {pub_key[:50] if pub_key else 'EMPTY'}")

# Check licenses.json
registry_path = Path("licenses.json")
registry = json.loads(registry_path.read_text())

print(f"\n✅ Registry loaded: {len(registry)} entries")

# Check for the specific license
test_key = "XF3M2YVT-N6L2"
if test_key in registry:
    entry = registry[test_key]
    print(f"\n✅ License '{test_key}' found:")
    print(f"   machine_fp: {entry.get('machine_fp')}")
    print(f"   first_seen: {entry.get('first_seen')}")
    print(f"   note: {entry.get('note')}")
    if entry.get("machine_fp"):
        print(f"   Pre-bound to: {entry['machine_fp']}")
    else:
        print(f"   TOFU mode (will pin on first connect)")
else:
    print(f"\n❌ License '{test_key}' NOT FOUND in registry!")
    print("   Available keys:")
    for k in list(registry.keys())[:5]:
        print(f"     - {k}")

print("\n=== Check Complete ===")
print("If license is missing, copy licenses.json and restart server.")

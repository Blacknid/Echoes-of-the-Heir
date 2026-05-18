#!/usr/bin/env python3
"""Check license.properties format."""
import sys
from pathlib import Path

license_path = Path("C:/Users/iulia/AppData/Local/MichiGame/license.properties")
if not license_path.exists():
    print("ERROR: license.properties not found")
    sys.exit(1)

content = license_path.read_text(encoding='utf-8')
print("=== Raw content ===")
print(repr(content))
print()

lines = content.splitlines()
for i, line in enumerate(lines, 1):
    print(f"Line {i}: {repr(line)}")

print()
# Parse as properties-like
for line in lines:
    if '=' in line and not line.startswith('#'):
        key, val = line.split('=', 1)
        print(f"{key}: len={len(val)}")
        if key == 'signature':
            # Check for newlines inside value
            if '\n' in val or '\r' in val:
                print("  ERROR: Signature contains newlines!")
            else:
                print(f"  OK: single line, first 50 chars: {val[:50]}...")

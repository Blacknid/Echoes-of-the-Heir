#!/usr/bin/env bash
# Linux/macOS counterpart of preflight.ps1.
# Ensures every bind-mounted host path exists with the correct type
# BEFORE `docker compose up`, otherwise Docker creates a stray directory
# in place of a missing file.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ensure_file() {
    if [ ! -e "$1" ]; then
        mkdir -p "$(dirname "$1")"
        : > "$1"
        echo "  [+] created file:  $1"
    fi
}
ensure_dir() {
    if [ ! -d "$1" ]; then
        mkdir -p "$1"
        echo "  [+] created dir:   $1"
    fi
}

echo "Pre-flight: verifying host-side paths..."

# save_server
ensure_file "$root/save_server/server_config.json"
ensure_file "$root/save_server/licenses.json"
ensure_file "$root/save_server/server_private_key.pem"
ensure_file "$root/save_server/saves.db"
ensure_file "$root/save_server/server.log"
ensure_dir  "$root/save_server/saves"

# patch_server
ensure_file "$root/patch_server/patch_config.json"
ensure_file "$root/patch_server/manifest.json"
ensure_file "$root/patch_server/patch_private_key.pem"
ensure_dir  "$root/patch_server/patches"

# multiplayer_server
ensure_file "$root/multiplayer_server/mp_config.json"
ensure_file "$root/multiplayer_server/licenses.json"
ensure_file "$root/multiplayer_server/server_private_key.pem"
ensure_dir  "$root/multiplayer_server/maps"

echo "Pre-flight OK."

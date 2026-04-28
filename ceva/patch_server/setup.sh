#!/usr/bin/env bash
# Michi's Adventure — Patch server setup for Raspberry Pi OS / Bookworm.
# Run with: sudo bash setup.sh
set -euo pipefail

if [ "${EUID:-$(id -u)}" -ne 0 ]; then
    echo "Run as root: sudo bash setup.sh" >&2
    exit 1
fi

REAL_USER="${SUDO_USER:-$(logname 2>/dev/null || echo pi)}"
REAL_GROUP="$(id -gn "$REAL_USER")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="michi-patch"

echo "==> Patch server setup for $REAL_USER ($SCRIPT_DIR)"

# ── 1. apt deps ────────────────────────────────────────────────────────────
echo "==> [1/7] Installing apt packages..."
apt-get update -qq
apt-get install -y python3-cryptography ufw

# ── 2. verify cryptography supports PKCS1v15 + SHA256 (always true on Bookworm) ─
echo "==> [2/7] Verifying Python cryptography..."
sudo -u "$REAL_USER" python3 - <<'PY'
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa
print("OK: cryptography import")
PY

# ── 3. RSA signing key ─────────────────────────────────────────────────────
echo "==> [3/7] Patch-signing key..."
if [ ! -f "$SCRIPT_DIR/patch_private_key.pem" ]; then
    echo "    No key found — generating fresh RSA-2048 keypair."
    sudo -u "$REAL_USER" python3 "$SCRIPT_DIR/generate_patch_keys.py"
    chmod 600 "$SCRIPT_DIR/patch_private_key.pem"
    chown "$REAL_USER:$REAL_GROUP" "$SCRIPT_DIR/patch_private_key.pem"
    chown "$REAL_USER:$REAL_GROUP" "$SCRIPT_DIR/patch_public_key.b64"
    echo
    echo "  >>> IMPORTANT: copy the contents of patch_public_key.b64 into <<<"
    echo "  >>> ceva/src/update/UpdateClient.java PATCH_PUBLIC_KEY_B64 <<<"
    echo
else
    echo "    Existing patch_private_key.pem kept."
fi

# ── 4. config + dirs ───────────────────────────────────────────────────────
echo "==> [4/7] Config + patches dir..."
if [ ! -f "$SCRIPT_DIR/patch_config.json" ]; then
    cp "$SCRIPT_DIR/patch_config.example.json" "$SCRIPT_DIR/patch_config.json"
    chown "$REAL_USER:$REAL_GROUP" "$SCRIPT_DIR/patch_config.json"
    echo "    Created patch_config.json from example. Edit it as needed."
fi
sudo -u "$REAL_USER" mkdir -p "$SCRIPT_DIR/patches"

# ── 5. firewall ────────────────────────────────────────────────────────────
PORT="$(python3 -c "import json; print(json.load(open('$SCRIPT_DIR/patch_config.json'))['port'])")"
echo "==> [5/7] ufw allow $PORT/tcp..."
ufw allow "$PORT/tcp" >/dev/null || true

# ── 6. systemd service ─────────────────────────────────────────────────────
echo "==> [6/7] systemd service ($SERVICE_NAME)..."
SERVICE_PATH="/etc/systemd/system/$SERVICE_NAME.service"
cat > "$SERVICE_PATH" <<EOF
[Unit]
Description=Michi's Adventure - Patch Server
After=network.target

[Service]
Type=simple
User=$REAL_USER
WorkingDirectory=$SCRIPT_DIR
ExecStart=/usr/bin/python3 $SCRIPT_DIR/server.py
Restart=on-failure
RestartSec=3
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

# ── 7. status ──────────────────────────────────────────────────────────────
echo "==> [7/7] Status..."
sleep 1
systemctl --no-pager --full status "$SERVICE_NAME" | head -n 20 || true
echo
echo "Done. Logs: journalctl -u $SERVICE_NAME -f"
echo "Add patches with: python3 build_patch.py <old.jar> <new.jar> <from> <to>"

#!/bin/bash
# ──────────────────────────────────────────────────
# Michi's Adventure — Multiplayer Server Setup
# Target: Raspberry Pi OS (x64)
# ──────────────────────────────────────────────────
# This script installs dependencies and creates a
# systemd service to run the multiplayer game server.
#
# THIS SERVER IS NOT THE SINGLEPLAYER SAVE SERVER.
# It only handles multiplayer session synchronization.
# ──────────────────────────────────────────────────

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_NAME="michi-multiplayer"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
SERVER_SCRIPT="${SCRIPT_DIR}/server.py"

echo "═══════════════════════════════════════════"
echo "  Michi's Adventure — Multiplayer Server"
echo "  Setup for Raspberry Pi OS (x64)"
echo "═══════════════════════════════════════════"
echo ""

# 1. Check Python 3
if ! command -v python3 &>/dev/null; then
    echo "[!] Python 3 not found. Installing..."
    sudo apt update && sudo apt install -y python3
fi

PYTHON=$(command -v python3)
echo "[✓] Python: $($PYTHON --version)"

# 2. No pip packages needed — server uses only stdlib (asyncio, json, logging)
echo "[✓] No external dependencies needed (stdlib only)"

# 3. Make server executable
chmod +x "$SERVER_SCRIPT"
echo "[✓] Server script is executable"

# 4. Open firewall port (if ufw is installed)
if command -v ufw &>/dev/null; then
    echo "[*] Opening port 7777/tcp in ufw..."
    sudo ufw allow 7777/tcp comment "Michi Multiplayer" || true
    echo "[✓] Firewall rule added"
else
    echo "[i] ufw not found, skipping firewall config."
    echo "    Make sure port 7777/tcp is open if you have another firewall."
fi

# 5. Create systemd service
echo "[*] Creating systemd service: ${SERVICE_NAME}"

sudo tee "$SERVICE_FILE" > /dev/null <<EOF
[Unit]
Description=Michi's Adventure Multiplayer Server
After=network.target

[Service]
Type=simple
User=$(whoami)
WorkingDirectory=${SCRIPT_DIR}
ExecStart=${PYTHON} ${SERVER_SCRIPT}
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl start "$SERVICE_NAME"

echo ""
echo "═══════════════════════════════════════════"
echo "  Setup complete!"
echo ""
echo "  Service:  ${SERVICE_NAME}"
echo "  Port:     7777"
echo "  Status:   sudo systemctl status ${SERVICE_NAME}"
echo "  Logs:     journalctl -u ${SERVICE_NAME} -f"
echo "  Stop:     sudo systemctl stop ${SERVICE_NAME}"
echo "  Restart:  sudo systemctl restart ${SERVICE_NAME}"
echo "═══════════════════════════════════════════"

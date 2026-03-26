#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/michi-s-adventure/ceva/server"
SERVICE_NAME="michi.service"
SERVICE_PATH="/etc/systemd/system/${SERVICE_NAME}"
SERVICE_USER="${SUDO_USER:-$(whoami)}"

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Run this script with sudo."
    exit 1
fi

echo "[1/6] Installing packages..."
apt-get update
apt-get install -y python3 python3-pip ufw

echo "[2/6] Installing Python dependencies..."
pip3 install cryptography

echo "[3/6] Preparing directories..."
mkdir -p "${APP_DIR}/saves"
touch "${APP_DIR}/server.log"

echo "[4/6] Generating RSA key pair (if missing)..."
if [ ! -f "${APP_DIR}/server_private_key.pem" ]; then
    python3 "${APP_DIR}/generate_keys.py"
    echo "  RSA key pair generated."
else
    echo "  Key pair already exists, skipping."
fi

echo "[5/6] Configuring firewall..."
ufw allow 5005/tcp
ufw --force enable

echo "[6/6] Creating systemd service..."
cat > "${SERVICE_PATH}" <<EOF
[Unit]
Description=Michi cloud save server
After=network.target

[Service]
Type=simple
User=${SERVICE_USER}
WorkingDirectory=${APP_DIR}
ExecStart=/usr/bin/python3 ${APP_DIR}/server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"
systemctl status "${SERVICE_NAME}" --no-pager
echo "Done! Server is running on port 5005."
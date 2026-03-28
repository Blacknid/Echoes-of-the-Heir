#!/usr/bin/env bash
set -euo pipefail

# Gets the absolute path of the directory where setup.sh is located
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Run this script with sudo."
    exit 1
fi

echo "[1/5] Installing packages..."
apt-get update
apt-get install -y python3 python3-pip ufw

echo "[2/5] Installing Python dependencies..."
pip3 install cryptography

echo "[3/5] Preparing directories..."
mkdir -p "${APP_DIR}/saves"
touch "${APP_DIR}/server.log"

echo "[4/5] Generating RSA key pair (if missing)..."
if [ ! -f "${APP_DIR}/server_private_key.pem" ]; then
    python3 "${APP_DIR}/generate_keys.py"
    echo "  RSA key pair generated."
else
    echo "  Key pair already exists, skipping."
fi

echo "[5/5] Configuring firewall..."
ufw allow 5005/tcp
ufw --force enable

echo ""
echo "Setup complete!"
echo "To start the server in the foreground, run:"
echo "  python3 ${APP_DIR}/server.py"
echo ""
echo "Logs will print to the terminal and also to ${APP_DIR}/server.log"
echo "Saves database: ${APP_DIR}/saves.db  (inspect with: sqlite3 ${APP_DIR}/saves.db)"
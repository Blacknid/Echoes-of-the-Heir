#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════
# Michi's Adventure — Cloud Save Server v2 setup
# Target: Raspberry Pi OS (Bookworm / 64-bit), Raspi 5
# ═══════════════════════════════════════════════════════
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="michi-save"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
SERVER_SCRIPT="${APP_DIR}/server.py"
KEY_FILE="${APP_DIR}/server_private_key.pem"
CONFIG_FILE="${APP_DIR}/server_config.json"
PYTHON="$(command -v python3)"

echo "═══════════════════════════════════════════════════════"
echo "  Michi's Adventure — Cloud Save Server v2 setup"
echo "═══════════════════════════════════════════════════════"

# ── 0. Must run as root ──────────────────────────────────
if [[ "$(id -u)" -ne 0 ]]; then
    echo "[!] Run this script with sudo: sudo bash setup.sh"
    exit 1
fi

REAL_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"

# ── 1. Install system packages ───────────────────────────
echo "[1/7] Installing packages..."
apt-get update -qq
apt-get install -y python3 python3-pip python3-cryptography sqlite3 ufw

# Verify import (Bookworm's package is new enough for HKDF + AESGCM)
if ! python3 -c "from cryptography.hazmat.primitives.kdf.hkdf import HKDF; \
                  from cryptography.hazmat.primitives.ciphers.aead import AESGCM" 2>/dev/null; then
    echo "  [!] System cryptography too old, installing via pip..."
    python3 -m pip install --break-system-packages "cryptography>=41"
fi
echo "  [✓] cryptography OK"

# ── 2. Prepare directories ───────────────────────────────
echo "[2/7] Preparing directories..."
mkdir -p "${APP_DIR}/saves"
touch "${APP_DIR}/server.log"
chown -R "${REAL_USER}":"${REAL_USER}" "${APP_DIR}"

# ── 3. Generate RSA key pair ─────────────────────────────
echo "[3/7] RSA key pair..."
if [ ! -f "${KEY_FILE}" ]; then
    echo "  Generating new RSA-2048 key pair..."
    sudo -u "${REAL_USER}" python3 "${APP_DIR}/generate_keys.py"
    chmod 600 "${KEY_FILE}"
    echo "  [✓] Keys written."
    echo
    echo "  *** IMPORTANT ***"
    echo "  Paste the public key from server_public_key.b64 into:"
    echo "    CloudSaveService.RSA_PUBLIC_KEY_B64  (src/data/CloudSaveService.java)"
    echo "  Then recompile and redeploy the game JAR."
    echo
else
    echo "  [✓] Key pair already exists, skipping."
fi

# ── 4. Config file ───────────────────────────────────────
echo "[4/7] Config file..."
if [ ! -f "${CONFIG_FILE}" ]; then
    cp "${APP_DIR}/server_config.example.json" "${CONFIG_FILE}"
    chown "${REAL_USER}":"${REAL_USER}" "${CONFIG_FILE}"
    echo "  [✓] Created ${CONFIG_FILE} from example."
    echo "  Edit it to set license_public_key_b64, ports, etc."
else
    echo "  [✓] Config already exists: ${CONFIG_FILE}"
fi

# ── 5. Firewall ──────────────────────────────────────────
echo "[5/7] Configuring firewall..."
PORT=$(python3 -c "import json; print(json.load(open('${CONFIG_FILE}')).get('port', 5005))" 2>/dev/null || echo 5005)
ufw allow "${PORT}"/tcp comment "Michi Save Server"
ufw --force enable
echo "  [✓] Port ${PORT}/tcp open."

# ── 6. systemd service ───────────────────────────────────
echo "[6/7] Installing systemd service: ${SERVICE_NAME}"
tee "${SERVICE_FILE}" > /dev/null <<EOF
[Unit]
Description=Michi's Adventure Cloud Save Server v2
After=network.target

[Service]
Type=simple
User=${REAL_USER}
WorkingDirectory=${APP_DIR}
ExecStart=${PYTHON} ${SERVER_SCRIPT}
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"
echo "  [✓] Service ${SERVICE_NAME} started."

# ── 7. Status ────────────────────────────────────────────
echo "[7/7] Verifying service..."
sleep 2
systemctl is-active --quiet "${SERVICE_NAME}" && \
    echo "  [✓] Service is running." || \
    echo "  [!] Service may have failed. Check: journalctl -u ${SERVICE_NAME} -n 30"

echo
echo "═══════════════════════════════════════════════════════"
echo "  Setup complete!"
echo
echo "  Service : ${SERVICE_NAME}"
echo "  Config  : ${CONFIG_FILE}"
echo "  Logs    : journalctl -u ${SERVICE_NAME} -f"
echo "  DB      : sqlite3 ${APP_DIR}/saves.db"
echo "  Status  : sudo systemctl status ${SERVICE_NAME}"
echo "═══════════════════════════════════════════════════════"


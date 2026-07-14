#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════
# Michi's Adventure — Multiplayer Server v2 setup
# Target: Raspberry Pi OS (Bookworm / 64-bit), Raspi 5
# ═══════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="michi-multiplayer"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
SERVER_SCRIPT="${SCRIPT_DIR}/server.py"
KEY_FILE="${SCRIPT_DIR}/server_private_key.pem"
CONFIG_FILE="${SCRIPT_DIR}/mp_config.json"
KEYGEN_SCRIPT="${SCRIPT_DIR}/generate_keys.py"
PYTHON="$(command -v python3)"

echo "═══════════════════════════════════════════════════════"
echo "  Michi's Adventure — Multiplayer Server v2 Setup"
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
apt-get install -y python3 python3-pip python3-cryptography ufw

if ! python3 -c "from cryptography.hazmat.primitives.kdf.hkdf import HKDF; \
                  from cryptography.hazmat.primitives.ciphers.aead import AESGCM" 2>/dev/null; then
    echo "  [!] System cryptography too old, installing via pip..."
    python3 -m pip install --break-system-packages "cryptography>=41"
fi
echo "  [✓] cryptography OK"

# ── 2. Make server executable ────────────────────────────
echo "[2/7] Permissions..."
chmod +x "${SERVER_SCRIPT}"
echo "  [✓] server.py is executable."

# ── 3. RSA private key ───────────────────────────────────
echo "[3/7] RSA key pair..."
if [ ! -f "${KEY_FILE}" ]; then
    SAVE_KEY="${SCRIPT_DIR}/../save_server/server_private_key.pem"
    if [ -f "${SAVE_KEY}" ]; then
        echo "  Reusing cloud-save server's RSA key (same Pi, shared identity)."
        cp "${SAVE_KEY}" "${KEY_FILE}"
        chmod 600 "${KEY_FILE}"
        echo "  [✓] Key copied."
    else
        echo "  No key found. Generating a new RSA-2048 key pair for the MP server..."
        # Ensure generate_keys.py is present (copy from save-server dir if not)
        if [ ! -f "${KEYGEN_SCRIPT}" ]; then
            if [ -f "${SCRIPT_DIR}/../save_server/generate_keys.py" ]; then
                cp "${SCRIPT_DIR}/../save_server/generate_keys.py" "${KEYGEN_SCRIPT}"
            else
                echo "  [!] generate_keys.py not found in either server directory. Aborting."
                exit 2
            fi
        fi
        sudo -u "${REAL_USER}" python3 "${KEYGEN_SCRIPT}"
        chmod 600 "${KEY_FILE}"
        echo "  [✓] Keys generated."
        echo
        echo "  *** IMPORTANT ***"
        echo "  Paste the public key from ${SCRIPT_DIR}/server_public_key.b64 into:"
        echo "    MultiplayerClient.RSA_PUBLIC_KEY_B64  (src/main/MultiplayerClient.java)"
        echo "  Then recompile and redeploy the game JAR."
        echo
    fi
else
    echo "  [✓] Key already exists: ${KEY_FILE}"
fi

# ── 3.5 Maps directory ───────────────────────────────────
echo "[3.5/7] Maps directory..."
MAPS_DIR="${SCRIPT_DIR}/maps"
mkdir -p "${MAPS_DIR}"
chown "${REAL_USER}":"${REAL_USER}" "${MAPS_DIR}"
TMX_COUNT=$(find "${MAPS_DIR}" -maxdepth 1 -name '*.tmx' | wc -l)
if [ "${TMX_COUNT}" -eq 0 ]; then
    echo "  [!] No .tmx files in ${MAPS_DIR}"
    echo "      The server will refuse to boot until you copy at least one map there."
    echo "      Operators usually copy the maps you want from your single-player JAR's"
    echo "      /res/maps/*.tmx directly into ${MAPS_DIR}."
else
    echo "  [✓] Found ${TMX_COUNT} map(s) in ${MAPS_DIR}"
fi

# ── 3.6 NPC definitions ──────────────────────────────────
# NPCs are hosted by THIS server, not the client: it owns their dialogue, their activity
# states and their shop stock (see npc.py). So it needs its own copy of npcs.json — the same
# file that ships in the game's assets. Keep the two in sync when you change NPC content, or
# players will see a world whose NPCs disagree with the sprites their client has.
echo "[3.6/7] NPC definitions..."
NPCS_FILE="${SCRIPT_DIR}/npcs.json"
if [ ! -f "${NPCS_FILE}" ]; then
    echo "  [!] No npcs.json in ${SCRIPT_DIR}"
    echo "      The server will boot but host NO NPCs. Copy the game's"
    echo "      core/assets/res/data/npcs.json next to server.py."
else
    echo "  [✓] Found npcs.json"
fi

# ── 4. Config file ───────────────────────────────────────
echo "[4/7] Config file..."
if [ ! -f "${CONFIG_FILE}" ]; then
    cp "${SCRIPT_DIR}/mp_config.example.json" "${CONFIG_FILE}"
    chown "${REAL_USER}":"${REAL_USER}" "${CONFIG_FILE}"
    echo "  [✓] Created ${CONFIG_FILE} from example."
    echo "  Edit it to set license_public_key_b64, max_players, etc."
else
    echo "  [✓] Config already exists: ${CONFIG_FILE}"
fi

# ── 5. Bind address / port ───────────────────────────────
echo "[5/7] Bind address..."
CONFIG_HOST=$(python3 -c "import json; c=json.load(open('${CONFIG_FILE}')); print(c.get('host') or '0.0.0.0')" 2>/dev/null || echo "0.0.0.0")
CONFIG_PORT=$(python3 -c "import json; c=json.load(open('${CONFIG_FILE}')); print(c.get('port') or 7777)"     2>/dev/null || echo 7777)

read -rp "  Bind IP   [${CONFIG_HOST}]: " INPUT_HOST
BIND_HOST="${INPUT_HOST:-${CONFIG_HOST}}"
read -rp "  Bind port [${CONFIG_PORT}]: " INPUT_PORT
BIND_PORT="${INPUT_PORT:-${CONFIG_PORT}}"

python3 -c "
import json, sys
cfg = json.load(open('${CONFIG_FILE}'))
cfg['host'] = '${BIND_HOST}'
cfg['port'] = int('${BIND_PORT}')
json.dump(cfg, open('${CONFIG_FILE}', 'w'), indent=2)
"
chown "${REAL_USER}":"${REAL_USER}" "${CONFIG_FILE}"
echo "  [✓] Config updated: ${BIND_HOST}:${BIND_PORT}"

# ── 6. Firewall ──────────────────────────────────────────
echo "[6/7] Configuring firewall..."
ufw allow "${BIND_PORT}"/tcp comment "Michi Multiplayer"
ufw --force enable
echo "  [✓] Port ${BIND_PORT}/tcp open."

# ── 7. systemd service ───────────────────────────────────
echo "[7/7] Installing systemd service: ${SERVICE_NAME}"
tee "${SERVICE_FILE}" > /dev/null <<EOF
[Unit]
Description=Michi's Adventure Multiplayer Server v2
After=network.target

[Service]
Type=simple
User=${REAL_USER}
WorkingDirectory=${SCRIPT_DIR}
ExecStart=${PYTHON} ${SERVER_SCRIPT} --no-prompt
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

sleep 2
systemctl is-active --quiet "${SERVICE_NAME}" && \
    echo "  [✓] Service is running." || \
    echo "  [!] Service may have failed. Check: journalctl -u ${SERVICE_NAME} -n 30"

echo
echo "═══════════════════════════════════════════════════════"
echo "  Setup complete!"
echo
echo "  Service : ${SERVICE_NAME}"
echo "  Bind    : ${BIND_HOST}:${BIND_PORT}"
echo "  Config  : ${CONFIG_FILE}"
echo "  Logs    : journalctl -u ${SERVICE_NAME} -f"
echo "  Status  : sudo systemctl status ${SERVICE_NAME}"
echo "═══════════════════════════════════════════════════════"

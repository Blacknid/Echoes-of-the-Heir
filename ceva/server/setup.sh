#!/usr/bin/env bash
set -euo pipefail

# Gets the absolute path of the directory where setup.sh is located
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
<<<<<<< HEAD

release_port_5005() {
    pkill -f "${APP_DIR}/server.py" || true
    pkill -f "python3 server.py" || true

    PORT_PIDS="$(ss -ltnp 2>/dev/null | sed -n 's/.*:5005 .*pid=\([0-9]\+\).*/\1/p' | sort -u | tr '\n' ' ')"
    if [[ -n "${PORT_PIDS// }" ]]; then
        echo "  Releasing port 5005 from PID(s): ${PORT_PIDS}"
        kill ${PORT_PIDS} || true
        sleep 1

        REMAINING_PIDS="$(ss -ltnp 2>/dev/null | sed -n 's/.*:5005 .*pid=\([0-9]\+\).*/\1/p' | sort -u | tr '\n' ' ')"
        if [[ -n "${REMAINING_PIDS// }" ]]; then
            echo "  Force killing remaining PID(s): ${REMAINING_PIDS}"
            kill -9 ${REMAINING_PIDS} || true
            sleep 1
        fi
    fi
}
=======
>>>>>>> 144e1ece49117478d634e1ebedadf1983c1020a0

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Run this script with sudo."
    exit 1
fi

<<<<<<< HEAD
echo "[0/6] Stopping existing server process (if running)..."
release_port_5005

echo "[1/6] Installing packages..."
=======
echo "[1/5] Installing packages..."
>>>>>>> 144e1ece49117478d634e1ebedadf1983c1020a0
apt-get update
apt-get install -y python3 python3-pip python3-cryptography ufw

<<<<<<< HEAD
echo "[2/6] Verifying Python dependencies..."
if python3 -c "import cryptography" >/dev/null 2>&1; then
    echo "  cryptography already available."
else
    echo "  cryptography missing, installing with pip fallback..."
    pip3 install --break-system-packages cryptography
fi
=======
echo "[2/5] Installing Python dependencies..."
pip3 install cryptography
>>>>>>> 144e1ece49117478d634e1ebedadf1983c1020a0

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
<<<<<<< HEAD
echo "Starting server in foreground..."
echo ""
echo "Logs will print to the terminal and also to ${APP_DIR}/server.log"
echo "Saves database: ${APP_DIR}/saves.db  (inspect with: sqlite3 ${APP_DIR}/saves.db)"

echo "Final port check before launch..."
release_port_5005

exec python3 "${APP_DIR}/server.py"
=======
echo "To start the server in the foreground, run:"
echo "  python3 ${APP_DIR}/server.py"
echo ""
echo "Logs will print to the terminal and also to ${APP_DIR}/server.log"
echo "Saves database: ${APP_DIR}/saves.db  (inspect with: sqlite3 ${APP_DIR}/saves.db)"
>>>>>>> 144e1ece49117478d634e1ebedadf1983c1020a0

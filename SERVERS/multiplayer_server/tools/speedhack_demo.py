#!/usr/bin/env python3
"""Speed-hack demo client — drives the anti-cheat pipeline for a live demo.

This is a *standalone* protocol client, not a modified game client. It speaks the
same v2 handshake and encrypted framing as the real one (see server.py's module
docstring), then deliberately sends movement the server must reject:

  * "speed"     — teleport-sized jumps that blow past max_tile_step_per_move,
                  tripping the step-cap clamp on every packet.
  * "noclip"    — a straight line through the map regardless of collision,
                  tripping the walkability rollback.
  * "legit"     — well-behaved movement, as a control. Nothing should flag.

Watch the dashboard's Telemetry wall while this runs: warn-level move_violation
lines appear immediately, and once the rolling threshold is crossed the
AnomalyMonitor raises an alert-level anomaly_flag and the player's card turns red.
Open that player's Info panel to see the claimed-vs-allowed path replay.

REQUIREMENTS
  The server must be running with "dev_mode": true in mp_config.json. Dev mode
  skips the save_server license round trip, so this script does not need (and
  never sees) a real license. Against a production server with dev_mode off, the
  handshake is rejected at LOGIN — which is the correct behaviour.

USAGE
  python tools/speedhack_demo.py --mode speed
  python tools/speedhack_demo.py --mode noclip --host 127.0.0.1 --port 7777
  python tools/speedhack_demo.py --mode legit --name Honest
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import struct
import sys
import time
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

BASE_DIR = Path(__file__).resolve().parent.parent
DEFAULT_KEY = BASE_DIR / "server_private_key.pem"

PROTOCOL_TAG = "v2"
DIR_S2C = b"\x01"
DIR_C2S = b"\x02"


def hkdf(secret: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(secret)


class DemoClient:
    """Minimal v2 protocol client: handshake, then encrypted JSON frames."""

    def __init__(self, host: str, port: int, name: str, public_key):
        self.host = host
        self.port = port
        self.name = name
        self.public_key = public_key
        self.reader: asyncio.StreamReader | None = None
        self.writer: asyncio.StreamWriter | None = None
        self.session_key = b""
        self.send_seq = 0
        self.recv_seq = 0
        self.player_id = -1
        self.x = 0
        self.y = 0

    async def connect(self) -> None:
        # Map-chunk frames are far larger than asyncio's default 64 KiB line limit,
        # so raise it to match the server's MAX_LINE_BYTES headroom.
        self.reader, self.writer = await asyncio.open_connection(
            self.host, self.port, limit=4 * 1024 * 1024
        )

        # Stage 1 — HELLO
        client_nonce = os.urandom(16)
        self.writer.write(
            f"HELLO {PROTOCOL_TAG} ".encode()
            + base64.b64encode(client_nonce) + b"\n"
        )
        await self.writer.drain()

        line = (await self.reader.readline()).decode().rstrip("\r\n")
        if not line.startswith("OK "):
            raise RuntimeError(f"server refused HELLO: {line!r}")
        parts = line.split(" ")
        server_nonce = base64.b64decode(parts[1])
        print(f"  server key fingerprint: {parts[2]}")

        # Stage 2 — LOGIN. In dev_mode the server derives the license from the
        # activation_id we present, so any stable string works for a demo.
        activation_id = f"DEMO{os.urandom(4).hex().upper()}"
        handshake = {
            "ts": int(time.time()),
            "client_nonce": client_nonce.hex(),
            "server_nonce": server_nonce.hex(),
            "name": self.name,
            "class": "Fighter",
        }
        enc = self.public_key.encrypt(
            json.dumps(handshake).encode("utf-8"),
            rsa_padding.OAEP(
                mgf=rsa_padding.MGF1(algorithm=hashes.SHA256()),
                algorithm=hashes.SHA256(),
                label=None,
            ),
        )
        self.writer.write(
            b"LOGIN " + base64.b64encode(enc)
            + b" " + activation_id.encode()
            + b" " + base64.b64encode(b"demo-blob") + b"\n"
        )
        await self.writer.drain()

        line = (await self.reader.readline()).decode().rstrip("\r\n")
        if not line.startswith("AUTH_OK "):
            raise RuntimeError(
                f"login rejected: {line!r}\n"
                "  If this says AUTH_FAIL, the server is not in dev_mode — set\n"
                '  "dev_mode": true in mp_config.json and restart it.'
            )

        # The session key is delivered encrypted under a key derived from the
        # license; in dev_mode the license is DEV-<activation_id[:16]>.
        license_key = f"DEV-{activation_id[:16]}"
        delivery_key = hkdf(
            secret=license_key.encode("utf-8") + b"michi-license-pepper-v2",
            salt=server_nonce,
            info=b"michi-delivery-v2",
        )
        self.session_key = AESGCM(delivery_key).decrypt(
            client_nonce[:12], base64.b64decode(line[8:]), b"MichiMpSession"
        )
        print(f"  session established as {self.name!r} (license {license_key})")

    def send_json(self, obj: dict) -> None:
        nonce = os.urandom(12)
        seq = struct.pack(">Q", self.send_seq)
        ct = AESGCM(self.session_key).encrypt(
            nonce, json.dumps(obj, separators=(",", ":")).encode(), DIR_C2S + seq
        )
        self.writer.write(b"DATA " + base64.b64encode(seq + nonce + ct) + b"\n")
        self.send_seq += 1

    async def recv_json(self) -> dict:
        line = await self.reader.readline()
        if not line:
            raise ConnectionError("server closed the connection")
        text = line.decode().rstrip("\r\n")
        if not text.startswith("DATA "):
            raise ValueError(f"unexpected frame: {text[:60]!r}")
        raw = base64.b64decode(text[5:])
        seq, nonce, ct = raw[:8], raw[8:20], raw[20:]
        plaintext = AESGCM(self.session_key).decrypt(nonce, ct, DIR_S2C + seq)
        self.recv_seq += 1
        return json.loads(plaintext.decode())

    async def await_welcome(self) -> None:
        """Read frames until the server tells us where we spawned."""
        deadline = time.time() + 10
        while time.time() < deadline:
            msg = await asyncio.wait_for(self.recv_json(), timeout=10)
            if msg.get("type") == "welcome":
                self.player_id = msg.get("id", -1)
                self.x = msg.get("spawn_x", 0)
                self.y = msg.get("spawn_y", 0)
                print(f"  spawned at ({self.x}, {self.y}) on map "
                      f"{msg.get('map_id','?')} as player {self.player_id}")
                return
        raise RuntimeError("never received welcome")

    async def drain_incoming(self) -> None:
        """Consume server frames in the background so the socket never backs up."""
        try:
            while True:
                msg = await self.recv_json()
                kind = msg.get("type")
                if kind == "pos_correction":
                    # The server rejecting our move — the whole point of the demo.
                    print(f"    <- server corrected us to ({msg['x']}, {msg['y']}) "
                          f"[{msg.get('reason','?')}]")
                elif kind == "kick":
                    print(f"\n  KICKED BY ADMIN: {msg.get('reason','')}")
                    return
        except (ConnectionError, asyncio.IncompleteReadError):
            return
        except Exception as exc:
            print(f"  receive loop ended: {type(exc).__name__}: {exc}")

    async def close(self) -> None:
        if self.writer:
            self.writer.close()
            try:
                await self.writer.wait_closed()
            except Exception:
                pass


async def run(args: argparse.Namespace) -> None:
    key_path = Path(args.key)
    if not key_path.exists():
        sys.exit(f"Server key not found: {key_path}\n"
                 "Pass --key with the path to server_private_key.pem "
                 "(the demo needs its public half to encrypt the handshake).")

    private_key = serialization.load_pem_private_key(
        key_path.read_bytes(), password=None
    )
    public_key = private_key.public_key()

    client = DemoClient(args.host, args.port, args.name, public_key)
    print(f"Connecting to {args.host}:{args.port} …")
    await client.connect()
    await client.await_welcome()

    pump = asyncio.create_task(client.drain_incoming())

    print(f"\nMode: {args.mode} — sending {args.count} moves "
          f"at {args.rate}/s. Watch the dashboard.\n")
    interval = 1.0 / max(1, args.rate)
    start_x, start_y = client.x, client.y

    try:
        for i in range(args.count):
            if args.mode == "speed":
                # Each packet claims a jump far beyond max_tile_step_per_move
                # (default 4 tiles). The server clamps every one of them.
                client.x = start_x + (i + 1) * args.jump
                client.y = start_y
            elif args.mode == "noclip":
                # March in a straight line and let collision rejection fire.
                client.x = start_x + (i + 1) * 32
                client.y = start_y + (i + 1) * 32
            else:  # legit
                # One tile at a time, within the cap — should never flag.
                client.x = start_x + (i + 1) * 4
                client.y = start_y

            client.send_json({
                "type": "move",
                "x": client.x, "y": client.y,
                "dir": 2, "sprite": 1, "attacking": False,
            })
            await client.writer.drain()

            if (i + 1) % 10 == 0:
                print(f"  -> sent {i+1} moves (now claiming x={client.x})")
            await asyncio.sleep(interval)

        print("\nDone sending. Holding the connection open so the dashboard keeps "
              "showing this player — Ctrl+C to disconnect.")
        await pump
    except KeyboardInterrupt:
        pass
    finally:
        pump.cancel()
        await client.close()
        print("Disconnected.")


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Drive the multiplayer anti-cheat with deliberately invalid movement.")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7777)
    ap.add_argument("--name", default="SpeedHacker")
    ap.add_argument("--mode", choices=["speed", "noclip", "legit"], default="speed")
    ap.add_argument("--count", type=int, default=60, help="number of move packets")
    ap.add_argument("--rate", type=int, default=10, help="moves per second")
    ap.add_argument("--jump", type=int, default=400,
                    help="pixels claimed per move in speed mode (cap is ~128)")
    ap.add_argument("--key", default=str(DEFAULT_KEY),
                    help="path to server_private_key.pem")
    args = ap.parse_args()

    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nInterrupted.")


if __name__ == "__main__":
    main()

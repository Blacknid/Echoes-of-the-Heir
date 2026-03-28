#!/usr/bin/env python3
"""
Michi's Adventure - Multiplayer Game Server

This server handles real-time multiplayer synchronization between players.
It is completely separate from the singleplayer cloud-save server (ceva/server/server.py).

Protocol: Newline-delimited JSON over TCP.
Each message is a single JSON object terminated by \\n.
"""

import asyncio
import json
import logging
import signal
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

# ════════════════════════════════════════════════════════════════
# CONFIGURATION
# ════════════════════════════════════════════════════════════════

HOST = "0.0.0.0"
PORT = 7777
MAX_PLAYERS = 8          # Maximum simultaneous players. Change this as needed.
TICK_RATE = 20           # Server broadcast rate (times per second)
PING_TIMEOUT = 15.0      # Seconds before a silent client is kicked
LOG_LEVEL = logging.INFO

# ════════════════════════════════════════════════════════════════
# LOGGING
# ════════════════════════════════════════════════════════════════

logging.basicConfig(
    level=LOG_LEVEL,
    format="[%(asctime)s] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("michi-mp")

# ════════════════════════════════════════════════════════════════
# DATA STRUCTURES
# ════════════════════════════════════════════════════════════════

@dataclass
class PlayerState:
    """Holds the current state of a connected player."""
    player_id: int
    name: str = "Player"
    player_class: str = "Fighter"
    x: int = 0
    y: int = 0
    direction: int = 0
    sprite_num: int = 1
    attacking: bool = False
    life: int = 6
    max_life: int = 6
    last_seen: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return {
            "id": self.player_id,
            "name": self.name,
            "class": self.player_class,
            "x": self.x,
            "y": self.y,
            "dir": self.direction,
            "sprite": self.sprite_num,
            "attacking": self.attacking,
            "life": self.life,
            "maxLife": self.max_life,
        }

    def to_update_dict(self) -> dict:
        return {
            "type": "player_update",
            "id": self.player_id,
            "x": self.x,
            "y": self.y,
            "dir": self.direction,
            "sprite": self.sprite_num,
            "attacking": self.attacking,
            "life": self.life,
            "maxLife": self.max_life,
        }


class ClientConnection:
    """Wraps an asyncio StreamWriter and manages per-client write queue."""

    def __init__(self, writer: asyncio.StreamWriter, player: PlayerState):
        self.writer = writer
        self.player = player
        self._queue: asyncio.Queue[str] = asyncio.Queue(maxsize=256)
        self._task: Optional[asyncio.Task] = None

    def start_writer(self):
        self._task = asyncio.create_task(self._write_loop())

    async def _write_loop(self):
        try:
            while True:
                msg = await self._queue.get()
                self.writer.write((msg + "\n").encode("utf-8"))
                await self.writer.drain()
        except (ConnectionError, asyncio.CancelledError):
            pass

    def send(self, msg: str):
        try:
            self._queue.put_nowait(msg)
        except asyncio.QueueFull:
            log.warning("Write queue full for player %d (%s), dropping message",
                        self.player.player_id, self.player.name)

    async def close(self):
        if self._task:
            self._task.cancel()
        try:
            self.writer.close()
            await self.writer.wait_closed()
        except Exception:
            pass


# ════════════════════════════════════════════════════════════════
# GAME SERVER
# ════════════════════════════════════════════════════════════════

class GameServer:
    def __init__(self):
        self.clients: dict[int, ClientConnection] = {}
        self._next_id = 1
        self._running = False
        self._server: Optional[asyncio.AbstractServer] = None

    def _allocate_id(self) -> int:
        pid = self._next_id
        self._next_id += 1
        return pid

    # ── Broadcasting ──

    def broadcast(self, msg: str, exclude_id: int = -1):
        """Send a message to all connected clients except exclude_id."""
        for pid, client in self.clients.items():
            if pid != exclude_id:
                client.send(msg)

    def broadcast_json(self, obj: dict, exclude_id: int = -1):
        self.broadcast(json.dumps(obj, separators=(",", ":")), exclude_id)

    # ── Client lifecycle ──

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        addr = writer.get_extra_info("peername")
        log.info("New connection from %s", addr)

        # Check capacity
        if len(self.clients) >= MAX_PLAYERS:
            log.info("Server full, rejecting %s", addr)
            try:
                writer.write(json.dumps({"type": "server_full"}).encode("utf-8") + b"\n")
                await writer.drain()
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
            return

        player_id = self._allocate_id()
        player = PlayerState(player_id=player_id)
        client = ClientConnection(writer, player)
        client.start_writer()

        # Wait for join message (first message must be join)
        try:
            raw = await asyncio.wait_for(reader.readline(), timeout=10.0)
            if not raw:
                await client.close()
                return
            msg = json.loads(raw.decode("utf-8").strip())
            if msg.get("type") != "join":
                log.warning("First message from %s was not 'join', disconnecting", addr)
                await client.close()
                return
            player.name = str(msg.get("name", "Player"))[:32]
            player.player_class = str(msg.get("class", "Fighter"))[:16]
        except (asyncio.TimeoutError, json.JSONDecodeError, UnicodeDecodeError) as e:
            log.warning("Bad handshake from %s: %s", addr, e)
            await client.close()
            return

        # Register client
        self.clients[player_id] = client
        log.info("Player %d (%s) joined [%s] — %d/%d players",
                 player_id, player.name, player.player_class,
                 len(self.clients), MAX_PLAYERS)

        # Send welcome with existing players
        existing = [c.player.to_dict() for pid, c in self.clients.items() if pid != player_id]
        welcome = {
            "type": "welcome",
            "id": player_id,
            "players": existing,
        }
        client.send(json.dumps(welcome, separators=(",", ":")))

        # Notify others
        self.broadcast_json({
            "type": "player_join",
            "id": player_id,
            "name": player.name,
            "class": player.player_class,
        }, exclude_id=player_id)

        # ── Main read loop ──
        try:
            while self._running:
                try:
                    raw = await asyncio.wait_for(reader.readline(), timeout=PING_TIMEOUT)
                except asyncio.TimeoutError:
                    log.info("Player %d (%s) timed out", player_id, player.name)
                    break

                if not raw:
                    break  # Client disconnected

                line = raw.decode("utf-8").strip()
                if not line:
                    continue

                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue

                msg_type = msg.get("type")
                player.last_seen = time.time()

                if msg_type == "move":
                    player.x = int(msg.get("x", player.x))
                    player.y = int(msg.get("y", player.y))
                    player.direction = int(msg.get("dir", player.direction))
                    player.sprite_num = int(msg.get("sprite", player.sprite_num))
                    player.attacking = bool(msg.get("attacking", False))
                    player.life = int(msg.get("life", player.life))
                    player.max_life = int(msg.get("maxLife", player.max_life))
                    # Position broadcast happens in the tick loop, not here

                elif msg_type == "chat":
                    chat_msg = str(msg.get("msg", ""))[:200]
                    if chat_msg:
                        log.info("[CHAT] %s: %s", player.name, chat_msg)
                        self.broadcast_json({
                            "type": "chat",
                            "from": player.name,
                            "msg": chat_msg,
                        })

                elif msg_type == "ping":
                    client.send('{"type":"pong"}')

        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            # Cleanup
            self.clients.pop(player_id, None)
            await client.close()
            log.info("Player %d (%s) disconnected — %d/%d players",
                     player_id, player.name, len(self.clients), MAX_PLAYERS)
            self.broadcast_json({
                "type": "player_leave",
                "id": player_id,
            })

    # ── Tick loop (broadcasts positions at fixed rate) ──

    async def tick_loop(self):
        interval = 1.0 / TICK_RATE
        while self._running:
            start = time.monotonic()

            # Broadcast all player positions to all other players
            for pid, client in list(self.clients.items()):
                update = client.player.to_update_dict()
                update_str = json.dumps(update, separators=(",", ":"))
                for other_pid, other_client in self.clients.items():
                    if other_pid != pid:
                        other_client.send(update_str)

            elapsed = time.monotonic() - start
            await asyncio.sleep(max(0, interval - elapsed))

    # ── Server start/stop ──

    async def start(self):
        self._running = True
        self._server = await asyncio.start_server(
            self.handle_client, HOST, PORT,
        )
        addrs = ", ".join(str(s.getsockname()) for s in self._server.sockets)
        log.info("╔══════════════════════════════════════════════════╗")
        log.info("║  Michi's Adventure — Multiplayer Server         ║")
        log.info("║  Listening on: %-33s ║", addrs)
        log.info("║  Max players:  %-33d ║", MAX_PLAYERS)
        log.info("║  Tick rate:    %-33d ║", TICK_RATE)
        log.info("╚══════════════════════════════════════════════════╝")

        # Start the tick broadcaster
        asyncio.create_task(self.tick_loop())

        async with self._server:
            await self._server.serve_forever()

    async def stop(self):
        self._running = False
        # Kick all players gracefully
        for pid, client in list(self.clients.items()):
            client.send(json.dumps({"type": "kick", "reason": "Server shutting down"}))
            await client.close()
        self.clients.clear()
        if self._server:
            self._server.close()


# ════════════════════════════════════════════════════════════════
# ENTRY POINT
# ════════════════════════════════════════════════════════════════

async def main():
    server = GameServer()

    loop = asyncio.get_running_loop()

    def shutdown_handler():
        log.info("Shutdown signal received...")
        asyncio.create_task(server.stop())

    # Register signal handlers (Unix only, ignored on Windows)
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, shutdown_handler)
        except NotImplementedError:
            # Windows doesn't support add_signal_handler for all signals
            signal.signal(sig, lambda s, f: shutdown_handler())

    try:
        await server.start()
    except asyncio.CancelledError:
        pass
    finally:
        await server.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("Server stopped.")
        sys.exit(0)

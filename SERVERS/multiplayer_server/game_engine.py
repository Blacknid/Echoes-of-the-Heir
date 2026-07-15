#!/usr/bin/env python3
"""
Bridge from the Python multiplayer server to the authoritative Java gameplay engine.

The Python server is the network front end — it owns the socket, the AES-GCM handshake, license
verification, bans, rate limiting, the admin console and map/chunk streaming. None of that is
gameplay. The *rules* of the game (combat outcomes, XP, and in time drops and quests) are decided
by the Java engine (SERVERS/multiplayer_server/engine.jar, built from the game's own core module by
`./gradlew :core:engineJar`). That engine runs the SAME classes the client runs, so the server and
client can never disagree about an outcome, and nothing has to be re-implemented in Python.

This module launches that jar as a child process and speaks its line-delimited JSON protocol over
stdin/stdout (see server.EngineServer on the Java side). It is a private loopback link between a
parent and its child, so it needs no crypto of its own.

DEGRADES GRACEFULLY. If the jar is missing, Java isn't installed, or the engine dies, this bridge
reports itself as unavailable and every call returns None. The multiplayer server treats None as
"the engine can't rule on this right now" and falls back to its existing "record what the client
says" behaviour — so the Python side keeps working with or without the jar present, exactly as it
did before the engine existed. The engine is an authority *upgrade*, never a hard dependency.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
from pathlib import Path
from typing import Optional

log = logging.getLogger("michi-mp.engine")

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_JAR = BASE_DIR / "engine.jar"

# How long to wait for the engine to announce itself ready before giving up and running without it.
READY_TIMEOUT = 30.0
# Per-request timeout. A gameplay ruling that takes this long means the engine is wedged; we
# fall back rather than stall the game loop for every player.
REQUEST_TIMEOUT = 2.0


class GameEngine:
    """A handle to the running Java engine, or a no-op stand-in when it isn't available.

    Use `await GameEngine.launch(...)`; it never raises on a missing/broken engine — it returns a
    handle whose `.available` is False and whose calls all return None. Callers must treat None as
    "no ruling, fall back", which keeps the server working when the engine is absent.
    """

    def __init__(self, jar_path: Path):
        self.jar_path = jar_path
        self.available = False
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._reader_task: Optional[asyncio.Task] = None
        self._pending: dict[int, asyncio.Future] = {}
        self._next_rid = 1
        self._write_lock = asyncio.Lock()
        self._ready = asyncio.Event()
        self.ready_info: dict = {}

    # ── lifecycle ────────────────────────────────────────────────────────────

    @classmethod
    async def launch(cls, jar_path: Optional[Path] = None,
                     java_bin: Optional[str] = None) -> "GameEngine":
        """Start the engine jar and wait for it to report ready. Returns a handle that is
        `.available == False` (a working no-op) if anything goes wrong, so the caller can always
        proceed."""
        jar = Path(jar_path) if jar_path else DEFAULT_JAR
        engine = cls(jar)

        if not jar.exists():
            log.warning("Gameplay engine jar not found at %s — running WITHOUT server-side "
                        "gameplay authority. Build it with `./gradlew :core:engineJar`.", jar)
            return engine

        java = java_bin or os.environ.get("MICHI_JAVA_BIN") or shutil.which("java") or "java"
        try:
            engine._proc = await asyncio.create_subprocess_exec(
                java, "-jar", str(jar),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except (OSError, ValueError) as exc:
            log.warning("Could not start gameplay engine (%s) — running WITHOUT server-side "
                        "gameplay authority.", exc)
            return engine

        engine._reader_task = asyncio.create_task(engine._read_loop())
        asyncio.create_task(engine._drain_stderr())

        try:
            await asyncio.wait_for(engine._ready.wait(), timeout=READY_TIMEOUT)
            engine.available = True
            log.info("Gameplay engine ready: %s", engine.ready_info)
        except asyncio.TimeoutError:
            log.warning("Gameplay engine did not report ready within %.0fs — running WITHOUT "
                        "server-side gameplay authority.", READY_TIMEOUT)
            await engine.shutdown()

        return engine

    async def shutdown(self) -> None:
        self.available = False
        if self._reader_task:
            self._reader_task.cancel()
        proc = self._proc
        self._proc = None
        if proc is not None:
            if proc.returncode is None:
                try:
                    # Closing stdin tells the engine's loop to exit cleanly (readLine → null).
                    if proc.stdin and not proc.stdin.is_closing():
                        proc.stdin.close()
                    await asyncio.wait_for(proc.wait(), timeout=5.0)
                except (asyncio.TimeoutError, ProcessLookupError, OSError):
                    try:
                        proc.kill()
                    except ProcessLookupError:
                        pass
            # Explicitly close the stdout/stderr pipe transports. On Windows' Proactor loop these
            # otherwise get closed only at GC time, which logs a spurious "unclosed transport"
            # ResourceWarning after the loop is gone.
            for stream in (proc.stdout, proc.stderr):
                transport = getattr(stream, "_transport", None)
                if transport is not None:
                    try:
                        transport.close()
                    except Exception:
                        pass
        # Fail any in-flight requests so awaiters don't hang forever.
        for fut in self._pending.values():
            if not fut.done():
                fut.set_result(None)
        self._pending.clear()

    # ── protocol I/O ─────────────────────────────────────────────────────────

    async def _read_loop(self) -> None:
        """Read engine events line by line and hand each to whoever is awaiting its rid."""
        assert self._proc and self._proc.stdout
        try:
            while True:
                line = await self._proc.stdout.readline()
                if not line:
                    break                      # engine exited
                try:
                    msg = json.loads(line.decode("utf-8").strip())
                except (ValueError, UnicodeDecodeError):
                    continue
                event = msg.get("event")
                if event == "ready":
                    self.ready_info = msg
                    self._ready.set()
                    continue
                rid = msg.get("rid")
                fut = self._pending.pop(rid, None) if rid is not None else None
                if fut is not None and not fut.done():
                    fut.set_result(msg)
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            log.warning("Gameplay engine reader stopped: %s", exc)
        finally:
            # The engine died — stop pretending we have authority, and release any waiters.
            self.available = False
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_result(None)
            self._pending.clear()

    async def _drain_stderr(self) -> None:
        """Forward the engine's own logging (which it writes to stderr, keeping stdout clean for
        the protocol) into our log at debug level."""
        if not self._proc or not self._proc.stderr:
            return
        try:
            while True:
                line = await self._proc.stderr.readline()
                if not line:
                    break
                log.debug("[engine] %s", line.decode("utf-8", "replace").rstrip())
        except (asyncio.CancelledError, Exception):
            pass

    async def _request(self, payload: dict) -> Optional[dict]:
        """Send one command and await its matching event. Returns None (fall back) if the engine
        is unavailable or doesn't answer in time."""
        if not self.available or not self._proc or not self._proc.stdin:
            return None

        rid = self._next_rid
        self._next_rid += 1
        payload["rid"] = rid
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self._pending[rid] = fut

        try:
            line = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
            async with self._write_lock:
                self._proc.stdin.write(line)
                await self._proc.stdin.drain()
            return await asyncio.wait_for(fut, timeout=REQUEST_TIMEOUT)
        except (asyncio.TimeoutError, ConnectionError, OSError, ValueError) as exc:
            log.debug("Engine request %s failed: %s", payload.get("cmd"), exc)
            self._pending.pop(rid, None)
            return None

    # ── gameplay calls ───────────────────────────────────────────────────────

    async def mob_hit(self, mob_id: int, mob_type: str, damage: int,
                      pid: int) -> Optional[dict]:
        """Ask the engine to rule on a claimed hit. Returns the authoritative mob state
        (`life`, `maxLife`, `alive`, `dying`, `applied`, `killed`, `exp`) or None to fall back to
        trusting the client's numbers."""
        return await self._request({
            "cmd": "mob_hit",
            "mobId": int(mob_id),
            "mobType": str(mob_type),
            "damage": int(damage),
            "pid": int(pid),
        })

    async def reset_mobs(self) -> Optional[dict]:
        """Forget all mob state (e.g. on a map change), so ids can be reused."""
        return await self._request({"cmd": "reset_mobs"})

    async def ping(self) -> Optional[dict]:
        return await self._request({"cmd": "ping"})

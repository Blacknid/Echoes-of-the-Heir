"""In-process publish/subscribe bus for live server telemetry.

The game server publishes structured events here (logins, movement violations,
anomaly flags, admin actions); the admin dashboard subscribes and streams them
to connected browsers over Server-Sent Events. Nothing here touches the network
— it is a fan-out queue and a bounded history buffer, so a slow or absent
dashboard can never block or crash the game loop.

Publishing is deliberately non-blocking and failure-tolerant: `publish()` is a
plain (non-async) call so it can be dropped into any hot path, and a subscriber
whose queue has filled up loses events rather than applying backpressure to the
tick loop.
"""
from __future__ import annotations

import asyncio
import collections
import itertools
import logging
import time
from typing import Any, Optional

log = logging.getLogger(__name__)

# Events kept for replay into a browser that connects late. Roughly a few
# minutes of a busy server; bounded so memory cannot grow without limit.
DEFAULT_HISTORY = 300

# Per-subscriber queue depth. A browser that stops reading (backgrounded tab,
# dead connection) drops events past this point instead of stalling publishers.
SUBSCRIBER_QUEUE_MAX = 200

# Event severities, used by the dashboard for colour-coding.
INFO = "info"
WARN = "warn"
ALERT = "alert"


class EventBus:
    """Fan-out of telemetry events to any number of live subscribers."""

    def __init__(self, history: int = DEFAULT_HISTORY) -> None:
        self._subscribers: set[asyncio.Queue] = set()
        self._history: collections.deque = collections.deque(maxlen=history)
        self._seq = itertools.count(1)

    # ── publishing ───────────────────────────────────────────────────────────

    def publish(
        self,
        kind: str,
        message: str,
        *,
        severity: str = INFO,
        player: str = "",
        license_key: str = "",
        data: Optional[dict[str, Any]] = None,
    ) -> dict:
        """Record an event and fan it out. Safe to call from any sync context.

        `kind` is a stable machine-readable slug (e.g. "move_violation") the UI
        filters on; `message` is the human sentence shown on the wall.
        """
        event = {
            "seq": next(self._seq),
            "ts": time.time(),
            "time": time.strftime("%H:%M:%S", time.localtime()),
            "kind": kind,
            "severity": severity,
            "message": message,
            "player": player,
            "license_key": license_key,
            "data": data or {},
        }
        self._history.append(event)

        for queue in list(self._subscribers):
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                # Subscriber is not keeping up; drop rather than block the caller.
                pass
        return event

    # ── subscription ─────────────────────────────────────────────────────────

    def subscribe(self) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=SUBSCRIBER_QUEUE_MAX)
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue) -> None:
        self._subscribers.discard(queue)

    def history(self, limit: int = 100) -> list[dict]:
        """Most recent events, oldest first, for replay into a new browser."""
        items = list(self._history)
        return items[-limit:] if limit > 0 else items

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)

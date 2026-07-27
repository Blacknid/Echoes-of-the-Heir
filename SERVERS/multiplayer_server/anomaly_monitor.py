"""Background anomaly detector for Michi's Adventure multiplayer server.

Periodically scans connected players for signals that suggest cheating or a
tampered client, and records a "flagged" verdict + human-readable reasons the
admin dashboard reads to render that player's name in red. Detection only —
it never kicks/bans on its own; an admin decides what to do from there.

Config keys (mp_config.json):
  anomaly_scan_interval_s     — seconds between scans (default 10)
  anomaly_move_violation_window_s — rolling window for move-violation count (default 30)
  anomaly_move_violation_threshold — violations in that window to flag (default 8)
  anomaly_max_stat_multiplier — flag if a stat exceeds this multiple of the
                                expected value for the player's level (default 5.0)
"""
from __future__ import annotations

import asyncio
import logging
import time

log = logging.getLogger(__name__)

DEFAULT_SCAN_INTERVAL_S = 10
DEFAULT_MOVE_WINDOW_S = 30
DEFAULT_MOVE_THRESHOLD = 8
DEFAULT_MAX_STAT_MULTIPLIER = 5.0

# Same progression the client seeds new characters with (see server.py DEFAULT_* /
# Player.java setDefaultValues); used only as a rough sanity ceiling, not authoritative data.
_EXPECTED_MAX_LIFE_AT_LVL1 = 4
_EXPECTED_MAX_MANA_AT_LVL1 = 3
_EXPECTED_COIN_PER_LEVEL = 50


class AnomalyMonitor:
    """Flags players whose server-observed behaviour or stats look tampered/cheated."""

    def __init__(self, server, cfg: dict) -> None:
        self._server = server
        self._interval = float(cfg.get("anomaly_scan_interval_s", DEFAULT_SCAN_INTERVAL_S))
        self._move_window_s = float(
            cfg.get("anomaly_move_violation_window_s", DEFAULT_MOVE_WINDOW_S)
        )
        self._move_threshold = int(
            cfg.get("anomaly_move_violation_threshold", DEFAULT_MOVE_THRESHOLD)
        )
        self._max_stat_multiplier = float(
            cfg.get("anomaly_max_stat_multiplier", DEFAULT_MAX_STAT_MULTIPLIER)
        )
        self._running = False
        # license_key -> {"reasons": [...], "flagged_at": iso_str}
        self.flags: dict[str, dict] = {}

    async def run(self) -> None:
        self._running = True
        while self._running:
            try:
                self._scan_once()
            except Exception:
                log.exception("Anomaly scan failed")
            await asyncio.sleep(self._interval)

    def stop(self) -> None:
        self._running = False

    def _scan_once(self) -> None:
        now = time.time()
        seen_licenses = set()
        for client in list(self._server.clients.values()):
            player = client.player
            seen_licenses.add(player.license_key)
            reasons = self._evaluate(player, now)
            if reasons:
                self._raise_flag(player.license_key, reasons, player)
            else:
                if self.flags.pop(player.license_key, None) is not None:
                    self._publish(
                        "anomaly_cleared",
                        f"{player.name} no longer flagged — behaviour back within limits",
                        severity="info", player=player,
                    )
        # Drop stale flags for players who disconnected.
        for license_key in list(self.flags.keys()):
            if license_key not in seen_licenses:
                self.flags.pop(license_key, None)

    def _evaluate(self, player, now: float) -> list[str]:
        reasons: list[str] = []

        window_start = now - self._move_window_s
        violations = player.move_violations
        while violations and violations[0] < window_start:
            violations.popleft()
        if len(violations) >= self._move_threshold:
            reasons.append(
                f"{len(violations)} movement violations in {int(self._move_window_s)}s "
                "(possible speed/noclip hack)"
            )

        expected_max_life = _EXPECTED_MAX_LIFE_AT_LVL1 + (player.level - 1) * 2
        if player.max_life > expected_max_life * self._max_stat_multiplier:
            reasons.append(
                f"maxLife {player.max_life} far exceeds expectation for level "
                f"{player.level} (possible stat tampering)"
            )

        expected_max_mana = _EXPECTED_MAX_MANA_AT_LVL1 + (player.level - 1) * 2
        if player.max_mana > expected_max_mana * self._max_stat_multiplier:
            reasons.append(
                f"maxMana {player.max_mana} far exceeds expectation for level "
                f"{player.level} (possible stat tampering)"
            )

        expected_coin_ceiling = player.level * _EXPECTED_COIN_PER_LEVEL
        if player.coin > expected_coin_ceiling * self._max_stat_multiplier:
            reasons.append(
                f"coin {player.coin} far exceeds expectation for level "
                f"{player.level} (possible currency exploit)"
            )

        return reasons

    def _raise_flag(self, license_key: str, reasons: list[str], player=None) -> None:
        existing = self.flags.get(license_key)
        if existing and existing["reasons"] == reasons:
            return
        self.flags[license_key] = {
            "reasons": reasons,
            "flagged_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            # Snapshot of the movement trace at flag time — frozen evidence, so the
            # dashboard's replay still shows the offending path after the player stops.
            "trace": self._snapshot_trace(player),
        }
        log.warning("Anomaly flag for license=%s: %s", license_key, "; ".join(reasons))
        if player is not None:
            self._publish(
                "anomaly_flag",
                f"{player.name} FLAGGED — {'; '.join(reasons)}",
                severity="alert", player=player,
                data={"reasons": reasons},
            )

    @staticmethod
    def _snapshot_trace(player) -> list[dict]:
        """Freeze the player's recent claimed-vs-allowed positions for replay."""
        if player is None:
            return []
        try:
            return [
                {"ts": ts, "cx": cx, "cy": cy, "ax": ax, "ay": ay, "v": kind}
                for (ts, cx, cy, ax, ay, kind) in list(player.move_trace)
            ]
        except Exception:
            return []

    def _publish(self, kind: str, message: str, *, severity: str,
                 player, data: dict | None = None) -> None:
        publish = getattr(self._server, "publish_event", None)
        if publish is None:
            return
        publish(
            kind, message, severity=severity,
            player=getattr(player, "name", ""),
            license_key=getattr(player, "license_key", ""),
            data=data or {},
        )

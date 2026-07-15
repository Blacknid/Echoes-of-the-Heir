#!/usr/bin/env python3
"""
Server-authoritative skill tree for the Michi MP server.

In singleplayer the client owns the skill tree completely: it reads skilltree.json, checks the
player's skill points against a node's cost, decrements them and applies the effect — all locally.
Offline that's fine. In a shared world it means a client is trusted to say "I spent 2 points and
unlocked Berserker Fury", which a modified client can do for free, in any order, with points it
never earned.

So the server owns the rules of a purchase, exactly the way it already owns a shop purchase:

  * skilltree.json lives next to this file — the same file that ships in the game's assets
    (core/assets/res/data/skilltree.json). It defines each node's cost, its prerequisite, and its
    grid column (used for the reveal gate below).
  * The player's skill-point balance lives on the server (PlayerState.skill_points) and the set of
    nodes they've unlocked lives on the server (PlayerProgress.unlocked_skills).
  * A client sends "I would like to unlock WINDSTEP". The server checks, against ITS numbers, that
    the node exists, isn't already unlocked, its prerequisite is unlocked, its column is revealed,
    and the player can afford it — then debits the points and records the unlock. The client is
    told the result and the effect id to apply; it never gets to set its own point total.

The "reveal" gate mirrors SkillTree.isRevealed on the client: a node in column C is only
purchasable once the player has unlocked something in column C-1 (or C == 0). Enforcing it here
means a client can't skip straight to a deep, cheap-looking node it hasn't earned the path to.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

log = logging.getLogger("michi-mp.skill")

BASE_DIR = Path(__file__).resolve().parent
SKILLTREE_JSON = BASE_DIR / "skilltree.json"


@dataclass
class SkillNode:
    node_id: str
    cost: int
    col: int
    requires: Optional[str]   # node id that must be unlocked first, or None


class SkillCatalog:
    """All skill nodes from skilltree.json, keyed by node id, plus the purchase rules."""

    def __init__(self, path: Path = SKILLTREE_JSON):
        self.path = path
        self.nodes: dict[str, SkillNode] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            log.error(
                "skilltree.json not found at %s — this server cannot authorise skill unlocks and "
                "every skill_unlock will be rejected. Copy it from the game's "
                "core/assets/res/data/skilltree.json.", self.path,
            )
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            log.exception("Failed to parse %s — authorising no skill unlocks", self.path)
            return
        if not isinstance(raw, list):
            log.error("%s: top level must be an array of skill nodes", self.path)
            return

        for entry in raw:
            if not isinstance(entry, dict):
                continue
            node_id = entry.get("id")
            if not isinstance(node_id, str) or not node_id:
                continue
            requires = entry.get("requires")
            if not isinstance(requires, str) or not requires:
                requires = None
            self.nodes[node_id] = SkillNode(
                node_id=node_id,
                cost=max(0, _as_int(entry.get("cost"), 1)),
                col=max(0, _as_int(entry.get("col"), 0)),
                requires=requires,
            )

        log.info("Loaded %d skill nodes from %s", len(self.nodes), self.path.name)

    def get(self, node_id: str) -> Optional[SkillNode]:
        return self.nodes.get(node_id)

    def reveal_max_col(self, unlocked: "set[str]") -> int:
        """Highest column the player may buy into. Mirrors SkillTree.getRevealMaxCol on the
        client: one column past the furthest column they've already unlocked something in (so a
        player with nothing unlocked can still buy any column-0 node)."""
        farthest = 0
        for nid in unlocked:
            node = self.nodes.get(nid)
            if node is not None and node.col > farthest:
                farthest = node.col
        return farthest + 1

    def can_unlock(self, node_id: str, unlocked: "set[str]",
                   skill_points: int) -> tuple[bool, str]:
        """Authoritative check for a purchase. Returns (ok, reason). Every rule the client's
        SkillTree.canUnlock enforces is re-checked here against the server's own numbers, because
        the client's answer cannot be trusted."""
        node = self.nodes.get(node_id)
        if node is None:
            return False, "No such skill."
        if node_id in unlocked:
            return False, "Already unlocked."
        if node.col > self.reveal_max_col(unlocked):
            return False, "Locked — unlock an earlier skill first."
        if node.requires is not None and node.requires not in unlocked:
            return False, "Requires a prerequisite skill."
        if skill_points < node.cost:
            return False, "Not enough skill points."
        return True, "OK"


def _as_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default

#!/usr/bin/env python3
"""
Server-authoritative NPCs for the Michi MP server.

In singleplayer the client owns everything about an NPC: it reads npcs.json out of its own
assets, runs the activity-state machine, picks the dialogue set, and mutates shop stock and
the player's gold locally. That is fine offline, but in a shared world it means every client
is trusted to tell the truth about its own progress and its own wallet — a player who edits
npcs.json or the save file gets free items and any dialogue they like.

Here the server owns all of it:

  * npcs.json lives next to this file (npcs.json in BASE_DIR) and is parsed with the real
    json module — the client's hand-rolled parser is a client-side detail.
  * NPC placements come from the map's own "NPCs" objectgroup (the same Tiled objects
    MapObjectLoader reads client-side), so a map and its NPCs stay in one place.
  * Which activity state / dialogue set a player sees is resolved HERE, against that
    player's server-held progress (level, quests, bosses, fragments, met-NPCs).
  * Shop stock is per-player and server-held; buy/sell debit server-held gold. The client
    never gets to say "I now have 9999 coins" — it can only say "I would like to buy this".

The client is sent only what it needs to draw the NPC (sprite paths, frame counts, scale,
light) plus whatever dialogue the server decided it may see, right now, this interaction.
Every other line of dialogue and every price stays on the server.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

log = logging.getLogger("michi-mp.npc")

BASE_DIR = Path(__file__).resolve().parent
NPCS_JSON = BASE_DIR / "npcs.json"

# Direction constants — must match entity.Entity in the client.
DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP = 0, 1, 2, 3

# A shop listing with "stock": "infinite" (or -1) restocks forever.
INFINITE_STOCK = -1

MAX_SHOP_QTY = 99


@dataclass
class ShopListing:
    item_id: str
    price: int
    max_stock: int = 1          # -1 = infinite

    def infinite(self) -> bool:
        return self.max_stock < 0


@dataclass
class ShopDef:
    sell_multiplier: float = 0.5
    items: list[ShopListing] = field(default_factory=list)

    def listing(self, item_id: str) -> Optional[ShopListing]:
        for it in self.items:
            if it.item_id == item_id:
                return it
        return None


@dataclass
class StateDef:
    """One entry of an NPC's "states" array. Last matching state wins (highest index =
    highest priority), exactly as NPC_Generic.evaluateActivityState does client-side."""
    state_id: str = ""
    animation: Optional[str] = None
    dialogue_name: Optional[str] = None   # named key into dialogues
    dialogue_set: int = -1                # numeric index into dialogues
    direction: int = -1
    offset_col: int = 0
    offset_row: int = 0
    pos_col: int = -1
    pos_row: int = -1
    stationary: bool = False

    # Conditions — ALL must hold for the state to match (AND).
    required_quest_complete: Optional[str] = None
    required_quest_active: Optional[str] = None
    required_fragments: int = -1
    required_boss: int = -1
    required_story_act: int = -1
    required_level: int = -1
    npc_not_met: Optional[str] = None
    marks_npc_met: bool = False


@dataclass
class NpcDef:
    """A parsed npcs.json entry. Presentation fields go to the client; dialogues, states
    and the shop stay here."""
    npc_id: str
    props: dict = field(default_factory=dict)
    dialogues: dict[str, list[str]] = field(default_factory=dict)  # ordered (py3.7+ dicts)
    states: list[StateDef] = field(default_factory=list)
    shop: Optional[ShopDef] = None

    def dialogue_lines(self, key: str) -> list[str]:
        return self.dialogues.get(key, [])

    def dialogue_key_at(self, index: int) -> Optional[str]:
        """Resolve a numeric dialogueSet to a key. Numeric keys ("0", "1") map to themselves;
        named keys are numbered after the highest numeric one, matching NPCFactory's
        nextNamedIdx pass so a JSON file authored for the client behaves identically here."""
        keys = list(self.dialogues.keys())
        numeric: dict[int, str] = {}
        named: list[str] = []
        next_named = 0
        for k in keys:
            try:
                n = int(k)
            except ValueError:
                named.append(k)
                continue
            numeric[n] = k
            if n >= next_named:
                next_named = n + 1
        if index in numeric:
            return numeric[index]
        pos = index - next_named
        if 0 <= pos < len(named):
            return named[pos]
        return None

    def presentation(self) -> dict:
        """The subset of the definition the client needs in order to DRAW this NPC.
        Deliberately excludes dialogues, states and shop — those never leave the server.

        Note the key is "npcId", not "npc_id": the client's JSON reader matches on the literal
        '"<key>":' substring, so a key ENDING in `id` (npc_id, object_id) would also match a
        lookup for "id" and hand back the wrong number. Keeping the id-ish keys camelCase keeps
        them unambiguous. Same reason object_id is sent as "objectId" (see spawn_message)."""
        p = self.props
        out: dict = {"npcId": self.npc_id}
        for key in ("name", "sprite", "idleSprite", "portrait", "dialogueActivity", "lightColor"):
            val = p.get(key)
            if isinstance(val, str) and val:
                out[key] = val
        for key in ("speed", "walkFrameCount", "idleDirection", "dialogueIdleDirection",
                    "interactRange", "depthSortYOffset", "lightRadius", "idleAnimSpeed"):
            val = p.get(key)
            if isinstance(val, (int, float)):
                out[key] = int(val)
        for key in ("staticNPC", "guardMode"):
            if bool(p.get(key, False)):
                out[key] = True
        scale = p.get("spriteScale")
        if isinstance(scale, (int, float)) and scale > 0:
            out["spriteScale"] = float(scale)
        for key in ("walkFramesPerRow", "idleFramesPerRow"):
            val = p.get(key)
            if isinstance(val, list) and val:
                out[key] = [int(v) for v in val]

        # Activities are pure presentation (sprite sheet + frame layout + anim speed), so the
        # client can play them; which one is ACTIVE is decided server-side and pushed per state.
        acts = p.get("activities")
        if isinstance(acts, dict) and acts:
            out["activities"] = {
                name: {k: v for k, v in act.items()
                       if k in ("sprite", "frames", "frameCount", "frameWidth", "frameHeight",
                                "speed", "aspect")}
                for name, act in acts.items()
                if isinstance(act, dict) and act.get("sprite")
            }
        return out


def _as_int(value, default: int = -1) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _parse_state(raw: dict) -> StateDef:
    sd = StateDef()
    sd.state_id = str(raw.get("id", ""))
    anim = raw.get("animation")
    sd.animation = str(anim) if isinstance(anim, str) and anim else None
    sd.direction = _as_int(raw.get("direction"), -1)
    sd.pos_col = _as_int(raw.get("posCol"), -1)
    sd.pos_row = _as_int(raw.get("posRow"), -1)
    sd.stationary = bool(raw.get("stationary", False))

    offset = raw.get("offset")
    if isinstance(offset, list) and len(offset) >= 2:
        sd.offset_col = _as_int(offset[0], 0)
        sd.offset_row = _as_int(offset[1], 0)

    # "dialogue" is always a named key. "dialogueSet" is a number OR a name — the client's
    # parser accepts both (see NPCFactory.parseStates), and npcs.json uses both spellings.
    dlg = raw.get("dialogue")
    if isinstance(dlg, str) and dlg:
        sd.dialogue_name = dlg
    ds = raw.get("dialogueSet")
    if isinstance(ds, str) and ds:
        try:
            sd.dialogue_set = int(ds)
        except ValueError:
            if sd.dialogue_name is None:
                sd.dialogue_name = ds
    elif isinstance(ds, int):
        sd.dialogue_set = ds

    qc = raw.get("requiredQuestComplete")
    sd.required_quest_complete = str(qc) if isinstance(qc, str) and qc else None
    qa = raw.get("requiredQuestActive")
    sd.required_quest_active = str(qa) if isinstance(qa, str) and qa else None
    sd.required_fragments = _as_int(raw.get("requiredFragments"), -1)
    sd.required_boss = _as_int(raw.get("requiredBoss"), -1)
    sd.required_story_act = _as_int(raw.get("requiredStoryAct"), -1)
    sd.required_level = _as_int(raw.get("requiredLevel"), -1)
    nm = raw.get("npcNotMet")
    sd.npc_not_met = str(nm) if isinstance(nm, str) and nm else None
    sd.marks_npc_met = bool(raw.get("marksNpcMet", False))
    return sd


def _parse_shop(raw: dict) -> ShopDef:
    shop = ShopDef()
    try:
        shop.sell_multiplier = float(raw.get("sellMultiplier", 0.5))
    except (TypeError, ValueError):
        shop.sell_multiplier = 0.5
    for item in raw.get("items", []) or []:
        if not isinstance(item, dict):
            continue
        item_id = item.get("id")
        if not isinstance(item_id, str) or not item_id:
            continue
        price = _as_int(item.get("price"), 0)
        raw_stock = item.get("stock", 1)
        if isinstance(raw_stock, str) and raw_stock.lower() == "infinite":
            stock = INFINITE_STOCK
        else:
            stock = _as_int(raw_stock, 1)
        shop.items.append(ShopListing(item_id=item_id, price=max(0, price), max_stock=stock))
    return shop


class NpcCatalog:
    """All NPC definitions from npcs.json, keyed by npc id."""

    def __init__(self, path: Path = NPCS_JSON):
        self.path = path
        self.defs: dict[str, NpcDef] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            log.error(
                "npcs.json not found at %s — this server will host NO NPCs. Copy it from the "
                "game's core/assets/res/data/npcs.json.", self.path,
            )
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            log.exception("Failed to parse %s — hosting no NPCs", self.path)
            return
        if not isinstance(raw, dict):
            log.error("%s: top level must be an object of {npc_id: definition}", self.path)
            return

        for npc_id, body in raw.items():
            if not isinstance(body, dict):
                continue
            d = NpcDef(npc_id=npc_id)
            for key, val in body.items():
                if key == "dialogues":
                    if isinstance(val, dict):
                        d.dialogues = {
                            str(k): [str(line) for line in v]
                            for k, v in val.items() if isinstance(v, list)
                        }
                elif key == "states":
                    if isinstance(val, list):
                        d.states = [_parse_state(s) for s in val if isinstance(s, dict)]
                elif key == "shop":
                    if isinstance(val, dict):
                        d.shop = _parse_shop(val)
                else:
                    d.props[key] = val
            self.defs[npc_id] = d

        log.info("Loaded %d NPC definitions from %s", len(self.defs), self.path.name)

    def get(self, npc_id: str) -> Optional[NpcDef]:
        return self.defs.get(npc_id)

    def has(self, npc_id: str) -> bool:
        return npc_id in self.defs


@dataclass
class NpcInstance:
    """One placed NPC in the world: a definition + where it stands + the stable id the
    client and server both use to talk about it."""
    instance_id: int
    definition: NpcDef
    object_id: str          # persistence key (shop stock, met-NPC set) — Tiled objectId or npc id
    world_x: int            # server pixel coords (tilewidth-sized tiles)
    world_y: int
    spawn_col: int
    spawn_row: int

    def spawn_message(self) -> dict:
        msg = {
            "type": "npc_spawn",
            "id": self.instance_id,
            "objectId": self.object_id,
            "x": self.world_x,
            "y": self.world_y,
        }
        msg.update(self.definition.presentation())
        return msg


class NpcWorld:
    """The NPCs placed on one map, plus the per-player logic that decides what each of them
    says and sells to a given player."""

    def __init__(self, catalog: NpcCatalog, world) -> None:
        self.catalog = catalog
        self.instances: dict[int, NpcInstance] = {}
        self._build(world)

    def _build(self, world) -> None:
        """Instantiate every NPC object in the map's "NPCs" objectgroup. Mirrors
        MapObjectLoader.createNPC: the Tiled object carries an npcId property naming a
        definition in npcs.json (with NPC_Alucard as a legacy alias for "alucard")."""
        next_id = 1
        for group_name, objects in world.object_groups.items():
            if group_name.lower() not in ("npcs", "npc"):
                continue
            for obj in objects:
                otype = (obj.obj_type or "").strip()
                npc_id = obj.properties.get("npcId")
                if not npc_id and otype == "NPC_Alucard":
                    npc_id = "alucard"
                if not isinstance(npc_id, str) or not npc_id:
                    log.warning("Map '%s': NPC object %d has no npcId property — skipped",
                                world.map_id, obj.obj_id)
                    continue
                definition = self.catalog.get(npc_id)
                if definition is None:
                    log.warning("Map '%s': NPC object %d references unknown npcId '%s' — skipped",
                                world.map_id, obj.obj_id, npc_id)
                    continue

                world_x = int(obj.x)
                world_y = int(obj.y)
                object_id = str(obj.properties.get("objectId")
                                or definition.props.get("objectId")
                                or npc_id)
                inst = NpcInstance(
                    instance_id=next_id,
                    definition=definition,
                    object_id=object_id,
                    world_x=world_x,
                    world_y=world_y,
                    spawn_col=world_x // world.tilewidth,
                    spawn_row=world_y // world.tileheight,
                )
                self.instances[next_id] = inst
                next_id += 1

        log.info("Map '%s': placed %d server-hosted NPCs", world.map_id, len(self.instances))

    def get(self, instance_id: int) -> Optional[NpcInstance]:
        return self.instances.get(instance_id)

    def spawn_messages(self) -> list[dict]:
        return [inst.spawn_message() for inst in self.instances.values()]

    # ── state resolution ────────────────────────────────────────────────────

    def resolve_state(self, inst: NpcInstance, progress: "PlayerProgress") -> Optional[StateDef]:
        """Last state whose conditions ALL hold wins — identical priority rule to
        NPC_Generic.evaluateActivityState, so a definition authored against the client
        behaves the same here."""
        winner: Optional[StateDef] = None
        for state in inst.definition.states:
            if self._matches(state, progress):
                winner = state
        return winner

    @staticmethod
    def _matches(state: StateDef, progress: "PlayerProgress") -> bool:
        if state.required_quest_complete:
            if not progress.is_quest_complete(state.required_quest_complete):
                return False
        if state.required_quest_active:
            if not progress.has_quest(state.required_quest_active):
                return False
            if progress.is_quest_complete(state.required_quest_active):
                return False
        if state.required_fragments >= 0:
            if progress.fragment_count() < state.required_fragments:
                return False
        if state.required_boss >= 0:
            if state.required_boss not in progress.bosses_defeated:
                return False
        if state.required_story_act >= 0:
            if progress.story_act < state.required_story_act:
                return False
        if state.required_level >= 0:
            if progress.level < state.required_level:
                return False
        if state.npc_not_met:
            if state.npc_not_met in progress.met_npcs:
                return False
        return True

    def dialogue_for(self, inst: NpcInstance,
                     progress: "PlayerProgress") -> tuple[Optional[StateDef], list[str]]:
        """The lines this player may see from this NPC right now, and the state that chose
        them. An NPC with no matching state falls back to its first dialogue set, which is
        what NPC_Generic's default `dialogueSet++` path lands on for a fresh conversation."""
        state = self.resolve_state(inst, progress)
        definition = inst.definition

        key: Optional[str] = None
        if state is not None:
            if state.dialogue_name:
                key = state.dialogue_name
            elif state.dialogue_set >= 0:
                key = definition.dialogue_key_at(state.dialogue_set)
        if key is None:
            keys = list(definition.dialogues.keys())
            key = keys[0] if keys else None

        lines = definition.dialogue_lines(key) if key else []
        return state, lines


    # ── shop ────────────────────────────────────────────────────────────────

    def shop_message(self, inst: NpcInstance, progress: "PlayerProgress") -> Optional[dict]:
        """The vendor's live stock as this player sees it. Prices and remaining units come
        from the server — the client renders this list and nothing else."""
        shop = inst.definition.shop
        if shop is None or not shop.items:
            return None
        return {
            "type": "npc_shop",
            "id": inst.instance_id,
            "sellMultiplier": shop.sell_multiplier,
            # "itemId", not "item_id" — see NpcDef.presentation for why keys ending in `id`
            # are poison for the client's substring-matching JSON reader.
            "items": [
                {
                    "itemId": listing.item_id,
                    "price": listing.price,
                    "stock": progress.stock_of(inst.object_id, listing),
                }
                for listing in shop.items
            ],
        }

    def buy(self, inst: NpcInstance, progress: "PlayerProgress", player,
            item_id: str, qty: int = 1) -> tuple[bool, str]:
        """Debit the player's SERVER-held gold and the SERVER-held stock. Returns
        (ok, message). Every check that matters happens here; the client's copy of price,
        stock and gold is display state and is never trusted."""
        shop = inst.definition.shop
        if shop is None:
            return False, "This NPC does not sell anything."
        listing = shop.listing(item_id)
        if listing is None:
            return False, "That item is not for sale here."

        qty = max(1, min(MAX_SHOP_QTY, _as_int(qty, 1)))
        remaining = progress.stock_of(inst.object_id, listing)
        if not listing.infinite() and remaining < qty:
            return False, "Out of stock."

        total = listing.price * qty
        if player.coin < total:
            return False, "Not enough coins."

        player.coin -= total
        progress.take_stock(inst.object_id, listing, qty)
        return True, f"Bought {item_id}."

    def sell(self, inst: NpcInstance, progress: "PlayerProgress", player,
             item_id: str, qty: int = 1) -> tuple[bool, str, int]:
        """Credit the player's SERVER-held gold for an item they sold. Returns
        (ok, message, payout).

        The sell price is derived from the vendor's own listing — a vendor won't buy what it
        doesn't deal in — so a client cannot invent a price. What the client CAN still assert
        is that it owns the item: inventory lives on the client for now, so this trusts the
        item id but not its value. Moving inventory server-side closes that last gap and is
        the natural next step."""
        shop = inst.definition.shop
        if shop is None:
            return False, "This NPC does not buy anything.", 0
        listing = shop.listing(item_id)
        if listing is None:
            return False, "This vendor doesn't deal in that.", 0

        qty = max(1, min(MAX_SHOP_QTY, _as_int(qty, 1)))
        unit = round(listing.price * shop.sell_multiplier)
        if unit <= 0:
            return False, "This vendor doesn't deal in that.", 0

        payout = unit * qty
        player.coin = min(999999999, player.coin + payout)
        return True, f"Sold {item_id}.", payout


class PlayerProgress:
    """The server's copy of everything an NPC condition can ask about a player.

    This is the whole point of the exercise: these values live here, not in the client's
    save file, so "requiredLevel: 3" or "requiredQuestComplete: gather_iron" cannot be
    satisfied by a client that simply claims it is so.
    """

    def __init__(self) -> None:
        self.level: int = 1
        self.story_act: int = 0
        self.bosses_defeated: set[int] = set()
        self.fragments: set[str] = set()
        self.met_npcs: set[str] = set()
        # quest id -> complete?
        self.quests: dict[str, bool] = {}
        # "objectId:itemId" -> units remaining (finite-stock listings only)
        self.shop_stock: dict[str, int] = {}

    def has_quest(self, quest_id: str) -> bool:
        return quest_id in self.quests

    def is_quest_complete(self, quest_id: str) -> bool:
        return self.quests.get(quest_id, False)

    def fragment_count(self) -> int:
        return len(self.fragments)

    def stock_of(self, object_id: str, listing: ShopListing) -> int:
        """Units this player can still buy. Infinite listings always report -1; finite ones
        start at max_stock and count down as this player buys them."""
        if listing.infinite():
            return INFINITE_STOCK
        return self.shop_stock.get(f"{object_id}:{listing.item_id}", listing.max_stock)

    def take_stock(self, object_id: str, listing: ShopListing, qty: int) -> None:
        if listing.infinite():
            return
        key = f"{object_id}:{listing.item_id}"
        remaining = self.shop_stock.get(key, listing.max_stock) - qty
        self.shop_stock[key] = max(0, remaining)

    def to_dict(self) -> dict:
        return {
            "level": self.level,
            "storyAct": self.story_act,
            "bossesDefeated": sorted(self.bosses_defeated),
            "fragments": sorted(self.fragments),
            "metNPCs": sorted(self.met_npcs),
            "quests": dict(self.quests),
            "shopStock": dict(self.shop_stock),
        }

    def load_from_dict(self, d: dict) -> None:
        if not isinstance(d, dict):
            return
        self.level = _as_int(d.get("level"), 1)
        self.story_act = _as_int(d.get("storyAct"), 0)
        self.bosses_defeated = {_as_int(b, -1) for b in d.get("bossesDefeated", []) or []}
        self.bosses_defeated.discard(-1)
        self.fragments = {str(f) for f in d.get("fragments", []) or []}
        self.met_npcs = {str(n) for n in d.get("metNPCs", []) or []}
        quests = d.get("quests", {}) or {}
        if isinstance(quests, dict):
            self.quests = {str(k): bool(v) for k, v in quests.items()}
        stock = d.get("shopStock", {}) or {}
        if isinstance(stock, dict):
            self.shop_stock = {str(k): _as_int(v, 0) for k, v in stock.items()}

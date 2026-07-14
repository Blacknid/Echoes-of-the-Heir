# NPC JSON Guide

This guide explains how to create and configure NPCs using `res/data/npcs.json`.
Adding a new NPC is **zero Java** — you define it in JSON, place it in Tiled with two properties, and it works.

---

## Big Picture

```
res/data/npcs.json          ← you define the NPC here
        ↓
NPCFactory.java             ← reads the JSON at runtime
        ↓
Tiled map (.tmx)            ← you place the NPC here with  type = NPC_Generic
                                                            npcId = your_npc_id
        ↓
MapObjectLoader.java        ← sees npcId, calls NPCFactory, then applies
                               any extra Tiled properties on top (gift, delivery, etc.)
```

Two properties in Tiled are all you need to link an NPC to its JSON definition:

| Tiled Property | Type   | Value               |
|----------------|--------|---------------------|
| `type`         | String | `NPC_Generic`       |
| `npcId`        | String | your JSON key       |

Everything else — name, sprite, portrait, all dialogue — comes from the JSON.

---

## JSON File Structure

`npcs.json` is a **top-level object** keyed by NPC id.
Each key must match the `npcId` you set in Tiled.

```json
{
  "your_npc_id": {
    ...NPC definition...
  },
  "another_npc": {
    ...
  }
}
```

> Note: unlike `monsters.json` (which is a top-level array `[...]`), `npcs.json` uses a top-level object `{...}`.

---

## Root Fields

These go directly inside the NPC's JSON object.

| Field           | Type    | Default  | Description |
|-----------------|---------|----------|-------------|
| `name`          | String  | `"NPC"`  | Display name shown in the dialogue box header |
| `sprite`        | String  | —        | Resource path to the **walk** spritesheet (no `.png`). Required if the NPC walks. |
| `idleSprite`    | String  | —        | Resource path to the **idle** spritesheet. If omitted, the walk sheet is reused for idle. |
| `speed`         | int     | `1`      | Movement speed in pixels per tick |
| `walkFrameCount`| int     | `6`      | Number of frames per row in the walk sheet |
| `staticNPC`     | bool    | `false`  | `true` = NPC never wanders or path-follows. Use for stationary characters. |
| `guardMode`     | bool    | `false`  | `true` = NPC faces the player constantly; starts walking only when triggered |
| `idleDirection` | int     | `-1`     | Force a permanent facing direction while idle. `0`=down `1`=left `2`=right `3`=up |
| `portrait`      | String  | —        | Resource path to the portrait image shown in the dialogue box (e.g. `/res/NPC/portraits/soldier.png`) |

**Sprite path rules:**
- Start with `/res/` so the resource loader can find the file.
- Omit `.png` — the loader adds it automatically.
- The walk sheet must be organized as rows of directions: row 0 = down, row 1 = up, row 2 = left, row 3 = right.

---

## Dialogues Block

```json
"dialogues": {
  "0": ["Line one.", "Line two.", "Line three."],
  "intro": ["Take this sword. I can't use it anymore."],
  "waiting": ["You're back... but I still need a bandage."],
  "thanks": ["You found one! Here, take these coins."]
}
```

- Keys can be **string numbers** (`"0"`, `"1"`) or **named keys** (`"intro"`, `"thanks"`) — both work.
- Each set is an **array of strings** — one string per dialogue line in the box.
- Use `\n` inside a string for a line break within a single bubble.
- Named keys are resolved at runtime via `Entity.dialogueNameMap`.
  - Named keys are assigned sequential indices in the order they appear (after any numeric keys).
  - e.g. `"intro"` becomes index 0, `"waiting"` index 1, `"thanks"` index 2.
- In quest steps, reference named keys directly: `"dialogue": "intro"`, `"failDialogue": "waiting"`.
- In states, use `"dialogue": "named_key"` (string) or `"dialogueSet": 0` (int index) — both work.
- The engine falls back to set `0` if nothing else is active.

---

## Activities Block

Activities are **custom animations** played while the NPC is stationary (working at an anvil, meditating, etc.).
If your NPC just stands and talks, you don't need this block.

```json
"activities": {
  "forge": {
    "sprite": "/res/NPC/smith_forge-sheet",
    "frames": [4, 4, 4, 4],
    "speed": 10
  },
  "pray": {
    "sprite": "/res/NPC/monk_pray-sheet",
    "frames": [3, 3, 3, 3],
    "speed": 15
  }
}
```

| Field    | Type       | Description |
|----------|------------|-------------|
| `sprite` | String     | Resource path to the activity spritesheet (same format as walk sheet) |
| `frames` | int array  | Frames per row `[down, up, left, right]`. Default `[6,6,6,6]`. |
| `speed`  | int        | Ticks between frame advances. Lower = faster. Default `10`. |

To play an activity in-game, reference its name in a **state's** `animation` field.

---

## Shop Block

Turns this NPC into a vendor. When present, talking to the NPC opens the Buy/Sell shop screen
instead of dialogue (any active quest step for this NPC still takes priority — see Worked
Example 4 below).

```json
"shop": {
  "sellMultiplier": 0.5,
  "items": [
    { "id": "iron_sword", "price": 75 },
    { "id": "bandage", "price": 10, "stock": 5 }
  ]
}
```

| Field                    | Type   | Default | Description |
|---------------------------|--------|---------|-------------|
| `sellMultiplier`          | float  | `0.5`   | Fraction of an item's listed `price` the vendor pays when the **player** sells it back. Only applies to items this vendor also buys (i.e. appear in `items`) — the vendor won't buy things outside their own stock. |
| `items`                   | array  | —       | The vendor's stock. Each entry: |
| `items[].id`               | String | —       | An id from `res/data/items.json` |
| `items[].price`            | int    | `0`     | Coins the player pays to buy one |
| `items[].stock`            | int    | infinite| Units available. Omit for infinite stock. If set, decrements on purchase and **persists across saves** (keyed by this NPC's `objectId`). |

The shop UI is the same declarative `Menu`/`MenuItem` system used for Settings/Pause — rows
grey out automatically when the player can't afford an item or it's out of stock.

---

## States Block

States let the NPC **automatically change behaviour based on quest/game progress**.
States are evaluated in order — **the last matching state wins**.
This means you list states from least specific (default) to most specific (end-game).

```json
"states": [
  { "id": "default",  "dialogueSet": 0 },
  { "id": "active",   "dialogueSet": 1, "requiredQuestActive": "find_sword" },
  { "id": "done",     "dialogueSet": 2, "requiredQuestComplete": "find_sword" }
]
```

### Position Fields

| Field        | Type    | Description |
|--------------|---------|-------------|
| `direction`  | int     | Face this direction in this state. `0`=down `1`=left `2`=right `3`=up. `-1` = no override |
| `offset`     | [col, row] | Move the NPC **relative to its spawn tile** when this state activates, e.g. `[2, 0]` = 2 tiles right |
| `posCol`     | int     | Move NPC to an **absolute tile column** when this state activates |
| `posRow`     | int     | Move NPC to an **absolute tile row** when this state activates |
| `stationary` | bool    | `true` = NPC stays put in this state (no wander, no path on re-trigger) |
| `dialogueSet`| int     | Which dialogue set **index** to use while this state is active |
| `dialogue`   | String  | Named dialogue **key** to use (e.g. `"done"`). Preferred over `dialogueSet` for named dialogue workflows |
| `animation`  | String  | Name of an activity defined in the `"activities"` block to play in this state |

> `offset` and `posCol/posRow` are mutually exclusive. Use `offset` when you want positioning relative to the spawn point (more portable). Use `posCol/posRow` for a fixed map position.

### Condition Fields

All conditions are **AND logic** — every field you add must be true for the state to match.
Leave a field out to ignore that condition.

| Field                   | Type   | Description |
|-------------------------|--------|-------------|
| `requiredQuestComplete` | String | Quest ID that must be **completed** |
| `requiredQuestActive`   | String | Quest ID that must be **started but not yet complete** |
| `requiredLevel`         | int    | Player must be at or above this level |
| `requiredFragments`     | int    | Player must have collected this many memory fragments |
| `requiredBoss`          | int    | Boss number (1–4) that must be defeated (`gp.boss1Defeated`, etc.) |
| `requiredStoryAct`      | int    | `gp.storyAct` must equal this value |

### State Evaluation Timing

- States are re-evaluated every time `setAction()` is called (roughly every second).
- When the active state changes, the NPC pathfinds to the new position and switches animation.
- During dialogue (`speak()`), the activity animation pauses so the NPC faces the player naturally.

---

## What Goes in Tiled

For step-based quests (the recommended approach), **only two Tiled properties are needed**:

| Tiled Property | Type   | Value         |
|----------------|--------|---------------|
| `type`         | String | `NPC_Generic` |
| `npcId`        | String | your JSON key |

Everything else — name, sprite, portrait, all dialogue, quest steps, states — comes from `npcs.json` and `quests.json`.

### Legacy Tiled Properties

Old NPCs that don't use `npcId` (or that predate the step system) can still set quest logic as Tiled object properties. These are supported for backward compatibility but are **not recommended** for new NPCs:

| Tiled Property           | What it does |
|--------------------------|--------------|
| `giftItem`               | Item ID given to the player on first interaction |
| `giftDialogueSet`        | Dialogue set used when the item is handed out |
| `giftQuestId`            | Quest started when the gift is given |
| `walkToDialogueSet`      | Dialogue set after the NPC walks to a destination |
| `deliveryItem`           | Item the player must return with |
| `deliveryDialogueSet`    | Dialogue played when the player brings the item |
| `deliveryConsumeItem`    | `true` = remove the item from player inventory |
| `deliveryQuestId`        | Quest progressed when delivery succeeds |
| `deliveryPostDialogueSet`| Dialogue set after the delivery is done |
| `deliveryRewardCoins`    | Coins rewarded on delivery |
| `onSpeakQuestId`         | Quest progressed every time the player talks |
| `step<N>_walkToCol/Row`  | Step-chain walking sequence |

> For new NPCs, put all of this into `quests.json` steps and `npcs.json` dialogues. See [QUEST_GUIDE.md](QUEST_GUIDE.md).

---

## Worked Examples

### Example 1 — Simple Talker (minimal setup)

A map guard who says hello. No quest logic, no movement.

**npcs.json:**
```json
"town_guard": {
  "name": "Guard",
  "sprite": "/res/NPC/guard_walk-sheet",
  "staticNPC": true,
  "idleDirection": 0,
  "portrait": "/res/NPC/portraits/guard.png",

  "dialogues": {
    "0": ["Halt! State your business.", "The market is open till sundown."]
  }
}
```

**Tiled object properties:**
```
type    = NPC_Generic
npcId   = town_guard
```

---

### Example 2 — The Soldier (step-based quest, from Awakening Cave)

The Soldier gives the player a sword (step 1), then asks for a bandage (step 2).
All dialogue and quest logic are driven by `quests.json` steps + `npcs.json` named dialogues.
Tiled only needs two properties.

**npcs.json:**
```json
"sword_giver_npc": {
  "name": "Soldier",
  "sprite": "/res/NPC/Tutorial_idle",
  "idleSprite": "/res/NPC/Tutorial_idle",
  "staticNPC": true,
  "idleDirection": 2,
  "idleAnimSpeed": 12,
  "portrait": "/res/NPC/portraits/soldier.png",

  "dialogues": {
    "intro": [
      "...Hey. I was ambushed on the road.",
      "My arm is useless, and I can't swing anymore.",
      "Take this sword. Use it better than I can right now.",
      "If you find a bandage, come back. I still need help."
    ],
    "waiting": [
      "You're back... but I still need a bandage.",
      "Check the cave rooms."
    ],
    "thanks": [
      "You found one... good. This should stop the bleeding.",
      "Thank you. Here, take these 25 coins for the trouble."
    ],
    "done": [
      "The bandage helped a lot.",
      "Keep the sword. You earned it."
    ]
  },

  "states": [
    {
      "id": "post_quest",
      "dialogue": "done",
      "stationary": true,
      "requiredQuestComplete": "sword_giver_bandage"
    }
  ]
}
```

**quests.json:**
```json
{
  "id": "sword_giver_bandage",
  "name": "Aid the Wounded Soldier",
  "description": "Help the soldier in the cave.",
  "steps": [
    { "action": "talk",    "npc": "sword_giver_npc", "dialogue": "intro",
      "give": "wooden_sword", "description": "Talk to the wounded soldier" },
    { "action": "deliver", "npc": "sword_giver_npc", "item": "bandage",
      "consume": true, "dialogue": "thanks", "failDialogue": "waiting",
      "description": "Bring a bandage to the soldier" }
  ],
  "rewardCoins": 25,
  "chainQuestId": "meet_hurt_soldier"
}
```

**Tiled object properties (that's all):**
```
type  = NPC_Generic
npcId = sword_giver_npc
```

Flow at runtime:
1. Player talks → quest auto-starts → step 0 executes: player receives `wooden_sword`, "intro" dialogue plays.
2. Player talks again (no bandage) → "waiting" dialogue plays (failDialogue).
3. Player returns with bandage → step 1 executes: bandage consumed, "thanks" plays, quest completes.
4. Quest complete → 25 coins rewarded → `meet_hurt_soldier` starts → state `post_quest` activates → "done" dialogue.

---

### Example 3 — Quest-Driven NPC with Activity Animation

An elder who meditates until you finish a quest, then stands and speaks.

**npcs.json:**
```json
"village_elder": {
  "name": "Elder Maren",
  "sprite": "/res/NPC/elder_walk-sheet",
  "idleSprite": "/res/NPC/elder_idle-sheet",
  "staticNPC": true,
  "idleDirection": 0,
  "portrait": "/res/NPC/portraits/elder.png",

  "activities": {
    "meditate": {
      "sprite": "/res/NPC/elder_meditate-sheet",
      "frames": [4, 4, 4, 4],
      "speed": 20
    }
  },

  "dialogues": {
    "0": [
      "...",
      "I am in communion with the ancestors.",
      "Do not disturb me until the forest is quiet."
    ],
    "1": [
      "You have silenced the corruption.",
      "The ancestors speak clearly now.",
      "Take this — the seal of our clan."
    ],
    "2": [
      "You carry the seal.",
      "Walk with our blessing."
    ]
  },

  "states": [
    {
      "id": "meditating",
      "animation": "meditate",
      "direction": 0,
      "dialogueSet": 0,
      "stationary": true
    },
    {
      "id": "quest_done",
      "direction": 0,
      "dialogueSet": 1,
      "stationary": true,
      "requiredQuestComplete": "silence_the_forest"
    },
    {
      "id": "item_given",
      "direction": 0,
      "dialogueSet": 2,
      "stationary": true,
      "requiredQuestComplete": "receive_clan_seal"
    }
  ]
}
```

**Tiled object properties:**
```
type               = NPC_Generic
npcId              = village_elder
giftItem           = clan_seal
giftDialogueSet    = 1
giftQuestId        = receive_clan_seal
requiredQuestComplete = silence_the_forest
```

> The `requiredQuestComplete` Tiled property locks the gift behind quest completion.
> Once the gift is given, the state system transitions to `"item_given"` automatically.

---

## Quick Reference — State Conditions

```
requiredQuestComplete  = "quest_id"    ← quest must be finished
requiredQuestActive    = "quest_id"    ← quest must be started but not finished
requiredLevel          = 5             ← player.level >= 5
requiredFragments      = 3             ← memoryJournal has >= 3 fragments
requiredBoss           = 2             ← boss 2 defeated (boss2Defeated == true)
requiredStoryAct       = 1             ← gp.storyAct == 1
```

All conditions in a single state are **AND**. To make OR conditions, create multiple states with overlapping dialogues:
```json
{ "id": "act1_version", "dialogueSet": 1, "requiredStoryAct": 1 },
{ "id": "act2_version", "dialogueSet": 1, "requiredStoryAct": 2 }
```

---

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| NPC shows default dialogue only | No `npcId` set in Tiled, or `npcId` doesn't match a JSON key |
| NPC is invisible / not loaded | `sprite` path is wrong or spritesheet not in `res/NPC/` |
| State never activates | Condition not met — check quest IDs match exactly |
| Activity animation not playing | `animation` field doesn't match an `activities` key |
| Last state is always active | **Intentional** — last matching state wins. Put the default state first. |
| Portrait not showing | Path must start with `/res/NPC/portraits/` and match the filename exactly |

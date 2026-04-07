# Tiled Entity & Map Configuration Guide

This guide covers everything you can configure in **Tiled** without touching Java code.
The game engine reads your `.tmx` files at runtime and applies all values automatically.

---

## Big Picture

```
You edit a .tmx file in Tiled
        ↓
Map Properties  →  music, weather, lighting, spawn point
Object Layers   →  entities, monsters, NPCs, events, spawn points
Tile Layers     →  animated tiles, flip flags, opacity, tint
Image Layers    →  background images with parallax
        ↓
Everything appears in-game — no Java changes needed
```

---

## Map-Level Properties

Set these in **Map → Properties** (the wrench icon for the whole map).

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `mapName` | String | `""` | Display name shown in the HUD |
| `music` | String | `""` | Music name (`"theme"`, `"dungeon"`) or SFX index (`"3"`) |
| `weather` | String | `CLEAR` | `CLEAR` \| `RAIN` \| `STORM` \| `SNOW` |
| `ambientLight` | float | *(cycle)* | Light filter alpha 0.0 (bright) → 0.95 (very dark). Overrides day/night when set |
| `lightLevel` | int | *(cycle)* | Skip `ambientLight` — just set time of day: `0`=day `1`=dusk `2`=night `3`=dawn |
| `spawnCol` | int | *(map center)* | Default tile column where the player spawns on this map |
| `spawnRow` | int | *(map center)* | Default tile row |
| `bgColor` | String | `#000000` | Background clear color as `#rrggbb` hex |
| `weatherCycle` | bool | `true` | `false` disables both day/night cycling and auto-weather changes |
| `dialogueTrigger` | String | `""` | HUD message shown once when the player spawns on this map |
| `dialogueTriggerDuration` | int | `300` | How many frames (÷60 = seconds) the `dialogueTrigger` message stays on screen |
| `actTitle` | String | `""` | Large fade-in/fade-out title card on map entry (e.g. `"Act I: The Awakening"`) |

**Example** — a rainy dungeon night scene:
```
mapName   = Forgotten Dungeon
music     = dungeon
weather   = RAIN
lightLevel = 2
spawnCol   = 10
spawnRow   = 5
```

---

## Object Layers

Each map can have these named Object Layers (right-click Layers → New → Object Layer):

| Layer Name | Contents | Backing array |
|---|---|---|
| `Objects` | Items, chests, doors, torches, etc. | `gp.obj[100]` |
| `Monsters` | All enemy types | `gp.monster[20]` |
| `NPCs` | Friendly characters | `gp.npc[10]` |
| `InteractiveTiles` | Breakable pots, coin piles | `gp.iTile[30]` |
| `Events` | Triggers, transitions, spawn points | EventHandler |
| `Collision` | Rectangles/polygons/ellipses for solid walls | TileManager |

The `type` property (Tiled 1.8) or `class` attribute (Tiled 1.9+) identifies the entity.

> **Tiled 1.9+ users:** Use the **Class** field in the object properties panel instead of a custom `type` property. Both work.

---

## Common Entity Properties (all object types)

| Property | Tiled Type | Description |
|---|---|---|
| `facing` | String | Initial direction: `up` `down` `left` `right` |
| `id` | String | Persistent object ID (used by quests/save system to track state) |
| `invisible` | bool | `true` = skip rendering (useful for invisible trigger entities) |
| `removeOnPickup` | bool | For touch-pickups in the `Objects` layer. `true` = remove the world object after a successful pickup. Default is `true`. |

---

## Objects Layer

Place **Point** objects (`P` key in Tiled) for all items.

### Supported Object Types

| `type` | Creates | Extra Properties |
|---|---|---|
| `Chest` | Treasure chest | `loot`/`lootId` (String), `opened` (bool), `requiredItem` (String), `consumeItem` (bool), `openAnimation` (String), `openFrames` (int) |
| `Door` | Door / portal | `destination` (String), `destCol` (int), `destRow` (int), `isLocked` (bool), `spawnId` (String), `spawnDirection` (String: up/down/left/right) |
| `Tent` | Rest tent | — |
| `Boots` | Speed boots | — |
| `Potion` | Health potion | `amount` (int) – stack count |
| `Torch` | Light source | `lightRadius` (int, pixels), `lightColor` (String `#rrggbb`) |
| `Key` | Door key | `amount` (int) |
| `Gem` | Victory gem | — |
| `Book` | Readable book | — |
| `Tower` | Tower (spawns Eye on top) | — |
| `Sword` | Normal sword | — |
| `Shield` | Wood shield | — |
| `Coins` | Coin pickup | `coinValue` (int) – value, `amount` (int) – pickup count |
| `Heart` | Heart pickup | — |
| `Mana` | Mana crystal | — |
| `Arrow` | Arrow ammo | `amount` (int) |
| `Compas` | Compass item | — |
| `Light` / `Lighting` | Invisible light source | `lightRadius` (int, default 4), `lightColor` (String `#rrggbb`) |
| `Item` | Any ItemFactory item | `itemId` (String, recommended), `factoryId` (String, alias), `amount` (int for stackables), `removeOnPickup` (bool) |

#### Loot Names (for Chest `loot` property)
`Compas` | `Key` | `Potion` | `Boots` | `Gem` | `Sword` | `Shield`

#### Recommended Data-Driven Item Workflow

For new pickups, prefer this pattern instead of adding new Java classes:

```xml
type   = Item
itemId = bandage
amount = 1
removeOnPickup = true
```

- `itemId` loads an item from `res/data/items.json`
- `factoryId` is supported as an alias for `itemId`
- `removeOnPickup` defaults to `true`, which is what you want for normal pickups like Bandages, Potions, Coins, etc.
- If you set `removeOnPickup=false`, the object stays on the map after pickup. Use that only when you intentionally want a reusable pickup.
- Chests also support `lootId` as a future-proof alias for `loot`
- Legacy object types like `Potion`, `Sword`, `Shield`, `Key` still work

### Best Practice

- Use `type=Item` + `itemId=...` for ordinary floor pickups.
- Leave `removeOnPickup=true` for those pickups so the player only gets them once.
- Use `type=Chest` for containers. Chests are interactable objects, not touch-pickups, so they do not need `removeOnPickup` for normal usage.

---

## Monsters Layer

Place **Point** objects. All monsters also accept the common entity properties.

| `type` | Creates | Extra Properties |
|---|---|---|
| `MON_monster` | Basic melee (Mummy) | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |
| `MON_SkeletonArcher` | Ranged skeleton | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |
| `MON_Shade` | Shade melee | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |
| `MON_Inkblot` / `Inkblot` | Tutorial slime | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |
| `BOSS_WitheredTree` | Withered Tree boss | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |
| *(any `monsters.json` id)* | Data-driven monster | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |

Valid data-driven monster IDs (from `res/data/monsters.json`): `painted_crab`, `drowned_sketch`, `shade_wolf`, `canvas_moth`, `hollow_stump`, `painted_guard`, `portrait_ghost`, `ink_knight`, `inkblot`, `withered_tree`.

### Monster Common Properties

All monsters accept these properties:

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `level` | int | `1` | Level scaling: +50% HP and +25% ATK per level above 1 |
| `aggroRange` | int | *(JSON default)* | Detection range in pixels — overrides the JSON/class default |
| `wanderRadius` | int | *(JSON default)* | Max wander distance from spawn in pixels |
| `bossId` | int | `0` | Wraps monster in BossMonster class when > 0. Enables boss health bar, AI phases, death story triggers |
| `phase2Threshold` | float | `0.5` | HP fraction (0.0–1.0) at which the boss enters phase 2 (requires `bossId > 0`) |
| `phase2SpeedBoost` | int | `1` | Extra speed added in phase 2 (requires `bossId > 0`) |

**Monster Area** — place a **Rectangle** object in the `Monsters` layer with `type = MonsterArea` to spawn N monsters randomly within that rectangle:

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `monster` | String | `MON_monster` | Same `type` values as above (`MON_monster`, etc.) |
| `count` | int | `3` | How many to spawn |
| `level` | int | `1` | Level for all spawned monsters |

---

## NPCs Layer

Place **Point** objects. All NPCs also accept the common entity properties (`facing`, `id`, `invisible`).

| `type` | Creates |
|---|---|
| `NPC_Alucard` | Alucard-style scripted NPC |
| `NPC_Generic` | Fully data-driven NPC recommended for new content |

### NPC Properties

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `sprite` | String | — | Walk-sheet resource path for `NPC_Generic`, without `.png` (example: `/res/npc/Alucard_walking-sheet`) |
| `idleSprite` | String | — | Optional idle-sheet resource path for `NPC_Generic` |
| `dialogue0`–`dialogue4` | String | *(built-in)* | Override the **first line** of dialogue set 0–4. Use `\n` for line breaks. Does not replace later lines in that set. |
| `dialogue_<set>_<line>` | String | — | Full data-driven dialogue lines. Example: `dialogue_2_1 = Thank you.` |
| `portrait` | String | — | Path to a 96×96 portrait image shown in the dialogue box (e.g. `/res/NPC/Elder_portrait`) |
| `idleAnimSpeed` | int | `24` | Ticks between idle animation frame advances. Lower = faster idle cycle |
| `wanderRadius` | int | `0` (free) | Max pixel distance the NPC may wander from its spawn point. `0` = unlimited wander. |
| `staticNPC` | bool | `false` | `true` = NPC stands still forever — no wander, no pathfinding. Ideal for town signs, stationary quest-givers. |
| `guardMode` | bool | `false` | Like `staticNPC` but the NPC also turns to face the player every tick. Stays locked until `onPath = true` is set in code (e.g. after a speak/quest trigger). Use for guards, sentinels, or immobile watchers. |
| `idleDirection` | int | `-1` | Forced idle direction. `0`=down `1`=left `2`=right `3`=up |
| `walkToCol` | int | `-1` | After the player interacts with this NPC, it walks to this tile column. On arrival it re-enters `guardMode` automatically. Requires `guardMode = true` and the NPC's `speak()` to set `onPath = true`. |
| `walkToRow` | int | `-1` | Tile row partner of `walkToCol`. Both must be set for the walk to trigger. |
| `walkToDialogueSet` | int | `-1` | Dialogue set index (0-based) to use permanently after the NPC arrives at `walkTo` destination. Leave at `-1` to keep the existing conditional logic. |
| `step<N>_dialogue` | int | *N* | **Step chain** — dialogue set to play when the player talks at step N. **Optional** — defaults to `N`, so step 0 plays set 0, step 1 plays set 1, etc. (from the NPC class's `setDialogue()`). Only set this if you want a different set than the step index. |
| `step<N>_walkToCol` | int | `-1` | Tile column the NPC walks to after speaking at step N. Omit (or `-1`) = NPC stays put (final stop). |
| `step<N>_walkToRow` | int | `-1` | Tile row partner of `step<N>_walkToCol`. |
| `speed` | int | `1` | Movement speed in pixels per tick. `1` = slow walk. `2` = brisk walk. |
| `collision` | bool | `true` | `true` = player cannot walk through the NPC. `false` = NPC is passable (ghosts, decorative NPCs). |
| `name` | String | *(type name)* | Display name shown in the dialogue UI header. |
| `onSpeakQuestId` | String | — | Quest ID to auto-progress when the player talks to this NPC. Must match a quest added via `QuestManager.addQuest()`. |
| `onSpeakQuestAmount` | int | `1` | How much to add to the quest counter each time the player talks to this NPC. Requires `onSpeakQuestId` to be set. |
| `giftItem` | String | — | ItemFactory ID given the first time the player talks to the NPC. Recommended alias for `giveItem`. |
| `giftDialogueSet` | int | `0` | Dialogue set used when `giftItem` is handed over. Alias for `giveItemDialogueSet`. |
| `giftQuestId` | String | — | Quest ID added when `giftItem` is given. Alias for `giveItemQuestId`. |
| `giftQuestName` | String | — | Quest name shown in the log when the quest starts. Alias for `giveItemQuestName`. |
| `giftQuestDesc` | String | `""` | Quest description shown in the log. Alias for `giveItemQuestDesc`. |
| `giftQuestTarget` | int | `1` | Quest completion target. Alias for `giveItemQuestTarget`. |
| `deliveryItem` | String | — | Required item for the return/delivery phase. Recommended alias for `requiredItem`. Can match either inventory item name or ItemFactory `itemId`. |
| `deliveryDialogueSet` | int | `0` | Dialogue set used when the player returns with `deliveryItem`. Alias for `requiredItemDialogueSet`. |
| `deliveryConsumeItem` | bool | `false` | Remove the delivered item from inventory when the delivery triggers. Alias for `requiredItemConsumed`. |
| `deliveryQuestId` | String | — | Quest to progress when the delivery succeeds. Alias for `requiredItemQuestId`. |
| `deliveryQuestAmount` | int | `1` | Amount added to `deliveryQuestId` when the delivery succeeds. Alias for `requiredItemQuestAmount`. |
| `deliveryPostDialogueSet` | int | `-1` | Dialogue set used on all future talks after the delivery is complete. Alias for `requiredItemPostQuestSet`. |
| `deliveryRewardCoins` | int | `0` | Coins granted once when the delivery succeeds. Alias for `requiredItemRewardCoins`. |
| `deliveryRewardItem` | String | — | ItemFactory ID granted once when the delivery succeeds. Alias for `requiredItemRewardItem`. |
| `deliveryRewardFragmentId` | String | — | Memory Fragment ID granted once when the delivery succeeds. Alias for `requiredItemRewardFragmentId`. |
| `giveItem` | String | — | Legacy name for `giftItem` |
| `giveItemDialogueSet` | int | `0` | Legacy name for `giftDialogueSet` |
| `giveItemQuestId` | String | — | Legacy name for `giftQuestId` |
| `giveItemQuestName` | String | — | Legacy name for `giftQuestName` |
| `giveItemQuestDesc` | String | — | Legacy name for `giftQuestDesc` |
| `giveItemQuestTarget` | int | `1` | Legacy name for `giftQuestTarget` |
| `requiredItem` | String | — | Legacy name for `deliveryItem` |
| `requiredItemDialogueSet` | int | `0` | Legacy name for `deliveryDialogueSet` |
| `requiredItemConsumed` | bool | `false` | Legacy name for `deliveryConsumeItem` |
| `requiredItemQuestId` | String | — | Legacy name for `deliveryQuestId` |
| `requiredItemQuestAmount` | int | `1` | Legacy name for `deliveryQuestAmount` |
| `requiredItemPostQuestSet` | int | `-1` | Legacy name for `deliveryPostDialogueSet` |
| `requiredItemRewardCoins` | int | `0` | Legacy name for `deliveryRewardCoins` |
| `requiredItemRewardItem` | String | — | Legacy name for `deliveryRewardItem` |
| `requiredItemRewardFragmentId` | String | — | Legacy name for `deliveryRewardFragmentId` |
| `giveItem2` | String | — | Existing follow-up reward item after walk/help logic finishes |
| `giveItem2DialogueSet` | int | `-1` | Dialogue set used for `giveItem2` |
| `dialogueChoices` | String | — | Pipe-separated choice labels, example: `Accept|Refuse` |
| `choiceNextSet` | String | — | Pipe-separated next dialogue sets, example: `1|4` |
| `choiceResultKey` | String | — | Stores which choice was made under a reusable key |
| `memoryFragmentId` | String | — | Registers and awards a fragment from this NPC using the memory system |
| `memoryFragmentName` | String | `memoryFragmentId` | Display name for the fragment |
| `memoryText0`–`memoryText4` | String | — | Up to 5 flashback lines for the fragment |
| `fragmentOrder` | int | `99` | Story ordering position in the Memory Journal |
| `fragmentSource` | String | NPC name | Source label in the Memory Journal |
| `fragmentRequiredCount` | int | `0` | Minimum total fragments required before this fragment can be claimed |
| `fragmentRequiredItem` | String | — | Item needed before this fragment can be claimed |
| `fragmentRequiredBoss` | int | `-1` | Boss number that must be defeated before this fragment can be claimed |
| `fragmentRequiredQuest` | String | — | Quest ID that must be complete before this fragment can be claimed |

### Recommended NPC Patterns

#### 1. Simple Static Talker

```xml
type      = NPC_Generic
staticNPC = true
name      = Villager
sprite    = /res/npc/Alucard_walking-sheet
dialogue_0_0 = Welcome to the village.
```

#### 2. Gift Then Return Item Quest

```xml
type                    = NPC_Generic
name                    = Sword Giver
guardMode               = true
sprite                  = /res/npc/Alucard_walking-sheet

giftItem                = sword_normal
giftDialogueSet         = 0
giftQuestId             = sword_giver_bandage
giftQuestName           = Aid the Sword Giver
giftQuestDesc           = A wounded traveler needs a bandage.
giftQuestTarget         = 1

walkToDialogueSet       = 1

deliveryItem            = bandage
deliveryDialogueSet     = 2
deliveryConsumeItem     = true
deliveryQuestId         = sword_giver_bandage
deliveryPostDialogueSet = 3
deliveryRewardCoins     = 25
```

This produces a 4-phase flow:
- First talk: NPC gives item and starts the quest
- Waiting phase: NPC asks for the return item
- Delivery phase: NPC consumes the item and rewards the player
- Post-quest phase: NPC switches to a permanent completed dialogue

#### 3. Generic Reward Delivery

```xml
deliveryItem            = bandage
deliveryConsumeItem     = true
deliveryRewardCoins     = 10
deliveryRewardItem      = potion
deliveryRewardFragmentId = frag_forest
```

Use any combination of the reward properties. They all fire once when the delivery succeeds.

**Example — a static village elder who advances a quest on first talk:**
```
type              = NPC_Alucard
staticNPC         = true
collision         = true
name              = Elder Voss
dialogue0         = Stranger, you must find the three relics.
onSpeakQuestId    = find_relics
onSpeakQuestAmount = 1
```

**Example — NPC changes dialogue when the player has a specific item:**
```
type                    = NPC_Alucard
staticNPC               = true
name                    = Sage
dialogue0               = You are not yet ready. Bring me the Dark Heart.
dialogue3               = You have obtained the Dark Heart! Well done.
requiredItem            = Dark Heart
requiredItemDialogueSet = 3
```
*When the player talks to Sage without the item → dialogue set 0. After obtaining "Dark Heart" → dialogue set 3.*

**Example — a ghost NPC you can walk through:**
```
type       = NPC_Alucard
collision  = false
staticNPC  = true
name       = Wandering Spirit
```

**Example — guard with a 3-step chain (minimal — dialogue sets come from class code):**
```
type              = NPC_Alucard
guardMode         = true
name              = Gate Guard
step0_walkToCol   = 48
step0_walkToRow   = 30
step1_walkToCol   = 55
step1_walkToRow   = 25
step2_walkToCol   = -1
```
Step 0 plays dialogue set 0 from `NPC_Alucard.setDialogue()`, walks to (48,30), re-enters guardMode.
Step 1 plays set 1, walks to (55,25), re-enters guardMode.
Step 2 plays set 2, stays put (no walk coords — `-1` or just omit).

**If you want to override which dialogue set plays at a specific step**, add `step<N>_dialogue`:
```
step1_dialogue    = 3
step1_walkToCol   = 55
step1_walkToRow   = 25
```

---

## InteractiveTiles Layer

| `type` | Creates |
|---|---|
| `IT_Pot` | Breakable pot (drops coin) |
| `IT_Coins` | Coin pile (interactive pickup) |

---

## Events Layer

Events can be **Point** objects OR **Rectangle** objects. Rectangles define an area trigger.

| `type` | Trigger Shape | Description | Key Properties |
|---|---|---|---|
| `MapTransition` | Point or Rect | Teleports player to another map | `targetMap`, `targetCol`, `targetRow`, `spawnId` |
| `HealingPool` | Point or Rect | Restores HP/MP fully | — |
| `DamageTrap` | Point or Rect | Hurts player each tick | `damage` (int), `repeatable` (bool) |
| `DialogueTrigger` | Point or Rect | Shows a custom message box | `message` (String), `speaker` (String, optional — name shown above box), `oneShot` (bool) |
| `ThoughtTrigger` | Point or Rect | Non-blocking inner monologue (typewriter text, auto-dismiss) | `message` (String), `oneShot` (bool), `linger` (int, frames to hold after typing, default 120), `delay` (int, frames to wait before starting, default 0) |
| `LevelGate` | Point or Rect | Blocks player below min level; optionally teleports if above | `minLevel` (int), `message` (String, optional), `targetMap` (String), `targetCol` (int), `targetRow` (int), `spawnId` (String), `requiredItem` (String), `consumeItem` (bool), `requiredFragment` (String) |
| `Checkpoint` | Point or Rect | Save + restore | `silent` (bool) |
| `QuestTrigger` | Point or Rect | Advances a quest counter | `questId` (String), `progress` (int — amount to add), `oneShot` (bool) |
| `QuestDefinition` | Point or Rect | Registers a new quest at map load time | `questId` (String), `questName` (String), `questDesc` (String), `target` (int — completion threshold) |
| `FragmentTrigger` | Point or Rect | Awards a memory fragment when the player steps on it | `fragmentId` (String), `oneShot` (bool, default `true`) |
| `SpawnPoint` | Point | Player spawn / named door arrival | `id` (String, optional) — registers as a named arrival target for Doors; omit to set the **default player spawn** for the entire map |
| `CameraShake` | Point or Rect | Screen shake on enter | `intensity` (String: `light`/`medium`/`heavy`) |
| `SpawnZone` | Rect | Continuously spawns enemies inside the area up to a live cap | `monster` (String, default `MON_monster`), `maxAmount` (int, default `5`), `interval` (int frames, default `300` = 5 s at 60 fps), `confined` (bool, default `false`), `activationRange` (int tiles, default `0` = always), `totalLimit` (int, default `0` = infinite), `lootItem` (String), `lootFragment` (String) |
| `MobSpawnerZone` | Rect | Registers an area for the time-of-day MobSpawner | *(no extra properties — rectangle bounds = spawn area)* |
| `MemoryGate` | Point or Rect | Blocks passage until player collects N memory fragments | `requiredFragments` (int, default `1`), `message` (String), `targetMap` (String), `targetCol` (int), `targetRow` (int), `spawnId` (String) |
| `Light` / `Lighting` | Point | Invisible light source placed in Events layer | `lightRadius` (int, default `4`), `lightColor` (String `#rrggbb`) |

### MapTransition Properties

| Property | Tiled Type | Description |
|---|---|---|
| `targetMap` | String | Map ID to travel to (e.g. `Dungeon1`) |
| `targetCol` | int | Spawn column on the target map |
| `targetRow` | int | Spawn row on the target map |
| `spawnId` | String | Named spawn point on the target map (overrides col/row) |

### SpawnPoint Workflows

#### Default map spawn — player starts here on New Game / Respawn
Add a **Point** in the `Events` layer with:
- `type` = `SpawnPoint`
- *(no `spawnId` needed)*

The player will always spawn at this point when the map loads fresh. You can only have one default SpawnPoint per map.

#### Named spawn — for door arrivals
Use named spawn points to control exactly where the player arrives when going through a door:

1. **On the target map** — add a **Point** in the `Events` layer:
   - `type` = `SpawnPoint`
   - `id` = `entrance_north`

2. **On the source map** — add a **Door** object:
   - `destination` = `Dungeon1`
   - `spawnId` = `entrance_north`
   - `spawnDirection` = `down` *(player faces this direction on arrival)*

The player will arrive at the named SpawnPoint's exact tile, facing the given direction.

> **Tip:** A SpawnPoint with `id` set AND placed at the default arrival location does both jobs at once.

---

## Named Spawn Points

Named spawn points let you have **multiple entrances** on a single map without needing different `destCol/destRow` values on every door.

```
Map: Dungeon1

SpawnPoint (Events layer):
  id = front_entrance   → at tile (5, 8)
  id = back_entrance    → at tile (18, 2)
  id = boss_room        → at tile (10, 15)

Map: Overworld

Door A → Dungeon1, spawnId = front_entrance
Door B → Dungeon1, spawnId = back_entrance
```

---

## Layer Properties (Tile Layers)

In the **Layers** panel, select a tile layer and open its properties:

| Attribute | Description |
|---|---|
| `parallaxx` / `parallaxy` | Parallax scroll factor (1.0 = normal, 0.5 = half speed) |
| `opacity` | 0.0 (invisible) to 1.0 (fully opaque) |
| `tintcolor` | Tint color in `#aarrggbb` or `#rrggbb` format |

The tint is blended on top of every tile in that layer (useful for color-grading cave layers, lava glow, etc.).

---

## Image Layers

Add an **Image Layer** in Tiled (Layers panel → right-click → New → Image Layer):

| Attribute | Description |
|---|---|
| `name` | Identifier (for debugging) |
| Source image | A PNG/JPG. Path is resolved via `res/` automatically |
| `offsetx` / `offsety` | World pixel offset |
| `parallaxx` / `parallaxy` | Scroll factor (< 1.0 for background parallax effect) |
| `opacity` | 0.0–1.0 |
| `tintcolor` | Optional color overlay |

Image layers are drawn **behind all tile layers**.

**Example** — sky background that scrolls at half speed:
```
Layer name: sky_background
Image: res/tiles/sky.png
parallaxx: 0.3
parallaxy: 0.1
opacity: 1.0
```

---

## Animated Tiles

Animation is defined **inside your tileset file** (`.tsx`) in Tiled — not on the map itself.

1. Open your tileset in Tiled (double-click the tileset in the Tilesets panel)
2. Select the tile you want to animate
3. In the **Tile Animation Editor** panel (View → Tile Animation Editor), add frames:
   - Each frame has a **Tile** (by local index) and a **Duration** (ms)
4. Save the tileset

The game reads `<animation>` blocks from the embedded tileset XML and automatically advances frames at 60 Hz. **No Java changes needed.**

---

## Tile Flip Flags

Use Tiled's built-in flip/rotate controls on individual tiles:

| Tiled shortcut | Effect |
|---|---|
| `X` | Flip tile horizontally |
| `Y` | Flip tile vertically |
| `Z` | Rotate 90° clockwise (stored as anti-diagonal + flip) |

The engine extracts the flip bits from the GID and applies an `AffineTransform` at draw time.

---

## Per-Tile Tileset Properties

In your tileset, select a tile and add **Custom Properties** to it.
These override the tileset-level settings for that specific tile only.

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `depthSort` | bool | `false` | Player walks **behind** the tile when approaching from above, and **in front** when coming from below. Ideal for campfires, barrels, bushes, single objects |
| `sortYOffset` | int | `0` | Shift the depth-sort anchor in pixels. Negative = move anchor UP (e.g. `-32` = mid-tile). Use for multi-row structures so all rows sort as one unit |
| `foreground` | bool | `false` | Always draw **on top** of all entities (e.g. cave ceiling, roof overhangs) |
| `background` | bool | `false` | Always draw **behind** all entities — never depth-sorted |

**Multi-row structure sorting example** — a 2-tile-tall tree:
- Bottom tile: `sortYOffset` = 0 (normal)
- Top tile: `sortYOffset` = +64 (game tileSize) so it sorts with the bottom row

**Campfire / animated object depth-sort workflow:**
1. Open your tileset → select the campfire tile (the first/base frame)
2. Custom Properties → add `depthSort` = `true` (bool)
3. Save the tileset — no Java changes needed
4. Result: player walks **behind** the fire when above it, **in front** when below it

> **Note:** For animated tiles, always set `depthSort` on the **base frame** (the tile placed in the map layer). The engine automatically uses the base tile's properties for all animation frames.

**Priority order when multiple flags are set:** `foreground` > `background` > `depthSort` > normal background tile.

> **Tip:** Use `depthSort` on individual tiles instead of enabling it for the whole tileset — this gives you per-object control without affecting the rest of the tileset.

---

## Tileset Render Order

**Why the Tiled layer panel order doesn't control draw order:** The engine sorts all tiles by `renderOrder` first, then by world Y. The layer order in Tiled is only a tiebreaker when two tiles share the same `renderOrder` *and* the same Y position.

To control draw order, set the `renderOrder` **custom property on the tileset** (select the tileset root in Tiled → Custom Properties → add `renderOrder` as int):

| renderOrder value | Where it draws | Built-in tilesets that use it |
|---|---|---|
| 5 | Water / deep background | `water` tilesets (auto-detected by name) |
| 10 | Below shadows | Good for floor decals, ground overlays |
| 15 | Shadows | `Shadows` tileset (auto-detected by name) |
| **16** | **Just above shadows** ← **default for new tilesets** | All unknown tilesets |
| 20 | Trees / decorations | `tree`, `decor`, `foliage` tilesets |
| 25 | Fences / buildings / walls | `fence`, `build`, `house`, `tower`, `wall` |
| 30+ | Custom — above all built-ins | Set manually |

**Quick reference — what to set on your new tileset:**
- New tileset should appear **above shadows** → leave `renderOrder` unset (default is 16, automatic)
- New tileset should appear **below shadows** → set `renderOrder = 10`
- New tileset should appear **above trees** → set `renderOrder = 30`

---

## Collision Layer

Add an Object Layer named exactly `Collision`. All shapes inside it become solid:

| Shape | Behavior |
|---|---|
| Rectangle | Axis-aligned or rotated solid rectangle |
| Ellipse | Solid ellipse |
| Polygon | Arbitrary solid polygon |
| Polyline | **Ignored** (open shape, no area) |
| Point (plain) | **Ignored** — unless it has a `collisionTemplate` property (see below) |

All shapes are scaled automatically from the TMX tile size to the game tile size.

### Collision Templates — reuse one polygon at many positions

If you have a shape (e.g. a tree trunk polygon) that you want to stamp at dozens of tree locations without drawing it over and over, use the template system:

**Step 1 — Define the template (once)**

Draw your polygon (or rectangle) exactly as you need it for one tree. In Tiled object properties:
- **Name** → `Tree_Collision` *(or any unique name you choose)*
- Add custom **bool** property: `isCollisionTemplate` = `true`

This object is **not** a collision shape itself — it is the definition only.

**Step 2 — Place a Point for every tree that needs the shape**

In the same `Collision` object layer, place a **Point** object (Insert → Insert Point) at the same anchor position within each tree (e.g. base of the trunk). Add one custom property:

| Property | Tiled Type | Value |
|---|---|---|
| `collisionTemplate` | String | `Tree_Collision` *(matches the template name)* |

The engine clones the template shape to each point's world position at load time. You will see this in the console:
```
Registered collision template: 'Tree_Collision'
```

> **Alignment rule:** The polygon's anchor (its X, Y in Tiled) is the pivot. Place each Point at the *same relative spot* within every other tree — the polygon offsets are preserved exactly.

> **Multiple template types** (e.g. `Tree_Collision`, `Rock_Collision`, `Crate_Collision`) are all supported — just give each a unique name and reference it by that name in the `collisionTemplate` property.

---

## Adding a New Map

1. **Create the TMX** in Tiled (File → New Map → Tile size 32×32)
2. **Set map properties** (music, weather, spawnCol, etc.)
3. **Register it** in `GamePanel.setupGame()`:
   ```java
   registerMap("Dungeon1", "/res/maps/Dungeon1.tmx");
   ```
4. **Add the object layers**: Objects, Monsters, NPCs, InteractiveTiles, Events, Collision
5. **Place entities** with Point objects and type/class values
6. **Add a Door or MapTransition** on another map to connect to it

---

## Adding a New Entity Type

1. Create `OBJ_Axe.java` (or `MON_Orc.java`, etc.) in the right package
2. Open `MapObjectLoader.java`, find `createObject()` (or `createMonster()`)
3. Add a new case:
   ```java
   case "Axe" -> { return new OBJ_Axe(gp); }
   ```
4. Now use `type` = `Axe` in Tiled — zero other changes needed

---

## Quick Reference — Layer by Layer

```
Tiled Layer          Object Type                Properties
────────────────────────────────────────────────────────────────────────
Objects (point)      Chest                      loot/lootId, opened, requiredItem,
                                                consumeItem, openAnimation, openFrames
                     Door                       destination, destCol, destRow,
                                                isLocked, spawnId, spawnDirection
                     Torch                      lightRadius, lightColor
                     Potion/Key/Coins/Arrow      amount
                     Sword/Shield/Boots/Tent     (none extra)
                     Heart/Mana/Gem/Compas       (none extra)
                     Light / Lighting            lightRadius, lightColor
                     Item                        itemId/factoryId, amount,
                                                removeOnPickup
                     Tower                       (spawns Eye on top)

Monsters (point)     MON_monster                level, aggroRange, wanderRadius,
                                                bossId, phase2Threshold, phase2SpeedBoost
                     MON_SkeletonArcher          (same as above)
                     MON_Shade                   (same as above)
                     MON_Inkblot / Inkblot       (same as above)
                     BOSS_WitheredTree            (same as above)
                     (any monsters.json id)      (same as above)
Monsters (rect)      MonsterArea                monster, count, level

NPCs (point)         NPC_Alucard                dialogue0-4, wanderRadius, staticNPC,
                                                guardMode, walkToCol, walkToRow,
                                                walkToDialogueSet, step<N>_dialogue,
                                                step<N>_walkToCol, step<N>_walkToRow,
                                                speed, collision, name, portrait,
                                                idleAnimSpeed,
                                                onSpeakQuestId, onSpeakQuestAmount,
                                                requiredItem, requiredItemDialogueSet
                     NPC_Generic                 (same as above) + sprite, idleSprite,
                                                dialogue_S_L (data-driven lines),
                                                memoryFragmentId, memoryText0-4

InteractiveTiles     IT_Pot / IT_Coins          amount (IT_Coins only)

Events (point/rect)  MapTransition              targetMap, targetCol, targetRow, spawnId
                     HealingPool                (none)
                     DamageTrap                 damage, repeatable
                     DialogueTrigger            message, speaker, oneShot
                     ThoughtTrigger             message, oneShot, linger, delay
                     LevelGate                  minLevel, message, targetMap, targetCol,
                                                targetRow, spawnId, requiredItem,
                                                consumeItem, requiredFragment
                     MemoryGate                 requiredFragments, message, targetMap,
                                                targetCol, targetRow, spawnId
                     Checkpoint                 silent
                     QuestTrigger               questId, progress, oneShot
                     FragmentTrigger            fragmentId, oneShot
                     QuestDefinition            questId, questName, questDesc, target
                     CameraShake                intensity (light/medium/heavy)
                     SpawnZone (rect)           monster, maxAmount, interval, confined,
                                                activationRange, totalLimit,
                                                lootItem, lootFragment
                     MobSpawnerZone (rect)      (none extra)
                     Light / Lighting           lightRadius, lightColor
Events (point only)  SpawnPoint                 id (optional — omit to set default map spawn)

Collision (any)      (unnamed shapes)           auto-solid
Collision (polygon)  isCollisionTemplate=true   defines a reusable shape template
                     name=Tree_Collision         (any unique name)
Collision (point)    collisionTemplate=<name>   stamps the template at this position
```

---

## Coordinate Math

Tiled stores object positions in pixels at the map's native tile size.

| Formula | Example (32px tiles) |
|---|---|
| `pixel = tile × 32` | tile 10 → pixel 320 |
| `tile  = pixel ÷ 32` | pixel 640 → tile 20 |

**You never need to do math** — just place objects where you want them in Tiled. `MapObjectLoader` converts everything to game coordinates automatically.

---

*Guide version: 4.0 — added: ThoughtTrigger, MemoryGate, Light/Lighting (Objects + Events), all data-driven monsters (monsters.json IDs), MON_Shade, MON_Inkblot, BOSS_WitheredTree, boss properties (bossId, phase2Threshold, phase2SpeedBoost), expanded Chest properties (requiredItem, consumeItem, openAnimation, openFrames), expanded LevelGate (requiredItem, consumeItem, requiredFragment), expanded SpawnZone (confined, activationRange, totalLimit, lootItem, lootFragment), NPC portrait + idleAnimSpeed, map properties weatherCycle / dialogueTrigger / dialogueTriggerDuration / actTitle. Previous: animated tiles, flip flags, image layers, layer opacity/tint, area events, named spawn points, default SpawnPoint, map properties, MonsterArea, CameraShake, LevelGate, DialogueTrigger, Checkpoint, QuestTrigger, QuestDefinition, SpawnZone, MobSpawnerZone, per-tile depthSort/foreground/background/sortYOffset, NPC extended properties, collision templates.*
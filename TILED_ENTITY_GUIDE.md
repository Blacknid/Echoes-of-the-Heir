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

---

## Objects Layer

Place **Point** objects (`P` key in Tiled) for all items.

### Supported Object Types

| `type` | Creates | Extra Properties |
|---|---|---|
| `Chest` | Treasure chest | `loot` (String), `opened` (bool – default false) |
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

#### Loot Names (for Chest `loot` property)
`Compas` | `Key` | `Potion` | `Boots` | `Gem` | `Sword` | `Shield`

---

## Monsters Layer

Place **Point** objects. All monsters also accept the common entity properties.

| `type` | Creates | Extra Properties |
|---|---|---|
| `MON_monster` | Basic melee monster | `level` (int), `aggroRange` (int px), `wanderRadius` (int px) |
| `MON_SkeletonArcher` | Ranged skeleton | `level` (int), `aggroRange` (int px) |

**Monster Area** — place a **Rectangle** object in the `Monsters` layer with `type = MonsterArea` to spawn N monsters randomly within that rectangle:

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `monsterType` | String | — | Same `type` values as above (`MON_monster`, etc.) |
| `count` | int | `3` | How many to spawn |
| `level` | int | `1` | Level for all spawned monsters |

---

## NPCs Layer

Place **Point** objects. All NPCs also accept the common entity properties (`facing`, `id`, `invisible`).

| `type` | Creates |
|---|---|
| `NPC_Alucard` | Alucard (currently the only NPC type) |

### NPC Properties

| Property | Tiled Type | Default | Description |
|---|---|---|---|
| `dialogue0`–`dialogue4` | String | *(built-in)* | Override the **first line** of dialogue set 0–4. Use `\n` for line breaks. Does not replace later lines in that set. |
| `wanderRadius` | int | `0` (free) | Max pixel distance the NPC may wander from its spawn point. `0` = unlimited wander. |
| `staticNPC` | bool | `false` | `true` = NPC stands still forever — no wander, no pathfinding. Ideal for town signs, stationary quest-givers. |
| `speed` | int | `1` | Movement speed in pixels per tick. `1` = slow walk. `2` = brisk walk. |
| `collision` | bool | `true` | `true` = player cannot walk through the NPC. `false` = NPC is passable (ghosts, decorative NPCs). |
| `name` | String | *(type name)* | Display name shown in the dialogue UI header. |
| `onSpeakQuestId` | String | — | Quest ID to auto-progress when the player talks to this NPC. Must match a quest added via `QuestManager.addQuest()`. |
| `onSpeakQuestAmount` | int | `1` | How much to add to the quest counter each time the player talks to this NPC. Requires `onSpeakQuestId` to be set. |

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

**Example — a ghost NPC you can walk through:**
```
type       = NPC_Alucard
collision  = false
staticNPC  = true
name       = Wandering Spirit
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
| `DialogueTrigger` | Point or Rect | Shows a custom message | `message` (String), `oneShot` (bool) |
| `LevelGate` | Point or Rect | Blocks player below min level | `minLevel` (int), `targetMap` (String), `targetCol` (int), `targetRow` (int) |
| `Checkpoint` | Point or Rect | Save + restore | `silent` (bool) |
| `QuestTrigger` | Point or Rect | Advances a quest | `questId` (String), `amount` (int) |
| `SpawnPoint` | Point | Player spawn position | `spawnId` (String, optional) — if set, also works as a named arrival target for Doors; if omitted, sets the **default player spawn** for the entire map |
| `CameraShake` | Point or Rect | Screen shake on enter | `intensity` (String: `light`/`medium`/`heavy`) |

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
   - `spawnId` = `entrance_north`

2. **On the source map** — add a **Door** object:
   - `destination` = `Dungeon1`
   - `spawnId` = `entrance_north`
   - `spawnDirection` = `down` *(player faces this direction on arrival)*

The player will arrive at the named SpawnPoint's exact tile, facing the given direction.

> **Tip:** A SpawnPoint with both `spawnId` set AND placed as the default arrival location does both jobs at once.

---

## Named Spawn Points

Named spawn points let you have **multiple entrances** on a single map without needing different `destCol/destRow` values on every door.

```
Map: Dungeon1

SpawnPoint (Events layer):
  spawnId = front_entrance   → at tile (5, 8)
  spawnId = back_entrance    → at tile (18, 2)
  spawnId = boss_room        → at tile (10, 15)

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
Objects (point)      Chest                      loot, opened
                     Door                       destination, destCol, destRow,
                                                isLocked, spawnId, spawnDirection
                     Torch                      lightRadius, lightColor
                     Potion/Key/Coins/Arrow      amount
                     Sword/Shield/Boots/Tent     (none extra)

Monsters (point)     MON_monster                level, aggroRange, wanderRadius
                     MON_SkeletonArcher          level, aggroRange
Monsters (rect)      MonsterArea                monsterType, count, level

NPCs (point)         NPC_Alucard                dialogue0-4, wanderRadius, staticNPC,
                                                speed, collision, name,
                                                onSpeakQuestId, onSpeakQuestAmount

InteractiveTiles     IT_Pot / IT_Coins          (none extra)

Events (point/rect)  MapTransition              targetMap, targetCol, targetRow, spawnId
                     HealingPool                (none)
                     DamageTrap                 damage, repeatable
                     DialogueTrigger            message, oneShot
                     LevelGate                  minLevel, targetMap, targetCol, targetRow
                     Checkpoint                 silent
                     QuestTrigger               questId, amount
                     CameraShake                intensity (light/medium/heavy)
Events (point only)  SpawnPoint                 spawnId (optional — omit to set default map spawn)

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

*Guide version: 2.4 — covers animated tiles, flip flags, image layers, layer opacity/tint, area events, named spawn points, default SpawnPoint, map properties, MonsterArea, CameraShake, LevelGate, DialogueTrigger, Checkpoint, QuestTrigger, per-tile depthSort (campfire/animated objects), per-tile foreground/background/sortYOffset, animated tile depth-sort fix. NPC extended properties: speed, staticNPC, collision, name, onSpeakQuestId/Amount. Collision templates (reusable polygon stamps via isCollisionTemplate + collisionTemplate properties).*
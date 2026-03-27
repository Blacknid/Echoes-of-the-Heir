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

| `type` | Creates | Extra Properties |
|---|---|---|
| `NPC_Alucard` | Alucard | `dialogue0`–`dialogue4` (String) – per-NPC dialogue override, `wanderRadius` (int px) |

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
| `SpawnPoint` | Point | Named arrival position | `spawnId` (String) — referenced by Door `spawnId` |
| `CameraShake` | Point or Rect | Screen shake on enter | `intensity` (String: `light`/`medium`/`heavy`) |

### MapTransition Properties

| Property | Tiled Type | Description |
|---|---|---|
| `targetMap` | String | Map ID to travel to (e.g. `Dungeon1`) |
| `targetCol` | int | Spawn column on the target map |
| `targetRow` | int | Spawn row on the target map |
| `spawnId` | String | Named spawn point on the target map (overrides col/row) |

### SpawnPoint + Door Workflow

Use named spawn points to control exactly where the player arrives when going through a door:

1. **On the target map** — add a **Point** in the `Events` layer:
   - `type` = `SpawnPoint`
   - `spawnId` = `entrance_north`

2. **On the source map** — add a **Door** object:
   - `destination` = `Dungeon1`
   - `spawnId` = `entrance_north`
   - `spawnDirection` = `down` *(player faces this direction on arrival)*

The player will arrive at the named SpawnPoint's exact tile, facing the given direction.

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

In your tileset, select a tile and add **Custom Properties** to it:

| Property | Tiled Type | Description |
|---|---|---|
| `sortYOffset` | int | Shift depth-sort Y (for multi-row structures — set on top tiles) |
| `foreground` | bool | `true` = draws above all entities (e.g. cave ceiling, roof) |
| `background` | bool | `true` = force background layer, never depth-sorted |

**Multi-row structure sorting example** — a 2-tile-tall tree:
- Bottom tile: `sortYOffset` = 0 (normal)
- Top tile: `sortYOffset` = +64 (game tileSize) so it sorts with the bottom row

---

## Collision Layer

Add an Object Layer named exactly `Collision`. All shapes inside it become solid:

| Shape | Behavior |
|---|---|
| Rectangle | Axis-aligned or rotated solid rectangle |
| Ellipse | Solid ellipse |
| Polygon | Arbitrary solid polygon |
| Polyline | **Ignored** (open shape, no area) |
| Point | **Ignored** (no area) |

All shapes are scaled automatically from the TMX tile size to the game tile size.

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

NPCs (point)         NPC_Alucard                dialogue0-4, wanderRadius

InteractiveTiles     IT_Pot / IT_Coins          (none extra)

Events (point/rect)  MapTransition              targetMap, targetCol, targetRow, spawnId
                     HealingPool                (none)
                     DamageTrap                 damage, repeatable
                     DialogueTrigger            message, oneShot
                     LevelGate                  minLevel, targetMap, targetCol, targetRow
                     Checkpoint                 silent
                     QuestTrigger               questId, amount
                     CameraShake                intensity (light/medium/heavy)
Events (point only)  SpawnPoint                 spawnId

Collision (any)      (unnamed shapes)           auto-solid
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

*Guide version: 2.0 — covers animated tiles, flip flags, image layers, layer opacity/tint, area events, named spawn points, map properties, MonsterArea, CameraShake, LevelGate, DialogueTrigger, Checkpoint, QuestTrigger.*
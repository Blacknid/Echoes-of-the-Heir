# Tiled Entity Placement Guide

This guide teaches you how to place **objects, monsters, NPCs, interactive tiles, and events** on your maps using Tiled instead of writing Java code.

---

## How It Works (Big Picture)

```
You edit a .tmx map in Tiled
        ↓
Add point objects to special layers
        ↓
Set a "type" property on each object
        ↓
MapObjectLoader.java reads the TMX at runtime
        ↓
Entities appear in-game at those positions
```

**No Java code changes needed** to add, move, or delete entities on any map.

---

## The 5 Object Layers

Each map can have up to 5 special object layers. The names **must match exactly** (case-sensitive):

| Layer Name | What goes in it | Java array |
|---|---|---|
| `Objects` | Chests, doors, keys, potions, torches, etc. | `gp.obj[100]` |
| `Monsters` | All enemy types | `gp.monster[20]` |
| `NPCs` | Friendly characters | `gp.npc[10]` |
| `InteractiveTiles` | Breakable pots, coin piles | `gp.iTile[30]` |
| `Events` | Map transitions, healing pools | EventHandler |

The `Collision` layer is **not affected** — TileManager still handles that separately.

---

## Step-by-Step: Adding an Entity in Tiled

### 1. Open your map
Open the `.tmx` file (e.g. `harta.tmx`) in Tiled.

### 2. Select the right layer
In the **Layers** panel (right side), click the layer that matches your entity type. For example, click `Objects` to place a key.

> **Don't have the layer yet?** Right-click in Layers → **New → Object Layer** → name it exactly: `Objects`, `Monsters`, `NPCs`, `InteractiveTiles`, or `Events`.

### 3. Insert a Point object
- Press **P** (or select the **Insert Point** tool from the toolbar)
- Click on the map where you want the entity

### 4. Set the `type` property
- With the object selected, look at the **Properties** panel (bottom-left)
- Click the **+** button to add a custom property
- Add a **String** property named `type`
- Set its value to one of the supported types (see tables below)

### 5. (Optional) Set extra properties
Some entity types need additional properties (doors need a destination, chests need loot). See the property tables below.

### 6. Save the TMX file
Save (`Ctrl+S`) and run the game. Your entity will appear at that position.

---

## Supported Entity Types

### Objects Layer

| `type` value | What it creates | Extra Properties |
|---|---|---|
| `Chest` | Treasure chest | `loot` (String) — name of loot item (see Loot Names below) |
| `Door` | Door / portal | `destination` (String), `destCol` (int), `destRow` (int), `isLocked` (bool) |
| `Tent` | Tent | — |
| `Boots` | Speed boots | — |
| `Potion` | Health potion | — |
| `Torch` | Light source | — |
| `Key` | Door key | — |
| `Gem` | Victory gem | — |
| `Book` | Readable book | — |
| `Tower` | Tower structure (spawns an Eye monster on top) | — |
| `Sword` | Normal sword | — |
| `Shield` | Wood shield | — |
| `Coins` | Coin pickup | — |
| `Heart` | Heart pickup | — |
| `Mana` | Mana crystal pickup | — |
| `Arrow` | Arrow ammo | — |
| `Compas` | Compass item | — |

#### Loot Names (for Chest `loot` property)
`Compas`, `Key`, `Potion`, `Boots`, `Gem`, `Sword`, `Shield`

### Monsters Layer

| `type` value | What it creates |
|---|---|
| `MON_monster` | Basic melee monster |
| `MON_SkeletonArcher` | Ranged skeleton archer |

### NPCs Layer

| `type` value | What it creates |
|---|---|
| `NPC_Alucard` | Alucard NPC |

### InteractiveTiles Layer

| `type` value | What it creates |
|---|---|
| `IT_Pot` | Breakable pot |
| `IT_Coins` | Coin pile (interactive) |

### Events Layer

| `type` value | What it creates | Extra Properties |
|---|---|---|
| `MapTransition` | Step-on tile that changes map | `targetMap` (String), `targetCol` (int), `targetRow` (int) |
| `HealingPool` | Restores HP/MP and saves game | — |
| `DamageTrap` | Hurts the player when stepped on | `damage` (int) — raw damage before defense (default: 1) |

---

## Property Types in Tiled

When adding properties, pick the right type:

| Property | Tiled Type | Example |
|---|---|---|
| `type` | String | `Door` |
| `loot` | String | `Compas` |
| `destination` | String | `test` or `Dungeon1` |
| `destCol` | int | `5` |
| `destRow` | int | `7` |
| `isLocked` | bool | `true` |
| `targetMap` | String | `Dungeon1` |
| `targetCol` | int | `5` |
| `targetRow` | int | `5` |
| `damage` | int | `3` |

To add a typed property in Tiled:
1. Click **+** in the Properties panel
2. Enter the property name
3. Select the type (String, int, or bool)
4. Set the value

---

## Common Tasks

### Move an entity
Select it in Tiled → drag it to the new position → save.

### Delete an entity
Select it → press `Delete` → save.

### Duplicate an entity
Select it → `Ctrl+D` → drag the copy → save. Useful for placing multiple keys or pots.

### Add a new door that goes to another map
1. Select `Objects` layer
2. **P** → click where you want the door
3. Add properties:
   - `type` = `Door`
   - `destination` = `Dungeon1` (the map ID — must match what's registered in `setupGame()`)
   - `destCol` = `5` (tile column where the player spawns on the target map)
   - `destRow` = `7` (tile row)
   - `isLocked` = `true` (requires a key) or `false`

### Add a chest with loot
1. Select `Objects` layer
2. **P** → click position
3. Add properties:
   - `type` = `Chest`
   - `loot` = `Compas` (or `Key`, `Potion`, `Boots`, `Gem`, `Sword`, `Shield`)

A chest without a `loot` property creates an empty chest.

### Add a map transition (step-on trigger)
1. Select `Events` layer
2. **P** → click the tile that triggers the transition
3. Add properties:
   - `type` = `MapTransition`
   - `targetMap` = `Dungeon1`
   - `targetCol` = `5`
   - `targetRow` = `5`

### Add a healing pool
1. Select `Events` layer
2. **P** → click position
3. Add property: `type` = `HealingPool`

---

## Coordinates

- Tiled shows positions in **pixels** at the map's native tile size (32px for harta)
- When you click tile (10, 20), Tiled stores `x="320" y="640"` (10×32, 20×32)
- `MapObjectLoader` converts these pixels to game coordinates automatically
- **You don't need to do any math** — just place the point where you want the entity

### Tile-to-pixel reference (32px maps like harta)
| Tile | Pixel |
|---|---|
| 0 | 0 |
| 5 | 160 |
| 10 | 320 |
| 25 | 800 |
| 50 | 1600 |
| 69 | 2208 |
| 100 | 3200 |

For 16px maps (like Dungeon1), divide by 2.

---

## Adding a New Map

1. **Create the TMX** in Tiled (File → New Map)
2. **Register it** in `GamePanel.setupGame()`:
   ```java
   registerMap("myNewMap", "/res/maps/myNewMap.tmx");
   ```
3. **Add the 5 object layers** in Tiled (Objects, Monsters, NPCs, InteractiveTiles, Events)
4. **Place your entities** using Point objects with `type` properties
5. **Add a door or transition** on another map that leads to this one

That's it — `MapObjectLoader` will automatically parse your new map's entities.

---

## Adding a New Entity Type

If you create a new Java class (e.g. `OBJ_Axe`), you need to register it in `MapObjectLoader.java`:

1. Open `MapObjectLoader.java`
2. Find the `createObject()` method
3. Add a new case:
   ```java
   case "Axe" -> { return new OBJ_Axe(gp); }
   ```
4. Import your new class at the top
5. Now you can place `type` = `Axe` objects in Tiled

Same pattern for new monsters (`createMonster()`), NPCs (`createNPC()`), and interactive tiles (`createInteractiveTile()`).

---

## What Stays in Java Code

| Feature | Where | Why |
|---|---|---|
| Test map return door | `AssetSetter.setObject_test()` | Needs runtime values (`previousMapId`, `doorEntryCol`) |
| Map registration | `GamePanel.setupGame()` | Maps must be registered before use |
| New entity class creation | `OBJ_*.java`, `MON_*.java` etc. | Java classes define entity behavior |
| Entity type registration | `MapObjectLoader.createObject()` | Maps type string → Java constructor |

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Entity doesn't appear | Layer name wrong | Check it's exactly `Objects`, `Monsters`, `NPCs`, `InteractiveTiles`, or `Events` (case-sensitive) |
| Entity doesn't appear | Missing `type` property | Add a `type` custom property with the correct value |
| "Unknown object type" in console | Typo in `type` value | Check spelling matches exactly (e.g. `MON_monster` not `monster`) |
| "obj[] full" warning | Too many objects | Max 100 objects, 20 monsters, 10 NPCs, 30 interactive tiles per map |
| Door doesn't transition | Missing `destination` property | Add `destination`, `destCol`, `destRow` properties |
| Map not found | Map not registered | Add `registerMap("id", "/res/maps/file.tmx")` in `setupGame()` |
| Entities at wrong position | Wrong layer in Tiled | Make sure you're placing on the correct object layer, not a tile layer |

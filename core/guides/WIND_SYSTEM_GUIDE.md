# Wind System — Authoring Guide

The wind system gives each map a **per-tile wind field**: a tile can be windier than its
neighbour, the wind has a direction, the whole map gusts over time, and rain can make chosen
tiles windier. Wind is a real **force** on the player — walking *with* the wind is faster,
*against* it is slower — but it never shoves you sideways.

This guide covers the two ways to author a wind map:

1. **Paint it in-game** with the Wind Painter (recommended — the live map shows behind your brush).
2. **Define it in code** as a wind gradient function (no painting, no file).

---

## 1. Concepts

Each map tile stores three values (a continuous grid, **not** quantized to Tiled tiles):

| Channel        | Meaning                                                                 |
|----------------|-------------------------------------------------------------------------|
| **base**       | Wind coefficient at that tile, `0` (calm) … `1` (full gale).            |
| **direction**  | The local heading the wind blows toward, in radians (`0` = east/+X).    |
| **rain-extra** | Extra strength added **only while raining/storming**, `0` … `1`. Per-tile, so *you* decide which tiles get windier in rain and by how much. |

On top of the painted field, the whole map **gusts**: a global animated direction wobbles
slowly. When it swings to a new heading it first lets the gust die down to **70%** strength,
then rotates, then builds back up — so the wind never instantly reverses. This is automatic;
you don't author it.

The player physics samples the wind over an enlarged **inertia hitbox** (a person-sized body
area, not just the feet), then applies only the component **along the player's movement axis**.
The perpendicular part is discarded, so wind speeds you up / slows you down without pushing
you off-course.

Wind data is saved to a small companion file next to the map's `.tmx`:

```
core/assets/res/maps/Shattered_Lake.tmx
core/assets/res/maps/shattered_lake.windmap   ← wind data (binary grid)
```

The runtime loads `<mapId>.windmap` from the classpath (so it ships in the jar). If none
exists, the code gradient (section 2) is used instead.

---

## 2. Option A — Paint it in-game (Wind Painter)

This is the "draw the wind over the map with a brush" tool. No separate app.

### Open it
1. Be in normal play (walking around a map).
2. **Ctrl+D** — enable debug mode.
3. **Ctrl+W** — toggle the Wind Painter. The map gets a wind overlay: coloured tiles show
   strength, little white arrows show direction. A HUD at the bottom-left shows the brush state.

### Paint
| Input            | Action                                                                 |
|------------------|------------------------------------------------------------------------|
| **Left-drag**    | Paint wind under a soft round brush. **The wind direction is the direction you drag** — flick the mouse the way you want the air to flow. |
| **Right-drag**   | Erase wind under the brush.                                             |
| **Mouse wheel**  | Brush radius bigger / smaller.                                          |
| **`[` / `]`**    | Brush strength down / up (how much each pass adds).                     |
| **`R`**          | Toggle the **rain-extra** channel. While on, strokes paint the "windier in rain" value for those tiles instead of the base strength. |
| **`F`**          | Toggle **fixed heading**: instead of drag direction, the arrow keys set a single heading (Up/Down/Left/Right). Good for a uniform prevailing wind. Press `F` again to go back to drag-direction. |
| **Delete**       | Clear all wind on this map.                                             |
| **Ctrl+S**       | Save to `core/assets/res/maps/<mapId>.windmap`.                         |

### Test as you paint
Because the painter writes into the same field the player reads, you feel edits live: paint a
strong eastward gust, then walk east (faster) and west (slower) through it. The HUD also shows
the current global gust percentage so you can see it dip on turns.

### Tips
- Build a **gradient** by overlapping soft strokes — low strength + big brush for a gentle
  breeze, then dab stronger spots where you want gusts.
- Paint **direction first with long strokes** (the heading follows your drag), then go back with
  short dabs to intensify without changing direction (direction is only re-set where you *add*
  strength, so erasing/re-dabbing keeps the heading).
- For "rain makes the shore windy": press `R`, paint the shoreline tiles, `Ctrl+S`.

---

## 3. Option B — Define wind in code (no painting)

If you'd rather not paint, you can describe a map's wind as a function of position. When a map
has **no** `.windmap` file, the field is filled from
[`WindField.proceduralWind`](../src/environment/WindField.java).

Edit that method:

```java
protected void proceduralWind(String mapId, int col, int row, float[] out) {
    if ("shattered_lake".equals(mapId)) {
        // Prevailing east wind that strengthens toward the south edge of the map.
        out[0] = 0.25f + 0.5f * (row / (float) rows); // base strength 0..1
        out[1] = 0.3f;                                 // +0.3 strength while raining
        out[2] = 0f;                                    // 0 rad = blowing east (+X)
    }
    // other maps: leave calm, or add their own rules
}
```

`out[0]` = base strength (0..1), `out[1]` = rain-extra (0..1), `out[2]` = direction in radians
(`0`=east, `π/2`=south, `π`=west, `-π/2`=north). Anything you can compute from `col`/`row`
works — radial gradients, noise, distance-to-shore, etc. A painted `.windmap`, if present,
always wins over this code.

---

## 4. Tuning the feel (constants)

| Where | Constant | Effect |
|-------|----------|--------|
| [`Player.java`](../src/entity/Player.java) | `WIND_FORCE_SCALE` | How hard wind pushes overall. Higher = stronger tail/headwind. |
| `Player.java` | `inertiaArea` (constructor) | The body area wind acts on (the enlarged inertia hitbox). |
| `Player.java` | `PLAYER_MASS`, `DRAG_K`, `DRIVE_ACCEL` | The base air-resistance movement physics (mass 60 kg, drag `½·ρ·C_D·A`). |
| [`WindField.java`](../src/environment/WindField.java) | `GUST_MIN` | How far gust strength dips before a direction change (default 70%). |
| `WindField.java` | `GUST_CHANGE_MIN/MAX`, `GUST_MAX_SWING`, `GUST_ROTATE_RATE` | How often / how far / how fast the global gust direction varies. |

---

## 5. File format (`.windmap`)

Binary, little-endian via `DataOutputStream`:

```
int   magic   = 0x57494E44  ("WIND")
int   version = 1
int   cols
int   rows
float[rows*cols] interleaved: { strength, rainBonus, angle } per cell (row-major)
```

Sized to the map's tile grid. If a map is later resized in Tiled, the loader clamps the old
data into the new grid (and logs a notice) rather than failing — re-save from the painter to
match the new size.

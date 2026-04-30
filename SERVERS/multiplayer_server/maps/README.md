# Multiplayer server map collection

Drop your `.tmx` files in this directory. Each map is identified by its filename
(without extension, lowercased): `harta.tmx` → id `harta`.

The server picks **one** active map at startup via `active_map` in
`mp_config.json`. All connected players are placed in that world. Cross-map
transitions defined in the TMX (objects in the `Events` group with a
`targetMap` property pointing to another map id in this directory) currently
notify the client with a `map_change` message — full per-player world hand-off
is reserved for a future version.

## Required TMX layout

The world parser is permissive but recognises these conventions:

- `<map>` properties:
  - `defaultSpawnCol` / `defaultSpawnRow` (int) — fallback spawn point.
  - `defaultSpawn` (string) — `"col,row"` or the name of a SpawnPoint object.
- `<objectgroup name="Collision">` — every rectangle in this group is solid.
- A `<layer>` whose `properties` include `collision=true` (or whose name is
  literally `Collision`) treats every nonzero GID as solid.
- `<objectgroup name="Events">` — drives both spawns and triggers:
  - `type` / `class` of `SpawnPoint`, `Spawn`, or `SpawnZone` registers a
    named spawn at that tile coordinate.
  - Any other rectangle becomes a trigger; on enter, the server forwards a
    `trigger` message with the object's `properties` to the client. If the
    properties include `targetMap` matching another map in this directory,
    the server emits a `map_change` for the player.

## Size declarations

`mp_config.json → maps` may declare the expected `width` and `height` of each
map. The server logs a warning if the TMX does not match. This is purely
informational — the server uses the TMX values at runtime — but keeps the
config a single source of truth for operators.

## Chunking

At startup, every tile layer is partitioned into spatial chunks of
`chunk_size_tiles × chunk_size_tiles`. Chunks are gzipped and base64-encoded
on first request, then cached. The client streams them on demand, prioritised
by player position (closest chunks first).

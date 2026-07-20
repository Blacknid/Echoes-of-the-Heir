# Modding Echoes of the Heir

Echoes of the Heir supports **Lua mods** via [LuaJ](https://github.com/luaj/luaj). Drop a mod folder
into `/mods` next to the game and it loads automatically at startup. With no `/mods` folder (or an
empty one) the game runs exactly as shipped — modding never changes vanilla behaviour.

Mods can add content and hook the game with **full reflection** over the gameplay classes (entities,
the game panel, AI, tiles, objects). What they **cannot** touch is the account/login system, the
multiplayer and save servers, the encrypted saves, and the cryptographic keys. Those are sealed.

---

## Quick start

```
mods/
  mymod/
    mod.json      <- manifest (id, name, version, entry)
    main.lua      <- your script (the "entry")
    sprites/      <- optional: your own art, referenced by path
    data/         <- created automatically for ModStorage saves (local only)
```

`mod.json`:
```json
{ "id": "mymod", "name": "My Mod", "version": "1.0", "entry": "main.lua" }
```

`main.lua`:
```lua
local Mod = import("ModApi")
Mod.registerMonster{ id = "frost_wisp", name = "Frost Wisp", maxLife = 12, aiBehavior = "ranged_archer" }
Mod.on("update", function(gp) --[[ runs every tick ]] end)
```

A complete, runnable reference mod ships in [`mods/example/`](mods/example/). Start there.

---

## The API

Everything is reached through `import(name)`.

### `import("ModApi")` — the curated high-level API

| Call | What it does |
|---|---|
| `Mod.registerMonster{ ... }` | Add/override a monster. Same fields as `res/data/monsters.json`. Needs an `id`. |
| `Mod.registerItem{ ... }` | Add/override an item (fields as `res/data/items.json`). |
| `Mod.registerNpc{ ... }` | Add/override an NPC (fields as `res/data/npcs.json`). |
| `Mod.registerAI(name, fn)` | Register a named custom AI behaviour (referenced by a monster's `aiBehavior`). |
| `Mod.on(event, fn)` | Register an event callback (see events below). |
| `Mod.setTileDimensions(px)` | Set the world tile size (8–256). Only effective if called at load time, before the world is built. |
| `Mod.modDir()` | Your mod's folder path, for building sprite paths. |
| `Mod.log(msg)` | Print an attributable line to the game log. |

### `import("ModStorage")` — local-only saves

```lua
local Store = import("ModStorage")
Store.save("progress", { level = 3, unlocked = {"a","b"} })  -- → mods/<id>/data/progress.json
local p = Store.load("progress")                              -- table, or nil if absent
Store.delete("progress")
```

Saved to **your machine only**, under your mod's own `data/` folder. **Never** uploaded to the save
server — it is a separate path with no connection to the cloud save system. This is the *only*
sanctioned way for a mod to persist data; raw file access is blocked.

### `import("Entities")` and friends — full reflection

`import` also gives you a reflective proxy over gameplay classes. On the proxy you can read/write any
permitted public field and call any permitted public method:

```lua
local Entity = import("Entities")     -- entity.Entity
print(Entity.DIR_UP)                  -- read a static field
local e = Entity.new(gp)              -- reflective constructor
e.maxLife = 50                        -- write a public field
e.speed = 3
```

Built-in aliases: `Entities`/`Entity`, `Player`, `Projectile`, `Particle`, `GamePanel`, `Config`,
`Color`, `InteractiveTile`. Any other class can be imported by its full name
(e.g. `import("object.OBJ_Chest")`) as long as it is not sealed.

### Events

| Event | Fired | Args |
|---|---|---|
| `ready` | Once, after the world is set up | `gp` (GamePanel) |
| `update` | Every fixed simulation tick (60/s) during play | `gp` |
| `break` | When a breakable (crate/vase/…) is destroyed | the tile |
| `mapLoad` | When a map finishes loading | *(reserved)* |

A callback that errors is logged and, after repeated failures, disabled — a broken mod can never
crash or stall the game loop.

---

## Security model — what is sealed, and why

Mods get full reflection over gameplay, but the following are **hard-sealed** and cannot be imported,
invoked, or received from any call. Trying returns an error (for `import`) or `nil` (for a sealed
field/return value):

- **Save server & encrypted saves** — `data.CloudSaveService`, `data.SaveLoad`
- **Multiplayer / networking** — `main.MultiplayerClient`, `main.BleMultiplayerSession`,
  `main.MpMapStreamer`, `main.ServerListManager`, `main.FriendsListManager`
- **Authoritative server** — the whole `server.*` package
- **Authentication / license / transport** — the whole `platform.*` package (License, itch auth,
  BLE, NFC), and `main.Main` (which holds the license key)
- **Crypto & dangerous JDK** — `javax.crypto.*`, `java.security.*`, `java.net.*`,
  `java.lang.reflect.*`, plus `Runtime`, `ProcessBuilder`, `System`, `ClassLoader`, raw `java.io.File`
- **Reachability seal** — even on the (allowed) `GamePanel`, the fields `saveLoad`, `mpClient`,
  `serverList`, `friendsListManager`, `bleSession` read as `nil`, so you cannot hop from a permitted
  object into a sealed one.

The seal is enforced at every reflective touch (import, field get/set, method invoke) **and on every
value returned to Lua**, so a permitted call that hands back a sealed object is neutralized to `nil`.

The sandbox also removes the Lua `io`, `os`, `luajava`, `loadfile`/`dofile`/`load` libraries, so there
is no file, process, or Java-interop escape route outside this API.

### Honest limits

This is **soft** isolation, appropriate for a modding API: it reliably keeps well-behaved and casual
mods away from auth/save/server/crypto internals, and guarantees nothing a mod does can flow into the
save-server upload path or the authoritative simulation (**the server never loads mods at all**). It
is *not* a defence against a determined attacker who already controls the machine and JVM — true hard
isolation would require running mods in a separate process, which is a possible future step.

---

## For maintainers: extending the bridge to more subsystems

This first pass wires the loader end-to-end for a representative slice: **mod monsters**
(`MonsterFactory`), the **per-tick update hook** (`GamePanel.update`), the **break hook**
(`Breakable.spawnDestroyBurst`), and **tile dimensions** (`Config` via `ModLoader.preInit`). Every
other content type and hook extends the same three seams:

1. **New declarative content type** (items, NPCs already have registry methods; add a factory hook):
   in the factory's `create(...)` add, before the vanilla lookup:
   ```java
   if (mod.ModContentRegistry.hasItem(id)) return buildItem(gp, mod.ModContentRegistry.item(id));
   ```
   and expose a `Mod.registerX{...}` in `mod.ModApi` (the shared `register(...)` helper already
   flattens a Lua def table into the `Map<String,String>` shape the factories parse JSON into).

2. **New event hook**: pick the exact engine moment, then fire it guarded:
   ```java
   if (!Headless.isEnabled() && mod.ModEventBus.has("myEvent")) {
       mod.ModEventBus.fire("myEvent", mod.LuaConv.toLua(thing));
   }
   ```
   Add the event name as a constant on `mod.ModEventBus`. `LuaConv.toLua` turns any Java object into a
   reflectable proxy (sealed objects become `nil` automatically).

3. **New safe global knob**: add a setter on `mod.ModApi` that validates its input and stores a pending
   value; apply it from `ModLoader` at the right lifecycle point (config-phase knobs, like tile size,
   must be applied in `preInit()` before the world is sized).

**Never** add an alias in `mod.JavaBridge` or a registry hook for a sealed subsystem, and when adding
a new gameplay field that must not be reachable, add it to `SEALED_FIELDS` in `mod.SecurityGate`.
`SecurityGate` is the single source of truth for the seal — all four enforcement points live there.

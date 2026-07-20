-- Example Mod for Echoes of the Heir
-- Demonstrates every part of the modding API. Delete this folder to run the game vanilla.
--
-- Loaded automatically at startup from /mods. Nothing here can reach the login, the
-- multiplayer/save servers, the encrypted saves or the crypto keys — those are sealed (try the
-- commented-out lines at the bottom to see the security gate refuse them).

print("hello from the example mod!")

----------------------------------------------------------------------
-- 1) DECLARATIVE CONTENT: add a brand-new monster.
--    Same fields as res/data/monsters.json — the engine builds it with the very same code that
--    builds a vanilla monster, so any field the base game understands works here.
----------------------------------------------------------------------
local Mod = import("ModApi")

Mod.registerMonster{
  id           = "frost_wisp",
  name         = "Frost Wisp",
  maxLife      = 12,
  attack       = 3,
  defense      = 1,
  exp          = 8,
  speed        = 2,
  aiBehavior   = "ranged_archer",   -- reuse a built-in AI profile...
  aggroRange   = 7,
  shootCooldown= 70,
  -- spriteSheet omitted on purpose: with no sheet the engine builds a valid (invisible-sheet)
  -- monster so this example needs no art. Point it at your own PNG to give it a look, e.g.:
  -- spriteSheet = Mod.modDir() .. "/sprites/frost_wisp.png",
  framesPerRow = {6, 6, 6, 6},
  solidArea    = { x = 12, y = 8, width = 40, height = 48 },
}

----------------------------------------------------------------------
-- 2) FULL REFLECTION over gameplay classes.
--    import("Entities") hands back a reflective proxy over entity.Entity. You can read/write any
--    permitted public field and call any permitted public method. (Sealed fields like GamePanel's
--    saveLoad / mpClient return nil — see the bottom of this file.)
----------------------------------------------------------------------
local Entity = import("Entities")
print("Entity.DIR_UP constant is " .. tostring(Entity.DIR_UP))   -- static field read

----------------------------------------------------------------------
-- 3) EVENT CALLBACKS: hook the game loop and world events.
----------------------------------------------------------------------
local tick = 0

-- Fires once when the world is ready (GamePanel fully set up).
Mod.on("ready", function(gp)
  Mod.log("world is ready; tile size is " .. tostring(gp.tileSize))
end)

-- Fires every fixed simulation tick (60/second) while the world is running.
Mod.on("update", function(gp)
  tick = tick + 1
  if tick % 600 == 0 then                    -- roughly every 10 seconds
    Mod.log("still ticking; player at " .. tostring(gp.player.worldX) .. "," .. tostring(gp.player.worldY))
  end
end)

-- Fires whenever a breakable (crate/vase/...) is destroyed.
Mod.on("break", function(tile)
  Mod.log("a breakable was destroyed at " .. tostring(tile.worldX) .. "," .. tostring(tile.worldY))
end)

----------------------------------------------------------------------
-- 4) LOCAL-ONLY PERSISTENCE.
--    Saves to mods/example/data/<key>.json on THIS machine. Never uploaded to the save server.
----------------------------------------------------------------------
local Store = import("ModStorage")
local saved = Store.load("stats") or { launches = 0 }
saved.launches = (saved.launches or 0) + 1
Store.save("stats", saved)
Mod.log("this mod has launched " .. tostring(saved.launches) .. " time(s) on this machine")

----------------------------------------------------------------------
-- 5) SAFE GLOBAL KNOBS.
--    Uncomment to change the tile size for the whole game (applied before the world is built).
----------------------------------------------------------------------
-- Mod.setTileDimensions(64)

----------------------------------------------------------------------
-- SECURITY: these are all REFUSED by the gate. Uncomment any one to see it blocked in the log,
-- proving auth / multiplayer / save server / crypto stay untouchable from a mod.
----------------------------------------------------------------------
-- import("data.CloudSaveService")     -- blocked: sealed class
-- import("main.MultiplayerClient")    -- blocked: sealed class
-- import("server.EngineServer")       -- blocked: sealed package
-- import("platform.LicenseActivation")-- blocked: sealed package (auth)
-- import("java.io.File")              -- blocked: sealed JDK class (mods persist via ModStorage)
-- local gp = ...; print(gp.saveLoad)  -- returns nil: sealed field, cannot reach CloudSaveService

print("example mod loaded successfully.")

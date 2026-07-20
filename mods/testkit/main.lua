-- ============================================================================
--  Test Kit  —  a mod that exercises every part of the modding API so you can
--  watch each piece work. Read the console: every line is tagged [mod:testkit].
--
--  Run headless (loads + registers, no world):   ./gradlew :core:runModSelfTest
--  Run in the real game (events fire, see below):  ./gradlew :desktop:run
--
--  Delete this folder any time — the game runs vanilla with no /mods.
-- ============================================================================

local Mod   = import("ModApi")
local Store = import("ModStorage")

Mod.log("==== Test Kit starting ====")

-- ---------------------------------------------------------------------------
-- TEST 1 — Full reflection over a gameplay class (read a static field).
-- ---------------------------------------------------------------------------
local Entity = import("Entities")
Mod.log("TEST 1 reflection: Entity.DIR_UP = " .. tostring(Entity.DIR_UP)
        .. ", Entity.TYPE_MONSTER = " .. tostring(Entity.TYPE_MONSTER))

-- ---------------------------------------------------------------------------
-- TEST 2 — Declarative content: register a custom monster.
--          (Reuses the built-in "melee_chase" AI so it needs no art to work.)
-- ---------------------------------------------------------------------------
Mod.registerMonster{
  id         = "test_slime",
  name       = "Test Slime",
  maxLife    = 20,
  attack     = 4,
  defense    = 1,
  exp        = 15,
  speed      = 2,
  aiBehavior = "melee_chase",
  aggroRange = 6,
  scale      = 1.2,
  solidArea  = { x = 12, y = 8, width = 40, height = 48 },
}
Mod.log("TEST 2 content: registered monster 'test_slime'")

-- ---------------------------------------------------------------------------
-- TEST 3 — Custom AI behaviour registered by name.
-- ---------------------------------------------------------------------------
Mod.registerAI("test_wander", function(self, gp)
  -- (Called by the engine for any monster whose aiBehavior = "test_wander".)
  -- Kept trivial here; the point is that registration succeeds.
end)
Mod.log("TEST 3 AI: registered custom AI 'test_wander'")

-- ---------------------------------------------------------------------------
-- TEST 4 — Local-only persistence: count how many times this mod has run.
--          Written to mods/testkit/data/runs.json on THIS machine only.
-- ---------------------------------------------------------------------------
local state = Store.load("runs") or { count = 0 }
state.count = state.count + 1
Store.save("runs", state)
Mod.log("TEST 4 storage: this mod has now run " .. tostring(state.count) .. " time(s) — see mods/testkit/data/runs.json")

-- The live GamePanel, captured in "ready" so other handlers (like "break", which only
-- receives the tile) can reach the player. Events get DIFFERENT args: ready/update get gp,
-- break gets the tile — so we stash gp here once and reuse it everywhere.
local world = nil

-- ---------------------------------------------------------------------------
-- TEST 5 — "ready" event: fires once, after the world is fully set up.
--          Only fires in the real game (./gradlew :desktop:run), not headless.
-- ---------------------------------------------------------------------------
Mod.on("ready", function(gp)
  world = gp   -- remember it for the break/other handlers
  Mod.log("TEST 5 ready: world is up. tileSize=" .. tostring(gp.tileSize)
          .. ", gameState=" .. tostring(gp.gameState))
  -- Reflection on a LIVE object: give the player a visible starting bonus so you
  -- can SEE the mod acted. (player.coin / player.maxLife are real public fields.)
  gp.player.coin = gp.player.coin + 100
  gp.player.maxLife = gp.player.maxLife + 4
  gp.player.life = gp.player.maxLife
  Mod.log("TEST 5 ready: granted +100 coin and +4 max life. coin now = " .. tostring(gp.player.coin))
end)

-- ---------------------------------------------------------------------------
-- TEST 6 — "update" event: fires every fixed sim tick (60/sec) during play.
--          Logs a heartbeat every ~5 seconds so it doesn't spam.
-- ---------------------------------------------------------------------------
local ticks = 0
Mod.on("update", function(gp)
  ticks = ticks + 1
  if ticks % 300 == 0 then
    Mod.log("TEST 6 update: tick " .. tostring(ticks)
            .. " | player at " .. tostring(gp.player.worldX) .. "," .. tostring(gp.player.worldY)
            .. " | life " .. tostring(gp.player.life) .. "/" .. tostring(gp.player.maxLife))
  end
end)

-- ---------------------------------------------------------------------------
-- TEST 7 — "break" event: fires when a breakable (crate/vase) is destroyed.
--          Go smash a crate in-game to see this line appear.
-- ---------------------------------------------------------------------------
Mod.on("break", function(tile)
  Mod.log("TEST 7 break: a breakable was destroyed at "
          .. tostring(tile.worldX) .. "," .. tostring(tile.worldY))
  -- The break handler only gets the tile, NOT gp — so use the player we captured in "ready".
  if world ~= nil then
    world.player.coin = world.player.coin + 100
    Mod.log("TEST 7 break: +100 coin! total = " .. tostring(world.player.coin))
  end
end)

-- ---------------------------------------------------------------------------
-- TEST 8 — Security seal: confirm sealed classes are refused. This uses
--          pcall so the refusal is caught and reported as a PASS, not a crash.
-- ---------------------------------------------------------------------------
local ok, err = pcall(function() import("data.CloudSaveService") end)
if ok then
  Mod.log("TEST 8 security: WARNING — CloudSaveService was NOT sealed (unexpected!)")
else
  Mod.log("TEST 8 security: OK — sealed class refused as expected")
end

Mod.log("==== Test Kit loaded. Watch for TEST 5/6/7 lines once the game is running. ====")

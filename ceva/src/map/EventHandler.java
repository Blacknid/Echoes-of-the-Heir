package map;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

/**
 * Handles all in-world events triggered by the player stepping on tiles.
 *
 * ---- EVENT TYPES -----------------------------------------------------------
 *  MapTransition   — step-on tile that changes map (supports spawnId)
 *  HealingPool     — restores HP/MP on ENTER press, saves game
 *  DamageTrap      — deals damage on step (optional repeatable flag)
 *  DialogueTrigger — shows a one-shot (or repeatable) dialogue message
 *  LevelGate       — blocks below minLevel; passes above (optionally transitions)
 *  Checkpoint      — silent save + HP/MP restore (no prompt)
 *  QuestTrigger    — increments quest progress by a set amount
 *  CameraShake     — triggers screen shake on step
 *
 * ---- NAMED SPAWN POINTS ----------------------------------------------------
 *  SpawnPoint objects in the Events layer register a named {id -> col, row} entry.
 *  Doors and MapTransitions reference a spawnId instead of fixed col/row coords.
 *  GamePanel.changeMap() resolves the spawnId after events are loaded.
 */
public class EventHandler {

    GamePanel gp;
    Entity eventMaster;

    int previousEventX, previousEventY;
    boolean canTouchEvent = true;

    // ---- Named spawn points --------------------------------------------------
    public final Map<String, int[]>  namedSpawnPoints = new HashMap<>();

    // ---- Map transitions -----------------------------------------------------
    final Map<Long, MapTransition> mapTransitions = new HashMap<>();

    // ---- Healing pools -------------------------------------------------------
    final Map<Long, int[]>           healingPools   = new HashMap<>();

    // ---- Damage traps --------------------------------------------------------
    final Map<Long, DamageTrapData> damageTraps   = new HashMap<>();

    // ---- Dialogue triggers ---------------------------------------------------
    final Map<Long, DialogueData>    dialogueTriggers = new HashMap<>();

    // ---- Level gates ---------------------------------------------------------
    final Map<Long, LevelGateData>   levelGates    = new HashMap<>();

    // ---- Checkpoints ---------------------------------------------------------
    final Map<Long, CheckpointData>  checkpoints  = new HashMap<>();

    // ---- Quest triggers ------------------------------------------------------
    final Map<Long, QuestTriggerData> questTriggers = new HashMap<>();

    // ---- Camera shake events -------------------------------------------------
    final Map<Long, String>          cameraShakes  = new HashMap<>();

    // ---- Memory gates --------------------------------------------------------
    final Map<Long, MemoryGateData>  memoryGates = new HashMap<>();

    // ---- Spawn zones ---------------------------------------------------------
    final List<SpawnZoneData> spawnZones = new ArrayList<>();
    private final Random spawnRnd = new Random();

    // ---- EventRect cache for AABB checks ------------------------------------
    private final HashMap<Long, EventRect> eventMap = new HashMap<>();

    // ENTRY POINT TRACKING
    public int lastTriggerCol = 0;
    public int lastTriggerRow = 0;

    // ---- Inner record types --------------------------------------------------

    static class MapTransition {
        String mapId; int spawnCol, spawnRow; String spawnId;
        MapTransition(String id, int sc, int sr, String sid) {
            mapId = id; spawnCol = sc; spawnRow = sr; spawnId = sid;
        }
    }

    private static class DamageTrapData {
        int damage;
        boolean repeatable, triggered;
        DamageTrapData(int d, boolean rep) {
            damage=d; repeatable=rep; triggered=false;
        }
    }

    static class DialogueData {
        String message; boolean oneShot, triggered;
        DialogueData(String m, String s, boolean one) { message=m; oneShot=one; triggered=false; }
    }

    private static class LevelGateData {
        int col, row, minLevel;
        String blockedMessage, targetMap, spawnId;
        int targetCol, targetRow;
        String requiredItem;   // item name the player must have (empty = no item check)
        boolean consumeItem;   // if true, remove the item from inventory when passing
        String requiredFragment; // memory fragment ID required to pass (empty = no check)
        LevelGateData(int c, int r, int ml, String msg, String tm, int tc, int tr, String sid,
                      String reqItem, boolean consume, String reqFrag) {
            col=c; row=r; minLevel=ml; blockedMessage=msg;
            targetMap=tm; targetCol=tc; targetRow=tr; spawnId=sid;
            requiredItem=reqItem; consumeItem=consume;
            requiredFragment = (reqFrag != null) ? reqFrag : "";
        }
    }

    private static class CheckpointData {
        boolean silent;
        CheckpointData(boolean s) { silent=s; }
    }

    private static class QuestTriggerData {
        String questId; int progress; boolean oneShot, triggered;
        QuestTriggerData(String qid, int p, boolean one) { questId=qid; progress=p; oneShot=one; triggered=false; }
    }

    static class SpawnZoneData {
        int worldX, worldY, areaW, areaH;
        String monsterType;
        int maxAmount, intervalFrames, spawnTimer;
        boolean confined;       // if true, spawned monsters cannot leave this zone
        int activationRange;    // tile distance from player before zone starts spawning (0 = always active)
        int totalLimit;         // total monsters to ever spawn (0 = unlimited)
        int totalSpawned;       // how many have been spawned so far
        String lootItem;        // item name to award after the last monster dies (empty = no loot)
        String lootFragment;    // memory fragment ID to collect after last monster dies (empty = none)
        boolean lootGiven;      // true once the loot has been awarded
        /** Indices into gp.monster[] that this zone has spawned and not yet replaced. */
        final List<Integer> trackedSlots = new ArrayList<>();
        SpawnZoneData(int wx, int wy, int aw, int ah, String mt, int max, int interval,
                      boolean confined, int activationRange, int totalLimit,
                      String lootItem, String lootFragment) {
            worldX=wx; worldY=wy; areaW=aw; areaH=ah;
            monsterType=mt; maxAmount=max; intervalFrames=interval; spawnTimer=0;
            this.confined=confined; this.activationRange=activationRange;
            this.totalLimit=totalLimit; this.totalSpawned=0;
            this.lootItem=(lootItem != null) ? lootItem : "";
            this.lootFragment=(lootFragment != null) ? lootFragment : "";
            this.lootGiven=false;
        }
    }

    private static class MemoryGateData {
        int col, row, requiredFragments;
        String blockedMessage, targetMap, spawnId;
        int targetCol, targetRow;
        MemoryGateData(int c, int r, int req, String msg, String tm, int tc, int tr, String sid) {
            col=c; row=r; requiredFragments=req; blockedMessage=msg;
            targetMap=tm; targetCol=tc; targetRow=tr; spawnId=sid;
        }
    }

    // ---- Constructor ---------------------------------------------------------

    public EventHandler(GamePanel gp) {
        this.gp = gp;
        eventMaster = new Entity(gp);
        eventMaster.ensureDialogues()[0][0] = "You've restored your health and mana at the Campfire.";
    }

    // ---- Reset (called on every map change) ----------------------------------

    public void reset() {
        mapTransitions.clear();
        healingPools.clear();
        damageTraps.clear();
        dialogueTriggers.clear();
        levelGates.clear();
        checkpoints.clear();
        questTriggers.clear();
        cameraShakes.clear();
        memoryGates.clear();
        namedSpawnPoints.clear();
        spawnZones.clear();
        eventMap.clear();
        canTouchEvent = true;
        previousEventX = gp.player.worldX;
        previousEventY = gp.player.worldY;
        // Clear MobSpawner zones — they are re-registered per map from Tiled
        gp.mobSpawner.clearZones();
    }

    // ---- Registration methods ------------------------------------------------

    public void registerMapTransition(int col, int row, String mapId,
                                      int spawnCol, int spawnRow, String spawnId) {
        mapTransitions.put(tileKey(col, row), new MapTransition(mapId, spawnCol, spawnRow, spawnId));
    }

    /** Legacy overload (no spawnId). */
    public void registerMapTransition(int col, int row, String mapId, int spawnCol, int spawnRow) {
        registerMapTransition(col, row, mapId, spawnCol, spawnRow, "");
    }

    public void registerHealingPool(int col, int row) {
        healingPools.put(tileKey(col, row), new int[]{col, row});
    }

    public void registerDamageTrap(int col, int row, int damage, boolean repeatable) {
        damageTraps.put(tileKey(col, row), new DamageTrapData(damage, repeatable));
    }

    public void registerDialogueTrigger(int col, int row, String message,
                                        String speaker, boolean oneShot) {
        dialogueTriggers.put(tileKey(col, row), new DialogueData(message, speaker, oneShot));
    }

    /** Package-private: registers a pre-created (shared) DialogueData for the given tile.
     *  Used by MapObjectLoader so that all tiles of a multi-tile trigger share one
     *  instance — ensuring the oneShot flag is honoured across the entire area. */
    void registerDialogueTrigger(int col, int row, DialogueData shared) {
        dialogueTriggers.put(tileKey(col, row), shared);
    }

    public void registerLevelGate(int col, int row, int minLevel, String blockedMessage,
                                  String targetMap, int targetCol, int targetRow, String spawnId,
                                  String requiredItem, boolean consumeItem, String requiredFragment) {
        levelGates.put(tileKey(col, row),
            new LevelGateData(col, row, minLevel, blockedMessage,
                              targetMap, targetCol, targetRow, spawnId,
                              requiredItem, consumeItem, requiredFragment));
    }

    public void registerCheckpoint(int col, int row, boolean silent) {
        checkpoints.put(tileKey(col, row), new CheckpointData(silent));
    }

    public void registerQuestTrigger(int col, int row, String questId,
                                     int progress, boolean oneShot) {
        questTriggers.put(tileKey(col, row), new QuestTriggerData(questId, progress, oneShot));
    }

    /** Register a named spawn point (resolved by doors/transitions after the map loads). */
    public void registerNamedSpawnPoint(String id, int col, int row) {
        namedSpawnPoints.put(id, new int[]{col, row});
    }

    public void registerMemoryGate(int col, int row, int requiredFragments,
                                   String blockedMessage, String targetMap,
                                   int targetCol, int targetRow, String spawnId) {
        memoryGates.put(tileKey(col, row),
            new MemoryGateData(col, row, requiredFragments, blockedMessage,
                               targetMap, targetCol, targetRow, spawnId));
    }

    /** Returns the {col, row} of a named spawn point, or null if not found. */
    public int[] getNamedSpawnPoint(String id) {
        return namedSpawnPoints.get(id);
    }

    public void registerCameraShake(int col, int row, String intensity) {
        cameraShakes.put(tileKey(col, row), intensity);
    }

    public void registerSpawnZone(int worldX, int worldY, int areaW, int areaH,
                                   String monsterType, int maxAmount, int intervalFrames,
                                   boolean confined, int activationRange,
                                   int totalLimit, String lootItem, String lootFragment) {
        spawnZones.add(new SpawnZoneData(worldX, worldY, areaW, areaH,
                                         monsterType, maxAmount, intervalFrames,
                                         confined, activationRange, totalLimit,
                                         lootItem, lootFragment));
        System.out.println("EventHandler: SpawnZone registered " + monsterType
            + " max=" + maxAmount + " interval=" + intervalFrames + "f"
            + " confined=" + confined + " activationRange=" + activationRange + " tiles"
            + " totalLimit=" + totalLimit + " lootItem=" + lootItem
            + " lootFragment=" + lootFragment);
    }

    /** Called every game frame (play state) to tick spawn zone timers. */
    public void updateSpawnZones() {
        if (spawnZones.isEmpty()) return;
        for (SpawnZoneData zone : spawnZones) {
            // Purge slots whose monster has died (slot nulled by GamePanel)
            zone.trackedSlots.removeIf(idx ->
                idx < 0 || idx >= gp.monster.length || gp.monster[idx] == null);

            // If totalLimit is set, check if all spawned monsters have been killed
            if (zone.totalLimit > 0 && zone.totalSpawned >= zone.totalLimit
                    && zone.trackedSlots.isEmpty() && !zone.lootGiven) {
                // All monsters from this zone are dead — award loot
                zone.lootGiven = true;
                if (!zone.lootItem.isEmpty()) {
                    entity.Entity item = gp.mapObjectLoader.createEntityByName(zone.lootItem);
                    if (item != null && gp.player.canObtainItem(item)) {
                        gp.ui.addMessage("You obtained " + item.name + "!",
                            new java.awt.Color(255, 230, 100));
                    } else if (item != null) {
                        gp.ui.addMessage("You found " + item.name + ", but your inventory is full!",
                            new java.awt.Color(255, 100, 100));
                    }
                }
                if (!zone.lootFragment.isEmpty() && gp.memoryJournal != null) {
                    data.MemoryJournal.MemoryFragment frag = gp.memoryJournal.collect(zone.lootFragment);
                    if (frag != null && gp.memoryFlashback != null) {
                        gp.memoryFlashback.trigger(frag);
                    }
                    // Collecting frag_cave marks "Find the Exit" as complete
                    if ("frag_cave".equals(zone.lootFragment) && gp.questManager != null) {
                        gp.questManager.progress("find_exit", 1);
                    }
                }
                continue; // zone is complete, nothing left to spawn
            }

            // If totalLimit reached, don't spawn more — just wait for remaining to die
            if (zone.totalLimit > 0 && zone.totalSpawned >= zone.totalLimit) continue;

            // Still at cap — nothing to do yet
            if (zone.trackedSlots.size() >= zone.maxAmount) continue;

            // Activation range check: only spawn when player is within N tiles of the zone
            if (zone.activationRange > 0) {
                int px = gp.player.worldX + gp.tileSize / 2;
                int py = gp.player.worldY + gp.tileSize / 2;
                int zCenterX = zone.worldX + zone.areaW / 2;
                int zCenterY = zone.worldY + zone.areaH / 2;
                int distTiles = (int)(Math.max(Math.abs(px - zCenterX), Math.abs(py - zCenterY)) / gp.tileSize);
                if (distTiles > zone.activationRange) continue;
            }

            // Tick the respawn timer only when a slot is free
            zone.spawnTimer++;
            if (zone.spawnTimer < zone.intervalFrames) continue;
            zone.spawnTimer = 0;

            // Find a free monster slot
            int slot = -1;
            for (int i = 0; i < gp.monster.length; i++) {
                if (gp.monster[i] == null) { slot = i; break; }
            }
            if (slot < 0) continue;

            // Try up to 20 random tiles inside the zone; skip if another entity occupies it
            int ts   = gp.tileSize;
            int cols = Math.max(1, zone.areaW / ts);
            int rows = Math.max(1, zone.areaH / ts);
            java.awt.Rectangle candidate = new java.awt.Rectangle();
            int col = -1, row = -1;
            boolean found = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                int tc = (zone.worldX / ts) + spawnRnd.nextInt(cols);
                int tr = (zone.worldY / ts) + spawnRnd.nextInt(rows);
                int wx = tc * ts;
                int wy = tr * ts;
                // Use the standard monster solid area (matches MobSpawner)
                candidate.setBounds(wx + 12, wy + 8, 40, 48);
                if (!overlapsAnyMonster(candidate)) {
                    col = tc; row = tr; found = true;
                    break;
                }
            }
            if (!found) continue; // all tiles occupied, try again next interval

            entity.Entity m = gp.mapObjectLoader.createMonsterByName(zone.monsterType, col, row);
            if (m != null) {
                if (zone.confined) {
                    m.confinementZone = new java.awt.Rectangle(
                        zone.worldX, zone.worldY, zone.areaW, zone.areaH);
                }
                gp.monster[slot] = m;
                zone.trackedSlots.add(slot);
                zone.totalSpawned++;
            }
        }
    }

    /** Returns true if {@code rect} overlaps the solid area of any currently alive monster. */
    private boolean overlapsAnyMonster(java.awt.Rectangle rect) {
        for (entity.Entity mon : gp.monster) {
            if (mon == null || !mon.alive || mon.dying) continue;
            java.awt.Rectangle solid = mon.solidArea;
            int mx = mon.worldX + solid.x;
            int my = mon.worldY + solid.y;
            if (rect.intersects(mx, my, solid.width, solid.height)) return true;
        }
        return false;
    }

    // ---- Per-frame event check -----------------------------------------------

    public void checkEvent() {
        int xDist = Math.abs(gp.player.worldX - previousEventX);
        int yDist = Math.abs(gp.player.worldY - previousEventY);
        if (Math.max(xDist, yDist) >= gp.tileSize) canTouchEvent = true;

        if (!canTouchEvent) return;

        if (forEachCandidateTile((col, row, key) -> {
            int[] pos = healingPools.get(key);
            if (pos != null && hit(col, row, Entity.DIR_ANY)) {
                healingPool(GamePanel.dialogueState);
                return true;
            }
            return false;
        })) return;

        if (forEachCandidateTile((col, row, key) -> {
            DamageTrapData trap = damageTraps.get(key);
            if (trap != null && (trap.repeatable || !trap.triggered) && hit(col, row, Entity.DIR_ANY)) {
                damageTrap(trap);
                return true;
            }
            return false;
        })) return;

        // Gates checked BEFORE dialogues — blocking barriers take priority.
        // Gates return true only when the player is blocked; a silent pass
        // (requirements met, no map transition) returns false so that
        // subsequent events (e.g. a dialogue just past the gate) can fire.
        if (forEachCandidateTile((col, row, key) -> {
            LevelGateData gate = levelGates.get(key);
            if (gate != null && hit(col, row, Entity.DIR_ANY)) {
                return triggerLevelGate(gate);
            }
            return false;
        })) return;

        if (forEachCandidateTile((col, row, key) -> {
            MemoryGateData memoryGate = memoryGates.get(key);
            if (memoryGate != null && hit(col, row, Entity.DIR_ANY)) {
                return triggerMemoryGate(memoryGate);
            }
            return false;
        })) return;

        if (forEachCandidateTile((col, row, key) -> {
            DialogueData data = dialogueTriggers.get(key);
            if (data != null && (!data.oneShot || !data.triggered) && hit(col, row, Entity.DIR_ANY)) {
                triggerDialogue(data);
                return true;
            }
            return false;
        })) return;

        if (forEachCandidateTile((col, row, key) -> {
            CheckpointData cp = checkpoints.get(key);
            if (cp != null && hit(col, row, Entity.DIR_ANY)) {
                triggerCheckpoint(cp);
                return true;
            }
            return false;
        })) return;

        if (forEachCandidateTile((col, row, key) -> {
            QuestTriggerData qt = questTriggers.get(key);
            if (qt != null && (!qt.oneShot || !qt.triggered) && hit(col, row, Entity.DIR_ANY)) {
                triggerQuestEvent(qt);
                return true;
            }
            return false;
        })) return;

        if (forEachCandidateTile((col, row, key) -> {
            String intensity = cameraShakes.get(key);
            if (intensity != null && hit(col, row, Entity.DIR_ANY)) {
                applyCameraShake(intensity);
                touchConsumed();
                return true;
            }
            return false;
        })) return;

        // Map transitions (checked last so gameplay events fire first)
        forEachCandidateTile((col, row, key) -> {
            MapTransition mt = mapTransitions.get(key);
            if (mt != null && hit(col, row, Entity.DIR_ANY)) {
                lastTriggerCol = col;
                lastTriggerRow = row;
                gp.mapManager.nextSpawnId = (mt.spawnId != null) ? mt.spawnId : "";
                gp.startTransition(mt.mapId, mt.spawnCol, mt.spawnRow);
                touchConsumed();
                return true;
            }
            return false;
        });
    }

    // ---- Event Actions -------------------------------------------------------

    public void healingPool(int gameState) {
        if (!gp.keyH.enterPressed) return;
        gp.gameState = gameState;
        gp.player.attackCanceled = true;
        gp.player.life  = gp.player.maxLife;
        gp.player.mana  = gp.player.maxMana;
        gp.aSetter.setMonster();
        eventMaster.startDialogue(eventMaster, 0);
        touchConsumed();
    }

    void damageTrap(DamageTrapData trap) {
        if (gp.player.invincible) return;
        gp.playSE(SFX.PLAYER_HIT);
        int dmg = Math.max(1, trap.damage - gp.player.defense);
        gp.player.life -= dmg;
        gp.player.invincible = true;
        gp.player.hitFlashCounter = 6;
        gp.screenShake.shakeMedium();
        if (!trap.repeatable) trap.triggered = true;
        touchConsumed();
    }

    private void triggerDialogue(DialogueData data) {
        // Set the eventMaster's active dialogue to the custom message and show it
        String[][] d = eventMaster.ensureDialogues();
        if (d[0] == null) d[0] = new String[1];
        d[0][0] = data.message;
        eventMaster.startDialogue(eventMaster, 0);
        gp.gameState = GamePanel.dialogueState;
        if (data.oneShot) data.triggered = true;
        touchConsumed();
    }

    /** @return true if the player was blocked (consume touch), false if passed silently. */
    private boolean triggerLevelGate(LevelGateData gate) {
        boolean levelOk = (gate.minLevel <= 0 || gp.player.level >= gate.minLevel);
        boolean itemOk  = true;
        int itemIdx = -1;
        if (gate.requiredItem != null && !gate.requiredItem.isEmpty()) {
            itemIdx = gp.player.searchItemInInventory(gate.requiredItem);
            itemOk = (itemIdx != 999);
        }

        boolean fragmentOk = gate.requiredFragment.isEmpty()
            || (gp.memoryJournal != null && gp.memoryJournal.has(gate.requiredFragment));

        if (levelOk && itemOk && fragmentOk) {
            // Consume the item if configured
            if (gate.consumeItem && itemIdx >= 0 && itemIdx < gp.player.inventory.size()) {
                gp.player.inventory.remove(itemIdx);
            }
            // Pass through (optionally transition map)
            if (!gate.targetMap.isEmpty()) {
                lastTriggerCol = gate.col;
                lastTriggerRow = gate.row;
                gp.mapManager.nextSpawnId = gate.spawnId != null ? gate.spawnId : "";
                gp.startTransition(gate.targetMap, gate.targetCol, gate.targetRow);
                touchConsumed();
                return true;
            }
            // Requirements met, no transition — pass silently (don't block other events)
            return false;
        } else {
            // Snap player back 2 tiles, reset walk frame, then show dialogue
            switch (gp.player.direction) {
                case Entity.DIR_UP    -> gp.player.worldY += gp.tileSize * 2;
                case Entity.DIR_DOWN  -> gp.player.worldY -= gp.tileSize * 2;
                case Entity.DIR_LEFT  -> gp.player.worldX += gp.tileSize * 2;
                case Entity.DIR_RIGHT -> gp.player.worldX -= gp.tileSize * 2;
            }
            gp.screenShake.shakeLight();
            gp.player.hitFlashCounter = 10;

            gp.player.spriteNum = 1;
            gp.player.spriteCounter = 0;
            String[][] d = eventMaster.ensureDialogues();
            if (d[0] == null) d[0] = new String[1];
            d[0][0] = gate.blockedMessage;
            eventMaster.startDialogue(eventMaster, 0);
            gp.gameState = GamePanel.dialogueState;
            touchConsumed();
            return true;
        }
    }

    /** @return true if the player was blocked (consume touch), false if passed silently. */
    private boolean triggerMemoryGate(MemoryGateData mg) {
        int collected = (gp.memoryJournal != null) ? gp.memoryJournal.getCount() : 0;
        if (collected >= mg.requiredFragments) {
            if (mg.targetMap != null && !mg.targetMap.isEmpty()) {
                lastTriggerCol = mg.col;
                lastTriggerRow = mg.row;
                gp.mapManager.nextSpawnId = mg.spawnId != null ? mg.spawnId : "";
                gp.startTransition(mg.targetMap, mg.targetCol, mg.targetRow);
                touchConsumed();
                return true;
            }
            // Requirements met, no transition — pass silently
            return false;
        } else {
            switch (gp.player.direction) {
                case Entity.DIR_UP    -> gp.player.worldY += gp.tileSize * 2;
                case Entity.DIR_DOWN  -> gp.player.worldY -= gp.tileSize * 2;
                case Entity.DIR_LEFT  -> gp.player.worldX += gp.tileSize * 2;
                case Entity.DIR_RIGHT -> gp.player.worldX -= gp.tileSize * 2;
            }
            gp.screenShake.shakeLight();
            gp.player.hitFlashCounter = 10;
            gp.player.spriteNum = 1;
            gp.player.spriteCounter = 0;
            String[][] d = eventMaster.ensureDialogues();
            if (d[0] == null) d[0] = new String[1];
            d[0][0] = mg.blockedMessage;
            eventMaster.startDialogue(eventMaster, 0);
            gp.gameState = GamePanel.dialogueState;
            touchConsumed();
            return true;
        }
    }

    private void triggerCheckpoint(CheckpointData cp) {
        if (!gp.keyH.enterPressed && !cp.silent) return;
        gp.player.life = gp.player.maxLife;
        gp.player.mana = gp.player.maxMana;
        gp.saveLoad.save(); // uncomment when SaveLoad is wired up
        if (!cp.silent) {
            String[][] d = eventMaster.ensureDialogues();
            if (d[0] == null) d[0] = new String[1];
            d[0][0] = "Checkpoint reached.\nHP and MP restored.";
            eventMaster.startDialogue(eventMaster, 0);
            gp.gameState = GamePanel.dialogueState;
        }
        touchConsumed();
    }

    private void triggerQuestEvent(QuestTriggerData qt) {
        if (gp.questManager != null) {
            gp.questManager.progress(qt.questId, qt.progress);
        }
        if (qt.oneShot) qt.triggered = true;
        touchConsumed();
    }

    private void applyCameraShake(String intensity) {
        switch (intensity.toLowerCase()) {
            case "light"  -> gp.screenShake.shakeLight();
            case "heavy"  -> gp.screenShake.shakeHeavy();
            default       -> gp.screenShake.shakeMedium();
        }
    }

    // ---- AABB Hit Test -------------------------------------------------------

    public boolean hit(int col, int row, int reqDirection) {
        EventRect er = getEventRect(col, row);

        gp.player.solidArea.x += gp.player.worldX;
        gp.player.solidArea.y += gp.player.worldY;
        er.x += col * gp.tileSize;
        er.y += row * gp.tileSize;

        boolean hit = gp.player.solidArea.intersects(er) && !er.eventDone
                && (reqDirection == Entity.DIR_ANY || gp.player.direction == reqDirection);

        if (hit) {
            previousEventX = gp.player.worldX;
            previousEventY = gp.player.worldY;
        }

        gp.player.solidArea.x = gp.player.solidAreaDefaultX;
        gp.player.solidArea.y = gp.player.solidAreaDefaultY;
        er.x = er.eventRectDefaultX;
        er.y = er.eventRectDefaultY;

        return hit;
    }

    /** Reusable return-path registration. */
    public void registerReturnTransition(int col, int row, String destinationMapId,
                                         int destCol, int destRow) {
        registerMapTransition(col, row, destinationMapId, destCol, destRow, "");
    }

    // ---- Helpers -------------------------------------------------------------

    private void touchConsumed() {
        canTouchEvent = false;
        previousEventX = gp.player.worldX;
        previousEventY = gp.player.worldY;
    }

    // ---- Debug visualisation --------------------------------------------------

    /** Draws coloured outlines for every registered event zone. Called by RenderPipeline. */
    public void drawEventDebug(Graphics2D g2,
                               int playerWorldX, int playerWorldY,
                               int pScreenX,     int pScreenY,
                               int tileSize) {
        Font   labelFont = new Font("Arial", Font.BOLD, 10);
        Stroke oldStroke = g2.getStroke();
        java.awt.Composite oldComp = g2.getComposite();
        g2.setFont(labelFont);
        g2.setStroke(new BasicStroke(2f));
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));

        // Map transitions — magenta
        drawEventLayer(g2, mapTransitions,   playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(255,   0, 255), "WARP");
        // Healing pools — bright green
        drawEventLayer(g2, healingPools,     playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(  0, 220,  80), "HEAL");
        // Damage traps — bright red
        drawEventLayer(g2, damageTraps,      playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(220,  40,  40), "TRAP");
        // Dialogue triggers — yellow
        drawEventLayer(g2, dialogueTriggers, playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(255, 230,   0), "DIAL");
        // Level gates — orange
        drawEventLayer(g2, levelGates,       playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(255, 140,   0), "GATE");
        // Checkpoints — cyan
        drawEventLayer(g2, checkpoints,      playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(  0, 220, 220), "SAVE");
        // Quest triggers — purple
        drawEventLayer(g2, questTriggers,    playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(180,  80, 255), "QUEST");
        // Camera shakes — white
        drawEventLayer(g2, cameraShakes,     playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color(220, 220, 220), "SHAKE");
        // Memory gates — deep blue
        drawEventLayer(g2, memoryGates,      playerWorldX, playerWorldY, pScreenX, pScreenY,
                       tileSize, new Color( 60,  80, 255), "MEM");

        // Spawn zones (have custom world-space bounds, not tile-grid keys)
        g2.setColor(new Color(255, 160, 0));
        for (SpawnZoneData sz : spawnZones) {
            int sx = sz.worldX - playerWorldX + pScreenX;
            int sy = sz.worldY - playerWorldY + pScreenY;
            g2.drawRect(sx, sy, sz.areaW, sz.areaH);
            g2.drawString("ZONE:" + sz.monsterType, sx + 3, sy + 13);
        }

        // Named spawn points — lime
        g2.setColor(new Color(160, 255, 80));
        for (Map.Entry<String, int[]> e : namedSpawnPoints.entrySet()) {
            int[] pos = e.getValue();  // [col, row]
            int sx = pos[0] * tileSize - playerWorldX + pScreenX;
            int sy = pos[1] * tileSize - playerWorldY + pScreenY;
            g2.drawRect(sx, sy, tileSize, tileSize);
            g2.drawString("SP:" + e.getKey(), sx + 3, sy + 13);
        }

        g2.setStroke(oldStroke);
        g2.setComposite(oldComp);
    }

    /** Draws one event-type layer (keyed by packed Long) with a given colour and label. */
    private void drawEventLayer(Graphics2D g2, Map<Long, ?> map,
                                int playerWorldX, int playerWorldY,
                                int pScreenX, int pScreenY,
                                int tileSize, Color color, String label) {
        g2.setColor(color);
        for (long k : map.keySet()) {
            int col = (int)(k >> 32);
            int row = (int)(k & 0xFFFFFFFFL);
            int sx  = col * tileSize - playerWorldX + pScreenX;
            int sy  = row * tileSize - playerWorldY + pScreenY;
            g2.drawRect(sx, sy, tileSize - 1, tileSize - 1);
            g2.drawString(label, sx + 3, sy + 13);
        }
    }

    private static long tileKey(int col, int row) { return ((long) col << 32) | (row & 0xFFFFFFFFL); }

    @FunctionalInterface
    private interface TileVisitor {
        /** Return true to stop iteration (event was consumed). */
        boolean visit(int col, int row, long key);
    }

    /**
     * Iterates all tiles the player's solid area touches and calls the visitor for each.
     * Returns true (and stops) as soon as the visitor returns true.
     */
    private boolean forEachCandidateTile(TileVisitor visitor) {
        int ts = gp.tileSize;
        int px = gp.player.worldX + gp.player.solidArea.x;
        int py = gp.player.worldY + gp.player.solidArea.y;
        int pw = gp.player.solidArea.width;
        int ph = gp.player.solidArea.height;
        int colMin = Math.max(0, px / ts);
        int colMax = Math.min(gp.maxWorldCol - 1, (px + pw) / ts);
        int rowMin = Math.max(0, py / ts);
        int rowMax = Math.min(gp.maxWorldRow - 1, (py + ph) / ts);
        for (int r = rowMin; r <= rowMax; r++) {
            for (int c = colMin; c <= colMax; c++) {
                if (visitor.visit(c, r, tileKey(c, r))) return true;
            }
        }
        return false;
    }

    /** Returns true if the player's solid area overlaps the given tile's full bounds. */
    private boolean playerOverlapsTile(int col, int row) {
        int px = gp.player.worldX + gp.player.solidArea.x;
        int py = gp.player.worldY + gp.player.solidArea.y;
        int pw = gp.player.solidArea.width;
        int ph = gp.player.solidArea.height;
        int tx = col * gp.tileSize;
        int ty = row * gp.tileSize;
        return px < tx + gp.tileSize && px + pw > tx
            && py < ty + gp.tileSize && py + ph > ty;
    }

    private EventRect getEventRect(int col, int row) {
        long k = ((long) col << 32) | (row & 0xFFFFFFFFL);
        EventRect er = eventMap.get(k);
        if (er == null) {
            er = new EventRect();
            er.x = 0; er.y = 0;
            er.width = gp.tileSize; er.height = gp.tileSize;
            er.eventRectDefaultX = 0; er.eventRectDefaultY = 0;
            eventMap.put(k, er);
        }
        return er;
    }

    // ---- Legacy public helpers (kept for AssetSetter / external callers) -----

    public void setDialogue() {
        eventMaster.ensureDialogues()[0][0] =
            "All your health and mana has been restored.\nYou feel refreshed.  \n( Game saved )";
    }
}

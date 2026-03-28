<<<<<<< HEAD:ceva/src/main/EventHandler.java
package main;
=======
package map;
>>>>>>> 64e48ffe7ab7410d3e10f2b7e9112387fcf9d11b:ceva/src/map/EventHandler.java

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
    final Map<String, MapTransition> mapTransitions = new HashMap<>();

    // ---- Healing pools -------------------------------------------------------
    final Map<String, int[]>         healingPools   = new HashMap<>();

    // ---- Damage traps --------------------------------------------------------
    final Map<String, DamageTrapData> damageTraps   = new HashMap<>();

    // ---- Dialogue triggers ---------------------------------------------------
    final Map<String, DialogueData>  dialogueTriggers = new HashMap<>();

    // ---- Level gates ---------------------------------------------------------
    final Map<String, LevelGateData> levelGates    = new HashMap<>();

    // ---- Checkpoints ---------------------------------------------------------
    final Map<String, CheckpointData> checkpoints  = new HashMap<>();

    // ---- Quest triggers ------------------------------------------------------
    final Map<String, QuestTriggerData> questTriggers = new HashMap<>();

    // ---- Camera shake events -------------------------------------------------
    final Map<String, String>        cameraShakes  = new HashMap<>();

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
        int col, row, damage; boolean repeatable;
        DamageTrapData(int c, int r, int d, boolean rep) { col=c; row=r; damage=d; repeatable=rep; }
    }

    static class DialogueData {
        String message; boolean oneShot, triggered;
        DialogueData(String m, String s, boolean one) { message=m; oneShot=one; triggered=false; }
    }

    private static class LevelGateData {
        int col, row, minLevel;
        String blockedMessage, targetMap, spawnId;
        int targetCol, targetRow;
        LevelGateData(int c, int r, int ml, String msg, String tm, int tc, int tr, String sid) {
            col=c; row=r; minLevel=ml; blockedMessage=msg;
            targetMap=tm; targetCol=tc; targetRow=tr; spawnId=sid;
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
        /** Indices into gp.monster[] that this zone has spawned and not yet replaced. */
        final List<Integer> trackedSlots = new ArrayList<>();
        SpawnZoneData(int wx, int wy, int aw, int ah, String mt, int max, int interval) {
            worldX=wx; worldY=wy; areaW=aw; areaH=ah;
            monsterType=mt; maxAmount=max; intervalFrames=interval; spawnTimer=0;
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
        mapTransitions.put(key(col, row), new MapTransition(mapId, spawnCol, spawnRow, spawnId));
    }

    /** Legacy overload (no spawnId). */
    public void registerMapTransition(int col, int row, String mapId, int spawnCol, int spawnRow) {
        registerMapTransition(col, row, mapId, spawnCol, spawnRow, "");
    }

    public void registerHealingPool(int col, int row) {
        healingPools.put(key(col, row), new int[]{col, row});
    }

    public void registerDamageTrap(int col, int row, int damage, boolean repeatable) {
        damageTraps.put(key(col, row), new DamageTrapData(col, row, damage, repeatable));
    }

    public void registerDialogueTrigger(int col, int row, String message,
                                        String speaker, boolean oneShot) {
        dialogueTriggers.put(key(col, row), new DialogueData(message, speaker, oneShot));
    }

    public void registerLevelGate(int col, int row, int minLevel, String blockedMessage,
                                  String targetMap, int targetCol, int targetRow, String spawnId) {
        levelGates.put(key(col, row),
            new LevelGateData(col, row, minLevel, blockedMessage,
                              targetMap, targetCol, targetRow, spawnId));
    }

    public void registerCheckpoint(int col, int row, boolean silent) {
        checkpoints.put(key(col, row), new CheckpointData(silent));
    }

    public void registerQuestTrigger(int col, int row, String questId,
                                     int progress, boolean oneShot) {
        questTriggers.put(key(col, row), new QuestTriggerData(questId, progress, oneShot));
    }

    /** Register a named spawn point (resolved by doors/transitions after the map loads). */
    public void registerNamedSpawnPoint(String id, int col, int row) {
        namedSpawnPoints.put(id, new int[]{col, row});
    }

    /** Returns the {col, row} of a named spawn point, or null if not found. */
    public int[] getNamedSpawnPoint(String id) {
        return namedSpawnPoints.get(id);
    }

    public void registerCameraShake(int col, int row, String intensity) {
        cameraShakes.put(key(col, row), intensity);
    }

    public void registerSpawnZone(int worldX, int worldY, int areaW, int areaH,
                                   String monsterType, int maxAmount, int intervalFrames) {
        spawnZones.add(new SpawnZoneData(worldX, worldY, areaW, areaH,
                                         monsterType, maxAmount, intervalFrames));
        System.out.println("EventHandler: SpawnZone registered " + monsterType
            + " max=" + maxAmount + " interval=" + intervalFrames + "f");
    }

    /** Called every game frame (play state) to tick spawn zone timers. */
    public void updateSpawnZones() {
        if (spawnZones.isEmpty()) return;
        for (SpawnZoneData zone : spawnZones) {
            // Purge slots whose monster has died (slot nulled by GamePanel)
            zone.trackedSlots.removeIf(idx ->
                idx < 0 || idx >= gp.monster.length || gp.monster[idx] == null);

            // Still at cap — nothing to do yet
            if (zone.trackedSlots.size() >= zone.maxAmount) continue;

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
                gp.monster[slot] = m;
                zone.trackedSlots.add(slot);
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
        if (Math.max(xDist, yDist) > gp.tileSize) canTouchEvent = true;

        if (!canTouchEvent) return;

        // Healing pools
        for (int[] pos : healingPools.values()) {
            if (hit(pos[0], pos[1], Entity.DIR_ANY)) {
                healingPool(GamePanel.dialogueState);
                return;
            }
        }

        // Damage traps
        for (DamageTrapData trap : damageTraps.values()) {
            if (hit(trap.col, trap.row, Entity.DIR_ANY)) {
                damageTrap(trap);
                return;
            }
        }

        // Dialogue triggers
        for (Map.Entry<String, DialogueData> entry : dialogueTriggers.entrySet()) {
            DialogueData data = entry.getValue();
            if (data.oneShot && data.triggered) continue;
            String[] parts = entry.getKey().split(",");
            if (hit(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Entity.DIR_ANY)) {
                triggerDialogue(data);
                return;
            }
        }

        // Level gates
        for (LevelGateData gate : levelGates.values()) {
            if (hit(gate.col, gate.row, Entity.DIR_ANY)) {
                triggerLevelGate(gate);
                return;
            }
        }

        // Checkpoints
        for (Map.Entry<String, CheckpointData> entry : checkpoints.entrySet()) {
            CheckpointData cp = entry.getValue();
            String[] parts = entry.getKey().split(",");
            if (hit(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Entity.DIR_ANY)) {
                triggerCheckpoint(cp);
                return;
            }
        }

        // Quest triggers
        for (Map.Entry<String, QuestTriggerData> entry : questTriggers.entrySet()) {
            QuestTriggerData qt = entry.getValue();
            if (qt.oneShot && qt.triggered) continue;
            String[] parts = entry.getKey().split(",");
            if (hit(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Entity.DIR_ANY)) {
                triggerQuestEvent(qt);
                return;
            }
        }

        // Camera shakes
        for (Map.Entry<String, String> entry : cameraShakes.entrySet()) {
            String[] parts = entry.getKey().split(",");
            if (hit(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Entity.DIR_ANY)) {
                applyCameraShake(entry.getValue());
                touchConsumed();
                return;
            }
        }

        // Map transitions (checked last so gameplay events fire first)
        for (Map.Entry<String, MapTransition> entry : mapTransitions.entrySet()) {
            String[] parts = entry.getKey().split(",");
            MapTransition mt = entry.getValue();
            if (hit(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Entity.DIR_ANY)) {
                lastTriggerCol = Integer.parseInt(parts[0]);
                lastTriggerRow = Integer.parseInt(parts[1]);
                gp.mapManager.nextSpawnId = (mt.spawnId != null) ? mt.spawnId : "";
                gp.startTransition(mt.mapId, mt.spawnCol, mt.spawnRow);
                touchConsumed();
                return;
            }
        }
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
        if (!trap.repeatable) trap.repeatable = false; // flag consumed for one-shot variant
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

    private void triggerLevelGate(LevelGateData gate) {
        if (gp.player.level >= gate.minLevel) {
            // Player is high enough level — pass through (optionally transition map)
            if (!gate.targetMap.isEmpty()) {
                lastTriggerCol = gate.col;
                lastTriggerRow = gate.row;
                gp.mapManager.nextSpawnId = gate.spawnId != null ? gate.spawnId : "";
                gp.startTransition(gate.targetMap, gate.targetCol, gate.targetRow);
            }
        } else {
            // Player is too low — show blocked message
            String[][] d = eventMaster.ensureDialogues();
            if (d[0] == null) d[0] = new String[1];
            d[0][0] = gate.blockedMessage;
            eventMaster.startDialogue(eventMaster, 0);
            gp.gameState = GamePanel.dialogueState;
        }
        touchConsumed();
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

    private static String key(int col, int row) { return col + "," + row; }

    private EventRect getEventRect(int col, int row) {
        long k = ((long) col << 32) | (row & 0xFFFFFFFFL);
        EventRect er = eventMap.get(k);
        if (er == null) {
            er = new EventRect();
            er.x = 8; er.y = 8;
            er.width = gp.tileSize - 16; er.height = gp.tileSize - 16;
            er.eventRectDefaultX = 8; er.eventRectDefaultY = 8;
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

package main;

import java.util.HashMap;
import java.util.Map;

import entity.Entity;

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

    // ---- Constructor ---------------------------------------------------------

    public EventHandler(GamePanel gp) {
        this.gp = gp;
        eventMaster = new Entity(gp);
        eventMaster.ensureDialogues()[0][0] = "All your health and mana has been restored.\nYou feel refreshed.  \n( Game saved )";
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
        eventMap.clear();
        canTouchEvent = true;
        previousEventX = gp.player.worldX;
        previousEventY = gp.player.worldY;
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
                gp.nextSpawnId = (mt.spawnId != null) ? mt.spawnId : "";
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
                gp.nextSpawnId = gate.spawnId != null ? gate.spawnId : "";
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
        // gp.saveLoad.save(); // uncomment when SaveLoad is wired up
        if (!cp.silent) {
            String[][] d = eventMaster.ensureDialogues();
            if (d[0] == null) d[0] = new String[1];
            d[0][0] = "Checkpoint reached.\nHP and MP restored.\n( Game saved )";
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

package main;

import entity.Entity;
import java.util.HashMap;
import java.util.Map;

public class EventHandler{

    GamePanel gp;
    private final HashMap<Long, EventRect> eventMap = new HashMap<>();
    Entity eventMaster;

    int previousEventX, previousEventY;
    boolean canTouchEvent = true;
    // map transitions keyed by "col,row"
    Map<String, MapTransition> mapTransitions = new HashMap<>();
    // healing pools keyed by "col,row"
    Map<String, int[]> healingPools = new HashMap<>();
    // damage traps keyed by "col,row" → damage amount
    Map<String, int[]> damageTraps = new HashMap<>();

    // ENTRY POINT TRACKING: Store the trigger tile position when a transition is activated
    public int lastTriggerCol = 0;
    public int lastTriggerRow = 0;

    public EventHandler(GamePanel gp) {
        this.gp = gp;
        eventMaster = new Entity(gp);
        setDialogue();
    }
    public void reset() {
        // Clear all map transitions and event states from the previous map
        mapTransitions.clear();
        healingPools.clear();
        damageTraps.clear();
        eventMap.clear();

        // Reset touch state so events on the new map can trigger properly
        canTouchEvent = true;
        previousEventX = gp.player.worldX;
        previousEventY = gp.player.worldY;
    }
    public void setDialogue() {
        
        eventMaster.ensureDialogues()[0][0] = "All your health and mana has been restored.\nYou feel refreshed.  \n( Game saved )";
    }

    public void checkEvent() {

        // CHECK IF THE PLAYER CHARACTER IS MORE THAN 1  TILE AWAY FROM THE LAST EVENT
        int xDistance = Math.abs ( gp.player.worldX - previousEventX );
        int yDistance = Math.abs ( gp.player.worldY - previousEventY );
        int distance = Math.max ( xDistance, yDistance );
        if ( distance > gp.tileSize ) {
            canTouchEvent = true;
        }
        if ( canTouchEvent ) {

            // Healing pools (loaded from TMX Events layer)
            for (Map.Entry<String, int[]> entry : healingPools.entrySet()) {
                int[] pos = entry.getValue();
                if ( hit(pos[0], pos[1], Entity.DIR_ANY) ) { healingPool( gp.dialogueState ); }
            }

            // Damage traps (loaded from TMX Events layer)
            for (Map.Entry<String, int[]> entry : damageTraps.entrySet()) {
                int[] data = entry.getValue();
                if ( hit(data[0], data[1], Entity.DIR_ANY) ) { damageTrap(data[2]); }
            }

            // Map transitions: use smooth fade transition when stepping on trigger tile
            for (String key : mapTransitions.keySet()) {
                String[] parts = key.split(",");
                int col = Integer.parseInt(parts[0]);
                int row = Integer.parseInt(parts[1]);
                MapTransition mt = mapTransitions.get(key);
                if ( hit(col, row, Entity.DIR_ANY) ) {
                    // Save the trigger position (where we entered from) before transitioning
                    lastTriggerCol = col;
                    lastTriggerRow = row;
                    // Use the safe transition entry point
                    gp.startTransition(mt.mapId, mt.spawnCol, mt.spawnRow);
                    canTouchEvent = false;
                    previousEventX = gp.player.worldX;
                    previousEventY = gp.player.worldY;
                    break;
                }
            }

        }      
    }

    public void registerMapTransition(int col, int row, String mapId, int spawnCol, int spawnRow) {
        String key = col + "," + row;
        mapTransitions.put(key, new MapTransition(mapId, spawnCol, spawnRow));
    }

    public void registerHealingPool(int col, int row) {
        String key = col + "," + row;
        healingPools.put(key, new int[]{col, row});
    }

    /**
     * Register a bidirectional transition: when stepping on (col, row),
     * go to the destination map and spawn at (destCol, destRow).
     * The return path is automatically set to go back to the previous map
     * at the spawn position stored when entering this map.
     *
     * Use this instead of registerMapTransition() for entry/exit pairs that
     * should automatically track where you came from.
     */
    public void registerReturnTransition(int col, int row, String destinationMapId, int destCol, int destRow) {
        // Register the forward transition (harta → test)
        registerMapTransition(col, row, destinationMapId, destCol, destRow);

        // The return transition will be registered when we're on the destination map.
        // See AssetSetter.registerReturnPath() for how this works.
    }

    void registerDamageTrap(int col, int row, int damage) {
        String key = col + "," + row;
        damageTraps.put(key, new int[]{col, row, damage});
    }

    static class MapTransition {
        String mapId;
        int spawnCol, spawnRow;
        MapTransition(String id, int sc, int sr){ mapId = id; spawnCol = sc; spawnRow = sr; }
    }
    private EventRect getEventRect(int col, int row) {
        long key = ((long)col << 32) | (row & 0xFFFFFFFFL);
        EventRect er = eventMap.get(key);
        if (er == null) {
            er = new EventRect();
            er.x = 8; er.y = 8;
            er.width = gp.tileSize - 16; er.height = gp.tileSize - 16;
            er.eventRectDefaultX = 8; er.eventRectDefaultY = 8;
            eventMap.put(key, er);
        }
        return er;
    }

    public boolean hit(int col, int row, int reqDirection) {

        boolean hit = false;
        EventRect er = getEventRect(col, row);

        gp.player.solidArea.x = gp.player.worldX + gp.player.solidArea.x;
        gp.player.solidArea.y = gp.player.worldY + gp.player.solidArea.y;
        er.x = col * gp.tileSize + er.x;
        er.y = row * gp.tileSize + er.y;

        if ( gp.player.solidArea.intersects(er) && !er.eventDone ) {
            if ( reqDirection == Entity.DIR_ANY || gp.player.direction == reqDirection ) {
                hit = true;

                previousEventX = gp.player.worldX;
                previousEventY = gp.player.worldY;
            }
        }

        gp.player.solidArea.x = gp.player.solidAreaDefaultX;
        gp.player.solidArea.y = gp.player.solidAreaDefaultY;
        er.x = er.eventRectDefaultX;
        er.y = er.eventRectDefaultY;

        return hit;
    }
    public void healingPool( int gameState ) {

        // Only react if ENTER was pressed THIS FRAME
        if (!gp.keyH.enterPressed) return;

        // --- EVENT FIRES ---
        gp.gameState = gameState;
        gp.player.attackCanceled = true;
        gp.player.life = gp.player.maxLife;
        gp.player.mana = gp.player.maxMana;
        gp.aSetter.setMonster();
        gp.saveLoad.save();
        eventMaster.startDialogue(eventMaster, 0);

        canTouchEvent = false;
        previousEventX = gp.player.worldX;
        previousEventY = gp.player.worldY;

    }

    public void damageTrap(int damage) {
        if (gp.player.invincible) return;

        gp.playSE(SFX.PLAYER_HIT);
        int actualDamage = damage - gp.player.defense;
        if (actualDamage < 1) actualDamage = 1;
        gp.player.life -= actualDamage;
        gp.player.invincible = true;
        gp.player.hitFlashCounter = 6;
        gp.screenShake.shakeMedium();

        canTouchEvent = false;
        previousEventX = gp.player.worldX;
        previousEventY = gp.player.worldY;
    }

}

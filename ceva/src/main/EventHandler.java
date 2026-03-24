package main;

import entity.Entity;
import java.util.HashMap;
import java.util.Map;

public class EventHandler{

    GamePanel gp;
    EventRect eventRect[][];
    Entity eventMaster;

    int previousEventX, previousEventY;
    boolean canTouchEvent = true;
    // map transitions keyed by "col,row"
    Map<String, MapTransition> mapTransitions = new HashMap<>();

    // ENTRY POINT TRACKING: Store the trigger tile position when a transition is activated
    public int lastTriggerCol = 0;
    public int lastTriggerRow = 0;

    public EventHandler(GamePanel gp) {
        this.gp = gp;

        eventMaster = new Entity(gp);

        eventRect = new EventRect[gp.maxWorldCol][gp.maxWorldRow];

        int col = 0;
        int row = 0;
        while ( col < gp.maxWorldCol && row < gp.maxWorldRow ) {

        eventRect[col][row] = new EventRect();
        eventRect[col][row].x = 8;
        eventRect[col][row].y = 8;
        eventRect[col][row].width = gp.tileSize - 16;
        eventRect[col][row].height = gp.tileSize - 16;
        eventRect[col][row].eventRectDefaultX = eventRect[col][row].x;
        eventRect[col][row].eventRectDefaultY = eventRect[col][row].y;

        col++;
        if ( col == gp.maxWorldCol ) {
            col = 0;
            row++;
            }
        }
        setDialogue();
    }
    public void reset() {
        // Clear all map transitions from the previous map
        mapTransitions.clear();

        // Rebuild event rects (same logic as constructor)
        eventRect = new EventRect[gp.maxWorldCol][gp.maxWorldRow];
        int col = 0;
        int row = 0;
        while ( col < gp.maxWorldCol && row < gp.maxWorldRow ) {

            eventRect[col][row] = new EventRect();
            eventRect[col][row].x = 8;
            eventRect[col][row].y = 8;
            eventRect[col][row].width = gp.tileSize - 16;
            eventRect[col][row].height = gp.tileSize - 16;
            eventRect[col][row].eventRectDefaultX = eventRect[col][row].x;
            eventRect[col][row].eventRectDefaultY = eventRect[col][row].y;

            col++;
            if ( col == gp.maxWorldCol ) {
                col = 0;
                row++;
            }
        }

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

            // Map-specific events
            if (gp.currentMapId.equals("harta")) {
                // Healing pool (only exists on harta map at tile 54,22)
                if ( hit(54, 22, "any") ) { healingPool( gp.dialogueState ); }
            }

            // Map transitions: use smooth fade transition when stepping on trigger tile
            for (String key : mapTransitions.keySet()) {
                String[] parts = key.split(",");
                int col = Integer.parseInt(parts[0]);
                int row = Integer.parseInt(parts[1]);
                MapTransition mt = mapTransitions.get(key);
                if ( hit(col, row, "any") ) {
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

    static class MapTransition {
        String mapId;
        int spawnCol, spawnRow;
        MapTransition(String id, int sc, int sr){ mapId = id; spawnCol = sc; spawnRow = sr; }
    }
    public boolean hit(int col, int row, String reqDirection) {

        boolean hit = false;

        gp.player.solidArea.x = gp.player.worldX + gp.player.solidArea.x;
        gp.player.solidArea.y = gp.player.worldY + gp.player.solidArea.y;
        eventRect[col][row].x = col * gp.tileSize + eventRect[col][row].x;
        eventRect[col][row].y = row * gp.tileSize + eventRect[col][row].y;

        if ( gp.player.solidArea.intersects(eventRect[col][row]) && !eventRect[col][row].eventDone ) {
            if ( gp.player.direction.contentEquals(reqDirection) || reqDirection.contentEquals("any")) {
                hit = true;

                previousEventX = gp.player.worldX;
                previousEventY = gp.player.worldY;
            }
        }

        gp.player.solidArea.x = gp.player.solidAreaDefaultX;
        gp.player.solidArea.y = gp.player.solidAreaDefaultY;
        eventRect[col][row].x = eventRect[col][row].eventRectDefaultX;
        eventRect[col][row].y = eventRect[col][row].eventRectDefaultY;

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
}

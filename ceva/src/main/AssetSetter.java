package main;

import object.OBJ_Door;

public class AssetSetter {

    GamePanel gp;

    public AssetSetter(GamePanel gp) {
        this.gp = gp;
    }

    // ========== INTERACTIVE TILES ==========
    public void setInteractiveTile() {
        for (int i = 0; i < gp.iTile.length; i++) gp.iTile[i] = null;
    }

    // ========== EVENTS / MAP TRANSITIONS ==========
    public void setEvents() {
        // Events are now loaded from TMX via loadEventsFromTMX()
    }

    // ========== OBJECTS ==========
    public void setObject() {
        for (int i = 0; i < gp.obj.length; i++) gp.obj[i] = null;

        // Test map: return door needs runtime values — keep hardcoded
        if ("test".equals(gp.currentMapId)) {
            setObject_test();
        }
    }

    private void setObject_test() {
        // Door to go back to where we entered from
        gp.obj[0] = new OBJ_Door(gp);
        gp.obj[0].worldX = 3 * gp.tileSize;
        gp.obj[0].worldY = 3 * gp.tileSize;
        int returnCol = (gp.doorEntryCol >= 0) ? gp.doorEntryCol : gp.previousTriggerCol;
        int returnRow = (gp.doorEntryRow >= 0) ? gp.doorEntryRow : gp.previousTriggerRow;
        ((OBJ_Door)gp.obj[0]).setDestination(gp.previousMapId, returnCol, returnRow + 2, false);
    }

    // ========== NPCs ==========
    public void setNPC() {
        for (int i = 0; i < gp.npc.length; i++) gp.npc[i] = null;
    }

    // ========== MONSTERS ==========
    public void setMonster() {
        for (int i = 0; i < gp.monster.length; i++) gp.monster[i] = null;
    }

    // ── TMX loading delegates ──

    /** Load objects, monsters, NPCs, and interactive tiles from TMX for the current map. */
    public void loadEntitiesFromTMX() {
        String tmxPath = gp.mapRegistry.get(gp.currentMapId);
        if (tmxPath != null) {
            gp.mapObjectLoader.loadEntities(tmxPath);
        }
    }

    /** Load events (map transitions, healing pools) from TMX for the current map. */
    public void loadEventsFromTMX() {
        String tmxPath = gp.mapRegistry.get(gp.currentMapId);
        if (tmxPath != null) {
            gp.mapObjectLoader.loadEvents(tmxPath);
        }
    }
}

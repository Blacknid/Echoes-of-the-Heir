package map;

import main.GamePanel;
import object.OBJ_Door;

public class AssetSetter {

    GamePanel gp;

    public AssetSetter(GamePanel gp) {
        this.gp = gp;
    }

    public void setInteractiveTile() {
        for (int i = 0; i < gp.iTile.length; i++) gp.iTile[i] = null;
    }

    public void setEvents() {
        // Events are now loaded from TMX via loadEventsFromTMX()
    }

    public void setObject() {
        for (int i = 0; i < gp.obj.length; i++) gp.obj[i] = null;

        // Test map: return door needs runtime values — keep hardcoded
        if ("test".equals(gp.mapManager.currentMapId)) {
            setObject_test();
        }
    }

    private void setObject_test() {
        // Door to go back to where we entered from
        gp.obj[0] = new OBJ_Door(gp);
        gp.obj[0].worldX = 3 * gp.tileSize;
        gp.obj[0].worldY = 3 * gp.tileSize;
        int returnCol = (gp.mapManager.doorEntryCol >= 0) ? gp.mapManager.doorEntryCol : gp.mapManager.previousTriggerCol;
        int returnRow = (gp.mapManager.doorEntryRow >= 0) ? gp.mapManager.doorEntryRow : gp.mapManager.previousTriggerRow;
        ((OBJ_Door)gp.obj[0]).setDestination(gp.mapManager.previousMapId, returnCol, returnRow + 2, false);
    }

    public void setNPC() {
        for (int i = 0; i < gp.npc.length; i++) gp.npc[i] = null;
    }

    public void setMonster() {
        for (int i = 0; i < gp.monster.length; i++) gp.monster[i] = null;
    }

    /** Load objects, monsters, NPCs, and interactive tiles from TMX for the current map. */
    public void loadEntitiesFromTMX() {
        String tmxPath = gp.mapManager.mapRegistry.get(gp.mapManager.currentMapId);
        if (tmxPath != null) {
            gp.mapObjectLoader.loadEntities(tmxPath);
        }
    }

    /** Load events (map transitions, healing pools) from TMX for the current map. */
    public void loadEventsFromTMX() {
        String tmxPath = gp.mapManager.mapRegistry.get(gp.mapManager.currentMapId);
        if (tmxPath != null) {
            gp.mapObjectLoader.loadEvents(tmxPath);
        }
    }
}

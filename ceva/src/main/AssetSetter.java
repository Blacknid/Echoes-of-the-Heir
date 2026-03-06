package main;

import entity.NPC_Alucard;
import monster.MON_monster;
import object.OBJ_Book;
import object.OBJ_Boots;
import object.OBJ_Chest;
import object.OBJ_Compas;
import object.OBJ_Door;
import object.OBJ_Gem;
import object.OBJ_Key;
import object.OBJ_Potion;
import object.OBJ_Tent;
import object.OBJ_Torch;
import tiles_interactive.IT_Coins;
import object.OBJ_Tower;

public class AssetSetter {

    GamePanel gp;

    public AssetSetter(GamePanel gp) {
        this.gp = gp;
    }

    // ========== INTERACTIVE TILES (dispatched by current map) ==========
    public void setInteractiveTile() {
        for (int i = 0; i < gp.iTile.length; i++) gp.iTile[i] = null;

        switch (gp.currentMapId) {
            case "harta" -> setInteractiveTile_harta();
            case "test"  -> setInteractiveTile_test();
        }
    }

    private void setInteractiveTile_harta() {
        int i = 0;
        gp.iTile[i] = new IT_Coins(gp, 69, 30);
        i++;
    }

    private void setInteractiveTile_test() {
        // No interactive tiles on test map yet
    }

    // ========== EVENTS / MAP TRANSITIONS (dispatched by current map) ==========
    public void setEvents() {
        switch (gp.currentMapId) {
            case "harta" -> setEvents_harta();
            case "test"  -> setEvents_test();
        }
    }

    private void setEvents_harta() {
        // Stepping on tile (69,30) transitions to 'test' map at spawn (5,5)
        gp.eHandler.registerMapTransition(69, 30, "test", 5, 5);
    }

    private void setEvents_test() {
        // No step-on triggers on test map - use doors to exit
    }

    // ========== OBJECTS (dispatched by current map) ==========
    public void setObject() {
        for (int i = 0; i < gp.obj.length; i++) gp.obj[i] = null;

        switch (gp.currentMapId) {
            case "harta" -> setObject_harta();
            case "test"  -> setObject_test();
        }
    }

    private void setObject_harta() {
        int i = 0;

        gp.obj[i] = new OBJ_Chest(gp);
        gp.obj[i].worldX = 43 * gp.tileSize;
        gp.obj[i].worldY = 36 * gp.tileSize;
        gp.obj[i].setLoot(new OBJ_Compas(gp));
        i++;

        gp.obj[i] = new OBJ_Tent(gp);
        gp.obj[i].worldX = 75 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Chest(gp);
        gp.obj[i].worldX = 18 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Boots(gp);
        gp.obj[i].worldX = (int) (73 * gp.tileSize);
        gp.obj[i].worldY = 65 * gp.tileSize;
        i++; 
        
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = (int)(8.08 * gp.tileSize);
        gp.obj[i].worldY = 21 * gp.tileSize;
        ((OBJ_Door)gp.obj[i]).setDestination("test", 5, 7, true);
        i++;
        
        gp.obj[i] = new OBJ_Potion(gp);
        gp.obj[i].worldX = (int)(69 * gp.tileSize);
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Potion(gp);
        gp.obj[i].worldX = (int)(71 * gp.tileSize);
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Potion(gp);
        gp.obj[i].worldX = (int)(73 * gp.tileSize);
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Torch(gp);
        gp.obj[i].worldX = 70 * gp.tileSize;
        gp.obj[i].worldY = 23 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Torch(gp);
        gp.obj[i].worldX = 23 * gp.tileSize;
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = 24 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
        ((OBJ_Door)gp.obj[i]).setDestination("test", 5, 7, true);
        i++;
        
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = 69 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = 69 * gp.tileSize;
        gp.obj[i].worldY = 28 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Boots(gp);
        gp.obj[i].worldX = 25 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Key(gp);
        gp.obj[i].worldX = 20 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Key(gp);
        gp.obj[i].worldX = 20 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Gem(gp);
        gp.obj[i].worldX = 69 * gp.tileSize;
        gp.obj[i].worldY = 18 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Book(gp);
        gp.obj[i].worldX = 24 * gp.tileSize;
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Key(gp);
        gp.obj[i].worldX = 70 * gp.tileSize;
        gp.obj[i].worldY = 34 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Key(gp);
        gp.obj[i].worldX = 73 * gp.tileSize;
        gp.obj[i].worldY = 34 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Book(gp);
        gp.obj[i].worldX = 74 * gp.tileSize;
        gp.obj[i].worldY = 24 * gp.tileSize;
        i++;

        /*gp.obj[i] = new OBJ_Tower(gp);
        gp.obj[i].worldX = 72 * gp.tileSize;
        gp.obj[i].worldY = 27 * gp.tileSize;
        i++;*/
    }

    private void setObject_test() {
        int i = 0;
        // Door to go back to where we entered from
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = 3 * gp.tileSize;
        gp.obj[i].worldY = 3 * gp.tileSize;
        // If we entered through a door, return to that door's position; otherwise use the trigger position
        int returnCol = (gp.doorEntryCol >= 0) ? gp.doorEntryCol : gp.previousTriggerCol;
        int returnRow = (gp.doorEntryRow >= 0) ? gp.doorEntryRow : gp.previousTriggerRow;
        // Spawn 2 tiles below the door to avoid re-triggering it
        ((OBJ_Door)gp.obj[i]).setDestination(gp.previousMapId, returnCol, returnRow + 2, false);
        i++;
    }

    // ========== NPCs (dispatched by current map) ==========
    public void setNPC() {
        for (int i = 0; i < gp.npc.length; i++) gp.npc[i] = null;

        switch (gp.currentMapId) {
            case "harta" -> setNPC_harta();
            case "test"  -> setNPC_test();
        }
    }

    private void setNPC_harta() {
        int i = 0;
        gp.npc[i] = new NPC_Alucard(gp);
        gp.npc[i].worldX = gp.tileSize * 71;
        gp.npc[i].worldY = gp.tileSize * 26;
        i++;
    }

    private void setNPC_test() {
        // No NPCs on test map yet
    }

    // ========== MONSTERS (dispatched by current map) ==========
    public void setMonster() {
        for (int i = 0; i < gp.monster.length; i++) gp.monster[i] = null;

        switch (gp.currentMapId) {
            case "harta" -> setMonster_harta();
            case "test"  -> setMonster_test();
        }
    }

    private void setMonster_harta() {
        int i = 0;
        gp.monster[i] = new MON_monster(gp, 70, 65); i++;
        gp.monster[i] = new MON_monster(gp, 77, 71); i++;
        gp.monster[i] = new MON_monster(gp, 80, 45); i++;
        gp.monster[i] = new MON_monster(gp, 61, 57); i++;
        gp.monster[i] = new MON_monster(gp, 83, 64); i++;
        gp.monster[i] = new MON_monster(gp, 59, 49); i++;
        gp.monster[i] = new MON_monster(gp, 68, 76); i++;
        gp.monster[i] = new MON_monster(gp, 74, 45); i++;
        gp.monster[i] = new MON_monster(gp, 74, 49); i++;
        gp.monster[i] = new MON_monster(gp, 61, 57); i++;
        gp.monster[i] = new MON_monster(gp, 59, 49); i++;
    }

    private void setMonster_test() {
        // No monsters on test map yet
    }

}

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
import object.OBJ_Torch;

public class AssetSetter {

    GamePanel gp;

    public AssetSetter(GamePanel gp) {
        this.gp = gp;
    }

    public void setObject() {

        int i = 0;
        
        gp.obj[i] = new OBJ_Boots(gp);
        gp.obj[i].worldX = (int) (73 * gp.tileSize);
        gp.obj[i].worldY = 65 * gp.tileSize;
        i++; 
        
        gp.obj[i] = new OBJ_Chest(gp);
        gp.obj[i].worldX = 43 * gp.tileSize;
        gp.obj[i].worldY = 36 * gp.tileSize;
        gp.obj[i].setLoot(new OBJ_Compas(gp));
        i++;
        
        gp.obj[i] = new OBJ_Chest(gp);
        gp.obj[i].worldX = 18 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;
        
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = (int)(8.08 * gp.tileSize);
        gp.obj[i].worldY = 21 * gp.tileSize;
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
        
        // --- TORCH PLACEMENT START ---
        // Placing a torch near the potions to light up the hallway
        gp.obj[i] = new OBJ_Torch(gp);
        gp.obj[i].worldX = 70 * gp.tileSize;
        gp.obj[i].worldY = 23 * gp.tileSize;
        i++;
        
        // Placing a torch near the book
        gp.obj[i] = new OBJ_Torch(gp);
        gp.obj[i].worldX = 23 * gp.tileSize;
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;
        // --- TORCH PLACEMENT END ---
        
        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = 24 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
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
    }
    public void setNPC() {

        int i = 0;

        gp.npc[i] = new NPC_Alucard(gp);
        gp.npc[i].worldX = gp.tileSize*71;
        gp.npc[i].worldY = gp.tileSize*26;
        i++;
    }

    public void setMonster() {

        int i = 0;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*70;
        gp.monster[i].worldY = gp.tileSize*65;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*77;
        gp.monster[i].worldY = gp.tileSize*71;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*83;
        gp.monster[i].worldY = gp.tileSize*64;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*68;
        gp.monster[i].worldY = gp.tileSize*76;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*80;
        gp.monster[i].worldY = gp.tileSize*45;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*74;
        gp.monster[i].worldY = gp.tileSize*49;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*61;
        gp.monster[i].worldY = gp.tileSize*57;
        i++;

        gp.monster[i] = new MON_monster(gp);
        gp.monster[i].worldX = gp.tileSize*59;
        gp.monster[i].worldY = gp.tileSize*49;
        i++;
    }

}

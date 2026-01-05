package main;

import java.awt.RenderingHints.Key;

import entity.NPC_Alucard;
import monster.MON_monster;
import object.OBJ_Book;
import object.OBJ_Boots;
import object.OBJ_COPAC1;
import object.OBJ_Chest;
import object.OBJ_Compas;
import object.OBJ_Door;
import object.OBJ_Ending_dj;
import object.OBJ_Ending_dm;
import object.OBJ_Ending_ds;
import object.OBJ_Ending_mj;
import object.OBJ_Ending_mm;
import object.OBJ_Ending_ms;
import object.OBJ_Ending_sj;
import object.OBJ_Ending_sm;
import object.OBJ_Ending_su;
import object.OBJ_Gem;
import object.OBJ_Key;
import object.OBJ_Potion;

public class AssetSetter {

    GamePanel gp;

    public AssetSetter(GamePanel gp) {
        this.gp = gp;
    }

    public void setObject() {

        int i = 0;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (60 * gp.tileSize);
        gp.obj[i].worldY = 21 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (77 * gp.tileSize);
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (65 * gp.tileSize);
        gp.obj[i].worldY = 23 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (51 * gp.tileSize);
        gp.obj[i].worldY = 27 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (81 * gp.tileSize);
        gp.obj[i].worldY = 27 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (64 * gp.tileSize);
        gp.obj[i].worldY = 29 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (74 * gp.tileSize);
        gp.obj[i].worldY = 29 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (42 * gp.tileSize);
        gp.obj[i].worldY = 30 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (69 * gp.tileSize);
        gp.obj[i].worldY = 32 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (83 * gp.tileSize);
        gp.obj[i].worldY = 33 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (75 * gp.tileSize);
        gp.obj[i].worldY = 34 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (59 * gp.tileSize);
        gp.obj[i].worldY = 36 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (51 * gp.tileSize);
        gp.obj[i].worldY = 37 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (68 * gp.tileSize);
        gp.obj[i].worldY = 37 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (45 * gp.tileSize);
        gp.obj[i].worldY = 40 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (38 * gp.tileSize);
        gp.obj[i].worldY = 43 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (48 * gp.tileSize);
        gp.obj[i].worldY = 44 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (65 * gp.tileSize);
        gp.obj[i].worldY = 23 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (87 * gp.tileSize);
        gp.obj[i].worldY = 40 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (83 * gp.tileSize);
        gp.obj[i].worldY = 43 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (86 * gp.tileSize);
        gp.obj[i].worldY = 46 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (81 * gp.tileSize);
        gp.obj[i].worldY = 47 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (83 * gp.tileSize);
        gp.obj[i].worldY = 52 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (88 * gp.tileSize);
        gp.obj[i].worldY = 52 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (80 * gp.tileSize);
        gp.obj[i].worldY = 51 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (78 * gp.tileSize);
        gp.obj[i].worldY = 53 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (76 * gp.tileSize);
        gp.obj[i].worldY = 49 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (76 * gp.tileSize);
        gp.obj[i].worldY = 47 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (78 * gp.tileSize);
        gp.obj[i].worldY = 55 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (87 * gp.tileSize);
        gp.obj[i].worldY = 59 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (68 * gp.tileSize);
        gp.obj[i].worldY = 54 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (73 * gp.tileSize);
        gp.obj[i].worldY = 52 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (67 * gp.tileSize);
        gp.obj[i].worldY = 51 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (68 * gp.tileSize);
        gp.obj[i].worldY = 48 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (59 * gp.tileSize);
        gp.obj[i].worldY = 51 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (51 * gp.tileSize);
        gp.obj[i].worldY = 53 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (59 * gp.tileSize);
        gp.obj[i].worldY = 57 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (83 * gp.tileSize);
        gp.obj[i].worldY = 66 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (79 * gp.tileSize);
        gp.obj[i].worldY = 68 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (77 * gp.tileSize);
        gp.obj[i].worldY = 64 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (81 * gp.tileSize);
        gp.obj[i].worldY = 70 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (74 * gp.tileSize);
        gp.obj[i].worldY = 69 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (73 * gp.tileSize);
        gp.obj[i].worldY = 72 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (71 * gp.tileSize);
        gp.obj[i].worldY = 70 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (72 * gp.tileSize);
        gp.obj[i].worldY = 63 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (69 * gp.tileSize);
        gp.obj[i].worldY = 62 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (65 * gp.tileSize);
        gp.obj[i].worldY = 70 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (66 * gp.tileSize);
        gp.obj[i].worldY = 67 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (61 * gp.tileSize);
        gp.obj[i].worldY = 64 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (63 * gp.tileSize);
        gp.obj[i].worldY = 62 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (69 * gp.tileSize);
        gp.obj[i].worldY = 62 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (72 * gp.tileSize);
        gp.obj[i].worldY = 63 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (30 * gp.tileSize);
        gp.obj[i].worldY = 43 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (24 * gp.tileSize);
        gp.obj[i].worldY = 48 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (31 * gp.tileSize);
        gp.obj[i].worldY = 49 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (36 * gp.tileSize);
        gp.obj[i].worldY = 46 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (36 * gp.tileSize);
        gp.obj[i].worldY = 49 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (23 * gp.tileSize);
        gp.obj[i].worldY = 53 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (30 * gp.tileSize);
        gp.obj[i].worldY = 56 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (35 * gp.tileSize);
        gp.obj[i].worldY = 53 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (41 * gp.tileSize);
        gp.obj[i].worldY = 54 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (37 * gp.tileSize);
        gp.obj[i].worldY = 41 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (31 * gp.tileSize);
        gp.obj[i].worldY = 49 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (38 * gp.tileSize);
        gp.obj[i].worldY = 59 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (32 * gp.tileSize);
        gp.obj[i].worldY = 61 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (42 * gp.tileSize);
        gp.obj[i].worldY = 60 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (43 * gp.tileSize);
        gp.obj[i].worldY = 65 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (37 * gp.tileSize);
        gp.obj[i].worldY = 67 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (36 * gp.tileSize);
        gp.obj[i].worldY = 64 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (32 * gp.tileSize);
        gp.obj[i].worldY = 64 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (32 * gp.tileSize);
        gp.obj[i].worldY = 61 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (28 * gp.tileSize);
        gp.obj[i].worldY = 67 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (28 * gp.tileSize);
        gp.obj[i].worldY = 62 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (24 * gp.tileSize);
        gp.obj[i].worldY = 59 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (21 * gp.tileSize);
        gp.obj[i].worldY = 65 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (27 * gp.tileSize);
        gp.obj[i].worldY = 71 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (40 * gp.tileSize);
        gp.obj[i].worldY = 70 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (41 * gp.tileSize);
        gp.obj[i].worldY = 75 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (46 * gp.tileSize);
        gp.obj[i].worldY = 70 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (50 * gp.tileSize);
        gp.obj[i].worldY = 72 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (50 * gp.tileSize);
        gp.obj[i].worldY = 68 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (54 * gp.tileSize);
        gp.obj[i].worldY = 70 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (55 * gp.tileSize);
        gp.obj[i].worldY = 75 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (48 * gp.tileSize);
        gp.obj[i].worldY = 77 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (41 * gp.tileSize);
        gp.obj[i].worldY = 75 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (49 * gp.tileSize);
        gp.obj[i].worldY = 62 * gp.tileSize;
        i++;    

        gp.obj[i] = new OBJ_Boots(gp);
        gp.obj[i].worldX = (int) (73 * gp.tileSize);
        gp.obj[i].worldY = 65 * gp.tileSize;
        i++; 

        gp.obj[i] = new OBJ_Chest(gp);
        gp.obj[i].worldX = 43 * gp.tileSize;
        gp.obj[i].worldY = 36 * gp.tileSize;
        i++;

        /*gp.obj[i] = new OBJ_COPAC1(gp);
        gp.obj[i].worldX = (int) (17.5 * gp.tileSize);
        gp.obj[i].worldY = 9 * gp.tileSize;
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
        gp.obj[i].worldX = (int)(21 * gp.tileSize);
        gp.obj[i].worldY = 21 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Door(gp);
        gp.obj[i].worldX = 24 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Boots(gp);
        gp.obj[i].worldX = 25 * gp.tileSize;
        gp.obj[i].worldY = 25 * gp.tileSize;
        i++;

        // Endiig Part

        gp.obj[i] = new OBJ_Ending_sj(gp);
        gp.obj[i].worldX = 17 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_sm(gp);
        gp.obj[i].worldX = 17 * gp.tileSize;
        gp.obj[i].worldY = 9 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_su(gp);
        gp.obj[i].worldX = 17 * gp.tileSize;
        gp.obj[i].worldY = 8 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_mj(gp);
        gp.obj[i].worldX = 18 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_mm(gp);
        gp.obj[i].worldX = 18 * gp.tileSize;
        gp.obj[i].worldY = 9 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_ms(gp);
        gp.obj[i].worldX = 18 * gp.tileSize;
        gp.obj[i].worldY = 8 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_dj(gp);
        gp.obj[i].worldX = 19 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_dm(gp);
        gp.obj[i].worldX = 19 * gp.tileSize;
        gp.obj[i].worldY = 9 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Ending_ds(gp);
        gp.obj[i].worldX = 19 * gp.tileSize;
        gp.obj[i].worldY = 8 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Key(gp);
        gp.obj[i].worldX = 20 * gp.tileSize;
        gp.obj[i].worldY = 10 * gp.tileSize;
        i++;*/

        gp.obj[i] = new OBJ_Gem(gp);
        gp.obj[i].worldX = 30 * gp.tileSize;
        gp.obj[i].worldY = 47 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Book(gp);
        gp.obj[i].worldX = 24 * gp.tileSize;
        gp.obj[i].worldY = 22 * gp.tileSize;
        i++;

        gp.obj[i] = new OBJ_Key(gp);
        gp.obj[i].worldX = 70 * gp.tileSize;
        gp.obj[i].worldY = 34 * gp.tileSize;
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

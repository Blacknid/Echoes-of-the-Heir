package object;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Key extends Entity{

    public OBJ_Key(GamePanel gp) {

        super(gp);

        type = TYPE_CONSUMABLE;
        stackable = true;
        name = "Key";
        down1 = setup("/res/objects/Key", gp.tileSize, gp.tileSize);
        description = "A small key.\nOpens locked doors & chests.";
        
        // HITBOX: Small key hitbox (24x24) centered
        solidArea.x      = gp.tileSize * 20 / 64;  // 20 at 64px
        solidArea.y      = gp.tileSize * 22 / 64;  // 22 at 64px
        solidArea.width  = gp.tileSize * 24 / 64;  // 24 at 64px
        solidArea.height = gp.tileSize * 24 / 64;  // 24 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        initDialogue();

    }

    private void initDialogue() {
    
        ensureDialogues()[0][0] = "You use the " + name + " to open the door.";
        ensureDialogues()[1][0] = "You use the " + name + " to open the chest.";
        ensureDialogues()[2][0] = "There is nothing to use the key on.";
    
    }

        @Override
    public boolean use(Entity entity) {

        int doorIndex = getDetected(entity, gp.obj, "Door");
        int chestIndex = getDetected(entity, gp.obj, "Chest");

        if (doorIndex != 999) {

            startDialogue(this, 0);
            gp.playSE(SFX.MENU_SELECT);
            gp.obj[doorIndex] = null;
            return true;
        }
        else if (chestIndex != 999) {
        
            OBJ_Chest chest = (OBJ_Chest) gp.obj[chestIndex];
        
            if (!chest.opened) {
            
                gp.playSE(SFX.MENU_SELECT);
            
                if (!gp.player.canObtainItem(chest.loot)) {
                    chest.startDialogue(chest, 1);  // inventory full
                }
                else {
                    chest.startDialogue(chest, 2);  // obtained item
                    chest.down1 = chest.image1;
                    chest.opened = true;
                    chest.collision = true;
                    if ("Compas".equals(chest.loot.name)) {
                        gp.teleportation = true;
                    }
                }
            
                return true;
            }
            else {
                chest.startDialogue(chest, 3); // already empty
                return false;
            }
        }
        else {
            startDialogue(this, 2);
            return false;
        }
    }
}

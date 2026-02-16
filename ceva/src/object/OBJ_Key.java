package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Key extends Entity{

    GamePanel gp;

    public OBJ_Key(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_consumable;
        stackable = true;
        name = "Key";
        down1 = setup("/res/objects/Key", gp.tileSize, gp.tileSize);
        description = "[" + name + "]\nA key used to open \nmysterious doors and chests.";

        setDialogue();

    }

    public void setDialogue() {
    
        dialogues[0][0] = "You use the " + name + " to open the door.";
        dialogues[1][0] = "You use the " + name + " to open the chest.";
        dialogues[2][0] = "There is nothing to use the key on.";
    
    }

    public boolean use(Entity entity) {

        int doorIndex = getDetected(entity, gp.obj, "Door");
        int chestIndex = getDetected(entity, gp.obj, "Chest");

        if (doorIndex != 999) {

            startDialogue(this, 0);
            gp.playSE(3);
            gp.obj[doorIndex] = null;
            return true;
        }
        else if (chestIndex != 999) {
        
            OBJ_Chest chest = (OBJ_Chest) gp.obj[chestIndex];
        
            if (chest.opened == false) {
            
                gp.playSE(3);
            
                if (gp.player.canObtainItem(chest.loot) == false) {
                    chest.startDialogue(chest, 1);  // inventory full
                }
                else {
                    chest.startDialogue(chest, 2);  // obtained item
                    chest.down1 = chest.image1;
                    chest.opened = true;
                    chest.collision = true;
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

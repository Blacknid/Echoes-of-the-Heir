package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Chest extends Entity{

    GamePanel gp;
    Entity loot;
    boolean opened = false;

    public OBJ_Chest(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_obstacle;
        name = "Chest";
        image = setup("/res/objects/Chest_closed", gp.tileSize, gp.tileSize);
        image1 = setup("/res/objects/Chest_opened", gp.tileSize, gp.tileSize);
        down1 = image;
        collision = true;

        solidArea.x = 4;   
        solidArea.y = 16;
        solidArea.width = 56;
        solidArea.height = 48;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }

    public void setLoot(Entity loot) {
        this.loot = new OBJ_Compas(gp);

        setDialogue();
    }

    public void setDialogue() {

        dialogues[0][0] = "You open the chest and find a " + loot.name + " ...But your inventory is full.";
        dialogues[0][1] = "\nYou obtain the " + loot.name + "!";
        dialogues[0][2] = "The chest is empty.";
    }


    public void interact() {

        if ( opened == false ) {
            gp.playSE(1);

            if ( gp.player.inventory.size() == gp.player.maxInventorySize ) {
                startDialogue(this, 0);
            }
            else {
                startDialogue(this, 1);
                gp.player.inventory.add(loot);
                down1 = image1;
                opened = true;
            }
        }
        else {
            startDialogue(this, 2);
        }
    }
}

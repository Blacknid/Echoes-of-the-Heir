package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Chest extends Entity{

    GamePanel gp;
    public boolean opened = false;
    public Entity loot;

    public OBJ_Chest(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_obstacle;
        name = "Chest";
        image = setup("/res/objects/Chest_closed", gp.tileSize, gp.tileSize);
        image1 = setup("/res/objects/Chest_opened", gp.tileSize, gp.tileSize);
        down1 = image;
        collision = true;

        solidArea.x = 8;   // Centered: 8px left + 48px width + 8px right = 64
        solidArea.y = 12;  // Top offset for chest lid area
        solidArea.width = 48;  // Chest width
        solidArea.height = 52; // Chest body height
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }

    public void setDialogue() {
    
        ensureDialogues()[0][0] = "You need a key to open this chest. [ EQUIP ]";
        ensureDialogues()[1][0] = "You open the chest and find a " + loot.name + " ... But your inventory is full.";
        ensureDialogues()[2][0] = "\nYou obtain the " + loot.name + "!";
        ensureDialogues()[3][0] = "The chest is empty.";
    
    }

    public void setLoot(Entity loot) {

        this.loot = loot;
        setDialogue();

    }

        public void interact() {
            
        startDialogue(this, 0);
    
    }

}

package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Door extends Entity{

    GamePanel gp;

    public OBJ_Door(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_obstacle;
        name = "Door";
        down1 = setup("/res/objects/Door", gp.tileSize, gp.tileSize);
        collision = true;

        solidArea.x = 8;   // Centered: 8px left + 48px width + 8px right = 64
        solidArea.y = 12;  // Top offset for door frame
        solidArea.width = 48;  // Door frame width
        solidArea.height = 52; // Door frame height
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        setDialogue();
    }

    public void setDialogue() {

        dialogues[0][0] = "You need a key to open this door. [ EQUIP ]";

    }

    public void interact() {

        startDialogue(this, 0);
    
    }
}

package object;

import java.awt.Color;

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

        solidArea.x = 0;
        solidArea.y = 16;
        solidArea.width = 64;
        solidArea.height = 48;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        setDialogue();
    }

    public void setDialogue() {

        dialogues[0][0] = "You need a key to open this door.";

    }

    public void interact() {

        startDialogue(this, 0);
    
    }
}

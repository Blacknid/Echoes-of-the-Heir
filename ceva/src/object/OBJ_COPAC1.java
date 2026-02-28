package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_COPAC1 extends Entity{

    public OBJ_COPAC1(GamePanel gp) {
        super(gp);

        name = "Tree";
        down1 = setup("/res/tiles/tree", gp.tileSize, gp.tileSize);
        collision = true;

        solidArea.x = 8;   // Centered: 8px left + 48px width + 8px right = 64
        solidArea.y = 32;  // Tree trunk bottom half
        solidArea.width = 48;  // Trunk width
        solidArea.height = 24; // Trunk height
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }

}

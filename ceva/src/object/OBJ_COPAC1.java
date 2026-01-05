package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_COPAC1 extends Entity{

    public OBJ_COPAC1(GamePanel gp) {
        super(gp);

        name = "Tree";
        down1 = setup("/res/tiles/tree", gp.tileSize, gp.tileSize);
        collision = true;

        solidArea.x = 0;
        solidArea.y = 32;
        solidArea.width = 64;
        solidArea.height = 24;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }

}

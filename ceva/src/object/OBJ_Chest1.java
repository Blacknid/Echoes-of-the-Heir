package object;

import entity.Entity;
import main.GamePanel;
import main.UtilityTool;

public class OBJ_Chest1 extends Entity{

    GamePanel gp;

    public OBJ_Chest1(GamePanel gp) {

        super(gp);

        this.gp = gp;

        UtilityTool uTool = new UtilityTool();

        name = "Chest";
        down1 = setup("/res/objects/Chest_opened", gp.tileSize, gp.tileSize);


        collision = true;

        solidArea.x = 0;
        solidArea.y = 16;
        solidArea.width = 64;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }
}

package object;

import entity.Entity;
import main.GamePanel;
import main.UtilityTool;

public class OBJ_Chest extends Entity{

    GamePanel gp;

    public OBJ_Chest(GamePanel gp) {

        super(gp);

        boolean opened = false;

        this.gp = gp;

        UtilityTool uTool = new UtilityTool();

        name = "Chest";
        down1 = setup("/res/objects/Chest_closed", gp.tileSize, gp.tileSize);

        collision = true;

        solidArea.x = 0;
        solidArea.y = 16;
        solidArea.width = 64;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }
}

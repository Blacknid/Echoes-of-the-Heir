package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_mj extends Entity{

    public OBJ_Ending_mj(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (mj)", gp.tileSize, gp.tileSize);

    }

}


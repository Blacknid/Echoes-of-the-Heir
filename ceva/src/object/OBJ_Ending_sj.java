package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_sj extends Entity{

    public OBJ_Ending_sj(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (sj)", gp.tileSize, gp.tileSize);

    }

}


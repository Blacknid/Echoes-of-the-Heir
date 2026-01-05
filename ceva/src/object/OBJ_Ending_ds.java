package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_ds extends Entity{

    public OBJ_Ending_ds(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (ds)", gp.tileSize, gp.tileSize);

    }

}

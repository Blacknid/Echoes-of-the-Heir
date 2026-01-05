package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_dj extends Entity{

    public OBJ_Ending_dj(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (dj)", gp.tileSize, gp.tileSize);

    }
}

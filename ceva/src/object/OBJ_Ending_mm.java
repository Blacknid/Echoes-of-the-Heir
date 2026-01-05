package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_mm extends Entity{

    public OBJ_Ending_mm(GamePanel gp) {
        super(gp);

        name = "Ending - altar";
        down1 = setup("/res/objects/Ending (mm)", gp.tileSize, gp.tileSize);

    }

}


package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_su extends Entity{

    public OBJ_Ending_su(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (su)", gp.tileSize, gp.tileSize);

    }

}


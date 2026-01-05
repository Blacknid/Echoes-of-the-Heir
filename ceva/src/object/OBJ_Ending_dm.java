package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_dm extends Entity{

    public OBJ_Ending_dm(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (dm)", gp.tileSize, gp.tileSize);

    }

}

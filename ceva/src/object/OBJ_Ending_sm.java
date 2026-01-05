package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_sm extends Entity{

    public OBJ_Ending_sm( GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (sm)", gp.tileSize, gp.tileSize);

    }

}


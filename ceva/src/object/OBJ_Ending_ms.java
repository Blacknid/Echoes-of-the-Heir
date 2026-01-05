package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Ending_ms extends Entity{

    public OBJ_Ending_ms(GamePanel gp) {
        super(gp);

        name = "Ending";
        down1 = setup("/res/objects/Ending (ms)", gp.tileSize, gp.tileSize);

    }

}

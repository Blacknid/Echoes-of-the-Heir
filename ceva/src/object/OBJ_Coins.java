package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Coins extends Entity {

    public OBJ_Coins(GamePanel gp) {
        super(gp);

        name = "Coins";
        down1 = setup("/res/objects/Coins", gp.tileSize, gp.tileSize);

    }
    
}

package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Coins extends Entity {

    /** Coin value awarded when player picks this up. Set from Tiled 'amount' property. */
    public int coinValue = 1;

    public OBJ_Coins(GamePanel gp) {
        super(gp);

        name = "Coins";
        down1 = setup("/res/interactive/Coins", gp.tileSize, gp.tileSize);
        
        // HITBOX: Small coins hitbox (24x24) centered
        solidArea.x      = gp.tileSize * 20 / 64;  // 20 at 64px
        solidArea.y      = gp.tileSize * 22 / 64;  // 22 at 64px
        solidArea.width  = gp.tileSize * 24 / 64;  // 24 at 64px
        solidArea.height = gp.tileSize * 24 / 64;  // 24 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }
    
}

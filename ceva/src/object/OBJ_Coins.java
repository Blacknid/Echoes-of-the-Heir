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
        solidArea.x = 20;  // 20px left + 24px width + 20px right = 64
        solidArea.y = 22;  // Centered vertically
        solidArea.width = 24;
        solidArea.height = 24;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }
    
}

package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Shield_Wood extends Entity{

    GamePanel gp;

    public OBJ_Shield_Wood(GamePanel gp) {
        
        super(gp);
        this.gp = gp;
        
        type = type_shield;
        name = "Wood_Shield";
        down1 = setup("/res/objects/shield_wood", gp.tileSize, gp.tileSize);
        defenseValue = 1;
        description = "Wooden shield.\nMinor protection.";
        
        // HITBOX: Medium shield hitbox (32x32) centered
        solidArea.x = 16;  // 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Centered
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

}

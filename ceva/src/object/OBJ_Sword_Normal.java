package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Sword_Normal extends Entity{

    public OBJ_Sword_Normal(GamePanel gp) {
        super(gp);
        
        type = type_sword;
        name = "Normal Sword";
        down1 = setup("/res/objects/sword_normal", gp.tileSize, gp.tileSize );
        attackValue = 1;
        knockBackPower = 2; // very small push
        description = "A basic sword.\nReliable and light.";
        attackArea.width = 36;
        attackArea.height = 36;
        
        // HITBOX: Medium sword hitbox (32x32) centered
        solidArea.x = 16;  // 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Centered
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

}

package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Boots extends Entity{

    public OBJ_Boots(GamePanel gp) {
        super(gp);

        type = type_buffs;
        name = "Boots";
        down1 = setup("/res/objects/Boots", gp.tileSize, gp.tileSize);
        description = "Light boots.\n+1 Speed.";
        
        // HITBOX: Medium boots hitbox (32x32) centered
        solidArea.x = 16;  // 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Centered
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }
}

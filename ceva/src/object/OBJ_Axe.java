package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Axe extends Entity{

    public OBJ_Axe(GamePanel gp) {
        super(gp);

        type = type_utility;
        name = "Axe";
        tool_type = "wood";
        working_power = 1.0f; //ia 1 punct din durabilitatea lemnului, (de ce nu poate fi java ca si celelalte limbaje de programare care au doar 1.0 ca si float?...)

        //aici se adauga textura pentru topor
        down1 = setup("/res/objects/Axe", gp.tileSize, gp.tileSize);
        description = "A sturdy axe.\nUseful for chopping wood.\nNot very useful in combat.";
        
        // HITBOX: Medium axe hitbox (32x32) centered
        solidArea.x = 16;  // 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Centered
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }
}
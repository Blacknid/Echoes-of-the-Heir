package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Compas extends Entity {

    public OBJ_Compas(GamePanel gp) {

        super(gp);

        type = type_buffs;
        name = "Compas";
        down1 = setup("/res/objects/Compas", gp.tileSize , gp.tileSize);
        description = "Ancient compass.\nTeleports you to a save point.";
        
        // HITBOX: Small compass hitbox (28x28) centered
        solidArea.x = 18;  // 18px left + 28px width + 18px right = 64
        solidArea.y = 18;  // Centered
        solidArea.width = 28;
        solidArea.height = 28;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }
}
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
        description = "[" + name + "]\nThe wooden shield \nprovides minimal \nprotection.";
    }

}

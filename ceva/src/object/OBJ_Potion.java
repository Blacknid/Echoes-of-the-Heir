package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Potion extends Entity {

    GamePanel gp;
    int value = 5;

    public OBJ_Potion ( GamePanel gp ) {

        super(gp);

        this.gp = gp;

        type = type_consumable;
        name = "Potion";
        down1 = setup("/res/objects/Potion", gp.tileSize, gp.tileSize);
        description = "[Potion]\nHeals your life by " + value + ".";
    }
}

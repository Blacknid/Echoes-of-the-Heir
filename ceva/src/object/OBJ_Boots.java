package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Boots extends Entity{

    public OBJ_Boots(GamePanel gp) {
        super(gp);

        type = type_consumable;
        name = "Boots";
        down1 = setup("/res/objects/Boots", gp.tileSize, gp.tileSize);
        description = "[Boots]\nIt helps you move faster, \nalso looks expensive.\n +1 SPEED";
    }
}

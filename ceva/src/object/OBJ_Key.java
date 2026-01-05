package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Key extends Entity{

    GamePanel gp;

    public OBJ_Key(GamePanel gp) {

        super(gp);
        this.gp = gp;

        type = type_consumable;
        name = "Key";
        down1 = setup("/res/objects/Key", gp.tileSize, gp.tileSize);
        description = "[" + name + "]\nA key used to open \nmysterious doors.";

    }
}

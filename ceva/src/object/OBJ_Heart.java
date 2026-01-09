package object;

import entity.Entity;
import main.GamePanel;
import main.UtilityTool;

public class OBJ_Heart extends Entity {

    public OBJ_Heart(GamePanel gp) {

        super(gp);

        UtilityTool uTool = new UtilityTool();

        name = "Heart";
        image = setup("/res/objects/full_heart", gp.tileSize , gp.tileSize);
        image1 = setup("/res/objects/empty_heart", gp.tileSize , gp.tileSize);           

    }
}

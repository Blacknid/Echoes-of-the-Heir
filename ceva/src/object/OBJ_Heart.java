package object;

import entity.Entity;
import main.GamePanel;
import main.UtilityTool;

public class OBJ_Heart extends Entity {

    public OBJ_Heart(GamePanel gp) {

        super(gp);

        UtilityTool uTool = new UtilityTool();

        name = "Heart";
        image = setup("/res/objects/Heart_3", gp.tileSize *  5, gp.tileSize * 2);
        image2 = setup("/res/objects/Heart_2", gp.tileSize * 5, gp.tileSize * 2);
        image3 = setup("/res/objects/Heart_1", gp.tileSize * 5, gp.tileSize * 2);
    
    }
}

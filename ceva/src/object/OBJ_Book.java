package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Book extends Entity{

    public OBJ_Book(GamePanel gp){

        super(gp);
        name = "Spell book";
        down1 = setup("/res/objects/Book",gp.tileSize, gp.tileSize);
        attackValue = 1;
        attackArea.width = 30;
        attackArea.height = 30;

    }
}

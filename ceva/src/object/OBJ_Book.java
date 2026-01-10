package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Book extends Entity{

    public OBJ_Book(GamePanel gp){

        super(gp);

        type = type_book;
        name = "Spell book";
        down1 = setup("/res/objects/Book",gp.tileSize, gp.tileSize);
        attackValue = 2;
        attackArea.width = 30;
        attackArea.height = 30;
        description = "[" + name + "]\nA book containing \nspell to cast fireball.";

    }
}

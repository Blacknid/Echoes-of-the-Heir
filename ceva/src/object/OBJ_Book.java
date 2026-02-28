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
        knockBackPower = 3; // slight magical push
        attackArea.width = 30;
        attackArea.height = 30;
        description = "Spellbook.\nContains Fireball.";
        
        // HITBOX: Medium book hitbox (32x32) centered
        solidArea.x = 16;  // 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Centered
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

    }
}

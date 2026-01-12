package object;

import java.awt.Color;

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
    public void use ( Entity entity ) {
        
        if ( gp.player.life >= gp.player.maxLife ) {
            gp.player.inventory.add(this);
            gp.ui.addMessage("But your life is already full.", Color.WHITE);
            return;
        }
        else {
            gp.ui.addMessage("You drink the potion.\n Your life has been recovered by " + Math.min(value, gp.player.maxLife - gp.player.life) + ".", Color.WHITE);
            if ( gp.player.life + value >= gp.player.maxLife ) {
                gp.player.life = gp.player.maxLife;
            }
        }
        gp.playSE(2);
    }
}

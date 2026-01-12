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

        setDialogue();

    }

    public void setDialogue() {

        dialogues[0][0] = "But your life is already full.";
        dialogues[1][0] = "You drink the potion.\n Your life has been recovered by " + Math.min(value, gp.player.maxLife - gp.player.life) + ".";

    }


    public boolean use ( Entity entity ) {
        
        if ( gp.player.life >= gp.player.maxLife ) {
            gp.player.inventory.add(this);
            startDialogue(this, 0);
        }
        else {
            startDialogue(this, 1);
            if ( gp.player.life + value >= gp.player.maxLife ) {
                gp.player.life = gp.player.maxLife;
            }
        }
        gp.playSE(2);
        return true;
    }
}

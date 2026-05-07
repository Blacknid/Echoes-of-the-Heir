package object;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Potion extends Entity {

    int value = 5;

    public OBJ_Potion ( GamePanel gp ) {

        super(gp);

        type = Entity.TYPE_CONSUMABLE;
        stackable = true;
        name = "Potion";
        down1 = setup("/res/objects/Potion", gp.tileSize, gp.tileSize);
        description = "Restores " + value + " HP.";
        
        // HITBOX: Small item hitbox (28x28) centered
        solidArea.x      = gp.tileSize * 18 / 64;  // 18 at 64px
        solidArea.y      = gp.tileSize * 20 / 64;  // 20 at 64px
        solidArea.width  = gp.tileSize * 28 / 64;  // 28 at 64px
        solidArea.height = gp.tileSize * 28 / 64;  // 28 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        initDialogue();

    }

    private void initDialogue() {

        ensureDialogues()[0][0] = "Your life is already full.";

    }

    @Override
    public boolean use(Entity entity) { 
    
    if (gp.player.life >= gp.player.maxLife) {
        startDialogue(this, 0);
    }
    else {

        int healAmount = Math.min(value, gp.player.maxLife - gp.player.life);

        ensureDialogues()[1][0] = "You drink the potion.\n Your life has been recovered by " + healAmount + ".";
        
        gp.player.life += healAmount;

        if (amount > 1) {
            amount--;
        } else {
            gp.player.inventory.remove(this);
        }
        
        startDialogue(this, 1);
    }
    
    gp.playSE(SFX.EQUIP);
    return true;
}
}

package object;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Potion extends Entity {

    GamePanel gp;
    int value = 5;

    public OBJ_Potion ( GamePanel gp ) {

        super(gp);

        this.gp = gp;

        type = type_consumable;
        stackable = true;
        name = "Potion";
        down1 = setup("/res/objects/Potion", gp.tileSize, gp.tileSize);
        description = "Restores " + value + " HP.";
        
        // HITBOX: Small item hitbox (28x28) centered
        solidArea.x = 18;  // 18px left + 28px width + 18px right = 64
        solidArea.y = 20;  // Centered vertically
        solidArea.width = 28;
        solidArea.height = 28;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        setDialogue();

    }

    public void setDialogue() {

        ensureDialogues()[0][0] = "Your life is already full.";

    }

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

package object;

import java.awt.Color;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Tent extends Entity {

    GamePanel gp;

    public OBJ_Tent(GamePanel gp) {
        super(gp);
        this.gp = gp;

        type = TYPE_CONSUMABLE;
        name = "Tent";
        down1 = setup("/res/objects/tent", gp.tileSize, gp.tileSize);
        description = "Use to sleep.\nRestores HP & MP.";
        stackable = true;
        
        // HITBOX: Larger tent hitbox (40x40) centered
        solidArea.x = 12;  // 12px left + 40px width + 12px right = 64
        solidArea.y = 12;  // Centered
        solidArea.width = 40;
        solidArea.height = 40;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    public boolean use(Entity entity) {
        
        // 1. Play Sound
        gp.playSE(SFX.EQUIP); 
        
        // 2. Restore Player Stats
        gp.player.life = gp.player.maxLife;
        gp.player.mana = gp.player.maxMana;
        
        // 3. Reset Environment to Day
        if (gp.eManager != null && gp.eManager.dayState != gp.eManager.day ) {
            gp.player.inventory.remove(this);
            gp.eManager.dayState = gp.eManager.day;
            gp.eManager.dayCounter = 0;
            gp.eManager.filterAlpha = 0f;
        }

        // 4. Feedback
        gp.ui.addMessage("You slept until morning.", Color.WHITE);
        return true;
    }
}

package object;

import gfx.Color;

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
        solidArea.x      = gp.tileSize * 3 / 16;  // 12 at 64px
        solidArea.y      = gp.tileSize * 3 / 16;  // 12 at 64px
        solidArea.width  = gp.tileSize * 5 / 8;   // 40 at 64px
        solidArea.height = gp.tileSize * 5 / 8;   // 40 at 64px
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

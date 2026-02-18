package object;

import entity.Entity;
import environment.EnvironmentManager;
import main.GamePanel;
import java.awt.Color;

public class OBJ_Tent extends Entity {

    GamePanel gp;

    public OBJ_Tent(GamePanel gp) {
        super(gp);
        this.gp = gp;

        type = type_consumable;
        name = "Tent";
        down1 = setup("/res/objects/tent", gp.tileSize, gp.tileSize);
        description = "[Tent]\nYou can sleep until\nmorning to restore\nhealth and mana.";
        stackable = true;
    }

    public boolean use(Entity entity) {
        
        // 1. Play Sound
        gp.playSE(2); 
        
        // 2. Restore Player Stats
        gp.player.life = gp.player.maxLife;
        gp.player.mana = gp.player.maxMana;
        
        // 3. Reset Environment to Day
        if (gp.eManager != null) {
            gp.eManager.dayState = gp.eManager.day;
            gp.eManager.dayCounter = 0;
            gp.eManager.filterAlpha = 0f;
        }

        // 4. Feedback
        gp.ui.addMessage("You slept until morning.", Color.WHITE);
        
        return true; // Return true to consume the item
    }
}

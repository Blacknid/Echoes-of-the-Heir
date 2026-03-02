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
        gp.playSE(2); 

        gp.obj[0] = new OBJ_Tent(gp);
        gp.obj[0].worldX = (gp.player.worldX + gp.player.solidArea.x) / gp.tileSize + 2 * gp.tileSize;
        gp.obj[0].worldY = (gp.player.worldY + gp.player.solidArea.y) / gp.tileSize + 2 * gp.tileSize;
        
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
        return true;
    }
}

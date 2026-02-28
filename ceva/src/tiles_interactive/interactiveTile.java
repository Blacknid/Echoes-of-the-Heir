package tiles_interactive;

import java.awt.Graphics2D;
import entity.Entity;
import main.GamePanel;

public class interactiveTile extends Entity {

    GamePanel gp;
    public boolean destructible = false;
    public boolean openable = false;

    public interactiveTile(GamePanel gp, int col, int row) {
        super(gp);
        this.gp = gp;
        this.worldX = gp.tileSize * col;
        this.worldY = gp.tileSize * row;
        
        // DEFAULT HITBOX: Full tile hitbox (48x48) centered on 64x64 tile
        solidArea.x = 8;   // 8px left + 48px width + 8px right = 64
        solidArea.y = 8;   // 8px top + 48px height + 8px bottom = 64
        solidArea.width = 48;
        solidArea.height = 48;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    // By default, no weapon destroys it
    public boolean isCorrectItem(Entity entity) {
        return false;
    }

    public void update() {
        if (invincible) {
            invincibleCounter++;
            if (invincibleCounter > 20) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
    }

    public void draw(Graphics2D g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {

            g2.drawImage(down1, screenX, screenY, gp.tileSize, gp.tileSize, null);
        }
    }
}
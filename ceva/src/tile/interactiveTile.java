package tile;

import gfx.GdxRenderer;

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
        
        // DEFAULT HITBOX: 48x48 centered on tile, scales with tileSize
        solidArea.x      = gp.tileSize / 8;       // 8 at 64px tile
        solidArea.y      = gp.tileSize / 8;
        solidArea.width  = gp.tileSize * 3 / 4;   // 48 at 64px tile
        solidArea.height = gp.tileSize * 3 / 4;
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

    /**
     * Ground-decal shadow, drawn by RenderPipeline BEFORE the Y-sorted entity pass so it always
     * sits underneath the player/NPCs/monsters. No-op by default; override in subclasses that
     * draw their own shadow (e.g. IT_Tree) instead of drawing it inside draw().
     */
    public void drawShadow(GdxRenderer g2) {
    }

    public void draw(GdxRenderer g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + (gp.screenWidth - gp.player.screenX) &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + (gp.screenHeight - gp.player.screenY)) {

            g2.drawImage(down1, screenX, screenY, gp.tileSize, gp.tileSize);
        }
    }
}
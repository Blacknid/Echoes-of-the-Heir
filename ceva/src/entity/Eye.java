package entity;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import main.GamePanel;

public class Eye extends Entity {

    public Eye(GamePanel gp) {
        super(gp);

        name = "Eye";
        direction = "down";
        collision = false; // decorative for now
        speed = 0;

        // Try to load an eye sprite if available. Use the safe `setup` loader
        // so missing resources don't throw in the constructor.
        BufferedImage eyeImg = setup("/res/monster/Eye_spritesheet", gp.tileSize, gp.tileSize);
        down1 = eyeImg; // may be null if resource missing

        // Small hitbox positioned where the eye texture will be drawn.
        // The Eye's worldY is intentionally set slightly below the tower so
        // it sorts after the tower; the draw() method renders the texture
        // one tile above (screenY - gp.tileSize). To align the hitbox with
        // that drawn position, set the solidArea y offset to -gp.tileSize.
        int hbWidth = gp.tileSize - 16; // center a slightly smaller hitbox
        int hbHeight = gp.tileSize - 16;
        solidArea.x = (gp.tileSize - hbWidth) / 2;
        solidArea.y = -gp.tileSize + (gp.tileSize - hbHeight) / 2; // place on top tile
        solidArea.width = hbWidth;
        solidArea.height = hbHeight;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        // Health and basic stats
        maxLife = 10;
        life = maxLife;
        defense = 0;
        invincible = false;
    }

    @Override
    public void draw(Graphics2D g2) {
        // Draw the eye one tile higher than its worldY so it appears on the tower top.
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        // viewport check (allow one-tile margin so it draws when slightly offscreen)
        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {

            if (down1 != null) {
                // draw one tile above: subtract gp.tileSize from the screen Y
                g2.drawImage(down1, screenX, screenY - gp.tileSize, gp.tileSize, gp.tileSize, null);
            }
        }
    }
}

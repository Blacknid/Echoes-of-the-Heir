package object;

import java.awt.Graphics2D;
import entity.Eye;
import entity.Entity;
import main.GamePanel;

public class OBJ_Tower extends Entity {

    private GamePanel gpRef;

    private boolean eyeSpawned = false;

    public OBJ_Tower(GamePanel gp) {
        super(gp);
        this.gpRef = gp;

        type = type_obstacle;
        name = "Tower";
        description = "A tall, imposing tower.\nBlocks the way.\nCame from under the eye";
        collision = true; // Player cannot walk through it

        // Load the two tile parts. Expect two separate images:
        // - /res/objects/Tower_sus (bottom)
        // - /res/objects/Tower_jos (top)
        down1 = setup("/res/objects/Tower_sus", gp.tileSize, gp.tileSize); // bottom tile
        down2 = setup("/res/objects/Tower_jos", gp.tileSize, gp.tileSize); // top tile

        // Solid area covers both tiles (two tiles tall)
        solidArea.x = 8;
        solidArea.y = 0;
        solidArea.width = 48;
        solidArea.height = gp.tileSize * 2;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    @Override
    public void update() {
        super.update();
    }

    private void spawnEye() {
        Eye eye = new Eye(gpRef);
        // Place the eye so it sorts AFTER the tower but is drawn visually above it.
        // We set worldY slightly below the tower's Y so the renderer draws the eye later,
        // and override Eye.draw to subtract one tile when drawing.
        eye.worldX = this.worldX;
        eye.worldY = this.worldY + 1; // small offset so eye sorts after the tower

        // Try to register it as an NPC (so it will be updated/drawn)
        for (int i = 0; i < gpRef.npc.length; i++) {
            if (gpRef.npc[i] == null) {
                gpRef.npc[i] = eye;
                return;
            }
        }

        // Fallback: if no NPC slot, try monster array
        for (int i = 0; i < gpRef.monster.length; i++) {
            if (gpRef.monster[i] == null) {
                gpRef.monster[i] = eye;
                return;
            }
        }
    }

    @Override
    public void draw(Graphics2D g2) {
        int screenX = worldX - this.gpRef.player.worldX + this.gpRef.player.screenX;
        int screenY = worldY - this.gpRef.player.worldY + this.gpRef.player.screenY;

        if (worldX + this.gpRef.tileSize > this.gpRef.player.worldX - this.gpRef.player.screenX &&
            worldX - this.gpRef.tileSize < this.gpRef.player.worldX + this.gpRef.player.screenX &&
            worldY + this.gpRef.tileSize > this.gpRef.player.worldY - this.gpRef.player.screenY &&
            worldY - this.gpRef.tileSize < this.gpRef.player.worldY + this.gpRef.player.screenY) {

            // Ensure the Eye is spawned once when the tower is first drawn
            if (!eyeSpawned) {
                spawnEye();
                eyeSpawned = true;
            }

            // Draw top tile above the base
            if (down2 != null) {
                g2.drawImage(down2, screenX, screenY - gpRef.tileSize, gpRef.tileSize, gpRef.tileSize, null);
            }

            // Draw bottom tile
            if (down1 != null) {
                g2.drawImage(down1, screenX, screenY, gpRef.tileSize, gpRef.tileSize, null);
            }
        }
    }

}
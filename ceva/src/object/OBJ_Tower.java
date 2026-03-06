package object;

import java.awt.Graphics2D;
import entity.Eye;
import entity.Entity;
import main.GamePanel;

public class OBJ_Tower extends Entity {

    private boolean eyeSpawned = false;

    public OBJ_Tower(GamePanel gp) {
        super(gp);

        type = type_obstacle;
        name = "Tower";
        description = "A tall, imposing tower.\nBlocks the way.\nCame from under the eye";
        collision = true; // Player cannot walk through it

        // Load the two tile parts. Expect two separate images:
        // - /res/objects/tower_down1 (bottom)
        // - /res/objects/tower_down2 (top)
        Image
        down1 = setup("/res/objects/Compas", gp.tileSize, gp.tileSize); // bottom tile
        down2 = setup("/res/objects/Compas", gp.tileSize, gp.tileSize); // top tile

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

        // Spawn the Eye entity on top of the tower once (after world position has been set)
        if (!eyeSpawned) {
            spawnEye();
            eyeSpawned = true;
        }
    }

    private void spawnEye() {
        Eye eye = new Eye(gp);
        // Place the eye on top tile of the tower
        eye.worldX = this.worldX;
        eye.worldY = this.worldY - gp.tileSize;

        // Try to register it as an NPC (so it will be updated/drawn)
        for (int i = 0; i < gp.npc.length; i++) {
            if (gp.npc[i] == null) {
                gp.npc[i] = eye;
                return;
            }
        }

        // Fallback: if no NPC slot, try monster array
        for (int i = 0; i < gp.monster.length; i++) {
            if (gp.monster[i] == null) {
                gp.monster[i] = eye;
                return;
            }
        }
    }

    @Override
    public void draw(Graphics2D g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {

            // Draw top tile above the base
            if (down2 != null) {
                g2.drawImage(down2, screenX, screenY - gp.tileSize, gp.tileSize, gp.tileSize, null);
            }

            // Draw bottom tile
            if (down1 != null) {
                g2.drawImage(down1, screenX, screenY, gp.tileSize, gp.tileSize, null);
            }
        }
    }

}
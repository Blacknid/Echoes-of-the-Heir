package object;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import entity.Entity;
import entity.Eye;
import main.GamePanel;

public class OBJ_Tower extends Entity {

    private GamePanel gpRef;
    private Eye spawnedEye;

    public OBJ_Tower(GamePanel gp) {
        super(gp);
        this.gpRef = gp;

        type = TYPE_OBSTACLE;
        name = "Tower";
        description = "A tall, imposing tower.\nBlocks the way.\nCame from under the eye";
        collision = true; // Player cannot walk through it

        // Tower_sus = top part, Tower_jos = bottom part
        down1 = setup("/res/objects/Tower_sus", gp.tileSize, gp.tileSize);
        down2 = setup("/res/objects/Tower_jos", gp.tileSize, gp.tileSize);

        // Only the lower tile blocks movement.
        solidArea.x = 8;
        solidArea.y = 0;
        solidArea.width = 48;
        solidArea.height = gp.tileSize;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    /**
     * Spawn the Eye monster in the monster array at the tower's position.
     * Call this AFTER setting worldX/worldY on the tower.
     */
    public void spawnEye() {
        if (spawnedEye != null && spawnedEye.alive) {
            return;
        }

        Eye eye = new Eye(gpRef);
        eye.worldX = this.worldX;
        eye.worldY = this.worldY;
        spawnedEye = eye;

        for (int i = 0; i < gpRef.monster.length; i++) {
            if (gpRef.monster[i] == null) {
                gpRef.monster[i] = eye;
                return;
            }
        }
    }

    @Override
    public void draw(Graphics2D g2) {
        int screenX = worldX - gpRef.player.worldX + gpRef.player.screenX;
        int screenY = worldY - gpRef.player.worldY + gpRef.player.screenY;

        if (worldX + gpRef.tileSize > gpRef.player.worldX - gpRef.player.screenX &&
            worldX - gpRef.tileSize < gpRef.player.worldX + gpRef.player.screenX &&
            worldY + gpRef.tileSize > gpRef.player.worldY - gpRef.player.screenY &&
            worldY - gpRef.tileSize < gpRef.player.worldY + gpRef.player.screenY) {

            // Draw bottom part (Tower_jos)
            if (down2 != null) {
                g2.drawImage(down2, screenX, screenY, gpRef.tileSize, gpRef.tileSize, null);
            }

            // Draw top part (Tower_sus) one tile above
            if (down1 != null) {
                g2.drawImage(down1, screenX, screenY - gpRef.tileSize, gpRef.tileSize, gpRef.tileSize, null);
            }

            // Draw the spawned Eye a bit larger and centered on top of the tower.
            BufferedImage currentEyeSprite = (spawnedEye != null) ? spawnedEye.getCurrentSprite() : null;
            if (currentEyeSprite != null) {
                int eyeSize = (int) (gpRef.tileSize * 2.5);
                int eyeX = screenX - (eyeSize - gpRef.tileSize) / 2;
                int eyeY = screenY + spawnedEye.solidArea.y - (eyeSize - spawnedEye.solidArea.height) / 2 - 12;
                g2.drawImage(currentEyeSprite, eyeX, eyeY, eyeSize, eyeSize, null);
            }
        }
    }
}
package entity;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import main.GamePanel;

/**
 * Visual-only swing overlay spawned by the Withered Tree boss during attacks.
 * Classified as a particle: lives in particleList, follows the boss,
 * plays the attack-swing spritesheet, and auto-destroys when done.
 */
public class BossSwingEffect extends Entity {

    private final Entity boss;
    private final BufferedImage[][] swingFrames;
    private final int dir;
    private int frameIndex;
    private int frameCounter = 0;
    private static final int FRAMES_PER_TICK = 4;

    public BossSwingEffect(GamePanel gp, Entity boss,
                           BufferedImage[][] swingFrames, int dir, int startFrame) {
        super(gp);
        this.boss = boss;
        this.swingFrames = swingFrames;
        this.dir = dir;
        this.frameIndex = startFrame;
        this.worldX = boss.worldX;
        this.worldY = boss.worldY;
        this.alive = true;
    }

    @Override
    public void update() {
        // Track boss position
        worldX = boss.worldX;
        worldY = boss.worldY;

        frameCounter++;
        if (frameCounter >= FRAMES_PER_TICK) {
            frameCounter = 0;
            frameIndex++;
            if (swingFrames == null || dir < 0 || dir >= swingFrames.length
                    || swingFrames[dir] == null || frameIndex >= swingFrames[dir].length) {
                alive = false;
            }
        }
    }

    @Override
    public void draw(Graphics2D g2) {
        if (swingFrames == null || dir < 0 || dir >= swingFrames.length
                || swingFrames[dir] == null || frameIndex < 0
                || frameIndex >= swingFrames[dir].length) {
            return;
        }

        BufferedImage frame = swingFrames[dir][frameIndex];
        if (frame == null) return;

        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;
        int drawSize = gp.tileSize * 6;

        int drawX = screenX - (drawSize - gp.tileSize) / 2;
        int drawY = screenY - (drawSize - gp.tileSize);

        changeAlpha(g2, 0.85f);
        g2.drawImage(frame, drawX, drawY, drawSize, drawSize, null);
        changeAlpha(g2, 1f);
    }
}

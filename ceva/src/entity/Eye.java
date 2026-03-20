package entity;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ThreadLocalRandom;
import main.GamePanel;
import main.UtilityTool;

public class Eye extends Entity {

    private final BufferedImage[] downFrames = new BufferedImage[8];
    private final BufferedImage[] upFrames = new BufferedImage[8];
    private final BufferedImage[] leftFrames = new BufferedImage[8];
    private final BufferedImage[] rightFrames = new BufferedImage[8];
    private int randomDirectionTimer = 0;
    private int nextRandomDirectionTicks;

    public Eye(GamePanel gp) {
        super(gp);

        name = "Eye";
        type = type_monster;
        direction = "down";
        collision = false; // Player can walk through
        speed = 0;
        walkFrameCount = 8;
        animationFrameInterval = 6;
        attack = 2;
        defense = 0;
        maxLife = 999999;
        life = maxLife;
        nextRandomDirectionTicks = randomDirectionDelay();

        loadEyeImages();
        updateHitbox();

        // Tower handles drawing the eye sprite
        drawing = false;
    }

    @Override
    public void update() {
        spriteCounter++;
        if (spriteCounter > animationFrameInterval) {
            spriteNum++;
            if (spriteNum > 8) {
                spriteNum = 1;
            }
            spriteCounter = 0;
        }

        int dx = gp.player.getCenterX() - getCenterX();
        int dy = gp.player.getCenterY() - getCenterY();
        int maxTrackingDistance = gp.tileSize * 7;

        if ((long) dx * dx + (long) dy * dy <= (long) maxTrackingDistance * maxTrackingDistance) {
            randomDirectionTimer = 0;
            if (Math.abs(dx) > Math.abs(dy)) {
                direction = (dx < 0) ? "left" : "right";
            } else {
                direction = (dy < 0) ? "up" : "down";
            }
        } else {
            randomDirectionTimer++;
            if (randomDirectionTimer >= nextRandomDirectionTicks) {
                direction = randomDirection();
                randomDirectionTimer = 0;
                nextRandomDirectionTicks = randomDirectionDelay();
            }
        }

        updateHitbox();

        // Stationary hazard: no movement, but still checks contact with player.
        checkCollision();
    }

    @Override
    public void draw(Graphics2D g2) {
        // Drawing handled by OBJ_Tower
    }

    private void loadEyeImages() {
        BufferedImage[][] frames = loadSpriteMatrix("/res/monster/Eye_spritesheet", 32, 32);
        for (int i = 0; i < 8; i++) {
            BufferedImage baseFrame = UtilityTool.scaleImage(frames[0][i], gp.tileSize, gp.tileSize);

            rightFrames[i] = baseFrame;
            downFrames[i] = Projectile.rotateImage(baseFrame, 90);
            leftFrames[i] = Projectile.rotateImage(baseFrame, 180);
            upFrames[i] = Projectile.rotateImage(baseFrame, 270);
        }

        up1 = upFrames[0];
        down1 = downFrames[0];
        left1 = leftFrames[0];
        right1 = rightFrames[0];
    }

    private void updateHitbox() {
        int eyeTopOffset = gp.tileSize + 12;

        switch (direction) {
            case "up" -> {
                solidArea.x = 16;
                solidArea.y = -eyeTopOffset + 6;
                solidArea.width = 32;
                solidArea.height = 48;
            }
            case "down" -> {
                solidArea.x = 16;
                solidArea.y = -eyeTopOffset + 10;
                solidArea.width = 32;
                solidArea.height = 48;
            }
            case "left", "right" -> {
                solidArea.x = 8;
                solidArea.y = -eyeTopOffset + 16;
                solidArea.width = 48;
                solidArea.height = 32;
            }
        }

        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    public BufferedImage getCurrentSprite() {
        int frameIndex = Math.max(0, Math.min(spriteNum - 1, 7));

        return switch (direction) {
            case "up" -> upFrames[frameIndex];
            case "left" -> leftFrames[frameIndex];
            case "right" -> rightFrames[frameIndex];
            default -> downFrames[frameIndex];
        };
    }

    private String randomDirection() {
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> "up";
            case 1 -> "down";
            case 2 -> "left";
            default -> "right";
        };
    }

    private int randomDirectionDelay() {
        // 2-3 seconds at 60 FPS.
        return ThreadLocalRandom.current().nextInt(120, 181);
    }
}

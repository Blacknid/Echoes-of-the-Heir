package entity;

import gfx.GdxRenderer;
import gfx.Sprite;
import java.util.concurrent.ThreadLocalRandom;

import main.GamePanel;
import util.UtilityTool;

public class Eye extends Entity {

    private final Sprite[] downFrames = new Sprite[8];
    private final Sprite[] upFrames = new Sprite[8];
    private final Sprite[] leftFrames = new Sprite[8];
    private final Sprite[] rightFrames = new Sprite[8];
    private int randomDirectionTimer = 0;
    private int nextRandomDirectionTicks;

    public Eye(GamePanel gp) {
        super(gp);

        name = "Eye";
        type = TYPE_MONSTER;
        direction = DIR_DOWN;
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
                direction = (dx < 0) ? DIR_LEFT : DIR_RIGHT;
            } else {
                direction = (dy < 0) ? DIR_UP : DIR_DOWN;
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
    public void draw(GdxRenderer g2) {
        // Drawing handled by OBJ_Tower
    }

    private void loadEyeImages() {
        Sprite[][] frames = loadSpriteMatrix("/res/monster/Eye_spritesheet", 32, 32);
        for (int i = 0; i < 8; i++) {
            Sprite baseFrame = UtilityTool.scaleImage(frames[0][i], gp.tileSize, gp.tileSize);

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
        int ts = gp.tileSize;
        int eyeTopOffset = ts + ts * 12 / 64;  // (tileSize + 12) at 64px, scales proportionally

        switch (direction) {
            case DIR_UP -> {
                solidArea.x      = ts / 4;                      // 16 at 64px
                solidArea.y      = -eyeTopOffset + ts * 6 / 64; // +6 at 64px
                solidArea.width  = ts / 2;                      // 32 at 64px
                solidArea.height = ts * 3 / 4;                  // 48 at 64px
            }
            case DIR_DOWN -> {
                solidArea.x      = ts / 4;                       // 16 at 64px
                solidArea.y      = -eyeTopOffset + ts * 10 / 64; // +10 at 64px
                solidArea.width  = ts / 2;                       // 32 at 64px
                solidArea.height = ts * 3 / 4;                   // 48 at 64px
            }
            case DIR_LEFT, DIR_RIGHT -> {
                solidArea.x      = ts / 8;                       // 8 at 64px
                solidArea.y      = -eyeTopOffset + ts / 4;       // +16 at 64px
                solidArea.width  = ts * 3 / 4;                   // 48 at 64px
                solidArea.height = ts / 2;                       // 32 at 64px
            }
        }

        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    public Sprite getCurrentSprite() {
        int frameIndex = Math.max(0, Math.min(spriteNum - 1, 7));

        return switch (direction) {
            case DIR_UP    -> upFrames[frameIndex];
            case DIR_LEFT  -> leftFrames[frameIndex];
            case DIR_RIGHT -> rightFrames[frameIndex];
            default        -> downFrames[frameIndex];
        };
    }

    private int randomDirection() {
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> DIR_UP;
            case 1 -> DIR_DOWN;
            case 2 -> DIR_LEFT;
            default -> DIR_RIGHT;
        };
    }

    private int randomDirectionDelay() {
        // 2-3 seconds at 60 FPS.
        return ThreadLocalRandom.current().nextInt(120, 181);
    }
}

package monster;

import entity.Entity;
import entity.Projectile;
import java.awt.image.BufferedImage;
import java.util.Random;
import main.GamePanel;

/**
 * Ranged skeleton archer that keeps distance and fires arrows at the player.
 * Reuses the mummy sprite (tinted) until a dedicated sprite is available.
 */
public class MON_SkeletonArcher extends Entity {

    GamePanel gp;
    private static final Random random = new Random();
    private static final String[] DIRECTIONS = {"up", "down", "left", "right"};

    // AI behavior
    private int shootCooldown = 0;
    private static final int SHOOT_COOLDOWN_MAX = 90; // 1.5 seconds
    private static final int PREFERRED_DIST = 5;       // tiles
    private static final int AGGRO_RANGE = 8;          // tiles
    private static final int FLEE_DIST = 2;            // too-close threshold in tiles

    public MON_SkeletonArcher(GamePanel gp, int col, int row) {
        super(gp);
        this.gp = gp;

        type = 2;
        name = "Skeleton Archer";
        collision = true;
        this.worldX = col * gp.tileSize;
        this.worldY = row * gp.tileSize;
        defaultSpeed = 1;
        speed = defaultSpeed;
        walkFrameCount = 6;
        maxLife = 4;
        life = maxLife;
        attack = 2;
        defense = 0;
        exp = 4;

        solidArea.x = 12;
        solidArea.y = 8;
        solidArea.width = 40;
        solidArea.height = 48;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        // Set up projectile
        projectile = new object.OBJ_Arrow(gp);

        getImage();
    }

    public void getImage() {
        // Reuse mummy sprite (can be replaced with unique sprite later)
        int[] framesPerRow = {6, 6, 6, 6};
        BufferedImage[][] frames = loadSheetVariable("/res/monster/Monster_walking-sheet", framesPerRow);
        down1 = frames[0][0];   down2 = frames[0][1];   down3 = frames[0][2];
        down4 = frames[0][3];   down5 = frames[0][4];   down6 = frames[0][5];
        left1 = frames[1][0];   left2 = frames[1][1];   left3 = frames[1][2];
        left4 = frames[1][3];   left5 = frames[1][4];   left6 = frames[1][5];
        right1 = frames[2][0];  right2 = frames[2][1];  right3 = frames[2][2];
        right4 = frames[2][3];  right5 = frames[2][4];  right6 = frames[2][5];
        up1 = frames[3][0];     up2 = frames[3][1];     up3 = frames[3][2];
        up4 = frames[3][3];     up5 = frames[3][4];     up6 = frames[3][5];
    }

    @Override
    public void setAction() {
        if (shootCooldown > 0) shootCooldown--;

        int dx = worldX - gp.player.worldX;
        int dy = worldY - gp.player.worldY;
        double dist = Math.sqrt(dx * dx + dy * dy) / gp.tileSize;

        if (fleeing) {
            fleeCounter++;
            setFleeDirection(dx, dy);
            if (fleeCounter > fleeDuration) {
                fleeing = false;
                fleeCounter = 0;
                speed = defaultSpeed;
            }
            return;
        }

        if (dist < AGGRO_RANGE) {
            if (dist < FLEE_DIST) {
                // Too close — strafe sideways to escape
                speed = defaultSpeed + 1;
                setStrafeDirecion(dx, dy);
            } else if (dist < PREFERRED_DIST) {
                // In shooting range — stop and shoot
                speed = 0;
                facePlayer(dx, dy);
                tryShoot();
            } else {
                // Approach to preferred range
                speed = defaultSpeed;
                facePlayer(dx, dy);
                onPath = true;
                int goalCol = gp.player.getTileCol();
                int goalRow = gp.player.getTileRow();
                searchPath(goalCol, goalRow);
            }
        } else {
            onPath = false;
            speed = defaultSpeed;
            randomMovement();
        }
    }

    private void tryShoot() {
        if (shootCooldown <= 0 && projectile != null) {
            // Get a projectile from pool
            Projectile p = gp.projectilePool.get();
            // Copy arrow properties
            p.name = projectile.name;
            p.speed = projectile.speed;
            p.maxLife = projectile.maxLife;
            p.attack = projectile.attack;
            p.solidArea.x = projectile.solidArea.x;
            p.solidArea.y = projectile.solidArea.y;
            p.solidArea.width = projectile.solidArea.width;
            p.solidArea.height = projectile.solidArea.height;
            // Copy sprites
            p.up1 = projectile.up1; p.up2 = projectile.up2;
            p.down1 = projectile.down1; p.down2 = projectile.down2;
            p.left1 = projectile.left1; p.left2 = projectile.left2;
            p.right1 = projectile.right1; p.right2 = projectile.right2;
            p.set(worldX, worldY, direction, true, this);
            gp.projectilesList.add(p);
            shootCooldown = SHOOT_COOLDOWN_MAX;
        }
    }

    private void facePlayer(int dx, int dy) {
        if (Math.abs(dx) > Math.abs(dy)) {
            direction = dx > 0 ? "left" : "right";
        } else {
            direction = dy > 0 ? "up" : "down";
        }
    }

    private void setFleeDirection(int dx, int dy) {
        if (Math.abs(dx) > Math.abs(dy)) {
            direction = dx > 0 ? "right" : "left";
        } else {
            direction = dy > 0 ? "down" : "up";
        }
    }

    private void setStrafeDirecion(int dx, int dy) {
        // Move perpendicular to player direction
        if (Math.abs(dx) > Math.abs(dy)) {
            direction = random.nextBoolean() ? "up" : "down";
        } else {
            direction = random.nextBoolean() ? "left" : "right";
        }
    }

    @Override
    public void damageReaction() {
        actionLockCounter = 0;
        if (life <= maxLife / 2) {
            fleeing = true;
            onPath = false;
            fleeCounter = 0;
            speed = defaultSpeed + 1;
        }
    }

    private void randomMovement() {
        actionLockCounter++;
        if (actionLockCounter >= 120) {
            direction = DIRECTIONS[random.nextInt(4)];
            actionLockCounter = 0;
        }
    }
}

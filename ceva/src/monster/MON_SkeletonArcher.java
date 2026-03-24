package monster;

import entity.Entity;
import entity.Projectile;
import java.util.Random;
import main.GamePanel;

/**
 * Ranged skeleton archer that keeps distance and fires arrows at the player.
 * Reuses the mummy sprite (tinted) until a dedicated sprite is available.
 */
public class MON_SkeletonArcher extends Entity {

    GamePanel gp;
    private static final Random random = new Random();
    private static final int[] DIRECTIONS = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};

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
        maxLife = 8;
        life = maxLife;
        attack = 3;
        defense = 1;
        exp = 7;

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
        int[] framesPerRow = {6, 6, 6, 6}; // down, left, right, up (matches DIR_DOWN=0,LEFT=1,RIGHT=2,UP=3)
        walkFrames = loadSheetVariable("/res/monster/Monster_walking-sheet", framesPerRow);
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
            // Copy sprites (share walkFrames array — it's read-only)
            p.walkFrames = projectile.walkFrames;
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
            direction = dx > 0 ? DIR_LEFT : DIR_RIGHT;
        } else {
            direction = dy > 0 ? DIR_UP : DIR_DOWN;
        }
    }

    private void setFleeDirection(int dx, int dy) {
        if (Math.abs(dx) > Math.abs(dy)) {
            direction = dx > 0 ? DIR_RIGHT : DIR_LEFT;
        } else {
            direction = dy > 0 ? DIR_DOWN : DIR_UP;
        }
    }

    private void setStrafeDirecion(int dx, int dy) {
        // Move perpendicular to player direction
        if (Math.abs(dx) > Math.abs(dy)) {
            direction = random.nextBoolean() ? DIR_UP : DIR_DOWN;
        } else {
            direction = random.nextBoolean() ? DIR_LEFT : DIR_RIGHT;
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

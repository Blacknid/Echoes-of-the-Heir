package data;

import java.util.Random;

import entity.Entity;
import entity.Projectile;
import main.GamePanel;

/**
 * A monster entity whose stats and AI behavior are configured from JSON data.
 * Supports melee_chase and ranged_archer AI profiles.
 */
public class DataDrivenMonster extends Entity {

    private static final Random random = new Random();
    private static final int[] DIRECTIONS = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};

    private final String aiBehavior;
    private final float fleeThreshold;
    private final int shootCooldownMax;
    private final int preferredDist;
    private final int fleeDist;
    private int shootCooldown = 0;

    /**
     * Construct from a pre-configured template entity.
     * Copies all stats/sprites from the template, then adds AI behavior.
     */
    public DataDrivenMonster(GamePanel gp, Entity template, String aiBehavior,
                             float fleeThreshold, int shootCooldownMax, int preferredDist, int fleeDist) {
        super(gp);
        this.aiBehavior = aiBehavior;
        this.fleeThreshold = fleeThreshold;
        this.shootCooldownMax = shootCooldownMax;
        this.preferredDist = preferredDist;
        this.fleeDist = fleeDist;

        // Copy all stats from template
        this.type = template.type;
        this.name = template.name;
        this.collision = template.collision;
        this.worldX = template.worldX;
        this.worldY = template.worldY;
        this.defaultSpeed = template.defaultSpeed;
        this.speed = template.speed;
        this.spriteScale = template.spriteScale;
        this.animSpeedMultiplier = template.animSpeedMultiplier;
        this.walkFrameCount = template.walkFrameCount;
        this.maxLife = template.maxLife;
        this.life = template.life;
        this.attack = template.attack;
        this.defense = template.defense;
        this.exp = template.exp;
        this.aggroRange = template.aggroRange;
        this.fleeDuration = template.fleeDuration;
        this.solidArea.x = template.solidArea.x;
        this.solidArea.y = template.solidArea.y;
        this.solidArea.width = template.solidArea.width;
        this.solidArea.height = template.solidArea.height;
        this.solidAreaDefaultX = template.solidAreaDefaultX;
        this.solidAreaDefaultY = template.solidAreaDefaultY;
        this.hurtPolygon = template.hurtPolygon;
        this.walkFrames = template.walkFrames;
        this.projectile = template.projectile;
        this.frontalArmor = template.frontalArmor;
        this.rootOnContactDuration = template.rootOnContactDuration;
        this.phasing = template.phasing;
        this.phasingCycleDuration = template.phasingCycleDuration;
    }

    @Override
    public void setAction() {
        switch (aiBehavior) {
            case "ranged_archer"    -> setActionRanged();
            case "stationary_trap" -> setActionStationaryTrap();
            case "pack_hunter"     -> setActionPackHunter();
            case "phasing_ranged"  -> setActionPhasingRanged();
            default                -> setActionMelee();  // melee_chase
        }
    }

    @Override
    public void damageReaction() {
        actionLockCounter = 0;
        switch (aiBehavior) {
            case "stationary_trap" -> { /* stationary — no flee reaction */ }
            case "ranged_archer", "phasing_ranged" -> {
                if (life <= maxLife * fleeThreshold) {
                    fleeing = true; onPath = false; fleeCounter = 0; speed = defaultSpeed + 1;
                }
            }
            default -> {
                if (!everAggroed) { everAggroed = true; onPath = true; alertTick = 60; }
                else onPath = true;
                if (life <= maxLife * fleeThreshold) {
                    fleeing = true; onPath = false; fleeCounter = 0; speed = defaultSpeed + 1;
                }
            }
        }
    }

    private void setActionMelee() {
        if (fleeing) {
            fleeCounter++;
            int dx = worldX - gp.player.worldX;
            int dy = worldY - gp.player.worldY;
            direction = (Math.abs(dx) > Math.abs(dy))
                ? (dx > 0 ? DIR_RIGHT : DIR_LEFT)
                : (dy > 0 ? DIR_DOWN : DIR_UP);
            if (fleeCounter > fleeDuration) {
                fleeing = false;
                fleeCounter = 0;
                speed = defaultSpeed;
            }
            return;
        }

        if (onPath) {
            int loseRange = gp.tileSize * 8;
            if (!isPlayerInRange(loseRange)) {
                onPath = false;
                everAggroed = false;
            } else {
                int goalCol = gp.player.getTileCol();
                int goalRow = gp.player.getTileRow();
                int closeDist = gp.tileSize * 2;
                int absDx = Math.abs(getCenterX() - gp.player.getCenterX());
                int absDy = Math.abs(getCenterY() - gp.player.getCenterY());
                if (absDx < closeDist && absDy < closeDist) {
                    if (!directChase(goalCol, goalRow)) giveUpIfUnreachable();
                } else {
                    searchPath(goalCol, goalRow);
                }
            }
        } else {
            if (isPlayerInRange(aggroRange)) {
                onPath = true;
                if (!everAggroed) { everAggroed = true; alertTick = 60; }
            } else {
                randomMovement();
            }
        }
    }

    private void setActionRanged() {
        if (shootCooldown > 0) shootCooldown--;

        int dx = worldX - gp.player.worldX;
        int dy = worldY - gp.player.worldY;
        double dist = Math.sqrt((double)dx * dx + (double)dy * dy) / gp.tileSize;

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

        int aggroTiles = aggroRange / gp.tileSize;
        if (dist < aggroTiles) {
            if (!everAggroed) { everAggroed = true; onPath = true; alertTick = 60; }
            if (dist < fleeDist) {
                speed = defaultSpeed + 1;
                setStrafeDirection(dx, dy);
            } else if (dist < preferredDist) {
                speed = 0;
                facePlayer(dx, dy);
                tryShoot();
            } else {
                speed = defaultSpeed;
                facePlayer(dx, dy);
                searchPath(gp.player.getTileCol(), gp.player.getTileRow());
            }
        } else {
            onPath = false;
            everAggroed = false;
            speed = defaultSpeed;
            randomMovement();
        }
    }

    private void tryShoot() {
        // Telegraph: flash red in the 20 frames leading up to a ranged shot
        if (shootCooldown <= 20 && shootCooldown > 0) {
            attackWindupFlash = 20 - shootCooldown;
        }
        if (shootCooldown <= 0 && projectile != null) {
            Projectile p = gp.projectilePool.get();
            p.name = projectile.name;
            p.speed = projectile.speed;
            p.maxLife = projectile.maxLife;
            p.attack = projectile.attack;
            p.solidArea.x = projectile.solidArea.x;
            p.solidArea.y = projectile.solidArea.y;
            p.solidArea.width = projectile.solidArea.width;
            p.solidArea.height = projectile.solidArea.height;
            p.walkFrames = projectile.walkFrames;
            p.up1 = projectile.up1; p.up2 = projectile.up2;
            p.down1 = projectile.down1; p.down2 = projectile.down2;
            p.left1 = projectile.left1; p.left2 = projectile.left2;
            p.right1 = projectile.right1; p.right2 = projectile.right2;
            p.set(worldX, worldY, direction, true, this);
            gp.projectilesList.add(p);
            shootCooldown = shootCooldownMax;
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

    private void setStrafeDirection(int dx, int dy) {
        if (Math.abs(dx) > Math.abs(dy)) {
            direction = random.nextBoolean() ? DIR_UP : DIR_DOWN;
        } else {
            direction = random.nextBoolean() ? DIR_LEFT : DIR_RIGHT;
        }
    }

    private void randomMovement() {
        actionLockCounter++;
        if (actionLockCounter >= 120) {
            direction = DIRECTIONS[random.nextInt(4)];
            actionLockCounter = 0;
        }
    }

    // Never moves. Faces the player. rootOnContactDuration handles the grab via Entity.checkCollision().
    private void setActionStationaryTrap() {
        speed  = 0;
        onPath = false;
        faceTowardPlayer();
    }

    // Chases the player when a pack-mate is nearby; flees when alone.
    private void setActionPackHunter() {
        if (fleeing) {
            fleeCounter++;
            int dx = worldX - gp.player.worldX;
            int dy = worldY - gp.player.worldY;
            setFleeDirection(dx, dy);
            if (fleeCounter > fleeDuration) { fleeing = false; fleeCounter = 0; speed = defaultSpeed; }
            return;
        }

        boolean packNearby = false;
        for (Entity m : gp.monster) {
            if (m == null || m == this || !m.alive) continue;
            if (m instanceof DataDrivenMonster ddm && "pack_hunter".equals(ddm.aiBehavior)) {
                double dist = Math.hypot(m.worldX - worldX, m.worldY - worldY);
                if (dist < gp.tileSize * 4) { packNearby = true; break; }
            }
        }

        if (packNearby) {
            setActionMelee();
        } else if (isPlayerInRange(aggroRange)) {
            // Alone — flee from player
            int dx = worldX - gp.player.worldX;
            int dy = worldY - gp.player.worldY;
            setFleeDirection(dx, dy);
            speed = defaultSpeed;
        } else {
            speed = defaultSpeed;
            randomMovement();
        }
    }

    // Behaves like ranged_archer but the phasing cycle (toggling invincibility) is driven by Entity.update().
    // The AI just needs to handle standard ranged combat — phasing is transparent to the AI.
    private void setActionPhasingRanged() {
        setActionRanged();
    }
}

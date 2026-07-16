package entity;

import main.GamePanel;

/**
 * The Phantom, a fast melee boss that chases the player down and strikes on contact.
 * Uses 64x64 sprites (native tile size), rendered at PHANTOM_SCALE.
 * At 50% health it enters phase 2 and becomes "Echo" (see the phase2* fields below).
 */
public class BOSS_Phantom extends Boss {

    private static final int SPRITE_SIZE = 64;
    private static final String SHEET_PATH = "/res/bosses/Phantom/Phantom_";
    private static final float PHANTOM_SCALE = 3f;
    // Final on-screen hitbox size (does not grow with PHANTOM_SCALE), anchored on the sprite as a
    // simple fraction (0..1) of its drawn width/height, the ghost floats in the upper-center of
    // its frame, so the hitbox sits there too instead of at its feet.
    private static final int PHANTOM_HITBOX_WIDTH = 24;
    private static final int PHANTOM_HITBOX_HEIGHT = 64;
    private static final float PHANTOM_HITBOX_ANCHOR_X = 0.5f;
    private static final float PHANTOM_HITBOX_ANCHOR_Y = 0.4f;

    // Attack cone (same shape as the player's melee cone), tune these to resize the attack hitbox.
    private static final double ATTACK_CONE_RADIUS_SCALE = 1.45; // x gp.tileSize, cone length
    private static final double ATTACK_CONE_HALF_ANGLE_DEG = 55; // half-spread in degrees (total spread = 2x this)

    public BOSS_Phantom(GamePanel gp) {
        super(gp);

        name = "Phantom";
        collision = true;
        setScale(PHANTOM_SCALE, PHANTOM_HITBOX_WIDTH, PHANTOM_HITBOX_HEIGHT, PHANTOM_HITBOX_ANCHOR_X, PHANTOM_HITBOX_ANCHOR_Y);
        requiresIntroForHpBar = true; // has a BossIntroTrigger in Dungeon.tmx, HP bar waits for it

        maxLife = 120;
        life = maxLife;
        attack = 1;
        defense = 2;
        exp = 40;
        defaultSpeed = 2;
        speed = defaultSpeed;
        walkFrameCount = 6;
        animationFrameInterval = 6;
        aggroRange = 9 * gp.tileSize;

        attackRange = gp.tileSize + gp.tileSize / 2;
        attackHitFrame = 5;
        attackFrameSpeed = 5;
        attackCooldown = 110;
        keepDistance = attackRange + gp.tileSize * 2; // hover here between attacks, then close in to strike

        attackConeRadiusScale = ATTACK_CONE_RADIUS_SCALE;
        attackConeHalfAngle = Math.toRadians(ATTACK_CONE_HALF_ANGLE_DEG);

        // Phase 2: at 50% health, the Phantom becomes "Echo", heals back to full, attacks faster,
        // and gains two new tricks: a chance to dash away from an incoming hit instead of taking it,
        // and periodic teleports (using the death animation played forward then backward) to close
        // distance or reposition unpredictably.
        phase2HealthFraction = 0.5f;
        phase2Name = "Echo";
        phase2FullHeal = true;
        phase2AttackCooldownMultiplier = 0.5f; // 30% faster attacks once enraged

        summonMonsterId = "iceblot";
        summonIntervalMinTicks = 5 * 60;
        summonIntervalMaxTicks = 7 * 60;
        summonMaxAlive = 3;

        evadeDashChance = 0.25f;
        evadeDashDurationTicks = 14;
        evadeDashDistanceTiles = 2;

        teleportIntervalMinTicks = 8 * 60;
        teleportIntervalMaxTicks = 12 * 60;
        teleportMinDistanceTiles = 3;
        teleportMaxDistanceTiles = 5;

        // Phantom's sheets are laid out row-by-row as: DOWN, UP, RIGHT, LEFT.
        walkFrames  = loadDirectionalSheet(SHEET_PATH + "walk",    SPRITE_SIZE, DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT);
        idleFrames  = loadDirectionalSheet(SHEET_PATH + "idle",   SPRITE_SIZE, DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT);
        attackFrames = loadDirectionalSheet(SHEET_PATH + "attack", SPRITE_SIZE, DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT);
        hurtFrames  = loadDirectionalSheet(SHEET_PATH + "hurt",   SPRITE_SIZE, DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT);
        deathFrames = loadDirectionalSheet(SHEET_PATH + "death",  SPRITE_SIZE, DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT);
        dashFrames  = loadDirectionalSheet(SHEET_PATH + "dash",   SPRITE_SIZE, DIR_DOWN, DIR_UP, DIR_LEFT, DIR_RIGHT);
    }

    @Override
    public void setAction() {
        chaseAndAttack(attackRange);
    }

    @Override
    protected void onDefeated() {
        gp.boss2Defeated = true;

        if (gp.memoryJournal != null) {
            data.MemoryJournal.MemoryFragment frag = gp.memoryJournal.collect("echo");
            if (frag != null && gp.memoryFlashback != null) {
                gp.memoryFlashback.trigger(frag);
            }
        }

        // Advances explore_the_cave's "defeat the phantom" step, same pattern
        // BOSS_WitheredTree uses for defeat_hollow_king, nothing generically watches quest
        // "defeat"/"enemy" step data, so each boss calls progress() on its own quest directly.
        if (gp.questManager != null) {
            gp.questManager.progress("explore_the_cave", 1);
        }
    }
}

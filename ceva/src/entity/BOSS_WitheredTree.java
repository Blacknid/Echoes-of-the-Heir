package entity;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.Random;

import audio.SFX;
import main.GamePanel;

/**
 * The Withered Tree — a 3-phase Ent boss with melee, stomp, and root attacks.
 * Uses 128×128 sprites rendered at 6× tileSize.
 * Phase 1 = Ent1 (Guardian), Phase 2 = Ent2 (Wrath), Phase 3 = Ent3 (Blight).
 *
 * Attack repertoire:
 *   - Melee Swing: close-range branch strike
 *   - Ground Stomp: AOE shockwave with expanding ring (all phases)
 *   - Root Barrage: roots erupt toward the player (Phase 2+)
 *   - Whirlwind Fury: rapid spinning multi-hit combo (Phase 3 only)
 */
public class BOSS_WitheredTree extends Entity {

    private static final Font BOSS_NAME_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font BOSS_PHASE_FONT = new Font("SansSerif", Font.ITALIC, 12);
    private static final BasicStroke BOSS_BAR_STROKE = new BasicStroke(2f);
    private static final Color BOSS_BAR_BORDER_COLOR = new Color(160, 140, 100);
    private static final Color BOSS_NAME_SHADOW = new Color(0, 0, 0, 180);

    // Phase thresholds (fraction of maxLife remaining)
    private static final float PHASE_2_THRESHOLD = 0.66f;
    private static final float PHASE_3_THRESHOLD = 0.33f;

    // Sprite storage per phase: [phase][direction][frame]
    private final BufferedImage[][][] phaseWalkFrames  = new BufferedImage[3][][];
    private final BufferedImage[][][] phaseIdleFrames  = new BufferedImage[3][][];
    private final BufferedImage[][][] phaseAttackFrames = new BufferedImage[3][][];
    private final BufferedImage[][][] phaseHurtFrames  = new BufferedImage[3][][];
    private final BufferedImage[][][] phaseDeathFrames = new BufferedImage[3][][];
    private final BufferedImage[][][] phaseSwingFrames = new BufferedImage[3][][];

    private int currentPhase = 0;

    private static final int ATK_MELEE       = 0;
    private static final int ATK_STOMP       = 1;
    private static final int ATK_ROOT        = 2;
    private static final int ATK_WHIRLWIND   = 3;
    private static final int ATK_LEAF_BOLT   = 4;
    private static final int ATK_TRIPLE_BOLT = 5;
    private static final int ATK_THORN_RING  = 6;

    private BufferedImage[][] leafBoltFrames; // [direction][frame]
    private static final int LEAF_BOLT_SPEED = 4;
    private static final int LEAF_BOLT_LIFE  = 120; // frames before disappearing
    private static final int LEAF_BOLT_DMG   = 3;
    private static final int LEAF_BOLT_RANGE = 260; // can fire from this far
    private int leafBoltCooldown = 0;
    private static final int LEAF_BOLT_COOLDOWN = 40;

    private boolean bossAttacking = false;
    private int attackFrameCounter = 0;
    private int attackFrameIndex = 0;
    private int attackCooldown = 0;
    private static final int ATTACK_HIT_FRAME = 3;
    private boolean attackHitApplied = false;
    private int consecutiveMelees = 0; // track melee count for combo variety

    private boolean stompActive = false;
    private int stompTimer = 0;
    private float stompRingRadius = 0;
    private float stompRingMaxRadius;
    private static final int STOMP_TELEGRAPH_FRAMES = 30;
    private static final int STOMP_EXPAND_FRAMES = 18;
    private static final float STOMP_RING_SPEED = 6f;
    private boolean stompDamageApplied = false;
    private int stompTelegraphTimer = 0;

    private boolean rootBarrageActive = false;
    private int rootBarrageTimer = 0;
    private int rootBarrageWave = 0;
    private static final int ROOT_WAVE_COUNT = 5;
    private static final int ROOT_WAVE_INTERVAL = 12;
    private int[] rootTargetX = new int[ROOT_WAVE_COUNT];
    private int[] rootTargetY = new int[ROOT_WAVE_COUNT];
    private int[] rootEruptTimer = new int[ROOT_WAVE_COUNT];
    private boolean[] rootErupted = new boolean[ROOT_WAVE_COUNT];

    private boolean whirlwindActive = false;
    private int whirlwindTimer = 0;

    private static final int WHIRLWIND_DURATION = 90;
    private static final int WHIRLWIND_HIT_INTERVAL = 15;
    private float whirlwindAngle = 0;

    private boolean thornRingActive = false;
    private int thornRingTimer = 0;
    private static final int THORN_RING_WINDUP    = 24; // frames of telegraph before firing
    private static final int THORN_RING_DURATION  = 36; // total frames of the attack
    private static final int THORN_RING_DMG        = 4;
    private static final int THORN_RING_SPEED      = 5;
    private static final int THORN_RING_BOLT_LIFE  = 100;
    private boolean thornRingFired = false;

    private boolean introPlayed = false;
    private int introTimer = 0;
    private static final int INTRO_DURATION = 80;

    private boolean enraged = false;
    private static final float ENRAGE_THRESHOLD = 0.15f;

    private boolean isHurt = false;
    private int hurtTimer = 0;
    private static final int HURT_DURATION = 20;

    private int idleFrameCounter = 0;
    private int idleFrameIndex = 0;
    private boolean isMoving = false;

    // Boss-specific sprite frame size (raw pixel size on the spritesheet)
    private static final int SPRITE_SIZE = 128;
    // Boss render scale: 6× the player tile size
    private static final int BOSS_SCALE = 6;

    private static final int[]   ATTACK_COOLDOWNS = {90, 60, 28};
    private static final int[]   PHASE_ATTACK     = {6, 9, 15};
    private static final int[]   PHASE_SPEED      = {1, 2, 3};
    private static final int[]   PHASE_DEFENSE    = {2, 3, 4};
    private static final int     ATTACK_RANGE     = 96;
    private static final int     STOMP_RANGE      = 240; // wider than melee

    // Hit flash buffer (reused to avoid allocation per frame)
    private BufferedImage bossFlashBuffer;
    private int bossFlashW, bossFlashH;
    private Graphics2D bossFlashG2; // OPTIMIZATION: cached Graphics2D for flash overlay

    private static final Random rng = new Random();

    private boolean phaseTransitioning = false;
    private int phaseTransitionTimer = 0;
    private static final int PHASE_TRANSITION_DURATION = 50;
    private int pendingPhase = -1;

    private float displayedHpRatio = 1f;
    private float targetHpRatio = 1f;
    private int hpFlashTimer = 0;

    private static final String[] PHASE_NAMES = {
        "The Guardian",
        "Wrath Awakened",
        "The Blight"
    };
    private int phaseNameAlpha = 0;
    private int phaseNameTimer = 0;

    private static final Color HP_BG        = new Color(35, 35, 35);
    private static final Color PHASE_GREEN  = new Color(50, 180, 50);
    private static final Color PHASE_YELLOW = new Color(220, 180, 30);
    private static final Color PHASE_RED    = new Color(220, 50, 30);
    private static final Color MARKER_COLOR = new Color(200, 200, 200, 150);
    private static final Color LEAF_PARTICLE = new Color(90, 130, 50);
    private static final Color PHASE2_SPARK = new Color(120, 180, 50);
    private static final Color PHASE3_SPARK = new Color(180, 40, 40);
    private static final Color STOMP_RING   = new Color(160, 120, 60, 180);
    private static final Color STOMP_WARN   = new Color(255, 200, 80, 100);
    private static final Color ROOT_COLOR   = new Color(80, 50, 20);
    private static final Color ROOT_TIP     = new Color(50, 120, 30);
    private static final Color WHIRL_TRAIL  = new Color(100, 160, 60, 150);
    private static final Color THORN_COLOR  = new Color(180, 220, 60, 200);
    private static final Color HP_DAMAGE_FLASH = new Color(255, 255, 255, 120);

    public BOSS_WitheredTree(GamePanel gp) {
        super(gp);

        type = TYPE_MONSTER;
        name = "The Withered Tree";
        collision = true;

        maxLife = 180;
        life = maxLife;
        attack = 6;
        defense = 2;
        exp = 4;
        defaultSpeed = 1;
        speed = defaultSpeed;
        walkFrameCount = 6;
        animationFrameInterval = 8;
        aggroRange = 8 * gp.tileSize;
        invincibleDuration = 15;

        stompRingMaxRadius = gp.tileSize * 4.5f;

        // Solid area at the tree's base (near worldX/worldY anchor).
        // The visual sprite is drawn offset upward, but the collision
        // footprint represents the trunk base where the tree stands.
        solidArea.x = 0;
        solidArea.y = 0;
        solidArea.width  = gp.tileSize;         // 64 at 64px tile
        solidArea.height = gp.tileSize;         // 64 at 64px tile
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        attackArea.width  = gp.tileSize * 5 / 4; // 80 at 64px tile
        attackArea.height = gp.tileSize * 5 / 4; // 80 at 64px tile

        loadAllPhaseSprites();
        applyPhase(0);
    }


    private void loadAllPhaseSprites() {
        String[] prefixes = {"Ent1", "Ent2", "Ent3"};
        for (int p = 0; p < 3; p++) {
//            String pre  = prefixes[p];    nu avem neaparat nevoie de asa ceva, dar nu sterg pentru a intelege mai bine
            String base = "/res/bosses/" + prefixes[p];
            // Sheet rows: DOWN(0), RIGHT(1), LEFT(2), UP(3)
            // Game dirs:  DOWN(0), LEFT(1),  RIGHT(2), UP(3)
            // Swap rows 1 & 2 so sprites match direction constants
            phaseWalkFrames[p]   = swapLeftRight(loadSpriteMatrix(base + "/With_shadow/" + prefixes[p] + "_Walk_with_shadow",   SPRITE_SIZE, SPRITE_SIZE));
            phaseIdleFrames[p]   = swapLeftRight(loadSpriteMatrix(base + "/With_shadow/" + prefixes[p] + "_Idle_with_shadow",   SPRITE_SIZE, SPRITE_SIZE));
            phaseAttackFrames[p] = swapLeftRight(loadSpriteMatrix(base + "/With_shadow/" + prefixes[p] + "_Attack_with_shadow", SPRITE_SIZE, SPRITE_SIZE));
            phaseHurtFrames[p]   = swapLeftRight(loadSpriteMatrix(base + "/With_shadow/" + prefixes[p] + "_Hurt_with_shadow",   SPRITE_SIZE, SPRITE_SIZE));
            phaseDeathFrames[p]  = swapLeftRight(loadSpriteMatrix(base + "/With_shadow/" + prefixes[p] + "_Death_with_shadow",  SPRITE_SIZE, SPRITE_SIZE));
           // phaseSwingFrames[p]  = swapLeftRight(loadSpriteMatrix(base + "/Parts/" + prefixes[p] + "_Attack_swing", SPRITE_SIZE, SPRITE_SIZE));
        }

        // Load leaf projectile frames from Ent2's leaves spritesheet
        BufferedImage[][] leafSheet = swapLeftRight(loadSpriteMatrix("/res/bosses/Ent2/Parts/Ent2_Attack_leaves", SPRITE_SIZE, SPRITE_SIZE));
        // leafSheet is [4 directions][7 frames]; pick a single visible frame per direction for the projectile
        leafBoltFrames = new BufferedImage[4][2];
        for (int d = 0; d < 4 && d < leafSheet.length; d++) {
            leafBoltFrames[d][0] = leafSheet[d][3]; // mid-animation frame
            leafBoltFrames[d][1] = leafSheet[d][4]; // alternate for animation
        }
    }

    /** Swap rows 1 (LEFT) and 2 (RIGHT) to match game direction constants. */
    private static BufferedImage[][] swapLeftRight(BufferedImage[][] matrix) {
        if (matrix.length >= 3) {
            BufferedImage[] tmp = matrix[1];
            matrix[1] = matrix[2];
            matrix[2] = tmp;
        }
        return matrix;
    }

    private void applyPhase(int phase) {
        currentPhase = phase;
        walkFrames   = phaseWalkFrames[phase];
        idleFrames   = phaseIdleFrames[phase];
        attackFrames = phaseAttackFrames[phase];
        speed        = PHASE_SPEED[phase];
        defaultSpeed = PHASE_SPEED[phase];
        defense      = PHASE_DEFENSE[phase];
        attack       = PHASE_ATTACK[phase];
    }


    private void checkPhaseTransition() {
        float hpPct = (float) life / maxLife;
        int target = currentPhase;
        if (hpPct <= PHASE_3_THRESHOLD && currentPhase < 2) target = 2;
        else if (hpPct <= PHASE_2_THRESHOLD && currentPhase < 1) target = 1;
        if (target != currentPhase) beginPhaseTransition(target);
    }

    private void beginPhaseTransition(int newPhase) {
        phaseTransitioning = true;
        phaseTransitionTimer = PHASE_TRANSITION_DURATION;
        pendingPhase = newPhase;
        bossAttacking = false;
        stompActive = false;
        rootBarrageActive = false;
        whirlwindActive = false;
        thornRingActive = false;
        attackFrameCounter = 0;
        speed = 0;
        invincible = true; // brief invulnerability during transition
        invincibleCounter = 0;

        // Dramatic screen shake
        gp.screenShake.shakeHeavy();
        gp.triggerHitstop(8);
        gp.playSE(SFX.MONSTER_HIT);

        // Burst of particles in expanding ring
        int particleCount = (newPhase == 2) ? 32 : 24;
        Color c = (newPhase == 2) ? PHASE3_SPARK : PHASE2_SPARK;
        for (int i = 0; i < particleCount; i++) {
            Particle p = gp.particlePool.get();
            float angle = (float) (i * Math.PI * 2 / particleCount);
            int dx = (int) (Math.cos(angle) * 4);
            int dy = (int) (Math.sin(angle) * 4);
            p.setWithPosition(this, this, c, 8, 4, 30, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(p);
        }

        // Show phase name
        phaseNameTimer = 80;
        phaseNameAlpha = 255;
    }

    private void updatePhaseTransition() {
        phaseTransitionTimer--;

        // At midpoint, actually switch phase
        if (phaseTransitionTimer == PHASE_TRANSITION_DURATION / 2) {
            transitionToPhase(pendingPhase);

            // Second wave of particles
            Color c = (pendingPhase == 2) ? PHASE3_SPARK : PHASE2_SPARK;
            for (int i = 0; i < 20; i++) {
                Particle p = gp.particlePool.get();
                int dx = rng.nextInt(7) - 3;
                int dy = rng.nextInt(7) - 3;
                p.setWithPosition(this, this, c, 6, 3, 25, dx, dy, Particle.STYLE_SPARK);
                gp.particleList.add(p);
            }
            gp.screenShake.shakeMedium();
        }

        if (phaseTransitionTimer <= 0) {
            phaseTransitioning = false;
            invincible = false;
            invincibleCounter = 0;
            invincibleDuration = 15;
            attackCooldown = 30; // brief pause before resuming
        }
    }

    private void transitionToPhase(int newPhase) {
        applyPhase(newPhase);
        crowdControlTimer = 30;
        bossAttacking = false;
        attackFrameCounter = 0;
    }


    private int chooseAttack(double dist) {
        // Phase 3: can use all attacks including whirlwind + leaf bolt + thorn ring
        if (currentPhase == 2) {
            // Enraged: frantic, fast attacks only
            if (enraged) {
                if (dist <= ATTACK_RANGE) {
                    return (consecutiveMelees < 2) ? ATK_MELEE : ATK_WHIRLWIND;
                }
                int enrageRoll = rng.nextInt(3);
                if (enrageRoll == 0) return ATK_THORN_RING;
                return rng.nextBoolean() ? ATK_TRIPLE_BOLT : ATK_ROOT;
            }
            int roll = rng.nextInt(100);
            if (dist <= ATTACK_RANGE && consecutiveMelees >= 2) {
                return rng.nextBoolean() ? ATK_STOMP : ATK_WHIRLWIND;
            }
            if (dist <= ATTACK_RANGE) {
                if (roll < 25) return ATK_MELEE;
                if (roll < 38) return ATK_STOMP;
                if (roll < 52) return ATK_WHIRLWIND;
                if (roll < 64) return ATK_TRIPLE_BOLT;
                if (roll < 77) return ATK_LEAF_BOLT;
                if (roll < 89) return ATK_ROOT;
                return ATK_THORN_RING;
            }
            if (dist <= STOMP_RANGE) {
                if (roll < 15) return ATK_STOMP;
                if (roll < 35) return ATK_ROOT;
                if (roll < 55) return ATK_TRIPLE_BOLT;
                if (roll < 75) return ATK_WHIRLWIND;
                return ATK_THORN_RING;
            }
            if (dist <= LEAF_BOLT_RANGE) return rng.nextBoolean() ? ATK_TRIPLE_BOLT : ATK_THORN_RING;
            return ATK_ROOT;
        }

        // Phase 2: melee, stomp, root, leaf bolt, triple bolt
        if (currentPhase == 1) {
            int roll = rng.nextInt(100);
            if (dist <= ATTACK_RANGE) {
                if (consecutiveMelees >= 3) return ATK_STOMP;
                if (roll < 35) return ATK_MELEE;
                if (roll < 55) return ATK_STOMP;
                if (roll < 75) return ATK_LEAF_BOLT;
                return ATK_ROOT;
            }
            if (dist <= STOMP_RANGE) {
                if (roll < 35) return ATK_STOMP;
                if (roll < 60) return ATK_LEAF_BOLT;
                if (roll < 80) return ATK_TRIPLE_BOLT;
                return ATK_ROOT;
            }
            if (dist <= LEAF_BOLT_RANGE) return rng.nextBoolean() ? ATK_LEAF_BOLT : ATK_TRIPLE_BOLT;
            return ATK_ROOT;
        }

        // Phase 1: melee, stomp, and leaf bolt at range
        if (dist <= ATTACK_RANGE) {
            if (consecutiveMelees >= 3) return ATK_STOMP;
            return (rng.nextInt(100) < 70) ? ATK_MELEE : ATK_STOMP;
        }
        if (dist <= STOMP_RANGE) {
            return (rng.nextInt(100) < 60) ? ATK_STOMP : ATK_LEAF_BOLT;
        }
        if (dist <= LEAF_BOLT_RANGE) return ATK_LEAF_BOLT;
        return ATK_MELEE; // will chase toward player
    }

    @Override
    public void setAction() {
        if (bossAttacking || stompActive || rootBarrageActive || whirlwindActive || thornRingActive) return;

        // Boss intro: first time player enters range, dramatic pause
        if (!introPlayed && isPlayerInRange(aggroRange)) {
            introPlayed = true;
            introTimer = INTRO_DURATION;
            hpBarOn = true;
            phaseNameTimer = 80;
            phaseNameAlpha = 255;
            gp.screenShake.shakeLight();
            gp.playSE(SFX.MONSTER_HIT);
            // Leaf burst to announce presence
            for (int i = 0; i < 12; i++) {
                Particle p = gp.particlePool.get();
                int dx = rng.nextInt(5) - 2;
                int dy = -(rng.nextInt(3) + 1);
                p.setWithPosition(this, this, LEAF_PARTICLE, 5, 2, 25, dx, dy, Particle.STYLE_DEFAULT);
                gp.particleList.add(p);
            }
            return;
        }

        if (isPlayerInRange(aggroRange)) {
            // Use visual center for distance so attacks feel right relative to the sprite
            int dx = Math.abs(getCenterX() - gp.player.getCenterX());
            int dy = Math.abs(getVisualCenterY() - gp.player.getCenterY());
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

            if (attackCooldown <= 0) {
                int atkType = chooseAttack(dist);
                if (atkType == ATK_MELEE && dist <= ATTACK_RANGE) {
                    consecutiveMelees++;
                    startAttack();
                } else if (atkType == ATK_STOMP && dist <= STOMP_RANGE) {
                    consecutiveMelees = 0;
                    startStomp();
                } else if (atkType == ATK_ROOT) {
                    consecutiveMelees = 0;
                    startRootBarrage();
                } else if (atkType == ATK_WHIRLWIND && dist <= STOMP_RANGE) {
                    consecutiveMelees = 0;
                    startWhirlwind();
                } else if (atkType == ATK_THORN_RING) {
                    consecutiveMelees = 0;
                    startThornRing();
                } else if (atkType == ATK_LEAF_BOLT && leafBoltCooldown <= 0) {
                    consecutiveMelees = 0;
                    fireLeafBolt();
                } else if (atkType == ATK_TRIPLE_BOLT && leafBoltCooldown <= 0) {
                    consecutiveMelees = 0;
                    fireTripleBolt();
                } else {
                    // Chase toward player
                    chasePlayer();
                }
            } else {
                chasePlayer();
            }
        } else {
            onPath = false;
            isMoving = false;
            actionLockCounter++;
            if (actionLockCounter >= 120) {
                direction = rng.nextInt(4);
                actionLockCounter = 0;
            }
        }
    }

    private void chasePlayer() {
        onPath = true;
        isMoving = true;
        int goalCol = gp.player.getTileCol();
        int goalRow = gp.player.getTileRow();
        searchPath(goalCol, goalRow);
    }

    private void startAttack() {
        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;
        speed = 0;
        isMoving = false;
        faceBossTowardPlayer();
    }


    @Override
    public void update() {
        // Phase transition drama
        if (phaseTransitioning) {
            updatePhaseTransition();
            tickInvincibleAndFlash();
            return;
        }

        // Boss intro freeze
        if (introTimer > 0) {
            introTimer--;
            tickInvincibleAndFlash();
            return;
        }

        // Crowd control
        if (crowdControlTimer > 0) {
            crowdControlTimer--;
            tickInvincibleAndFlash();
            return;
        }
        // Knockback
        if (knockBack) {
            worldX += knockBackVectorX;
            worldY += knockBackVectorY;
            knockBackRemaining -= Math.hypot(knockBackVectorX, knockBackVectorY);
            if (knockBackRemaining <= 0) {
                knockBack = false;
                knockBackVectorX = 0;
                knockBackVectorY = 0;
                knockBackRemaining = 0;
                knockBackPower = 0;
                onPath = false;
            }
            return;
        }

        if (attackCooldown > 0) attackCooldown--;
        if (leafBoltCooldown > 0) leafBoltCooldown--;
        if (isHurt) { hurtTimer--; if (hurtTimer <= 0) isHurt = false; }

        // Smooth HP bar
        targetHpRatio = Math.max(0f, (float) life / maxLife);
        if (displayedHpRatio > targetHpRatio) {
            displayedHpRatio -= 0.005f;
            if (displayedHpRatio < targetHpRatio) displayedHpRatio = targetHpRatio;
        }
        if (hpFlashTimer > 0) hpFlashTimer--;

        // Phase name fade
        if (phaseNameTimer > 0) {
            phaseNameTimer--;
            if (phaseNameTimer < 20) {
                phaseNameAlpha = Math.max(0, (int)(255 * (phaseNameTimer / 20f)));
            }
        }

        checkPhaseTransition();

        // Enrage at ≤15% HP
        if (!enraged && !dying && (float) life / maxLife <= ENRAGE_THRESHOLD) {
            enraged = true;
            speed = PHASE_SPEED[currentPhase] + 2;
            defaultSpeed = speed;
            invincibleDuration = 8;
            gp.screenShake.shakeHeavy();
            gp.playSE(SFX.MONSTER_HIT);
            for (int i = 0; i < 30; i++) {
                Particle ep = gp.particlePool.get();
                float angle = (float)(i * Math.PI * 2 / 30);
                int edx = (int)(Math.cos(angle) * 5);
                int edy = (int)(Math.sin(angle) * 5);
                ep.setWithPosition(this, this, PHASE3_SPARK, 7, 4, 35, edx, edy, Particle.STYLE_SPARK);
                gp.particleList.add(ep);
            }
            gp.ui.addMessage("The Withered Tree ENRAGES!", new Color(220, 50, 30));
            phaseNameTimer = 100;
            phaseNameAlpha = 255;
        }

        // Phase 3: ambient rage particles
        if (currentPhase == 2 && !dying) {
            if (rng.nextInt(6) == 0) {
                Particle p = gp.particlePool.get();
                int dx = rng.nextInt(3) - 1;
                int dy = -(rng.nextInt(2) + 1);
                p.setWithPosition(this, this, PHASE3_SPARK, 4, 2, 18, dx, dy, Particle.STYLE_SPARK);
                gp.particleList.add(p);
            }
        }

        // Update active special attacks
        if (stompActive) {
            updateStomp();
            tickInvincibleAndFlash();
            return;
        }
        if (rootBarrageActive) {
            updateRootBarrage();
            tickInvincibleAndFlash();
            return;
        }
        if (whirlwindActive) {
            updateWhirlwind();
            tickInvincibleAndFlash();
            return;
        }
        if (thornRingActive) {
            updateThornRing();
            tickInvincibleAndFlash();
            return;
        }

        if (bossAttacking) {
            updateAttack();
        } else {
            int prevX = worldX, prevY = worldY;
            setAction();
            checkCollision();
            if (!collisionOn && !onPath) {
                switch (direction) {
                    case DIR_UP    -> worldY -= speed;
                    case DIR_DOWN  -> worldY += speed;
                    case DIR_LEFT  -> worldX -= speed;
                    case DIR_RIGHT -> worldX += speed;
                }
            }
            isMoving = (worldX != prevX || worldY != prevY);

            if (isMoving) {
                spriteCounter++;
                if (spriteCounter > animationFrameInterval) {
                    spriteNum++;
                    if (spriteNum > walkFrameCount) spriteNum = 1;
                    spriteCounter = 0;
                }
                // Walking leaf particles
                if (rng.nextInt(8) == 0) {
                    Particle p = gp.particlePool.get();
                    p.setWithPosition(this, this, LEAF_PARTICLE, 3, 1, 15,
                            rng.nextInt(3) - 1, -1, Particle.STYLE_DEFAULT);
                    gp.particleList.add(p);
                }
            } else {
                idleFrameCounter++;
                if (idleFrameCounter > 12) {
                    idleFrameIndex++;
                    if (idleFrames != null && direction >= 0 && direction < idleFrames.length
                            && idleFrames[direction] != null
                            && idleFrameIndex >= idleFrames[direction].length) {
                        idleFrameIndex = 0;
                    }
                    idleFrameCounter = 0;
                }
            }
        }

        tickInvincibleAndFlash();
    }

    private void tickInvincibleAndFlash() {
        if (invincible) {
            invincibleCounter++;
            if (invincibleCounter > invincibleDuration) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
        if (hitFlashCounter > 0) hitFlashCounter--;
    }


    private void updateAttack() {
        attackFrameCounter++;
        int framesPerSprite = 6;
        int newIndex = attackFrameCounter / framesPerSprite;
        int maxFrames = safeLen(phaseAttackFrames[currentPhase], direction, 7);

        if (newIndex >= maxFrames) {
            bossAttacking = false;
            speed = PHASE_SPEED[currentPhase];
            attackCooldown = ATTACK_COOLDOWNS[currentPhase];
            return;
        }
        attackFrameIndex = newIndex;

        if (attackFrameIndex == ATTACK_HIT_FRAME && !attackHitApplied) {
            attackHitApplied = true;
            spawnSwingParticle();
            performBossAttack();
        }
    }

    private void spawnSwingParticle() {
        BossSwingEffect swing = new BossSwingEffect(
                gp, this, phaseSwingFrames[currentPhase], direction, attackFrameIndex);
        gp.particleList.add(swing);
    }

    private void performBossAttack() {
        int dx = Math.abs(getCenterX() - gp.player.getCenterX());
        int dy = Math.abs(getVisualCenterY() - gp.player.getCenterY());
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

        if (dist <= ATTACK_RANGE && !gp.player.invincible) {
            int damage = attack - gp.player.defense;
            if (damage < 1) damage = 1;
            gp.player.life -= damage;
            gp.player.invincible = true;
            gp.playSE(SFX.PLAYER_HIT);
            gp.screenShake.shakeMedium();
            gp.triggerHitstop(3);
            hpFlashTimer = 8;
        }
    }


    private void startStomp() {
        faceBossTowardPlayer();
        stompActive = true;
        stompTimer = 0;
        stompTelegraphTimer = STOMP_TELEGRAPH_FRAMES;
        stompDamageApplied = false;
        stompRingRadius = 0;
        speed = 0;
        isMoving = false;
        bossAttacking = true; // use attack animation
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;
    }

    private void updateStomp() {
        if (stompTelegraphTimer > 0) {
            // Telegraph: boss raises up, warning ring pulses
            stompTelegraphTimer--;
            // Slight screen tremble during windup
            if (stompTelegraphTimer % 6 == 0) {
                gp.screenShake.shake(1.5f, 4);
            }
            // Update attack animation during telegraph
            attackFrameCounter++;
            attackFrameIndex = Math.min(attackFrameCounter / 8, ATTACK_HIT_FRAME - 1);
            return;
        }

        stompTimer++;

        // Impact frame
        if (stompTimer == 1) {
            gp.screenShake.shakeHeavy();
            gp.triggerHitstop(5);
            gp.playSE(SFX.MONSTER_HIT);
            attackFrameIndex = ATTACK_HIT_FRAME;
            spawnSwingParticle();

            // Ground zero: immediate heavy damage to player standing at boss feet
            int gzDx = Math.abs(getCenterX() - gp.player.getCenterX());
            int gzDy = Math.abs(getCenterY() - gp.player.getCenterY());
            if (gzDx < gp.tileSize && gzDy < gp.tileSize && !gp.player.invincible) {
                int damage = attack + 4 - gp.player.defense;
                if (damage < 1) damage = 1;
                gp.player.life -= damage;
                gp.player.invincible = true;
                gp.playSE(SFX.PLAYER_HIT);
                stompDamageApplied = true;
            }

            // Ground debris particles
            for (int i = 0; i < 20; i++) {
                Particle p = gp.particlePool.get();
                float angle = (float)(rng.nextDouble() * Math.PI * 2);
                int spd = rng.nextInt(3) + 2;
                int dx = (int)(Math.cos(angle) * spd);
                int dy = (int)(Math.sin(angle) * spd);
                Color debrisColor = rng.nextBoolean() ? ROOT_COLOR : LEAF_PARTICLE;
                p.setWithPosition(this, this, debrisColor, rng.nextInt(4) + 3, 3, 22, dx, dy, Particle.STYLE_HIT);
                gp.particleList.add(p);
            }
        }

        // Expanding shockwave ring
        if (stompTimer <= STOMP_EXPAND_FRAMES) {
            stompRingRadius += STOMP_RING_SPEED;

            // Check damage when ring passes player
            if (!stompDamageApplied) {
                int dx = Math.abs(getCenterX() - gp.player.getCenterX());
                int dy = Math.abs(getCenterY() - gp.player.getCenterY());
                double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

                if (dist <= stompRingRadius + gp.tileSize / 2.0 && dist >= stompRingRadius - gp.tileSize
                        && !gp.player.invincible) {
                    stompDamageApplied = true;
                    int damage = attack + 2 - gp.player.defense;
                    if (damage < 1) damage = 1;
                    gp.player.life -= damage;
                    gp.player.invincible = true;
                    gp.playSE(SFX.PLAYER_HIT);
                    gp.screenShake.shakeMedium();

                    // Knockback player away from boss
                    int kbDx = gp.player.getCenterX() - getCenterX();
                    int kbDy = gp.player.getCenterY() - getCenterY();
                    double kbDist = Math.max(1, Math.hypot(kbDx, kbDy));
                    gp.player.knockBack = true;
                    gp.player.knockBackVectorX = (int)(kbDx / kbDist * 6);
                    gp.player.knockBackVectorY = (int)(kbDy / kbDist * 6);
                    gp.player.knockBackRemaining = 30;
                }
            }
        }

        if (stompTimer >= STOMP_EXPAND_FRAMES + 10) {
            stompActive = false;
            bossAttacking = false;
            speed = PHASE_SPEED[currentPhase];
            attackCooldown = ATTACK_COOLDOWNS[currentPhase] + 20;
        }
    }


    private void startRootBarrage() {
        faceBossTowardPlayer();
        rootBarrageActive = true;
        rootBarrageTimer = 0;
        rootBarrageWave = 0;
        speed = 0;
        isMoving = false;
        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;

        // Pre-calculate root target positions leading toward the player
        int bx = getCenterX();
        int by = getCenterY();
        int px = gp.player.getCenterX();
        int py = gp.player.getCenterY();
        for (int i = 0; i < ROOT_WAVE_COUNT; i++) {
            float t = (i + 1f) / ROOT_WAVE_COUNT;
            rootTargetX[i] = (int)(bx + (px - bx) * t) + rng.nextInt(gp.tileSize) - gp.tileSize / 2;
            rootTargetY[i] = (int)(by + (py - by) * t) + rng.nextInt(gp.tileSize) - gp.tileSize / 2;
            rootEruptTimer[i] = 0;
            rootErupted[i] = false;
        }

        gp.playSE(SFX.WEAPON_SWING);
    }

    private void updateRootBarrage() {
        rootBarrageTimer++;

        // Update attack animation
        attackFrameCounter++;
        attackFrameIndex = Math.min(attackFrameCounter / 8,
                safeLen(phaseAttackFrames[currentPhase], direction, 7) - 1);

        // Spawn roots at intervals
        if (rootBarrageWave < ROOT_WAVE_COUNT
                && rootBarrageTimer >= (rootBarrageWave + 1) * ROOT_WAVE_INTERVAL) {
            rootEruptTimer[rootBarrageWave] = 1;
            gp.screenShake.shake(2f, 5);
            rootBarrageWave++;
        }

        // Update each root eruption
        for (int i = 0; i < ROOT_WAVE_COUNT; i++) {
            if (rootEruptTimer[i] > 0) {
                rootEruptTimer[i]++;

                // Damage check at eruption moment
                if (rootEruptTimer[i] == 8 && !rootErupted[i]) {
                    rootErupted[i] = true;
                    int dx = Math.abs(rootTargetX[i] - gp.player.getCenterX());
                    int dy = Math.abs(rootTargetY[i] - gp.player.getCenterY());
                    if (dx < gp.tileSize && dy < gp.tileSize && !gp.player.invincible) {
                        int damage = attack - 1 - gp.player.defense;
                        if (damage < 1) damage = 1;
                        gp.player.life -= damage;
                        gp.player.invincible = true;
                        gp.playSE(SFX.PLAYER_HIT);
                        gp.screenShake.shakeLight();
                    }

                    // Root burst particles
                    for (int j = 0; j < 6; j++) {
                        Particle p = gp.particlePool.get();
                        p.setWithPosition(this, this, ROOT_TIP, 4, 2, 15,
                                rng.nextInt(3) - 1, -(rng.nextInt(3) + 1), Particle.STYLE_HIT);
                        p.worldX = rootTargetX[i];
                        p.worldY = rootTargetY[i];
                        gp.particleList.add(p);
                    }
                }
            }
        }

        // End after all roots have erupted and animation finishes
        if (rootBarrageWave >= ROOT_WAVE_COUNT
                && rootBarrageTimer > ROOT_WAVE_COUNT * ROOT_WAVE_INTERVAL + 30) {
            rootBarrageActive = false;
            bossAttacking = false;
            speed = PHASE_SPEED[currentPhase];
            attackCooldown = ATTACK_COOLDOWNS[currentPhase] + 10;
        }
    }


    private void startWhirlwind() {
        faceBossTowardPlayer();
        whirlwindActive = true;
        whirlwindTimer = 0;
        whirlwindAngle = 0;
        speed = PHASE_SPEED[currentPhase] + 1; // faster during whirlwind
        isMoving = true;
        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;

        gp.playSE(SFX.WEAPON_SWING);
        gp.screenShake.shakeLight();
    }

    private void updateWhirlwind() {
        whirlwindTimer++;
        whirlwindAngle += 0.3f;

        // Rapidly cycle through attack frames for spinning effect
        attackFrameCounter++;
        int totalFrames = safeLen(phaseAttackFrames[currentPhase], direction, 7);
        attackFrameIndex = (attackFrameCounter / 3) % totalFrames;

        // Chase the player while spinning
        int goalCol = gp.player.getTileCol();
        int goalRow = gp.player.getTileRow();
        directChase(goalCol, goalRow);
        checkCollision();

        // Cycle directions for visual spinning
        direction = (whirlwindTimer / 4) % 4;

        // Periodic damage pulses
        if (whirlwindTimer % WHIRLWIND_HIT_INTERVAL == 0) {
            int dx = Math.abs(getCenterX() - gp.player.getCenterX());
            int dy = Math.abs(getCenterY() - gp.player.getCenterY());
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

            if (dist <= ATTACK_RANGE * 1.3 && !gp.player.invincible) {
                int damage = attack - 2 - gp.player.defense;
                if (damage < 1) damage = 1;
                gp.player.life -= damage;
                gp.player.invincible = true;
                gp.playSE(SFX.PLAYER_HIT);
                gp.screenShake.shakeLight();
            }

            // Swing trail particles
            spawnSwingParticle();
        }

        // Whirlwind trail particles each frame
        if (whirlwindTimer % 3 == 0) {
            Particle p = gp.particlePool.get();
            float a = whirlwindAngle + rng.nextFloat();
            int pdx = (int)(Math.cos(a) * 3);
            int pdy = (int)(Math.sin(a) * 3);
            p.setWithPosition(this, this, WHIRL_TRAIL, 5, 2, 12, pdx, pdy, Particle.STYLE_TRAIL);
            gp.particleList.add(p);
        }

        if (whirlwindTimer >= WHIRLWIND_DURATION) {
            whirlwindActive = false;
            bossAttacking = false;
            speed = PHASE_SPEED[currentPhase];
            // Dizzy pause after whirlwind
            attackCooldown = ATTACK_COOLDOWNS[currentPhase] + 40;
            crowdControlTimer = 20; // brief stun after spinning
        }
    }


    private void startThornRing() {
        faceBossTowardPlayer();
        thornRingActive = true;
        thornRingTimer = 0;
        thornRingFired = false;
        speed = 0;
        isMoving = false;
        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;

        gp.playSE(SFX.WEAPON_SWING);
        gp.screenShake.shakeLight();
    }

    private void updateThornRing() {
        thornRingTimer++;

        // Update attack animation during windup
        attackFrameCounter++;
        int totalFrames = safeLen(phaseAttackFrames[currentPhase], direction, 7);
        attackFrameIndex = Math.min(attackFrameCounter / 5, totalFrames - 1);

        // Pulse particles during windup
        if (thornRingTimer < THORN_RING_WINDUP && thornRingTimer % 4 == 0) {
            for (int i = 0; i < 4; i++) {
                Particle pw = gp.particlePool.get();
                float angle = (float)(i * Math.PI / 2 + thornRingTimer * 0.05);
                int pdx = (int)(Math.cos(angle) * 2);
                int pdy = (int)(Math.sin(angle) * 2);
                pw.setWithPosition(this, this, THORN_COLOR, 4, 2, 14, pdx, pdy, Particle.STYLE_SPARK);
                gp.particleList.add(pw);
            }
        }

        // Fire at windup completion
        if (thornRingTimer == THORN_RING_WINDUP && !thornRingFired) {
            thornRingFired = true;
            gp.screenShake.shakeMedium();
            gp.triggerHitstop(4);
            spawnSwingParticle();

            // Determine how many directions to fire: 8 if enraged, 4 otherwise
            int[] dirs;
            if (enraged) {
                dirs = new int[]{DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT,
                                 rotateCW(DIR_UP), rotateCW(DIR_DOWN),
                                 rotateCW(DIR_LEFT), rotateCW(DIR_RIGHT)};
            } else {
                dirs = new int[]{DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT};
            }

            for (int d : dirs) {
                Projectile bolt = gp.projectilePool.get();
                bolt.name = "Thorn";
                bolt.speed = THORN_RING_SPEED;
                bolt.maxLife = THORN_RING_BOLT_LIFE;
                bolt.attack = THORN_RING_DMG - gp.player.defense < 1 ? 1 : THORN_RING_DMG;
                bolt.alive = true;
                bolt.user = this;
                bolt.solidArea.x      = gp.tileSize * 20 / 64; // 20 at 64px
                bolt.solidArea.y      = gp.tileSize * 20 / 64;
                bolt.solidArea.width  = gp.tileSize * 24 / 64; // 24 at 64px
                bolt.solidArea.height = gp.tileSize * 24 / 64;
                bolt.solidAreaDefaultX = bolt.solidArea.x;
                bolt.solidAreaDefaultY = bolt.solidArea.y;
                // Reuse leaf bolt frames as stand-in visuals
                bolt.walkFrames = leafBoltFrames;
                bolt.set(getCenterX() - gp.tileSize / 2, getVisualCenterY() - gp.tileSize / 2,
                         d, true, this);
                gp.projectilesList.add(bolt);
            }

            // Burst of thorn particles
            for (int i = 0; i < 20; i++) {
                Particle p = gp.particlePool.get();
                float angle = (float)(i * Math.PI * 2 / 20);
                int pdx = (int)(Math.cos(angle) * (rng.nextInt(3) + 3));
                int pdy = (int)(Math.sin(angle) * (rng.nextInt(3) + 3));
                p.setWithPosition(this, this, THORN_COLOR, rng.nextInt(3) + 3, 2, 20,
                        pdx, pdy, Particle.STYLE_SPARK);
                gp.particleList.add(p);
            }
        }

        if (thornRingTimer >= THORN_RING_DURATION) {
            thornRingActive = false;
            bossAttacking = false;
            speed = PHASE_SPEED[currentPhase];
            attackCooldown = ATTACK_COOLDOWNS[currentPhase] + 30;
        }
    }


    private void fireLeafBolt() {
        faceBossTowardPlayer();
        leafBoltCooldown = LEAF_BOLT_COOLDOWN;
        attackCooldown = ATTACK_COOLDOWNS[currentPhase] / 2; // shorter cooldown for ranged

        // Quick attack animation (no locking bossAttacking for a long time)
        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;

        // Spawn the projectile from the pool
        Projectile bolt = gp.projectilePool.get();
        bolt.name = "Leaf Bolt";
        bolt.speed = LEAF_BOLT_SPEED + currentPhase; // faster in later phases
        bolt.maxLife = LEAF_BOLT_LIFE;
        bolt.attack = LEAF_BOLT_DMG + currentPhase;
        bolt.alive = true;
        bolt.user = this;

        // Hitbox
        bolt.solidArea.x      = gp.tileSize * 24 / 64; // 24 at 64px
        bolt.solidArea.y      = gp.tileSize * 24 / 64;
        bolt.solidArea.width  = gp.tileSize / 4;        // 16 at 64px
        bolt.solidArea.height = gp.tileSize / 4;
        bolt.solidAreaDefaultX = bolt.solidArea.x;
        bolt.solidAreaDefaultY = bolt.solidArea.y;

        // Set leaf sprites for 2-frame animation per direction
        bolt.walkFrames = leafBoltFrames;

        // Fire from the boss's visual center (tree body, not feet)
        bolt.set(getCenterX() - gp.tileSize / 2, getVisualCenterY() - gp.tileSize / 2,
                 direction, true, this);

        gp.projectilesList.add(bolt);
        gp.playSE(SFX.WEAPON_SWING);

        // Leaf burst particles at launch
        for (int i = 0; i < 6; i++) {
            Particle p = gp.particlePool.get();
            int pdx = rng.nextInt(3) - 1;
            int pdy = rng.nextInt(3) - 1;
            p.setWithPosition(this, this, LEAF_PARTICLE, 4, 2, 15, pdx, pdy, Particle.STYLE_DEFAULT);
            gp.particleList.add(p);
        }

        // Release attack animation after a brief moment (handled in updateAttack)
    }

    private void fireTripleBolt() {
        faceBossTowardPlayer();
        leafBoltCooldown = LEAF_BOLT_COOLDOWN + 20;
        attackCooldown = ATTACK_COOLDOWNS[currentPhase] / 2;

        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;

        int[] dirs = {direction, rotateCW(direction), rotateCCW(direction)};
        for (int d : dirs) {
            Projectile bolt = gp.projectilePool.get();
            bolt.name = "Leaf Bolt";
            bolt.speed = LEAF_BOLT_SPEED + currentPhase;
            bolt.maxLife = LEAF_BOLT_LIFE;
            bolt.attack = Math.max(1, LEAF_BOLT_DMG + currentPhase - 1);
            bolt.alive = true;
            bolt.user = this;
            bolt.solidArea.x      = gp.tileSize * 24 / 64; // 24 at 64px
            bolt.solidArea.y      = gp.tileSize * 24 / 64;
            bolt.solidArea.width  = gp.tileSize / 4;        // 16 at 64px
            bolt.solidArea.height = gp.tileSize / 4;
            bolt.solidAreaDefaultX = bolt.solidArea.x;
            bolt.solidAreaDefaultY = bolt.solidArea.y;
            bolt.walkFrames = leafBoltFrames;
            bolt.set(getCenterX() - gp.tileSize / 2, getVisualCenterY() - gp.tileSize / 2, d, true, this);
            gp.projectilesList.add(bolt);
        }
        gp.playSE(SFX.WEAPON_SWING);

        for (int i = 0; i < 10; i++) {
            Particle p = gp.particlePool.get();
            int pdx = rng.nextInt(5) - 2;
            int pdy = rng.nextInt(5) - 2;
            p.setWithPosition(this, this, LEAF_PARTICLE, 5, 3, 18, pdx, pdy, Particle.STYLE_DEFAULT);
            gp.particleList.add(p);
        }
    }

    private static int rotateCW(int dir) {
        return switch (dir) {
            case DIR_UP    -> DIR_RIGHT;
            case DIR_RIGHT -> DIR_DOWN;
            case DIR_DOWN  -> DIR_LEFT;
            case DIR_LEFT  -> DIR_UP;
            default        -> dir;
        };
    }

    private static int rotateCCW(int dir) {
        return switch (dir) {
            case DIR_UP    -> DIR_LEFT;
            case DIR_LEFT  -> DIR_DOWN;
            case DIR_DOWN  -> DIR_RIGHT;
            case DIR_RIGHT -> DIR_UP;
            default        -> dir;
        };
    }

    private void faceBossTowardPlayer() {
        int dx = gp.player.worldX - worldX;
        int dy = gp.player.worldY - worldY;
        if (Math.abs(dx) >= Math.abs(dy)) {
            direction = dx >= 0 ? DIR_RIGHT : DIR_LEFT;
        } else {
            direction = dy >= 0 ? DIR_DOWN : DIR_UP;
        }
    }


    @Override
    public void damageReaction() {
        isHurt = true;
        hurtTimer = HURT_DURATION;
        onPath = true;
        actionLockCounter = 0;
    }


    @Override
    public Color getParticleColor() { return LEAF_PARTICLE; }
    @Override
    public int getParticleSize()    { return 5; }
    @Override
    public int getParticleSpeed()   { return 2; }
    @Override
    public int getParticleMaxLife() { return 16; }
    @Override
    public int getParticleStyle()   { return Particle.STYLE_HIT; }


    /**
     * Override collision check to prevent the boss from dealing contact damage.
     * Boss only damages the player through performBossAttack() during its
     * attack animation. This also prevents directChase/followWaypoints from
     * applying accidental contact damage.
     */
    @Override
    public void checkCollision() {
        checkCollisionNoDamage();
    }

    private void checkCollisionNoDamage() {
        collisionOn = false;
        gp.cChecker.checkTile(this);
        gp.cChecker.checkObject(this, false);
        gp.cChecker.checkEntity(this, gp.npc);
        gp.cChecker.checkEntity(this, gp.monster);
        gp.cChecker.checkPlayer(this); // detect collision but skip damage
    }

    // Visual center: with centered sprite drawing, visual center matches hitbox center.
    private int getVisualCenterY() {
        return getCenterY();
    }


    @Override
    public void draw(Graphics2D g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;
        int drawSize = gp.tileSize * BOSS_SCALE;

        // Hitbox center in screen space — sprite is centered on this point
        int bossCX = screenX + solidArea.x + solidArea.width / 2;
        int bossCY = screenY + solidArea.y + solidArea.height / 2;

        // Viewport culling (wider margin for boss)
        if (worldX + drawSize < gp.player.worldX - gp.player.screenX - gp.tileSize * 4
                || worldX - gp.tileSize * 4 > gp.player.worldX + gp.player.screenX + gp.screenWidth
                || worldY + drawSize < gp.player.worldY - gp.player.screenY - gp.tileSize * 4
                || worldY - gp.tileSize * 4 > gp.player.worldY + gp.player.screenY + gp.screenHeight) {
            return;
        }

        // ── Stomp telegraph warning ring ──
        if (stompActive && stompTelegraphTimer > 0) {
            float telegraphPulse = (float)(0.5 + 0.5 * Math.sin(stompTelegraphTimer * 0.4));
            int warnAlpha = (int)(80 * telegraphPulse);
            Color warn = new Color(STOMP_WARN.getRed(), STOMP_WARN.getGreen(), STOMP_WARN.getBlue(), Math.min(255, Math.max(0, warnAlpha)));
            int warnR = (int) stompRingMaxRadius;
            g2.setColor(warn);
            g2.fillOval(bossCX - warnR, bossCY - warnR, warnR * 2, warnR * 2);
        }

        // ── Stomp expanding shockwave ring ──
        if (stompActive && stompTimer > 0 && stompRingRadius > 0) {
            float alpha = Math.max(0, 1f - stompRingRadius / stompRingMaxRadius);
            int ringAlpha = (int)(180 * alpha);
            Color ring = new Color(STOMP_RING.getRed(), STOMP_RING.getGreen(), STOMP_RING.getBlue(), Math.min(255, Math.max(0, ringAlpha)));
            g2.setColor(ring);
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(4f));
            int r = (int) stompRingRadius;
            g2.drawOval(bossCX - r, bossCY - r, r * 2, r * 2);
            // Inner fill
            Color fill = new Color(STOMP_RING.getRed(), STOMP_RING.getGreen(), STOMP_RING.getBlue(), Math.min(255, Math.max(0, ringAlpha / 3)));
            g2.setColor(fill);
            g2.fillOval(bossCX - r, bossCY - r, r * 2, r * 2);
            g2.setStroke(oldStroke);
        }

        // ── Root barrage ground markers ──
        if (rootBarrageActive) {
            for (int i = 0; i < ROOT_WAVE_COUNT; i++) {
                int rx = rootTargetX[i] - gp.player.worldX + gp.player.screenX;
                int ry = rootTargetY[i] - gp.player.worldY + gp.player.screenY;

                if (rootEruptTimer[i] == 0) {
                    // Warning crack on ground
                    if (rootBarrageTimer >= i * ROOT_WAVE_INTERVAL - 10) {
                        float pulse = (float)(0.5 + 0.5 * Math.sin(rootBarrageTimer * 0.5));
                        int wAlpha = (int)(120 * pulse);
                        g2.setColor(new Color(120, 80, 30, Math.min(255, Math.max(0, wAlpha))));
                        g2.fillOval(rx - 16, ry - 16, 32, 32);
                    }
                } else if (rootEruptTimer[i] < 25) {
                    // Erupting root spike
                    float eruptPct = Math.min(1f, rootEruptTimer[i] / 10f);
                    int rootH = (int)(gp.tileSize * 1.5f * eruptPct);
                    int rootW = (int)(gp.tileSize * 0.4f);
                    float fadeAlpha = rootEruptTimer[i] > 15 ? Math.max(0, 1f - (rootEruptTimer[i] - 15) / 10f) : 1f;

                    changeAlpha(g2, fadeAlpha);
                    // Root body
                    g2.setColor(ROOT_COLOR);
                    g2.fillRoundRect(rx - rootW / 2, ry - rootH, rootW, rootH, 6, 6);
                    // Root tip
                    g2.setColor(ROOT_TIP);
                    int tipH = Math.max(4, rootH / 4);
                    g2.fillRoundRect(rx - rootW / 2 + 2, ry - rootH, rootW - 4, tipH, 4, 4);
                    // Ground crack
                    g2.setColor(new Color(60, 40, 15, (int)(180 * fadeAlpha)));
                    g2.fillOval(rx - 18, ry - 8, 36, 16);
                    changeAlpha(g2, 1f);
                }
            }
        }

        // ── Whirlwind trail ring ──
        if (whirlwindActive) {
            float trailAlpha = 0.3f + 0.15f * (float)Math.sin(whirlwindAngle * 2);
            changeAlpha(g2, trailAlpha);
            int trailR = (int)(ATTACK_RANGE * 1.3);
            g2.setColor(WHIRL_TRAIL);
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(3f));
            g2.drawOval(bossCX - trailR, bossCY - trailR, trailR * 2, trailR * 2);
            g2.setStroke(oldStroke);
            changeAlpha(g2, 1f);
        }

        // ── Thorn Ring windup glow ──
        if (thornRingActive && !thornRingFired) {
            float windupPct = (float) thornRingTimer / THORN_RING_WINDUP;
            float pulse = (float)(0.5 + 0.5 * Math.sin(thornRingTimer * 0.6));
            int glowAlpha = (int)(60 + 100 * windupPct * pulse);
            int glowR = (int)(gp.tileSize * (0.5f + windupPct * 1.5f));
            g2.setColor(new Color(180, 220, 60, Math.min(255, Math.max(0, glowAlpha))));
            g2.fillOval(bossCX - glowR, bossCY - glowR, glowR * 2, glowR * 2);
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(2f + 2f * windupPct));
            g2.setColor(new Color(120, 200, 40, Math.min(255, Math.max(0, glowAlpha + 40))));
            g2.drawOval(bossCX - glowR, bossCY - glowR, glowR * 2, glowR * 2);
            g2.setStroke(oldStroke);
        }

        BufferedImage currentSprite = getCurrentSprite();

        // Boss HP bar: draw at top of screen when player is within 20 tiles
        if (hpBarOn && isPlayerInRange(20 * gp.tileSize)) {
            drawBossHPBar(g2);
        }

        if (invincible) {
            hpBarOn = true;
            hpBarCounter = 0;
            changeAlpha(g2, 0.4f);
        }

        // Phase transition flash
        if (phaseTransitioning) {
            float flashPct = (float) phaseTransitionTimer / PHASE_TRANSITION_DURATION;
            float flashAlpha = (float)(0.4 * Math.sin(flashPct * Math.PI));
            changeAlpha(g2, Math.max(0.2f, 1f - flashAlpha));
        }

        int drawW = drawSize;
        int drawH = drawSize;

        if (dying) {
            drawDeathSequence(g2, screenX, screenY, drawSize);
            return;
        }

        if (currentSprite != null) {
            int drawX = bossCX - drawW / 2;
            int drawY = bossCY - drawH / 2;
            g2.drawImage(currentSprite, drawX, drawY, drawW, drawH, null);
        }

        // Hit flash overlay
        if (hitFlashCounter > 0 && currentSprite != null) {
            float flashAlpha = Math.min(1f, hitFlashCounter / 6f * 0.8f);
            int sprW = currentSprite.getWidth();
            int sprH = currentSprite.getHeight();
            if (bossFlashBuffer == null || bossFlashW < sprW || bossFlashH < sprH) {
                bossFlashW = sprW;
                bossFlashH = sprH;
                bossFlashBuffer = new BufferedImage(sprW, sprH, BufferedImage.TYPE_INT_ARGB);
                if (bossFlashG2 != null) bossFlashG2.dispose();
                bossFlashG2 = bossFlashBuffer.createGraphics();
            }
            Graphics2D fg = bossFlashG2;
            fg.setComposite(AlphaComposite.Clear);
            fg.fillRect(0, 0, bossFlashW, bossFlashH);
            fg.setComposite(AlphaComposite.SrcOver);
            fg.drawImage(currentSprite, 0, 0, null);
            fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, flashAlpha));
            fg.setColor(Color.WHITE);
            fg.fillRect(0, 0, sprW, sprH);
            int drawX = bossCX - drawSize / 2;
            int drawY = bossCY - drawSize / 2;
            g2.drawImage(bossFlashBuffer, drawX, drawY, drawSize, drawSize, null);
        }

        changeAlpha(g2, 1f);
    }


    private void drawDeathSequence(Graphics2D g2, int screenX, int screenY, int drawSize) {
        int drawW;
        int drawH;
        // Center offset relative to screenX/screenY (jitter/collapse shift screenX/Y)
        int cxOff = solidArea.x + solidArea.width / 2;
        int cyOff = solidArea.y + solidArea.height / 2;

        // Stage 1: Convulsing jitter (frames 0-30)
        if (dyingCounter < 30) {
            int jitter = Math.max(1, 8 - dyingCounter / 4);
            screenX += rng.nextInt(jitter * 2 + 1) - jitter;
            screenY += rng.nextInt(jitter * 2 + 1) - jitter;

            // Squash & stretch
            float t = dyingCounter / 30f;
            float stretchX = 1.0f + (float) Math.sin(t * Math.PI * 3) * 0.15f;
            float squashY  = 1.0f - (float) Math.sin(t * Math.PI * 3) * 0.12f;
            drawW = Math.max(1, (int)(drawSize * stretchX));
            drawH = Math.max(1, (int)(drawSize * squashY));

            // Shedding bark/leaf particles
            if (dyingCounter % 3 == 0) {
                Particle p = gp.particlePool.get();
                Color c = rng.nextBoolean() ? LEAF_PARTICLE : ROOT_COLOR;
                p.setWithPosition(this, this, c, rng.nextInt(4) + 3, 2, 20,
                        rng.nextInt(5) - 2, -(rng.nextInt(3) + 1), Particle.STYLE_DEFAULT);
                gp.particleList.add(p);
            }
        }
        // Stage 2: Crumbling collapse (frames 30-55)
        else if (dyingCounter < 55) {
            float collapse = (dyingCounter - 30) / 25f;
            drawH = Math.max(1, (int)(drawSize * (1f - collapse * 0.6f)));
            drawW = Math.max(1, (int)(drawSize * (1f + collapse * 0.3f)));
            screenY += (int)(drawSize * collapse * 0.5f);

            // Heavy debris burst
            if (dyingCounter % 2 == 0) {
                for (int i = 0; i < 3; i++) {
                    Particle p = gp.particlePool.get();
                    Color c = rng.nextInt(3) == 0 ? PHASE3_SPARK : (rng.nextBoolean() ? LEAF_PARTICLE : ROOT_COLOR);
                    p.setWithPosition(this, this, c, rng.nextInt(5) + 2, 3, 25,
                            rng.nextInt(7) - 3, rng.nextInt(5) - 3, Particle.STYLE_HIT);
                    gp.particleList.add(p);
                }
            }

            // Screen shake fading out
            if (dyingCounter == 30) gp.screenShake.shakeHeavy();
            if (dyingCounter == 40) gp.screenShake.shakeMedium();
        }
        // Stage 3: Final fade with light pillar effect (frames 55+)
        else {
            float fade = Math.max(0, 1f - (dyingCounter - 55) / 25f);
            changeAlpha(g2, fade);

            drawH = Math.max(1, (int)(drawSize * 0.4f));
            drawW = Math.max(1, (int)(drawSize * 1.3f));
            screenY += (int)(drawSize * 0.5f);

            // Light pillar
            if (fade > 0.1f) {
                int pillarX = screenX + cxOff - 20;
                int pillarAlpha = (int)(120 * fade);
                g2.setColor(new Color(255, 255, 200, Math.min(255, Math.max(0, pillarAlpha))));
                g2.fillRect(pillarX, 0, 40, screenY + drawH);
                g2.setColor(new Color(255, 255, 255, Math.min(255, Math.max(0, pillarAlpha / 2))));
                g2.fillRect(pillarX + 10, 0, 20, screenY + drawH);
            }
        }

        dyingAnimation(g2);

        BufferedImage currentSprite = getCurrentSprite();
        if (currentSprite != null) {
            int drawX = screenX + cxOff - drawW / 2;
            int drawY = screenY + cyOff - drawH / 2;
            g2.drawImage(currentSprite, drawX, drawY, drawW, drawH, null);
        }

        changeAlpha(g2, 1f);
    }

    @Override
    public void dyingAnimation(Graphics2D g2) {
        boolean wasAlive = alive;
        super.dyingAnimation(g2);
        if (wasAlive && !alive) {
            // Boss defeated — advance quest and story flags before map transition
            gp.boss1Defeated = true;
            gp.storyAct = Math.max(gp.storyAct, 1);
            if (gp.questManager != null) gp.questManager.progress("defeat_hollow_king", 1);
            // Transition to Shattered Lake at the Coming_from_boss1 spawn point
            gp.mapManager.nextSpawnId = "Coming_from_boss1";
            gp.mapManager.startTransition("shattered_lake", -1, -1);
        }
    }

    private BufferedImage getCurrentSprite() {
        if (dying) {
            int deathFrame = Math.min(dyingCounter / 8, 5);
            return safeFrameWithFallback(phaseDeathFrames[currentPhase], direction, deathFrame);
        }
        if (bossAttacking) {
            return safeFrameWithFallback(phaseAttackFrames[currentPhase], direction, attackFrameIndex);
        }
        if (isHurt) {
            int hurtFrame = Math.min((HURT_DURATION - hurtTimer) / 5, 3);
            return safeFrameWithFallback(phaseHurtFrames[currentPhase], direction, hurtFrame);
        }
        if (isMoving) {
            BufferedImage walk = getWalkFrameImage(direction, spriteNum);
            if (walk == null) walk = getWalkFrameImage(DIR_DOWN, spriteNum);
            if (walk == null) walk = safeFrameWithFallback(idleFrames, direction, 0);
            return walk;
        }
        // Idle
        BufferedImage idle = safeFrame(idleFrames, direction, idleFrameIndex);
        if (idle == null) idle = safeFrame(idleFrames, DIR_DOWN, idleFrameIndex);
        if (idle == null) idle = getWalkFrameImage(direction, 1);
        if (idle == null) idle = getWalkFrameImage(DIR_DOWN, 1);
        return idle;
    }

    /** safeFrame with automatic fallback to DIR_DOWN if the requested direction row doesn't exist. */
    private static BufferedImage safeFrameWithFallback(BufferedImage[][] frames, int dir, int frame) {
        BufferedImage img = safeFrame(frames, dir, frame);
        if (img == null) img = safeFrame(frames, DIR_DOWN, frame);
        if (img == null) img = safeFrame(frames, DIR_DOWN, 0);
        return img;
    }

    private void drawBossHPBar(Graphics2D g2) {
        int barWidth  = gp.screenWidth / 2;
        int barHeight = 18;
        int barX = (gp.screenWidth - barWidth) / 2;
        int barY = 22;

        // Background with outer glow based on phase
        Color phaseGlow = switch (currentPhase) {
            case 1  -> new Color(220, 180, 30, 40);
            case 2  -> new Color(220, 50, 30, 50);
            default -> new Color(50, 180, 50, 30);
        };
        g2.setColor(phaseGlow);
        g2.fillRoundRect(barX - 8, barY - 8, barWidth + 16, barHeight + 16, 12, 12);

        g2.setColor(new Color(0, 0, 0, 200));
        g2.fillRoundRect(barX - 4, barY - 4, barWidth + 8, barHeight + 8, 8, 8);
        g2.setColor(HP_BG);
        g2.fillRect(barX, barY, barWidth, barHeight);

        // Damage trail (white bar that drains smoothly)
        if (displayedHpRatio > targetHpRatio) {
            g2.setColor(HP_DAMAGE_FLASH);
            g2.fillRect(barX, barY, (int) (barWidth * displayedHpRatio), barHeight);
        }

        // HP fill
        float hpRatio = Math.max(0f, (float) life / maxLife);
        Color barColor = switch (currentPhase) {
            case 0  -> PHASE_GREEN;
            case 1  -> PHASE_YELLOW;
            case 2  -> PHASE_RED;
            default -> PHASE_RED;
        };
        g2.setColor(barColor);
        int hpWidth = (int) (barWidth * hpRatio);
        g2.fillRect(barX, barY, hpWidth, barHeight);

        // Shiny highlight on top of HP bar
        g2.setColor(new Color(255, 255, 255, 50));
        g2.fillRect(barX, barY, hpWidth, barHeight / 3);

        // Phase markers
        g2.setColor(MARKER_COLOR);
        int m2 = barX + (int) (barWidth * PHASE_2_THRESHOLD);
        int m3 = barX + (int) (barWidth * PHASE_3_THRESHOLD);
        g2.fillRect(m2 - 1, barY, 2, barHeight);
        g2.fillRect(m3 - 1, barY, 2, barHeight);

        // Damage flash overlay on bar
        if (hpFlashTimer > 0) {
            float flashAlpha = hpFlashTimer / 8f * 0.4f;
            g2.setColor(new Color(255, 255, 255, (int)(255 * Math.min(1f, flashAlpha))));
            g2.fillRect(barX, barY, barWidth, barHeight);
        }

        // Border — thicker, more dramatic
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(BOSS_BAR_STROKE);
        g2.setColor(BOSS_BAR_BORDER_COLOR);
        g2.drawRoundRect(barX - 1, barY - 1, barWidth + 2, barHeight + 2, 4, 4);
        g2.setStroke(oldStroke);

        // Boss name + phase subtitle
        g2.setFont(BOSS_NAME_FONT);
        FontMetrics fm = g2.getFontMetrics();

        // Drop shadow
        g2.setColor(BOSS_NAME_SHADOW);
        int textX = barX + (barWidth - fm.stringWidth(name)) / 2;
        g2.drawString(name, textX + 1, barY - 7);
        // Main text
        g2.setColor(Color.WHITE);
        g2.drawString(name, textX, barY - 8);

        // Phase subtitle / enrage label
        if (phaseNameTimer > 0 && phaseNameAlpha > 0) {
            g2.setFont(BOSS_PHASE_FONT);
            FontMetrics fm2 = g2.getFontMetrics();
            String phaseName = enraged ? "ENRAGED!" : PHASE_NAMES[currentPhase];
            Color labelColor = enraged ? PHASE_RED : barColor;
            int subX = barX + (barWidth - fm2.stringWidth(phaseName)) / 2;
            int safeAlpha = Math.min(255, Math.max(0, phaseNameAlpha));
            g2.setColor(new Color(labelColor.getRed(), labelColor.getGreen(), labelColor.getBlue(), safeAlpha));
            g2.drawString(phaseName, subX, barY + barHeight + 16);
        }

        hpBarCounter++;
        if (hpBarCounter > 600) { hpBarCounter = 0; hpBarOn = false; }
    }


    private static BufferedImage safeFrame(BufferedImage[][] frames, int dir, int frame) {
        if (frames != null && dir >= 0 && dir < frames.length
                && frames[dir] != null && frame >= 0 && frame < frames[dir].length) {
            return frames[dir][frame];
        }
        return null;
    }

    private static int safeLen(BufferedImage[][] frames, int dir, int fallback) {
        if (frames != null && dir >= 0 && dir < frames.length && frames[dir] != null) {
            return frames[dir].length;
        }
        return fallback;
    }
}

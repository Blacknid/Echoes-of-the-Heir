package entity;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import audio.SFX;
import main.GamePanel;

/**
 * The Withered Tree — a 3-phase Ent boss with melee attacks.
 * Uses 128×128 sprites rendered at 2× tileSize.
 * Phase 1 = Ent1, Phase 2 = Ent2, Phase 3 = Ent3.
 */
public class BOSS_WitheredTree extends Entity {

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

    // Attack state
    private boolean bossAttacking = false;
    private int attackFrameCounter = 0;
    private int attackFrameIndex = 0;
    private int attackCooldown = 0;
    private static final int ATTACK_HIT_FRAME = 3;
    private boolean attackHitApplied = false;

    // Hurt flash
    private boolean isHurt = false;
    private int hurtTimer = 0;
    private static final int HURT_DURATION = 20;

    // Idle animation
    private int idleFrameCounter = 0;
    private int idleFrameIndex = 0;
    private boolean isMoving = false;

    // Boss-specific sprite frame size (raw pixel size on the spritesheet)
    private static final int SPRITE_SIZE = 128;
    // Boss render scale: 6× the player tile size
    private static final int BOSS_SCALE = 6;

    // Per-phase tuning
    private static final int[]   ATTACK_COOLDOWNS = {120, 90, 60};
    private static final int[]   PHASE_ATTACK     = {5, 7, 10};
    private static final int[]   PHASE_SPEED      = {1, 2, 2};
    private static final int[]   PHASE_DEFENSE    = {2, 3, 4};
    private static final int     ATTACK_RANGE     = 128;

    // Hit flash buffer (reused to avoid allocation per frame)
    private BufferedImage bossFlashBuffer;
    private int bossFlashW, bossFlashH;

    private static final Random rng = new Random();

    // Colors
    private static final Color HP_BG        = new Color(35, 35, 35);
    private static final Color PHASE_GREEN  = new Color(50, 180, 50);
    private static final Color PHASE_YELLOW = new Color(220, 180, 30);
    private static final Color PHASE_RED    = new Color(220, 50, 30);
    private static final Color MARKER_COLOR = new Color(200, 200, 200, 150);
    private static final Color LEAF_PARTICLE = new Color(90, 130, 50);
    private static final Color PHASE2_SPARK = new Color(120, 180, 50);
    private static final Color PHASE3_SPARK = new Color(180, 40, 40);

    public BOSS_WitheredTree(GamePanel gp) {
        super(gp);

        type = type_monster;
        name = "The Withered Tree";
        collision = true;

        maxLife = 80;
        life = maxLife;
        attack = 5;
        defense = 2;
        exp = 25;
        defaultSpeed = 1;
        speed = defaultSpeed;
        walkFrameCount = 6;
        animationFrameInterval = 8;
        aggroRange = 10 * gp.tileSize;
        invincibleDuration = 15;

        // Solid area at the tree's base (near worldX/worldY anchor).
        // The visual sprite is drawn offset upward, but the collision
        // footprint represents the trunk base where the tree stands.
        solidArea.x = 0;
        solidArea.y = 0;
        solidArea.width = 64;
        solidArea.height = 64;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        attackArea.width = 80;
        attackArea.height = 80;

        loadAllPhaseSprites();
        applyPhase(0);
    }

    // ────────── Sprite loading ──────────

    private void loadAllPhaseSprites() {
        String[] prefixes = {"Ent1", "Ent2", "Ent3"};
        for (int p = 0; p < 3; p++) {
            String pre  = prefixes[p];
            String base = "/res/bosses/" + pre;
            String ws   = base + "/With_shadow/" + pre;
            // Sheet rows are already in game direction order: DOWN(0), LEFT(1), RIGHT(2), UP(3)
            phaseWalkFrames[p]   = loadSpriteMatrix(ws + "_Walk_with_shadow",   SPRITE_SIZE, SPRITE_SIZE);
            phaseIdleFrames[p]   = loadSpriteMatrix(ws + "_Idle_with_shadow",   SPRITE_SIZE, SPRITE_SIZE);
            phaseAttackFrames[p] = loadSpriteMatrix(ws + "_Attack_with_shadow", SPRITE_SIZE, SPRITE_SIZE);
            phaseHurtFrames[p]   = loadSpriteMatrix(ws + "_Hurt_with_shadow",   SPRITE_SIZE, SPRITE_SIZE);
            phaseDeathFrames[p]  = loadSpriteMatrix(ws + "_Death_with_shadow",  SPRITE_SIZE, SPRITE_SIZE);
            phaseSwingFrames[p]  = loadSpriteMatrix(base + "/Parts/" + pre + "_Attack_swing", SPRITE_SIZE, SPRITE_SIZE);
        }
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

    // ────────── Phase transitions ──────────

    private void checkPhaseTransition() {
        float hpPct = (float) life / maxLife;
        int target = currentPhase;
        if (hpPct <= PHASE_3_THRESHOLD && currentPhase < 2) target = 2;
        else if (hpPct <= PHASE_2_THRESHOLD && currentPhase < 1) target = 1;
        if (target != currentPhase) transitionToPhase(target);
    }

    private void transitionToPhase(int newPhase) {
        applyPhase(newPhase);
        // Spark burst visual feedback
        Color c = (newPhase == 2) ? PHASE3_SPARK : PHASE2_SPARK;
        for (int i = 0; i < 16; i++) {
            Particle p = gp.particlePool.get();
            float angle = (float) (i * Math.PI * 2 / 16);
            int dx = (int) (Math.cos(angle) * 3);
            int dy = (int) (Math.sin(angle) * 3);
            p.setWithPosition(this, this, c, 6, 3, 20, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(p);
        }
        crowdControlTimer = 30;
        bossAttacking = false;
        attackFrameCounter = 0;
    }

    // ────────── AI ──────────

    @Override
    public void setAction() {
        if (bossAttacking) return;

        if (isPlayerInRange(aggroRange)) {
            int dx = Math.abs(getCenterX() - gp.player.getCenterX());
            int dy = Math.abs(getCenterY() - gp.player.getCenterY());
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

            if (dist <= ATTACK_RANGE && attackCooldown <= 0) {
                startAttack();
            } else {
                onPath = true;
                isMoving = true;
                int goalCol = gp.player.getTileCol();
                int goalRow = gp.player.getTileRow();
                if (dx < gp.tileSize * 2 && dy < gp.tileSize * 2) {
                    directChase(goalCol, goalRow);
                } else {
                    searchPath(goalCol, goalRow);
                }
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

    private void startAttack() {
        bossAttacking = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;
        speed = 0;
        isMoving = false;
        // Face toward player
        int dx = gp.player.worldX - worldX;
        int dy = gp.player.worldY - worldY;
        if (Math.abs(dx) >= Math.abs(dy)) {
            direction = dx >= 0 ? DIR_RIGHT : DIR_LEFT;
        } else {
            direction = dy >= 0 ? DIR_DOWN : DIR_UP;
        }
    }

    // ────────── Update ──────────

    @Override
    public void update() {
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
        if (isHurt) { hurtTimer--; if (hurtTimer <= 0) isHurt = false; }

        checkPhaseTransition();

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

    // ────────── Attack logic ──────────

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
        int dy = Math.abs(getCenterY() - gp.player.getCenterY());
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

        if (dist <= ATTACK_RANGE && !gp.player.invincible) {
            int damage = attack - gp.player.defense;
            if (damage < 1) damage = 1;
            gp.player.life -= damage;
            gp.player.invincible = true;
            gp.playSE(SFX.PLAYER_HIT);
            gp.screenShake.shakeMedium();
            gp.triggerHitstop(3);
        }
    }

    // ────────── Damage reaction ──────────

    @Override
    public void damageReaction() {
        isHurt = true;
        hurtTimer = HURT_DURATION;
        onPath = true;
        actionLockCounter = 0;
    }

    // ────────── Particles ──────────

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

    // ────────── Collision (no contact damage) ──────────

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

    // ────────── Draw ──────────

    @Override
    public void draw(Graphics2D g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;
        int drawSize = gp.tileSize * BOSS_SCALE;

        // Viewport culling (wider margin for 2-tile boss)
        if (worldX + drawSize < gp.player.worldX - gp.player.screenX
                || worldX - gp.tileSize > gp.player.worldX + gp.player.screenX
                || worldY + drawSize < gp.player.worldY - gp.player.screenY
                || worldY - gp.tileSize > gp.player.worldY + gp.player.screenY) {
            return;
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

        int drawW = drawSize;
        int drawH = drawSize;

        if (dying) {
            int deathJitter = Math.max(0, 6 - dyingCounter / 8);
            if (deathJitter > 0) {
                screenX += (int) ((Math.random() * (deathJitter * 2 + 1)) - deathJitter);
                screenY += (int) ((Math.random() * (deathJitter * 2 + 1)) - deathJitter);
            }
            if (dyingCounter < 22) {
                float t = dyingCounter / 22f;
                float stretchX = 1.0f + (float) Math.sin(t * Math.PI) * 0.25f;
                float squashY  = 1.0f - (float) Math.sin(t * Math.PI) * 0.20f;
                drawW = Math.max(1, (int) (drawSize * stretchX));
                drawH = Math.max(1, (int) (drawSize * squashY));
            }
            dyingAnimation(g2);
        }

        if (currentSprite != null) {
            int drawX = screenX - (drawW - gp.tileSize) / 2;
            int drawY = screenY - (drawH - gp.tileSize);
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
            }
            Graphics2D fg = bossFlashBuffer.createGraphics();
            fg.setComposite(AlphaComposite.Clear);
            fg.fillRect(0, 0, bossFlashW, bossFlashH);
            fg.setComposite(AlphaComposite.SrcOver);
            fg.drawImage(currentSprite, 0, 0, null);
            fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, flashAlpha));
            fg.setColor(Color.WHITE);
            fg.fillRect(0, 0, sprW, sprH);
            fg.dispose();
            int drawX = screenX - (drawSize - gp.tileSize) / 2;
            int drawY = screenY - (drawSize - gp.tileSize);
            g2.drawImage(bossFlashBuffer, drawX, drawY, drawSize, drawSize, null);
        }

        changeAlpha(g2, 1f);
    }

    private BufferedImage getCurrentSprite() {
        if (dying) {
            int deathFrame = Math.min(dyingCounter / 8, 5);
            return safeFrame(phaseDeathFrames[currentPhase], direction, deathFrame);
        }
        if (bossAttacking) {
            return safeFrame(phaseAttackFrames[currentPhase], direction, attackFrameIndex);
        }
        if (isHurt) {
            int hurtFrame = Math.min((HURT_DURATION - hurtTimer) / 5, 3);
            return safeFrame(phaseHurtFrames[currentPhase], direction, hurtFrame);
        }
        if (isMoving) {
            return getWalkFrameImage(direction, spriteNum);
        }
        // Idle
        BufferedImage idle = safeFrame(idleFrames, direction, idleFrameIndex);
        return idle != null ? idle : getWalkFrameImage(direction, 1);
    }

    private void drawBossHPBar(Graphics2D g2) {
        int barWidth  = gp.screenWidth / 2;
        int barHeight = 16;
        int barX = (gp.screenWidth - barWidth) / 2;
        int barY = 20;

        // Background
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect(barX - 4, barY - 4, barWidth + 8, barHeight + 8, 8, 8);
        g2.setColor(HP_BG);
        g2.fillRect(barX, barY, barWidth, barHeight);

        // HP fill
        float hpRatio = Math.max(0f, (float) life / maxLife);
        Color barColor = switch (currentPhase) {
            case 0  -> PHASE_GREEN;
            case 1  -> PHASE_YELLOW;
            case 2  -> PHASE_RED;
            default -> PHASE_RED;
        };
        g2.setColor(barColor);
        g2.fillRect(barX, barY, (int) (barWidth * hpRatio), barHeight);

        // Phase markers
        g2.setColor(MARKER_COLOR);
        int m2 = barX + (int) (barWidth * PHASE_2_THRESHOLD);
        int m3 = barX + (int) (barWidth * PHASE_3_THRESHOLD);
        g2.fillRect(m2 - 1, barY, 2, barHeight);
        g2.fillRect(m3 - 1, barY, 2, barHeight);

        // Border
        g2.setColor(new Color(160, 140, 100));
        g2.drawRoundRect(barX - 1, barY - 1, barWidth + 2, barHeight + 2, 4, 4);

        // Boss name
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
        FontMetrics fm = g2.getFontMetrics();
        int textX = barX + (barWidth - fm.stringWidth(name)) / 2;
        g2.drawString(name, textX, barY - 8);

        hpBarCounter++;
        if (hpBarCounter > 600) { hpBarCounter = 0; hpBarOn = false; }
    }

    // ────────── Helpers ──────────

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

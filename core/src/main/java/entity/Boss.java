package entity;

import gfx.Color;
import gfx.Font;
import gfx.FontMetrics;
import gfx.GdxRenderer;
import gfx.Sprite;

import main.GamePanel;

/**
 * Shared base for boss enemies: sprite-driven animation state (idle/walk/attack/hurt/death),
 * a name-tagged HP bar, and a simple telegraphed melee attack.
 *
 * To add a new boss: extend this class, load your sprite sheets with loadDirectionalSheet(...) into
 * walkFrames/idleFrames/attackFrames/hurtFrames/deathFrames, set the stat fields, and override
 * setAction() for custom AI (or just call chaseAndAttack(range) for simple melee chasers).
 *
 * loadDirectionalSheet(path, frameSize) assumes rows are DOWN, LEFT, RIGHT, UP (top to bottom).
 * If your sheet uses a different row order, use the other overload and just list the actual
 * order, see its javadoc.
 */
public abstract class Boss extends Entity {

    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Color NAME_SHADOW = new Color(0, 0, 0, 180);
    private static final Color HP_BG = new Color(35, 35, 35);
    private static final Color HP_FG = new Color(220, 50, 30);
    private static final Color HP_BORDER = new Color(160, 140, 100);

    protected Sprite[][] hurtFrames;
    protected Sprite[][] deathFrames;

    // Attack animation state: windup plays the attack frames, damage lands on attackHitFrame.
    protected boolean attackingNow = false;
    protected int attackFrameCounter = 0;
    protected int attackFrameIndex = 0;
    protected int attackCooldownTimer = 0;
    private boolean attackHitApplied = false;

    protected int attackHitFrame = 3;       // frame index (0-based) within attackFrames that lands the hit
    protected int attackFrameSpeed = 6;     // ticks per attack frame
    protected int attackCooldown = 60;      // ticks between attacks
    protected int attackRange;              // pixels; set from solidArea/tileSize in subclass

    // Cone hitbox for the attack, same shape/math as the player's melee cone (gfx.geom.Cone).
    // Override these three in a subclass constructor to resize the cone for that boss, e.g.
    // attackConeRadiusScale = 1.5; attackConeHalfAngle = Math.toRadians(45);
    protected double attackConeRadiusScale = 1.35; // x gp.tileSize
    protected double attackConeHalfAngle = Math.toRadians(55); // half-spread, radians
    protected double attackAngle = 0.0;
    public gfx.geom.Cone attackCone;

    protected boolean isHurt = false;
    private int hurtTimer = 0;
    protected int hurtDuration = 16;

    // Set true in a subclass constructor if this boss has a BossIntroTrigger somewhere (see
    // ui.BossIntroCutscene), its HP bar then stays hidden until that intro has actually played once,
    // instead of just popping in whenever the player wanders within range. Bosses with no intro
    // cutscene leave this false and keep the old range-only behavior. Set by BossIntroCutscene.finish().
    protected boolean requiresIntroForHpBar = false;
    private boolean introSeen = false;

    /** Called once by BossIntroCutscene when its intro for this boss finishes. */
    public void markIntroSeen() {
        introSeen = true;
    }

    // ─── Phase 2 ─────────────────────────────────────────────────────────────────────────────────
    // At phase2HealthFraction of max life (0 = no phase 2), the boss transitions once: optionally
    // renames itself, optionally heals back to full, and, from then on, periodically summons
    // minions, teleports, and/or attacks faster, per whichever of the fields below a subclass set.
    // Everything phase-2-related is opt-in (defaults are all "off"); set only what a given boss needs.
    protected float phase2HealthFraction = 0f;
    protected String phase2Name = null;                 // non-null: rename on transition
    protected boolean phase2FullHeal = false;            // true: heal to maxLife on transition
    protected float phase2AttackCooldownMultiplier = 1f; // < 1 = attacks more often after transition
    private boolean phase2Active = false;

    // Minion summoning: opt in by setting summonMonsterId (null = disabled).
    protected String summonMonsterId = null;
    protected int summonIntervalMinTicks = 300;  // 5s at 60 ticks/sec
    protected int summonIntervalMaxTicks = 420;  // 7s at 60 ticks/sec
    protected int summonMaxAlive = 3;            // cap on this boss's own live summons at once
    private int summonTimer = 0;
    private final java.util.List<Entity> liveSummons = new java.util.ArrayList<>();

    // Dash-evade: opt in by setting evadeDashChance > 0 and dashFrames. When the player's melee
    // would hit this boss, there's a evadeDashChance chance it dashes away instead of taking the hit.
    protected Sprite[][] dashFrames;
    protected float evadeDashChance = 0f;
    protected int evadeDashDurationTicks = 14;
    protected int evadeDashDistanceTiles = 2;
    private boolean evadingNow = false;
    private int evadeDashTimer = 0;

    // Teleport: opt in by setting teleportIntervalMinTicks/MaxTicks > 0. Reuses deathFrames played
    // forward (vanish in place) then reversed (reappear at the new spot), see updateTeleport().
    protected int teleportIntervalMinTicks = 0;
    protected int teleportIntervalMaxTicks = 0;
    protected int teleportMinDistanceTiles = 3;
    protected int teleportMaxDistanceTiles = 6;
    private int teleportTimer = 0;
    private static final int TELEPORT_NONE = 0, TELEPORT_VANISHING = 1, TELEPORT_REAPPEARING = 2;
    private int teleportPhase = TELEPORT_NONE;
    private int teleportFrameCounter = 0;

    private final java.util.Random bossRandom = new java.util.Random();

    public Boss(GamePanel gp) {
        super(gp);
        type = TYPE_MONSTER;
        hpBarOn = true;
    }

    /**
     * Sets the visual size (spriteScale) and a same-size square hitbox, horizontally centered and
     * flush with the bottom of the tile (like a character standing on the ground). hitboxSize is a
 * final on-screen pixel size, it does NOT grow with spriteScale (a bigger sprite doesn't need
     * a proportionally bigger hitbox).
     */
    protected void setScale(float scale, int hitboxSize) {
        setScale(scale, hitboxSize, 0.5f, 1f);
    }

    /**
     * Sets the visual size (spriteScale) and hitbox together, so they never drift apart no matter
 * how big spriteScale is. hitboxSize is a final on-screen pixel size, it does NOT grow with
     * spriteScale, so just pick a small tight box directly (e.g. 20-24px).
     *
     * anchorX/anchorY place the hitbox's center as a simple fraction of the drawn sprite: 0 = left/top
     * edge, 1 = right/bottom edge, 0.5 = middle. E.g. (0.5, 0.5) centers it in the sprite; (0.5, 0.3)
 * puts it in the upper-center, handy for sprites like ghosts that float instead of standing on
 * the ground. No pixel math, no native-frame coordinates, just eyeball where on the sprite you
     * want it and adjust the fraction.
     */
    protected void setScale(float scale, int hitboxSize, float anchorX, float anchorY) {
        setScale(scale, hitboxSize, hitboxSize, anchorX, anchorY);
    }

    /** Same as the 4-arg overload, but lets the hitbox be taller/wider than it is wide/tall. */
    protected void setScale(float scale, int hitboxWidth, int hitboxHeight, float anchorX, float anchorY) {
        spriteScale = scale;
        int drawSize = (int) (gp.tileSize * scale);
        int spriteLeft = -(drawSize - gp.tileSize) / 2;   // draw()'s sprite offset, relative to worldX/Y
        int spriteTop  = -(drawSize - gp.tileSize);
        int centerX = spriteLeft + Math.round(drawSize * anchorX);
        int centerY = spriteTop  + Math.round(drawSize * anchorY);
        solidArea.x = centerX - hitboxWidth / 2;
        solidArea.y = centerY - hitboxHeight / 2;
        solidArea.width = hitboxWidth;
        solidArea.height = hitboxHeight;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        setOctagonHurt(centerX, centerY, hitboxWidth / 2, hitboxHeight / 2);
    }

    /**
     * Loads a [direction][frame] sprite matrix, assuming the sheet's rows are already in the
     * engine's order: row 0 = DOWN, row 1 = LEFT, row 2 = RIGHT, row 3 = UP.
     */
    protected Sprite[][] loadDirectionalSheet(String path, int frameSize) {
        return loadSpriteMatrix(path, frameSize, frameSize);
    }

    /**
     * Loads a [direction][frame] sprite matrix from a sheet whose rows are in a different order
 * than the engine expects. Just list which direction each row actually is, top to bottom
     * e.g. a sheet drawn as DOWN, RIGHT, LEFT, UP would be:
     * <pre>loadDirectionalSheet(path, size, DIR_DOWN, DIR_RIGHT, DIR_LEFT, DIR_UP);</pre>
     */
    protected Sprite[][] loadDirectionalSheet(String path, int frameSize, int row0, int row1, int row2, int row3) {
        Sprite[][] raw = loadSpriteMatrix(path, frameSize, frameSize);
        Sprite[][] sheet = new Sprite[Math.max(raw.length, 4)][];
        int[] rowDirs = {row0, row1, row2, row3};
        for (int i = 0; i < rowDirs.length && i < raw.length; i++) {
            sheet[rowDirs[i]] = raw[i];
        }
        return sheet;
    }

    // When set > range, the boss backs off to this distance between attacks instead of sitting in
    // the player's face, then closes in to `range` again once its attack is off cooldown, for a
    // hit-and-run feel. 0 (default) disables this and the boss just stays at melee range, unchanged.
    protected int keepDistance = 0;

    /** Simple reusable AI: chase the player, attack when in range and off cooldown. */
    protected void chaseAndAttack(int range) {
        if (attackingNow) return;

        if (attackCooldownTimer > 0) attackCooldownTimer--;

        if (!isPlayerInRange(aggroRange)) {
            onPath = false;
            return;
        }

        int dx = Math.abs(getCenterX() - gp.player.getCenterX());
        int dy = Math.abs(getCenterY() - gp.player.getCenterY());
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

        // Retreat phase: attack is on cooldown and the player is already closer than the preferred
        // hover distance, back away instead of continuing to close in.
        if (keepDistance > range && attackCooldownTimer > 0 && dist < keepDistance) {
            onPath = true;
            double away = Math.atan2(getCenterY() - gp.player.getCenterY(), getCenterX() - gp.player.getCenterX());
            boolean moved = moveFreelyToward(getCenterX() + Math.cos(away) * gp.tileSize,
                                              getCenterY() + Math.sin(away) * gp.tileSize);
            if (moved || dist > range) return; // couldn't move at all (fully boxed in) -> fall through to fighting
        }

        if (dist <= range) {
            onPath = false;
            faceTowardPlayer();
            if (attackCooldownTimer <= 0) startAttack();
        } else {
            onPath = true;
            if (dx < aggroRange && dy < aggroRange) {
                // Free movement handles walls/corners itself (per-axis collision = slides along
                // walls instead of stopping dead), so it covers everything A* used to be needed for
                // except genuinely large obstacles the boss can't just slide around.
                if (!moveFreelyToward(gp.player.getCenterX(), gp.player.getCenterY())) {
                    searchPath(gp.player.getTileCol(), gp.player.getTileRow());
                }
            } else {
                searchPath(gp.player.getTileCol(), gp.player.getTileRow());
            }
        }
    }

    /**
     * Moves this entity directly toward (targetX, targetY) at its current `speed`, checking X and Y
     * collision independently (like the player's own movement) so a wall blocking one axis doesn't
 * stop movement on the other, the entity slides along walls/corners instead of freezing or
     * flip-flopping direction. Diagonal movement is normalized so it isn't faster than cardinal.
     * Also sets `direction` from whichever axis actually moved more, for the 4-way walk sprite.
     * Returns true if the entity moved on either axis this frame.
     */
    protected boolean moveFreelyToward(double targetX, double targetY) {
        double dx = targetX - getCenterX();
        double dy = targetY - getCenterY();
        double len = Math.hypot(dx, dy);
        if (len < 1.0) return false;

        double nx = dx / len, ny = dy / len;
        boolean diagonal = Math.abs(nx) > 0.05 && Math.abs(ny) > 0.05;
        double scale = diagonal ? speed * 0.7071 : speed;
        int stepX = (int) Math.round(nx * scale);
        int stepY = (int) Math.round(ny * scale);

        boolean movedX = stepX != 0 && tryMove(stepX, 0);
        boolean movedY = stepY != 0 && tryMove(0, stepY);

        if (Math.abs(stepX) >= Math.abs(stepY)) {
            if (movedX) direction = stepX < 0 ? DIR_LEFT : DIR_RIGHT;
            else if (movedY) direction = stepY < 0 ? DIR_UP : DIR_DOWN;
        } else {
            if (movedY) direction = stepY < 0 ? DIR_UP : DIR_DOWN;
            else if (movedX) direction = stepX < 0 ? DIR_LEFT : DIR_RIGHT;
        }

        return movedX || movedY;
    }

    /**
     * Attempts to move by (dx, dy) on this single axis. Mirrors Player.moveAxis: checkCollision()
     * does a "swept" check that extends the probe by `speed` pixels in the entity's CURRENT
 * `direction`, so it must run BEFORE worldX/worldY change, predicting the step rather than
     * checking it after the fact (checking post-move would probe a step beyond where we actually
     * are). `direction` is only used for that check here, not left mutated by it.
     */
    private boolean tryMove(int dx, int dy) {
        int savedDir = direction;
        direction = dx != 0 ? (dx < 0 ? DIR_LEFT : DIR_RIGHT) : (dy < 0 ? DIR_UP : DIR_DOWN);
        checkCollision();
        boolean blocked = collisionOn;
        direction = savedDir;
        if (blocked) return false;
        worldX += dx;
        worldY += dy;
        return true;
    }

    public boolean isAttackingNow() {
        return attackingNow;
    }

    /** Whether this boss's HP bar overlay should currently be drawn (see RenderPipeline.drawBossHpBars). */
    public boolean shouldShowHpBar() {
        if (gp.bossIntroCutscene != null && gp.bossIntroCutscene.getBoss() == this) return false;
        if (requiresIntroForHpBar && !introSeen) return false;
        return hpBarOn && alive && !dying && isPlayerInRange(16 * gp.tileSize);
    }

    protected void startAttack() {
        attackingNow = true;
        attackFrameCounter = 0;
        attackFrameIndex = 0;
        attackHitApplied = false;
        attackCone = null;
        onPath = false;
        speed = 0;
        // Cardinal-only attack angle (matches `direction`, already axis-locked by faceTowardPlayer())
        // so the cone always points exactly up/down/left/right, never at a free diagonal angle.
        attackAngle = switch (direction) {
            case DIR_UP -> -Math.PI / 2;
            case DIR_DOWN -> Math.PI / 2;
            case DIR_LEFT -> Math.PI;
            default -> 0; // DIR_RIGHT
        };
    }

    /** Advances the attack animation and applies damage on the hit frame. Call from update(). */
    protected void updateAttack() {
        attackFrameCounter++;
        int newIndex = attackFrameCounter / attackFrameSpeed;
        int maxFrames = frameCount(attackFrames, direction);

        if (newIndex >= maxFrames) {
            attackingNow = false;
            speed = defaultSpeed;
            attackCooldownTimer = attackCooldown;
            attackCone = null;
            return;
        }
        attackFrameIndex = newIndex;

        if (attackFrameIndex == attackHitFrame && !attackHitApplied) {
            attackHitApplied = true;
            onAttackHit();
        }
    }

    /** Called once, on the hit frame of the attack animation. Default: cone-hitbox melee damage, same shape as the player's attack. */
    protected void onAttackHit() {
        double radius = gp.tileSize * attackConeRadiusScale;
        attackCone = new gfx.geom.Cone(getCenterX(), getCenterY(), radius, attackAngle, attackConeHalfAngle);

        int hit = gp.cChecker.checkEntityCone(attackCone, new Entity[]{gp.player});
        if (hit != 999 && !gp.player.invincible) {
            int damage = Math.max(1, attack - gp.player.defense);
            gp.player.onHitByEnemy(damage, worldX, worldY, Math.max(1, (attack + 1) / 2));
        }
    }

    @Override
    public void damageReaction() {
        isHurt = true;
        hurtTimer = hurtDuration;
    }

    /** Called once, the moment the death animation finishes. Override to set defeat flags, advance quests, trigger map transitions, etc. */
    protected void onDefeated() {}

    /** Prevents contact damage, bosses only hurt the player via their telegraphed attacks. */
    @Override
    public void checkCollision() {
        collisionOn = false;
        gp.cChecker.checkTile(this);
        gp.cChecker.checkObject(this, false);
        gp.cChecker.checkEntity(this, gp.npc);
        gp.cChecker.checkEntity(this, gp.monster);
        gp.cChecker.checkEntity(this, gp.iTile);
        gp.cChecker.checkPlayer(this);
    }

    @Override
    public void update() {
        if (tickKnockback()) return;

        // While this boss is the subject of an intro cutscene, freeze in a plain idle-down pose
        // instead of running its normal AI, the camera is showing it off, not fighting it yet.
        // Clears every flag currentSprite() would otherwise prioritize over idle (in case the boss
        // was mid-attack/hurt/evade/teleport at the exact moment the cutscene trigger fired).
        if (gp.bossIntroCutscene != null && gp.bossIntroCutscene.getBoss() == this) {
            onPath = false;
            direction = DIR_DOWN;
            attackingNow = false;
            isHurt = false;
            evadingNow = false;
            teleportPhase = TELEPORT_NONE;
            speed = defaultSpeed;
            advanceWalkOrIdle(false); // keep the idle animation looping while frozen for the cutscene
            return;
        }

        if (isHurt) {
            hurtTimer--;
            if (hurtTimer <= 0) isHurt = false;
        }

        checkPhase2Transition();
        updateSummons();

        if (evadingNow) {
            updateEvadeDash();
        } else if (teleportPhase != TELEPORT_NONE) {
            updateTeleport();
        } else {
            updateTeleportTimer();
            if (attackingNow) {
                updateAttack();
            } else {
                int prevX = worldX, prevY = worldY;
                setAction();
                boolean moving = (worldX != prevX || worldY != prevY);
                advanceWalkOrIdle(moving);
            }
        }

        if (invincible) {
            invincibleCounter++;
            if (invincibleCounter > invincibleDuration) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
        if (hitFlashCounter > 0) hitFlashCounter--;
    }

    /**
     * One-time phase-2 transition at phase2HealthFraction of max life: optional rename, optional
 * full heal, optional faster attacks, then (if summonMonsterId is set) starts periodic minion
     * summoning. All fields default to "off"; a subclass only needs to set the ones it wants.
     */
    private void checkPhase2Transition() {
        if (phase2Active || phase2HealthFraction <= 0f || dying) return;
        if (life > maxLife * phase2HealthFraction) return;

        phase2Active = true;
        if (phase2Name != null) name = phase2Name;
        if (phase2FullHeal) life = maxLife;
        attackCooldown = Math.max(1, Math.round(attackCooldown * phase2AttackCooldownMultiplier));
        if (summonMonsterId != null) summonTimer = randomInterval(summonIntervalMinTicks, summonIntervalMaxTicks);
        if (teleportIntervalMinTicks > 0) teleportTimer = randomInterval(teleportIntervalMinTicks, teleportIntervalMaxTicks);
    }

    private int randomInterval(int min, int max) {
        int span = Math.max(1, max - min);
        return min + bossRandom.nextInt(span);
    }

    private void updateSummons() {
        if (summonMonsterId == null || !phase2Active) return;
        liveSummons.removeIf(m -> m == null || !m.alive || m.dying);
        summonTimer--;
        if (summonTimer <= 0) {
            summonTimer = randomInterval(summonIntervalMinTicks, summonIntervalMaxTicks);
            if (liveSummons.size() < summonMaxAlive) spawnSummon();
        }
    }

    // Summons never land within this many tiles of the player (Chebyshev distance), even if that
    // spot is otherwise a valid ring tile around the boss, spawning right on top of the player
    // feels like a cheap surprise hit rather than a fair "something new joined the fight" moment.
    private static final int SUMMON_MIN_PLAYER_DIST_TILES = 2;

    /** Spawns summonMonsterId at a free tile near the boss (but not right on top of the player), in the first open gp.monster[] slot. */
    private void spawnSummon() {
        int slot = -1;
        for (int i = 0; i < gp.monster.length; i++) {
            if (gp.monster[i] == null) { slot = i; break; }
        }
        if (slot < 0) return;

        int bossCol = getCenterX() / gp.tileSize;
        int bossRow = getCenterY() / gp.tileSize;
        int playerCol = gp.player.getTileCol();
        int playerRow = gp.player.getTileRow();
        int[][] ring = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1},{2,0},{-2,0},{0,2},{0,-2}};
        for (int[] off : ring) {
            int col = bossCol + off[0];
            int row = bossRow + off[1];
            if (col < 0 || row < 0 || col >= gp.maxWorldCol || row >= gp.maxWorldRow) continue;
            if (Math.max(Math.abs(col - playerCol), Math.abs(row - playerRow)) < SUMMON_MIN_PLAYER_DIST_TILES) continue;
            gfx.geom.Rect probe = new gfx.geom.Rect(col * gp.tileSize + 12, row * gp.tileSize + 8, 40, 48);
            if (gp.cChecker.rectHitsCollision(probe)) continue;

            Entity summon = data.MonsterFactory.create(gp, summonMonsterId, col, row);
            if (summon == null) return;
            summon.exp = 0; // boss-summoned minions don't award XP on death, unlike naturally-spawned ones
            gp.monster[slot] = summon;
            liveSummons.add(summon);
            return;
        }
    }

    /**
     * Rolls evadeDashChance and, if it hits, dashes this boss away from the player instead of
 * taking the incoming hit, see Entity.tryDodgeIncomingHit(). Only usable once phase 2 has
     * started, dashFrames is loaded, and the boss isn't already mid-attack/evade/teleport.
     */
    @Override
    public boolean tryDodgeIncomingHit() {
        if (!phase2Active || evadeDashChance <= 0f || dashFrames == null) return false;
        if (attackingNow || evadingNow || teleportPhase != TELEPORT_NONE || dying) return false;
        if (bossRandom.nextFloat() >= evadeDashChance) return false;

        evadingNow = true;
        evadeDashTimer = evadeDashDurationTicks;
        spriteNum = 0;
        spriteCounter = 0;
        double away = Math.atan2(getCenterY() - gp.player.getCenterY(), getCenterX() - gp.player.getCenterX());
        evadeDashTargetX = getCenterX() + Math.cos(away) * gp.tileSize * evadeDashDistanceTiles;
        evadeDashTargetY = getCenterY() + Math.sin(away) * gp.tileSize * evadeDashDistanceTiles;
        return true;
    }

    private double evadeDashTargetX, evadeDashTargetY;

    private void updateEvadeDash() {
        evadeDashTimer--;
        moveFreelyToward(evadeDashTargetX, evadeDashTargetY);

        spriteCounter++;
        if (spriteCounter > animationFrameInterval) {
            spriteCounter = 0;
            if (spriteNum < frameCount(dashFrames, direction) - 1) spriteNum++;
        }

        if (evadeDashTimer <= 0) {
            evadingNow = false;
            spriteNum = 0;
        }
    }

    /** Ticks down to the next teleport once phase 2 is active; starts the vanish when it fires. */
    private void updateTeleportTimer() {
        if (teleportIntervalMinTicks <= 0 || !phase2Active || attackingNow) return;
        teleportTimer--;
        if (teleportTimer <= 0) {
            teleportPhase = TELEPORT_VANISHING;
            teleportFrameCounter = 0;
            onPath = false;
            speed = 0;
        }
    }

    /**
     * Teleport = deathFrames played forward in place (vanish), then a new spot is picked near the
 * player, then deathFrames played backward at the new spot (reappear), reusing the death
     * animation both ways instead of needing dedicated teleport art.
     */
    private void updateTeleport() {
        int maxFrame = frameCount(deathFrames, direction) - 1;

        if (teleportPhase == TELEPORT_VANISHING) {
            teleportFrameCounter++;
            if (teleportFrameCounter / 6 > maxFrame) {
                relocateNearPlayer();
                teleportPhase = TELEPORT_REAPPEARING;
                teleportFrameCounter = 0;
            }
        } else { // TELEPORT_REAPPEARING
            teleportFrameCounter++;
            if (teleportFrameCounter / 6 > maxFrame) {
                teleportPhase = TELEPORT_NONE;
                teleportTimer = randomInterval(teleportIntervalMinTicks, teleportIntervalMaxTicks);
            }
        }
    }

    /** Picks a random spot teleportMinDistanceTiles..teleportMaxDistanceTiles from the player and moves the boss there directly. */
    private void relocateNearPlayer() {
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = bossRandom.nextDouble() * Math.PI * 2;
            int dist = teleportMinDistanceTiles + bossRandom.nextInt(Math.max(1, teleportMaxDistanceTiles - teleportMinDistanceTiles + 1));
            int col = gp.player.getTileCol() + (int) Math.round(Math.cos(angle) * dist);
            int row = gp.player.getTileRow() + (int) Math.round(Math.sin(angle) * dist);
            if (col < 0 || row < 0 || col >= gp.maxWorldCol || row >= gp.maxWorldRow) continue;

            gfx.geom.Rect probe = new gfx.geom.Rect(col * gp.tileSize + solidArea.x, row * gp.tileSize + solidArea.y,
                    solidArea.width, solidArea.height);
            if (gp.cChecker.rectHitsCollision(probe)) continue;

            worldX = col * gp.tileSize;
            worldY = row * gp.tileSize;
            return;
        }
    }

    // Separate counter/frame index for the idle animation, so it loops independently of the walk
    // cycle's spriteNum (which has its own frame count and only advances while actually moving).
    private int idleSpriteCounter = 0;
    private int idleSpriteNum = 0;

    private void advanceWalkOrIdle(boolean moving) {
        if (moving) {
            spriteCounter++;
            if (spriteCounter > animationFrameInterval) {
                spriteNum++;
                if (spriteNum > walkFrameCount) spriteNum = 1;
                spriteCounter = 0;
            }
        } else {
            idleSpriteCounter++;
            if (idleSpriteCounter > animationFrameInterval) {
                idleSpriteCounter = 0;
                idleSpriteNum++;
                if (idleSpriteNum >= frameCount(idleFrames, direction)) idleSpriteNum = 0;
            }
        }
    }

    private Sprite currentSprite() {
        if (dying) {
            int frame = Math.min(dyingCounter / 8, frameCount(deathFrames, direction) - 1);
            return safeFrame(deathFrames, direction, frame);
        }
        if (teleportPhase != TELEPORT_NONE) {
            int maxFrame = frameCount(deathFrames, direction) - 1;
            int frame = Math.min(teleportFrameCounter / 6, maxFrame);
            // Vanishing plays the death animation forward; reappearing plays it backward, so the
            // boss looks like it's un-vanishing at the new spot instead of just popping in.
            if (teleportPhase == TELEPORT_REAPPEARING) frame = maxFrame - frame;
            return safeFrame(deathFrames, direction, frame);
        }
        if (evadingNow) {
            return safeFrame(dashFrames, direction, spriteNum);
        }
        if (attackingNow) {
            return safeFrame(attackFrames, direction, attackFrameIndex);
        }
        if (isHurt) {
            int frame = Math.min((hurtDuration - hurtTimer) / 4, frameCount(hurtFrames, direction) - 1);
            return safeFrame(hurtFrames, direction, frame);
        }
        if (onPath) {
            return safeFrame(walkFrames, direction, spriteNum - 1);
        }
        return safeFrame(idleFrames, direction, idleSpriteNum);
    }

    @Override
    public void draw(GdxRenderer g2) {
        int drawSize = (int) (gp.tileSize * spriteScale);
        int screenX = worldX - gp.getCamWorldX() + gp.player.screenX;
        int screenY = worldY - gp.getCamWorldY() + gp.player.screenY;

        if (worldX + drawSize < gp.getCamWorldX() - gp.player.screenX
                || worldX - drawSize > gp.getCamWorldX() + (gp.screenWidth - gp.player.screenX)
                || worldY + drawSize < gp.getCamWorldY() - gp.player.screenY
                || worldY - drawSize > gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY)) {
            return;
        }

        Sprite sprite = currentSprite();

        if (invincible) changeAlpha(g2, 0.4f);

        int drawX = screenX - (drawSize - gp.tileSize) / 2;
        int drawY = screenY - (drawSize - gp.tileSize);

        if (dying) {
            boolean wasAlive = alive;
            dyingAnimation(g2);
            if (wasAlive && !alive) onDefeated();
            if (sprite != null) g2.drawImage(sprite, drawX, drawY, drawSize, drawSize);
            changeAlpha(g2, 1f);
            return;
        }

        if (sprite != null) {
            g2.drawImage(sprite, drawX, drawY, drawSize, drawSize);
        }

        if (hitFlashCounter > 0 && sprite != null) {
            float flashAlpha = Math.min(1f, hitFlashCounter / 6f * 0.8f);
            g2.drawImageTinted(sprite, drawX, drawY, drawSize, drawSize, Color.WHITE, flashAlpha);
        }

        changeAlpha(g2, 1f);
    }

    /**
     * Drawn as a separate overlay pass (see RenderPipeline) after all world/depth-tile layers, not
 * inline inside draw(), draw() runs interleaved with Y-sorted depth tiles (tall foliage, walls,
     * overhangs that draw in front of entities standing behind them), so a bar emitted from inside it
     * could get painted over by a depth tile sorted after this boss. This bar is a fixed screen
     * overlay anyway (not tied to the boss's world position beyond which boss it belongs to), so it
     * belongs in a pass that always runs after everything else in the world.
     */
    public void drawBossHPBar(GdxRenderer g2) {
        int barWidth = gp.screenWidth / 2;
        int barHeight = 16;
        int barX = (gp.screenWidth - barWidth) / 2;
        int barY = 22;

        g2.setColor(new Color(0, 0, 0, 200));
        g2.fillRoundRect(barX - 4, barY - 4, barWidth + 8, barHeight + 8, 8, 8);
        g2.setColor(HP_BG);
        g2.fillRect(barX, barY, barWidth, barHeight);

        float hpRatio = Math.max(0f, (float) life / maxLife);
        g2.setColor(HP_FG);
        g2.fillRect(barX, barY, (int) (barWidth * hpRatio), barHeight);

        g2.setColor(HP_BORDER);
        g2.drawRoundRect(barX - 1, barY - 1, barWidth + 2, barHeight + 2, 4, 4);

        g2.setFont(NAME_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int textX = barX + (barWidth - fm.stringWidth(name)) / 2;
        g2.setColor(NAME_SHADOW);
        g2.drawString(name, textX + 1, barY - 7);
        g2.setColor(Color.WHITE);
        g2.drawString(name, textX, barY - 8);
    }

    private static Sprite safeFrame(Sprite[][] frames, int dir, int frame) {
        if (frames == null) return null;
        if (dir < 0 || dir >= frames.length || frames[dir] == null) dir = DIR_DOWN;
        if (dir < 0 || dir >= frames.length || frames[dir] == null) return null;
        if (frame < 0) frame = 0;
        if (frame >= frames[dir].length) frame = frames[dir].length - 1;
        return frames[dir][frame];
    }

    private static int frameCount(Sprite[][] frames, int dir) {
        if (frames == null) return 1;
        if (dir < 0 || dir >= frames.length || frames[dir] == null) dir = DIR_DOWN;
        if (dir < 0 || dir >= frames.length || frames[dir] == null) return 1;
        return Math.max(1, frames[dir].length);
    }
}

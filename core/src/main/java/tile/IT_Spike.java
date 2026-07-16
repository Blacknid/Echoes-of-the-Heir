package tile;

import entity.Entity;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/**
 * Animated spike hazard, sliced from a single 5-frame strip (Spike.png): frames 0-1 are the
 * retracted "idle" pose, 2-4 rise up to fully extended. Idles on frames 0-1, and the moment the
 * player steps on it, rises (whatever frame the idle loop was on -> frame 4, damaging the
 * player once), holds fully extended for a beat, then retracts by playing the same frames back
 * in reverse before returning to idling.
 *
 * Optionally locked up by an IT_SpikeTrigger sharing the same "lockId" property: while locked, it
 * stays fully extended (frames 3-4 looping) and solid, ignoring the normal hazard behavior, until
 * the trigger releases it (its boss is defeated).
 */
public class IT_Spike extends interactiveTile {

    private static final String SHEET_PATH = "/res/tiles/DUNGEON/Spike.png";
    private static final String BASE_PATH = "/res/tiles/DUNGEON/Spike_base.png";
    private static final int FRAME_COUNT = 5;
    private static final int IDLE_FRAME_A = 0;
    private static final int IDLE_FRAME_B = 1;
    private static final int GATE_FRAME_A = 3;
    private static final int GATE_FRAME_B = 4;

    private static final int IDLE_TICKS_PER_FRAME = 30;   // slow "breathing" idle
    private static final int RISE_TICKS_PER_FRAME = 6;    // slower, weightier rise when triggered
    private static final int HOLD_TICKS = 45;             // pause fully extended before retracting
    private static final int RETRACT_TICKS_PER_FRAME = 8; // easing back down, same frames in reverse
    private static final int GATE_TICKS_PER_FRAME = 40; // slower breathing loop while locked in a boss arena
    private static final int DAMAGE = 1;
    private static final int RETRIGGER_COOLDOWN = 30; // ticks before the same spike can hit again
    private static final float TRIGGER_RADIUS_FRACTION = 0.55f; // of tileSize; must be near the middle
    private static final int KNOCKBACK_POWER = 4; // clearly pushes the player off the tile

    private static Sprite[] frames;
    private static Sprite baseSprite;
    private static boolean baseLoadAttempted = false;
    private static final java.util.Random RNG = new java.util.Random();

    private enum Phase { IDLE, RISING, HOLDING, RETRACTING, LOCKED }

    public final String lockId;

    private int frameIndex = IDLE_FRAME_A;
    private int frameTicks = 0;
    private Phase phase = Phase.IDLE;
    private int cooldown = 0;
    private boolean locked = false;

    public IT_Spike(GamePanel gp, int col, int row, String lockId) {
        super(gp, col, row);
        this.lockId = lockId;
        loadFramesIfNeeded();
        collision = false;
    }

    /** Called by an IT_SpikeTrigger sharing this spike's lockId. */
    public void setLocked(boolean locked) {
        if (this.locked == locked) return;
        this.locked = locked;
        if (locked) {
            // Rise up normally instead of snapping straight to fully extended; updateRising()
            // detects the locked flag once it reaches the top and hands off to the idle-up loop.
            phase = Phase.RISING;
            frameTicks = 0;
            collision = true;
        } else {
            phase = Phase.RETRACTING;
            frameTicks = 0;
            // The normal hazard cycle clears collision in updateHolding()'s own hold->retract
            // transition; unlocking jumps straight from LOCKED to RETRACTING and skips that, so
            // without this the spike kept blocking movement even after visually retracting to idle.
            collision = false;
        }
    }

    private static void loadFramesIfNeeded() {
        if (frames == null) {
            Sprite sheet = ResourceCache.loadImageIfPresent(SHEET_PATH);
            frames = new Sprite[FRAME_COUNT];
            if (sheet != null) {
                int frameW = sheet.getWidth() / FRAME_COUNT;
                for (int i = 0; i < FRAME_COUNT; i++) {
                    frames[i] = sheet.getSubimage(i * frameW, 0, frameW, sheet.getHeight());
                }
            }
        }
        if (!baseLoadAttempted) {
            baseLoadAttempted = true;
            baseSprite = ResourceCache.loadImageIfPresent(BASE_PATH);
        }
    }

    @Override
    public void update() {
        super.update();

        if (cooldown > 0) cooldown--;

        switch (phase) {
            case IDLE -> { if (!locked) { updateIdle(); checkEntityStep(); } }
            case RISING -> updateRising();
            case HOLDING -> updateHolding();
            case RETRACTING -> updateRetracting();
            case LOCKED -> updateLocked();
        }
    }

    private void updateIdle() {
        frameTicks++;
        if (frameTicks >= IDLE_TICKS_PER_FRAME) {
            frameTicks = 0;
            frameIndex = (frameIndex == IDLE_FRAME_A) ? IDLE_FRAME_B : IDLE_FRAME_A;
        }
    }

    /** Player or any alive monster/boss standing near the tile's center triggers the spike. */
    private void checkEntityStep() {
        if (cooldown > 0) return;

        if (entityNearCenter(gp.player)) {
            triggerOn(gp.player);
            return;
        }
        for (Entity m : gp.monster) {
            if (m != null && m.alive && !m.dying && entityNearCenter(m)) {
                triggerOn(m);
                return;
            }
        }
    }

    private void triggerOn(Entity target) {
        phase = Phase.RISING;
        frameTicks = 0;
        // frameIndex keeps whatever idle frame (0 or 1) it was already showing, the rise just
        // continues from there instead of snapping back to frame 0.
        // While dashing (or otherwise invincible) the target still triggers the rise but takes no
        // damage, so dashing through is a safe way to trigger/cross them.
        if (!target.invincible) {
            // Knockback source is placed directly behind the target (opposite its current facing),
            // so it gets pushed onward the way it was already heading instead of away from the
            // tile's center.
            int srcX = target.worldX;
            int srcY = target.worldY;
            switch (target.direction) {
                case Entity.DIR_UP    -> srcY += gp.tileSize;
                case Entity.DIR_DOWN  -> srcY -= gp.tileSize;
                case Entity.DIR_LEFT  -> srcX += gp.tileSize;
                case Entity.DIR_RIGHT -> srcX -= gp.tileSize;
            }
            if (target == gp.player) {
                gp.player.onHitByEnemy(DAMAGE, srcX, srcY, KNOCKBACK_POWER, false);
            } else {
                damageEntity(target, srcX, srcY);
            }
        }
        cooldown = RETRIGGER_COOLDOWN;
    }

    /** Generic hit application for monsters/bosses (Player has its own richer onHitByEnemy juice). */
    private void damageEntity(Entity target, int srcX, int srcY) {
        target.life -= DAMAGE;
        target.invincible = true;
        target.invincibleCounter = 0;

        double dx = target.worldX - srcX;
        double dy = target.worldY - srcY;
        double dist = Math.hypot(dx, dy);
        if (dist < 0.01) { dx = 1; dy = 0; dist = 1; }
        target.knockBackVectorX = (int) Math.round(dx / dist * KNOCKBACK_POWER);
        target.knockBackVectorY = (int) Math.round(dy / dist * KNOCKBACK_POWER);
        target.knockBack = true; // arms Entity.tickKnockback(), without this the vectors are a no-op
    }

    private void updateRising() {
        frameTicks++;
        if (frameTicks >= RISE_TICKS_PER_FRAME) {
            frameTicks = 0;
            if (frameIndex < FRAME_COUNT - 1) {
                frameIndex++;
            } else if (locked) {
                // Reached full extension while locked: settle into the idle-up loop instead of
                // holding-then-retracting. Random starting frame/offset so spikes that all rose
                // at the same moment don't stay visually in lockstep afterward.
                frameIndex = RNG.nextBoolean() ? GATE_FRAME_A : GATE_FRAME_B;
                frameTicks = RNG.nextInt(GATE_TICKS_PER_FRAME);
                phase = Phase.LOCKED;
            } else {
                phase = Phase.HOLDING;
            }
        }
    }

    private void updateHolding() {
        // Fully extended and holding: solid, so a dash can't just pass through it.
        collision = true;
        frameTicks++;
        if (frameTicks >= HOLD_TICKS) {
            frameTicks = 0;
            phase = Phase.RETRACTING;
            collision = false;
        }
    }

    private void updateRetracting() {
        frameTicks++;
        if (frameTicks >= RETRACT_TICKS_PER_FRAME) {
            frameTicks = 0;
            if (frameIndex > IDLE_FRAME_A) {
                frameIndex--; // same frames, played back in reverse
            } else {
                phase = Phase.IDLE;
            }
        }
    }

    /** Locked up by an IT_SpikeTrigger: stays fully extended, looping the top 2 frames. */
    private void updateLocked() {
        frameTicks++;
        if (frameTicks >= GATE_TICKS_PER_FRAME) {
            frameTicks = 0;
            frameIndex = (frameIndex == GATE_FRAME_A) ? GATE_FRAME_B : GATE_FRAME_A;
        }
    }

    /** True once the entity's center gets close to the tile's center, not just at first touch. */
    private boolean entityNearCenter(Entity e) {
        double tileCenterX = worldX + gp.tileSize / 2.0;
        double tileCenterY = worldY + gp.tileSize / 2.0;
        double entityCenterX = e.worldX + e.solidArea.x + e.solidArea.width / 2.0;
        double entityCenterY = e.worldY + e.solidArea.y + e.solidArea.height / 2.0;
        double dist = Math.hypot(entityCenterX - tileCenterX, entityCenterY - tileCenterY);
        return dist <= gp.tileSize * TRIGGER_RADIUS_FRACTION;
    }

    /**
     * The pit/socket the spikes rise out of is drawn in the pre-entity ground-shadow pass (see
     * Entity.drawGroundShadowPass / RenderPipeline.drawGroundShadows) instead of inline here, so it
 * never takes part in Y-sort and always renders behind the player and every entity, only the
     * animated spikes themselves (in draw() below) Y-sort normally.
     */
    @Override
    public void drawGroundShadowPass(GdxRenderer g2) {
        if (baseSprite == null || offscreen()) return;
        g2.drawImage(baseSprite, screenX(), screenY(), gp.tileSize, gp.tileSize);
    }

    @Override
    public void draw(GdxRenderer g2) {
        if (frames == null || offscreen()) return;
        Sprite sprite = frames[frameIndex];
        if (sprite == null) return;
        // Spike.png's frames are native 32x64 (taller than the 32x32 base/tile) so the rising
        // spikes read as growing up out of the socket instead of being squashed into one tile.
        // Scaled up keeping that same aspect ratio, and bottom-aligned so the bottom 32x32 slice
        // (where the base socket art lives) always lines up exactly over the base tile.
        int drawW = gp.tileSize;
        int drawH = gp.tileSize * sprite.getHeight() / sprite.getWidth();
        int drawX = screenX();
        int drawY = screenY() + gp.tileSize - drawH;
        g2.drawImage(sprite, drawX, drawY, drawW, drawH);
    }

    private int screenX() { return worldX - gp.getCamWorldX() + gp.player.screenX; }
    private int screenY() { return worldY - gp.getCamWorldY() + gp.player.screenY; }

    private boolean offscreen() {
        return worldX + gp.tileSize <= gp.getCamWorldX() - gp.player.screenX ||
               worldX - gp.tileSize >= gp.getCamWorldX() + (gp.screenWidth - gp.player.screenX) ||
               worldY + gp.tileSize <= gp.getCamWorldY() - gp.player.screenY ||
               worldY - gp.tileSize >= gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY);
    }

    /**
     * Invisible detection rectangle (placed from a Tiled rectangle object, like IT_Gate): the
     * moment the player enters it, every IT_Spike sharing this trigger's "lockId" locks up fully
     * extended and solid, blocking the way. Stays locked until the boss identified by "bossId"
     * (1-4, same convention as BossMonster/gp.bossNDefeated) is defeated, at which point they
     * release. Kept nested here since it exists purely to drive IT_Spike and nothing else.
     */
    public static class SpikeTrigger extends interactiveTile {

        private final String lockId;
        private final int bossId;
        private final int drawW, drawH;

        private boolean armed = false; // true once the player has entered and spikes are locked

        public SpikeTrigger(GamePanel gp, int worldX, int worldY, int drawW, int drawH,
                             String lockId, int bossId) {
            super(gp, worldX / gp.tileSize, worldY / gp.tileSize);
            this.worldX = worldX;
            this.worldY = worldY;
            this.drawW = drawW;
            this.drawH = drawH;
            this.lockId = lockId;
            this.bossId = bossId;
            collision = false;
        }

        @Override
        public void update() {
            super.update();

            if (bossDefeated()) {
                if (armed) {
                    armed = false;
                    setSpikesLocked(false);
                }
                return;
            }

            if (!armed && playerOverlaps()) {
                armed = true;
                setSpikesLocked(true);
            }
        }

        private void setSpikesLocked(boolean locked) {
            for (interactiveTile t : gp.iTile) {
                if (t instanceof IT_Spike spike && spike.lockId != null && spike.lockId.equals(lockId)) {
                    spike.setLocked(locked);
                }
            }
        }

        private boolean bossDefeated() {
            return switch (bossId) {
                case 1 -> gp.boss1Defeated;
                case 2 -> gp.boss2Defeated;
                case 3 -> gp.boss3Defeated;
                case 4 -> gp.boss4Defeated;
                default -> false;
            };
        }

        private boolean playerOverlaps() {
            Entity p = gp.player;
            int px = p.worldX + p.solidArea.x;
            int py = p.worldY + p.solidArea.y;
            int pw = p.solidArea.width;
            int ph = p.solidArea.height;
            return px < worldX + drawW && px + pw > worldX &&
                   py < worldY + drawH && py + ph > worldY;
        }

        // Invisible: no draw(), purely a logic trigger.
        @Override
        public void draw(GdxRenderer g2) {
        }
    }
}

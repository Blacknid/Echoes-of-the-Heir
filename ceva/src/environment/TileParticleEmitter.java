package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.util.Random;
import main.GamePanel;

/**
 * Lightweight particle system for footstep / movement effects on different tile types.
 * Grass → tiny green blades flying out, Stone → grey dust puffs, Sand → tan specks, etc.
 * Self-contained: owns its own pool, update, and draw.
 */
public class TileParticleEmitter {

    // ─── Tile type constants (returned by TileManager.getTileType) ───
    public static final int TILE_NONE  = 0;
    public static final int TILE_GRASS = 1;
    public static final int TILE_STONE = 2;
    public static final int TILE_WATER = 3;

    // ─── Generic particle API types ───
    public enum ParticleType {
        FOOTSTEP_GRASS,
        FOOTSTEP_STONE
    }

    // ─── Pool ───
    private static final int MAX_PARTICLES = 120;
    private final FP[] particles = new FP[MAX_PARTICLES];
    private final int[] activeIndices = new int[MAX_PARTICLES];
    private final int[] indexInActive = new int[MAX_PARTICLES];
    private int activeCount = 0;
    private final int[] freeStack = new int[MAX_PARTICLES];
    private int freeTop = -1;

    // ─── Y-sorted merge-draw support ───
    private final int[] sortedIndices = new int[MAX_PARTICLES];
    private int sortedCount = 0;

    // ─── Cached alpha composites ───
    private static final int ALPHA_LEVELS = 32;
    private final AlphaComposite[] alphaCompositeCache = new AlphaComposite[ALPHA_LEVELS + 1];

    private final GamePanel gp;
    private final Random rng = new Random();

    // ─── Emit throttle (frames between spawns per entity) ───
    private static final int EMIT_INTERVAL = 6;

    // ─── Adaptive LOD ───
    private static final int LOD_MED_FPS = 52;
    private static final int LOD_LOW_FPS = 42;

    private final ParticlePreset grassPreset = new ParticlePreset(
        GRASS_COLORS, 2, 3, 18, 27, 0.04f, 1.2f, 0.8f, -0.30f
    );
    private final ParticlePreset stonePreset = new ParticlePreset(
        STONE_COLORS, 1, 2, 14, 21, 0.06f, 1.2f, 0.8f, -0.30f
    );

    public TileParticleEmitter(GamePanel gp) {
        this.gp = gp;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            particles[i] = new FP();
            indexInActive[i] = -1;
            freeStack[i] = i;
            float alpha = (float) i / ALPHA_LEVELS;
            if (i <= ALPHA_LEVELS) {
                alphaCompositeCache[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
            }
        }
        freeTop = MAX_PARTICLES - 1;
    }

    // ═══════════════════════════════════════════════════════════
    //  EMIT — call when an entity moves on a tile
    //  Returns the internal throttle so callers can skip repeat calls.
    // ═══════════════════════════════════════════════════════════

    /**
     * Backward-compatible API used by existing entity/player calls.
     */
    public void emit(int worldX, int worldY, int tileType, String direction) {
        emitFootstep(worldX, worldY, tileType, direction);
    }

    /**
     * User-friendly high-level helper for terrain footsteps.
     */
    public void emitFootstep(int worldX, int worldY, int tileType, String direction) {
        ParticleType type = particleTypeFromTile(tileType);
        if (type == null) return;

        int count = adjustSpawnCount(2 + rng.nextInt(2)); // base 2-3 particles
        emit(type, worldX, worldY, direction, count);
    }

    /**
     * Generic emission API for future effects (impact, sparks, snow, etc).
     */
    public void emit(ParticleType type, int worldX, int worldY, String direction, int count) {
        if (type == null || count <= 0) return;
        for (int i = 0; i < count; i++) {
            spawnOne(type, worldX, worldY, direction);
        }
    }

    private ParticleType particleTypeFromTile(int tileType) {
        return switch (tileType) {
            case TILE_GRASS -> ParticleType.FOOTSTEP_GRASS;
            case TILE_STONE -> ParticleType.FOOTSTEP_STONE;
            default -> null;
        };
    }

    private int adjustSpawnCount(int baseCount) {
        int fps = gp.currentFPS;
        if (fps <= 0) return baseCount;
        if (fps < LOD_LOW_FPS) {
            return Math.max(1, baseCount - 2);
        }
        if (fps < LOD_MED_FPS) {
            return Math.max(1, baseCount - 1);
        }
        return baseCount;
    }

    private void spawnOne(ParticleType type, int worldX, int worldY, String direction) {
        int particleIndex = allocateParticleSlot();
        FP p = particles[particleIndex];
        ParticlePreset preset = getPreset(type);

        // Foot position with slight random spread
        int halfTile = gp.tileSize / 2;
        p.worldX = worldX + halfTile + rng.nextInt(13) - 6;
        p.worldY = worldY + gp.tileSize - 4 + rng.nextInt(5); // near feet
        p.sortY = worldY; // entity's top worldY — used for depth sorting with entities

        // Velocity: particles kick opposite to movement + random spread
        float baseVX = 0, baseVY = 0;
        switch (direction) {
            case "up":    baseVY =  0.6f; break;
            case "down":  baseVY = -0.5f; break;
            case "left":  baseVX =  0.6f; break;
            case "right": baseVX = -0.6f; break;
        }
        p.vx = baseVX + (rng.nextFloat() - 0.5f) * preset.velocitySpreadX;
        p.vy = baseVY + (rng.nextFloat() - 0.5f) * preset.velocitySpreadY + preset.verticalBias;

        p.color = preset.palette[rng.nextInt(preset.palette.length)];
        p.size = randomRangeInclusive(preset.minSize, preset.maxSize);
        p.maxLife = randomRangeInclusive(preset.minLife, preset.maxLife);
        p.gravity = preset.gravity;

        p.life = p.maxLife;
        p.alive = true;
        activateParticle(particleIndex);
    }

    private int randomRangeInclusive(int min, int max) {
        if (max <= min) return min;
        return min + rng.nextInt(max - min + 1);
    }

    private ParticlePreset getPreset(ParticleType type) {
        return switch (type) {
            case FOOTSTEP_GRASS -> grassPreset;
            case FOOTSTEP_STONE -> stonePreset;
        };
    }

    private int allocateParticleSlot() {
        if (freeTop >= 0) {
            return freeStack[freeTop--];
        }

        if (activeCount > 0) {
            int reclaimedIndex = activeIndices[0];
            deactivateParticle(reclaimedIndex);
            return freeStack[freeTop--];
        }

        return 0;
    }

    private void activateParticle(int particleIndex) {
        if (indexInActive[particleIndex] != -1) {
            return;
        }
        activeIndices[activeCount] = particleIndex;
        indexInActive[particleIndex] = activeCount;
        activeCount++;
    }

    private void deactivateParticle(int particleIndex) {
        int activePos = indexInActive[particleIndex];
        if (activePos == -1) {
            return;
        }

        int lastPos = activeCount - 1;
        int lastParticleIndex = activeIndices[lastPos];
        activeIndices[activePos] = lastParticleIndex;
        indexInActive[lastParticleIndex] = activePos;
        activeCount--;
        indexInActive[particleIndex] = -1;

        particles[particleIndex].alive = false;
        freeStack[++freeTop] = particleIndex;
    }

    // ═══════════════════════════════════════════════════════════
    //  UPDATE
    // ═══════════════════════════════════════════════════════════

    public void update() {
        for (int i = activeCount - 1; i >= 0; i--) {
            int particleIndex = activeIndices[i];
            FP p = particles[particleIndex];

            p.worldX += p.vx;
            p.worldY += p.vy;
            p.vy += p.gravity; // gravity pulls down
            p.life--;

            if (p.life <= 0) {
                deactivateParticle(particleIndex);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DRAW  (legacy — draws all particles in one flat pass)
    // ═══════════════════════════════════════════════════════════

    public void draw(Graphics2D g2) {
        Composite originalComp = g2.getComposite();

        for (int i = 0; i < activeCount; i++) {
            drawParticle(g2, particles[activeIndices[i]]);
        }

        g2.setComposite(originalComp);
    }

    // ═══════════════════════════════════════════════════════════
    //  Y-SORTED MERGE-DRAW  (interleave with entity render list)
    // ═══════════════════════════════════════════════════════════

    /**
     * Collect alive particles, sort by sortY. Call once per frame before merge-drawing.
     * @return number of sorted active particles
     */
    public int prepareSortedIndices() {
        sortedCount = activeCount;
        System.arraycopy(activeIndices, 0, sortedIndices, 0, sortedCount);
        // Insertion sort — fast for small N, zero allocation
        for (int i = 1; i < sortedCount; i++) {
            int key = sortedIndices[i];
            float keyY = particles[key].sortY;
            int j = i - 1;
            while (j >= 0 && particles[sortedIndices[j]].sortY > keyY) {
                sortedIndices[j + 1] = sortedIndices[j];
                j--;
            }
            sortedIndices[j + 1] = key;
        }
        return sortedCount;
    }

    /** Get the sortY of the particle at the given sorted position. */
    public float getSortY(int sortedPosition) {
        return particles[sortedIndices[sortedPosition]].sortY;
    }

    /** Draw a single particle at the given sorted position. */
    public void drawSingle(Graphics2D g2, int sortedPosition) {
        FP p = particles[sortedIndices[sortedPosition]];
        if (!p.alive) return;
        drawParticle(g2, p);
    }

    private void drawParticle(Graphics2D g2, FP p) {
        int sx = (int) p.worldX - gp.player.worldX + gp.player.screenX;
        int sy = (int) p.worldY - gp.player.worldY + gp.player.screenY;

        if (sx + p.size < 0 || sy + p.size < 0 || sx >= gp.screenWidth || sy >= gp.screenHeight) {
            return;
        }

        float alpha = 1.0f;
        float fadeRatio = (float) p.life / p.maxLife;
        if (fadeRatio < 0.35f) {
            alpha = fadeRatio / 0.35f;
        }
        alpha = Math.max(0.0f, Math.min(alpha, 1.0f));

        g2.setComposite(getCachedComposite(alpha));
        g2.setColor(p.color);
        g2.fillRect(sx, sy, p.size, p.size);
    }

    private AlphaComposite getCachedComposite(float alpha) {
        int bucket = (int) (alpha * ALPHA_LEVELS + 0.5f);
        if (bucket < 0) bucket = 0;
        if (bucket > ALPHA_LEVELS) bucket = ALPHA_LEVELS;
        return alphaCompositeCache[bucket];
    }

    // ═══════════════════════════════════════════════════════════
    //  EMIT INTERVAL helper — entities track their own counter
    // ═══════════════════════════════════════════════════════════

    public int getEmitInterval() {
        return EMIT_INTERVAL;
    }

    public int getActiveCount() {
        return activeCount;
    }

    // ═══════════════════════════════════════════════════════════
    //  COLOR PALETTES
    // ═══════════════════════════════════════════════════════════

    private static final Color[] GRASS_COLORS = {
        new Color( 76, 153,  0),   // dark green
        new Color(102, 178, 51),   // mid green
        new Color(128, 204, 77),   // light green
        new Color(110, 170, 60),   // olive green
        new Color( 85, 140, 30),   // forest green
    };

    private static final Color[] STONE_COLORS = {
        new Color(160, 155, 145),  // warm grey
        new Color(140, 135, 130),  // darker grey
        new Color(180, 175, 165),  // light grey
        new Color(120, 115, 110),  // dark stone
    };

    // ═══════════════════════════════════════════════════════════
    //  Inner particle struct  (Footstep Particle)
    // ═══════════════════════════════════════════════════════════

    private static class FP {
        boolean alive;
        float worldX, worldY;
        float sortY;  // entity's worldY at spawn time — used for depth sorting
        float vx, vy;
        float gravity;
        Color color;
        int size;
        int life, maxLife;
    }

    private static class ParticlePreset {
        final Color[] palette;
        final int minSize;
        final int maxSize;
        final int minLife;
        final int maxLife;
        final float gravity;
        final float velocitySpreadX;
        final float velocitySpreadY;
        final float verticalBias;

        ParticlePreset(
            Color[] palette,
            int minSize,
            int maxSize,
            int minLife,
            int maxLife,
            float gravity,
            float velocitySpreadX,
            float velocitySpreadY,
            float verticalBias
        ) {
            this.palette = palette;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.minLife = minLife;
            this.maxLife = maxLife;
            this.gravity = gravity;
            this.velocitySpreadX = velocitySpreadX;
            this.velocitySpreadY = velocitySpreadY;
            this.verticalBias = verticalBias;
        }
    }
}

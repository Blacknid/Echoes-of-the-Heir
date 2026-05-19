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

    public static final int TILE_NONE  = 0;
    public static final int TILE_GRASS = 1;
    public static final int TILE_STONE = 2;
    public static final int TILE_WATER = 3;

    public enum ParticleType {
        FOOTSTEP_GRASS,
        FOOTSTEP_STONE,
        FOOTSTEP_WATER,
        LEAF_FALL
    }

    private static final int MAX_PARTICLES = 200;
    private final FP[] particles = new FP[MAX_PARTICLES];
    private final int[] activeIndices = new int[MAX_PARTICLES];
    private final int[] indexInActive = new int[MAX_PARTICLES];
    private int activeCount = 0;
    private final int[] freeStack = new int[MAX_PARTICLES];
    private int freeTop = -1;

    private final int[] sortedIndices = new int[MAX_PARTICLES];
    private int sortedCount = 0;

    private static final int ALPHA_LEVELS = 32;
    private final AlphaComposite[] alphaCompositeCache = new AlphaComposite[ALPHA_LEVELS + 1];

    private final GamePanel gp;
    private final Random rng = new Random();

    private static final int EMIT_INTERVAL = 6;

    private static final int LOD_MED_FPS = 52;
    private static final int LOD_LOW_FPS = 42;

    private final ParticlePreset grassPreset = new ParticlePreset(
        GRASS_COLORS, 2, 3, 18, 27, 0.04f, 1.2f, 0.8f, -0.30f
    );
    private final ParticlePreset stonePreset = new ParticlePreset(
        STONE_COLORS, 1, 2, 14, 21, 0.06f, 1.2f, 0.8f, -0.30f
    );
    private final ParticlePreset waterPreset = new ParticlePreset(
        WATER_COLORS, 1, 2, 10, 18, 0.10f, 1.6f, 1.4f, -0.55f
    );
    private final ParticlePreset leafPreset = new ParticlePreset(
        LEAF_COLORS, 2, 4, 80, 140, 0.012f, 0.6f, 0.3f, 0.15f
    );

    private int leafSpawnTimer = 0;
    private static final int LEAF_SPAWN_INTERVAL = 8; // frames between leaf spawn attempts

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

    /**
     * Emit footstep particles. direction uses Entity.DIR_DOWN/LEFT/RIGHT/UP constants.
     */
    public void emit(int worldX, int worldY, int tileType, int direction) {
        emitFootstep(worldX, worldY, tileType, direction);
    }

    /**
     * User-friendly high-level helper for terrain footsteps.
     */
    public void emitFootstep(int worldX, int worldY, int tileType, int direction) {
        ParticleType type = particleTypeFromTile(tileType);
        if (type == null) return;

        int count = adjustSpawnCount(2 + rng.nextInt(2)); // base 2-3 particles
        emit(type, worldX, worldY, direction, count);
    }

    /**
     * Generic emission API for future effects (impact, sparks, snow, etc).
     */
    public void emit(ParticleType type, int worldX, int worldY, int direction, int count) {
        if (type == null || count <= 0) return;
        for (int i = 0; i < count; i++) {
            spawnOne(type, worldX, worldY, direction);
        }
    }

    private ParticleType particleTypeFromTile(int tileType) {
        return switch (tileType) {
            case TILE_GRASS -> ParticleType.FOOTSTEP_GRASS;
            case TILE_STONE -> ParticleType.FOOTSTEP_STONE;
            case TILE_WATER -> ParticleType.FOOTSTEP_WATER;
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

    private void spawnOne(ParticleType type, int worldX, int worldY, int direction) {
        int particleIndex = allocateParticleSlot();
        FP p = particles[particleIndex];
        ParticlePreset preset = getPreset(type);

        if (type == ParticleType.LEAF_FALL) {
            // Leaf starts at random position within the tree tile area
            p.worldX = worldX + rng.nextInt(gp.tileSize);
            p.worldY = worldY + rng.nextInt(gp.tileSize / 2); // upper half of tree
            p.sortY = worldY;

            // Gentle drifting motion — sway left/right, fall slowly
            p.vx = (rng.nextFloat() - 0.5f) * 0.5f;
            p.vy = 0.15f + rng.nextFloat() * 0.25f; // gentle fall
            p.swayAmplitude = 0.3f + rng.nextFloat() * 0.4f;
            p.swaySpeed = 0.04f + rng.nextFloat() * 0.03f;
            p.swayPhase = rng.nextFloat() * 6.28f; // random start phase
        } else {
            // Foot position with slight random spread
            int halfTile = gp.tileSize / 2;
            p.worldX = worldX + halfTile + rng.nextInt(13) - 6;
            p.worldY = worldY + gp.tileSize - 4 + rng.nextInt(5); // near feet
            p.sortY = worldY;

            // Velocity: particles kick opposite to movement + random spread
            float baseVX = 0, baseVY = 0;
            switch (direction) {
                case entity.Entity.DIR_UP:    baseVY =  0.6f; break;
                case entity.Entity.DIR_DOWN:  baseVY = -0.5f; break;
                case entity.Entity.DIR_LEFT:  baseVX =  0.6f; break;
                case entity.Entity.DIR_RIGHT: baseVX = -0.6f; break;
            }
            p.vx = baseVX + (rng.nextFloat() - 0.5f) * preset.velocitySpreadX;
            p.vy = baseVY + (rng.nextFloat() - 0.5f) * preset.velocitySpreadY + preset.verticalBias;
            p.swayAmplitude = 0;
        }

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
            case FOOTSTEP_WATER -> waterPreset;
            case LEAF_FALL -> leafPreset;
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
        // OPTIMIZATION: clear sort-membership flag so the next prepareSortedIndices()
        // knows this slot was removed and re-adds it when reused.
        particles[particleIndex].inSortedList = false;
        freeStack[++freeTop] = particleIndex;
    }

    public void update() {
        for (int i = activeCount - 1; i >= 0; i--) {
            int particleIndex = activeIndices[i];
            FP p = particles[particleIndex];

            // Apply sway for leaf particles (fastSin LUT — no native Math.sin call)
            if (p.swayAmplitude > 0) {
                p.swayPhase += p.swaySpeed;
                p.vx += MapShaderManager.fastSin(p.swayPhase) * p.swayAmplitude * 0.05f;
            }

            p.worldX += p.vx;
            p.worldY += p.vy;
            p.vy += p.gravity;
            // Update sortY to follow particle as it falls (keeps persistent sort close to sorted)
            p.sortY = p.worldY;
            p.life--;

            if (p.life <= 0) {
                deactivateParticle(particleIndex);
            }
        }

        // Ambient tree leaf spawning
        leafSpawnTimer++;
        if (leafSpawnTimer >= LEAF_SPAWN_INTERVAL) {
            leafSpawnTimer = 0;
            spawnAmbientLeaves();
        }
    }

    /**
     * Picks random visible tree tiles and spawns falling leaf particles from them.
     */
    private void spawnAmbientLeaves() {
        // Find the "Trees" layer index
        int treesLayerIndex = -1;
        for (int l = 0; l < gp.tileM.layerNames.size(); l++) {
            if (gp.tileM.layerNames.get(l).equalsIgnoreCase("Trees")) {
                treesLayerIndex = l;
                break;
            }
        }
        if (treesLayerIndex < 0 || treesLayerIndex >= gp.tileM.mapLayers.size()) return;

        int[][] treeLayer = gp.tileM.mapLayers.get(treesLayerIndex);

        // Calculate visible tile range
        int cameraX = gp.player.worldX - gp.player.screenX;
        int cameraY = gp.player.worldY - gp.player.screenY;
        int minCol = Math.max(0, cameraX / gp.tileSize - 1);
        int maxCol = Math.min(gp.maxWorldCol - 1, (cameraX + gp.screenWidth) / gp.tileSize + 1);
        int minRow = Math.max(0, cameraY / gp.tileSize - 1);
        int maxRow = Math.min(gp.maxWorldRow - 1, (cameraY + gp.screenHeight) / gp.tileSize + 1);

        // Try a few random visible positions for tree tiles
        int attempts = 3;
        for (int a = 0; a < attempts; a++) {
            int col = minCol + rng.nextInt(Math.max(1, maxCol - minCol + 1));
            int row = minRow + rng.nextInt(Math.max(1, maxRow - minRow + 1));
            if (col >= 0 && col < gp.maxWorldCol && row >= 0 && row < gp.maxWorldRow) {
                int gid = treeLayer[col][row];
                if (gid != 0) {
                    // This tile has a tree — spawn a leaf
                    int worldX = col * gp.tileSize;
                    int worldY = row * gp.tileSize;
                    spawnOne(ParticleType.LEAF_FALL, worldX, worldY, entity.Entity.DIR_DOWN);
                }
            }
        }
    }

    public void draw(Graphics2D g2) {
        Composite originalComp = g2.getComposite();

        for (int i = 0; i < activeCount; i++) {
            drawParticle(g2, particles[activeIndices[i]]);
        }

        g2.setComposite(originalComp);
    }

    /**
     * Collect alive particles, sort by sortY. Call once per frame before merge-drawing.
     *
     * OPTIMIZATION: The sorted list is preserved across frames. Each call:
     *   1. Compacts dead entries out (O(n))
     *   2. Appends newly-activated particles (O(new))
     *   3. Insertion-sorts — because particles drift only a few pixels per frame,
     *      the list is almost-sorted from last frame, so insertion sort runs in
     *      effectively O(n) instead of O(n²) on random data.
     *
     * @return number of sorted active particles
     */
    public int prepareSortedIndices() {
        int write = 0;
        for (int read = 0; read < sortedCount; read++) {
            int idx = sortedIndices[read];
            if (particles[idx].alive) {
                sortedIndices[write++] = idx;
            } else {
                particles[idx].inSortedList = false;
            }
        }
        sortedCount = write;

        for (int i = 0; i < activeCount; i++) {
            int idx = activeIndices[i];
            if (!particles[idx].inSortedList) {
                sortedIndices[sortedCount++] = idx;
                particles[idx].inSortedList = true;
            }
        }

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

    public int getEmitInterval() {
        return EMIT_INTERVAL;
    }

    public int getActiveCount() {
        return activeCount;
    }

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

    private static final Color[] WATER_COLORS = {
        new Color(110, 190, 230, 220),  // pale sky blue
        new Color(160, 215, 245, 200),  // light blue
        new Color(220, 240, 255, 180),  // near-white spray
        new Color( 80, 170, 220, 210),  // deeper blue
    };

    private static final Color[] LEAF_COLORS = {
        new Color( 60, 130, 20),   // deep green
        new Color( 90, 160, 40),   // fresh green
        new Color(120, 170, 50),   // light green
        new Color(150, 140, 30),   // yellow-green
        new Color(170, 120, 20),   // orange-brown
        new Color(140, 100, 30),   // autumn brown
    };

    private static class FP {
        boolean alive;
        // OPTIMIZATION: persistent-sort membership flag (see prepareSortedIndices)
        boolean inSortedList;
        float worldX, worldY;
        float sortY;
        float vx, vy;
        float gravity;
        Color color;
        int size;
        int life, maxLife;
        // Leaf sway
        float swayAmplitude;
        float swaySpeed;
        float swayPhase;
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

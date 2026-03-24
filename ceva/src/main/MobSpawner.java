package main;

import java.awt.Rectangle;
import java.util.Random;
import entity.Entity;
import environment.EnvironmentManager;
import monster.MON_monster;
import monster.MON_SkeletonArcher;

/**
 * Periodically spawns monsters based on time of day and weather.
 * Day: Mummies (MON_monster)
 * Night / Storm: Skeleton Archers (MON_SkeletonArcher)
 */
public class MobSpawner {

    GamePanel gp;
    private final Random random = new Random();

    // Spawn timing
    private int spawnTimer = 0;
    private static final int SPAWN_INTERVAL_DAY   = 600;  // 10 seconds at 60 FPS
    private static final int SPAWN_INTERVAL_NIGHT = 360;  // 6 seconds (more frequent at night)

    // Limits
    private static final int MAX_SPAWNED = 20;            // total monster array size
    private static final int MIN_SPAWN_DIST = 12;         // min tiles from player
    private static final int MAX_SPAWN_DIST = 25;         // max tiles from player
    private static final int MAX_PLACEMENT_ATTEMPTS = 30;  // tries before giving up

    // Reusable rectangle for collision checks
    private final Rectangle spawnRect = new Rectangle();

    public MobSpawner(GamePanel gp) {
        this.gp = gp;
    }

    public void update() {
        // Only spawn on the main map
        if (!"harta".equals(gp.currentMapId)) return;

        spawnTimer++;

        boolean isNightOrStorm = isNightOrStorm();
        int interval = isNightOrStorm ? SPAWN_INTERVAL_NIGHT : SPAWN_INTERVAL_DAY;

        if (spawnTimer >= interval) {
            spawnTimer = 0;
            trySpawn(isNightOrStorm);
        }
    }

    private boolean isNightOrStorm() {
        EnvironmentManager em = gp.eManager;
        boolean nightTime = (em.dayState == em.night || em.dayState == em.dusk);
        boolean storming = (em.weatherState == EnvironmentManager.WEATHER_STORM && em.weatherIntensity > 0.5f);
        return nightTime || storming;
    }

    private void trySpawn(boolean nightMode) {
        // Find a free slot
        int slot = findFreeSlot();
        if (slot < 0) return; // array full

        // Find a valid position off-screen but near the player
        int[] pos = findSpawnPosition();
        if (pos == null) return; // couldn't find valid spot

        int col = pos[0];
        int row = pos[1];

        if (nightMode) {
            // Night / Storm: Skeleton Archers
            gp.monster[slot] = new MON_SkeletonArcher(gp, col, row);
        } else {
            // Day: Mummies
            gp.monster[slot] = new MON_monster(gp, col, row);
        }
    }

    private int findFreeSlot() {
        for (int i = 0; i < gp.monster.length; i++) {
            if (gp.monster[i] == null) return i;
        }
        return -1;
    }

    /**
     * Picks a random tile position that is:
     * - Within MIN_SPAWN_DIST..MAX_SPAWN_DIST tiles from the player
     * - Off-screen (not visible to the player)
     * - Not colliding with any collision rectangles
     * - Within map bounds
     */
    private int[] findSpawnPosition() {
        int playerCol = gp.player.worldX / gp.tileSize;
        int playerRow = gp.player.worldY / gp.tileSize;

        // Screen bounds in tiles (to avoid spawning on-screen)
        int halfScreenCols = gp.maxScreenCol / 2 + 1;
        int halfScreenRows = gp.maxScreenRow / 2 + 1;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            // Random offset from player
            int offsetCol = MIN_SPAWN_DIST + random.nextInt(MAX_SPAWN_DIST - MIN_SPAWN_DIST + 1);
            int offsetRow = MIN_SPAWN_DIST + random.nextInt(MAX_SPAWN_DIST - MIN_SPAWN_DIST + 1);
            if (random.nextBoolean()) offsetCol = -offsetCol;
            if (random.nextBoolean()) offsetRow = -offsetRow;

            int col = playerCol + offsetCol;
            int row = playerRow + offsetRow;

            // Map bounds check
            if (col < 2 || col >= gp.maxWorldCol - 2 || row < 2 || row >= gp.maxWorldRow - 2) continue;

            // Must be off-screen
            int dcol = Math.abs(col - playerCol);
            int drow = Math.abs(row - playerRow);
            if (dcol < halfScreenCols && drow < halfScreenRows) continue;

            // Collision check: see if a tile-sized rect at this position overlaps any collision rect
            int worldX = col * gp.tileSize;
            int worldY = row * gp.tileSize;
            spawnRect.setBounds(worldX + 12, worldY + 8, 40, 48); // match monster solid area

            if (!collidesWithTerrain(spawnRect)) {
                return new int[]{col, row};
            }
        }
        return null; // failed to find a valid spot
    }

    private boolean collidesWithTerrain(Rectangle rect) {
        // Check tile-layer collision (only if configured)
        if (!gp.tileM.collisionTileLayers.isEmpty()) {
            int ts = gp.tileSize;
            int leftCol   = rect.x / ts;
            int rightCol  = (rect.x + rect.width) / ts;
            int topRow    = rect.y / ts;
            int bottomRow = (rect.y + rect.height) / ts;
            for (int row = topRow; row <= bottomRow; row++) {
                for (int col = leftCol; col <= rightCol; col++) {
                    if (gp.tileM.isTileBlocking(col, row)) return true;
                }
            }
        }
        // Check shape collision (rectangles, rotated rects, polygons, ellipses)
        for (java.awt.Shape s : gp.tileM.collisionShapes) {
            if (s.intersects(rect)) return true;
        }
        return false;
    }
}

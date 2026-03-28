package map;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Random;

import environment.EnvironmentManager;
import main.GamePanel;
import monster.MON_SkeletonArcher;
import monster.MON_monster;

/**
 * Periodically spawns monsters inside Tiled-defined MobSpawnerZone rectangles.
 * Only active on maps that explicitly have at least one MobSpawnerZone.
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
    private static final int MAX_PLACEMENT_ATTEMPTS = 30;  // tries before giving up

    /** Rectangles (world-pixel) registered from MobSpawnerZone objects in Tiled. */
    public final ArrayList<Rectangle> spawnZones = new ArrayList<>();

    // Reusable rectangle for collision checks
    private final Rectangle spawnRect = new Rectangle();

    public MobSpawner(GamePanel gp) {
        this.gp = gp;
    }

    /** Remove all registered spawn zones (called on map change). */
    public void clearZones() {
        spawnZones.clear();
    }

    /** Register a spawn zone rectangle (world pixels). Called by MapObjectLoader. */
    public void addZone(int worldX, int worldY, int w, int h) {
        spawnZones.add(new Rectangle(worldX, worldY, w, h));
        System.out.println("MobSpawner: zone added at (" + worldX + "," + worldY + ") size " + w + "x" + h);
    }

    public void update() {
        // Only run if this map has explicitly defined spawn zones
        if (spawnZones.isEmpty()) return;

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
        int slot = findFreeSlot();
        if (slot < 0) return;

        int[] pos = findSpawnPosition();
        if (pos == null) return;

        int col = pos[0];
        int row = pos[1];

        if (nightMode) {
            gp.monster[slot] = new MON_SkeletonArcher(gp, col, row);
        } else {
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
     * Picks a random tile position inside one of the registered spawn zones that:
     * - Is off-screen
     * - Does not collide with terrain
     */
    private int[] findSpawnPosition() {
        int playerCol = gp.player.worldX / gp.tileSize;
        int playerRow = gp.player.worldY / gp.tileSize;
        int halfScreenCols = gp.maxScreenCol / 2 + 1;
        int halfScreenRows = gp.maxScreenRow / 2 + 1;

        int ts = gp.tileSize;

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            // Pick a random zone
            Rectangle zone = spawnZones.get(random.nextInt(spawnZones.size()));

            // Pick a random tile inside that zone
            int zoneCols = Math.max(1, zone.width  / ts);
            int zoneRows = Math.max(1, zone.height / ts);
            int col = (zone.x / ts) + random.nextInt(zoneCols);
            int row = (zone.y / ts) + random.nextInt(zoneRows);

            // Map bounds check
            if (col < 2 || col >= gp.maxWorldCol - 2 || row < 2 || row >= gp.maxWorldRow - 2) continue;

            // Must be off-screen
            int dcol = Math.abs(col - playerCol);
            int drow = Math.abs(row - playerRow);
            if (dcol < halfScreenCols && drow < halfScreenRows) continue;

            // Collision check
            int worldX = col * ts;
            int worldY = row * ts;
            spawnRect.setBounds(worldX + 12, worldY + 8, 40, 48);

            if (!collidesWithTerrain(spawnRect)) {
                return new int[]{col, row};
            }
        }
        return null;
    }

    private boolean collidesWithTerrain(Rectangle rect) {
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
        for (java.awt.Shape s : gp.tileM.collisionShapes) {
            if (s.intersects(rect)) return true;
        }
        return false;
    }
}

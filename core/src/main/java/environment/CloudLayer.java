package environment;

import java.util.Random;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/** Drifting clouds, drawn as the topmost world layer, above every entity and tile. */
public class CloudLayer {

    private static final String[] CLOUD_PATHS = {
        "/res/environment/Clouds1.png",
        "/res/environment/Clouds2.png"
    };

    public static final int MAX_CLOUDS = 4;
    public static final int MIN_SPAWN_GAP_TICKS = 480;   // ~10s at 60 ticks/sec
    public static final int MAX_SPAWN_GAP_TICKS = 900;  // ~20s
    public static final int MIN_LIFE_TICKS = 1200;       // ~20s fully visible
    public static final int MAX_LIFE_TICKS = 2400;       // ~40s fully visible
    public static final int FADE_TICKS = 120;            // ~2s fade in/out on top of the life above
    public static final float MIN_SPEED = 0.25f;
    public static final float MAX_SPEED = 0.55f;
    public static final float MIN_SCALE = 3f;
    public static final float MAX_SCALE = 5f;
    public static final float MAX_ALPHA = 0.4f;

    // How much the drift direction wobbles each tick, so a cloud curves gently instead of sliding
    // in a perfectly straight line.
    public static final float WANDER_STRENGTH = 0.004f;

    // Clouds spawn around the player but well clear of the screen (in pixels, centered on the
    // player), and never closer to each other than MIN_SPACING_PX.
    public static final float SPAWN_RADIUS_X = 900f;
    public static final float SPAWN_RADIUS_Y = 500f;
    public static final float MIN_SPACING_PX = 700f;

    private static final Color CLOUD_TINT = new Color(235, 235, 245);

    public boolean spawningEnabled = false;

    private final GamePanel gp;
    private final Random random = new Random();
    private Sprite[] cloudSprites;

    private final float[] worldX = new float[MAX_CLOUDS];
    private final float[] worldY = new float[MAX_CLOUDS];
    private final float[] moveAngle = new float[MAX_CLOUDS];
    private final float[] speed = new float[MAX_CLOUDS];
    private final float[] scale = new float[MAX_CLOUDS];
    private final int[] spriteIndex = new int[MAX_CLOUDS];
    private final int[] age = new int[MAX_CLOUDS];
    private final int[] lifeTicks = new int[MAX_CLOUDS];
    private final boolean[] active = new boolean[MAX_CLOUDS];

    private int ticksUntilNextSpawn = 0;

    public CloudLayer(GamePanel gp) {
        this.gp = gp;
    }

    /** Called on map change so clouds from the previous map don't keep drifting into the new one. */
    public void clearAll() {
        for (int i = 0; i < MAX_CLOUDS; i++) active[i] = false;
        ticksUntilNextSpawn = 0;
    }

    private void loadSpritesIfNeeded() {
        if (cloudSprites != null) return;
        cloudSprites = new Sprite[CLOUD_PATHS.length];
        for (int i = 0; i < CLOUD_PATHS.length; i++) {
            cloudSprites[i] = ResourceCache.loadImageIfPresent(CLOUD_PATHS[i]);
        }
    }

    public void update() {
        loadSpritesIfNeeded();

        for (int i = 0; i < MAX_CLOUDS; i++) {
            if (!active[i]) continue;
            // Wander: the heading slowly drifts by a random nudge instead of holding a fixed
            // direction, so the path curves gently rather than sliding in a dead-straight line.
            moveAngle[i] += (random.nextFloat() * 2 - 1) * WANDER_STRENGTH;
            worldX[i] += (float) Math.cos(moveAngle[i]) * speed[i];
            worldY[i] += (float) Math.sin(moveAngle[i]) * speed[i] * 0.4f;
            age[i]++;
            if (age[i] >= lifeTicks[i]) {
                active[i] = false;
            }
        }

        if (!spawningEnabled) return;

        ticksUntilNextSpawn--;
        if (ticksUntilNextSpawn <= 0) {
            spawnCloud();
            ticksUntilNextSpawn = MIN_SPAWN_GAP_TICKS
                + random.nextInt(MAX_SPAWN_GAP_TICKS - MIN_SPAWN_GAP_TICKS);
        }
    }

    private void spawnCloud() {
        if (cloudSprites == null) return;
        int slot = -1;
        for (int i = 0; i < MAX_CLOUDS; i++) {
            if (!active[i]) { slot = i; break; }
        }
        if (slot == -1) return;

        float spawnX = gp.player.worldX + (random.nextFloat() * 2 - 1) * SPAWN_RADIUS_X;
        float spawnY = gp.player.worldY + (random.nextFloat() * 2 - 1) * SPAWN_RADIUS_Y;
        for (int i = 0; i < MAX_CLOUDS; i++) {
            if (!active[i]) continue;
            if (Math.hypot(worldX[i] - spawnX, worldY[i] - spawnY) < MIN_SPACING_PX) {
                return; // too close to an existing cloud, just wait for the next spawn attempt
            }
        }

        worldX[slot] = spawnX;
        worldY[slot] = spawnY;
        moveAngle[slot] = random.nextFloat() * (float) (Math.PI * 2);
        speed[slot] = MIN_SPEED + random.nextFloat() * (MAX_SPEED - MIN_SPEED);
        scale[slot] = MIN_SCALE + random.nextFloat() * (MAX_SCALE - MIN_SCALE);
        spriteIndex[slot] = random.nextInt(cloudSprites.length);
        age[slot] = 0;
        lifeTicks[slot] = MIN_LIFE_TICKS + random.nextInt(MAX_LIFE_TICKS - MIN_LIFE_TICKS);
        active[slot] = true;
    }

    public void draw(GdxRenderer g2) {
        if (cloudSprites == null) return;
        float camWX = gp.getCamWorldX() - gp.player.getCamScreenX();
        float camWY = gp.getCamWorldY() - gp.player.getCamScreenY();

        for (int i = 0; i < MAX_CLOUDS; i++) {
            if (!active[i]) continue;
            Sprite sprite = cloudSprites[spriteIndex[i]];
            if (sprite == null) continue;

            int w = Math.round(sprite.getWidth() * scale[i]);
            int h = Math.round(sprite.getHeight() * scale[i]);
            int sx = Math.round(worldX[i] - camWX - w / 2f);
            int sy = Math.round(worldY[i] - camWY - h / 2f);

            float fadeIn = Math.min(1f, age[i] / (float) FADE_TICKS);
            float fadeOut = Math.min(1f, (lifeTicks[i] - age[i]) / (float) FADE_TICKS);
            float alpha = Math.min(fadeIn, fadeOut);
            if (alpha <= 0f) continue;

            g2.drawImageTinted(sprite, sx, sy, w, h, CLOUD_TINT, MAX_ALPHA * alpha);
        }
    }
}

package environment;

import java.util.Random;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;

/**
 * Low, slow-drifting dust/fog motes for dungeons and other enclosed spaces — same drifting-overlay
 * lifecycle as {@link CloudLayer} (spawn/wander/fade), but tuned to sit low near the floor, move
 * slower, and use a soft procedurally generated blob instead of a sky sprite so no new art asset
 * is required. Swap {@link #generateFogSprite} for a real PNG later if one is added under
 * assets/res/environment/ — nothing else about the class needs to change.
 */
public class DustFogLayer {

    public static final int MAX_MOTES = 6;
    public static final int MIN_SPAWN_GAP_TICKS = 180;   // ~3s
    public static final int MAX_SPAWN_GAP_TICKS = 360;   // ~6s
    public static final int MIN_LIFE_TICKS = 900;        // ~15s fully visible
    public static final int MAX_LIFE_TICKS = 1800;        // ~30s fully visible
    public static final int FADE_TICKS = 150;             // ~2.5s fade in/out
    public static final float MIN_SPEED = 0.08f;
    public static final float MAX_SPEED = 0.2f;
    public static final float MIN_SCALE = 2f;
    public static final float MAX_SCALE = 3.5f;
    public static final float MAX_ALPHA = 0.22f;

    public static final float WANDER_STRENGTH = 0.002f;

    // Fog hugs the floor: spawn ring is wide horizontally but biased low vertically relative to
    // the player, instead of CloudLayer's symmetric spawn box.
    public static final float SPAWN_RADIUS_X = 500f;
    public static final float SPAWN_RADIUS_Y_UP = 80f;
    public static final float SPAWN_RADIUS_Y_DOWN = 220f;
    public static final float MIN_SPACING_PX = 350f;

    private static final Color FOG_TINT = new Color(150, 145, 140);
    private static final int SPRITE_SIZE = 128;

    public boolean spawningEnabled = false;

    private final GamePanel gp;
    private final Random random = new Random();
    private Sprite fogSprite;

    private final float[] worldX = new float[MAX_MOTES];
    private final float[] worldY = new float[MAX_MOTES];
    private final float[] moveAngle = new float[MAX_MOTES];
    private final float[] speed = new float[MAX_MOTES];
    private final float[] scale = new float[MAX_MOTES];
    private final int[] age = new int[MAX_MOTES];
    private final int[] lifeTicks = new int[MAX_MOTES];
    private final boolean[] active = new boolean[MAX_MOTES];

    private int ticksUntilNextSpawn = 0;

    public DustFogLayer(GamePanel gp) {
        this.gp = gp;
    }

    /** Called on map change so fog from the previous map doesn't keep drifting into the new one. */
    public void clearAll() {
        for (int i = 0; i < MAX_MOTES; i++) active[i] = false;
        ticksUntilNextSpawn = 0;
    }

    private void loadSpriteIfNeeded() {
        if (fogSprite != null) return;
        fogSprite = generateFogSprite(SPRITE_SIZE);
    }

    public void update() {
        loadSpriteIfNeeded();

        for (int i = 0; i < MAX_MOTES; i++) {
            if (!active[i]) continue;
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
            spawnMote();
            ticksUntilNextSpawn = MIN_SPAWN_GAP_TICKS
                + random.nextInt(MAX_SPAWN_GAP_TICKS - MIN_SPAWN_GAP_TICKS);
        }
    }

    private void spawnMote() {
        if (fogSprite == null) return;
        int slot = -1;
        for (int i = 0; i < MAX_MOTES; i++) {
            if (!active[i]) { slot = i; break; }
        }
        if (slot == -1) return;

        float spawnX = gp.player.worldX + (random.nextFloat() * 2 - 1) * SPAWN_RADIUS_X;
        float spawnY = gp.player.worldY
            + random.nextFloat() * (SPAWN_RADIUS_Y_DOWN + SPAWN_RADIUS_Y_UP) - SPAWN_RADIUS_Y_UP;
        for (int i = 0; i < MAX_MOTES; i++) {
            if (!active[i]) continue;
            if (Math.hypot(worldX[i] - spawnX, worldY[i] - spawnY) < MIN_SPACING_PX) {
                return; // too close to an existing mote, just wait for the next spawn attempt
            }
        }

        worldX[slot] = spawnX;
        worldY[slot] = spawnY;
        moveAngle[slot] = random.nextFloat() * (float) (Math.PI * 2);
        speed[slot] = MIN_SPEED + random.nextFloat() * (MAX_SPEED - MIN_SPEED);
        scale[slot] = MIN_SCALE + random.nextFloat() * (MAX_SCALE - MIN_SCALE);
        age[slot] = 0;
        lifeTicks[slot] = MIN_LIFE_TICKS + random.nextInt(MAX_LIFE_TICKS - MIN_LIFE_TICKS);
        active[slot] = true;
    }

    public void draw(GdxRenderer g2) {
        if (fogSprite == null) return;
        float camWX = gp.getCamWorldX() - gp.player.getCamScreenX();
        float camWY = gp.getCamWorldY() - gp.player.getCamScreenY();

        for (int i = 0; i < MAX_MOTES; i++) {
            if (!active[i]) continue;

            int w = Math.round(fogSprite.getWidth() * scale[i]);
            int h = Math.round(fogSprite.getHeight() * scale[i]);
            int sx = Math.round(worldX[i] - camWX - w / 2f);
            int sy = Math.round(worldY[i] - camWY - h / 2f);

            float fadeIn = Math.min(1f, age[i] / (float) FADE_TICKS);
            float fadeOut = Math.min(1f, (lifeTicks[i] - age[i]) / (float) FADE_TICKS);
            float alpha = Math.min(fadeIn, fadeOut);
            if (alpha <= 0f) continue;

            g2.drawImageTinted(fogSprite, sx, sy, w, h, FOG_TINT, MAX_ALPHA * alpha);
        }
    }

    /** Soft radial-gradient blob (opaque grey center fading to transparent edge), generated once. */
    private static Sprite generateFogSprite(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.SourceOver);
        float cx = size / 2f, cy = size / 2f, maxDist = size / 2f;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dist = (float) Math.hypot(x - cx, y - cy);
                float t = Math.min(1f, dist / maxDist);
                // Smoothstep falloff so the edge fades gradually instead of a hard circle.
                float a = 1f - (t * t * (3f - 2f * t));
                if (a <= 0f) continue;
                pm.setColor(1f, 1f, 1f, a);
                pm.drawPixel(x, y);
            }
        }

        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return new Sprite(tex);
    }
}

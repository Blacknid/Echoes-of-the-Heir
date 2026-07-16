package environment;

import java.util.Random;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;

/**
 * Small drifting fireflies for a "glowing night" ambience, same pooled spawn/wander/fade
 * lifecycle as {@link DustFogLayer}, but each firefly also pulses its own brightness and
 * registers a tiny real light with {@link Lightning} so it actually lights up the ground
 * around it, not just an additive sprite.
 *
 * <p>To use on another map, just set the {@code fireflies="true"} bool property on that map
 * in Tiled, no code changes needed. All tuning knobs are the public constants below.
 */
public class FireflyLayer {

    public static final int MAX_FIREFLIES = 18;
    public static final int MIN_SPAWN_GAP_TICKS = 20;
    public static final int MAX_SPAWN_GAP_TICKS = 50;
    public static final int MIN_LIFE_TICKS = 1200;   // ~20s
    public static final int MAX_LIFE_TICKS = 2400;   // ~40s
    public static final int FADE_TICKS = 90;          // ~1.5s fade in/out

    public static final float MIN_SPEED = 0.15f;
    public static final float MAX_SPEED = 0.35f;
    public static final float WANDER_STRENGTH = 0.05f;
    public static final float BOB_SPEED_MIN = 0.02f;
    public static final float BOB_SPEED_MAX = 0.05f;
    public static final float BOB_HEIGHT_PX = 3f;

    /** Body size in pixels, deliberately visible, not a single-pixel speck. */
    public static final float MIN_SCALE_PX = 7f;
    public static final float MAX_SCALE_PX = 10f;
    public static final float MAX_ALPHA = 0.9f;

    /** Pulse: each firefly's glow breathes between dim and bright on its own random cycle. */
    public static final float PULSE_SPEED_MIN = 0.03f;
    public static final float PULSE_SPEED_MAX = 0.06f;
    public static final float PULSE_MIN_ALPHA = 0.25f;

    /** Real light cast on the world, small and soft, just enough to glow, not illuminate the area. */
    public static final int LIGHT_RADIUS_PX = 26;
    public static final float LIGHT_INTENSITY = 0.35f;

    public static final float SPAWN_RADIUS_X = 420f;
    public static final float SPAWN_RADIUS_Y = 280f;
    public static final float MIN_SPACING_PX = 60f;

    private static final Color GLOW_COLOR = new Color(210, 255, 130);
    // Tiny native pixel grid, upscaled with Nearest filtering, keeps the firefly a crisp blocky
    // dot instead of a smooth airbrushed circle, matching the game's pixel-art look.
    private static final int SPRITE_PIXELS = 5;

    public boolean spawningEnabled = false;

    private final GamePanel gp;
    private final Random random = new Random();
    private Sprite glowSprite;

    private final float[] worldX = new float[MAX_FIREFLIES];
    private final float[] worldY = new float[MAX_FIREFLIES];
    private final float[] baseY = new float[MAX_FIREFLIES];
    private final float[] moveAngle = new float[MAX_FIREFLIES];
    private final float[] speed = new float[MAX_FIREFLIES];
    private final float[] scale = new float[MAX_FIREFLIES];
    private final float[] bobPhase = new float[MAX_FIREFLIES];
    private final float[] bobSpeed = new float[MAX_FIREFLIES];
    private final float[] pulsePhase = new float[MAX_FIREFLIES];
    private final float[] pulseSpeed = new float[MAX_FIREFLIES];
    private final int[] age = new int[MAX_FIREFLIES];
    private final int[] lifeTicks = new int[MAX_FIREFLIES];
    private final boolean[] active = new boolean[MAX_FIREFLIES];

    private int ticksUntilNextSpawn = 0;

    public FireflyLayer(GamePanel gp) {
        this.gp = gp;
    }

    /** Called on map change so fireflies from the previous map don't linger into the new one. */
    public void clearAll() {
        for (int i = 0; i < MAX_FIREFLIES; i++) active[i] = false;
        ticksUntilNextSpawn = 0;
    }

    private void loadSpriteIfNeeded() {
        if (glowSprite != null) return;
        glowSprite = generateGlowSprite(SPRITE_PIXELS);
    }

    public void update() {
        loadSpriteIfNeeded();

        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (!active[i]) continue;
            moveAngle[i] += (random.nextFloat() * 2 - 1) * WANDER_STRENGTH;
            worldX[i] += (float) Math.cos(moveAngle[i]) * speed[i];
            baseY[i] += (float) Math.sin(moveAngle[i]) * speed[i];
            bobPhase[i] += bobSpeed[i];
            worldY[i] = baseY[i] + (float) Math.sin(bobPhase[i]) * BOB_HEIGHT_PX;
            pulsePhase[i] += pulseSpeed[i];
            age[i]++;
            if (age[i] >= lifeTicks[i]) active[i] = false;
        }

        if (!spawningEnabled) return;

        ticksUntilNextSpawn--;
        if (ticksUntilNextSpawn <= 0) {
            spawnFirefly();
            ticksUntilNextSpawn = MIN_SPAWN_GAP_TICKS
                + random.nextInt(MAX_SPAWN_GAP_TICKS - MIN_SPAWN_GAP_TICKS);
        }
    }

    private void spawnFirefly() {
        if (glowSprite == null) return;
        int slot = -1;
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (!active[i]) { slot = i; break; }
        }
        if (slot == -1) return;

        float spawnX = gp.player.worldX + (random.nextFloat() * 2 - 1) * SPAWN_RADIUS_X;
        float spawnY = gp.player.worldY + (random.nextFloat() * 2 - 1) * SPAWN_RADIUS_Y;
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (!active[i]) continue;
            if (Math.hypot(worldX[i] - spawnX, worldY[i] - spawnY) < MIN_SPACING_PX) {
                return; // too close to an existing firefly, just wait for the next spawn attempt
            }
        }

        worldX[slot] = spawnX;
        baseY[slot] = spawnY;
        worldY[slot] = spawnY;
        moveAngle[slot] = random.nextFloat() * (float) (Math.PI * 2);
        speed[slot] = MIN_SPEED + random.nextFloat() * (MAX_SPEED - MIN_SPEED);
        scale[slot] = MIN_SCALE_PX + random.nextFloat() * (MAX_SCALE_PX - MIN_SCALE_PX);
        bobPhase[slot] = random.nextFloat() * (float) (Math.PI * 2);
        bobSpeed[slot] = BOB_SPEED_MIN + random.nextFloat() * (BOB_SPEED_MAX - BOB_SPEED_MIN);
        pulsePhase[slot] = random.nextFloat() * (float) (Math.PI * 2);
        pulseSpeed[slot] = PULSE_SPEED_MIN + random.nextFloat() * (PULSE_SPEED_MAX - PULSE_SPEED_MIN);
        age[slot] = 0;
        lifeTicks[slot] = MIN_LIFE_TICKS + random.nextInt(MAX_LIFE_TICKS - MIN_LIFE_TICKS);
        active[slot] = true;
    }

    /** Current pulse brightness (0..1) for firefly i, factoring in its life fade. */
    private float glowAlpha(int i) {
        float pulse = PULSE_MIN_ALPHA + (1f - PULSE_MIN_ALPHA)
            * (0.5f + 0.5f * (float) Math.sin(pulsePhase[i]));
        float fadeIn = Math.min(1f, age[i] / (float) FADE_TICKS);
        float fadeOut = Math.min(1f, (lifeTicks[i] - age[i]) / (float) FADE_TICKS);
        return pulse * Math.min(fadeIn, fadeOut);
    }

    /** Registers each active firefly as a small light. Call after {@code lightning.clearLights()}. */
    public void addLights(Lightning lightning) {
        if (!spawningEnabled) return;
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (!active[i]) continue;
            float glow = glowAlpha(i);
            if (glow <= 0.05f) continue;
            lightning.addLight(Math.round(worldX[i]), Math.round(worldY[i]),
                LIGHT_RADIUS_PX, GLOW_COLOR, LIGHT_INTENSITY * glow);
        }
    }

    public void draw(GdxRenderer g2) {
        if (glowSprite == null) return;
        float camWX = gp.getCamWorldX() - gp.player.getCamScreenX();
        float camWY = gp.getCamWorldY() - gp.player.getCamScreenY();

        g2.setBlendMode(GdxRenderer.BLEND_ADDITIVE);
        for (int i = 0; i < MAX_FIREFLIES; i++) {
            if (!active[i]) continue;
            float glow = glowAlpha(i);
            if (glow <= 0f) continue;

            int w = Math.round(scale[i]);
            int h = Math.round(scale[i]);
            int sx = Math.round(worldX[i] - camWX - w / 2f);
            int sy = Math.round(worldY[i] - camWY - h / 2f);

            g2.drawImageTinted(glowSprite, sx, sy, w, h, GLOW_COLOR, MAX_ALPHA * glow);
        }
        g2.setBlendMode(GdxRenderer.BLEND_NORMAL);
    }

    /**
 * Tiny blocky pixel-art dot, a bright core pixel with dimmer pixels stepped out around it
     * (chunky rings, not a smooth gradient), drawn at native size and scaled up with Nearest
     * filtering so each "pixel" stays a crisp square instead of blurring into a soft blob.
     */
    private static Sprite generateGlowSprite(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.SourceOver);
        float cx = (size - 1) / 2f, cy = (size - 1) / 2f;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dist = Math.max(Math.abs(x - cx), Math.abs(y - cy)); // chebyshev = blocky rings
                float a;
                if (dist < 0.6f) a = 1f;        // bright core pixel
                else if (dist < 1.6f) a = 0.55f; // one dim ring around it
                else continue;                   // nothing beyond, keeps the sprite small and crisp
                pm.setColor(1f, 1f, 1f, a);
                pm.drawPixel(x, y);
            }
        }

        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();
        return new Sprite(tex);
    }
}

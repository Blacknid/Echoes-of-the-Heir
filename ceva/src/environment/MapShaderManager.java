package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.image.BufferedImage;
import java.util.Random;
import main.GamePanel;

/**
 * Provides shader-like visual effects for the map using Java2D.
 * Effects: water shimmer/waves, ambient floating particles, vignette, color grading.
 */
public class MapShaderManager {

    GamePanel gp;

    // --- Animation tick (advanced each update) ---
    public long tick = 0;

    // ===================== WATER SHIMMER =====================
    public static final int SHIMMER_LEVELS = 36;
    public Color[] waterShimmerColors;

    // OPTIMIZATION: Pre-computed sine lookup table to avoid Math.sin/cos per tile per frame
    private static final int SIN_TABLE_SIZE = 1024;
    private static final float SIN_TABLE_SCALE = SIN_TABLE_SIZE / (float)(2 * Math.PI);
    private static final float[] SIN_TABLE = new float[SIN_TABLE_SIZE];
    static {
        for (int i = 0; i < SIN_TABLE_SIZE; i++) {
            SIN_TABLE[i] = (float) Math.sin(2 * Math.PI * i / SIN_TABLE_SIZE);
        }
    }
    
    private static float fastSin(double angle) {
        int idx = (int)(angle * SIN_TABLE_SCALE) % SIN_TABLE_SIZE;
        if (idx < 0) idx += SIN_TABLE_SIZE;
        return SIN_TABLE[idx];
    }
    
    private static float fastCos(double angle) {
        int idx = (int)((angle + Math.PI * 0.5) * SIN_TABLE_SCALE) % SIN_TABLE_SIZE;
        if (idx < 0) idx += SIN_TABLE_SIZE;
        return SIN_TABLE[idx];
    }

    // ===================== VIGNETTE =====================
    private BufferedImage vignetteImage;
    private float vignetteStrength = 0.45f;

    // ===================== COLOR GRADING =====================
    private Color warmOverlay;

    // ===================== AMBIENT PARTICLES =====================
    private static final int PARTICLE_COUNT = 35;
    private float[] pX, pY, pVX, pVY, pAlpha, pSize, pAlphaDir;
    private Random random = new Random();
    private float windX = 0.3f;
    private float windY = -0.1f;

    // ===================== WEATHER SYSTEM =====================
    private static final int MAX_RAIN = 250;
    private float[] rainX, rainY, rainSpeed, rainAlpha, rainLength;

    private static final int MAX_SNOW = 120;
    private float[] snowX, snowY, snowSpeed, snowSize, snowDrift, snowAlpha;

    // Storm lightning flash
    private int stormFlashTimer = 0;
    private int nextFlashIn = 0;

    // Weather overlay tint colors
    private static final Color RAIN_TINT  = new Color(40, 80, 160, 12);
    private static final Color STORM_TINT = new Color(30, 60, 140, 18);
    private static final Color SNOW_TINT  = new Color(180, 210, 240, 8);
    private static final Color RAIN_DROP_COLOR = new Color(180, 210, 255);
    private static final Color SNOW_FLAKE_COLOR = new Color(240, 245, 255);

    public MapShaderManager(GamePanel gp) {
        this.gp = gp;
    }

    public void setup() {
        initWaterShimmer();
        createVignette();
        initParticles();
        initWeather();
        warmOverlay = new Color(255, 220, 160, 7);
    }

    // =====================================================================
    //  WATER SHIMMER
    // =====================================================================

    private void initWaterShimmer() {
        waterShimmerColors = new Color[SHIMMER_LEVELS];
        for (int i = 0; i < SHIMMER_LEVELS; i++) {
            float t = (float) i / (SHIMMER_LEVELS - 1);
            int alpha = (int) (t * 32); // very subtle highlight
            waterShimmerColors[i] = new Color(180, 225, 255, alpha);
        }
    }

    /** Vertical wave offset for a water tile (call from TileManager). */
    public int getWaterWaveOffset(int worldCol, int worldRow) {
        float wave = fastSin(worldCol * 0.7 + worldRow * 0.3 + tick * 0.055) * 1.5f;
        return (int) wave;
    }

    /** Shimmer color index for a water tile (call from TileManager). */
    public int getWaterShimmerIndex(int worldCol, int worldRow) {
        float phase = fastSin(worldCol * 1.2 + worldRow * 0.8 + tick * 0.09);
        int idx = (int) ((phase * 0.5f + 0.5f) * (SHIMMER_LEVELS - 1));
        return Math.max(0, Math.min(SHIMMER_LEVELS - 1, idx));
    }

    // =====================================================================
    //  AMBIENT PARTICLES  (dust motes / pollen drifting in sunlight)
    // =====================================================================

    private void initParticles() {
        pX = new float[PARTICLE_COUNT];
        pY = new float[PARTICLE_COUNT];
        pVX = new float[PARTICLE_COUNT];
        pVY = new float[PARTICLE_COUNT];
        pAlpha = new float[PARTICLE_COUNT];
        pSize = new float[PARTICLE_COUNT];
        pAlphaDir = new float[PARTICLE_COUNT];

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            resetParticle(i, true);
        }
    }

    private void resetParticle(int i, boolean randomizePos) {
        if (randomizePos) {
            pX[i] = random.nextFloat() * gp.screenWidth;
            pY[i] = random.nextFloat() * gp.screenHeight;
        } else {
            // Enter from screen edges
            if (random.nextBoolean()) {
                pX[i] = random.nextBoolean() ? -5 : gp.screenWidth + 5;
                pY[i] = random.nextFloat() * gp.screenHeight;
            } else {
                pX[i] = random.nextFloat() * gp.screenWidth;
                pY[i] = random.nextBoolean() ? -5 : gp.screenHeight + 5;
            }
        }
        pVX[i] = (random.nextFloat() - 0.4f) * 0.5f + windX;
        pVY[i] = (random.nextFloat() - 0.5f) * 0.3f + windY;
        pAlpha[i] = random.nextFloat() * 0.5f;
        pSize[i] = 1.5f + random.nextFloat() * 2.5f;
        pAlphaDir[i] = 0.005f + random.nextFloat() * 0.01f;
    }

    private void updateParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            pX[i] += pVX[i];
            pY[i] += pVY[i];

            // Gentle sine drift (uses fast LUT instead of Math.sin/cos)
            pX[i] += fastSin(tick * 0.02 + i) * 0.15f;
            pY[i] += fastCos(tick * 0.015 + i * 0.7) * 0.1f;

            // Fade in / out
            pAlpha[i] += pAlphaDir[i];
            if (pAlpha[i] >= 0.55f) {
                pAlpha[i] = 0.55f;
                pAlphaDir[i] = -Math.abs(pAlphaDir[i]);
            }
            if (pAlpha[i] <= 0f) {
                pAlpha[i] = 0f;
                pAlphaDir[i] = Math.abs(pAlphaDir[i]);
            }

            // Wrap around screen
            if (pX[i] < -10 || pX[i] > gp.screenWidth + 10 ||
                pY[i] < -10 || pY[i] > gp.screenHeight + 10) {
                resetParticle(i, false);
            }
        }
    }

    public void drawAmbientParticles(Graphics2D g2) {
        /* 
        Composite original = g2.getComposite();

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            if (pAlpha[i] <= 0.01f) continue;

            float alpha = Math.min(pAlpha[i], 1.0f);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.setColor(Color.WHITE);
            int sz = (int) pSize[i];
            g2.fillOval((int) pX[i], (int) pY[i], sz, sz);
            

            // Tiny glow halo around larger particles
            if (pSize[i] > 2.5f) {
                g2.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, Math.min(alpha * 0.3f, 1.0f)));
                int glowSz = sz + 3;
                g2.fillOval((int) pX[i] - 1, (int) pY[i] - 1, glowSz, glowSz);
            }
        }

        g2.setComposite(original);*/
    }

    // =====================================================================
    //  VIGNETTE  (darkened screen edges for cinematic feel)
    // =====================================================================

    private void createVignette() {
        int w = gp.screenWidth;
        int h = gp.screenHeight;
        vignetteImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D vg = vignetteImage.createGraphics();

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = (float) Math.sqrt(cx * cx + cy * cy);

        RadialGradientPaint paint = new RadialGradientPaint(
            cx, cy, radius,
            new float[]{0.0f, 0.5f, 0.85f, 1.0f},
            new Color[]{
                new Color(0, 0, 0, 0),
                new Color(0, 0, 0, 0),
                new Color(0, 0, 0, (int)(60 * vignetteStrength)),
                new Color(0, 0, 0, (int)(160 * vignetteStrength))
            }
        );

        vg.setPaint(paint);
        vg.fillRect(0, 0, w, h);
        vg.dispose();
    }

    public void drawVignette(Graphics2D g2) {
        if (vignetteImage != null) {
            g2.drawImage(vignetteImage, 0, 0, null);
        }
    }

    // =====================================================================
    //  COLOR GRADING  (subtle warm tint during daytime)
    // =====================================================================

    public void drawColorGrading(Graphics2D g2) {
        // Only apply warm overlay during daytime; let the night system handle darkness
        if (gp.eManager != null && gp.eManager.filterAlpha < 0.5f) {
            g2.setColor(warmOverlay);
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }
    }

    // =====================================================================
    //  UPDATE  (call once per game tick from GamePanel.update())
    // =====================================================================

    public void update() {
        tick++;
        updateParticles();
        updateWeather();
    }

    // =====================================================================
    //  WEATHER SYSTEM
    // =====================================================================

    private void initWeather() {
        rainX = new float[MAX_RAIN];
        rainY = new float[MAX_RAIN];
        rainSpeed = new float[MAX_RAIN];
        rainAlpha = new float[MAX_RAIN];
        rainLength = new float[MAX_RAIN];
        for (int i = 0; i < MAX_RAIN; i++) resetRaindrop(i, true);

        snowX = new float[MAX_SNOW];
        snowY = new float[MAX_SNOW];
        snowSpeed = new float[MAX_SNOW];
        snowSize = new float[MAX_SNOW];
        snowDrift = new float[MAX_SNOW];
        snowAlpha = new float[MAX_SNOW];
        for (int i = 0; i < MAX_SNOW; i++) resetSnowflake(i, true);

        nextFlashIn = 300 + random.nextInt(300);
    }

    private void resetRaindrop(int i, boolean randomY) {
        rainX[i] = random.nextFloat() * (gp.screenWidth + 60) - 30;
        rainY[i] = randomY ? random.nextFloat() * gp.screenHeight : -(random.nextFloat() * 40);
        rainSpeed[i] = 12 + random.nextFloat() * 6;
        rainAlpha[i] = 0.25f + random.nextFloat() * 0.35f;
        rainLength[i] = 8 + random.nextFloat() * 6;
    }

    private void resetSnowflake(int i, boolean randomY) {
        snowX[i] = random.nextFloat() * (gp.screenWidth + 40) - 20;
        snowY[i] = randomY ? random.nextFloat() * gp.screenHeight : -(random.nextFloat() * 30);
        snowSpeed[i] = 1 + random.nextFloat() * 2;
        snowSize[i] = 2 + random.nextFloat() * 3;
        snowDrift[i] = random.nextFloat() * 6.28f; // random phase
        snowAlpha[i] = 0.3f + random.nextFloat() * 0.5f;
    }

    private void updateWeather() {
        if (gp.eManager == null) return;
        int ws = gp.eManager.weatherState;
        float intensity = gp.eManager.weatherIntensity;
        if (intensity <= 0.001f) return;

        if (ws == EnvironmentManager.WEATHER_RAIN || ws == EnvironmentManager.WEATHER_STORM) {
            int active = (int)(MAX_RAIN * intensity);
            // LOD: reduce particles under low FPS
            if (gp.currentFPS > 0 && gp.currentFPS < 48) active = active / 2;
            for (int i = 0; i < active; i++) {
                rainY[i] += rainSpeed[i];
                rainX[i] -= 2.0f; // slight diagonal
                rainX[i] += fastSin(tick * 0.04 + i) * 0.3f;
                if (rainY[i] > gp.screenHeight + 10) {
                    resetRaindrop(i, false);
                }
            }
            // Storm: lightning flash timer
            if (ws == EnvironmentManager.WEATHER_STORM) {
                if (stormFlashTimer > 0) stormFlashTimer--;
                nextFlashIn--;
                if (nextFlashIn <= 0) {
                    stormFlashTimer = 4;
                    nextFlashIn = 300 + random.nextInt(300);
                }
            }
        }

        if (ws == EnvironmentManager.WEATHER_SNOW) {
            int active = (int)(MAX_SNOW * intensity);
            if (gp.currentFPS > 0 && gp.currentFPS < 48) active = active / 2;
            for (int i = 0; i < active; i++) {
                snowY[i] += snowSpeed[i];
                snowX[i] += fastSin(tick * 0.03 + snowDrift[i]) * 0.8f;
                if (snowY[i] > gp.screenHeight + 10 || snowX[i] < -20 || snowX[i] > gp.screenWidth + 20) {
                    resetSnowflake(i, false);
                }
            }
        }
    }

    public void drawWeather(Graphics2D g2) {
        if (gp.eManager == null) return;
        int ws = gp.eManager.weatherState;
        float intensity = gp.eManager.weatherIntensity;
        if (intensity <= 0.001f && stormFlashTimer <= 0) return;

        Composite original = g2.getComposite();

        // Storm lightning flash (bright white overlay)
        if (ws == EnvironmentManager.WEATHER_STORM && stormFlashTimer > 0) {
            float flashA = Math.min(1f, stormFlashTimer / 4f * 0.15f * intensity);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, flashA));
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }

        if (ws == EnvironmentManager.WEATHER_RAIN || ws == EnvironmentManager.WEATHER_STORM) {
            // Rain drops as diagonal lines
            int active = (int)(MAX_RAIN * intensity);
            if (gp.currentFPS > 0 && gp.currentFPS < 48) active = active / 2;
            // Set composite + color ONCE before the loop — was 250 setComposite calls, now 1
            float rainA = Math.min(1f, 0.4f * intensity);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, rainA));
            g2.setColor(RAIN_DROP_COLOR);
            for (int i = 0; i < active; i++) {
                int x1 = (int) rainX[i];
                int y1 = (int) rainY[i];
                g2.drawLine(x1, y1, x1 + 2, y1 - (int) rainLength[i]);
            }

            // Overlay tint
            Color tint = (ws == EnvironmentManager.WEATHER_STORM) ? STORM_TINT : RAIN_TINT;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, intensity)));
            g2.setColor(tint);
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }

        if (ws == EnvironmentManager.WEATHER_SNOW) {
            int active = (int)(MAX_SNOW * intensity);
            if (gp.currentFPS > 0 && gp.currentFPS < 48) active = active / 2;
            // Set composite + color ONCE before the loop — was 120 setComposite calls, now 1
            float snowA = Math.min(1f, 0.5f * intensity);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, snowA));
            g2.setColor(SNOW_FLAKE_COLOR);
            for (int i = 0; i < active; i++) {
                int sz = (int) snowSize[i];
                g2.fillOval((int) snowX[i], (int) snowY[i], sz, sz);
            }

            // Overlay tint
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, intensity)));
            g2.setColor(SNOW_TINT);
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }

        g2.setComposite(original);
    }
}

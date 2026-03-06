package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.image.BufferedImage;
import java.util.Random;

import entity.Particle;
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

    public MapShaderManager(GamePanel gp) {
        this.gp = gp;
    }

    public void setup() {
        initWaterShimmer();
        createVignette();
        initParticles();
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
        double wave = Math.sin(worldCol * 0.7 + worldRow * 0.3 + tick * 0.055) * 1.5;
        return (int) wave;
    }

    /** Shimmer color index for a water tile (call from TileManager). */
    public int getWaterShimmerIndex(int worldCol, int worldRow) {
        double phase = Math.sin(worldCol * 1.2 + worldRow * 0.8 + tick * 0.09);
        int idx = (int) ((phase * 0.5 + 0.5) * (SHIMMER_LEVELS - 1));
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

            // Gentle sine drift
            pX[i] += (float) Math.sin(tick * 0.02 + i) * 0.15f;
            pY[i] += (float) Math.cos(tick * 0.015 + i * 0.7) * 0.1f;

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

        g2.setComposite(original);
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
    }
}

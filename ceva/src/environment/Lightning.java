package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.HashMap;

import main.GamePanel;

public class Lightning {

    GamePanel gp;
    BufferedImage darknessFilter;
    public int dayState;
    
    // OPTIMIZATION: Single offscreen image for the darkness pass.
    private BufferedImage darknessOverlay;
    private int[] darknessPixels;
    private int overlayWidth = 0, overlayHeight = 0;

    // OPTIMIZATION: Cached Graphics2D for the darkness overlay — avoids createGraphics() every frame
    private Graphics2D overlayG2;

    // Player light radius in tiles — change this to increase or decrease the player's visible area.
    // Minimum: 1 tile, recommended range: 3–15.
    public int playerLightRadius = 3;

    // OPTIMIZATION: Pre-computed alpha lookup table for player light
    private static final int LIGHT_LUT_SIZE = 50;
    private final float[] lightAlphaLUT = new float[LIGHT_LUT_SIZE + 1];

    // ===================== COLORED LIGHT REGISTRY =====================
    private static final int MAX_LIGHTS = 20;
    public int[] lightWX = new int[MAX_LIGHTS];
    public int[] lightWY = new int[MAX_LIGHTS];
    public int[] lightRadiusPx = new int[MAX_LIGHTS];
    public Color[] lightColor = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // Gradient image cache keyed by (r << 48 | g << 40 | b << 32 | radiusPx)
    private final HashMap<Long, BufferedImage> gradientCache = new HashMap<>();

    // OPTIMIZATION: Pre-rendered DstOut light masks — eliminates RadialGradientPaint per frame.
    // RadialGradientPaint falls back to software rasterization in Java2D and is the #1 FPS killer.
    // Key = radiusPx, Value = pre-rendered white radial gradient for DstOut punching.
    private final HashMap<Integer, BufferedImage> dstOutLightCache = new HashMap<>();

    // OPTIMIZATION: Reusable Point2D — avoids allocation per light per frame
    private final Point2D.Float reusableCenter = new Point2D.Float();

    // OPTIMIZATION: AlphaComposite cache — avoids per-light allocation each frame
    private static final int ALPHA_CACHE_SIZE = 101;
    private static final AlphaComposite[] alphaCache = new AlphaComposite[ALPHA_CACHE_SIZE];
    static {
        for (int i = 0; i < ALPHA_CACHE_SIZE; i++) {
            alphaCache[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, i / 100f);
        }
    }
    private static AlphaComposite cachedAlpha(float alpha) {
        int idx = Math.round(alpha * 100f);
        if (idx < 0) idx = 0; else if (idx >= ALPHA_CACHE_SIZE) idx = ALPHA_CACHE_SIZE - 1;
        return alphaCache[idx];
    }

    /** Allocate (or re-allocate on resize) the darkness overlay image. */
    private void ensureOverlay(int w, int h) {
        if (darknessOverlay != null && overlayWidth == w && overlayHeight == h) return;
        // Dispose old cached Graphics2D
        if (overlayG2 != null) { overlayG2.dispose(); overlayG2 = null; }
        darknessOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        darknessPixels  = ((DataBufferInt) darknessOverlay.getRaster().getDataBuffer()).getData();
        overlayWidth    = w;
        overlayHeight   = h;
        // Create and cache the overlay's Graphics2D
        overlayG2 = darknessOverlay.createGraphics();
    }

    /**
     * Get (or create) a pre-rendered radial light mask for DstOut punching.
     * This replaces per-frame RadialGradientPaint usage with a single cached drawImage.
     */
    private BufferedImage getDstOutLight(int radiusPx) {
        BufferedImage img = dstOutLightCache.get(radiusPx);
        if (img != null) return img;

        int diameter = radiusPx * 2;
        img = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Point2D center = new Point2D.Float(radiusPx, radiusPx);
        float[] dist = {0.0f, 0.35f, 0.7f, 1.0f};
        Color[] colors = {
            new Color(255, 255, 255, 255),
            new Color(255, 255, 255, 220),
            new Color(255, 255, 255, 100),
            new Color(255, 255, 255, 0)
        };
        RadialGradientPaint rgp = new RadialGradientPaint(center, radiusPx, dist, colors);
        ig.setPaint(rgp);
        ig.fillOval(0, 0, diameter, diameter);
        ig.dispose();
        dstOutLightCache.put(radiusPx, img);
        return img;
    }

    public Lightning(GamePanel gp2) {
        this.gp = gp2;
        for (int i = 0; i <= LIGHT_LUT_SIZE; i++) {
            lightAlphaLUT[i] = (float) Math.sqrt(i) / playerLightRadius;
        }
    }

    // ===================== COLORED LIGHT API =====================
    public void clearLights() {
        lightCount = 0;
    }

    public void addLight(int worldX, int worldY, int radiusPx, Color color, float intensity) {
        if (lightCount >= MAX_LIGHTS) return;
        lightWX[lightCount] = worldX;
        lightWY[lightCount] = worldY;
        lightRadiusPx[lightCount] = radiusPx;
        lightColor[lightCount] = color;
        lightIntensity[lightCount] = intensity;
        lightCount++;
    }

    private BufferedImage getGradient(Color c, int radiusPx) {
        long key = ((long)(c.getRed() & 0xFF) << 48)
                 | ((long)(c.getGreen() & 0xFF) << 40)
                 | ((long)(c.getBlue() & 0xFF) << 32)
                 | (radiusPx & 0xFFFFFFFFL);
        BufferedImage img = gradientCache.get(key);
        if (img != null) return img;

        int diameter = radiusPx * 2;
        img = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Point2D center = new Point2D.Float(radiusPx, radiusPx);
        float[] dist = {0.0f, 0.4f, 1.0f};
        Color cCenter = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
        Color cMid    = new Color(c.getRed(), c.getGreen(), c.getBlue(), 80);
        Color cEdge   = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        RadialGradientPaint rgp = new RadialGradientPaint(center, radiusPx, dist, new Color[]{cCenter, cMid, cEdge});
        ig.setPaint(rgp);
        ig.fillOval(0, 0, diameter, diameter);
        ig.dispose();
        gradientCache.put(key, img);
        return img;
    }

    public void draw(Graphics2D g2, float currentMaxDarkness) {
        if (currentMaxDarkness <= 0.001f) return;

        int screenWidth  = gp.screenWidth;
        int screenHeight = gp.screenHeight;

        // Ensure the overlay image matches current screen size (lazy alloc / handles resizes)
        ensureOverlay(screenWidth, screenHeight);

        // Fill entire overlay with uniform max darkness
        int darkArgb = ((int)(currentMaxDarkness * 255 + 0.5f)) << 24;
        Arrays.fill(darknessPixels, darkArgb);

        // OPTIMIZATION: Reuse cached Graphics2D on the overlay instead of createGraphics() per frame
        Graphics2D og = overlayG2;
        og.setComposite(AlphaComposite.DstOut);

        // ── PLAYER LIGHT — uses pre-rendered cached mask (no RadialGradientPaint per frame) ──
        int playerSX = gp.player.screenX + gp.tileSize / 2;
        int playerSY = gp.player.screenY + gp.tileSize / 2;
        int lightPx  = playerLightRadius * gp.tileSize;

        BufferedImage playerMask = getDstOutLight(lightPx);
        og.drawImage(playerMask, playerSX - lightPx, playerSY - lightPx, null);

        // ── TORCH LIGHTS — uses same pre-rendered cached masks ──
        int playerWorldX = gp.player.worldX;
        int playerWorldY = gp.player.worldY;
        int playerScreenX = gp.player.screenX;
        int playerScreenY = gp.player.screenY;

        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] == null || !gp.obj[i].lightSource || gp.obj[i].lightRadius <= 0) continue;

            int tx = gp.obj[i].worldX - playerWorldX + playerScreenX + gp.tileSize / 2;
            int ty = gp.obj[i].worldY - playerWorldY + playerScreenY + gp.tileSize / 2;
            int tRad = gp.obj[i].lightRadius * gp.tileSize;

            // Frustum cull
            if (tx + tRad < 0 || tx - tRad > screenWidth || ty + tRad < 0 || ty - tRad > screenHeight) continue;

            BufferedImage torchMask = getDstOutLight(tRad);
            og.drawImage(torchMask, tx - tRad, ty - tRad, null);
        }

        // ONE draw call blits the entire darkness layer
        g2.drawImage(darknessOverlay, 0, 0, null);

        // ===================== COLORED LIGHT PASS (unchanged) =====================
        if (lightCount > 0 && currentMaxDarkness > 0.05f) {
            Composite saved = g2.getComposite();
            for (int i = 0; i < lightCount; i++) {
                int lx  = lightWX[i] - playerWorldX + playerScreenX;
                int ly  = lightWY[i] - playerWorldY + playerScreenY;
                int rad = lightRadiusPx[i];
                if (lx + rad < 0 || lx - rad > screenWidth || ly + rad < 0 || ly - rad > screenHeight) continue;
                BufferedImage grad = getGradient(lightColor[i], rad);
                float a = lightIntensity[i] * currentMaxDarkness;
                if (a > 1f) a = 1f;
                if (a < 0.01f) continue;
                g2.setComposite(cachedAlpha(a));
                g2.drawImage(grad, lx - rad, ly - rad, null);
            }
            g2.setComposite(saved);
        }
    }
}
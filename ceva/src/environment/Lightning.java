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
    // All tile darkness values are written into this pixel array, then blitted
    // with ONE g2.drawImage() call instead of ~450 individual fillRect calls.
    private BufferedImage darknessOverlay;
    private int[] darknessPixels;   // direct reference to overlay's backing int[] (ARGB)
    private int overlayWidth = 0, overlayHeight = 0;

    // OPTIMIZATION: Pre-computed squared distance lookup for player light
    private static final int LIGHT_RADIUS = 7;
    
    // OPTIMIZATION: Pre-computed alpha lookup table for player light
    // Index by (distSq * 100), maps squared distance to alpha [0..1]
    // Max squared distance we care about = LIGHT_RADIUS^2 = 49
    private static final int LIGHT_LUT_SIZE = 50; // 0..49
    private final float[] lightAlphaLUT = new float[LIGHT_LUT_SIZE + 1];

    // ===================== COLORED LIGHT REGISTRY =====================
    private static final int MAX_LIGHTS = 20;
    public int[] lightWX = new int[MAX_LIGHTS];
    public int[] lightWY = new int[MAX_LIGHTS];
    public int[] lightRadiusPx = new int[MAX_LIGHTS];   // radius in pixels
    public Color[] lightColor = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // Gradient image cache keyed by (r << 48 | g << 40 | b << 32 | radiusPx)
    private final HashMap<Long, BufferedImage> gradientCache = new HashMap<>();

    // OPTIMIZATION: AlphaComposite cache — avoids per-light allocation each frame
    private static final int ALPHA_CACHE_SIZE = 101; // 0..100 → alpha 0.00..1.00
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
        darknessOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        darknessPixels  = ((DataBufferInt) darknessOverlay.getRaster().getDataBuffer()).getData();
        overlayWidth    = w;
        overlayHeight   = h;
    }

    public Lightning(GamePanel gp2) {
        this.gp = gp2;
        // Pre-compute light alpha: alpha = sqrt(distSq) / LIGHT_RADIUS
        for (int i = 0; i <= LIGHT_LUT_SIZE; i++) {
            lightAlphaLUT[i] = (float) Math.sqrt(i) / LIGHT_RADIUS;
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

        // Clear overlay to fully transparent — one bulk array fill instead of skipping tiles
        Arrays.fill(darknessPixels, 0);

        // Viewport center in tile coords (player is always centered)
        int viewCenterCol = gp.player.worldX / gp.tileSize;
        int viewCenterRow = gp.player.worldY / gp.tileSize;

        // Sub-tile fractional player position for smooth light centering
        float playerTileColF = (float) gp.player.worldX / gp.tileSize;
        float playerTileRowF = (float) gp.player.worldY / gp.tileSize;

        int screenTilesX = (screenWidth  / gp.tileSize) / 2 + 2;
        int screenTilesY = (screenHeight / gp.tileSize) / 2 + 2;
        int leftCol   = viewCenterCol - screenTilesX;
        int rightCol  = viewCenterCol + screenTilesX;
        int topRow    = viewCenterRow - screenTilesY;
        int bottomRow = viewCenterRow + screenTilesY;

        // Pre-compute torch data to avoid inner-loop array access
        int torchCount = 0;
        int[]   torchCol       = new int[gp.obj.length];
        int[]   torchRow       = new int[gp.obj.length];
        int[]   torchRadiusSq  = new int[gp.obj.length];
        float[] torchRadiusInv = new float[gp.obj.length];
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] != null && gp.obj[i].lightSource && gp.obj[i].lightRadius > 0) {
                torchCol[torchCount]       = gp.obj[i].worldX / gp.tileSize;
                torchRow[torchCount]       = gp.obj[i].worldY / gp.tileSize;
                torchRadiusSq[torchCount]  = gp.obj[i].lightRadius * gp.obj[i].lightRadius;
                torchRadiusInv[torchCount] = 1.0f / gp.obj[i].lightRadius;
                torchCount++;
            }
        }

        int   tileSize     = gp.tileSize;
        int   playerWorldX = gp.player.worldX;
        int   playerWorldY = gp.player.worldY;
        int   playerScreenX= gp.player.screenX;
        int   playerScreenY= gp.player.screenY;
        int   maxAlpha     = Math.min(255, (int)(currentMaxDarkness * 255 + 0.5f));

        for (int col = leftCol; col <= rightCol; col++) {
            for (int row = topRow; row <= bottomRow; row++) {

                int screenX = col * tileSize - playerWorldX + playerScreenX;
                int screenY = row * tileSize - playerWorldY + playerScreenY;

                // Early frustum cull — skip tiles fully outside the screen
                if (screenX + tileSize <= 0 || screenX >= screenWidth ||
                    screenY + tileSize <= 0 || screenY >= screenHeight) continue;

                // Player light alpha via LUT (avoids sqrt per tile)
                float distX  = col - playerTileColF;
                float distY  = row - playerTileRowF;
                float distSq = distX * distX + distY * distY;
                float darknessAlpha;
                int   distSqInt = (int)(distSq + 0.5f);
                if (distSqInt <= LIGHT_LUT_SIZE) {
                    darknessAlpha = lightAlphaLUT[distSqInt];
                } else {
                    darknessAlpha = 1.0f;
                }

                // Torch lights
                for (int t = 0; t < torchCount; t++) {
                    float tDx    = col - torchCol[t];
                    float tDy    = row - torchRow[t];
                    float tDistSq= tDx * tDx + tDy * tDy;
                    if (tDistSq >= torchRadiusSq[t] && darknessAlpha <= 1.0f) continue;
                    float torchAlpha = (float) Math.sqrt(tDistSq) * torchRadiusInv[t];
                    if (torchAlpha < darknessAlpha) darknessAlpha = torchAlpha;
                }

                if (darknessAlpha <= 0f) continue; // fully lit — nothing to write

                // Convert to 0-255 alpha, clamped to max darkness
                int alpha = (int)((darknessAlpha >= currentMaxDarkness ? currentMaxDarkness : darknessAlpha) * 255 + 0.5f);
                if (alpha <= 0) continue;
                if (alpha > maxAlpha) alpha = maxAlpha;

                // Pure black pixel with computed alpha
                int argb = (alpha << 24);

                // Clamp tile rect to screen bounds, then fill via Arrays.fill per row
                // (bulk array writes — far faster than individual Java2D draw calls)
                int x0 = Math.max(0, screenX);
                int y0 = Math.max(0, screenY);
                int x1 = Math.min(screenWidth,  screenX + tileSize);
                int y1 = Math.min(screenHeight, screenY + tileSize);
                for (int py = y0; py < y1; py++) {
                    Arrays.fill(darknessPixels, py * screenWidth + x0, py * screenWidth + x1, argb);
                }
            }
        }

        // ONE draw call blits the entire darkness layer — replaces ~450 fillRect calls
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
                float alpha = lightIntensity[i] * currentMaxDarkness;
                if (alpha > 1f) alpha = 1f;
                if (alpha < 0.01f) continue;
                g2.setComposite(cachedAlpha(alpha));
                g2.drawImage(grad, lx - rad, ly - rad, null);
            }
            g2.setComposite(saved);
        }
    }
}
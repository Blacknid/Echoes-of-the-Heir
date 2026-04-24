package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import main.GamePanel;

public class Lightning {

    GamePanel gp;
    public int dayState;

    // ===================== DARKNESS OVERLAY =====================
    // Single screen-sized image filled with flat darkness; lights punch holes in it.
    private BufferedImage darknessOverlay;
    private int[] darknessPixels;
    private int overlayWidth = 0, overlayHeight = 0;
    private Graphics2D overlayG2; // cached — never recreated unless screen resizes

    // ===================== PLAYER LIGHT =====================
    // Radius in tiles. Minimum 1, recommended 3–15.
    public int playerLightRadius = 3;

    // ===================== COLORED LIGHT REGISTRY =====================
    private static final int MAX_LIGHTS = 20;
    public int[] lightWX        = new int[MAX_LIGHTS];
    public int[] lightWY        = new int[MAX_LIGHTS];
    public int[] lightRadiusPx  = new int[MAX_LIGHTS];
    public Color[] lightColor   = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // ===================== IMAGE CACHES =====================
    // Pre-rendered DstOut radial mask per radius (computed once, never redrawn).
    private final HashMap<Integer, BufferedImage> dstOutLightCache = new HashMap<>();

    // Colored-light gradient images per (r,g,b,radius) key (computed once).
    private final HashMap<Long, BufferedImage> gradientCache = new HashMap<>();

    // Per-radius light-mask images (size = 2*rad × 2*rad).
    // Reused every frame: cleared and redrawn, but the allocation is cached.
    // KEY CHANGE vs old code: old masks were (2*rad + 2*extend)^2 — up to 3328×3328 pixels.
    // New masks are exactly 2*rad × 2*rad — ~25× smaller per torch.
    private final HashMap<Integer, BufferedImage> lightMaskImages = new HashMap<>();
    private final HashMap<Integer, Graphics2D>    lightMaskG2s    = new HashMap<>();
    private final HashMap<Integer, int[]>         lightMaskPixels = new HashMap<>();

    // ===================== SHADOW GEOMETRY CACHE =====================
    // Torch shadow polys are computed ONCE per map load (torches never move).
    // Player shadow polys are recomputed only when the player moves > PLAYER_SHADOW_DIRTY_PX.
    // Each poly = float[8]: {sx,sy, ex,ey, pex,pey, psx,psy} in world coords relative to light center.
    private final HashMap<Integer, List<float[]>> torchShadowPolys = new HashMap<>();
    private boolean torchShadowCacheDirty = true;

    private List<float[]> playerShadowPolys      = null;
    private int playerLastCacheWorldX            = Integer.MIN_VALUE;
    private int playerLastCacheWorldY            = Integer.MIN_VALUE;
    private static final int PLAYER_SHADOW_DIRTY_PX = 8;

    // ===================== REUSABLE OBJECTS (zero per-frame allocation) =====================
    private final Path2D.Double reusableShadowPath = new Path2D.Double();
    private final ArrayList<Rectangle> reusableOccluders = new ArrayList<>(32);
    private final Rectangle reusableWorldRect = new Rectangle();

    // Pre-computed AlphaComposite cache.
    private static final int ALPHA_CACHE_SIZE = 101;
    private static final AlphaComposite[] alphaCache = new AlphaComposite[ALPHA_CACHE_SIZE];
    static {
        for (int i = 0; i < ALPHA_CACHE_SIZE; i++) {
            alphaCache[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, i / 100f);
        }
    }
    private static AlphaComposite cachedAlpha(float alpha) {
        int idx = Math.round(alpha * 100f);
        if (idx < 0) idx = 0;
        else if (idx >= ALPHA_CACHE_SIZE) idx = ALPHA_CACHE_SIZE - 1;
        return alphaCache[idx];
    }

    // ===================== CONSTRUCTOR =====================
    public Lightning(GamePanel gp2) {
        this.gp = gp2;
    }

    // ===================== COLORED LIGHT API =====================
    public void clearLights() { lightCount = 0; }

    public void addLight(int worldX, int worldY, int radiusPx, Color color, float intensity) {
        if (lightCount >= MAX_LIGHTS) return;
        lightWX[lightCount]        = worldX;
        lightWY[lightCount]        = worldY;
        lightRadiusPx[lightCount]  = radiusPx;
        lightColor[lightCount]     = color;
        lightIntensity[lightCount] = intensity;
        lightCount++;
    }

    /**
     * Call this whenever collision geometry changes (map load/change).
     * Forces torch shadow polys to be recomputed on the next draw call.
     */
    public void clearShadowCaches() {
        torchShadowPolys.clear();
        torchShadowCacheDirty = true;
        playerShadowPolys     = null;
        playerLastCacheWorldX = Integer.MIN_VALUE;
        playerLastCacheWorldY = Integer.MIN_VALUE;
    }

    // ===================== PRIVATE HELPERS =====================

    /** Allocate (or re-allocate on resize) the darkness overlay. */
    private void ensureOverlay(int w, int h) {
        if (darknessOverlay != null && overlayWidth == w && overlayHeight == h) return;
        if (overlayG2 != null) { overlayG2.dispose(); overlayG2 = null; }
        darknessOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        darknessPixels  = ((DataBufferInt) darknessOverlay.getRaster().getDataBuffer()).getData();
        overlayWidth    = w;
        overlayHeight   = h;
        overlayG2       = darknessOverlay.createGraphics();
    }

    /** Get (or create) a light-mask image for the given radius (size = 2*rad × 2*rad). */
    private BufferedImage ensureLightMaskImage(int rad) {
        BufferedImage img = lightMaskImages.get(rad);
        if (img != null) return img;
        img = new BufferedImage(rad * 2, rad * 2, BufferedImage.TYPE_INT_ARGB);
        lightMaskImages.put(rad, img);
        lightMaskG2s.put(rad, img.createGraphics());
        lightMaskPixels.put(rad, ((DataBufferInt) img.getRaster().getDataBuffer()).getData());
        return img;
    }

    /**
     * Get (or create) the pre-rendered DstOut radial gradient for the given radius.
     * This is a white-to-transparent circle: when composited with DstOut it punches a
     * soft-edged circular hole in the darkness overlay.
     */
    private BufferedImage getDstOutLight(int radiusPx) {
        BufferedImage img = dstOutLightCache.get(radiusPx);
        if (img != null) return img;
        int diameter = radiusPx * 2;
        img = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Point2D center = new Point2D.Float(radiusPx, radiusPx);
        float[] dist   = { 0.0f, 0.35f, 0.7f, 1.0f };
        Color[] colors = {
            new Color(255, 255, 255, 255),
            new Color(255, 255, 255, 220),
            new Color(255, 255, 255, 100),
            new Color(255, 255, 255, 0)
        };
        ig.setPaint(new RadialGradientPaint(center, radiusPx, dist, colors));
        ig.fillOval(0, 0, diameter, diameter);
        ig.dispose();
        dstOutLightCache.put(radiusPx, img);
        return img;
    }

    /** Get (or create) a colored glow gradient image for the given color and radius. */
    private BufferedImage getGradient(Color c, int radiusPx) {
        long key = ((long)(c.getRed()   & 0xFF) << 48)
                 | ((long)(c.getGreen() & 0xFF) << 40)
                 | ((long)(c.getBlue()  & 0xFF) << 32)
                 | (radiusPx & 0xFFFFFFFFL);
        BufferedImage img = gradientCache.get(key);
        if (img != null) return img;
        int diameter = radiusPx * 2;
        img = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Point2D center = new Point2D.Float(radiusPx, radiusPx);
        float[] dist   = { 0.0f, 0.4f, 1.0f };
        Color[] colors = {
            new Color(c.getRed(), c.getGreen(), c.getBlue(), 180),
            new Color(c.getRed(), c.getGreen(), c.getBlue(),  80),
            new Color(c.getRed(), c.getGreen(), c.getBlue(),   0)
        };
        ig.setPaint(new RadialGradientPaint(center, radiusPx, dist, colors));
        ig.fillOval(0, 0, diameter, diameter);
        ig.dispose();
        gradientCache.put(key, img);
        return img;
    }

    /**
     * Compute shadow-polygon vertices for a light source at (lightWX, lightWY).
     * Uses the occluder list already populated in reusableOccluders.
     * Returns a list of float[8] quads in world-relative coords (origin = light center).
     * Format: {nearS.x, nearS.y, nearE.x, nearE.y, farE.x, farE.y, farS.x, farS.y}
     *
     * These coords are STABLE between frames for static lights (torches never move),
     * so the list can be cached and reused every frame without recomputation.
     */
    private List<float[]> computeShadowPolysWorld(int lightWX, int lightWY, int rad,
                                                   List<Rectangle> occluders) {
        List<float[]> polys = new ArrayList<>();
        if (occluders.isEmpty()) return polys;

        // Project shadows far enough to exceed the 2*rad mask boundary in every direction.
        double projLen = rad * 3.0;

        double[] ang = new double[4];
        int[]    idx = new int[4];

        for (Rectangle br : occluders) {
            // Corner positions relative to light center (world space)
            double c0x = br.x - lightWX,               c0y = br.y - lightWY;
            double c1x = br.x + br.width - lightWX,    c1y = br.y - lightWY;
            double c2x = br.x + br.width - lightWX,    c2y = br.y + br.height - lightWY;
            double c3x = br.x - lightWX,               c3y = br.y + br.height - lightWY;

            // Skip occluder that contains the light center
            if (c0x <= 0 && c1x >= 0 && c0y <= 0 && c3y >= 0) continue;

            double[][] corners = { {c0x, c0y}, {c1x, c1y}, {c2x, c2y}, {c3x, c3y} };

            // Compute angle of each corner from the light center
            for (int k = 0; k < 4; k++) {
                double a = Math.atan2(corners[k][1], corners[k][0]);
                if (a < 0) a += Math.PI * 2;
                ang[k] = a;
                idx[k] = k;
            }

            // Insertion sort by angle (4 elements — cheapest possible sort)
            for (int a = 1; a < 4; a++) {
                int v = idx[a]; double av = ang[v]; int j = a - 1;
                while (j >= 0 && ang[idx[j]] > av) { idx[j + 1] = idx[j]; j--; }
                idx[j + 1] = v;
            }

            // Identify the largest angular gap (= back face of occluder from light's view)
            double largestGap = -1; int gapIdx = 0;
            for (int a = 0; a < 4; a++) {
                int b = (a + 1) % 4;
                double gap = (b == 0)
                    ? (ang[idx[0]] + Math.PI * 2) - ang[idx[a]]
                    : ang[idx[b]] - ang[idx[a]];
                if (gap < 0) gap += Math.PI * 2;
                if (gap > largestGap) { largestGap = gap; gapIdx = a; }
            }

            // The two silhouette corners are the ones on either side of the largest gap
            int s = idx[(gapIdx + 1) % 4];
            int e = idx[gapIdx];

            double sx = corners[s][0], sy = corners[s][1];
            double ex = corners[e][0], ey = corners[e][1];

            double slen = Math.hypot(sx, sy); if (slen < 1e-4) continue;
            double elen = Math.hypot(ex, ey); if (elen < 1e-4) continue;

            // Project far vertices along the same direction
            double psx = sx + (sx / slen) * projLen;
            double psy = sy + (sy / slen) * projLen;
            double pex = ex + (ex / elen) * projLen;
            double pey = ey + (ey / elen) * projLen;

            polys.add(new float[] {
                (float) sx,  (float) sy,
                (float) ex,  (float) ey,
                (float) pex, (float) pey,
                (float) psx, (float) psy
            });
        }
        return polys;
    }

    /**
     * Draw a single light onto the darkness overlay.
     *  1. Gets the per-radius light-mask image (cached, 2*rad × 2*rad).
     *  2. Clears it via direct pixel array write (fastest possible clear).
     *  3. Draws the pre-rendered radial gradient into it.
     *  4. Erases shadow areas (DstOut white polygons) using world-relative cached polys.
     *  5. Composites the mask onto the darkness overlay with DstOut (punches the hole).
     *
     * @param og          Graphics2D of darknessOverlay (reused across all lights)
     * @param sx          screen X of light center
     * @param sy          screen Y of light center
     * @param rad         light radius in pixels
     * @param shadowPolys world-relative shadow quads (may be null or empty = no shadows)
     */
    private void drawLight(Graphics2D og, int sx, int sy, int rad, List<float[]> shadowPolys) {
        BufferedImage mask = ensureLightMaskImage(rad);
        Graphics2D    mg   = lightMaskG2s.get(rad);
        int[]         px   = lightMaskPixels.get(rad);

        // 1. Clear mask pixels directly — much faster than mg.clearRect() for small images
        Arrays.fill(px, 0);

        // 2. Draw pre-rendered radial gradient (white center → transparent edge)
        mg.setComposite(AlphaComposite.SrcOver);
        mg.drawImage(getDstOutLight(rad), 0, 0, null);

        // 3. Erase shadow areas from the mask (DstOut: bright white polys kill the light)
        if (shadowPolys != null && !shadowPolys.isEmpty()) {
            mg.setComposite(AlphaComposite.DstOut);
            mg.setColor(Color.WHITE);
            for (float[] poly : shadowPolys) {
                // poly coords are world-relative; add rad to shift into mask-local space
                reusableShadowPath.reset();
                reusableShadowPath.moveTo(poly[0] + rad, poly[1] + rad);
                reusableShadowPath.lineTo(poly[2] + rad, poly[3] + rad);
                reusableShadowPath.lineTo(poly[4] + rad, poly[5] + rad);
                reusableShadowPath.lineTo(poly[6] + rad, poly[7] + rad);
                reusableShadowPath.closePath();
                mg.fill(reusableShadowPath);
            }
        }

        // 4. Punch the light hole into the darkness overlay
        og.setComposite(AlphaComposite.DstOut);
        og.drawImage(mask, sx - rad, sy - rad, null);
    }

    // ===================== MAIN DRAW =====================
    public void draw(Graphics2D g2, float currentMaxDarkness) {
        if (currentMaxDarkness <= 0.001f) return;

        int screenWidth  = gp.screenWidth;
        int screenHeight = gp.screenHeight;

        ensureOverlay(screenWidth, screenHeight);

        // Fill overlay with flat darkness
        int darkArgb = ((int)(currentMaxDarkness * 255 + 0.5f)) << 24; // black, variable alpha
        Arrays.fill(darknessPixels, darkArgb);

        Graphics2D og = overlayG2;

        int playerWorldX  = gp.player.worldX;
        int playerWorldY  = gp.player.worldY;
        int playerScreenX = gp.player.screenX;
        int playerScreenY = gp.player.screenY;
        int playerSX = playerScreenX + gp.tileSize / 2;
        int playerSY = playerScreenY + gp.tileSize / 2;

        // ========= PLAYER LIGHT =========
        int lightPx = playerLightRadius * gp.tileSize;

        // Recompute player shadow polys only when player has moved significantly.
        // This is the only geometry that changes per-movement; torches are fully static.
        int dxP = playerWorldX - playerLastCacheWorldX;
        int dyP = playerWorldY - playerLastCacheWorldY;
        if (playerShadowPolys == null
                || dxP * dxP + dyP * dyP > PLAYER_SHADOW_DIRTY_PX * PLAYER_SHADOW_DIRTY_PX) {
            reusableOccluders.clear();
            reusableWorldRect.setBounds(
                    playerWorldX - lightPx, playerWorldY - lightPx, lightPx * 2, lightPx * 2);
            gp.cChecker.getCollisionBoundsInRect(reusableWorldRect, reusableOccluders);
            playerShadowPolys     = computeShadowPolysWorld(playerWorldX, playerWorldY, lightPx,
                                                            reusableOccluders);
            playerLastCacheWorldX = playerWorldX;
            playerLastCacheWorldY = playerWorldY;
        }

        drawLight(og, playerSX, playerSY, lightPx, playerShadowPolys);

        // ========= TORCH LIGHTS =========
        // Shadow polys are cached per obj-array index. torchShadowCacheDirty is set true on
        // every map change (clearShadowCaches()), so polys are recomputed once per map.
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] == null || !gp.obj[i].lightSource || gp.obj[i].lightRadius <= 0)
                continue;

            int tx   = gp.obj[i].worldX - playerWorldX + playerScreenX + gp.tileSize / 2;
            int ty   = gp.obj[i].worldY - playerWorldY + playerScreenY + gp.tileSize / 2;
            int tRad = gp.obj[i].lightRadius * gp.tileSize;

            if (tx + tRad < 0 || tx - tRad > screenWidth
                    || ty + tRad < 0 || ty - tRad > screenHeight) continue;

            if (torchShadowCacheDirty || !torchShadowPolys.containsKey(i)) {
                int twx = gp.obj[i].worldX + gp.tileSize / 2;
                int twy = gp.obj[i].worldY + gp.tileSize / 2;
                reusableOccluders.clear();
                reusableWorldRect.setBounds(twx - tRad, twy - tRad, tRad * 2, tRad * 2);
                gp.cChecker.getCollisionBoundsInRect(reusableWorldRect, reusableOccluders);
                torchShadowPolys.put(i, computeShadowPolysWorld(twx, twy, tRad, reusableOccluders));
            }

            drawLight(og, tx, ty, tRad, torchShadowPolys.get(i));
        }
        torchShadowCacheDirty = false;

        // Blit the completed darkness layer to the main screen in one call.
        g2.drawImage(darknessOverlay, 0, 0, null);

        // ========= COLORED LIGHTS (ambient glow, no shadow casting) =========
        // These are secondary decorative lights (player warm glow, torch color).
        // Shadow occlusion is imperceptible at the intensities used (~0.25), so we skip it
        // entirely and just alpha-blend the gradient directly — zero extra masking cost.
        if (lightCount > 0 && currentMaxDarkness > 0.05f) {
            Composite saved = g2.getComposite();
            for (int i = 0; i < lightCount; i++) {
                int lx  = lightWX[i] - playerWorldX + playerScreenX;
                int ly  = lightWY[i] - playerWorldY + playerScreenY;
                int rad = lightRadiusPx[i];
                if (lx + rad < 0 || lx - rad > screenWidth
                        || ly + rad < 0 || ly - rad > screenHeight) continue;

                float a = lightIntensity[i] * currentMaxDarkness;
                if (a > 1f) a = 1f;
                if (a < 0.01f) continue;

                g2.setComposite(cachedAlpha(a));
                g2.drawImage(getGradient(lightColor[i], rad), lx - rad, ly - rad, null);
            }
            g2.setComposite(saved);
        }
    }
}
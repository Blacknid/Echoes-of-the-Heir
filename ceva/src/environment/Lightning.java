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
    // Rendered at screenW/lightDownscale × screenH/lightDownscale for performance;
    // scaled back to full screen on blit. lightDownscale=1 → full res.
    private BufferedImage darknessOverlay;
    private int[] darknessPixels;
    private int overlayWidth = 0, overlayHeight = 0;
    private Graphics2D overlayG2;

    /**
     * Render quality / performance control.
     *   1 = full resolution (best quality, highest GPU cost)
     *   2 = half resolution (default — good quality, ~4× less overlay fill work)
     *   4 = quarter resolution (low-end hardware)
     * Change at runtime: setLightDownscale(2).
     */
    public int lightDownscale = 2;

    // ===================== PLAYER LIGHT =====================
    public int playerLightRadius = 3;

    // ===================== COLORED LIGHT REGISTRY =====================
    private static final int MAX_LIGHTS = 20;
    public int[]   lightWX        = new int[MAX_LIGHTS];
    public int[]   lightWY        = new int[MAX_LIGHTS];
    public int[]   lightRadiusPx  = new int[MAX_LIGHTS];
    public Color[] lightColor     = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // ===================== IMAGE CACHES =====================
    private final HashMap<Integer, BufferedImage> dstOutLightCache = new HashMap<>();
    private final HashMap<Long,    BufferedImage> gradientCache    = new HashMap<>();
    private final HashMap<Integer, BufferedImage> lightMaskImages  = new HashMap<>();
    private final HashMap<Integer, Graphics2D>    lightMaskG2s     = new HashMap<>();
    private final HashMap<Integer, int[]>         lightMaskPixels  = new HashMap<>();

    // ===================== SHADOW GEOMETRY CACHE =====================
    // Torch polys: computed once per map load (torches never move).
    // Player polys: recomputed only when player moves > PLAYER_SHADOW_DIRTY_PX world pixels.
    // Format: float[8] = {nearS.x, nearS.y, nearE.x, nearE.y, farE.x, farE.y, farS.x, farS.y}
    // Coords are world-space, relative to the light center.
    private final HashMap<Integer, List<float[]>> torchShadowPolys = new HashMap<>();
    private boolean torchShadowCacheDirty = true;

    private List<float[]> playerShadowPolys      = null;
    private int playerLastCacheWorldX            = Integer.MIN_VALUE;
    private int playerLastCacheWorldY            = Integer.MIN_VALUE;
    // Increased to 16 px: halves shadow-recomputation frequency vs the old 8 px threshold.
    private static final int PLAYER_SHADOW_DIRTY_PX = 16;

    // ===================== REUSABLE OBJECTS (zero per-frame allocation) =====================
    private final Path2D.Double        reusableShadowPath = new Path2D.Double();
    private final ArrayList<Rectangle> reusableOccluders  = new ArrayList<>(32);
    private final Rectangle            reusableWorldRect  = new Rectangle();

    // Warm highlight color for reflective tiles (water, crystals, polished stone, …)
    private static final Color REFLECT_GLOW = new Color(255, 245, 200);

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
    public Lightning(GamePanel gp2) { this.gp = gp2; }

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
        // Also clear per-radius mask caches so they are rebuilt at the new downscale
        lightMaskImages.clear();
        lightMaskG2s.clear();
        lightMaskPixels.clear();
        dstOutLightCache.clear();
    }

    // ===================== PRIVATE HELPERS =====================

    private void ensureOverlay(int w, int h) {
        if (darknessOverlay != null && overlayWidth == w && overlayHeight == h) return;
        if (overlayG2 != null) { overlayG2.dispose(); overlayG2 = null; }
        darknessOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        darknessPixels  = ((DataBufferInt) darknessOverlay.getRaster().getDataBuffer()).getData();
        overlayWidth    = w;
        overlayHeight   = h;
        overlayG2       = darknessOverlay.createGraphics();
    }

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
     * Pre-rendered white radial gradient (center fully opaque → edge transparent).
     * Composited with DstOut to punch a soft-edged hole in the darkness overlay.
     */
    private BufferedImage getDstOutLight(int radiusPx) {
        BufferedImage img = dstOutLightCache.get(radiusPx);
        if (img != null) return img;
        int diameter = radiusPx * 2;
        img = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Point2D center = new Point2D.Float(radiusPx, radiusPx);
        float[] dist   = { 0.0f, 0.30f, 0.65f, 1.0f };
        Color[] colors = {
            new Color(255, 255, 255, 255),
            new Color(255, 255, 255, 230),
            new Color(255, 255, 255, 110),
            new Color(255, 255, 255,   0)
        };
        ig.setPaint(new RadialGradientPaint(center, radiusPx, dist, colors));
        ig.fillOval(0, 0, diameter, diameter);
        ig.dispose();
        dstOutLightCache.put(radiusPx, img);
        return img;
    }

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
     * Compute shadow-polygon vertices for a light at (lightWX, lightWY).
     * Returns float[8] quads in world-relative coords (origin = light center).
     * Format: {nearS.x, nearS.y, nearE.x, nearE.y, farE.x, farE.y, farS.x, farS.y}
     *
     * Only axis-aligned rectangles (from lightOccluderRects) are passed here —
     * bounding boxes of polygons/ellipses are intentionally excluded to prevent
     * false shadows in open areas.
     */
    private List<float[]> computeShadowPolysWorld(int lightWX, int lightWY, int rad,
                                                   List<Rectangle> occluders) {
        List<float[]> polys = new ArrayList<>();
        if (occluders.isEmpty()) return polys;

        double projLen = rad * 3.0;

        double[] ang = new double[4];
        int[]    idx = new int[4];

        for (Rectangle br : occluders) {
            double c0x = br.x - lightWX,               c0y = br.y - lightWY;
            double c1x = br.x + br.width - lightWX,    c1y = br.y - lightWY;
            double c2x = br.x + br.width - lightWX,    c2y = br.y + br.height - lightWY;
            double c3x = br.x - lightWX,               c3y = br.y + br.height - lightWY;

            // Skip occluder that fully contains the light center
            if (c0x <= 0 && c1x >= 0 && c0y <= 0 && c3y >= 0) continue;

            double[][] corners = { {c0x, c0y}, {c1x, c1y}, {c2x, c2y}, {c3x, c3y} };

            for (int k = 0; k < 4; k++) {
                double a = Math.atan2(corners[k][1], corners[k][0]);
                if (a < 0) a += Math.PI * 2;
                ang[k] = a;
                idx[k] = k;
            }

            // Insertion sort by angle (4 elements — minimal cost)
            for (int a = 1; a < 4; a++) {
                int v = idx[a]; double av = ang[v]; int j = a - 1;
                while (j >= 0 && ang[idx[j]] > av) { idx[j + 1] = idx[j]; j--; }
                idx[j + 1] = v;
            }

            // Largest angular gap = back face; silhouette corners flank this gap
            double largestGap = -1; int gapIdx = 0;
            for (int a = 0; a < 4; a++) {
                int b = (a + 1) % 4;
                double gap = (b == 0)
                    ? (ang[idx[0]] + Math.PI * 2) - ang[idx[a]]
                    : ang[idx[b]] - ang[idx[a]];
                if (gap < 0) gap += Math.PI * 2;
                if (gap > largestGap) { largestGap = gap; gapIdx = a; }
            }

            int s = idx[(gapIdx + 1) % 4];
            int e = idx[gapIdx];

            double sx = corners[s][0], sy = corners[s][1];
            double ex = corners[e][0], ey = corners[e][1];

            double slen = Math.hypot(sx, sy); if (slen < 1e-4) continue;
            double elen = Math.hypot(ex, ey); if (elen < 1e-4) continue;

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
     * Draw one light source onto the darkness overlay.
     *
     * @param og          Graphics2D of the (possibly downscaled) darkness overlay
     * @param sx          overlay-space X of light center  (screenX / ds)
     * @param sy          overlay-space Y of light center  (screenY / ds)
     * @param rad         light radius in overlay pixels   (worldPx / ds)
     * @param shadowPolys world-relative shadow quads (null/empty = no shadows)
     * @param polyScale   factor to convert world-relative poly coords → overlay pixels (= 1/ds)
     */
    private void drawLight(Graphics2D og, int sx, int sy, int rad,
                           List<float[]> shadowPolys, float polyScale) {
        BufferedImage mask = ensureLightMaskImage(rad);
        Graphics2D    mg   = lightMaskG2s.get(rad);
        int[]         px   = lightMaskPixels.get(rad);

        Arrays.fill(px, 0);

        mg.setComposite(AlphaComposite.SrcOver);
        mg.drawImage(getDstOutLight(rad), 0, 0, null);

        if (shadowPolys != null && !shadowPolys.isEmpty()) {
            mg.setComposite(AlphaComposite.DstOut);
            mg.setColor(Color.WHITE);
            for (float[] poly : shadowPolys) {
                // World-relative coords × polyScale → overlay-local, then offset by rad (center)
                reusableShadowPath.reset();
                reusableShadowPath.moveTo(poly[0] * polyScale + rad, poly[1] * polyScale + rad);
                reusableShadowPath.lineTo(poly[2] * polyScale + rad, poly[3] * polyScale + rad);
                reusableShadowPath.lineTo(poly[4] * polyScale + rad, poly[5] * polyScale + rad);
                reusableShadowPath.lineTo(poly[6] * polyScale + rad, poly[7] * polyScale + rad);
                reusableShadowPath.closePath();
                mg.fill(reusableShadowPath);
            }
        }

        og.setComposite(AlphaComposite.DstOut);
        og.drawImage(mask, sx - rad, sy - rad, null);
    }

    /**
     * Mark all tile positions within a circular light radius as lit in tileM.tileIsLit.
     * Uses a bounding-box pre-filter then a per-tile distance check.
     */
    private void markTilesLit(int lightWX, int lightWY, int radiusPx) {
        boolean[][] litMap = gp.tileM.tileIsLit;
        if (litMap == null) return;
        int ts      = gp.tileSize;
        int maxCol  = litMap.length;
        int maxRow  = maxCol > 0 ? litMap[0].length : 0;
        int minCol  = Math.max(0, (lightWX - radiusPx) / ts);
        int maxColI = Math.min(maxCol - 1, (lightWX + radiusPx) / ts);
        int minRow  = Math.max(0, (lightWY - radiusPx) / ts);
        int maxRowI = Math.min(maxRow - 1, (lightWY + radiusPx) / ts);
        int r2 = radiusPx * radiusPx;
        for (int col = minCol; col <= maxColI; col++) {
            for (int row = minRow; row <= maxRowI; row++) {
                int tcx = col * ts + ts / 2;
                int tcy = row * ts + ts / 2;
                int dx = tcx - lightWX, dy = tcy - lightWY;
                if (dx * dx + dy * dy <= r2) litMap[col][row] = true;
            }
        }
    }

    /**
     * After the darkness overlay has been blitted, draw a warm highlight over every visible
     * tile marked as reflectsLight=true that is currently within a light's radius.
     * Intensity falls off quadratically with distance. The highlight is drawn with
     * SRC_OVER onto the already-lit game world, making polished/wet surfaces sparkle.
     */
    private void drawReflectiveHighlights(Graphics2D g2, float maxDarkness,
                                          int playerWX, int playerWY,
                                          int playerSX, int playerSY) {
        if (maxDarkness < 0.04f) return;
        boolean[] gidRefl = gp.tileM.gidToReflectsLight;
        boolean[][] litMap = gp.tileM.tileIsLit;
        if (gidRefl == null || litMap == null) return;

        int ts          = gp.tileSize;
        int cameraWX    = playerWX - playerSX;
        int cameraWY    = playerWY - playerSY;
        int minCol      = Math.max(0, cameraWX / ts - 1);
        int maxCol      = Math.min(gp.maxWorldCol - 1, (cameraWX + gp.screenWidth)  / ts + 1);
        int minRow      = Math.max(0, cameraWY / ts - 1);
        int maxRow      = Math.min(gp.maxWorldRow - 1, (cameraWY + gp.screenHeight) / ts + 1);

        Composite saved = g2.getComposite();
        g2.setColor(REFLECT_GLOW);

        for (int col = minCol; col <= maxCol; col++) {
            for (int row = minRow; row <= maxRow; row++) {
                if (col >= litMap.length || row >= litMap[col].length || !litMap[col][row]) continue;

                // Check if any map layer has a reflective tile here
                boolean hasReflective = false;
                for (int l = 0; l < gp.tileM.mapLayers.size(); l++) {
                    int gid = gp.tileM.mapLayers.get(l)[col][row];
                    if (gid > 0 && gid < gidRefl.length && gidRefl[gid]) {
                        hasReflective = true;
                        break;
                    }
                }
                if (!hasReflective) continue;

                // Compute best (strongest) light intensity reaching this tile
                int tcx = col * ts + ts / 2;
                int tcy = row * ts + ts / 2;
                float bestIntensity = 0f;

                // Player light
                int plx = playerWX + ts / 2, ply = playerWY + ts / 2;
                int lightPxP = playerLightRadius * ts;
                {
                    int dx = tcx - plx, dy = tcy - ply;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < lightPxP) {
                        float t = 1f - dist / lightPxP;
                        bestIntensity = Math.max(bestIntensity, t * t);
                    }
                }

                // Torch lights
                for (int i = 0; i < gp.obj.length; i++) {
                    if (gp.obj[i] == null || !gp.obj[i].lightSource) continue;
                    int tRad = gp.obj[i].lightRadius * ts;
                    int twx  = gp.obj[i].worldX + ts / 2;
                    int twy  = gp.obj[i].worldY + ts / 2;
                    int dx = tcx - twx, dy = tcy - twy;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < tRad) {
                        float t = 1f - dist / tRad;
                        bestIntensity = Math.max(bestIntensity, t * t);
                    }
                }

                if (bestIntensity < 0.01f) continue;

                float alpha = bestIntensity * maxDarkness * 0.38f;
                if (alpha > 0.55f) alpha = 0.55f;

                int screenX = col * ts - cameraWX;
                int screenY = row * ts - cameraWY;
                g2.setComposite(cachedAlpha(alpha));
                g2.fillRect(screenX, screenY, ts, ts);
            }
        }
        g2.setComposite(saved);
    }

    // ===================== MAIN DRAW =====================
    public void draw(Graphics2D g2, float currentMaxDarkness) {
        int screenWidth  = gp.screenWidth;
        int screenHeight = gp.screenHeight;

        int playerWorldX  = gp.player.worldX;
        int playerWorldY  = gp.player.worldY;
        int playerScreenX = gp.player.screenX;
        int playerScreenY = gp.player.screenY;

        // Light center = center of the player tile (not the top-left corner)
        int playerLightCX = playerWorldX + gp.tileSize / 2;
        int playerLightCY = playerWorldY + gp.tileSize / 2;
        int lightPxWorld  = playerLightRadius * gp.tileSize;

        // ========= ALWAYS: update tileIsLit (used by gameplay + reflective highlights) =========
        if (gp.tileM.tileIsLit != null) {
            gp.tileM.clearTileLitMap();
            markTilesLit(playerLightCX, playerLightCY, lightPxWorld);
            for (int i = 0; i < gp.obj.length; i++) {
                if (gp.obj[i] != null && gp.obj[i].lightSource && gp.obj[i].lightRadius > 0) {
                    markTilesLit(gp.obj[i].worldX + gp.tileSize / 2,
                                 gp.obj[i].worldY + gp.tileSize / 2,
                                 gp.obj[i].lightRadius * gp.tileSize);
                }
            }
        }

        if (currentMaxDarkness <= 0.001f) return;

        // ========= DOWNSCALE SETUP =========
        int ds          = Math.max(1, lightDownscale);
        int overlayW    = Math.max(1, screenWidth  / ds);
        int overlayH    = Math.max(1, screenHeight / ds);
        float polyScale = 1.0f / ds;

        ensureOverlay(overlayW, overlayH);

        int darkArgb = ((int)(currentMaxDarkness * 255 + 0.5f)) << 24;
        Arrays.fill(darknessPixels, darkArgb);

        Graphics2D og = overlayG2;

        // Overlay-space screen position of player light center
        int playerSXov = (playerScreenX + gp.tileSize / 2) / ds;
        int playerSYov = (playerScreenY + gp.tileSize / 2) / ds;
        int lightPxOv  = lightPxWorld / ds;

        // ========= PLAYER LIGHT =========
        int dxP = playerWorldX - playerLastCacheWorldX;
        int dyP = playerWorldY - playerLastCacheWorldY;
        if (playerShadowPolys == null
                || dxP * dxP + dyP * dyP > PLAYER_SHADOW_DIRTY_PX * PLAYER_SHADOW_DIRTY_PX) {
            reusableOccluders.clear();
            reusableWorldRect.setBounds(
                    playerLightCX - lightPxWorld, playerLightCY - lightPxWorld,
                    lightPxWorld * 2, lightPxWorld * 2);
            // BUG FIX: use lightOccluderRects (AA rects only) instead of all collision bounds.
            // Polygon/ellipse/rotated bounding boxes caused false shadows in open areas.
            gp.cChecker.getLightOccludersInRect(reusableWorldRect, reusableOccluders);
            playerShadowPolys     = computeShadowPolysWorld(
                    playerLightCX, playerLightCY, lightPxWorld, reusableOccluders);
            playerLastCacheWorldX = playerWorldX;
            playerLastCacheWorldY = playerWorldY;
        }

        drawLight(og, playerSXov, playerSYov, lightPxOv, playerShadowPolys, polyScale);

        // ========= TORCH LIGHTS =========
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] == null || !gp.obj[i].lightSource || gp.obj[i].lightRadius <= 0) continue;

            int tRadWorld = gp.obj[i].lightRadius * gp.tileSize;
            int tRadOv    = tRadWorld / ds;
            int tx = (gp.obj[i].worldX - playerWorldX + playerScreenX + gp.tileSize / 2) / ds;
            int ty = (gp.obj[i].worldY - playerWorldY + playerScreenY + gp.tileSize / 2) / ds;

            if (tx + tRadOv < 0 || tx - tRadOv > overlayW
                    || ty + tRadOv < 0 || ty - tRadOv > overlayH) continue;

            if (torchShadowCacheDirty || !torchShadowPolys.containsKey(i)) {
                int twx = gp.obj[i].worldX + gp.tileSize / 2;
                int twy = gp.obj[i].worldY + gp.tileSize / 2;
                reusableOccluders.clear();
                reusableWorldRect.setBounds(twx - tRadWorld, twy - tRadWorld,
                                            tRadWorld * 2, tRadWorld * 2);
                gp.cChecker.getLightOccludersInRect(reusableWorldRect, reusableOccluders);
                torchShadowPolys.put(i, computeShadowPolysWorld(twx, twy, tRadWorld, reusableOccluders));
            }

            drawLight(og, tx, ty, tRadOv, torchShadowPolys.get(i), polyScale);
        }
        torchShadowCacheDirty = false;

        // Blit darkness overlay — scale up to full screen when downscaling is active
        if (ds > 1) {
            g2.drawImage(darknessOverlay, 0, 0, screenWidth, screenHeight, null);
        } else {
            g2.drawImage(darknessOverlay, 0, 0, null);
        }

        // ========= REFLECTIVE TILE HIGHLIGHTS =========
        drawReflectiveHighlights(g2, currentMaxDarkness,
                playerWorldX, playerWorldY, playerScreenX, playerScreenY);

        // ========= COLORED LIGHTS (ambient glow, no shadow casting) =========
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
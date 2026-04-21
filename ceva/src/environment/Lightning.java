package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;

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

    // OPTIMIZATION: Per-radius cached temporary masks (radius<<32 | extend)
    private final HashMap<Long, BufferedImage> lightMaskCache = new HashMap<>();
    private final HashMap<Long, BufferedImage> colorMaskCache = new HashMap<>();

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

        // Prepare some commonly-used coords
        int playerWorldX = gp.player.worldX;
        int playerWorldY = gp.player.worldY;
        int playerScreenX = gp.player.screenX;
        int playerScreenY = gp.player.screenY;
        int playerSX = playerScreenX + gp.tileSize / 2;
        int playerSY = playerScreenY + gp.tileSize / 2;

        // Draw a masked player light (radial gradient minus shadows)
        int lightPx = playerLightRadius * gp.tileSize;
        int maxExtend = Math.max(overlayWidth, overlayHeight);
        int extend = Math.min(maxExtend, Math.max(64, lightPx * 4));
        long pKey = (((long) lightPx) << 32) | (extend & 0xFFFFFFFFL);
        BufferedImage pMask = lightMaskCache.get(pKey);
        if (pMask == null) {
            int size = lightPx * 2 + extend * 2;
            pMask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            lightMaskCache.put(pKey, pMask);
        }
        Graphics2D mg = pMask.createGraphics();
        mg.setComposite(AlphaComposite.Clear);
        mg.fillRect(0, 0, pMask.getWidth(), pMask.getHeight());
        mg.setComposite(AlphaComposite.SrcOver);
        mg.drawImage(getDstOutLight(lightPx), extend, extend, null);

        // Query collision bounds near the light and cast simple polygonal shadows from bounding rects
        java.awt.Rectangle worldRect = new java.awt.Rectangle(playerWorldX - lightPx - extend, playerWorldY - lightPx - extend, (lightPx + extend) * 2, (lightPx + extend) * 2);
        ArrayList<java.awt.Rectangle> occluders = gp.cChecker.getCollisionBoundsInRect(worldRect);
        if (!occluders.isEmpty()) {
            mg.setComposite(AlphaComposite.DstOut);
            mg.setColor(new Color(0, 0, 0, 255));
            double diag = Math.hypot(overlayWidth, overlayHeight) * 1.2;
            double centerX = extend + lightPx;
            double centerY = extend + lightPx;
            int maskX = playerSX - lightPx - extend;
            int maskY = playerSY - lightPx - extend;
            for (java.awt.Rectangle br : occluders) {
                int brScreenX = br.x - playerWorldX + playerScreenX;
                int brScreenY = br.y - playerWorldY + playerScreenY;
                int localX = brScreenX - maskX;
                int localY = brScreenY - maskY;
                if (localX + br.width < 0 || localX > pMask.getWidth() || localY + br.height < 0 || localY > pMask.getHeight()) continue;

                double[][] corners = new double[4][2];
                corners[0][0] = localX; corners[0][1] = localY;
                corners[1][0] = localX + br.width; corners[1][1] = localY;
                corners[2][0] = localX + br.width; corners[2][1] = localY + br.height;
                corners[3][0] = localX; corners[3][1] = localY + br.height;

                // Skip if light center is inside the occluder
                if (centerX >= corners[0][0] && centerX <= corners[1][0] && centerY >= corners[0][1] && centerY <= corners[3][1]) continue;

                double[] ang = new double[4];
                int[] idx = new int[] {0,1,2,3};
                for (int k = 0; k < 4; k++) {
                    double dx = corners[k][0] - centerX;
                    double dy = corners[k][1] - centerY;
                    double a = Math.atan2(dy, dx);
                    if (a < 0) a += Math.PI * 2;
                    ang[k] = a;
                }
                // simple sort of 4 items
                for (int a = 1; a < 4; a++) {
                    int v = idx[a];
                    double av = ang[v];
                    int j = a - 1;
                    while (j >= 0 && ang[idx[j]] > av) { idx[j+1] = idx[j]; j--; }
                    idx[j+1] = v;
                }
                double largestGap = -1; int gapIdx = 0;
                for (int a = 0; a < 4; a++) {
                    int b = (a + 1) % 4;
                    double aAng = ang[idx[a]];
                    double bAng = ang[idx[b]];
                    double gap = bAng - aAng;
                    if (b == 0) gap = (ang[idx[0]] + Math.PI*2) - ang[idx[a]];
                    if (gap < 0) gap += Math.PI*2;
                    if (gap > largestGap) { largestGap = gap; gapIdx = a; }
                }
                int startIdx = (gapIdx + 1) % 4;
                int endIdx = gapIdx;
                int s = idx[startIdx];
                int e = idx[endIdx];
                double sx = corners[s][0], sy = corners[s][1];
                double ex = corners[e][0], ey = corners[e][1];
                double svx = sx - centerX, svy = sy - centerY; double slen = Math.hypot(svx, svy); if (slen < 1e-4) continue;
                double evx = ex - centerX, evy = ey - centerY; double elen = Math.hypot(evx, evy); if (elen < 1e-4) continue;
                double projLen = diag;
                double psx = sx + (svx / slen) * projLen;
                double psy = sy + (svy / slen) * projLen;
                double pex = ex + (evx / elen) * projLen;
                double pey = ey + (evy / elen) * projLen;
                Path2D poly = new Path2D.Double();
                poly.moveTo(sx, sy);
                poly.lineTo(ex, ey);
                poly.lineTo(pex, pey);
                poly.lineTo(psx, psy);
                poly.closePath();
                mg.fill(poly);
            }
        }
        mg.dispose();
        og.drawImage(pMask, playerSX - lightPx - extend, playerSY - lightPx - extend, null);

        // TORCH LIGHTS — masked similar to player light (uses pre-rendered radial + occluders)
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] == null || !gp.obj[i].lightSource || gp.obj[i].lightRadius <= 0) continue;
            int tx = gp.obj[i].worldX - playerWorldX + playerScreenX + gp.tileSize / 2;
            int ty = gp.obj[i].worldY - playerWorldY + playerScreenY + gp.tileSize / 2;
            int tRad = gp.obj[i].lightRadius * gp.tileSize;
            if (tx + tRad < 0 || tx - tRad > screenWidth || ty + tRad < 0 || ty - tRad > screenHeight) continue;

            int tExtend = Math.min(maxExtend, Math.max(64, tRad * 4));
            long tKey = (((long) tRad) << 32) | (tExtend & 0xFFFFFFFFL);
            BufferedImage tMask = lightMaskCache.get(tKey);
            if (tMask == null) {
                int size = tRad * 2 + tExtend * 2;
                tMask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                lightMaskCache.put(tKey, tMask);
            }
            Graphics2D tm = tMask.createGraphics();
            tm.setComposite(AlphaComposite.Clear);
            tm.fillRect(0, 0, tMask.getWidth(), tMask.getHeight());
            tm.setComposite(AlphaComposite.SrcOver);
            tm.drawImage(getDstOutLight(tRad), tExtend, tExtend, null);

            java.awt.Rectangle tworldRect = new java.awt.Rectangle(gp.obj[i].worldX - tRad - tExtend, gp.obj[i].worldY - tRad - tExtend, (tRad + tExtend) * 2, (tRad + tExtend) * 2);
            ArrayList<java.awt.Rectangle> toccluders = gp.cChecker.getCollisionBoundsInRect(tworldRect);
            if (!toccluders.isEmpty()) {
                tm.setComposite(AlphaComposite.DstOut);
                tm.setColor(new Color(0,0,0,255));
                double diagT = Math.hypot(overlayWidth, overlayHeight) * 1.2;
                double centerTX = tExtend + tRad;
                double centerTY = tExtend + tRad;
                int maskTX = tx - tRad - tExtend;
                int maskTY = ty - tRad - tExtend;
                for (java.awt.Rectangle br : toccluders) {
                    int brScreenX = br.x - playerWorldX + playerScreenX;
                    int brScreenY = br.y - playerWorldY + playerScreenY;
                    int localX = brScreenX - maskTX;
                    int localY = brScreenY - maskTY;
                    if (localX + br.width < 0 || localX > tMask.getWidth() || localY + br.height < 0 || localY > tMask.getHeight()) continue;
                    double[][] corners = new double[4][2];
                    corners[0][0] = localX; corners[0][1] = localY;
                    corners[1][0] = localX + br.width; corners[1][1] = localY;
                    corners[2][0] = localX + br.width; corners[2][1] = localY + br.height;
                    corners[3][0] = localX; corners[3][1] = localY + br.height;
                    if (centerTX >= corners[0][0] && centerTX <= corners[1][0] && centerTY >= corners[0][1] && centerTY <= corners[3][1]) continue;
                    double[] ang2 = new double[4]; int[] idx2 = new int[]{0,1,2,3};
                    for (int k = 0; k < 4; k++) { double dx = corners[k][0] - centerTX; double dy = corners[k][1] - centerTY; double a = Math.atan2(dy, dx); if (a < 0) a += Math.PI*2; ang2[k] = a; }
                    for (int a = 1; a < 4; a++) { int v = idx2[a]; double av = ang2[v]; int j = a-1; while (j >= 0 && ang2[idx2[j]] > av) { idx2[j+1] = idx2[j]; j--; } idx2[j+1] = v; }
                    double largestGap2 = -1; int gapIdx2 = 0; for (int a = 0; a < 4; a++) { int b = (a+1)%4; double aAng = ang2[idx2[a]]; double bAng = ang2[idx2[b]]; double gap = bAng - aAng; if (b==0) gap = (ang2[idx2[0]] + Math.PI*2) - ang2[idx2[a]]; if (gap < 0) gap += Math.PI*2; if (gap > largestGap2) { largestGap2 = gap; gapIdx2 = a; } }
                    int startIdx2 = (gapIdx2+1)%4; int endIdx2 = gapIdx2; int s2 = idx2[startIdx2]; int e2 = idx2[endIdx2];
                    double sx2 = corners[s2][0], sy2 = corners[s2][1];
                    double ex2 = corners[e2][0], ey2 = corners[e2][1];
                    double svx = sx2 - centerTX, svy = sy2 - centerTY; double slen2 = Math.hypot(svx, svy); if (slen2 < 1e-4) continue;
                    double evx = ex2 - centerTX, evy = ey2 - centerTY; double elen2 = Math.hypot(evx, evy); if (elen2 < 1e-4) continue;
                    double projLen2 = diagT;
                    double psx2 = sx2 + (svx / slen2) * projLen2;
                    double psy2 = sy2 + (svy / slen2) * projLen2;
                    double pex2 = ex2 + (evx / elen2) * projLen2;
                    double pey2 = ey2 + (evy / elen2) * projLen2;
                    Path2D poly2 = new Path2D.Double(); poly2.moveTo(sx2, sy2); poly2.lineTo(ex2, ey2); poly2.lineTo(pex2, pey2); poly2.lineTo(psx2, psy2); poly2.closePath();
                    tm.fill(poly2);
                }
            }
            tm.dispose();
            og.drawImage(tMask, tx - tRad - tExtend, ty - tRad - tExtend, null);
        }

        // ONE draw call blits the entire darkness layer
        g2.drawImage(darknessOverlay, 0, 0, null);

        // ===================== COLORED LIGHT PASS (occlusion-aware) =====================
        if (lightCount > 0 && currentMaxDarkness > 0.05f) {
            Composite saved = g2.getComposite();
            for (int i = 0; i < lightCount; i++) {
                int worldLX = lightWX[i];
                int worldLY = lightWY[i];
                int lx  = worldLX - playerWorldX + playerScreenX;
                int ly  = worldLY - playerWorldY + playerScreenY;
                int rad = lightRadiusPx[i];
                if (lx + rad < 0 || lx - rad > screenWidth || ly + rad < 0 || ly - rad > screenHeight) continue;

                BufferedImage grad = getGradient(lightColor[i], rad);
                float a = lightIntensity[i] * currentMaxDarkness;
                if (a > 1f) a = 1f;
                if (a < 0.01f) continue;

                // Build (or reuse) a color buffer the size of the light+extend and punch out occluders
                int maxExtendLocal = Math.max(64, rad * 4);
                int cExtend = Math.min(Math.max(overlayWidth, overlayHeight), maxExtendLocal);
                long cKey = (((long) rad) << 32) | (cExtend & 0xFFFFFFFFL);
                BufferedImage cMask = colorMaskCache.get(cKey);
                if (cMask == null) {
                    int size = rad * 2 + cExtend * 2;
                    cMask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    colorMaskCache.put(cKey, cMask);
                }
                Graphics2D cg = cMask.createGraphics();
                cg.setComposite(AlphaComposite.Clear);
                cg.fillRect(0, 0, cMask.getWidth(), cMask.getHeight());
                cg.setComposite(AlphaComposite.SrcOver);
                cg.drawImage(grad, cExtend, cExtend, null);

                java.awt.Rectangle lWorldRect = new java.awt.Rectangle(worldLX - rad - cExtend, worldLY - rad - cExtend, (rad + cExtend) * 2, (rad + cExtend) * 2);
                ArrayList<java.awt.Rectangle> loccluders = gp.cChecker.getCollisionBoundsInRect(lWorldRect);
                if (!loccluders.isEmpty()) {
                    cg.setComposite(AlphaComposite.DstOut);
                    cg.setColor(new Color(0,0,0,255));
                    double diagC = Math.hypot(overlayWidth, overlayHeight) * 1.2;
                    double centerCX = cExtend + rad;
                    double centerCY = cExtend + rad;
                    int maskCX = lx - rad - cExtend;
                    int maskCY = ly - rad - cExtend;
                    for (java.awt.Rectangle br : loccluders) {
                        int brScreenX = br.x - playerWorldX + playerScreenX;
                        int brScreenY = br.y - playerWorldY + playerScreenY;
                        int localX = brScreenX - maskCX;
                        int localY = brScreenY - maskCY;
                        if (localX + br.width < 0 || localX > cMask.getWidth() || localY + br.height < 0 || localY > cMask.getHeight()) continue;
                        double[][] corners = new double[4][2];
                        corners[0][0] = localX; corners[0][1] = localY;
                        corners[1][0] = localX + br.width; corners[1][1] = localY;
                        corners[2][0] = localX + br.width; corners[2][1] = localY + br.height;
                        corners[3][0] = localX; corners[3][1] = localY + br.height;
                        if (centerCX >= corners[0][0] && centerCX <= corners[1][0] && centerCY >= corners[0][1] && centerCY <= corners[3][1]) continue;
                        double[] ang3 = new double[4]; int[] idx3 = new int[]{0,1,2,3};
                        for (int k = 0; k < 4; k++) { double dx = corners[k][0] - centerCX; double dy = corners[k][1] - centerCY; double aa = Math.atan2(dy, dx); if (aa < 0) aa += Math.PI*2; ang3[k] = aa; }
                        for (int a2 = 1; a2 < 4; a2++) { int v = idx3[a2]; double av = ang3[v]; int j = a2-1; while (j >= 0 && ang3[idx3[j]] > av) { idx3[j+1] = idx3[j]; j--; } idx3[j+1] = v; }
                        double largestGap3 = -1; int gapIdx3 = 0; for (int a2 = 0; a2 < 4; a2++) { int b = (a2+1)%4; double aAng = ang3[idx3[a2]]; double bAng = ang3[idx3[b]]; double gap = bAng - aAng; if (b==0) gap = (ang3[idx3[0]] + Math.PI*2) - ang3[idx3[a2]]; if (gap < 0) gap += Math.PI*2; if (gap > largestGap3) { largestGap3 = gap; gapIdx3 = a2; } }
                        int s3 = idx3[(gapIdx3+1)%4]; int e3 = idx3[gapIdx3];
                        double sx3 = corners[s3][0], sy3 = corners[s3][1], ex3 = corners[e3][0], ey3 = corners[e3][1];
                        double svx3 = sx3 - centerCX, svy3 = sy3 - centerCY; double slen3 = Math.hypot(svx3, svy3); if (slen3 < 1e-4) continue;
                        double evx3 = ex3 - centerCX, evy3 = ey3 - centerCY; double elen3 = Math.hypot(evx3, evy3); if (elen3 < 1e-4) continue;
                        double projLen3 = diagC;
                        double psx3 = sx3 + (svx3 / slen3) * projLen3;
                        double psy3 = sy3 + (svy3 / slen3) * projLen3;
                        double pex3 = ex3 + (evx3 / elen3) * projLen3;
                        double pey3 = ey3 + (evy3 / elen3) * projLen3;
                        Path2D poly3 = new Path2D.Double(); poly3.moveTo(sx3, sy3); poly3.lineTo(ex3, ey3); poly3.lineTo(pex3, pey3); poly3.lineTo(psx3, psy3); poly3.closePath();
                        cg.fill(poly3);
                    }
                }
                cg.dispose();
                g2.setComposite(cachedAlpha(a));
                g2.drawImage(cMask, lx - rad - cExtend, ly - rad - cExtend, null);
            }
            g2.setComposite(saved);
        }
    }
}
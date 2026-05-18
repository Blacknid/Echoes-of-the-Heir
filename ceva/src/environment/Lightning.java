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

import main.Config;
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
    private final HashMap<Integer, BufferedImage> squareDstOutLightCache = new HashMap<>();
    private final HashMap<Long,    BufferedImage> gradientCache    = new HashMap<>();
    private final HashMap<Integer, BufferedImage> lightMaskImages  = new HashMap<>();
    private final HashMap<Integer, Graphics2D>    lightMaskG2s     = new HashMap<>();
    // lightMaskPixels removed: mask is now cleared via AlphaComposite.Src (GPU-side, no DataBuffer write)

    // ===================== SHADOW GEOMETRY CACHE =====================
    // Torch polys: computed once per map load (torches never move).
    // Player polys: recomputed every frame (sub-5µs, eliminates threshold-snap saccading).
    // Format: float[8] = {nearS.x, nearS.y, nearE.x, nearE.y, farE.x, farE.y, farS.x, farS.y}
    // Coords are world-space, relative to the light center.
    private final HashMap<Integer, List<float[]>> torchShadowPolys = new HashMap<>();
    private boolean torchShadowCacheDirty = true;

    private List<float[]> playerShadowPolys = null;
    // Player shadow polys are recomputed every frame: the math is <5 µs and avoids the
    // saccadating "snap" that caching at a fixed pixel threshold causes.

    // ===================== REUSABLE OBJECTS (zero per-frame allocation) =====================
    private final Path2D.Double        reusableShadowPath = new Path2D.Double();
    private final ArrayList<Rectangle> reusableOccluders  = new ArrayList<>(128);
    // Pre-allocated pool for buildSolidTileOccluders — avoids per-frame Rectangle allocation.
    private static final int SOLID_POOL_SIZE = 128;
    private final Rectangle[] solidTilePool  = new Rectangle[SOLID_POOL_SIZE];
    { for (int i = 0; i < SOLID_POOL_SIZE; i++) solidTilePool[i] = new Rectangle(); }
    private int solidTilePoolUsed = 0;
    private final Rectangle            reusableWorldRect  = new Rectangle();

    // ===================== TILE-SHADOW OCCLUDER SCRATCH =====================
    private final ArrayList<Rectangle> tileShadowOccluders = new ArrayList<>(32);
    private final Rectangle            reusableLightBounds = new Rectangle();

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
        // Also clear per-radius mask caches so they are rebuilt at the new downscale
        lightMaskImages.clear();
        lightMaskG2s.clear();
        dstOutLightCache.clear();
        squareDstOutLightCache.clear();
        // Force overlay to redraw on the next frame after a map change
        overlayDirty    = true;
        lastDarkArgb    = Integer.MIN_VALUE;
        lastPlayerSXov  = Integer.MIN_VALUE;
        lastPlayerSYov  = Integer.MIN_VALUE;
    }

    // ===================== PRIVATE HELPERS =====================

    private boolean ensureOverlay(int w, int h) {
        if (darknessOverlay != null && overlayWidth == w && overlayHeight == h) return false;
        if (overlayG2 != null) { overlayG2.dispose(); overlayG2 = null; }
        darknessOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        darknessPixels  = ((DataBufferInt) darknessOverlay.getRaster().getDataBuffer()).getData();
        overlayWidth    = w;
        overlayHeight   = h;
        overlayG2       = darknessOverlay.createGraphics();
        overlayDirty    = true;
        return true;
    }

    private BufferedImage ensureLightMaskImage(int rad) {
        BufferedImage img = lightMaskImages.get(rad);
        if (img != null) return img;
        img = new BufferedImage(rad * 2, rad * 2, BufferedImage.TYPE_INT_ARGB);
        lightMaskImages.put(rad, img);
        Graphics2D mg = img.createGraphics();
        lightMaskG2s.put(rad, mg);
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

    /**
     * Pre-rendered white square gradient (Chebyshev distance: center opaque → edge transparent).
     * Used by LOW graphics mode for square-shaped lights.
     */
    private BufferedImage getDstOutLightSquare(int radiusPx) {
        BufferedImage img = squareDstOutLightCache.get(radiusPx);
        if (img != null) return img;
        int size = radiusPx * 2;
        img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float invRad = 1f / radiusPx;
        for (int y = 0; y < size; y++) {
            float dy = Math.abs(y - radiusPx + 0.5f) * invRad;
            int rowOff = y * size;
            for (int x = 0; x < size; x++) {
                float dx = Math.abs(x - radiusPx + 0.5f) * invRad;
                float dist = Math.max(dx, dy);
                int alpha;
                if (dist <= 0.30f)      alpha = 255;
                else if (dist <= 0.65f) alpha = 230 - (int)((dist - 0.30f) * (120f / 0.35f));
                else if (dist < 1.0f)   alpha = 110 - (int)((dist - 0.65f) * (110f / 0.35f));
                else                     alpha = 0;
                px[rowOff + x] = (alpha << 24) | 0x00FFFFFF;
            }
        }
        squareDstOutLightCache.put(radiusPx, img);
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

        // Src composite replaces every pixel: clears transparent areas AND draws gradient in one pass.
        // Keeps the mask image GPU-resident (no DataBuffer write = no CPU↔GPU texture re-upload).
        mg.setComposite(AlphaComposite.Src);
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
     * LOW graphics mode: BFS flood-fill propagation on the tile grid.
     * Light starts at the origin tile and expands to 4-neighbors. Each step is weaker
     * than the previous (linear falloff over `radiusTiles` steps). When the BFS reaches
     * a solid (collision) tile, that tile is lit with its remaining intensity but
     * light does NOT propagate through it.
     */
    private void markTilesLitLowBFS(int lightWX, int lightWY, int radiusPx) {
        boolean[][] litMap = gp.tileM.tileIsLit;
        float[][]   lightLevel = gp.tileM.tileLightLevel;
        if (litMap == null) return;

        int ts      = gp.tileSize;
        int maxCol  = litMap.length;
        int maxRow  = maxCol > 0 ? litMap[0].length : 0;

        int originCol = lightWX / ts;
        int originRow = lightWY / ts;
        if (originCol < 0 || originCol >= maxCol || originRow < 0 || originRow >= maxRow) return;

        int radiusTiles = Math.max(1, radiusPx / ts);

        int minCol = Math.max(0, originCol - radiusTiles);
        int maxColI = Math.min(maxCol - 1, originCol + radiusTiles);
        int minRow = Math.max(0, originRow - radiusTiles);
        int maxRowI = Math.min(maxRow - 1, originRow + radiusTiles);

        // Pre-filter occluders to bounding box
        tileShadowOccluders.clear();
        reusableLightBounds.setBounds(minCol * ts, minRow * ts,
                                      (maxColI - minCol + 1) * ts,
                                      (maxRowI - minRow + 1) * ts);
        ArrayList<Rectangle> allOcc = gp.tileM.lightOccluderRects;
        for (int i = 0, n = allOcc.size(); i < n; i++) {
            if (allOcc.get(i).intersects(reusableLightBounds))
                tileShadowOccluders.add(allOcc.get(i));
        }

        int bw = maxColI - minCol + 1;
        int bh = maxRowI - minRow + 1;
        int cells = bw * bh;
        if (bfsVisited == null || bfsVisited.length < cells) bfsVisited = new boolean[cells];
        Arrays.fill(bfsVisited, 0, cells, false);

        int cap = cells;
        if (bfsQCol == null || bfsQCol.length < cap) {
            bfsQCol   = new int[cap];
            bfsQRow   = new int[cap];
            bfsQDepth = new int[cap];
        }

        int head = 0, tail = 0;
        bfsQCol[tail] = originCol;
        bfsQRow[tail] = originRow;
        bfsQDepth[tail] = 0;
        tail++;
        bfsVisited[(originRow - minRow) * bw + (originCol - minCol)] = true;

        float invRad = 1f / radiusTiles;

        while (head < tail) {
            int col = bfsQCol[head];
            int row = bfsQRow[head];
            int depth = bfsQDepth[head];
            head++;

            litMap[col][row] = true;
            float intensity = 1f - depth * invRad;
            if (intensity < 0f) intensity = 0f;
            if (lightLevel != null && intensity > lightLevel[col][row])
                lightLevel[col][row] = intensity;

            // Solid tile = terminal (lit, but no further propagation)
            if (isTileSolid(col, row, ts, tileShadowOccluders)) continue;
            if (depth >= radiusTiles) continue;

            // 4-way neighbors
            for (int k = 0; k < 4; k++) {
                int nc = col + BFS_DCOL[k];
                int nr = row + BFS_DROW[k];
                if (nc < minCol || nc > maxColI || nr < minRow || nr > maxRowI) continue;
                int vIdx = (nr - minRow) * bw + (nc - minCol);
                if (bfsVisited[vIdx]) continue;
                bfsVisited[vIdx] = true;
                bfsQCol[tail]   = nc;
                bfsQRow[tail]   = nr;
                bfsQDepth[tail] = depth + 1;
                tail++;
            }
        }
    }

    private static final int[] BFS_DCOL = { 1, -1, 0, 0 };
    private static final int[] BFS_DROW = { 0, 0, 1, -1 };
    private boolean[] bfsVisited;
    private int[]     bfsQCol;
    private int[]     bfsQRow;
    private int[]     bfsQDepth;

    /** Does the tile at (col,row) intersect any occluder rectangle? Uses the tileSolid grid when available. */
    private boolean isTileSolid(int col, int row, int ts, ArrayList<Rectangle> occ) {
        boolean[][] solid = gp.tileM.tileSolid;
        if (solid != null) {
            return col >= 0 && col < solid.length
                && row >= 0 && row < solid[col].length
                && solid[col][row];
        }
        int tx = col * ts, ty = row * ts;
        for (int i = 0, n = occ.size(); i < n; i++) {
            Rectangle r = occ.get(i);
            if (tx + ts > r.x && tx < r.x + r.width
             && ty + ts > r.y && ty < r.y + r.height) return true;
        }
        return false;
    }

    /**
     * Fill `out` with one tile-sized Rectangle per solid tile that lies within lightBounds.
     * Uses the rasterised tileSolid grid when available; falls back to lightOccluderRects.
     * Called during HIGH-mode shadow polygon computation (cached, infrequent).
     */
    private void buildSolidTileOccluders(Rectangle lightBounds, ArrayList<Rectangle> out) {
        solidTilePoolUsed = 0;
        boolean[][] solid = gp.tileM.tileSolid;
        if (solid == null) {
            ArrayList<Rectangle> fallback = gp.tileM.lightOccluderRects;
            for (int i = 0, n = fallback.size(); i < n; i++)
                if (fallback.get(i).intersects(lightBounds)) out.add(fallback.get(i));
            return;
        }
        int ts     = gp.tileSize;
        int maxCol = solid.length;
        int maxRow = maxCol > 0 ? solid[0].length : 0;
        int minCol = Math.max(0, lightBounds.x / ts);
        int maxColI = Math.min(maxCol - 1, (lightBounds.x + lightBounds.width)  / ts);
        int minRow = Math.max(0, lightBounds.y / ts);
        int maxRowI = Math.min(maxRow - 1, (lightBounds.y + lightBounds.height) / ts);
        for (int col = minCol; col <= maxColI; col++) {
            for (int row = minRow; row <= maxRowI; row++) {
                if (solid[col][row] && solidTilePoolUsed < SOLID_POOL_SIZE) {
                    Rectangle r = solidTilePool[solidTilePoolUsed++];
                    r.setBounds(col * ts, row * ts, ts, ts);
                    out.add(r);
                }
            }
        }
    }

    /**
     * Bresenham tile walk from the light origin tile to the target tile.
     * Returns true if any INTERMEDIATE tile (not the target) is solid.
     * Uses tileSolid grid directly — no per-frame allocation.
     */
    private boolean isRayBlockedByGrid(int ox, int oy, int tx, int ty) {
        boolean[][] solid = gp.tileM.tileSolid;
        if (solid == null) return false;
        int ts   = gp.tileSize;
        int c0   = ox / ts, r0 = oy / ts;
        int c1   = tx / ts, r1 = ty / ts;
        int dc   = Math.abs(c1 - c0), dr = Math.abs(r1 - r0);
        int sc   = c0 < c1 ? 1 : -1,  sr = r0 < r1 ? 1 : -1;
        int err  = dc - dr;
        int c = c0, r = r0;
        while (c != c1 || r != r1) {
            int e2 = err * 2;
            if (e2 > -dr) { err -= dr; c += sc; }
            if (e2 <  dc) { err += dc; r += sr; }
            if (c == c1 && r == r1) break;
            if (c < 0 || c >= solid.length || r < 0 || r >= solid[c].length) break;
            if (solid[c][r]) return true;
        }
        return false;
    }

    /**
     * Mark tile positions within a light radius as lit in tileM.tileIsLit.
     * When useTileShadows is true, rays are cast from the light center to each tile center;
     * tiles occluded by collision rectangles are skipped (shadow).
     * For LOW mode (isLow=true), also writes per-tile intensity into tileM.tileLightLevel
     * with a smooth quadratic falloff using Chebyshev (square) distance.
     */
    private void markTilesLit(int lightWX, int lightWY, int radiusPx,
                               boolean useTileShadows, boolean isLow) {
        boolean[][] litMap = gp.tileM.tileIsLit;
        if (litMap == null) return;

        int ts      = gp.tileSize;
        int maxCol  = litMap.length;
        int maxRow  = maxCol > 0 ? litMap[0].length : 0;
        int minCol  = Math.max(0, (lightWX - radiusPx) / ts);
        int maxColI = Math.min(maxCol - 1, (lightWX + radiusPx) / ts);
        int minRow  = Math.max(0, (lightWY - radiusPx) / ts);
        int maxRowI = Math.min(maxRow - 1, (lightWY + radiusPx) / ts);

        // Pre-filter occluders into the light's bounding box (avoids testing far-away rects)
        boolean useTileGrid = (useTileShadows && gp.tileM.tileSolid != null);
        if (useTileShadows && !useTileGrid) {
            tileShadowOccluders.clear();
            reusableLightBounds.setBounds(lightWX - radiusPx, lightWY - radiusPx,
                                          radiusPx * 2, radiusPx * 2);
            ArrayList<Rectangle> allOcc = gp.tileM.lightOccluderRects;
            for (int i = 0, n = allOcc.size(); i < n; i++) {
                if (allOcc.get(i).intersects(reusableLightBounds))
                    tileShadowOccluders.add(allOcc.get(i));
            }
        }
        ArrayList<Rectangle> occ = null;
        if (useTileShadows && !tileShadowOccluders.isEmpty()) occ = tileShadowOccluders;

        float[][] lightLevel = gp.tileM.tileLightLevel;
        int   r2     = radiusPx * radiusPx;
        float invRad = 1f / radiusPx;

        for (int col = minCol; col <= maxColI; col++) {
            for (int row = minRow; row <= maxRowI; row++) {
                int tcx = col * ts + ts / 2;
                int tcy = row * ts + ts / 2;
                int dx  = tcx - lightWX, dy = tcy - lightWY;

                // Distance gate
                if (isLow) {
                    if (Math.abs(dx) > radiusPx || Math.abs(dy) > radiusPx) continue;
                } else {
                    if (dx * dx + dy * dy > r2) continue;
                }

                // Shadow gate: ray from light center → tile center blocked?
                if (useTileGrid && isRayBlockedByGrid(lightWX, lightWY, tcx, tcy)) continue;
                if (occ != null && isRayBlocked(lightWX, lightWY, tcx, tcy, occ)) continue;

                litMap[col][row] = true;

                // LOW mode: write per-tile intensity (max of all contributing lights)
                if (isLow && lightLevel != null) {
                    float dist = Math.max(Math.abs(dx), Math.abs(dy)) * invRad; // Chebyshev
                    float intensity = Math.max(0f, 1f - dist);
                    intensity *= intensity; // quadratic falloff
                    if (intensity > lightLevel[col][row])
                        lightLevel[col][row] = intensity;
                }
            }
        }
    }

    // ===================== RAY-AABB SHADOW TEST =====================

    /**
     * Returns true if the segment from (ox,oy) → (tx,ty) is blocked by any occluder.
     * Skips occluders that contain the light origin (prevents self-shadowing).
     */
    private boolean isRayBlocked(int ox, int oy, int tx, int ty,
                                  ArrayList<Rectangle> occluders) {
        float dx = tx - ox, dy = ty - oy;
        for (int i = 0, n = occluders.size(); i < n; i++) {
            Rectangle r = occluders.get(i);
            // Skip if light origin is inside this occluder
            if (ox >= r.x && ox <= r.x + r.width && oy >= r.y && oy <= r.y + r.height) continue;
            if (segmentIntersectsAABB(ox, oy, dx, dy, r.x, r.y, r.x + r.width, r.y + r.height))
                return true;
        }
        return false;
    }

    /**
     * Slab method: does the segment (ox,oy)+(dx,dy)*t, t∈[0,1] intersect the AABB [minX,minY,maxX,maxY]?
     */
    private static boolean segmentIntersectsAABB(float ox, float oy, float dx, float dy,
                                                  int minX, int minY, int maxX, int maxY) {
        float tmin = 0f, tmax = 1f;
        if (dx != 0f) {
            float inv = 1f / dx;
            float t1 = (minX - ox) * inv;
            float t2 = (maxX - ox) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return false;
        } else {
            if (ox < minX || ox > maxX) return false;
        }
        if (dy != 0f) {
            float inv = 1f / dy;
            float t1 = (minY - oy) * inv;
            float t2 = (maxY - oy) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return false;
        } else {
            if (oy < minY || oy > maxY) return false;
        }
        return true;
    }

    // ===================== LOW-MODE TILE OVERLAY =====================

    /**
     * Build the darkness overlay directly from tileLightLevel.
     * Each tile-sized block is filled with a single darkness value = maxDark × (1 − lightLevel).
     * Called only in LOW graphics mode (replaces the radial-gradient drawLight path).
     */
    private void renderLowModeOverlay(float currentMaxDarkness,
                                       int playerWorldX, int playerWorldY,
                                       int playerScreenX, int playerScreenY,
                                       int ds) {
        float[][] lightLevel = gp.tileM.tileLightLevel;
        if (lightLevel == null) return;

        int ts   = gp.tileSize;
        int tsOv = Math.max(1, ts / ds);

        int cameraWX = playerWorldX - playerScreenX;
        int cameraWY = playerWorldY - playerScreenY;

        int startCol = Math.max(0, cameraWX / ts);
        int endCol   = Math.min(lightLevel.length - 1, (cameraWX + gp.screenWidth) / ts);
        int maxRow   = lightLevel.length > 0 ? lightLevel[0].length : 0;
        int startRow = Math.max(0, cameraWY / ts);
        int endRow   = Math.min(maxRow - 1, (cameraWY + gp.screenHeight) / ts);

        int maxDark = (int)(currentMaxDarkness * 255 + 0.5f);

        for (int col = startCol; col <= endCol; col++) {
            for (int row = startRow; row <= endRow; row++) {
                float level = lightLevel[col][row];
                if (level <= 0f) continue; // stays at full darkness (already filled)

                int tileAlpha = (int)((1f - level) * maxDark + 0.5f);
                if (tileAlpha < 0) tileAlpha = 0;
                if (tileAlpha >= maxDark) continue; // no visible change
                int argb = tileAlpha << 24;

                int sx = (col * ts - cameraWX) / ds;
                int sy = (row * ts - cameraWY) / ds;

                int x0 = Math.max(0, sx);
                int x1 = Math.min(overlayWidth,  sx + tsOv);
                int y0 = Math.max(0, sy);
                int y1 = Math.min(overlayHeight, sy + tsOv);

                for (int py = y0; py < y1; py++) {
                    int off = py * overlayWidth;
                    for (int px = x0; px < x1; px++) {
                        darknessPixels[off + px] = argb;
                    }
                }
            }
        }
    }

    /**
     * After the darkness overlay has been blitted, draw a warm highlight over every reflective
     * tile that is currently within a light's radius and on screen. Iterates the precomputed
     * tileM.reflectiveTilePositions list (built once at map load) instead of the visible viewport.
     */
    private void drawReflectiveHighlights(Graphics2D g2, float maxDarkness,
                                          int playerWX, int playerWY,
                                          int playerSX, int playerSY) {
        if (maxDarkness < 0.04f) return;
        int[]       refl   = gp.tileM.reflectiveTilePositions;
        boolean[][] litMap = gp.tileM.tileIsLit;
        if (refl == null || refl.length == 0 || litMap == null) return;

        int ts        = gp.tileSize;
        int cameraWX  = playerWX - playerSX;
        int cameraWY  = playerWY - playerSY;
        int screenW   = gp.screenWidth;
        int screenH   = gp.screenHeight;

        // Player light center + squared radius (computed once)
        int   plx        = playerWX + ts / 2;
        int   ply        = playerWY + ts / 2;
        long  playerR2   = (long) playerLightRadius * ts * playerLightRadius * ts;
        float invPlayerR = 1f / (playerLightRadius * ts);

        // Snapshot torch lights into local arrays once (avoids array bounds + null checks per tile)
        int torchCount = 0;
        int[]   tcx = reusableTorchCX;
        int[]   tcy = reusableTorchCY;
        long[]  tr2 = reusableTorchR2;
        float[] trI = reusableTorchInvR;
        for (int i = 0; i < gp.obj.length && torchCount < tcx.length; i++) {
            entity.Entity o = gp.obj[i];
            if (o == null || !o.lightSource || o.lightRadius <= 0) continue;
            int rPx = o.lightRadius * ts;
            tcx[torchCount] = o.worldX + ts / 2;
            tcy[torchCount] = o.worldY + ts / 2;
            tr2[torchCount] = (long) rPx * rPx;
            trI[torchCount] = 1f / rPx;
            torchCount++;
        }

        Composite saved = g2.getComposite();
        g2.setColor(REFLECT_GLOW);

        for (int p = 0; p < refl.length; p += 2) {
            int col = refl[p];
            int row = refl[p + 1];

            // Lit-state gate (cheap)
            if (col >= litMap.length || row >= litMap[col].length || !litMap[col][row]) continue;

            // Viewport cull
            int screenX = col * ts - cameraWX;
            int screenY = row * ts - cameraWY;
            if (screenX + ts < 0 || screenX > screenW || screenY + ts < 0 || screenY > screenH) continue;

            int wx = col * ts + ts / 2;
            int wy = row * ts + ts / 2;

            // Best intensity from any light touching this tile (no sqrt)
            float bestIntensity = 0f;
            long dx = wx - plx, dy = wy - ply;
            long d2 = dx * dx + dy * dy;
            if (d2 < playerR2) {
                float t = 1f - (float) Math.sqrt(d2) * invPlayerR;
                bestIntensity = t * t;
            }
            for (int i = 0; i < torchCount; i++) {
                long tdx = wx - tcx[i], tdy = wy - tcy[i];
                long td2 = tdx * tdx + tdy * tdy;
                if (td2 < tr2[i]) {
                    float t = 1f - (float) Math.sqrt(td2) * trI[i];
                    float intensity = t * t;
                    if (intensity > bestIntensity) bestIntensity = intensity;
                }
            }

            if (bestIntensity < 0.01f) continue;

            float alpha = bestIntensity * maxDarkness * 0.38f;
            if (alpha > 0.55f) alpha = 0.55f;
            g2.setComposite(cachedAlpha(alpha));
            g2.fillRect(screenX, screenY, ts, ts);
        }
        g2.setComposite(saved);
    }

    // Reusable scratch arrays for the torch snapshot in drawReflectiveHighlights.
    // Sized to MAX_LIGHTS — enough headroom for all dynamic torches on any map.
    private final int[]   reusableTorchCX  = new int[MAX_LIGHTS];
    private final int[]   reusableTorchCY  = new int[MAX_LIGHTS];
    private final long[]  reusableTorchR2  = new long[MAX_LIGHTS];
    private final float[] reusableTorchInvR = new float[MAX_LIGHTS];

    // OPTIMIZATION: Overlay dirty tracking — skip Arrays.fill + light redraw when nothing changed.
    // Saves the most expensive work on static scenes (e.g. Shattered Lake standing still).
    private int   lastDarkArgb      = Integer.MIN_VALUE;
    private int   lastPlayerSXov    = Integer.MIN_VALUE;
    private int   lastPlayerSYov    = Integer.MIN_VALUE;
    private int   lastLightDs       = -1;
    private boolean overlayDirty    = true;

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

        boolean isLow       = (gp.config.graphicsQuality == Config.GRAPHICS_LOW);
        boolean useTileShadows = (gp.config.graphicsQuality != Config.GRAPHICS_MEDIUM);

        // ========= ALWAYS: update tileIsLit (+ tileLightLevel for LOW) =========
        if (gp.tileM.tileIsLit != null) {
            gp.tileM.clearTileLitMap();
            if (isLow) {
                markTilesLitLowBFS(playerLightCX, playerLightCY, lightPxWorld);
                for (int i = 0; i < gp.obj.length; i++) {
                    if (gp.obj[i] != null && gp.obj[i].lightSource && gp.obj[i].lightRadius > 0) {
                        markTilesLitLowBFS(gp.obj[i].worldX + gp.tileSize / 2,
                                           gp.obj[i].worldY + gp.tileSize / 2,
                                           gp.obj[i].lightRadius * gp.tileSize);
                    }
                }
            } else {
                markTilesLit(playerLightCX, playerLightCY, lightPxWorld, useTileShadows, false);
                for (int i = 0; i < gp.obj.length; i++) {
                    if (gp.obj[i] != null && gp.obj[i].lightSource && gp.obj[i].lightRadius > 0) {
                        markTilesLit(gp.obj[i].worldX + gp.tileSize / 2,
                                     gp.obj[i].worldY + gp.tileSize / 2,
                                     gp.obj[i].lightRadius * gp.tileSize,
                                     useTileShadows, false);
                    }
                }
            }
        }

        if (currentMaxDarkness <= 0.001f) return;

        // ========= DOWNSCALE SETUP =========
        int ds          = Math.max(1, lightDownscale);
        int overlayW    = Math.max(1, screenWidth  / ds);
        int overlayH    = Math.max(1, screenHeight / ds);
        float polyScale = 1.0f / ds;

        boolean overlayResized = ensureOverlay(overlayW, overlayH);

        int darkArgb = ((int)(currentMaxDarkness * 255 + 0.5f)) << 24;

        int playerSXov = (playerScreenX + gp.tileSize / 2) / ds;
        int playerSYov = (playerScreenY + gp.tileSize / 2) / ds;

        // Invalidate overlay when darkness level, player position, downscale, or overlay size changed
        if (overlayResized || darkArgb != lastDarkArgb
                || playerSXov != lastPlayerSXov || playerSYov != lastPlayerSYov
                || ds != lastLightDs || torchShadowCacheDirty) {
            overlayDirty = true;
        }

        if (overlayDirty) {
            lastDarkArgb   = darkArgb;
            lastPlayerSXov = playerSXov;
            lastPlayerSYov = playerSYov;
            lastLightDs    = ds;
            overlayDirty   = false;

            Arrays.fill(darknessPixels, darkArgb);

            if (isLow) {
                // ========= LOW: tile-based overlay from tileLightLevel (shadows via raycasting) =========
                renderLowModeOverlay(currentMaxDarkness, playerWorldX, playerWorldY,
                                     playerScreenX, playerScreenY, ds);
            } else {
                // ========= MEDIUM / HIGH: radial-gradient overlay =========
                Graphics2D og = overlayG2;

                int lightPxOv  = lightPxWorld / ds;

                // Polygon shadows only for HIGH (tiles can be half-lit at shadow edges)
                boolean usePolyShadows = (gp.config.graphicsQuality == Config.GRAPHICS_HIGH);

                // ========= PLAYER LIGHT =========
                if (usePolyShadows) {
                    reusableOccluders.clear();
                    reusableWorldRect.setBounds(
                            playerLightCX - lightPxWorld, playerLightCY - lightPxWorld,
                            lightPxWorld * 2, lightPxWorld * 2);
                    buildSolidTileOccluders(reusableWorldRect, reusableOccluders);
                    playerShadowPolys = computeShadowPolysWorld(
                            playerLightCX, playerLightCY, lightPxWorld, reusableOccluders);
                }

                drawLight(og, playerSXov, playerSYov, lightPxOv,
                          usePolyShadows ? playerShadowPolys : null, polyScale);

                // ========= TORCH LIGHTS =========
                for (int i = 0; i < gp.obj.length; i++) {
                    if (gp.obj[i] == null || !gp.obj[i].lightSource || gp.obj[i].lightRadius <= 0) continue;

                    int tRadWorld = gp.obj[i].lightRadius * gp.tileSize;
                    int tRadOv    = tRadWorld / ds;
                    int tx = (gp.obj[i].worldX - playerWorldX + playerScreenX + gp.tileSize / 2) / ds;
                    int ty = (gp.obj[i].worldY - playerWorldY + playerScreenY + gp.tileSize / 2) / ds;

                    if (tx + tRadOv < 0 || tx - tRadOv > overlayW
                            || ty + tRadOv < 0 || ty - tRadOv > overlayH) continue;

                    if (usePolyShadows) {
                        if (torchShadowCacheDirty || !torchShadowPolys.containsKey(i)) {
                            int twx = gp.obj[i].worldX + gp.tileSize / 2;
                            int twy = gp.obj[i].worldY + gp.tileSize / 2;
                            reusableOccluders.clear();
                            reusableWorldRect.setBounds(twx - tRadWorld, twy - tRadWorld,
                                                        tRadWorld * 2, tRadWorld * 2);
                            buildSolidTileOccluders(reusableWorldRect, reusableOccluders);
                            torchShadowPolys.put(i, computeShadowPolysWorld(twx, twy, tRadWorld, reusableOccluders));
                        }
                    }

                    drawLight(og, tx, ty, tRadOv,
                              usePolyShadows ? torchShadowPolys.get(i) : null, polyScale);
                }
                if (usePolyShadows) torchShadowCacheDirty = false;
            }
        }

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
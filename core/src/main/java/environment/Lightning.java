package environment;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.RadialGradient;
import gfx.Sprite;
import gfx.geom.Rect;
import java.util.ArrayList;
import java.util.HashMap;

import main.Config;
import main.GamePanel;

/**
 * 2D darkness / lighting compositor — GPU-native (libGDX / gfx facade) reimplementation.
 *
 * <h2>Original (java.awt) technique</h2>
 * The Graphics2D version built a downscaled transparent {@code BufferedImage} overlay, filled it
 * with a darkness color, then punched soft holes per light with {@code AlphaComposite.DstOut} using
 * cached radial "dst-out" images (optionally subtracting shadow polygons), and finally blitted the
 * overlay scaled up to the screen, plus additive colored glows.
 *
 * <h2>GPU-native technique used here</h2>
 * The per-pixel {@code BufferedImage}/{@code DataBufferInt} overlay is gone. We render directly to
 * the screen framebuffer:
 * <ol>
 *   <li>One full-screen darkness {@code fillRect} of the night color at the computed darkness alpha.</li>
 *   <li>For each light, a single baked unit "falloff" texture (white center → transparent edge,
 *       {@link GdxRenderer#bakeRadialGradient}) is drawn <b>additively</b>
 *       ({@link GdxRenderer#BLEND_ADDITIVE}) and tinted with the light color so it brightens the
 *       darkened scene back where the light reaches — the close-enough equivalent of punching a
 *       soft hole in the darkness. One unit texture is scaled per light radius; no per-radius bake.</li>
 *   <li>Colored ambient glows are baked per (color,radius) with {@link GdxRenderer#bakeRadialGradient}
 *       and drawn additively too.</li>
 * </ol>
 * The {@link GdxRenderer#BLEND_DSTOUT} mode was also added to the facade for a future offscreen
 * (FrameBuffer) overlay that could do true dst-out hole punching; without an offscreen render target
 * exposed by the facade, additive re-lighting on the default framebuffer is the chosen approximation.
 *
 * <p>All the tile-grid lighting computation ({@code markTilesLit*}, BFS, ray/AABB shadow tests) is
 * preserved unchanged: other systems read {@code tileM.tileIsLit} / {@code tileM.tileLightLevel}.
 *
 * <p>TODO(gfx-stage5): shadow occlusion (the old Path2D + DstOut polygon subtraction in the screen
 * overlay) is omitted — radial light areas no longer cast hard polygon shadows on screen. The
 * tile-grid shadow tests still gate {@code tileIsLit}. Lights without on-screen polygon shadows is
 * the accepted "close enough" behavior for this stage.
 */
public class Lightning {

    GamePanel gp;
    public int dayState;

    /**
     * Render quality / performance control (kept for API compatibility). With the GPU path the
     * lights are drawn directly to the screen, so downscale no longer allocates an overlay buffer;
     * it is retained because EnvironmentManager adjusts it adaptively.
     *   1 = full resolution, 2 = default, 4 = low-end hardware.
     */
    public int lightDownscale = 2;

    /** Kept for API compatibility with callers that may set the downscale at runtime. */
    public void setLightDownscale(int ds) { lightDownscale = Math.max(1, ds); }

    public int playerLightRadius = 2;

    private static final int MAX_LIGHTS = 20;
    public int[]   lightWX        = new int[MAX_LIGHTS];
    public int[]   lightWY        = new int[MAX_LIGHTS];
    public int[]   lightRadiusPx  = new int[MAX_LIGHTS];
    public Color[] lightColor     = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // Night color the darkness is tinted with (was a black ARGB fill in the overlay).
    // Night ambient: a deep moonlit indigo-teal — cool and atmospheric, but with enough blue life that
    // shadowed areas read as "moonlit night" rather than a dead flat black/purple. The warm light pools
    // pop against this cool base (classic warm-key / cool-fill contrast that makes lighting feel alive).
    // Per-map override: set via the Tiled 'nightColor' property (see MapObjectLoader), reset here so
    // maps without the property keep the default.
    public static final Color DEFAULT_NIGHT_COLOR = new Color(12, 16, 32);
    private Color nightColor = DEFAULT_NIGHT_COLOR;

    public void setNightColor(Color color) { nightColor = color != null ? color : DEFAULT_NIGHT_COLOR; }
    public void resetNightColor() { nightColor = DEFAULT_NIGHT_COLOR; }

    // Baked unit "falloff" texture: white center → transparent edge. Scaled per light when drawn.
    private static final int FALLOFF_TEX_SIZE = 256;
    private Sprite falloffTexture;

    // Colored ambient glow textures, baked per (color,radius) like the old getGradient cache.
    private final HashMap<Long, Sprite> gradientCache = new HashMap<>();

    // TODO(gfx-stage5): on-screen polygon shadow occlusion is omitted (see class doc), so the old
    // per-light shadow-poly caches were removed. The tile-grid shadow tests below still gate
    // tileIsLit. torchShadowCacheDirty is kept only so clearShadowCaches() stays a no-cost flag.
    private boolean torchShadowCacheDirty = true;

    private final ArrayList<Rect> tileShadowOccluders = new ArrayList<>(32);
    private final Rect            reusableLightBounds = new Rect();

    // Warm highlight color for reflective tiles (water, crystals, polished stone, …)
    private static final Color REFLECT_GLOW = new Color(255, 245, 200);

    // ===================== BORDER VIGNETTE (night-scaled) =====================
    // A screen-edge darkening that grows with the current darkness level, so the far corners/edges of
    // the screen read as blacker margins at night instead of the flat uniform darkness mask — makes
    // night feel like the light is a pool in a bigger dark world, not a dark filter over the whole
    // screen. Baked once per screen size (like MapShaderManager's vignette), tinted with nightColor,
    // and its alpha is scaled by currentMaxDarkness each frame so it fades out completely by day.
    private Sprite borderVignette;
    private int borderVignetteW = -1, borderVignetteH = -1;
    private Color borderVignetteNightColor;

    private Sprite getBorderVignette(int screenW, int screenH) {
        if (borderVignette != null && borderVignetteW == screenW && borderVignetteH == screenH
                && borderVignetteNightColor == nightColor) return borderVignette;
        float cx = screenW / 2f, cy = screenH / 2f;
        float radius = (float) Math.sqrt(cx * cx + cy * cy);
        Color edge = new Color(
                Math.max(0, nightColor.getRed()   / 3),
                Math.max(0, nightColor.getGreen() / 3),
                Math.max(0, nightColor.getBlue()  / 3));
        RadialGradient grad = new RadialGradient(
                cx, cy, radius,
                new float[]{ 0.45f, 0.75f, 1.0f },
                new Color[]{
                    new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 0),
                    new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 140),
                    new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 235)
                });
        borderVignette = GdxRenderer.bakeRadialGradient(grad, screenW, screenH);
        borderVignetteW = screenW; borderVignetteH = screenH; borderVignetteNightColor = nightColor;
        return borderVignette;
    }

    /** Draw the night-scaled border vignette. Alpha ramps in with darkness so it's invisible by day. */
    private void drawBorderVignette(GdxRenderer g2, float currentMaxDarkness, int screenWidth, int screenHeight) {
        if (currentMaxDarkness < 0.03f) return;
        Sprite v = getBorderVignette(screenWidth, screenHeight);
        g2.setBlendMode(GdxRenderer.BLEND_NORMAL);
        g2.setAlpha(Math.min(1f, currentMaxDarkness));
        g2.drawImage(v, 0, 0, screenWidth, screenHeight);
        g2.setAlpha(1f);
    }

    public Lightning(GamePanel gp2) { this.gp = gp2; }

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
     * Kept for API compatibility; with on-screen polygon shadows omitted there are no per-light
     * shadow caches to clear, but the tile-grid shadow tests pick up the new geometry automatically.
     */
    public void clearShadowCaches() {
        torchShadowCacheDirty = true;
    }

    // ===================== PRIVATE HELPERS =====================

    /**
     * Lazily bake the unit radial falloff texture (white opaque center → transparent edge),
     * the GPU-native replacement for the cached "dst-out light" BufferedImages. One texture is
     * baked at unit size and scaled per light when drawn.
     */
    private Sprite getFalloffTexture() {
        if (falloffTexture != null) return falloffTexture;
        int s = FALLOFF_TEX_SIZE;
        float c = s / 2f;
        // Soft radial falloff (alpha = how much darkness this light removes). A gentle curve that
        // starts near-full at the center and eases to 0 at the edge gives a natural pool of light
        // rather than a hard-edged fully-lit disc; the mask model (DST_OUT) turns this into a smooth
        // transition from lit → dark.
        float[] dist   = { 0.0f, 0.55f, 0.80f, 1.0f };
        Color[] colors = {
            new Color(255, 255, 255, 255),
            new Color(255, 255, 255, 180),
            new Color(255, 255, 255,  70),
            new Color(255, 255, 255,   0)
        };
        falloffTexture = GdxRenderer.bakeRadialGradient(
                new RadialGradient(c, c, c, dist, colors), s, s);
        return falloffTexture;
    }

    /**
     * Colored ambient glow texture (center → transparent edge), baked per (color,radius) and cached,
     * mirroring the old getGradient() RadialGradientPaint image.
     */
    private Sprite getGradient(Color c, int radiusPx) {
        long key = ((long)(c.getRed()   & 0xFF) << 48)
                 | ((long)(c.getGreen() & 0xFF) << 40)
                 | ((long)(c.getBlue()  & 0xFF) << 32)
                 | (radiusPx & 0xFFFFFFFFL);
        Sprite img = gradientCache.get(key);
        if (img != null) return img;
        int diameter = Math.max(2, radiusPx * 2);
        float center = diameter / 2f;
        float[] dist   = { 0.0f, 0.4f, 1.0f };
        Color[] colors = {
            new Color(c.getRed(), c.getGreen(), c.getBlue(), 180),
            new Color(c.getRed(), c.getGreen(), c.getBlue(),  80),
            new Color(c.getRed(), c.getGreen(), c.getBlue(),   0)
        };
        img = GdxRenderer.bakeRadialGradient(
                new RadialGradient(center, center, center, dist, colors), diameter, diameter);
        gradientCache.put(key, img);
        return img;
    }

    // TODO(gfx-stage5): the old computeShadowPolysWorld() (silhouette-projection shadow geometry)
    // and buildSolidTileOccluders() were removed along with the on-screen polygon-shadow pass.
    // Reintroduce them if/when an offscreen darkness FrameBuffer with DSTOUT hole-punching + shadow
    // subtraction is added to the facade (gfx.GdxRenderer.BLEND_DSTOUT already exists for this).
    // Tile-grid shadow gating (markTilesLit / isRayBlocked*) is retained and still drives tileIsLit.

    /**
     * Draw one light source's soft glow onto the screen. The baked unit falloff texture is scaled
     * to the light diameter, tinted with {@code tint}, and drawn additively to brighten the
     * darkened scene back where the light reaches (the GPU equivalent of the old dst-out hole).
     *
     * @param g2  screen renderer (already in ADDITIVE blend mode)
     * @param sx  screen X of light center
     * @param sy  screen Y of light center
     * @param rad light radius in screen pixels
     * @param tint light color (warm white for the player/torch lights)
     * @param strength 0..1 multiplier on the punch strength (how strongly darkness is lifted)
     */
    private void drawLight(GdxRenderer g2, int sx, int sy, int rad, Color tint, float strength) {
        if (rad <= 0) return;
        Sprite falloff = getFalloffTexture();
        int d = rad * 2;
        g2.drawImageTinted(falloff, sx - rad, sy - rad, d, d, tint, strength);
    }

    /**
     * Mark tiles lit for every light-emitting entity in an array (torches in gp.obj, NPCs in gp.npc,
     * or any future light source). Mirrors the per-object loop the player light uses, so lit tiles /
     * reflective highlights work for NPC lights too.
     */
    private void markEntityArrayTilesLit(entity.Entity[] arr, boolean isLow, boolean useTileShadows) {
        if (arr == null) return;
        for (entity.Entity e : arr) {
            if (e == null || !e.lightSource || e.lightRadius <= 0) continue;
            int cx = e.worldX + gp.tileSize / 2;
            int cy = e.worldY + gp.tileSize / 2;
            int rPx = e.lightRadius * gp.tileSize;
            if (isLow) markTilesLitLowBFS(cx, cy, rPx);
            else       markTilesLit(cx, cy, rPx, useTileShadows, false);
        }
    }

    /**
     * Carve a light hole in the darkness mask for every light-emitting entity in an array. Used for
     * both torches (gp.obj) and NPCs (gp.npc) so a glowing NPC draws the player's eye in a dark cave.
     * Must be called while the mask is bound and in DST_OUT blend mode.
     */
    private void drawEntityArrayLights(GdxRenderer g2, entity.Entity[] arr,
                                       int playerWorldX, int playerWorldY,
                                       int playerScreenX, int playerScreenY,
                                       int screenWidth, int screenHeight, float punch) {
        if (arr == null) return;
        for (entity.Entity e : arr) {
            if (e == null || !e.lightSource || e.lightRadius <= 0) continue;
            int rWorld = e.lightRadius * gp.tileSize;
            int sx = e.worldX - playerWorldX + playerScreenX + gp.tileSize / 2;
            int sy = e.worldY - playerWorldY + playerScreenY + gp.tileSize / 2;
            if (sx + rWorld < 0 || sx - rWorld > screenWidth
                    || sy + rWorld < 0 || sy - rWorld > screenHeight) continue;
            drawLight(g2, sx, sy, rWorld, Color.WHITE, punch);
        }
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
        ArrayList<Rect> allOcc = gp.tileM.lightOccluderRects;
        for (int i = 0, n = allOcc.size(); i < n; i++) {
            if (allOcc.get(i).intersects(reusableLightBounds))
                tileShadowOccluders.add(allOcc.get(i));
        }

        int bw = maxColI - minCol + 1;
        int bh = maxRowI - minRow + 1;
        int cells = bw * bh;
        if (bfsVisited == null || bfsVisited.length < cells) bfsVisited = new boolean[cells];
        java.util.Arrays.fill(bfsVisited, 0, cells, false);

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
    private boolean isTileSolid(int col, int row, int ts, ArrayList<Rect> occ) {
        boolean[][] solid = gp.tileM.tileSolid;
        if (solid != null) {
            return col >= 0 && col < solid.length
                && row >= 0 && row < solid[col].length
                && solid[col][row];
        }
        int tx = col * ts, ty = row * ts;
        for (int i = 0, n = occ.size(); i < n; i++) {
            Rect r = occ.get(i);
            if (tx + ts > r.x && tx < r.x + r.width
             && ty + ts > r.y && ty < r.y + r.height) return true;
        }
        return false;
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
            ArrayList<Rect> allOcc = gp.tileM.lightOccluderRects;
            for (int i = 0, n = allOcc.size(); i < n; i++) {
                if (allOcc.get(i).intersects(reusableLightBounds))
                    tileShadowOccluders.add(allOcc.get(i));
            }
        }
        ArrayList<Rect> occ = null;
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
                                  ArrayList<Rect> occluders) {
        float dx = tx - ox, dy = ty - oy;
        for (int i = 0, n = occluders.size(); i < n; i++) {
            Rect r = occluders.get(i);
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

    // ===================== REFLECTIVE TILE HIGHLIGHTS =====================

    /**
     * After the darkness has been drawn, draw a warm highlight over every reflective
     * tile that is currently within a light's radius and on screen. Iterates the precomputed
     * tileM.reflectiveTilePositions list (built once at map load) instead of the visible viewport.
     */
    private void drawReflectiveHighlights(GdxRenderer g2, float maxDarkness,
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
            g2.setAlpha(alpha);
            g2.fillRect(screenX, screenY, ts, ts);
        }
        g2.setAlpha(1f);
    }

    // Reusable scratch arrays for the torch snapshot in drawReflectiveHighlights.
    // Sized to MAX_LIGHTS — enough headroom for all dynamic torches on any map.
    private final int[]   reusableTorchCX  = new int[MAX_LIGHTS];
    private final int[]   reusableTorchCY  = new int[MAX_LIGHTS];
    private final long[]  reusableTorchR2  = new long[MAX_LIGHTS];
    private final float[] reusableTorchInvR = new float[MAX_LIGHTS];

    // Strength with which each light brightens (lifts) the darkness back, additively. Split per tier since
    // LOW and HIGH/MEDIUM use completely different rendering paths below:
    //   - LOW_PUNCH_STRENGTH  → legacy baked DST_OUT falloff texture path (drawLight/drawEntityArrayLights).
    //     Stronger, since LOW has no shader glow/bloom to help the player/NPCs read in the dark.
    //   - SHADER_PUNCH_STRENGTH → feeds u_lightIntensity in the GLSL shader (HIGH/MEDIUM). Kept modest so a
    //     single light stays gently lit and two overlapping lights (player approaching an NPC) don't sum
    //     past full brightness over a wide merged area — merging still happens, it just stays readable.
    private static final float LOW_PUNCH_STRENGTH    = 0.95f;
    private static final float SHADER_PUNCH_STRENGTH = 0.28f;

    // ===================== GLSL LIGHT PATH (HIGH/MEDIUM) =====================
    // Reusable arrays holding the current frame's lights in SCREEN pixels, uploaded to the light
    // shader in one pass. Sized to MAX_LIGHTS + 1 (the player light). Rebuilt each frame; never
    // allocated per-frame.
    private final float[] slx  = new float[MAX_LIGHTS + 1];
    private final float[] sly  = new float[MAX_LIGHTS + 1];
    private final float[] srad = new float[MAX_LIGHTS + 1];
    private final float[] sr   = new float[MAX_LIGHTS + 1];
    private final float[] sg   = new float[MAX_LIGHTS + 1];
    private final float[] sb   = new float[MAX_LIGHTS + 1];
    private final float[] sint = new float[MAX_LIGHTS + 1];
    // Per-light WORLD position (screen px offset = worldX - cameraWorldX). The organic-noise texture is
    // sampled in world space so the light's textured falloff is painted onto the GROUND and stays locked
    // to the world as the camera scrolls — instead of the noise sliding across the ground with the light
    // (the "texture drifts / tracks camera at 2x" artifact). Pure world-space; identical for every light
    // including remote players on a multiplayer server (no dependence on the local player's screen pos).
    private final float[] swx  = new float[MAX_LIGHTS + 1];
    private final float[] swy  = new float[MAX_LIGHTS + 1];

    /** Add one light (screen coords + world coords, color, intensity) to the shader arrays; ignores
     *  off-screen and overflow. worldX/worldY drive the world-space noise texture only; lighting math
     *  still uses the screen position. */
    private int addShaderLight(int count, int sx, int sy, int worldX, int worldY, int rad, Color c,
                               float intensity, int screenW, int screenH) {
        if (count >= slx.length) return count;
        if (rad <= 0) return count;
        if (sx + rad < 0 || sx - rad > screenW || sy + rad < 0 || sy - rad > screenH) return count;
        slx[count]  = sx;   sly[count] = sy;   srad[count] = rad;
        swx[count]  = worldX; swy[count] = worldY;
        sr[count]   = c.getRed()   / 255f;
        sg[count]   = c.getGreen() / 255f;
        sb[count]   = c.getBlue()  / 255f;
        sint[count] = intensity < 0f ? 0f : (intensity > 1f ? 1f : intensity);
        return count + 1;
    }

    /**
     * Gather every active light into the shader arrays and render the smooth GLSL darkness mask.
     * Returns true if the GLSL path handled it (mask composited); false means the pipeline was
     * unavailable and the caller must run the legacy baked path.
     */
    private boolean drawShaderLighting(GdxRenderer g2, float darkness,
                                       int playerWorldX, int playerWorldY,
                                       int playerScreenX, int playerScreenY,
                                       int screenWidth, int screenHeight) {
        gfx.shader.ShaderPipeline pipe = g2.shaderPipeline();
        if (pipe == null || !pipe.isAvailable()) return false;

        int n = 0;
        // Player light (warm white). Center of the player tile. World pos = tile center in world coords.
        // psx/psy use the player's OWN screen position (its real worldX/Y offset from the camera), not
        // playerScreenX/playerWorldX above (the camera's position) — those can differ during a locked-
        // camera cutscene, and this light must stay attached to the player, not the camera.
        int psx = gp.player.screenX + (gp.player.worldX - playerWorldX) + gp.tileSize / 2;
        int psy = gp.player.screenY + (gp.player.worldY - playerWorldY) + gp.tileSize / 2;
        int pwx = gp.player.worldX + gp.tileSize / 2;
        int pwy = gp.player.worldY + gp.tileSize / 2;
        int pRad = playerLightRadius * gp.tileSize;
        n = addShaderLight(n, psx, psy, pwx, pwy, pRad, PLAYER_LIGHT_COLOR, SHADER_PUNCH_STRENGTH,
                           screenWidth, screenHeight);

        n = addEntityArrayShaderLights(n, gp.obj, playerWorldX, playerWorldY,
                                       playerScreenX, playerScreenY, screenWidth, screenHeight);
        n = addEntityArrayShaderLights(n, gp.npc, playerWorldX, playerWorldY,
                                       playerScreenX, playerScreenY, screenWidth, screenHeight);

        // Registered colored lights (addLight): crystals, magic glows, scripted torches. These now also
        // cast shadows via the shader mask (previously they only added flat ambient glow).
        for (int i = 0; i < lightCount && n < slx.length; i++) {
            int sx = lightWX[i] - playerWorldX + playerScreenX;
            int sy = lightWY[i] - playerWorldY + playerScreenY;
            n = addShaderLight(n, sx, sy, lightWX[i], lightWY[i], lightRadiusPx[i], lightColor[i],
                               Math.min(1f, lightIntensity[i] + 0.3f), screenWidth, screenHeight);
        }

        // Shadows are a consequence of light on BOTH shader tiers. HIGH runs the full 32-step march
        // with organic light detail; MEDIUM (the mobile tier) uses the cheap variant — 12 steps, no
        // noise — so phones still get real texture-cast shadows at a fraction of the cost.
        // F9 (LightDebug.noShadows) kills the march for the dancing-lights hunt.
        boolean shadows = !gfx.shader.LightDebug.noShadows;
        boolean cheap   = (gp.config.graphicsQuality != Config.GRAPHICS_HIGH);
        if (shadows) buildOccluderMask(g2);

        // Dancing-lights HUD telemetry: what the shader was actually given this frame.
        gfx.shader.LightDebug.tier = cheap ? "MED-cheap" : "HIGH";
        gfx.shader.LightDebug.lightCount = n;
        if (n > 0) { gfx.shader.LightDebug.light0X = slx[0]; gfx.shader.LightDebug.light0Y = sly[0]; }

        boolean ok = g2.renderShaderLightMask(nightColor, darkness, n,
                                              slx, sly, swx, swy, srad, sr, sg, sb, sint, shadows, cheap);
        if (!ok) return false;
        g2.setBlendMode(GdxRenderer.BLEND_NORMAL);
        g2.drawLightMask();
        return true;
    }

    /**
     * Render every on-screen shadow-caster's silhouette into the occluder FBO. The light shader then
     * ray-marches this mask so lit pixels behind a silhouette fall into shadow — the "rays hit the
     * texture, shadow left behind" effect. Uses each entity's CURRENT sprite frame (via drawOccluder),
     * so shadows track the visible pose; viewport-culled so off-screen casters cost nothing.
     */
    private void buildOccluderMask(GdxRenderer g2) {
        g2.beginOccluderMask();
        // SCENERY SILHOUETTES: the walls / trees / rocks / props drawn as depth-sorted tiles are the
        // actual "things around" — they cast shadows from their TEXTURES (the sprite's own alpha),
        // not from hitboxes. Flat background floor tiles are the ground the shadows land on, so they
        // are intentionally excluded (see TileManager.drawDepthOccluders). This is what makes shadows
        // appear on maps whose scenery is tilemap art rather than entities (e.g. the cave walls).
        if (gp.tileM != null) gp.tileM.drawDepthOccluders(g2);
        // The player is NOT drawn into the occluder mask. The player carries the primary light centered
        // on itself, so its silhouette would ray-march-shadow its OWN light pool — a black player-shaped
        // hole with starburst streaks (the "shadows mixed up" bug). LIGHT_EXCLUDE can't fully prevent it
        // because the player body spans a large fraction of its own (small) light radius. Excluding the
        // player from the mask kills it definitively; other casters (NPCs, monsters, objects, trees) are
        // offset from the lights that matter, so they cast correctly and get rim light.
        drawOccluderArray(g2, gp.npc);
        drawOccluderArray(g2, gp.monster);
        drawOccluderArray(g2, gp.obj);
        // Interactive tiles (trees, statues) opt in via castsShadow; wider margin covers tall canopies.
        if (gp.iTile != null) {
            for (entity.Entity it : gp.iTile) {
                if (it != null && it.castsShadow()) drawOccluderIfVisible(g2, it, gp.tileSize * 4);
            }
        }
        g2.endOccluderMask();
    }

    private void drawOccluderArray(GdxRenderer g2, entity.Entity[] arr) {
        if (arr == null) return;
        for (entity.Entity e : arr) {
            if (e == null) continue;
            // A light-EMITTING entity (torch, glowing NPC…) must not cast into the mask: its
            // silhouette sits exactly at its own light's center, so the ray march would shadow the
            // light it carries and cancel it out entirely — the same reason the player (who carries
            // the primary light) is excluded above. Its light still casts OTHER entities' shadows.
            if (e.lightSource && e.lightRadius > 0) continue;
            if (e.castsShadow()) drawOccluderIfVisible(g2, e);
        }
    }

    private void drawOccluderIfVisible(GdxRenderer g2, entity.Entity e) {
        drawOccluderIfVisible(g2, e, gp.tileSize);
    }

    private void drawOccluderIfVisible(GdxRenderer g2, entity.Entity e, int margin) {
        if (e == null) return;
        if (!gp.isEntityInViewport(e, margin)) return;
        e.drawOccluder(g2);
    }

    /** Add each light-emitting entity in an array to the shader light arrays (screen coords). */
    private int addEntityArrayShaderLights(int count, entity.Entity[] arr,
                                           int playerWorldX, int playerWorldY,
                                           int playerScreenX, int playerScreenY,
                                           int screenWidth, int screenHeight) {
        if (arr == null) return count;
        for (entity.Entity e : arr) {
            if (e == null || !e.lightSource || e.lightRadius <= 0) continue;
            int rWorld = e.lightRadius * gp.tileSize;
            int sx = e.worldX - playerWorldX + playerScreenX + gp.tileSize / 2;
            int sy = e.worldY - playerWorldY + playerScreenY + gp.tileSize / 2;
            int wx = e.worldX + gp.tileSize / 2;
            int wy = e.worldY + gp.tileSize / 2;
            // Entity torch color: warm, matches the reflective glow warmth.
            count = addShaderLight(count, sx, sy, wx, wy, rWorld, TORCH_LIGHT_COLOR, SHADER_PUNCH_STRENGTH,
                                   screenWidth, screenHeight);
        }
        return count;
    }

    // Warm light tints for the shader path (slightly warm white reads far nicer than pure white).
    // Warmer than pure white — a candle/torch amber-white. The light shader pushes the core warmer
    // still (hot gold heart), so these are the base/edge tint.
    private static final Color PLAYER_LIGHT_COLOR = new Color(255, 224, 170);
    private static final Color TORCH_LIGHT_COLOR  = new Color(255, 205, 140);

    /** Dev diagnostic: when true, prints which lighting path runs each frame + key coords.
     *  Enable in the REAL game with -Dlight.debug=1 (no harness needed) to see the true runtime tier. */
    public static boolean DEBUG_LIGHT_PATH = "1".equals(System.getProperty("light.debug"));
    private int debugFrameCounter = 0;
    /** Dev diagnostic (-Dlight.track=1): prints the per-frame delta of playerWorldX/screenX and the world
     *  offset (screenX - worldX) that every world point (and the shader light) is drawn with. If the offset
     *  is CONSTANT while walking, the light pool is coordinate-correct; if it JUMPS in step with movement,
     *  screenX lags worldX by a frame and the pool drifts by the walk delta — the "2x/tracks camera" bug. */
    public static boolean DEBUG_LIGHT_TRACK = "1".equals(System.getProperty("light.track"));
    private int lastDbgWorldX = Integer.MIN_VALUE, lastDbgScreenX = Integer.MIN_VALUE;

    // ===================== MAIN DRAW =====================
    public void draw(GdxRenderer g2, float currentMaxDarkness) {
        int screenWidth  = gp.screenWidth;
        int screenHeight = gp.screenHeight;

        int playerWorldX  = gp.getCamWorldX();
        int playerWorldY  = gp.getCamWorldY();
        int playerScreenX = gp.player.screenX;
        int playerScreenY = gp.player.screenY;

        // Per-frame drift tracker (-Dlight.track=1). Placed BEFORE the darkness early-return and tier
        // branch so it fires whenever the player moves — dark or not, any graphics tier. The offset
        // (screenX - worldX) is what every world point AND the shader light are shifted by. dWorld = how
        // far the player moved this frame; dOffset = how much the drawn offset changed. dOffset ~0 while
        // dWorld != 0 => pool glued to player (coords correct). dOffset tracking dWorld => screenX lagged
        // worldX and the pool drifts by exactly the walk delta — the "2x/tracks camera" bug.
        if (DEBUG_LIGHT_TRACK && lastDbgWorldX != Integer.MIN_VALUE) {
            int dWorld  = playerWorldX  - lastDbgWorldX;
            int dScreen = playerScreenX - lastDbgScreenX;
            int off  = playerScreenX - playerWorldX;
            int dOff = dScreen - dWorld;   // change in the world-draw offset this frame
            if (dWorld != 0 || dScreen != 0) {
                System.out.println("LIGHT_TRACK q=" + gp.config.graphicsQuality
                    + " dark=" + String.format("%.3f", currentMaxDarkness)
                    + " dWorld=" + dWorld + " dScreen=" + dScreen
                    + " offset(screenX-worldX)=" + off + " dOffset=" + dOff
                    + (dOff != 0 ? "  <-- POOL DRIFTS " + dOff + "px this frame" : "  (pool stable)"));
            }
        }
        lastDbgWorldX = playerWorldX; lastDbgScreenX = playerScreenX;

        // Light center = center of the player tile (not the top-left corner). World-space, so it must
        // be the player's own real position, not playerWorldX/Y above (the camera's position, which
        // can differ during a locked-camera cutscene) — this drives tile-lit bookkeeping/shadow math
        // that has to line up with where the player actually is.
        int playerLightCX = gp.player.worldX + gp.tileSize / 2;
        int playerLightCY = gp.player.worldY + gp.tileSize / 2;
        int lightPxWorld  = playerLightRadius * gp.tileSize;

        boolean isLow       = (gp.config.graphicsQuality == Config.GRAPHICS_LOW);
        boolean useTileShadows = (gp.config.graphicsQuality != Config.GRAPHICS_MEDIUM);

        // ========= ALWAYS: update tileIsLit (+ tileLightLevel for LOW) =========
        if (gp.tileM.tileIsLit != null) {
            gp.tileM.clearTileLitMap();
            if (isLow) {
                markTilesLitLowBFS(playerLightCX, playerLightCY, lightPxWorld);
                markEntityArrayTilesLit(gp.obj, isLow, useTileShadows);
                markEntityArrayTilesLit(gp.npc, isLow, useTileShadows);
            } else {
                markTilesLit(playerLightCX, playerLightCY, lightPxWorld, useTileShadows, false);
                markEntityArrayTilesLit(gp.obj, isLow, useTileShadows);
                markEntityArrayTilesLit(gp.npc, isLow, useTileShadows);
            }
        }

        if (currentMaxDarkness <= 0.001f) { gfx.shader.LightDebug.tier = "off (no darkness)"; return; }

        // ========= GLSL SMOOTH-LIGHT PATH (HIGH / MEDIUM) =========
        // Try the per-pixel shader mask first. On HIGH/MEDIUM with a capable GPU this replaces the
        // baked-falloff dst-out dance with smooth HDR lighting; if the pipeline is unavailable (old
        // GPU / shader compile failure) it returns false and we fall through to the legacy baked path
        // below, so behavior degrades gracefully and never crashes.
        boolean shaderLit = false;
        if (gp.config.graphicsQuality != Config.GRAPHICS_LOW) {
            shaderLit = drawShaderLighting(g2, currentMaxDarkness,
                    playerWorldX, playerWorldY, playerScreenX, playerScreenY,
                    screenWidth, screenHeight);
        }
        if (DEBUG_LIGHT_PATH && (debugFrameCounter++ % 60) == 0) {
            System.out.println("LIGHT_PATH quality=" + gp.config.graphicsQuality
                + " shaderLit=" + shaderLit + " bloomAvail=" + g2.bloomAvailable()
                + " darkness=" + currentMaxDarkness
                + " playerWorldX=" + playerWorldX + " playerScreenX=" + playerScreenX
                + " lightSX=" + (playerScreenX + gp.tileSize / 2)
                + " translateX=" + g2.getTranslateX() + " translateY=" + g2.getTranslateY());
        }
        // ========= DARKNESS MASK (offscreen) — legacy baked path =========
        if (!shaderLit) {
        gfx.shader.LightDebug.tier = "baked (shader unavailable)";
        // Proper 2D lighting: render a darkness LAYER into an offscreen alpha buffer, then carve soft
        // holes where lights reach (DST_OUT). Compositing that mask over the finished scene lets the
        // scene show through at its NATURAL brightness in lit areas and stay genuinely dark elsewhere —
        // replacing the old "darken + add white glow" approximation that washed the player out to a
        // bright blob and never let caves read as truly dark. Any new light source just carves its own
        // hole; no per-light tuning needed.
        g2.beginLightMask();

        // Fill the mask with the night color at the darkness alpha.
        g2.setBlendMode(GdxRenderer.BLEND_NORMAL);
        g2.setColor(nightColor);
        g2.setAlpha(currentMaxDarkness);
        g2.fillRect(0, 0, screenWidth, screenHeight);
        g2.setAlpha(1f);

        // Carve light holes: DST_OUT subtracts each falloff's alpha from the darkness, revealing the
        // scene. LOW_PUNCH_STRENGTH < 1 keeps a faint darkness even at a light's core so lit areas
        // read as "dimly lit", not fully bright — tweak per taste.
        g2.setBlendMode(GdxRenderer.BLEND_DSTOUT);
        float punch = LOW_PUNCH_STRENGTH;

        // ========= PLAYER LIGHT =========
        // Anchored to the player's own screen position (gp.player.screenX/Y + camOffset from its real
        // worldX/Y), NOT playerScreenX/playerWorldX above (those are the CAMERA's position, which can
        // differ from the player's during a locked-camera cutscene — see Player.draw()'s camOffsetX/Y).
        // Without this the light stays pinned to the panned camera center while the player's sprite
        // correctly recedes into the distance, visually detaching the glow from its owner.
        int playerSX = gp.player.screenX + (gp.player.worldX - playerWorldX) + gp.tileSize / 2;
        int playerSY = gp.player.screenY + (gp.player.worldY - playerWorldY) + gp.tileSize / 2;
        drawLight(g2, playerSX, playerSY, lightPxWorld, Color.WHITE, punch);

        // ========= TORCH / NPC LIGHTS =========
        // Any entity flagged lightSource carves a light hole — torches (gp.obj) and NPCs (gp.npc), so a
        // glowing NPC beckons the player through a dark cave. New light-emitting entity types just get
        // passed here.
        drawEntityArrayLights(g2, gp.obj, playerWorldX, playerWorldY, playerScreenX, playerScreenY,
                              screenWidth, screenHeight, punch);
        drawEntityArrayLights(g2, gp.npc, playerWorldX, playerWorldY, playerScreenX, playerScreenY,
                              screenWidth, screenHeight, punch);
        torchShadowCacheDirty = false;

        // Finish the mask and composite it over the scene with normal blending.
        g2.setBlendMode(GdxRenderer.BLEND_NORMAL);
        g2.endLightMask();
        g2.drawLightMask();
        } // end legacy baked path (!shaderLit)

        // ========= BORDER VIGNETTE (night-scaled) =========
        // Extra darkening toward the screen edges, scaled with darkness — the far margins read as
        // blacker at night so the world feels bigger/darker beyond the light pool, without affecting
        // daytime at all (alpha is gated on currentMaxDarkness).
        drawBorderVignette(g2, currentMaxDarkness, screenWidth, screenHeight);

        // ========= COLORED LIGHTS (ambient glow, no shadow casting) =========
        // These ADD colored light on top of the composited scene (a colored torch/crystal glow), so
        // they stay additive on the default framebuffer.
        g2.setBlendMode(GdxRenderer.BLEND_ADDITIVE);
        if (lightCount > 0 && currentMaxDarkness > 0.05f) {
            for (int i = 0; i < lightCount; i++) {
                int lx  = lightWX[i] - playerWorldX + playerScreenX;
                int ly  = lightWY[i] - playerWorldY + playerScreenY;
                int rad = lightRadiusPx[i];
                if (lx + rad < 0 || lx - rad > screenWidth
                        || ly + rad < 0 || ly - rad > screenHeight) continue;

                float a = lightIntensity[i] * currentMaxDarkness;
                if (a > 1f) a = 1f;
                if (a < 0.01f) continue;

                g2.setAlpha(a);
                g2.drawImage(getGradient(lightColor[i], rad), lx - rad, ly - rad, rad * 2, rad * 2);
            }
        }

        // Reset GL state to normal alpha blending for the rest of the frame.
        g2.setAlpha(1f);
        g2.setBlendMode(GdxRenderer.BLEND_NORMAL);

        // ========= REFLECTIVE TILE HIGHLIGHTS (normal blend) =========
        drawReflectiveHighlights(g2, currentMaxDarkness,
                playerWorldX, playerWorldY, playerScreenX, playerScreenY);
    }
}

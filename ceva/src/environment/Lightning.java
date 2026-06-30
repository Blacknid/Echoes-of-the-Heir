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

    public int playerLightRadius = 3;

    private static final int MAX_LIGHTS = 20;
    public int[]   lightWX        = new int[MAX_LIGHTS];
    public int[]   lightWY        = new int[MAX_LIGHTS];
    public int[]   lightRadiusPx  = new int[MAX_LIGHTS];
    public Color[] lightColor     = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // Night color the darkness is tinted with (was a black ARGB fill in the overlay).
    private static final Color NIGHT_COLOR = new Color(6, 8, 18);

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
        // Same falloff stops the old getDstOutLight used (0.0→1.0 alpha curve), white throughout.
        float[] dist   = { 0.0f, 0.30f, 0.65f, 1.0f };
        Color[] colors = {
            new Color(255, 255, 255, 255),
            new Color(255, 255, 255, 230),
            new Color(255, 255, 255, 110),
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

    // Strength with which each light brightens (lifts) the darkness back, additively.
    private static final float LIGHT_PUNCH_STRENGTH = 0.95f;

    // ===================== MAIN DRAW =====================
    public void draw(GdxRenderer g2, float currentMaxDarkness) {
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

        // ========= DARKNESS: one full-screen night-color fill =========
        // GPU-native replacement for the BufferedImage overlay fill.
        g2.setColor(NIGHT_COLOR);
        g2.setAlpha(currentMaxDarkness);
        g2.fillRect(0, 0, screenWidth, screenHeight);
        g2.setAlpha(1f);

        // ========= LIGHTS: additive soft glows that lift the darkness back =========
        // TODO(gfx-stage5): on-screen polygon shadow occlusion omitted — see class doc. The radial
        // glows below do not cast hard polygon shadows; tile-grid shadows still gate tileIsLit.
        g2.setBlendMode(GdxRenderer.BLEND_ADDITIVE);

        // Warm white light tint: scaled by darkness so lights don't over-brighten at dusk.
        float punch = LIGHT_PUNCH_STRENGTH * currentMaxDarkness;

        // ========= PLAYER LIGHT =========
        int playerSX = playerScreenX + gp.tileSize / 2;
        int playerSY = playerScreenY + gp.tileSize / 2;
        drawLight(g2, playerSX, playerSY, lightPxWorld, Color.WHITE, punch);

        // ========= TORCH LIGHTS =========
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] == null || !gp.obj[i].lightSource || gp.obj[i].lightRadius <= 0) continue;

            int tRadWorld = gp.obj[i].lightRadius * gp.tileSize;
            int tx = gp.obj[i].worldX - playerWorldX + playerScreenX + gp.tileSize / 2;
            int ty = gp.obj[i].worldY - playerWorldY + playerScreenY + gp.tileSize / 2;

            if (tx + tRadWorld < 0 || tx - tRadWorld > screenWidth
                    || ty + tRadWorld < 0 || ty - tRadWorld > screenHeight) continue;

            drawLight(g2, tx, ty, tRadWorld, Color.WHITE, punch);
        }
        torchShadowCacheDirty = false;

        // ========= COLORED LIGHTS (ambient glow, no shadow casting) =========
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

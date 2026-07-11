package tile;

import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;
import util.UtilityTool;

// Tiled Editor : new String variant = "nume copac / statuie / etc".
//
// Trees, statues, movable plants — anything drawn as one or more swaying canopy layers over a
// static trunk/base, with a background-anchored shadow. One PNG per variant holds every part side
// by side in a single row of `cellCount` EQUAL-WIDTH cells (cellWidth = sheetWidth / cellCount,
// full sheetHeight tall — cells are NOT assumed square, since sheetWidth/sheetHeight alone can't
// tell us how many cells there are). Cell order, from the END of the row:
//   cell N-1 (last)           = shadow
//   cell N-2 (second-to-last) = trunk / base
//   cells 0 .. N-3            = canopy layers, each swaying independently as one rigid piece
// A plain trunk+shadow prop (a statue, say) is just a 2-cell sheet with no canopy cells at all.
// This means adding a new tree/statue/plant is one image file, sized as wide as it needs to be,
// instead of two or three separate files — and the shadow is real art, not a procedural blob.

public class IT_Prop extends interactiveTile {

    // sizeInTiles is how many tiles tall/wide this variant draws at. cellCount is how many equal
    // slices the sheet is cut into (must match how the PNG was actually laid out).
    private record Variant(String sheetPath, int cellCount, int sizeInTiles, float swayPixels,
                            float hitboxWidth, float hitboxHeight,
                            float hitboxOffsetX, float hitboxOffsetY) {
        // Convenience: default size of 4 tiles, hitbox centered/bottom-anchored (offset 0,0).
        Variant(String sheetPath, int cellCount, int sizeInTiles, float swayPixels,
                float hitboxWidth, float hitboxHeight) {
            this(sheetPath, cellCount, sizeInTiles, swayPixels, hitboxWidth, hitboxHeight, 0f, 0f);
        }
    }

    private static final String DEFAULT_VARIANT = "default";

    private static final Map<String, Variant> VARIANTS = new HashMap<>();
    static {
        VARIANTS.put(DEFAULT_VARIANT, new Variant(
                "/res/Interactive/Tree.png",
                3, 4, 2f, 1.5f, 1f));
        VARIANTS.put("Fruit", new Variant(
                "/res/Interactive/Fruits_Tree.png",
                3, 4, 1f, 1.5f, 1f));
        VARIANTS.put("Yellow", new Variant(
                "/res/Interactive/Yellow_Tree.png",
                5, 5, 1f, 1.5f, 1f));
        VARIANTS.put("Pin", new Variant(
                "/res/Interactive/Pin_Tree.png",
                3, 5, 1f, 1.5f, 0.5f));
        VARIANTS.put("Statue", new Variant(
                "/res/Interactive/Statue.png",
                3, 3, 1f, 1.5f, 0.5f,
                0f, -0.75f
            ));

        // Exemplu prop cu mai multe straturi de coroana (frunze/ramuri separate care se leagana
        // independent): sheet = [canopy0 | canopy1 | trunk | shadow] (4 cells).
        // VARIANTS.put("bushy", new Variant(
        //         "/res/tiles/CANVAS VILLAGE/Field/tree_sheet.png",
        //         4, 4, 1.5f, 2f, 1.25f, 0f, 0.5f));
    }

    /**
     * One sheet sliced by the [canopy...][trunk][shadow] convention (last=shadow, prev=trunk).
     * Every cell is scaled to drawSize WIDE, keeping its own native aspect ratio for height
     * (never forced square), so a wider-than-tall cell doesn't get vertically squashed.
     * If there's exactly ONE canopy cell it's sliced into CANOPY_BANDS horizontal bands that sway
     * independently (the classic single-tree rustle); with 2+ canopy cells each cell is already an
     * independent layer, so no further slicing happens — they sway as whole pieces.
     */
    private static final class PropAssets {
        private static final int CANOPY_BANDS = 3;
        // Source art is authored at 16x16 then upscaled 2x to 32x32 PNGs — every native pixel is
        // really a 2x2 block. Slicing/scaling math that lands mid-block (odd native Y, or a scale
        // factor that isn't a whole multiple) cuts through a block and leaves a visible seam/gap.
        // Snapping every boundary and scaled size to this grid keeps every cut pixel-perfect.
        private static final int PIXEL_SCALE = 2;

        final Sprite[] canopyLayers; // whole-piece layers (used when there are 2+ canopy cells)
        final Sprite[] canopyBands;  // sliced bands of the single canopy cell (used when there's 1)
        final int[] canopyBandY;     // cumulative top-Y of each band, parallel to canopyBands
        final Sprite trunk;          // null if the sheet has no trunk cell (fewer than 2 cells)
        final Sprite shadow;         // null if the sheet has no shadow cell (empty sheet)

        PropAssets(String path, int cellCount, int drawSize) {
            Sprite sheet = ResourceCache.loadImageIfPresent(path);
            if (sheet == null) {
                canopyLayers = new Sprite[0];
                canopyBands = null;
                canopyBandY = null;
                trunk = null;
                shadow = null;
                return;
            }
            int cellH = sheet.getHeight();
            int n = Math.max(1, cellCount);
            int cellW = sheet.getWidth() / n;
            int drawH = snap(cellH * drawSize / cellW);

            int shadowIdx = n - 1;
            int trunkIdx = n - 2; // -1 (absent) if the sheet is shadow-only

            shadow = scaledCell(sheet, shadowIdx, cellW, cellH, drawSize, drawH);
            trunk = (trunkIdx >= 0) ? scaledCell(sheet, trunkIdx, cellW, cellH, drawSize, drawH) : null;

            int canopyCount = Math.max(0, trunkIdx); // cells [0, trunkIdx)
            if (canopyCount == 1) {
                canopyLayers = new Sprite[0];
                Sprite canopyCell = sheet.getSubimage(0, 0, cellW, cellH);
                canopyBands = new Sprite[CANOPY_BANDS];
                canopyBandY = new int[CANOPY_BANDS];
                int cumulativeY = 0;
                for (int b = 0; b < CANOPY_BANDS; b++) {
                    int y0 = snap(b * cellH / CANOPY_BANDS);
                    int y1 = (b == CANOPY_BANDS - 1) ? cellH : snap((b + 1) * cellH / CANOPY_BANDS);
                    Sprite nativeBand = canopyCell.getSubimage(0, y0, cellW, y1 - y0);
                    int scaledBandH = (b == CANOPY_BANDS - 1) ? drawH - cumulativeY : snap((y1 - y0) * drawH / cellH);
                    canopyBands[b] = UtilityTool.scaleImage(nativeBand, drawSize, scaledBandH);
                    canopyBandY[b] = cumulativeY;
                    cumulativeY += scaledBandH;
                }
            } else {
                canopyBands = null;
                canopyBandY = null;
                canopyLayers = new Sprite[canopyCount];
                for (int i = 0; i < canopyCount; i++) {
                    canopyLayers[i] = scaledCell(sheet, i, cellW, cellH, drawSize, drawH);
                }
            }
        }

        private static Sprite scaledCell(Sprite sheet, int idx, int cellW, int cellH, int drawW, int drawH) {
            return UtilityTool.scaleImage(sheet.getSubimage(idx * cellW, 0, cellW, cellH), drawW, drawH);
        }

        /** Round to the nearest multiple of PIXEL_SCALE so every cut/scale lands on a whole source pixel. */
        private static int snap(int value) {
            return Math.round(value / (float) PIXEL_SCALE) * PIXEL_SCALE;
        }
    }

    private static final Map<String, PropAssets> ASSET_CACHE = new HashMap<>();

    private final PropAssets assets;
    private final Variant variant;

    private float phase;
    private final float swaySpeed;
    private final float swayPixels;
    // +1 or -1: which way "odd" canopy layers sway relative to "even" ones. Randomized per-prop
    // (but fixed for that prop's lifetime) so neighboring props don't all sway in visual lockstep.
    private final float swayParityFlip;

    public IT_Prop(GamePanel gp, int col, int row) {
        this(gp, col, row, DEFAULT_VARIANT);
    }

    public IT_Prop(GamePanel gp, int col, int row, String variantId) {
        super(gp, col, row);
        collision = true; // Coliziune

        String id = (variantId == null || variantId.isBlank() || !VARIANTS.containsKey(variantId))
                ? DEFAULT_VARIANT : variantId;
        variant = VARIANTS.get(id);
        int drawSize = gp.tileSize * variant.sizeInTiles();
        assets = ASSET_CACHE.computeIfAbsent(id, key -> new PropAssets(variant.sheetPath(), variant.cellCount(), drawSize));

        int hitboxW = Math.round(gp.tileSize * variant.hitboxWidth());
        int hitboxH = Math.round(gp.tileSize * variant.hitboxHeight());
        solidArea.width = hitboxW;
        solidArea.height = hitboxH;
        solidArea.x = (gp.tileSize - hitboxW) / 2 + Math.round(gp.tileSize * variant.hitboxOffsetX());
        solidArea.y = gp.tileSize - hitboxH + Math.round(gp.tileSize * variant.hitboxOffsetY());
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        // One cheap deterministic per-tile hash drives all the "make each prop feel distinct"
        // randomness below — same tile position always gives the same prop, but neighboring
        // props land on different phase/speed/pixels/direction so they don't animate in lockstep.
        int hash = (col * 928371 + row * 68273 + 12345);
        hash ^= (hash >>> 15);

        phase = (hash & 0xFF) * 0.0245f;
        swaySpeed = 0.03f + ((hash >>> 8) % 21 - 10) * 0.00045f; // 0.03 +/- 0.0045 (slow, gentle sway)
        // variant.swayPixels() is authored in NATIVE art pixels, but sway is applied in screen space
        // (already gp.scale'd up, e.g. 2x by default) — without this, swayPixels=1 only ever moves
        // 1 screen px, which is half a native pixel and reads as barely-there jitter.
        float swayPixelsNative = variant.swayPixels() + ((hash >>> 16) % 21 - 10) * 0.02f; // +/- 0.2px
        swayPixels = (float) (swayPixelsNative * gp.scale);
        swayParityFlip = (hash & 1) == 0 ? 1f : -1f;
    }

    @Override
    public void update() {
        super.update();
        phase += swaySpeed;
    }

    private int anchorX(int drawSize) { return worldX - (drawSize - gp.tileSize) / 2; }
    private int anchorY(int drawSize) { return worldY - (drawSize - gp.tileSize); }
    private int screenX(int drawSize) { return anchorX(drawSize) - gp.getCamWorldX() + gp.player.screenX; }
    private int screenY(int drawSize) { return anchorY(drawSize) - gp.getCamWorldY() + gp.player.screenY; }

    private boolean offscreen(int drawSize) {
        int ax = anchorX(drawSize), ay = anchorY(drawSize);
        return ax + drawSize <= gp.getCamWorldX() - gp.player.screenX ||
               ax >= gp.getCamWorldX() + (gp.screenWidth - gp.player.screenX) ||
               ay + drawSize <= gp.getCamWorldY() - gp.player.screenY ||
               ay >= gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY);
    }

    /** Every prop casts a dynamic canopy+trunk silhouette shadow (Stage 2 lighting). */
    @Override
    public boolean castsShadow() { return true; }

    // Mostly plain sin(x) (the smoothest, most even glide) with just a light touch of sin(x)^3
    // mixed in for a faint settle at center — a full 50/50 blend still felt rushed between the
    // pause points, since the cube component forces the same travel through a shorter window.
    // Lean this ratio further toward sin(x) (lower the 0.2 weight) for an even more even glide, or
    // toward sin(x)^3 (raise it) for a more pronounced center pause — they trade off directly.
    private static float ease(double radians) {
        float s = (float) Math.sin(radians);
        return 0.8f * s + 0.2f * (s * s * s);
    }

    // Per-layer sway offset (in px) for canopy layer `i` at the current phase. Even layers sway one
    // way, odd layers the opposite way — swayParityFlip randomizes (per-prop, fixed) which physical
    // direction counts as "even" so neighboring props don't sway in lockstep.
    private float canopySwayOffset(int layerIndex) {
        float layerPhase = phase + layerIndex * 1.7f;
        float speedMul = 1f + (layerIndex % 3) * 0.15f; // 1.0, 1.15, 1.3, 1.0, ...
        float swayDir = ((layerIndex % 2 == 0) ? 1f : -1f) * swayParityFlip;
        return ease(layerPhase * speedMul) * swayPixels * swayDir;
    }

    /**
     * Draw the prop's silhouette (trunk + canopy layers) in solid black into the shadow-occluder
     * mask, reusing the exact same layout/sway as {@link #draw} so the cast shadow matches the
     * swaying canopy. IT_Prop overrides draw() with a custom renderer and stores no walkFrames, so
     * the generic Entity.drawOccluder can't handle it — this override does.
     */
    @Override
    public void drawOccluder(GdxRenderer g2) {
        int drawSize = gp.tileSize * variant.sizeInTiles();
        if (offscreen(drawSize)) return;
        int screenX = screenX(drawSize);
        int screenY = screenY(drawSize);
        if (assets.trunk != null) {
            g2.drawImageTinted(assets.trunk, screenX, screenY, assets.trunk.getWidth(), assets.trunk.getHeight(), gfx.Color.BLACK, 1f);
        }
        if (assets.canopyBands != null) {
            drawCanopyBandsOccluder(g2, screenX, screenY);
        } else {
            for (int i = 0; i < assets.canopyLayers.length; i++) {
                if (assets.canopyLayers[i] == null) continue;
                int offset = Math.round(canopySwayOffset(i));
                Sprite layer = assets.canopyLayers[i];
                g2.drawImageTinted(layer, screenX + offset, screenY, layer.getWidth(), layer.getHeight(), gfx.Color.BLACK, 1f);
            }
        }
    }

    private void drawCanopyBandsOccluder(GdxRenderer g2, int screenX, int screenY) {
        for (int b = assets.canopyBands.length - 1; b >= 0; b--) {
            Sprite band = assets.canopyBands[b];
            if (band == null) continue;
            int offset = Math.round(bandSwayOffset(b));
            g2.drawImageTinted(band, screenX + offset, screenY + assets.canopyBandY[b], band.getWidth(), band.getHeight(), gfx.Color.BLACK, 1f);
        }
    }

    /**
     * Ground shadow is drawn in its own pre-entity pass (see Entity.drawGroundShadowPass /
     * RenderPipeline.drawGroundShadows) instead of inline here, so it never gets depth-sorted
     * alongside the prop's own trunk/canopy — otherwise a player or NPC standing "in front of" the
     * prop in depth order would have the prop's shadow painted on top of their sprite. The shadow
     * is real art (the sheet's last cell), drawn once at the same rect as the trunk.
     */
    @Override
    public void drawGroundShadowPass(GdxRenderer g2) {
        if (assets.shadow == null) return;
        int drawSize = gp.tileSize * variant.sizeInTiles();
        if (offscreen(drawSize)) return;
        g2.drawImage(assets.shadow, screenX(drawSize), screenY(drawSize), assets.shadow.getWidth(), assets.shadow.getHeight());
    }

    @Override
    public void draw(GdxRenderer g2) {
        int drawSize = gp.tileSize * variant.sizeInTiles();
        if (offscreen(drawSize)) return;

        int screenX = screenX(drawSize);
        int screenY = screenY(drawSize);

        if (assets.trunk != null) {
            g2.drawImage(assets.trunk, screenX, screenY, assets.trunk.getWidth(), assets.trunk.getHeight());
        }

        if (assets.canopyBands != null) {
            // Single canopy cell, sliced into bands that sway independently (classic rustle).
            drawCanopyBands(g2, screenX, screenY);
        } else {
            // 2+ canopy cells: each is already an independent layer (e.g. separate branches), drawn
            // in sheet order (cell 0 first, so later cells draw on top) swaying as a whole piece via
            // its own phase/speed/direction (see canopySwayOffset) so layers don't move in lockstep.
            for (int i = 0; i < assets.canopyLayers.length; i++) {
                if (assets.canopyLayers[i] == null) continue;
                float offset = canopySwayOffset(i);
                Sprite layer = assets.canopyLayers[i];
                g2.drawImage(layer, screenX + offset, screenY, layer.getWidth(), layer.getHeight());
            }
        }
    }

    // Per-band sway offset (in px) for band `b` of a single sliced canopy cell — even bands sway
    // one way, odd bands the opposite way, giving the "1st/3rd together, 2nd opposite" rustle look.
    private float bandSwayOffset(int b) {
        float swayDir = ((b % 2 == 0) ? 1f : -1f) * swayParityFlip;
        return ease(phase) * swayPixels * swayDir;
    }

    private void drawCanopyBands(GdxRenderer g2, int screenX, int screenY) {
        int bandCount = assets.canopyBands.length;
        // Draw bottom-to-top so each band overlaps 1px on top of the band below it — matches
        // natural stacking order and means the overlap pixel always belongs to whichever band
        // is "in front".
        for (int b = bandCount - 1; b >= 0; b--) {
            Sprite band = assets.canopyBands[b];
            if (band == null) continue;
            // Sub-pixel offset (float, not rounded): at small swayPixels a whole-pixel snap only
            // ever lands on a couple of positions and reads as a jump, not a slide. Fractional
            // positioning here trades perfect pixel-grid alignment for a genuinely smooth glide.
            float offset = bandSwayOffset(b);
            int bandY = assets.canopyBandY[b];
            int bandH = band.getHeight();
            // Adjacent bands sway to different X offsets, which can leave a hairline seam at their
            // shared Y boundary. Extend this band's draw rect 1px upward (reusing its own top edge
            // pixels via scaling) so it overlaps the band above by 1px and hides the seam, same as
            // before fractional positioning — the bottom band's bottom edge is untouched.
            int growTop = (b == 0) ? 0 : 1;
            g2.drawImage(band, screenX + offset, (float) (screenY + bandY - growTop), (float) band.getWidth(), (float) (bandH + growTop));
        }
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return false; // not choppable/destructible for now
    }
}

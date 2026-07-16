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
// Trees, statues, movable plants, anything drawn as one or more swaying canopy layers over a
// static trunk/base, with a background-anchored shadow. One PNG per variant holds every part side
// by side in a single row of `cellCount` EQUAL-WIDTH cells (cellWidth = sheetWidth / cellCount,
// full sheetHeight tall, cells are NOT assumed square, since sheetWidth/sheetHeight alone can't
// tell us how many cells there are). Cell order, from the END of the row:
//   cell N-1 (last)           = shadow
//   cell N-2 (second-to-last) = trunk / base
//   cells 0 .. N-3            = canopy layers, each swaying independently as one rigid piece
// A plain trunk+shadow prop (a statue, say) is just a 2-cell sheet with no canopy cells at all.
// This means adding a new tree/statue/plant is one image file, sized as wide as it needs to be,
// instead of two or three separate files, and the shadow is real art, not a procedural blob.

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
        VARIANTS.put("Glow", new Variant(
                "/res/Interactive/Glow_Tree.png",
                5, 5, 1f, 1.5f, 1f));
        VARIANTS.put("Glow2", new Variant(
                "/res/Interactive/Glow2_Tree.png",
                4, 5, 1f, 1.5f, 1f));

    }

    private static final class PropAssets {
        private static final int CANOPY_BANDS = 3;
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
    private final String variantId;

    private static final int MAX_FRUITS_PER_TREE = 4;
    private static final int MIN_FRUITS_PER_TREE = 3;
    private final int maxFruits;
    private int fruitsDropped = 0;

    // Per-instance random starting point in the sway cycle (seeded from tile position, see ctor).
    private final float phaseSeed;
    // Radians/second, NOT radians/tick: the sway is driven by wall-clock time at draw time (see
    // drawPhase()) instead of the fixed 60Hz update() tick, so it stays perfectly smooth even when65
    // the monitor refresh rate isn't a clean multiple of 60, a fixed-tick phase only changes 60
    // times/sec, which on a 75/144/165Hz display redraws the same position for several frames in a
    // row before jumping, reading as stepped motion no matter how smooth the easing curve is.
    private final float swaySpeedPerSecond;
    private final float swayPixels;
    private final float swayParityFlip;

    public IT_Prop(GamePanel gp, int col, int row) {
        this(gp, col, row, DEFAULT_VARIANT);
    }

    public IT_Prop(GamePanel gp, int col, int row, String variantIdArg) {
        super(gp, col, row);
        collision = true; // Coliziune

        String id = (variantIdArg == null || variantIdArg.isBlank() || !VARIANTS.containsKey(variantIdArg))
                ? DEFAULT_VARIANT : variantIdArg;
        variantId = id;
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


        int hash = (col * 928371 + row * 68273 + 12345);
        hash ^= (hash >>> 15);

        phaseSeed = (hash & 0xFF) * 0.0245f;
        float swaySpeedPerTick = 0.03f + ((hash >>> 8) % 21 - 10) * 0.00045f; // 0.03 +/- 0.0045 (slow, gentle sway)
        swaySpeedPerSecond = swaySpeedPerTick * TARGET_UPS_FOR_SWAY;

        float swayPixelsNative = variant.swayPixels() + ((hash >>> 16) % 21 - 10) * 0.02f; // +/- 0.2px
        swayPixels = (float) (swayPixelsNative * gp.scale);
        swayParityFlip = (hash & 1) == 0 ? 1f : -1f;

        int fruitRange = MAX_FRUITS_PER_TREE - MIN_FRUITS_PER_TREE + 1;
        maxFruits = MIN_FRUITS_PER_TREE + ((hash >>> 24) % fruitRange);
    }

    // Matches GamePanel.TARGET_UPS (the fixed simulation rate the old per-tick swaySpeed was tuned
    // against) so converting to radians/second preserves the exact same sway speed as before.
    private static final float TARGET_UPS_FOR_SWAY = 60f;

    /** Wall-clock phase at draw time, see the swaySpeedPerSecond field doc for why. */
    private float drawPhase() {
        return phaseSeed + (System.nanoTime() * 1e-9f) * swaySpeedPerSecond;
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

    @Override
    public boolean castsShadow() { return true; }


    // A plain sine's velocity peaks at the zero-crossing and drops to zero at the extremes, which
    // reads as "hold at the end, then dart through the middle" instead of a continuous slide.
    // Re-mapping the PHASE through a smoothstep (not the output) flattens the middle crossing and
    // spreads the motion evenly across the swing instead, so the branch glides from side to side at
    // a steadier speed and only truly eases right at the two extremes where it turns around.
    private static float ease(double radians) {
        double twoPi = Math.PI * 2;
        double cycle = radians / twoPi;
        double frac = cycle - Math.floor(cycle);       // 0..1 position in the cycle
        float t = (float) (frac * 2.0);                 // 0..2, two half-swings per cycle
        boolean secondHalf = t >= 1f;
        float local = secondHalf ? t - 1f : t;          // 0..1 within this half-swing
        float smooth = local * local * (3f - 2f * local); // smoothstep: steady mid, easing at ends
        float half = smooth * 2f - 1f;                  // -1..1 across this half-swing
        return secondHalf ? -half : half;
    }

    private float canopySwayOffset(int layerIndex) {
        float layerPhase = drawPhase() + layerIndex * 1.7f;
        float speedMul = 1f + (layerIndex % 3) * 0.15f; // 1.0, 1.15, 1.3, 1.0, ...
        float swayDir = ((layerIndex % 2 == 0) ? 1f : -1f) * swayParityFlip;
        return ease(layerPhase * speedMul) * swayPixels * swayDir;
    }

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

            withLinearFilter(assets.canopyBands[0], () -> drawCanopyBands(g2, screenX, screenY));
        } else if (assets.canopyLayers.length > 0) {

            withLinearFilter(assets.canopyLayers[0], () -> {
                for (int i = 0; i < assets.canopyLayers.length; i++) {
                    if (assets.canopyLayers[i] == null) continue;
                    float offset = canopySwayOffset(i);
                    Sprite layer = assets.canopyLayers[i];
                    g2.drawImage(layer, screenX + offset, screenY, layer.getWidth(), layer.getHeight());
                }
            });
        }
    }

    private float bandSwayOffset(int b) {
        float swayDir = ((b % 2 == 0) ? 1f : -1f) * swayParityFlip;
        return ease(drawPhase()) * swayPixels * swayDir;
    }

    private void drawCanopyBands(GdxRenderer g2, int screenX, int screenY) {
        int bandCount = assets.canopyBands.length;

        for (int b = bandCount - 1; b >= 0; b--) {
            Sprite band = assets.canopyBands[b];
            if (band == null) continue;

            float offset = bandSwayOffset(b);
            int bandY = assets.canopyBandY[b];
            int bandH = band.getHeight();

            int growTop = (b == 0) ? 0 : 1;
            g2.drawImage(band, screenX + offset, (float) (screenY + bandY - growTop), (float) band.getWidth(), (float) (bandH + growTop));
        }
    }

    /**
     * Swaying canopy layers move by sub-pixel amounts each frame; under this game's usual
     * nearest-neighbor filtering that motion only becomes visible once it crosses a whole device
     * pixel, so a slow small-amplitude sway reads as a jerky stair-step instead of a slide.
     * Switching just the canopy texture to bilinear filtering for these draws lets it genuinely
     * interpolate between pixels. Trunk/shadow/every other sprite sharing the same sheet texture is
     * unaffected since the filter is restored immediately after.
     */
    private static void withLinearFilter(Sprite anyCanopySprite, Runnable drawCalls) {
        if (anyCanopySprite == null) { drawCalls.run(); return; }
        com.badlogic.gdx.graphics.Texture tex = anyCanopySprite.texture();
        tex.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,
                      com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        try {
            drawCalls.run();
        } finally {
            tex.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Nearest,
                          com.badlogic.gdx.graphics.Texture.TextureFilter.Nearest);
        }
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return false;
    }

    /** Fruit-variant trees pop a fruit loose on each hit, up to a per-tree cap, without being
 * destroyed (destructible stays false, see class doc for why props never break). Reuses
     *  interactiveTile's invincible/invincibleCounter as a simple per-swing cooldown. */
    @Override
    public void onAttackHit(Entity player) {
        if (!"Fruit".equals(variantId) || invincible || fruitsDropped >= maxFruits) return;
        invincible = true;
        invincibleCounter = 0;
        fruitsDropped++;

        // Canopy occupies roughly the top portion of the tree's full draw height, trunk/base the
        // bottom ~1 tile, so the fall should visually start about (sizeInTiles - 1) tiles above the
        // landing spot, putting its origin up in the leaves rather than at the trunk.
        float canopyFallHeight = Math.max(1f, variant.sizeInTiles() - 1);

        // Fixed left / middle / right pattern (cycling if a tree drops more than 3), so each hit
        // visibly comes from a different part of the canopy instead of clustering together. The
        // launch origin (where the fall starts, up in the leaves) and the landing spot (beside the
        // trunk once it's down) both use the same side, so the whole fall reads as one path.
        int patternIdx = (fruitsDropped - 1) % 3; // 0 = left, 1 = middle, 2 = right
        float canopyHalfWidth = Math.max(0.5f, (variant.sizeInTiles() - 1) / 2f);
        float[] launchSideMul = { -1f, 0f, 1f };
        float launchOriginXTiles = launchSideMul[patternIdx] * canopyHalfWidth * 0.7f;

        // The trunk hitbox spans roughly -0.25..1.25 tiles from worldX (1.5-tile-wide, centered).
        // Left/right land clearly beside it with margin; middle lands just past its bottom edge
        // (still centered) rather than on the trunk art itself.
        float[] landOffsetX = { -1.1f, 0f, 1.6f };
        float[] landOffsetY = { 0.15f, 0.4f, 0.15f };

        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] == null) {
                object.OBJ_Fruit fruit = new object.OBJ_Fruit(gp, canopyFallHeight, launchOriginXTiles);
                fruit.worldX = worldX + gp.tileSize / 2 - fruit.solidArea.width / 2 - fruit.solidArea.x
                        + Math.round(landOffsetX[patternIdx] * gp.tileSize);
                fruit.worldY = worldY + gp.tileSize + Math.round(landOffsetY[patternIdx] * gp.tileSize)
                        - fruit.solidArea.height - fruit.solidArea.y;
                gp.obj[i] = fruit;
                break;
            }
        }
    }
}

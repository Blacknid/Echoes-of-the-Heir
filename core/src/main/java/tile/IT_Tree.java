package tile;

import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;
import util.UtilityTool;

// Tiled Editor : new String variant = "nume copac".

public class IT_Tree extends interactiveTile {

    // Toti parametrii unui copac. bandCounts has one entry per canopyPaths entry — each branch
    // layer can be sliced into its own number of sway bands. sizeInTiles is how many tiles
    // tall/wide this variant's trunk+canopy is drawn at (bigger tree = bigger sizeInTiles).
    private record Variant(String trunkPath, String[] canopyPaths, int[] bandCounts, int sizeInTiles,
                            float swayPixels, float hitboxWidth, float hitboxHeight,
                            float shadowWidth, float shadowHeight, float shadowOffsetX, float shadowOffsetY) {
        // Single-canopy convenience: one bandCount for the one layer, default size of 4 tiles.
        Variant(String trunkPath, String canopyPath, int bandCount, float swayPixels,
                float hitboxWidth, float hitboxHeight,
                float shadowWidth, float shadowHeight, float shadowOffsetX, float shadowOffsetY) {
            this(trunkPath, new String[] { canopyPath }, new int[] { bandCount }, 4, swayPixels,
                    hitboxWidth, hitboxHeight, shadowWidth, shadowHeight, shadowOffsetX, shadowOffsetY);
        }
    }

    private static final String DEFAULT_VARIANT = "default";

    private static final Map<String, Variant> VARIANTS = new HashMap<>();
    static {
        VARIANTS.put(DEFAULT_VARIANT, new Variant(
                "/res/Interactive/Trees/Simple_trunk.png",
                "/res/Interactive/Trees/Simple_canopy.png",
                3, 2f, 1.5f, 1f, 
                3f, 1f, -0.1f, -0.5f));
        VARIANTS.put("Fruit", new Variant(
                "/res/Interactive/Trees/Bigger_trunk.png",
                "/res/Interactive/Trees/Bigger_canopy.png",
                3, 1f, 1.5f, 1f, 
                3f, 1f, 0f, -0.25f));
        VARIANTS.put("Yellow", new Variant(
                 "/res/Interactive/Trees/Yellow_trunk.png",
                 new String[] {
                         "/res/Interactive/Trees/Yellow_top_canopy.png",
                         "/res/Interactive/Trees/Yellow_middle_canopy.png",
                         "/res/Interactive/Trees/Yellow_bottom_canopy.png",
                 },
                 new int[] { 1, 1, 1 }, // band count per branch: top, middle, bottom
                 5, // sizeInTiles
                1f, 1.5f, 1f,
                3f, 1f, 0.1f, -0.25f));
        
       

                
        // Exemplu copac cu multiple ramuri ( mnai multe animatii separate )
        // VARIANTS.put("bushy", new Variant(
        //         "/res/tiles/CANVAS VILLAGE/Field/trunk.png",
        //         new String[] {
        //                 "/res/tiles/CANVAS VILLAGE/Field/tree.png",
        //                 "/res/tiles/CANVAS VILLAGE/Field/tree_branches.png"
        //         },
        //         3, 1.5f, 2f, 1.25f, 1.8f, 0.35f, 0f, 0.5f));
    }

    /** One canopy image, sliced into sway bands. */
    private static final class CanopyLayer {
        final Sprite[] bands;
        final int[] bandY; // cumulative top-Y of each band (derived from real scaled heights)

        CanopyLayer(String path, int bandCount, int drawSize) {
            Sprite raw = ResourceCache.loadImageIfPresent(path);
            if (raw == null) {
                bands = null;
                bandY = null;
                return;
            }
            // Slice into bands FIRST, in the source image's native pixel coordinates, then scale
            // each band up. getSubimage() reads native pixel rects — slicing an already
            // logical-size-scaled Sprite would read out-of-bounds/garbled regions, since
            // scaleImage() only changes the reported logical draw size, never the texture.
            int nativeW = raw.getWidth();
            int nativeH = raw.getHeight();
            bands = new Sprite[bandCount];
            bandY = new int[bandCount];
            int cumulativeY = 0;
            for (int b = 0; b < bandCount; b++) {
                int y0 = b * nativeH / bandCount;
                int y1 = (b == bandCount - 1) ? nativeH : (b + 1) * nativeH / bandCount;
                Sprite nativeBand = raw.getSubimage(0, y0, nativeW, y1 - y0);
                // Last band takes whatever pixels remain up to drawSize, so the stack always
                // sums to exactly drawSize regardless of per-band rounding.
                int scaledBandH = (b == bandCount - 1)
                    ? drawSize - cumulativeY
                    : (y1 - y0) * drawSize / nativeH;
                bands[b] = UtilityTool.scaleImage(nativeBand, drawSize, scaledBandH);
                bandY[b] = cumulativeY;
                cumulativeY += scaledBandH;
            }
        }
    }

    private static final class VariantAssets {
        final Sprite trunkImg;
        final CanopyLayer[] canopyLayers;

        VariantAssets(Variant v, int drawSize) {
            Sprite trunkRaw = ResourceCache.loadImageIfPresent(v.trunkPath());
            trunkImg = (trunkRaw != null) ? UtilityTool.scaleImage(trunkRaw, drawSize, drawSize) : null;

            canopyLayers = new CanopyLayer[v.canopyPaths().length];
            for (int i = 0; i < canopyLayers.length; i++) {
                canopyLayers[i] = new CanopyLayer(v.canopyPaths()[i], v.bandCounts()[i], drawSize);
            }
        }
    }

    private static final Map<String, VariantAssets> ASSET_CACHE = new HashMap<>();

    private final VariantAssets assets;
    private final Variant variant;

    private float phase;
    private final float swaySpeed;
    private final float swayPixels;
    // +1 or -1: which way the "odd" bands (parity 1) sway relative to the "even" bands (parity 0).
    // Randomized per-tree (but fixed for that tree's lifetime) so neighboring trees don't all
    // sway in visual lockstep, matching the more organic Moonshire/Chucklefish canopy look.
    private final float swayParityFlip;

    public IT_Tree(GamePanel gp, int col, int row) {
        this(gp, col, row, DEFAULT_VARIANT);
    }

    public IT_Tree(GamePanel gp, int col, int row, String variantId) {
        super(gp, col, row);
        collision = true; // Coliziune

        String id = (variantId == null || variantId.isBlank() || !VARIANTS.containsKey(variantId))
                ? DEFAULT_VARIANT : variantId;
        variant = VARIANTS.get(id);
        int drawSize = gp.tileSize * variant.sizeInTiles();
        assets = ASSET_CACHE.computeIfAbsent(id, key -> new VariantAssets(variant, drawSize));

        int hitboxW = Math.round(gp.tileSize * variant.hitboxWidth());
        int hitboxH = Math.round(gp.tileSize * variant.hitboxHeight());
        solidArea.width = hitboxW;
        solidArea.height = hitboxH;
        solidArea.x = (gp.tileSize - hitboxW) / 2;
        solidArea.y = gp.tileSize - hitboxH;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        // One cheap deterministic per-tile hash drives all the "make each tree feel distinct"
        // randomness below — same tile position always gives the same tree, but neighboring
        // trees land on different phase/speed/pixels/direction so they don't animate in lockstep.
        int hash = (col * 928371 + row * 68273 + 12345);
        hash ^= (hash >>> 15);

        phase = (hash & 0xFF) * 0.0245f;
        swaySpeed = 0.1f + ((hash >>> 8) % 21 - 10) * 0.0015f; // 0.1 +/- 0.015
        swayPixels = variant.swayPixels() + ((hash >>> 16) % 21 - 10) * 0.02f; // +/- 0.2px
        swayParityFlip = (hash & 1) == 0 ? 1f : -1f;
    }

    @Override
    public void update() {
        super.update();
        phase += swaySpeed;
    }

    private int anchorX(int drawSize) { return worldX - (drawSize - gp.tileSize) / 2; }
    private int anchorY(int drawSize) { return worldY - (drawSize - gp.tileSize); }
    private int screenX(int drawSize) { return anchorX(drawSize) - gp.player.worldX + gp.player.screenX; }
    private int screenY(int drawSize) { return anchorY(drawSize) - gp.player.worldY + gp.player.screenY; }

    private boolean offscreen(int drawSize) {
        int ax = anchorX(drawSize), ay = anchorY(drawSize);
        return ax + drawSize <= gp.player.worldX - gp.player.screenX ||
               ax >= gp.player.worldX + (gp.screenWidth - gp.player.screenX) ||
               ay + drawSize <= gp.player.worldY - gp.player.screenY ||
               ay >= gp.player.worldY + (gp.screenHeight - gp.player.screenY);
    }

    /** Trees always cast a dynamic canopy silhouette shadow (Stage 2 lighting). */
    @Override
    public boolean castsShadow() { return true; }

    /**
     * Draw the tree's silhouette (trunk + canopy bands) in solid black into the shadow-occluder mask,
     * reusing the exact same layout/sway as {@link #draw} so the cast shadow matches the swaying canopy.
     * IT_Tree overrides draw() with a custom band renderer and stores no walkFrames, so the generic
     * Entity.drawOccluder can't handle it — this override does.
     */
    @Override
    public void drawOccluder(GdxRenderer g2) {
        int drawSize = gp.tileSize * variant.sizeInTiles();
        if (offscreen(drawSize)) return;
        int screenX = screenX(drawSize);
        int screenY = screenY(drawSize);
        if (assets.trunkImg != null) {
            g2.drawImageTinted(assets.trunkImg, screenX, screenY, drawSize, drawSize, gfx.Color.BLACK, 1f);
        }
        for (int i = 0; i < assets.canopyLayers.length; i++) {
            drawCanopyLayerOccluder(g2, assets.canopyLayers[i], i, screenX, screenY, drawSize);
        }
    }

    /** Occluder-mask version of drawCanopyLayer: same sway math, drawn as solid black silhouette. */
    private void drawCanopyLayerOccluder(GdxRenderer g2, CanopyLayer layer, int layerIndex,
                                         int screenX, int screenY, int drawSize) {
        if (layer.bands == null) return;
        int bandCount = layer.bands.length;
        float branchPhase = phase + layerIndex * 1.7f;
        float branchSpeedMul = 1f + (layerIndex % 3) * 0.15f;
        float branchDirFlip = (layerIndex % 2 == 0) ? 1f : -1f;
        for (int b = bandCount - 1; b >= 0; b--) {
            if (layer.bands[b] == null) continue;
            float swayDir = ((b % 2 == 0) ? 1f : -1f) * swayParityFlip * branchDirFlip;
            float offset = (float) Math.sin(branchPhase * branchSpeedMul) * swayPixels * swayDir;
            int bandY = layer.bandY[b];
            int bandH = layer.bands[b].getHeight();
            int growTop = (b == 0) ? 0 : 1;
            g2.drawImageTinted(layer.bands[b], screenX + Math.round(offset), screenY + bandY - growTop,
                    drawSize, bandH + growTop, gfx.Color.BLACK, 1f);
        }
    }

    /**
     * Ground shadow is drawn in its own pre-entity pass (see Entity.drawGroundShadowPass /
     * RenderPipeline.drawGroundShadows) instead of inline here, so it never gets depth-sorted
     * alongside the tree's own trunk/canopy — otherwise a player or NPC standing "in front of" the
     * tree in depth order would have the tree's shadow painted on top of their sprite.
     */
    @Override
    public void drawGroundShadowPass(GdxRenderer g2) {
        int drawSize = gp.tileSize * variant.sizeInTiles();
        if (offscreen(drawSize)) return;
        drawGroundShadow(g2, screenX(drawSize), screenY(drawSize), drawSize,
                variant.shadowWidth(), variant.shadowHeight(), variant.shadowOffsetX(), variant.shadowOffsetY());
    }

    @Override
    public void draw(GdxRenderer g2) {
        int drawSize = gp.tileSize * variant.sizeInTiles();
        if (offscreen(drawSize)) return;

        int screenX = screenX(drawSize);
        int screenY = screenY(drawSize);

        if (assets.trunkImg != null) {
            g2.drawImage(assets.trunkImg, screenX, screenY, drawSize, drawSize);
        }

        // Draw each canopy layer in order (later entries in VARIANTS' canopyPaths draw on top,
        // e.g. an extra branch layer over the base canopy). Each layer sways with its own
        // phase/speed/direction (computed in drawCanopyLayer) so branches don't move in lockstep.
        for (int i = 0; i < assets.canopyLayers.length; i++) {
            drawCanopyLayer(g2, assets.canopyLayers[i], i, screenX, screenY, drawSize);
        }
    }

    private void drawCanopyLayer(GdxRenderer g2, CanopyLayer layer, int layerIndex, int screenX, int screenY, int drawSize) {
        if (layer.bands == null) return;
        int bandCount = layer.bands.length; // this layer's own band count
        // Layer 0 (base canopy) uses the tree's own phase unchanged. Every extra branch layer
        // gets a cheap deterministic offset to its phase/speed/direction, derived from its index,
        // so branch 1, branch 2, etc. all sway independently instead of in lockstep.
        float branchPhase = phase + layerIndex * 1.7f;
        float branchSpeedMul = 1f + (layerIndex % 3) * 0.15f; // 1.0, 1.15, 1.3, 1.0, ...
        float branchDirFlip = (layerIndex % 2 == 0) ? 1f : -1f;
        // Draw bottom-to-top so each band overlaps 1px on top of the band below it — matches
        // natural stacking order and means the overlap pixel always belongs to whichever band
        // is "in front".
        for (int b = bandCount - 1; b >= 0; b--) {
            if (layer.bands[b] == null) continue;
            // Even bands (0, 2, 4, ...) sway one way, odd bands (1, 3, ...) sway the opposite
            // way — a simple parity flip that works for any bandCount, giving the "1st/3rd
            // together, 2nd opposite" look. swayParityFlip randomizes (per-tree, but fixed) which
            // physical direction counts as "even", so trees don't all sway in lockstep.
            float swayDir = ((b % 2 == 0) ? 1f : -1f) * swayParityFlip * branchDirFlip;
            float offset = (float) Math.sin(branchPhase * branchSpeedMul) * swayPixels * swayDir;
            int bandY = layer.bandY[b];
            int bandH = layer.bands[b].getHeight();
            // Adjacent bands sway in different directions, so their rounded X offsets can differ
            // and leave a 1px seam. Fix: extend this band's draw rect 1px upward (reusing its own
            // top edge pixels via scaling) so it overlaps the band above by 1px, hiding the seam
            // with no net height change — the bottom band's bottom edge is untouched.
            int growTop = (b == 0) ? 0 : 1;
            g2.drawImage(layer.bands[b], screenX + Math.round(offset), screenY + bandY - growTop,
                    drawSize, bandH + growTop);
        }
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return false; // not choppable/destructible for now
    }
}

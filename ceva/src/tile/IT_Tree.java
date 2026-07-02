package tile;

import gfx.Sprite;
import gfx.GdxRenderer;

import entity.Entity;
import main.GamePanel;
import util.ResourceCache;
import util.UtilityTool;

/**
 * Decorative tree: a static trunk with a canopy that idly sways
 * (Moonshire/Challacade-style breathing). The canopy is sliced into thin
 * horizontal bands, each offset by a sine sampled at its vertical position —
 * a continuous top-to-bottom wave rather than 2-3 rigid slabs, which avoids
 * visible seams on a round, non-rectangular canopy silhouette.
 */
public class IT_Tree extends interactiveTile {

    // Shared across all instances — loaded/sliced once
    private static Sprite trunkImg = null;
    private static Sprite[] canopyBands = null;
    // Cumulative top-Y of each band, derived from each band's actual scaled height (not
    // recomputed independently) so bands draw edge-to-edge with no rounding gap/overlap seam.
    private static int[] canopyBandY = null;

    private static final int BAND_COUNT = 3;
    private static final float SWAY_PIXELS = 1f; // max horizontal offset, in draw pixels

    // Draw size in tiles — the tree occupies a 2x2 tile footprint on screen so it reads as
    // a full-sized tree rather than a single-tile bush. The interaction/collision anchor
    // (worldX/worldY, inherited from interactiveTile) stays a single tile at the trunk base.
    private static final int SIZE_IN_TILES = 2;

    // Per-instance sway state
    private float phase;
    private final float swaySpeed;

    public IT_Tree(GamePanel gp, int col, int row) {
        super(gp, col, row);
        collision = true; // trunk blocks movement like a normal tree

        int drawSize = gp.tileSize * SIZE_IN_TILES;

        if (trunkImg == null) {
            Sprite trunkRaw = ResourceCache.loadImageIfPresent("/res/tiles/CANVAS VILLAGE/Field/trunk.png");
            Sprite canopyRaw = ResourceCache.loadImageIfPresent("/res/tiles/CANVAS VILLAGE/Field/tree.png");
            if (trunkRaw != null) trunkImg = UtilityTool.scaleImage(trunkRaw, drawSize, drawSize);
            if (canopyRaw != null) {
                // Slice into bands FIRST, in the source image's native pixel coordinates, then scale
                // each band up. getSubimage() reads native pixel rects — slicing an already
                // logical-size-scaled Sprite (whose native rect is still the original size) would
                // read out-of-bounds/garbled regions, since scaleImage() only changes the reported
                // logical draw size and never rasterizes a bigger texture.
                int nativeW = canopyRaw.getWidth();
                int nativeH = canopyRaw.getHeight();
                canopyBands = new Sprite[BAND_COUNT];
                canopyBandY = new int[BAND_COUNT];
                int cumulativeY = 0;
                for (int b = 0; b < BAND_COUNT; b++) {
                    int y0 = b * nativeH / BAND_COUNT;
                    int y1 = (b == BAND_COUNT - 1) ? nativeH : (b + 1) * nativeH / BAND_COUNT;
                    Sprite nativeBand = canopyRaw.getSubimage(0, y0, nativeW, y1 - y0);
                    // Last band takes whatever pixels remain up to drawSize, so the stack always
                    // sums to exactly drawSize regardless of per-band rounding.
                    int scaledBandH = (b == BAND_COUNT - 1)
                        ? drawSize - cumulativeY
                        : (y1 - y0) * drawSize / nativeH;
                    canopyBands[b] = UtilityTool.scaleImage(nativeBand, drawSize, scaledBandH);
                    canopyBandY[b] = cumulativeY;
                    cumulativeY += scaledBandH;
                }
            }
        }

        // Randomize phase/speed per tile so neighboring trees don't sway in lockstep
        int seed = (col * 7 + row * 13) & 0xFF;
        phase = seed * 0.0245f;
        swaySpeed = 0.5f;
        //0.02f + (seed % 13) * 0.001f;
    }

    @Override
    public void update() {
        super.update();
        phase += swaySpeed;
    }

    @Override
    public void draw(GdxRenderer g2) {
        int drawSize = gp.tileSize * SIZE_IN_TILES;

        // Anchor the sprite's bottom-center on the tree's single-tile position (trunk base),
        // so the enlarged canopy grows up and outward from its footprint instead of the extra
        // size being pushed down into the ground/collision tile below.
        int anchorX = worldX - (drawSize - gp.tileSize) / 2;
        int anchorY = worldY - (drawSize - gp.tileSize);

        int screenX = anchorX - gp.player.worldX + gp.player.screenX;
        int screenY = anchorY - gp.player.worldY + gp.player.screenY;

        if (anchorX + drawSize <= gp.player.worldX - gp.player.screenX ||
            anchorX - drawSize >= gp.player.worldX + gp.player.screenX ||
            anchorY + drawSize <= gp.player.worldY - gp.player.screenY ||
            anchorY - drawSize >= gp.player.worldY + gp.player.screenY) return;

        // Trunk stays perfectly still.
        if (trunkImg != null) {
            g2.drawImage(trunkImg, screenX, screenY, drawSize, drawSize);
        }

        if (canopyBands != null) {
            for (int b = 0; b < BAND_COUNT; b++) {
                if (canopyBands[b] == null) continue;
                // Sample point at this band's vertical center, mapped to a full sine period
                // across the canopy height — gives a continuous wave, so band N+1 always
                // starts almost where band N left off (no visible step at the seam).
                float t = (b + 0.5f) / BAND_COUNT;
                float offset = (float) Math.sin(phase + t * Math.PI * 2) * SWAY_PIXELS;
                int bandY = canopyBandY[b];
                int bandH = canopyBands[b].getHeight();
                g2.drawImage(canopyBands[b], screenX + Math.round(offset), screenY + bandY,
                        drawSize, bandH);
            }
        }
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return false; // not choppable/destructible for now
    }
}

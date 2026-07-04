package tile;

import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import entity.Particle;
import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/**
 * A single destructible grass sprite, rendered at its own native resolution/aspect ratio (not
 * squeezed to fill a tile) and then scaled by the game's global content scale (gp.scale — the same
 * multiplier every other tile uses, so a 32x32 grass PNG reads at the same visual scale as the rest
 * of the world). Any weapon breaks it into a small burst of grass-colored debris particles cut from
 * its own image.
 *
 * To add a new grass look: add one line to VARIANTS with a path to the new .png, then set the
 * Tiled object's "variant" property to that key (Tiled type = "IT_GrassPatch").
 */
public class IT_GrassPatch extends interactiveTile {

    private static final String DEFAULT_VARIANT = "default";

    // One entry per grass look: just an image path. Add more here to add more grass types.
    private static final Map<String, String> VARIANTS = new HashMap<>();
    static {
        VARIANTS.put(DEFAULT_VARIANT, "/res/Interactive/grass.png");
    }

    // Loaded once per variant path and shared by every instance using it.
    private static final Map<String, Sprite> IMAGE_CACHE = new HashMap<>();

    private final Sprite image;
    // Drawn at the sprite's own native resolution, then scaled by the same global content scale as
    // every other tile in the game (gp.scale — e.g. 2x for 32px art rendered at 64px tiles), so a
    // grass sprite reads at the same visual scale as the rest of the world instead of half-size.
    private final int drawWidth;
    private final int drawHeight;

    public IT_GrassPatch(GamePanel gp, int col, int row) {
        this(gp, col, row, DEFAULT_VARIANT);
    }

    public IT_GrassPatch(GamePanel gp, int col, int row, String variantId) {
        super(gp, col, row);
        destructible = true;
        collision = false; // grass doesn't block movement

        String path = VARIANTS.getOrDefault(variantId, VARIANTS.get(DEFAULT_VARIANT));
        image = IMAGE_CACHE.computeIfAbsent(path, ResourceCache::loadImageIfPresent);

        if (image != null) {
            drawWidth = (int) Math.round(image.getWidth() * gp.scale);
            drawHeight = (int) Math.round(image.getHeight() * gp.scale);
        } else {
            drawWidth = 0;
            drawHeight = 0;
        }

        // Hitbox matches the sprite's own (scaled) footprint instead of the default 3/4-tile
        // square, since grass sprites are often smaller/taller than a tile.
        if (image != null) {
            solidArea.width = drawWidth;
            solidArea.height = drawHeight;
            solidArea.x = 0;
            solidArea.y = 0;
            solidAreaDefaultX = 0;
            solidAreaDefaultY = 0;
        }
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return entity.currentWeapon != null; // any weapon cuts it down
    }

    @Override
    public void draw(GdxRenderer g2) {
        if (image == null) return;
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + drawWidth  <= gp.player.worldX - gp.player.screenX ||
            worldX - drawWidth  >= gp.player.worldX + (gp.screenWidth  - gp.player.screenX) ||
            worldY + drawHeight <= gp.player.worldY - gp.player.screenY ||
            worldY - drawHeight >= gp.player.worldY + (gp.screenHeight - gp.player.screenY)) return;

        // Drawn at the image's own native resolution, scaled by the game's global content scale
        // (same as every other tile) — not the raw PNG pixel size.
        g2.drawImage(image, screenX, screenY, drawWidth, drawHeight);
    }

    /** Small burst of the grass's own sprite, cut into a few tumbling/fading debris particles. */
    public void spawnDestroyBurst() {
        if (image == null || gp.particlePool == null) return;
        int count = 4;
        int debrisSize = Math.max(6, Math.min(drawWidth, drawHeight) / 2);
        int cx = getCenterX(), cy = getCenterY();
        for (int i = 0; i < count; i++) {
            Particle p = gp.particlePool.get();
            float angle = (float) (Math.random() * Math.PI * 2);
            float burstSpeed = 0.6f + (float) Math.random() * 0.8f;
            int lifeTicks = 16 + (int) (Math.random() * 8);
            p.setAsImageDebris(this, image, cx, cy, debrisSize, angle, burstSpeed, lifeTicks);
            p.depthSortYOffset = -gp.tileSize * 2;
            gp.particleList.add(p);
        }
    }

    @Override public Color getParticleColor()  { return new Color(90, 150, 70); }
}

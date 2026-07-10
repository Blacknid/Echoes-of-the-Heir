package tile;

import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import entity.Particle;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/**
 * Generic destructible prop (crate, barrel, ...) that pops into a burst of debris particles cut
 * from its own sprite when hit by a weapon. One class handles every look: each "kind" is just a
 * sprite path plus an optional debris-particle image, registered in {@link #KINDS} below.
 *
 * To add a new breakable:
 *   1) Drop the sprite PNG under assets/res/Interactive/Breakable/.
 *   2) Add one line to {@link #KINDS} with a new kind id (optionally tweak its size via extraScale).
 *   3) Place it in Tiled with type "Breakable" and a "kind" property matching that id — position is
 *      read straight from the object's raw (x, y) in Tiled, not snapped to the tile grid, so it can
 *      go anywhere. In code, use {@link #atPixel} the same way.
 * No new Java class needed unless the object needs special behavior (e.g. loot drops) beyond
 * "breaks into particles of its own image" — for that, extend Breakable (see class doc below).
 */
public class Breakable extends interactiveTile {

    /**
     * One entry per look: sprite path, an optional distinct particle image (falls back to the
     * sprite itself), and an extra scale multiplier applied on top of gp.scale — 1.0 means "draw at
     * the same visual scale as every other tile", use higher/lower to make a specific kind read
     * bigger/smaller (e.g. a squat barrel vs a tall crate) without touching the PNG.
     */
    public record Kind(String spritePath, String particlePath, float extraScale) {
        public Kind(String spritePath) { this(spritePath, null, 1f); }
        public Kind(String spritePath, float extraScale) { this(spritePath, null, extraScale); }
    }

    public static final Map<String, Kind> KINDS = new HashMap<>();
    static {

        // CRATES

        KINDS.put("big_crate_dark",  new Kind("/res/Interactive/Breakable/dark_crate.png", null, 2f));
        KINDS.put("big_crate_light", new Kind("/res/Interactive/Breakable/light_crate.png", null, 2f));
        KINDS.put("small_crate_light", new Kind("/res/Interactive/Breakable/light_crate.png", null, 1f));
        KINDS.put("small_crate_dark", new Kind("/res/Interactive/Breakable/dark_crate.png", null, 1f));

        // VASES

        KINDS.put("big_blue_vase", new Kind("/res/Interactive/Breakable/blue_vase.png", null, 2f));
        KINDS.put("big_brown_vase", new Kind("/res/Interactive/Breakable/brown_vase.png", null, 2f));
        // Add barrel (or any new look) here once its PNG is in assets/res/Interactive/Breakable/,
        // e.g.: KINDS.put("barrel_dark", new Kind("/res/Interactive/Breakable/dark_barrel.png", 0.9f));
    }

    private static final String DEFAULT_KIND = "crate_dark";
    private static final Map<String, Sprite> IMAGE_CACHE = new HashMap<>();

    private static final float HITBOX_WIDTH_FRACTION = 0.8f;
    private static final float HITBOX_HEIGHT_FRACTION = 0.7f;
    private static final int DEBRIS_COUNT = 6;

    private Sprite sprite;
    private Sprite particleImage;
    private int drawWidth;
    private int drawHeight;

    public Breakable(GamePanel gp, int col, int row) {
        this(gp, col, row, DEFAULT_KIND);
    }

    public Breakable(GamePanel gp, int col, int row, String kindId) {
        super(gp, col, row);
        init(gp, kindId);
    }

    /**
     * Places the breakable at an exact pixel position instead of snapping to the tile grid — use
     * this when a crate/barrel needs to sit at an arbitrary (x, y) rather than tile col/row.
     */
    public static Breakable atPixel(GamePanel gp, int worldX, int worldY, String kindId) {
        Breakable b = new Breakable(gp, 0, 0, kindId);
        b.worldX = worldX;
        b.worldY = worldY;
        return b;
    }

    private void init(GamePanel gp, String kindId) {
        destructible = true;
        collision = true; // crates/barrels block movement until broken

        Kind kind = KINDS.getOrDefault(kindId, KINDS.get(DEFAULT_KIND));
        sprite = loadCached(kind.spritePath());
        particleImage = kind.particlePath() != null ? loadCached(kind.particlePath()) : sprite;

        if (sprite != null) {
            double totalScale = gp.scale * kind.extraScale();
            drawWidth = (int) Math.round(sprite.getWidth() * totalScale);
            drawHeight = (int) Math.round(sprite.getHeight() * totalScale);

            // Footprint hitbox anchored at the sprite's visual base, matching IT_GrassPatch/IT_Tree.
            int footW = Math.max(1, Math.round(drawWidth * HITBOX_WIDTH_FRACTION));
            int footH = Math.max(1, Math.round(drawHeight * HITBOX_HEIGHT_FRACTION));
            solidArea.width = footW;
            solidArea.height = footH;
            solidArea.x = (drawWidth - footW) / 2;
            solidArea.y = drawHeight - footH;
            solidAreaDefaultX = solidArea.x;
            solidAreaDefaultY = solidArea.y;
        } else {
            drawWidth = gp.tileSize;
            drawHeight = gp.tileSize;
        }
    }

    private static Sprite loadCached(String path) {
        return IMAGE_CACHE.computeIfAbsent(path, ResourceCache::loadImageIfPresent);
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return entity.currentWeapon != null; // any weapon breaks it
    }

    @Override
    public void draw(GdxRenderer g2) {
        if (sprite == null) return;
        int screenX = worldX - gp.getCamWorldX() + gp.player.screenX;
        int screenY = worldY - gp.getCamWorldY() + gp.player.screenY;

        if (worldX + drawWidth  <= gp.getCamWorldX() - gp.player.screenX ||
            worldX - drawWidth  >= gp.getCamWorldX() + (gp.screenWidth  - gp.player.screenX) ||
            worldY + drawHeight <= gp.getCamWorldY() - gp.player.screenY ||
            worldY - drawHeight >= gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY)) return;

        g2.drawImage(sprite, screenX, screenY, drawWidth, drawHeight);
    }

    /**
     * Bursts this breakable's own particle image into a handful of tumbling, fading debris
     * chunks. Subclasses that want different debris behavior (count, spread, extra effects like a
     * loot drop) should override this and call {@code super.spawnDestroyBurst()} or replace it
     * entirely — see {@code Player.damageInteractiveTile} for the call site.
     */
    public void spawnDestroyBurst() {
        if (particleImage == null || gp.particlePool == null) return;
        int debrisSize = Math.max(6, Math.min(drawWidth, drawHeight) / 3);
        int cx = getCenterX(), cy = getCenterY();
        for (int i = 0; i < DEBRIS_COUNT; i++) {
            Particle p = gp.particlePool.get();
            float angle = (float) (Math.random() * Math.PI * 2);
            float burstSpeed = 0.7f + (float) Math.random() * 1.0f;
            int lifeTicks = 16 + (int) (Math.random() * 10);
            p.setAsImageDebris(this, particleImage, cx, cy, debrisSize, angle, burstSpeed, lifeTicks);
            p.depthSortYOffset = -gp.tileSize * 2;
            gp.particleList.add(p);
        }
    }
}

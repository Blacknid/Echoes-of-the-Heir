package object;

import entity.Entity;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/**
 * A fruit knocked loose from a Fruit-variant IT_Prop tree. Falls from canopy height down to the
 * ground with a small settle-bounce on landing (spawn-time animation, purely time-driven like
 * IT_Prop's canopy sway -- gp.obj[] entities are never ticked per-frame, see GamePanel's main
 * loop), then idles with a gentle vertical pulse until picked up like any other floor item.
 */
public class OBJ_Fruit extends Entity {

    private static final float FALL_DURATION_SECONDS = 0.4f;
    private static final float BOUNCE_DURATION_SECONDS = 0.15f;
    private static final float BOUNCE_HEIGHT_TILES = 0.18f; // small settle-bounce once it reaches the ground
    private static final float PULSE_HEIGHT_TILES = 0.08f;
    private static final float PULSE_PERIOD_SECONDS = 1.1f;
    private static final float DRAW_SCALE = 0.5f; // Fruits.png is a small icon, not a full tile-sized sprite
    private static final float ICON_SCALE = 0.6f; // inventory icon: shrunk further, centered in the slot

    private final long spawnNanos = System.nanoTime();
    private final float launchOriginXTiles; // horizontal offset (in tiles) from the landing spot where the fall visually starts
    private final int worldDrawSize;
    private final float fallStartOffsetTiles; // how many tiles above the landing spot the fall starts (canopy height)

    public OBJ_Fruit(GamePanel gp) {
        this(gp, 2.5f, 0f);
    }

    /** fallStartOffsetTiles: how far above the landing spot (in tiles) the fruit starts its fall
 * from, pass the tree's canopy height so the drop visually originates from the canopy, not
     *  the trunk/base where the fruit actually lands.
 * launchOriginXTiles: how far horizontally (in tiles) from the landing spot the fall starts
     *  pass a random offset within the canopy's width so fruit visibly falls from different spots
     *  across the canopy instead of always the same point straight above the trunk. */
    public OBJ_Fruit(GamePanel gp, float fallStartOffsetTiles, float launchOriginXTiles) {
        super(gp);
        this.fallStartOffsetTiles = fallStartOffsetTiles;
        this.launchOriginXTiles = launchOriginXTiles;

        name = "Fruit";
        stackable = true;
        type = TYPE_BUFFS;
        description = "A ripe fruit, still warm from the sun.";
        // down1 must stay a full tileSize x tileSize sprite overall, since UI.drawInventory() draws
        // item icons at native resolution with no per-item rescaling, a smaller down1 would just
        // render tiny and stuck in the slot's top-left corner instead of centered. To get a smaller
        // *visible* icon that's still centered in the slot, composite the source art scaled down onto
        // a transparent tileSize x tileSize cell (see Sprite.croppedCentered) rather than shrinking
        // the sprite itself.
        Sprite source = ResourceCache.loadImageIfPresent("/res/Interactive/Fruits.png");
        if (source != null) {
            int iconSize = Math.round(gp.tileSize * ICON_SCALE);
            down1 = source.croppedCentered(0, 0, source.nativeWidth(), source.nativeHeight(),
                    iconSize, iconSize, gp.tileSize, gp.tileSize);
        }
        worldDrawSize = Math.round(gp.tileSize * DRAW_SCALE);

        // Small hitbox, centered under the (smaller) sprite
        solidArea.x      = gp.tileSize / 2 - gp.tileSize * 12 / 64;
        solidArea.y      = gp.tileSize / 2 - gp.tileSize * 12 / 64;
        solidArea.width  = gp.tileSize * 24 / 64;
        solidArea.height = gp.tileSize * 24 / 64;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    private float elapsedSeconds() {
        return (System.nanoTime() - spawnNanos) * 1e-9f;
    }

    @Override
    public void draw(GdxRenderer g2) {
        if (down1 == null) return;

        float t = elapsedSeconds();
        int offsetX = 0;
        int offsetY;

        if (t < FALL_DURATION_SECONDS) {
            // Falls from canopy height down to the ground -- ease-in, slow to start and
            // accelerating down, like gravity actually pulling it off the branch. Horizontally it
            // eases from launchOriginXTiles (its random spot across the canopy) in to 0 (the landing
            // spot) using the same curve, so it reads as a single diagonal fall from that point.
            float f = t / FALL_DURATION_SECONDS;
            float fallEase = f * f;
            offsetY = -Math.round(fallStartOffsetTiles * (1f - fallEase) * gp.tileSize);
            offsetX = Math.round(launchOriginXTiles * (1f - fallEase) * gp.tileSize);
        } else if (t < FALL_DURATION_SECONDS + BOUNCE_DURATION_SECONDS) {
            // Small settle-bounce right after landing so it doesn't just stop dead on impact.
            float bf = (t - FALL_DURATION_SECONDS) / BOUNCE_DURATION_SECONDS;
            float bounce = (float) Math.sin(bf * Math.PI) * BOUNCE_HEIGHT_TILES;
            offsetY = -Math.round(bounce * gp.tileSize);
        } else {
            // Resting pulse: slow sine bob, a bit up then back down, on repeat.
            float pulsePhase = (t - FALL_DURATION_SECONDS) / PULSE_PERIOD_SECONDS * (float) (Math.PI * 2);
            float pulse = (float) Math.sin(pulsePhase) * 0.5f + 0.5f; // 0..1
            offsetY = -Math.round(pulse * PULSE_HEIGHT_TILES * gp.tileSize);
        }

        // worldX/worldY mark the tile-cell origin; center the smaller world sprite within that cell.
        int centerInset = (gp.tileSize - worldDrawSize) / 2;
        int screenX = worldX - gp.getCamWorldX() + gp.player.screenX + centerInset + offsetX;
        int screenY = worldY - gp.getCamWorldY() + gp.player.screenY + centerInset + offsetY;

        if (worldX + gp.tileSize <= gp.getCamWorldX() - gp.player.screenX ||
            worldX - gp.tileSize >= gp.getCamWorldX() + (gp.screenWidth  - gp.player.screenX) ||
            worldY + gp.tileSize <= gp.getCamWorldY() - gp.player.screenY ||
            worldY - gp.tileSize >= gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY)) return;

        g2.drawImage(down1, screenX, screenY, worldDrawSize, worldDrawSize);
    }
}

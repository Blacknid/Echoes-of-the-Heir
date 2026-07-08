package ui;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import gfx.Color;
import gfx.Font;
import gfx.GdxRenderer;
import gfx.RadialGradient;
import gfx.Sprite;
import gfx.Stroke;
import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import main.GamePanel;
import object.OBJ_Chest;

/**
 * Don't Starve Together-style circular minimap.
 * Paints flat biome colours (no tile images), clips to a circle,
 * applies a radial vignette and draws an ornate ring border.
 */
public class Minimap {

    private final GamePanel gp;

    private static final int   MINIMAP_RADIUS    = 68;
    private static final int   BORDER_WIDTH      = 6;
    private static final int   MARGIN            = 18;
    private static final float ALPHA             = 0.90f;
    private static final float FULL_MAP_BG_ALPHA = 0.82f;

    private static final int GID_GRASS_MIN  = 1;
    private static final int GID_GRASS_MAX  = 12;
    private static final int GID_STRUCT_MIN = 13;
    private static final int GID_STRUCT_MAX = 76;
    private static final int GID_WATER_MIN  = 77;
    private static final int GID_WATER_MAX  = 106;
    private static final int GID_TREE       = 107;
    private static final int GID_SHADOW_MIN = 108;

    // Flower GIDs → plain grass
    private static final int FLOWER_GID_1    = 8;
    private static final int FLOWER_GID_2    = 9;
    private static final int FLOWER_GID_3    = 10;
    private static final int PLAIN_GRASS_GID = 1;

    private static final Color COL_BG      = new Color(12,  10,   7);
    private static final Color COL_GRASS   = new Color(74, 100,  36);
    private static final Color COL_GRASS2  = new Color(58,  80,  26);
    private static final Color COL_WATER   = new Color(18,  42,  68);
    private static final Color COL_WATER2  = new Color(24,  54,  82);
    private static final Color COL_TREE    = new Color(20,  38,  12);
    private static final Color COL_STRUCT  = new Color(85,  68,  45);

    private static final Color PLAYER_COLOR  = new Color(255, 240, 180);
    private static final Color MONSTER_COLOR = new Color(200,  50,  40);
    private static final Color NPC_COLOR     = new Color( 80, 185,  80);
    private static final Color CHEST_COLOR   = new Color(215, 175,  40);
    private static final Color OBJECT_COLOR  = new Color(180, 140,  80);

    // Cached fonts for world map overlay
    private static final Font MAP_LABEL_FONT = new Font("Georgia", Font.BOLD | Font.ITALIC, 15);
    private static final Font MAP_HINT_FONT  = new Font("Georgia", Font.PLAIN, 11);
    private static final Color VIEWPORT_COLOR= new Color(255, 255, 255, 80);

    private static final Stroke BORDER_OUTER_STROKE = new Stroke(BORDER_WIDTH + 3 + 4); // large mode
    private static final Stroke BORDER_MID_STROKE   = new Stroke(BORDER_WIDTH + 4);
    private static final Stroke BORDER_INNER_STROKE = new Stroke(1.8f);
    private static final Stroke BORDER_HIGHLIGHT_STROKE = new Stroke(1.0f);
    private static final Stroke BORDER_OUTER_STROKE_SM = new Stroke(BORDER_WIDTH + 3);
    private static final Stroke BORDER_MID_STROKE_SM   = new Stroke(BORDER_WIDTH);
    private static final Color BORDER_OUTER_COLOR    = new Color(6, 5, 3);
    private static final Color BORDER_MID_COLOR      = new Color(55, 42, 22);
    private static final Color BORDER_INNER_COLOR    = new Color(140, 108, 55, 210);
    private static final Color BORDER_HIGHLIGHT_COLOR= new Color(85, 68, 36, 110);

    private final HashMap<Integer, Sprite> vignetteCache = new HashMap<>();

    private static final int BAKE_PIXELS_PER_TILE = 4;

    private Sprite terrainImage;
    private boolean worldMapOpen = false;
    private final Map<String, Sprite> bakedTerrainCache = new HashMap<>();

    public Minimap(GamePanel gp) {
        this.gp = gp;
    }

    /** Bake the world terrain as flat DST-style biome colours. Call after map loads. */
    public void bakeTerrainImage() {
        String cacheKey = getCacheKey();
        Sprite cached = bakedTerrainCache.get(cacheKey);
        if (cached != null) {
            terrainImage = cached;
            return;
        }

        int mapCols = gp.tileM.currentMapCols;
        int mapRows = gp.tileM.currentMapRows;
        int w = mapCols * BAKE_PIXELS_PER_TILE;
        int h = mapRows * BAKE_PIXELS_PER_TILE;

        // GPU port: bake the flat-colour terrain into a Pixmap once (replaces the
        // BufferedImage + Graphics2D per-tile fill), upload as a Texture.
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.setColor(COL_BG.getRed() / 255f, COL_BG.getGreen() / 255f, COL_BG.getBlue() / 255f, 1f);
        pm.fill();

        // Batch the per-GID pixel samples: gidToColor()->sampleTileColor()->Sprite.getRGB() decodes
        // a tileset PNG on each new GID. On big maps (hundreds of unique GIDs) that was ~10s on the
        // first bake. The batch decodes each tileset texture once and disposes them at the end.
        Sprite.beginPixelBatch();
        try {
        for (int l = 0; l < gp.tileM.mapLayers.size(); l++) {
            int[][] layer = gp.tileM.mapLayers.get(l);
            // Clamp to the actual layer array bounds. currentMapCols/Rows come from the TMX header
            // and can exceed the fixed maxWorldCol/Row (100) that layer arrays are allocated at, so
            // iterating by mapCols/mapRows alone overflows on maps bigger than 100 tiles.
            int colLimit = Math.min(mapCols, layer.length);
            for (int row = 0; row < mapRows; row++) {
                for (int col = 0; col < colLimit; col++) {
                    if (row >= layer[col].length) continue;
                    int gid = normalizeBakedGid(layer[col][row]);
                    if (gid == 0) continue;
                    Color c = gidToColor(gid, col, row);
                    if (c == null) continue;
                    pm.setColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
                    pm.fillRectangle(col * BAKE_PIXELS_PER_TILE, row * BAKE_PIXELS_PER_TILE,
                                     BAKE_PIXELS_PER_TILE, BAKE_PIXELS_PER_TILE);
                }
            }
        }
        } finally {
            Sprite.endPixelBatch();
        }

        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        terrainImage = new Sprite(tex);
        bakedTerrainCache.put(cacheKey, terrainImage);
    }

    public void invalidateTerrainCache(String mapId) {
        bakedTerrainCache.remove(mapId);
    }

    /** Map a GID to a minimap colour by sampling the actual tile image center pixel. */
    private Color gidToColor(int gid, int col, int row) {
        // Sample actual tile art center pixel — works across all maps without heuristics
        Color sampled = gp.tileM.sampleTileColor(gid);
        if (sampled != null) return sampled;
        // Transparent or missing tile — draw nothing
        return null;
    }

    public void toggleWorldMap() {
        worldMapOpen = !worldMapOpen;
    }

    public boolean isWorldMapOpen() {
        return worldMapOpen;
    }

    // Full world map overlay

    public void drawWorldMap(GdxRenderer g2) {
        if (!worldMapOpen || terrainImage == null) return;

        g2.setAlpha(FULL_MAP_BG_ALPHA);
        g2.setColor(COL_BG);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setAlpha(1f);

        int mapRadius = Math.min(gp.screenWidth, gp.screenHeight) / 2 - 54;
        int cx = gp.screenWidth  / 2;
        int cy = gp.screenHeight / 2;

        drawCircularMap(g2, cx, cy, mapRadius, true);

        g2.setAlpha(0.72f);
        int labelY = cy + mapRadius + BORDER_WIDTH + 26;
        g2.setColor(new Color(195, 168, 100));
        g2.setFont(MAP_LABEL_FONT);
        String label = "Map";
        int lw = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, cx - lw / 2, labelY);
        g2.setFont(MAP_HINT_FONT);
        g2.setColor(new Color(145, 122, 72, 180));
        String hint = "[ M ] close";
        int hw = g2.getFontMetrics().stringWidth(hint);
        g2.drawString(hint, cx - hw / 2, labelY + 16);

        g2.setAlpha(1f);
    }

    // Shared circular renderer  (used by both HUD and world map)

    private void drawCircularMap(GdxRenderer g2, int cx, int cy, int radius, boolean largeMode) {
        Stroke savedStroke = g2.getStroke();

        // Dark circular base.
        g2.setColor(COL_BG);
        g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // Terrain: drawn as a square. GL scissor can't do a circular clip, so instead the
        // baked radial vignette below (opaque black at the rim) plus the ring border mask the
        // square corners — visually equivalent to the old Ellipse2D clip.
        g2.drawImage(terrainImage, cx - radius, cy - radius, radius * 2, radius * 2);

        float scaleX = (float)(radius * 2) / gp.tileM.currentMapCols;
        float scaleY = (float)(radius * 2) / gp.tileM.currentMapRows;
        drawEntities(g2, cx - radius, cy - radius, scaleX, scaleY, largeMode);

        // Radial vignette baked once per radius (replaces RadialGradientPaint); its opaque rim
        // also hides the terrain square's corners outside the circle.
        Sprite vig = vignetteCache.get(radius);
        if (vig == null) {
            int diam = radius * 2;
            RadialGradient vg = new RadialGradient(radius, radius, radius,
                new float[]{ 0.50f, 0.97f, 1.0f },
                new Color[]{ new Color(0, 0, 0, 0), new Color(0, 0, 0, 215), new Color(0, 0, 0, 255) });
            vig = GdxRenderer.bakeRadialGradient(vg, diam, diam);
            vignetteCache.put(radius, vig);
        }
        g2.drawImage(vig, cx - radius, cy - radius, radius * 2, radius * 2);

        int bw = largeMode ? BORDER_WIDTH + 4 : BORDER_WIDTH;

        g2.setStroke(largeMode ? BORDER_OUTER_STROKE : BORDER_OUTER_STROKE_SM);
        g2.setColor(BORDER_OUTER_COLOR);
        g2.drawOval(cx - radius - bw / 2, cy - radius - bw / 2,
                    radius * 2 + bw, radius * 2 + bw);

        g2.setStroke(largeMode ? BORDER_MID_STROKE : BORDER_MID_STROKE_SM);
        g2.setColor(BORDER_MID_COLOR);
        g2.drawOval(cx - radius - bw / 2, cy - radius - bw / 2,
                    radius * 2 + bw, radius * 2 + bw);

        g2.setStroke(BORDER_INNER_STROKE);
        g2.setColor(BORDER_INNER_COLOR);
        g2.drawOval(cx - radius + 1, cy - radius + 1, radius * 2 - 2, radius * 2 - 2);

        g2.setStroke(BORDER_HIGHLIGHT_STROKE);
        g2.setColor(BORDER_HIGHLIGHT_COLOR);
        g2.drawOval(cx - radius - bw, cy - radius - bw,
                    radius * 2 + bw * 2, radius * 2 + bw * 2);

        g2.setStroke(savedStroke);
    }


    private void drawEntities(GdxRenderer g2, int originX, int originY,
                              float scaleX, float scaleY, boolean largeMode) {
        int monSize    = largeMode ? 5 : 3;
        int npcSize    = largeMode ? 5 : 3;
        int chestSize  = largeMode ? 6 : 4;
        int playerSize = largeMode ? 7 : 4;

        for (Entity mon : gp.monster) {
            if (mon == null || !mon.alive) continue;
            int mx = originX + (int)((mon.worldX / (float)gp.tileSize) * scaleX);
            int my = originY + (int)((mon.worldY / (float)gp.tileSize) * scaleY);
            g2.setColor(MONSTER_COLOR);
            g2.fillOval(mx - monSize / 2, my - monSize / 2, monSize, monSize);
        }

        for (Entity npc : gp.npc) {
            if (npc == null || !npc.alive) continue;
            int nx = originX + (int)((npc.worldX / (float)gp.tileSize) * scaleX);
            int ny = originY + (int)((npc.worldY / (float)gp.tileSize) * scaleY);
            g2.setColor(NPC_COLOR);
            g2.fillOval(nx - npcSize / 2, ny - npcSize / 2, npcSize, npcSize);
        }

        if (gp.obj != null) {
            for (Entity obj : gp.obj) {
                if (obj == null) continue;
                int ox = originX + (int)((obj.worldX / (float)gp.tileSize) * scaleX);
                int oy = originY + (int)((obj.worldY / (float)gp.tileSize) * scaleY);
                if (obj instanceof OBJ_Chest && !obj.opened) {
                    g2.setColor(CHEST_COLOR);
                    g2.fillOval(ox - chestSize / 2, oy - chestSize / 2, chestSize, chestSize);
                } else if (largeMode) {
                    g2.setColor(OBJECT_COLOR);
                    g2.fillOval(ox - 2, oy - 2, 4, 4);
                }
            }
        }

        int px = originX + (int)((gp.player.worldX / (float)gp.tileSize) * scaleX);
        int py = originY + (int)((gp.player.worldY / (float)gp.tileSize) * scaleY);
        g2.setColor(new Color(255, 235, 130, 55));
        g2.fillOval(px - playerSize, py - playerSize, playerSize * 2, playerSize * 2);
        g2.setColor(PLAYER_COLOR);
        g2.fillOval(px - playerSize / 2, py - playerSize / 2, playerSize, playerSize);

        g2.setAlpha(largeMode ? 0.40f : 0.22f);
        g2.setColor(VIEWPORT_COLOR);
        int vpX = originX + (int)(((gp.getCamWorldX() - gp.player.screenX) / (float)gp.tileSize) * scaleX);
        int vpY = originY + (int)(((gp.getCamWorldY() - gp.player.screenY) / (float)gp.tileSize) * scaleY);
        int vpW = Math.max(1, (int)(gp.maxScreenCol * scaleX));
        int vpH = Math.max(1, (int)(gp.maxScreenRow * scaleY));
        g2.drawRect(vpX, vpY, vpW, vpH);
        g2.setAlpha(1f);
    }

    private int normalizeBakedGid(int gid) {
        if (gid == FLOWER_GID_1 || gid == FLOWER_GID_2 || gid == FLOWER_GID_3) {
            return PLAIN_GRASS_GID;
        }
        return gid;
    }

    private String getCacheKey() {
        if (gp.mapManager != null && gp.mapManager.currentMapId != null && !gp.mapManager.currentMapId.isBlank()) {
            return gp.mapManager.currentMapId;
        }
        return "default";
    }
}

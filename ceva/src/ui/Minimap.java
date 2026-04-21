package ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
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

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------
    private static final int   MINIMAP_RADIUS    = 68;
    private static final int   BORDER_WIDTH      = 6;
    private static final int   MARGIN            = 18;
    private static final float ALPHA             = 0.90f;
    private static final float FULL_MAP_BG_ALPHA = 0.82f;

    // -----------------------------------------------------------------------
    // GID ranges — legacy fallback for maps without tileset name heuristics
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // DST biome palette  (dark, earthy, desaturated)
    // -----------------------------------------------------------------------
    private static final Color COL_BG      = new Color(12,  10,   7);
    private static final Color COL_GRASS   = new Color(74, 100,  36);
    private static final Color COL_GRASS2  = new Color(58,  80,  26);
    private static final Color COL_WATER   = new Color(18,  42,  68);
    private static final Color COL_WATER2  = new Color(24,  54,  82);
    private static final Color COL_TREE    = new Color(20,  38,  12);
    private static final Color COL_STRUCT  = new Color(85,  68,  45);

    // Entity dot colours
    private static final Color PLAYER_COLOR  = new Color(255, 240, 180);
    private static final Color MONSTER_COLOR = new Color(200,  50,  40);
    private static final Color NPC_COLOR     = new Color( 80, 185,  80);
    private static final Color CHEST_COLOR   = new Color(215, 175,  40);
    private static final Color OBJECT_COLOR  = new Color(180, 140,  80);

    // Cached fonts for world map overlay
    private static final java.awt.Font MAP_LABEL_FONT = new java.awt.Font("Georgia", java.awt.Font.BOLD | java.awt.Font.ITALIC, 15);
    private static final java.awt.Font MAP_HINT_FONT  = new java.awt.Font("Georgia", java.awt.Font.PLAIN, 11);
    private static final Color VIEWPORT_COLOR= new Color(255, 255, 255, 80);

    // OPTIMIZATION: Pre-allocated border strokes and colors (avoid per-frame allocation)
    private static final BasicStroke BORDER_OUTER_STROKE = new BasicStroke(BORDER_WIDTH + 3 + 4); // large mode
    private static final BasicStroke BORDER_MID_STROKE   = new BasicStroke(BORDER_WIDTH + 4);
    private static final BasicStroke BORDER_INNER_STROKE = new BasicStroke(1.8f);
    private static final BasicStroke BORDER_HIGHLIGHT_STROKE = new BasicStroke(1.0f);
    private static final BasicStroke BORDER_OUTER_STROKE_SM = new BasicStroke(BORDER_WIDTH + 3);
    private static final BasicStroke BORDER_MID_STROKE_SM   = new BasicStroke(BORDER_WIDTH);
    private static final Color BORDER_OUTER_COLOR    = new Color(6, 5, 3);
    private static final Color BORDER_MID_COLOR      = new Color(55, 42, 22);
    private static final Color BORDER_INNER_COLOR    = new Color(140, 108, 55, 210);
    private static final Color BORDER_HIGHLIGHT_COLOR= new Color(85, 68, 36, 110);

    // OPTIMIZATION: Cached vignette overlay images keyed by radius
    private final HashMap<Integer, BufferedImage> vignetteCache = new HashMap<>();

    // -----------------------------------------------------------------------
    // Bake detail
    // -----------------------------------------------------------------------
    private static final int BAKE_PIXELS_PER_TILE = 4;

    private BufferedImage terrainImage;
    private boolean worldMapOpen = false;
    private final Map<String, BufferedImage> bakedTerrainCache = new HashMap<>();

    public Minimap(GamePanel gp) {
        this.gp = gp;
    }

    /** Bake the world terrain as flat DST-style biome colours. Call after map loads. */
    public void bakeTerrainImage() {
        String cacheKey = getCacheKey();
        BufferedImage cached = bakedTerrainCache.get(cacheKey);
        if (cached != null) {
            terrainImage = cached;
            return;
        }

        int mapCols = gp.tileM.currentMapCols;
        int mapRows = gp.tileM.currentMapRows;
        int w = mapCols * BAKE_PIXELS_PER_TILE;
        int h = mapRows * BAKE_PIXELS_PER_TILE;
        terrainImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        Graphics2D mapG = terrainImage.createGraphics();
        mapG.setColor(COL_BG);
        mapG.fillRect(0, 0, w, h);

        for (int l = 0; l < gp.tileM.mapLayers.size(); l++) {
            int[][] layer = gp.tileM.mapLayers.get(l);
            for (int row = 0; row < mapRows; row++) {
                for (int col = 0; col < mapCols; col++) {
                    int gid = normalizeBakedGid(layer[col][row]);
                    if (gid == 0) continue;
                    Color c = gidToColor(gid, col, row);
                    if (c == null) continue;
                    mapG.setColor(c);
                    mapG.fillRect(col * BAKE_PIXELS_PER_TILE, row * BAKE_PIXELS_PER_TILE,
                                  BAKE_PIXELS_PER_TILE, BAKE_PIXELS_PER_TILE);
                }
            }
        }

        mapG.dispose();
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

    // -----------------------------------------------------------------------
    // Corner HUD minimap
    // -----------------------------------------------------------------------

    public void draw(Graphics2D g2) {
        if (terrainImage == null) return;

        int cx = gp.screenWidth - MARGIN - MINIMAP_RADIUS;
        int cy = MARGIN + MINIMAP_RADIUS;

        java.awt.Composite savedComp = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ALPHA));
        drawCircularMap(g2, cx, cy, MINIMAP_RADIUS, false);
        g2.setComposite(savedComp);
    }

    // -----------------------------------------------------------------------
    // Full world map overlay
    // -----------------------------------------------------------------------

    public void drawWorldMap(Graphics2D g2) {
        if (!worldMapOpen || terrainImage == null) return;

        java.awt.Composite savedComp = g2.getComposite();

        // Dark backdrop
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, FULL_MAP_BG_ALPHA));
        g2.setColor(COL_BG);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

        int mapRadius = Math.min(gp.screenWidth, gp.screenHeight) / 2 - 54;
        int cx = gp.screenWidth  / 2;
        int cy = gp.screenHeight / 2;

        drawCircularMap(g2, cx, cy, mapRadius, true);

        // Caption
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f));
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

        g2.setComposite(savedComp);
    }

    // -----------------------------------------------------------------------
    // Shared circular renderer  (used by both HUD and world map)
    // -----------------------------------------------------------------------

    private void drawCircularMap(Graphics2D g2, int cx, int cy, int radius, boolean largeMode) {
        // Save Graphics2D state
        Shape           savedClip   = g2.getClip();
        java.awt.Stroke savedStroke = g2.getStroke();
        Object savedInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Object savedRender = g2.getRenderingHint(RenderingHints.KEY_RENDERING);
        Object savedAA     = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,    RenderingHints.VALUE_RENDER_QUALITY);

        // 1. Solid dark base disc
        g2.setColor(COL_BG);
        g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // 2. Clip to circle, draw scaled terrain
        Ellipse2D circle = new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2);
        g2.setClip(circle);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(terrainImage, cx - radius, cy - radius, radius * 2, radius * 2, null);

        // 3. Entity dots (clipped inside the circle)
        float scaleX = (float)(radius * 2) / gp.tileM.currentMapCols;
        float scaleY = (float)(radius * 2) / gp.tileM.currentMapRows;
        drawEntities(g2, cx - radius, cy - radius, scaleX, scaleY, largeMode);

        // 4. Radial vignette: transparent centre → dark rim (cached to avoid RadialGradientPaint per frame)
        BufferedImage vig = vignetteCache.get(radius);
        if (vig == null) {
            int diam = radius * 2;
            vig = new BufferedImage(diam, diam, BufferedImage.TYPE_INT_ARGB);
            Graphics2D vg = vig.createGraphics();
            float[] vigFracs  = { 0.50f, 1.0f };
            Color[] vigColors = { new Color(0, 0, 0, 0), new Color(0, 0, 0, 215) };
            vg.setPaint(new RadialGradientPaint(radius, radius, radius, vigFracs, vigColors));
            vg.fillOval(0, 0, diam, diam);
            vg.dispose();
            vignetteCache.put(radius, vig);
        }
        g2.drawImage(vig, cx - radius, cy - radius, null);

        // Restore clip before drawing border
        g2.setClip(savedClip);

        // 5. Ornate ring border (DST wood-frame style: dark outer / earthy brown / amber inner rim)
        int bw = largeMode ? BORDER_WIDTH + 4 : BORDER_WIDTH;

        // Outer dark body
        g2.setStroke(largeMode ? BORDER_OUTER_STROKE : BORDER_OUTER_STROKE_SM);
        g2.setColor(BORDER_OUTER_COLOR);
        g2.drawOval(cx - radius - bw / 2, cy - radius - bw / 2,
                    radius * 2 + bw, radius * 2 + bw);

        // Mid earthy-brown layer
        g2.setStroke(largeMode ? BORDER_MID_STROKE : BORDER_MID_STROKE_SM);
        g2.setColor(BORDER_MID_COLOR);
        g2.drawOval(cx - radius - bw / 2, cy - radius - bw / 2,
                    radius * 2 + bw, radius * 2 + bw);

        // Bright amber inner rim
        g2.setStroke(BORDER_INNER_STROKE);
        g2.setColor(BORDER_INNER_COLOR);
        g2.drawOval(cx - radius + 1, cy - radius + 1, radius * 2 - 2, radius * 2 - 2);

        // Faint outer highlight
        g2.setStroke(BORDER_HIGHLIGHT_STROKE);
        g2.setColor(BORDER_HIGHLIGHT_COLOR);
        g2.drawOval(cx - radius - bw, cy - radius - bw,
                    radius * 2 + bw * 2, radius * 2 + bw * 2);

        // Restore state
        g2.setStroke(savedStroke);
        if (savedInterp != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, savedInterp);
        if (savedRender != null) g2.setRenderingHint(RenderingHints.KEY_RENDERING,     savedRender);
        if (savedAA     != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  savedAA);
    }

    // -----------------------------------------------------------------------
    // Entities
    // -----------------------------------------------------------------------

    private void drawEntities(Graphics2D g2, int originX, int originY,
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

        // Player: soft glow halo + bright cream core dot
        int px = originX + (int)((gp.player.worldX / (float)gp.tileSize) * scaleX);
        int py = originY + (int)((gp.player.worldY / (float)gp.tileSize) * scaleY);
        g2.setColor(new Color(255, 235, 130, 55));
        g2.fillOval(px - playerSize, py - playerSize, playerSize * 2, playerSize * 2);
        g2.setColor(PLAYER_COLOR);
        g2.fillOval(px - playerSize / 2, py - playerSize / 2, playerSize, playerSize);

        // Viewport rectangle
        java.awt.Composite prevComp = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, largeMode ? 0.40f : 0.22f));
        g2.setColor(VIEWPORT_COLOR);
        int vpX = originX + (int)(((gp.player.worldX - gp.player.screenX) / (float)gp.tileSize) * scaleX);
        int vpY = originY + (int)(((gp.player.worldY - gp.player.screenY) / (float)gp.tileSize) * scaleY);
        int vpW = Math.max(1, (int)(gp.maxScreenCol * scaleX));
        int vpH = Math.max(1, (int)(gp.maxScreenRow * scaleY));
        g2.drawRect(vpX, vpY, vpW, vpH);
        g2.setComposite(prevComp);
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

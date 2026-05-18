package tile;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import main.GamePanel;
import main.Config;
import util.ResourceCache;
import util.UtilityTool;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class TileManager {

    GamePanel gp;

    // ---- Tiled GID flip-flag bitmasks (high 3 bits of a 32-bit GID) ----
    public static final long GID_FLIP_H  = 0x80000000L; // bit 31
    public static final long GID_FLIP_V  = 0x40000000L; // bit 30
    public static final long GID_FLIP_D  = 0x20000000L; // bit 29 (anti-diagonal / transpose)
    public static final long GID_FLIP_ALL = GID_FLIP_H | GID_FLIP_V | GID_FLIP_D;

    // ---- Animated tile support ----
    /** Per-tileset-local-id animation descriptor. */
    public static class TileAnimation {
        public int[] frameLocalIds;   // local tile ids (0-based within tileset)
        public int[] frameDurationsMs; // duration of each frame in milliseconds
        public int currentFrame = 0;
        public int timerMs = 0;
    }

    /** GID -> animation (null = not animated). Built alongside gidToTile arrays. */
    private Map<Integer, TileAnimation> gidToAnimation = new HashMap<>();
    /** Whether any animated tile is registered (skip animation tick when false). */
    private boolean hasAnimatedTiles = false;

    // ---- Image layer support ----
    public static class ImageLayerData {
        public BufferedImage image;
        public float worldX, worldY;       // offset from Tiled (already scaled)
        public float parallaxX = 1f, parallaxY = 1f;
        public float opacity = 1f;
        public java.awt.Color tintColor = null; // null = no tint
        public String name = "";
        public int globalLayerIndex;    // position in the full Tiled layer stack
        public boolean foreground = false; // true = draw above entities
    }
    /** Ordered list of image layers (parsed alongside tile layers, drawn behind background tiles). */
    public ArrayList<ImageLayerData> imageLayers = new ArrayList<>();

    // ---- Layer opacity / tint ----
    /** Per-layer opacity (1.0 = fully opaque). Same indexing as mapLayers. */
    public ArrayList<Float>  layerOpacity = new ArrayList<>();
    /** Per-layer tint color (null = none). Same indexing as mapLayers. */
    public ArrayList<Color> layerTint   = new ArrayList<>();

    // Tile scaling is centralized in Config to support runtime scaling and
    // a single authoritative source for original/native tile size and scale.
    public final int originalTileSize = Config.originalTileSize;
    public final double scale = Config.scale;
    public final int tileSize = Config.tileSize;

    // Per-map TMX tile size (updated when loading a map; used for scaling)
    int mapTileSize = Config.originalTileSize;
    // Actual map dimensions in tiles (read from TMX <map width/height>)
    public int currentMapCols = 100;
    public int currentMapRows = 100;
    // Pixel offset for infinite maps (after shifting chunks to start at 0,0)
    int mapOffsetPixelsX = 0;
    int mapOffsetPixelsY = 0;
    // Background color for void areas outside the map (parsed from TMX backgroundcolor)
    public Color mapBackgroundColor = new Color(20, 18, 22);

    // ── OPTIMIZATION: AlphaComposite cache — eliminates ~880 allocations/frame ──
    private static final HashMap<Float, AlphaComposite> alphaCompositeCache = new HashMap<>();
    static {
        // Pre-populate common alpha values
        alphaCompositeCache.put(1f, AlphaComposite.SrcOver);
    }
    private static AlphaComposite cachedAlpha(float alpha) {
        return alphaCompositeCache.computeIfAbsent(alpha,
            a -> AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
    }

    private static final String DEFAULT_COLLISION_OBJECT_LAYER = "Collision";

    private static final class TilesetFrameCache {
        final BufferedImage[] images;
        final int[] drawOffsets;

        TilesetFrameCache(BufferedImage[] images, int[] drawOffsets) {
            this.images = images;
            this.drawOffsets = drawOffsets;
        }
    }

    private static final HashMap<String, TilesetFrameCache> tilesetFrameCache = new HashMap<>();

    // Tilesets
    public class Tileset {
        String name;
        String sourcePath;
        int firstGID;
        int tileCount;
        boolean waterEffect;
        int renderOrder;
        boolean depthSort;
        boolean foreground;
        Tile[] tiles;
    }

    private static class VisibleTileDraw {
        BufferedImage image;
        int screenX;
        int screenY;
        int worldX;         // worldCol * tileSize (for fast sub-tile camera update)
        int screenBaseY;    // (worldRow * tileSize) - drawOffsetY (for fast sub-tile camera update)
        float parallaxX, parallaxY; // stored so screen pos can be recalculated without full rebuild
        int baseWorldY;
        int layerIndex;
        int worldCol;
        int renderOrder;
        int worldRow;
        int sortY;
        // Flip flags extracted from Tiled GID high bits (0 = no flip)
        byte flipFlags;
        // Per-layer rendering extras (composited at draw time)
        float opacity = 1f;
        Color tint = null;
        // OPTIMIZATION: original (possibly animated) GID so we can patch `image` in-place
        // when a tile animation advances a frame — avoids a full visible-tile rebuild.
        int baseGid;
    }

    private final Comparator<VisibleTileDraw> backgroundTileComparator = new Comparator<VisibleTileDraw>() {
        @Override
        public int compare(VisibleTileDraw first, VisibleTileDraw second) {
            int byRenderOrder = Integer.compare(first.renderOrder, second.renderOrder);
            if (byRenderOrder != 0) {
                return byRenderOrder;
            }

            int byBaseY = Integer.compare(first.baseWorldY, second.baseWorldY);
            if (byBaseY != 0) {
                return byBaseY;
            }

            int byLayer = Integer.compare(first.layerIndex, second.layerIndex);
            if (byLayer != 0) {
                return byLayer;
            }

            int byRow = Integer.compare(first.worldRow, second.worldRow);
            if (byRow != 0) {
                return byRow;
            }

            return Integer.compare(first.worldCol, second.worldCol);
        }
    };
    private final Comparator<VisibleTileDraw> depthTileComparator = new Comparator<VisibleTileDraw>() {
        @Override
        public int compare(VisibleTileDraw first, VisibleTileDraw second) {
            int bySortY = Integer.compare(first.sortY, second.sortY);
            if (bySortY != 0) {
                return bySortY;
            }

            int byRenderOrder = Integer.compare(first.renderOrder, second.renderOrder);
            if (byRenderOrder != 0) {
                return byRenderOrder;
            }

            int byLayer = Integer.compare(first.layerIndex, second.layerIndex);
            if (byLayer != 0) {
                return byLayer;
            }

            return Integer.compare(first.worldCol, second.worldCol);
        }
    };
    ArrayList<Tileset> tilesets = new ArrayList<>();
    public Tile[] tile; // combined tiles for quick lookup
    private final ArrayList<VisibleTileDraw> backgroundVisibleTiles = new ArrayList<>();
    private final ArrayList<VisibleTileDraw> depthVisibleTiles = new ArrayList<>();
    private final ArrayList<VisibleTileDraw> foregroundVisibleTiles = new ArrayList<>();

    // Multi-layer map
    public ArrayList<int[][]>  mapLayers = new ArrayList<>();
    public ArrayList<byte[][]> mapFlipLayers = new ArrayList<>();  // parallel flip-flag arrays
    public ArrayList<String> layerNames = new ArrayList<>();
    public ArrayList<Float> layerParallaxX = new ArrayList<>();
    public ArrayList<Float> layerParallaxY = new ArrayList<>();
    public ArrayList<Boolean> layerBackground = new ArrayList<>();
    public ArrayList<Boolean> layerDepthSort  = new ArrayList<>();
    public ArrayList<Boolean> layerForeground = new ArrayList<>();
    // New: unified render bucket from Tiled "render" enum property ("auto"/"background"/"depth"/"foreground")
    // null or "auto" means the engine decides. Takes priority over legacy booleans when set.
    public ArrayList<String> layerRenderBucket = new ArrayList<>();
    // Global layer index for each tile layer (accounts for interleaved image/group layers)
    public ArrayList<Integer> layerGlobalIndex = new ArrayList<>();
    // Per-tile-layer pixel offsets (from Tiled offsetx/offsety, already scaled to game pixels)
    public ArrayList<Float> layerOffsetX = new ArrayList<>();
    public ArrayList<Float> layerOffsetY = new ArrayList<>();
    // (layerOpacity and layerTint declared above near ImageLayerData)

    // Collision shapes (from Tiled objectgroup layers — rectangles, rotated rects, polygons, ellipses)
    public ArrayList<Shape> collisionShapes = new ArrayList<>();
    // Bounding boxes for each shape (used by spatial grid for broad-phase)
    public ArrayList<Rectangle> collisionBounds = new ArrayList<>();
    // Axis-aligned rectangle occluders only — used exclusively by the lighting system for shadow casting.
    // Non-rectangular and rotated shapes are intentionally excluded to prevent incorrect shadow projections.
    public ArrayList<Rectangle> lightOccluderRects = new ArrayList<>();

    // Runtime per-tile-position lit state. Cleared + rebuilt every frame by Lightning.draw().
    // Index: [col][row]. Null until initTileLitMap() is called on first map load.
    public boolean[][] tileIsLit = null;

    // Per-tile solid state — true if the tile is covered by any collision shape.
    // Built once per map load by initTileSolid(); used by the lighting system for
    // shadow casting and BFS propagation on all graphics quality levels.
    public boolean[][] tileSolid = null;

    // Per-tile light intensity for LOW graphics mode (0.0 = dark, 1.0 = fully lit).
    // Cleared + rebuilt every frame alongside tileIsLit. Null until initTileLitMap().
    public float[][] tileLightLevel = null;

    /**
     * Packed [col, row] tile positions on the current map that contain at least one tile with
     * reflectsLight = true. Built once at map load by {@link #rebuildReflectiveTilePositions()}.
     * Lightning iterates this list every frame instead of scanning the full visible viewport,
     * which collapses the reflective-highlight pass from O(visibleTiles * layers) to O(reflectiveTiles).
     * Even with hundreds of reflective tiles per map, this is typically <1% of the previous work.
     */
    public int[] reflectiveTilePositions = new int[0];

    public void initTileLitMap() {
        tileIsLit = new boolean[gp.maxWorldCol][gp.maxWorldRow];
        tileLightLevel = new float[gp.maxWorldCol][gp.maxWorldRow];
        initTileSolid();
        rebuildReflectiveTilePositions();
    }

    /**
     * Rasterise all collision shapes onto a per-tile boolean grid.
     * A tile is marked solid if its rectangle intersects any loaded collision shape.
     * Called once per map load from initTileLitMap().
     */
    public void initTileSolid() {
        int cols = gp.maxWorldCol;
        int rows = gp.maxWorldRow;
        tileSolid = new boolean[cols][rows];
        if (collisionShapes.isEmpty()) return;
        double ts = gp.tileSize;
        for (int col = 0; col < cols; col++) {
            double tx = col * ts;
            for (int row = 0; row < rows; row++) {
                double ty = row * ts;
                for (int s = 0, n = collisionShapes.size(); s < n; s++) {
                    if (collisionShapes.get(s).intersects(tx, ty, ts, ts)) {
                        tileSolid[col][row] = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Scan all map layers once and remember which tile positions contain a reflectsLight tile.
     * Called automatically by initTileLitMap() on each map load.
     * Result is stored in reflectiveTilePositions as a flat packed array: [col0, row0, col1, row1, ...].
     */
    public void rebuildReflectiveTilePositions() {
        if (gidToReflectsLight == null || mapLayers.isEmpty()) {
            reflectiveTilePositions = new int[0];
            return;
        }
        int maxCol = gp.maxWorldCol;
        int maxRow = gp.maxWorldRow;
        // First pass: count to size the flat array exactly
        int count = 0;
        for (int col = 0; col < maxCol; col++) {
            for (int row = 0; row < maxRow; row++) {
                for (int l = 0; l < mapLayers.size(); l++) {
                    int[][] layer = mapLayers.get(l);
                    if (col >= layer.length || row >= layer[col].length) continue;
                    int gid = layer[col][row];
                    if (gid > 0 && gid < gidToReflectsLight.length && gidToReflectsLight[gid]) {
                        count++;
                        break; // one match per (col,row) is enough
                    }
                }
            }
        }
        // Second pass: fill
        int[] out = new int[count * 2];
        int idx = 0;
        for (int col = 0; col < maxCol; col++) {
            for (int row = 0; row < maxRow; row++) {
                for (int l = 0; l < mapLayers.size(); l++) {
                    int[][] layer = mapLayers.get(l);
                    if (col >= layer.length || row >= layer[col].length) continue;
                    int gid = layer[col][row];
                    if (gid > 0 && gid < gidToReflectsLight.length && gidToReflectsLight[gid]) {
                        out[idx++] = col;
                        out[idx++] = row;
                        break;
                    }
                }
            }
        }
        reflectiveTilePositions = out;
        System.out.println("Reflective tile cache: " + count + " tiles flagged");
    }

    public boolean isTileLit(int col, int row) {
        if (tileIsLit == null || col < 0 || col >= tileIsLit.length) return false;
        if (row < 0 || row >= tileIsLit[col].length) return false;
        return tileIsLit[col][row];
    }

    public void clearTileLitMap() {
        if (tileIsLit == null) return;
        for (boolean[] column : tileIsLit) Arrays.fill(column, false);
        if (tileLightLevel != null) {
            for (float[] column : tileLightLevel) Arrays.fill(column, 0f);
        }
    }

    // --- Configurable collision settings ---
    // Objectgroup layer names whose rectangles provide collision (default: "Collision")
    public HashSet<String> collisionObjectLayers = new HashSet<>();
    // Tile layer names where all non-empty tiles block movement (default: none)
    public HashSet<String> collisionTileLayers = new HashSet<>();

    public TileManager(GamePanel gp) {
        this.gp = gp;

        resetCollisionConfig();

        initializeDefaultMap();
        printCollisionConfig();
    }

    /** Print current collision configuration to console. */
    private void printCollisionConfig() {
        System.out.println("=== COLLISION CONFIGURATION ===");
        System.out.println("Object layers (rectangles): " + collisionObjectLayers);
        System.out.println("Tile layers (full tile blocking): " + collisionTileLayers);
        System.out.println("Loaded collision shapes: " + collisionShapes.size());
        System.out.println("Tile layer names in map: " + layerNames);
        System.out.println("===============================");
    }

    private void initializeDefaultMap() {
        loadMapFromTMX("/res/maps/Awakening_Cave.tmx");
        loadCollisionLayer("/res/maps/Awakening_Cave.tmx");
        initTileLitMap();
    }

    private void resetCollisionConfig() {
        collisionObjectLayers.clear();
        collisionTileLayers.clear();
        collisionObjectLayers.add(DEFAULT_COLLISION_OBJECT_LAYER);
    }

    private void applyMapCollisionProperties(Element mapRoot) {
        String objectLayers = getStringProperty(mapRoot, "collisionObjectLayers", "");
        if (objectLayers != null && !objectLayers.isBlank()) {
            collisionObjectLayers.clear();
            loadDelimitedSet(collisionObjectLayers, objectLayers);
            if (collisionObjectLayers.isEmpty()) {
                collisionObjectLayers.add(DEFAULT_COLLISION_OBJECT_LAYER);
            }
        }

        String tileLayers = getStringProperty(mapRoot, "collisionTileLayers", "");
        if (tileLayers != null && !tileLayers.isBlank()) {
            loadDelimitedSet(collisionTileLayers, tileLayers);
        }
    }

    private void loadDelimitedSet(HashSet<String> target, String value) {
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    // ---------------- Load tilesets ----------------
    public void addTileset(String path, int firstGID, int tileWidth, int tileHeight, int tileCount, int columns, String name, int renderOrder, boolean depthSort, boolean foreground) {
        try {
            int safeColumns = Math.max(1, columns);
            int safeTileCount = Math.max(1, tileCount);
            TilesetFrameCache cachedFrames = getOrCreateTilesetFrames(path, tileWidth, tileHeight, safeTileCount, safeColumns);

            Tileset ts = new Tileset();
            ts.name = name;
            ts.sourcePath = path;
            ts.firstGID = firstGID;
            ts.tileCount = safeTileCount;
            ts.waterEffect = isWaterTileset(name, path);
            ts.renderOrder = renderOrder;
            ts.depthSort = depthSort;
            ts.foreground = foreground;
            ts.tiles = new Tile[ts.tileCount];

            for (int index = 0; index < ts.tileCount; index++) {
                if (cachedFrames.images[index] == null) {
                    break;
                }

                ts.tiles[index] = new Tile();
                ts.tiles[index].image = cachedFrames.images[index];
                ts.tiles[index].drawOffsetY = cachedFrames.drawOffsets[index];
            }

            tilesets.add(ts);
        } catch (Exception e) {
            System.out.println("Failed to load tileset: " + path);
            e.printStackTrace(System.out);
        }
    }

    private TilesetFrameCache getOrCreateTilesetFrames(String path, int tileWidth, int tileHeight,
                                                       int tileCount, int columns) throws Exception {
        String cacheKey = path + '|' + tileWidth + '|' + tileHeight + '|' + tileCount
            + '|' + columns + '|' + mapTileSize + '|' + tileSize;
        TilesetFrameCache cached = tilesetFrameCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        BufferedImage tilesetImage = ResourceCache.loadImage(path);
        BufferedImage[] images = new BufferedImage[tileCount];
        int[] drawOffsets = new int[tileCount];
        float tileScale = (float) tileSize / mapTileSize;

        for (int index = 0; index < tileCount; index++) {
            int tileX = (index % columns) * tileWidth;
            int tileY = (index / columns) * tileHeight;
            if (tileX + tileWidth > tilesetImage.getWidth() || tileY + tileHeight > tilesetImage.getHeight()) {
                break;
            }

            BufferedImage sub = tilesetImage.getSubimage(tileX, tileY, tileWidth, tileHeight);
            int scaledWidth = Math.max(1, Math.round(tileWidth * tileScale));
            int scaledHeight = Math.max(1, Math.round(tileHeight * tileScale));
            images[index] = UtilityTool.scaleImage(sub, scaledWidth, scaledHeight);
            drawOffsets[index] = Math.max(0, scaledHeight - tileSize);
        }

        TilesetFrameCache frameCache = new TilesetFrameCache(images, drawOffsets);
        tilesetFrameCache.put(cacheKey, frameCache);
        return frameCache;
    }

    public void loadTilesets(String mapPath) {
        // Free all cached scaled tile images from the previous map before loading the new one.
        // Without this, the static cache accumulates hundreds of MB and causes OutOfMemoryError
        // when entering a large map (e.g. Canvas Village) after a smaller one.
        tilesetFrameCache.clear();
        tilesets.clear();
        gidToAnimation.clear();
        hasAnimatedTiles = false;
        try {
            Document doc = parseXmlResource(mapPath);
            if (doc == null) {
                return;
            }

            // Read map base tile size first so addTileset can scale oversized tiles correctly
            Element mapRootEl = doc.getDocumentElement();
            String mapTw = mapRootEl.getAttribute("tilewidth");
            mapTileSize = (mapTw != null && !mapTw.isEmpty()) ? Integer.parseInt(mapTw) : originalTileSize;

            NodeList tilesetNodes = doc.getElementsByTagName("tileset");
            for (int i = 0; i < tilesetNodes.getLength(); i++) {
                Element tilesetElement = (Element) tilesetNodes.item(i);
                Element imageElement = (Element) tilesetElement.getElementsByTagName("image").item(0);

                // metaElement holds tilewidth/tileheight/tilecount/columns/name.
                // For external .tsx references these come from the tsx root, not the tmx tileset stub.
                Element metaElement = tilesetElement;
                String tsxBaseDir = null;

                if (imageElement == null) {
                    // External .tsx reference: <tileset firstgid="N" source="../tiles/foo.tsx"/>
                    String tsxSource = tilesetElement.getAttribute("source");
                    if (tsxSource == null || tsxSource.isEmpty()) continue;
                    String tsxPath = toResourcePath(tsxSource);
                    if (tsxPath == null) {
                        System.out.println("Skipping external tileset (cannot resolve): " + tsxSource);
                        continue;
                    }
                    // Base dir for resolving the tsx's own image path
                    int lastSlash = tsxPath.lastIndexOf('/');
                    tsxBaseDir = lastSlash >= 0 ? tsxPath.substring(0, lastSlash + 1) : "/res/tiles/";
                    Document tsxDoc = parseXmlResource(tsxPath);
                    if (tsxDoc == null) {
                        System.out.println("Skipping external tileset (cannot load): " + tsxPath);
                        continue;
                    }
                    metaElement = tsxDoc.getDocumentElement();
                    imageElement = (Element) metaElement.getElementsByTagName("image").item(0);
                    if (imageElement == null) {
                        System.out.println("Skipping external tileset (no image in tsx): " + tsxPath);
                        continue;
                    }
                }

                String source = imageElement.getAttribute("source");
                String resourcePath;
                if (tsxBaseDir != null) {
                    // Resolve image path relative to the tsx file's directory
                    String normalized = source.replace('\\', '/').replaceFirst("^(\\.\\./)+", "");
                    resourcePath = tsxBaseDir + normalized;
                } else {
                    resourcePath = toResourcePath(source);
                }
                if (resourcePath == null) {
                    System.out.println("Skipping tileset source: " + source);
                    continue;
                }

                addTileset(
                    resourcePath,
                    Integer.parseInt(tilesetElement.getAttribute("firstgid")),
                    Integer.parseInt(metaElement.getAttribute("tilewidth")),
                    Integer.parseInt(metaElement.getAttribute("tileheight")),
                    Integer.parseInt(metaElement.getAttribute("tilecount")),
                    Integer.parseInt(metaElement.getAttribute("columns")),
                    metaElement.getAttribute("name"),
                    getTilesetRenderOrder(metaElement, resourcePath),
                    getTilesetDepthSort(metaElement, resourcePath),
                    getTilesetForeground(metaElement)
                );
                applyPerTileProperties(tilesets.get(tilesets.size() - 1), metaElement);
            }

            rebuildTileLookup();
        } catch (Exception e) {
            System.out.println("Failed to load tilesets from map: " + mapPath);
            e.printStackTrace(System.out);
        }
    }

    // ---------------- Get tile type for a world position ----------------
    /**
     * Returns the tile type constant for the given tile column/row.
     * Checks all layers top-down and returns the first non-empty GID's type.
     * Types: 0 = none, 1 = grass (GID 1-12), 2 = stone (GID 13-76), 3 = water (GID 77+)
     */
    public int getTileType(int col, int row) {
        if (col < 0 || col >= gp.maxWorldCol || row < 0 || row >= gp.maxWorldRow) return 0;
        // Check layers top-down so the uppermost visible tile wins
        for (int l = mapLayers.size() - 1; l >= 0; l--) {
            int gid = mapLayers.get(l)[col][row];
            if (gid == 0) continue;
            Tileset ts = getTilesetForGID(gid);
            if (ts == null) continue;
            if (ts.waterEffect) return 3;
            if (isGrassTileset(ts)) return 1;
            if (isSolidGroundTileset(ts)) return 2;
        }
        return 0;
    }

    // ---------------- Get tile by GID ----------------
    public Tile getTileByGID(int gid) {
        if (gid == 0) return null;
        for (int i = tilesets.size() - 1; i >= 0; i--) {
            Tileset ts = tilesets.get(i);
            if (gid >= ts.firstGID) {
                int index = gid - ts.firstGID;
                if (index >= 0 && index < ts.tiles.length) return ts.tiles[index];
            }
        }
        return null;
    }

    // Mask to strip Tiled flip flags (horizontal, vertical, diagonal) from GIDs —
    // used when we only need the clean tile index (lookup). Flip flags are extracted separately.
    private static final long GID_MASK = 0x1FFFFFFFL;

    // ---------------- Load tile layers ----------------
    public void loadMapFromTMX(String path) {
        try {
            loadTilesets(path);
            mapLayers.clear();
            mapFlipLayers.clear();
            layerNames.clear();
            layerParallaxX.clear();
            layerParallaxY.clear();
            layerBackground.clear();
            layerDepthSort.clear();
            layerForeground.clear();
            layerRenderBucket.clear();
            layerOpacity.clear();
            layerTint.clear();
            layerGlobalIndex.clear();
            layerOffsetX.clear();
            layerOffsetY.clear();
            imageLayers.clear();
            hasFgImagesCacheValid = false;

            Document doc = parseXmlResource(path);
            if (doc == null) {
                return;
            }

            // Read TMX map tile size
            Element mapRoot = doc.getDocumentElement();
            String tw = mapRoot.getAttribute("tilewidth");
            if (tw != null && !tw.isEmpty()) {
                mapTileSize = Integer.parseInt(tw);
            } else {
                mapTileSize = originalTileSize;
            }

            // Read actual map dimensions (tiles)
            String mw = mapRoot.getAttribute("width");
            String mh = mapRoot.getAttribute("height");
            if (mw != null && !mw.isEmpty()) currentMapCols = Integer.parseInt(mw);
            if (mh != null && !mh.isEmpty()) currentMapRows = Integer.parseInt(mh);

            resetCollisionConfig();
            applyMapCollisionProperties(mapRoot);

            // Parse TMX background color (e.g. "#1a1216" or "#ff1a1216")
            String bgAttr = mapRoot.getAttribute("backgroundcolor");
            if (bgAttr != null && bgAttr.startsWith("#") && bgAttr.length() >= 7) {
                try {
                    String hex = bgAttr.substring(1);
                    if (hex.length() == 6) {
                        mapBackgroundColor = new Color(Integer.parseInt(hex, 16));
                    } else if (hex.length() == 8) {
                        // ARGB format from Tiled
                        int argb = (int) Long.parseLong(hex, 16);
                        mapBackgroundColor = new Color(argb, true);
                    }
                } catch (NumberFormatException ignored) {}
            }

            // Detect infinite map
            boolean infinite = "1".equals(mapRoot.getAttribute("infinite"));
            int offsetX = 0, offsetY = 0;

            if (infinite) {
                // Scan ALL chunks to find the min x/y so we can shift to 0,0
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                NodeList allChunks = doc.getElementsByTagName("chunk");
                for (int c = 0; c < allChunks.getLength(); c++) {
                    Element chunk = (Element) allChunks.item(c);
                    int cx = Integer.parseInt(chunk.getAttribute("x"));
                    int cy = Integer.parseInt(chunk.getAttribute("y"));
                    if (cx < minX) minX = cx;
                    if (cy < minY) minY = cy;
                }
                if (minX != Integer.MAX_VALUE) {
                    offsetX = -minX;
                    offsetY = -minY;
                }
                System.out.println("Infinite map detected: tile offset (" + offsetX + ", " + offsetY + ")");
            }

            // Store pixel offsets for collision layer (tile offset * game tile size)
            mapOffsetPixelsX = offsetX * tileSize;
            mapOffsetPixelsY = offsetY * tileSize;

            // Walk all children of <map> in Tiled document order so that tile layers,
            // image layers, and group layers are processed in the exact stacking order
            // the user set up in the Tiled editor.
            int[] globalLayerIdx = {0};
            processLayerChildren(mapRoot, globalLayerIdx, infinite, offsetX, offsetY,
                                 1f, null, 0f, 0f);

            logLayerClassification();

            System.out.println("Loaded " + mapLayers.size() + " tile layers, "
                + imageLayers.size() + " image layers (mapTileSize=" + mapTileSize + "px)");
        } catch (Exception e) {
            System.out.println("Failed to load map: " + path);
            e.printStackTrace(System.out);
        }
    }

    /**
     * Log the classification of every loaded layer for debugging.
     * Classification is determined ONLY by explicit Tiled properties:
     *   - "render" enum property (RenderBucket): background | depth | foreground
     *   - Legacy boolean properties: background, depthSort, foreground
     * No name-based heuristics. Layers without a render property default to background.
     */
    private void logLayerClassification() {
        System.out.println("=== LAYER CLASSIFICATION ===");
        for (int l = 0; l < mapLayers.size(); l++) {
            String name   = l < layerNames.size() ? layerNames.get(l) : "?";
            String bucket = l < layerRenderBucket.size() ? layerRenderBucket.get(l) : null;
            boolean expBg = l < layerBackground.size() && layerBackground.get(l);
            boolean expDs = l < layerDepthSort.size()  && layerDepthSort.get(l);
            boolean expFg = l < layerForeground.size() && layerForeground.get(l);

            String classification;
            if ("foreground".equals(bucket))      classification = "FOREGROUND (render enum)";
            else if ("background".equals(bucket)) classification = "BACKGROUND (render enum)";
            else if ("depth".equals(bucket))      classification = "DEPTH (render enum)";
            else if (expFg)                       classification = "FOREGROUND (legacy bool)";
            else if (expDs)                       classification = "DEPTH (legacy bool)";
            else if (expBg)                       classification = "BACKGROUND (legacy bool)";
            else {
                classification = "BACKGROUND (default — no render property set)";
                System.out.println("  WARN: Layer " + l + " \"" + name + "\" has no 'render' property, defaulting to background");
            }
            System.out.println("  Layer " + l + " \"" + name + "\" -> " + classification);
        }
        System.out.println("===========================");
    }

    /**
     * Recursively walk the child elements of a TMX container (map root or group)
     * in document order, processing tile layers, image layers, and groups.
     * This preserves the exact visual stacking order from the Tiled editor.
     *
     * @param parent        the container element ({@code <map>} or {@code <group>})
     * @param globalIdx     single-element array holding the next global layer index (mutable counter)
     * @param infinite      whether the map uses infinite chunks
     * @param offsetX       tile offset for infinite maps (shift to 0,0)
     * @param offsetY       tile offset for infinite maps (shift to 0,0)
     * @param parentOpacity composed opacity from parent group(s), 1.0 = fully opaque
     * @param parentTint    composed tint from parent group(s), null = no tint
     * @param parentOffX    accumulated X offset from parent groups (Tiled pixels, unscaled)
     * @param parentOffY    accumulated Y offset from parent groups (Tiled pixels, unscaled)
     */
    private void processLayerChildren(Element parent, int[] globalIdx,
                                      boolean infinite, int offsetX, int offsetY,
                                      float parentOpacity, Color parentTint,
                                      float parentOffX, float parentOffY) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            String tag = child.getTagName();

            // Skip invisible layers (Tiled visible attribute: absent or "1" = visible, "0" = hidden)
            String visAttr = child.getAttribute("visible");
            if ("0".equals(visAttr)) continue;

            switch (tag) {
                case "layer":
                    parseTileLayerElement(child, globalIdx[0]++, infinite, offsetX, offsetY,
                                         parentOpacity, parentTint, parentOffX, parentOffY);
                    break;
                case "imagelayer":
                    parseImageLayerElement(child, globalIdx[0]++, parentOpacity, parentTint,
                                           parentOffX, parentOffY);
                    break;
                case "group":
                    float groupOpacity = getFloatAttribute(child, "opacity", 1f) * parentOpacity;
                    Color groupTint = composeTint(parentTint, parseTintColor(child.getAttribute("tintcolor")));
                    float groupOffX = parentOffX + getFloatAttribute(child, "offsetx", 0f);
                    float groupOffY = parentOffY + getFloatAttribute(child, "offsety", 0f);
                    processLayerChildren(child, globalIdx, infinite, offsetX, offsetY,
                                        groupOpacity, groupTint, groupOffX, groupOffY);
                    break;
                // objectgroup handled separately (collision, entities)
            }
        }
    }

    /** Parse a single {@code <layer>} element and add its tile data to the layer lists. */
    private void parseTileLayerElement(Element layer, int globalIndex,
                                       boolean infinite, int offsetX, int offsetY,
                                       float parentOpacity, Color parentTint,
                                       float parentOffX, float parentOffY) {
        String layerName = layer.getAttribute("name");
        Element data = (Element) layer.getElementsByTagName("data").item(0);
        if (data == null) return;

        double sf = (double) tileSize / mapTileSize;
        float parallaxX = getFloatAttribute(layer, "parallaxx", 1.0f);
        float parallaxY = getFloatAttribute(layer, "parallaxy", 1.0f);
        float opacity   = getFloatAttribute(layer, "opacity", 1.0f) * parentOpacity;
        Color tint      = composeTint(parentTint, parseTintColor(layer.getAttribute("tintcolor")));
        float layerOffX = parentOffX + getFloatAttribute(layer, "offsetx", 0f);
        float layerOffY = parentOffY + getFloatAttribute(layer, "offsety", 0f);

        boolean isBackground = false;
        boolean isDepthSort  = false;
        boolean isForeground = false;
        boolean isCollision  = false;
        String renderBucket  = null; // from "render" enum property (RenderBucket)
        NodeList layerProps = layer.getChildNodes();
        for (int lp = 0; lp < layerProps.getLength(); lp++) {
            if (layerProps.item(lp) instanceof Element) {
                Element child = (Element) layerProps.item(lp);
                if ("properties".equals(child.getTagName())) {
                    NodeList propList = child.getElementsByTagName("property");
                    for (int pp = 0; pp < propList.getLength(); pp++) {
                        Element prop = (Element) propList.item(pp);
                        String propName = prop.getAttribute("name");
                        if ("render".equals(propName)) {
                            renderBucket = prop.getAttribute("value").trim().toLowerCase();
                        } else if ("background".equals(propName)) {
                            isBackground = "true".equalsIgnoreCase(prop.getAttribute("value"));
                        } else if ("depthSort".equals(propName)) {
                            isDepthSort = "true".equalsIgnoreCase(prop.getAttribute("value"));
                        } else if ("foreground".equals(propName)) {
                            isForeground = "true".equalsIgnoreCase(prop.getAttribute("value"));
                        } else if ("collision".equals(propName)) {
                            isCollision = "true".equalsIgnoreCase(prop.getAttribute("value"));
                        }
                    }
                }
            }
        }
        if (isCollision && !layerName.isBlank()) {
            collisionTileLayers.add(layerName);
        }

        int[][] layerMap  = new int[gp.maxWorldCol][gp.maxWorldRow];
        byte[][] flipMap  = new byte[gp.maxWorldCol][gp.maxWorldRow];

        NodeList chunks = data.getElementsByTagName("chunk");
        if (chunks.getLength() > 0) {
            for (int c = 0; c < chunks.getLength(); c++) {
                Element chunk = (Element) chunks.item(c);
                int cx = Integer.parseInt(chunk.getAttribute("x")) + offsetX;
                int cy = Integer.parseInt(chunk.getAttribute("y")) + offsetY;
                int cw = Integer.parseInt(chunk.getAttribute("width"));
                String csv = chunk.getTextContent().trim().replaceAll("\\s+", "");
                String[] numbers = csv.split(",");
                int col = 0, row = 0;
                for (String numStr : numbers) {
                    if (numStr.isEmpty()) continue;
                    long raw = Long.parseLong(numStr.trim());
                    int gid = (int) (raw & GID_MASK);
                    byte flip = extractFlipFlags(raw);
                    int mapCol = cx + col;
                    int mapRow = cy + row;
                    if (mapCol >= 0 && mapCol < gp.maxWorldCol && mapRow >= 0 && mapRow < gp.maxWorldRow) {
                        layerMap[mapCol][mapRow] = gid;
                        flipMap[mapCol][mapRow]  = flip;
                    }
                    col++;
                    if (col == cw) {
                        col = 0;
                        row++;
                    }
                }
            }
        } else {
            String layerWidthStr = layer.getAttribute("width");
            int layerWidth = (layerWidthStr != null && !layerWidthStr.isEmpty())
                    ? Integer.parseInt(layerWidthStr) : gp.maxWorldCol;
            String csv = data.getTextContent().trim().replaceAll("\\s+", "");
            String[] numbers = csv.split(",");
            int col = 0, row = 0;
            for (String numStr : numbers) {
                if (numStr.isEmpty()) continue;
                long raw = Long.parseLong(numStr.trim());
                int gid  = (int) (raw & GID_MASK);
                byte flip = extractFlipFlags(raw);
                if (col < gp.maxWorldCol && row < gp.maxWorldRow) {
                    layerMap[col][row] = gid;
                    flipMap[col][row]  = flip;
                }
                col++;
                if (col == layerWidth) {
                    col = 0;
                    row++;
                    if (row >= gp.maxWorldRow) break;
                }
            }
        }

        mapLayers.add(layerMap);
        mapFlipLayers.add(flipMap);
        layerNames.add(layerName);
        layerParallaxX.add(parallaxX);
        layerParallaxY.add(parallaxY);
        layerBackground.add(isBackground);
        layerDepthSort.add(isDepthSort);
        layerForeground.add(isForeground);
        layerRenderBucket.add(renderBucket);
        layerOpacity.add(opacity);
        layerTint.add(tint);
        layerGlobalIndex.add(globalIndex);
        layerOffsetX.add((float)(layerOffX * sf));
        layerOffsetY.add((float)(layerOffY * sf));
    }

    /** Parse a single {@code <imagelayer>} element and add it to imageLayers. */
    private void parseImageLayerElement(Element ilEl, int globalIndex,
                                        float parentOpacity, Color parentTint,
                                        float parentOffX, float parentOffY) {
        Element imgEl = (Element) ilEl.getElementsByTagName("image").item(0);
        if (imgEl == null) return;
        String srcRaw = imgEl.getAttribute("source");
        String srcPath = toResourcePath(srcRaw);
        if (srcPath == null) {
            System.out.println("Skipping imagelayer — cannot resolve source: " + srcRaw);
            return;
        }
        BufferedImage rawImage;
        try {
            rawImage = ResourceCache.loadImage(srcPath);
        } catch (java.io.IOException imageError) {
            System.out.println("Skipping imagelayer — file not found: " + srcPath);
            return;
        }

        double sf = (double) tileSize / mapTileSize;
        float layerOffX = parentOffX + getFloatAttribute(ilEl, "offsetx", 0f);
        float layerOffY = parentOffY + getFloatAttribute(ilEl, "offsety", 0f);

        ImageLayerData ild = new ImageLayerData();
        ild.name      = ilEl.getAttribute("name");
        ild.worldX    = (float) (layerOffX * sf + mapOffsetPixelsX);
        ild.worldY    = (float) (layerOffY * sf + mapOffsetPixelsY);
        ild.parallaxX = getFloatAttribute(ilEl, "parallaxx", 1f);
        ild.parallaxY = getFloatAttribute(ilEl, "parallaxy", 1f);
        ild.opacity   = getFloatAttribute(ilEl, "opacity", 1f) * parentOpacity;
        ild.tintColor = composeTint(parentTint, parseTintColor(ilEl.getAttribute("tintcolor")));
        ild.globalLayerIndex = globalIndex;
        // Read "render" enum property (RenderBucket) — takes priority over legacy "foreground" boolean
        String renderProp = getStringProperty(ilEl, "render", null);
        if (renderProp != null && !renderProp.isBlank()) {
            String r = renderProp.trim().toLowerCase();
            ild.foreground = "foreground".equals(r);
        } else {
            String fgProp = getStringProperty(ilEl, "foreground", null);
            ild.foreground = fgProp != null && Boolean.parseBoolean(fgProp.trim());
        }

        int scaledW = Math.max(1, (int) Math.round(rawImage.getWidth()  * sf));
        int scaledH = Math.max(1, (int) Math.round(rawImage.getHeight() * sf));
        try {
            ild.image = ResourceCache.loadScaledImage(srcPath, scaledW, scaledH);
        } catch (java.io.IOException scaleError) {
            System.out.println("Failed to scale imagelayer: " + srcPath);
            ild.image = rawImage;
        }
        imageLayers.add(ild);
        System.out.println("Loaded imagelayer[" + globalIndex + "]: " + ild.name
            + " @ (" + ild.worldX + "," + ild.worldY + ")");
    }

    /** Compose two tint colors (null = no tint). Multiplies RGB channels when both are present. */
    private static Color composeTint(Color parent, Color child) {
        if (child == null) return parent;
        if (parent == null) return child;
        float r = (parent.getRed()   / 255f) * (child.getRed()   / 255f);
        float g = (parent.getGreen() / 255f) * (child.getGreen() / 255f);
        float b = (parent.getBlue()  / 255f) * (child.getBlue()  / 255f);
        float a = (parent.getAlpha() / 255f) * (child.getAlpha() / 255f);
        return new Color(r, g, b, a);
    }

    // ---------------- Load Collision Layer ----------------
    public void loadCollisionLayer(String path) {
        try {
            Document doc = parseXmlResource(path);
            if (doc == null) {
                System.out.println("WARNING: Could not load collision layer — TMX not found: " + path);
                return;
            }

            collisionShapes.clear();
            collisionBounds.clear();
            lightOccluderRects.clear();
            int matchedLayers = 0;

            NodeList objectGroups = doc.getElementsByTagName("objectgroup");
            for (int i = 0; i < objectGroups.getLength(); i++) {
                Element og = (Element) objectGroups.item(i);
                String layerName = og.getAttribute("name");
                if (!collisionObjectLayers.contains(layerName)) continue;
                matchedLayers++;
                System.out.println("Loading collision shapes from objectgroup: '" + layerName + "'");

                NodeList objects = og.getElementsByTagName("object");

                // --- Pass 1: collect named collision templates ---
                // Any object with property isCollisionTemplate=true acts as a shape template
                // and is NOT added as a real collision shape itself.
                java.util.HashMap<String, Element> templateMap = new java.util.HashMap<>();
                for (int j = 0; j < objects.getLength(); j++) {
                    Element obj = (Element) objects.item(j);
                    if ("true".equalsIgnoreCase(getObjectProperty(obj, "isCollisionTemplate"))) {
                        String name = obj.getAttribute("name");
                        if (!name.isEmpty()) {
                            templateMap.put(name, obj);
                            System.out.println("  Registered collision template: '" + name + "'");
                        }
                    }
                }

                // --- Pass 2: build shapes ---
                for (int j = 0; j < objects.getLength(); j++) {
                    try {
                        Element obj = (Element) objects.item(j);
                        // Skip template definitions — they are not real collision shapes
                        if ("true".equalsIgnoreCase(getObjectProperty(obj, "isCollisionTemplate"))) continue;

                        String tmplName = getObjectProperty(obj, "collisionTemplate");
                        if (tmplName != null && !tmplName.isEmpty()) {
                            // Template instance — clone template shape at this object's position
                            Element tmpl = templateMap.get(tmplName);
                            if (tmpl != null) {
                                Shape shape = parseCollisionObjectAtPosition(tmpl, obj);
                                if (shape != null) {
                                    collisionShapes.add(shape);
                                    collisionBounds.add(shape.getBounds());
                                    if (isAxisAlignedCollisionRect(tmpl)) lightOccluderRects.add(shape.getBounds());
                                }
                            } else {
                                System.out.println("WARNING: collisionTemplate '" + tmplName
                                        + "' not found for object #" + obj.getAttribute("id")
                                        + " (is the template in the same Collision layer?)");
                            }
                        } else {
                            boolean isAARect = isAxisAlignedCollisionRect(obj);
                            Shape shape = parseCollisionObject(obj);
                            if (shape != null) {
                                collisionShapes.add(shape);
                                collisionBounds.add(shape.getBounds());
                                if (isAARect) lightOccluderRects.add(shape.getBounds());
                            }
                        }
                    } catch (Exception objEx) {
                        System.out.println("Skipping bad collision object: " + objEx.getMessage());
                    }
                }
            }

            if (matchedLayers == 0) {
                System.out.println("WARNING: No matching objectgroup found in " + path);
                System.out.println("  Configured collision object layers: " + collisionObjectLayers);
            }
            System.out.println("Loaded collision layer with " + collisionShapes.size() + " shapes from " + path);

        } catch (Exception e) {
            System.out.println("Failed to load collision layer: " + path);
            e.printStackTrace(System.out);
        }
    }

    /**
     * Returns true when a Tiled collision object is a plain axis-aligned rectangle (no rotation,
     * no polygon/ellipse/polyline child). Only these shapes are safe to use as light occluders;
     * bounding boxes of non-rectangular shapes are too large and cause false shadows.
     */
    private boolean isAxisAlignedCollisionRect(Element obj) {
        if (obj.getElementsByTagName("polygon").getLength()  > 0) return false;
        if (obj.getElementsByTagName("ellipse").getLength()  > 0) return false;
        if (obj.getElementsByTagName("polyline").getLength() > 0) return false;
        String rot = obj.getAttribute("rotation");
        if (!rot.isEmpty()) {
            try { if (Math.abs(Double.parseDouble(rot)) > 0.01) return false; }
            catch (NumberFormatException ignored) {}
        }
        String w = obj.getAttribute("width");
        String h = obj.getAttribute("height");
        return !w.isEmpty() && !h.isEmpty();
    }

    /**
     * Parse a single Tiled object into a collision Shape.
     * Supports: rectangles (with rotation), ellipses (with rotation), polygons (with rotation).
     * All coordinates are scaled from Tiled map tile size to the game tileSize.
     */
    private Shape parseCollisionObject(Element obj) {
        double sf = (double) tileSize / mapTileSize; // scale factor (adapts to TMX tile size)

        String xAttr = obj.getAttribute("x");
        String yAttr = obj.getAttribute("y");
        if (xAttr.isEmpty() || yAttr.isEmpty()) return null;

        double x = Double.parseDouble(xAttr) * sf + mapOffsetPixelsX;
        double y = Double.parseDouble(yAttr) * sf + mapOffsetPixelsY;

        double rotation = 0;
        String rotAttr = obj.getAttribute("rotation");
        if (!rotAttr.isEmpty()) {
            rotation = Double.parseDouble(rotAttr);
        }

        // --- Polygon ---
        NodeList polygonNodes = obj.getElementsByTagName("polygon");
        if (polygonNodes.getLength() > 0) {
            String pointsStr = ((Element) polygonNodes.item(0)).getAttribute("points");
            return buildPolygonShape(x, y, rotation, pointsStr, sf);
        }

        // --- Ellipse ---
        NodeList ellipseNodes = obj.getElementsByTagName("ellipse");
        if (ellipseNodes.getLength() > 0) {
            String wAttr = obj.getAttribute("width");
            String hAttr = obj.getAttribute("height");
            if (wAttr.isEmpty() || hAttr.isEmpty()) return null;
            double w = Double.parseDouble(wAttr) * sf;
            double h = Double.parseDouble(hAttr) * sf;
            return buildEllipseShape(x, y, w, h, rotation);
        }

        // --- Polyline — stroked into a filled shape using a configurable thickness ---
        NodeList polylineNodes = obj.getElementsByTagName("polyline");
        if (polylineNodes.getLength() > 0) {
            String pointsStr = ((Element) polylineNodes.item(0)).getAttribute("points");
            // Optional custom thickness Tiled property (in map pixels, scaled to game pixels)
            String thickAttr = getObjectProperty(obj, "thickness");
            float thickness = (thickAttr != null && !thickAttr.isEmpty())
                ? (float)(Double.parseDouble(thickAttr) * sf)
                : 6f; // default: 6 game pixels
            return buildPolylineShape(x, y, rotation, pointsStr, sf, thickness);
        }

        // --- Rectangle (default Tiled object type) ---
        String wAttr = obj.getAttribute("width");
        String hAttr = obj.getAttribute("height");
        if (wAttr.isEmpty() || hAttr.isEmpty()) {
            System.out.println("Skipping collision object #" + obj.getAttribute("id") + " (point — no area)");
            return null;
        }
        double w = Double.parseDouble(wAttr) * sf;
        double h = Double.parseDouble(hAttr) * sf;
        return buildRectShape(x, y, w, h, rotation);
    }

    /**
     * Read a custom Tiled property value from an object element.
     * Returns null if the property is not present.
     */
    private String getObjectProperty(Element obj, String propName) {
        NodeList propsList = obj.getElementsByTagName("properties");
        if (propsList.getLength() == 0) return null;
        NodeList props = ((Element) propsList.item(0)).getElementsByTagName("property");
        for (int i = 0; i < props.getLength(); i++) {
            Element prop = (Element) props.item(i);
            if (propName.equals(prop.getAttribute("name"))) {
                String val = prop.getAttribute("value");
                return val.isEmpty() ? prop.getTextContent() : val;
            }
        }
        return null;
    }

    /**
     * Build a collision shape using the SHAPE from {@code template} but placed at
     * the world position of {@code posObj}. Supports polygon and rectangle templates.
     * This is used by the collisionTemplate property system.
     */
    private Shape parseCollisionObjectAtPosition(Element template, Element posObj) {
        double sf = (double) tileSize / mapTileSize;

        String xAttr = posObj.getAttribute("x");
        String yAttr = posObj.getAttribute("y");
        if (xAttr.isEmpty() || yAttr.isEmpty()) return null;
        double x = Double.parseDouble(xAttr) * sf + mapOffsetPixelsX;
        double y = Double.parseDouble(yAttr) * sf + mapOffsetPixelsY;

        double rotation = 0;
        String rotAttr = template.getAttribute("rotation");
        if (!rotAttr.isEmpty()) rotation = Double.parseDouble(rotAttr);

        // Polygon template
        NodeList polygonNodes = template.getElementsByTagName("polygon");
        if (polygonNodes.getLength() > 0) {
            String pointsStr = ((Element) polygonNodes.item(0)).getAttribute("points");
            return buildPolygonShape(x, y, rotation, pointsStr, sf);
        }

        // Rectangle template
        String wAttr = template.getAttribute("width");
        String hAttr = template.getAttribute("height");
        if (!wAttr.isEmpty() && !hAttr.isEmpty()) {
            double w = Double.parseDouble(wAttr) * sf;
            double h = Double.parseDouble(hAttr) * sf;
            return buildRectShape(x, y, w, h, rotation);
        }

        return null;
    }

    private Shape buildRectShape(double x, double y, double w, double h, double rotation) {
        if (rotation == 0) {
            return new Rectangle2D.Double(x, y, w, h);
        }
        // Tiled rotates around the object's origin (top-left)
        AffineTransform at = new AffineTransform();
        at.translate(x, y);
        at.rotate(Math.toRadians(rotation));
        return at.createTransformedShape(new Rectangle2D.Double(0, 0, w, h));
    }

    private Shape buildEllipseShape(double x, double y, double w, double h, double rotation) {
        if (rotation == 0) {
            return new Ellipse2D.Double(x, y, w, h);
        }
        AffineTransform at = new AffineTransform();
        at.translate(x, y);
        at.rotate(Math.toRadians(rotation));
        return at.createTransformedShape(new Ellipse2D.Double(0, 0, w, h));
    }

    private Shape buildPolylineShape(double x, double y, double rotation, String pointsStr, double sf, float thickness) {
        String[] pairs = pointsStr.trim().split("\\s+");
        Path2D.Double line = new Path2D.Double();
        for (int i = 0; i < pairs.length; i++) {
            String[] coords = pairs[i].split(",");
            double px = Double.parseDouble(coords[0]) * sf;
            double py = Double.parseDouble(coords[1]) * sf;
            if (i == 0) line.moveTo(px, py);
            else        line.lineTo(px, py);
        }
        // Convert open path to a filled area by stroking it
        Shape stroked = new BasicStroke(thickness, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
                .createStrokedShape(line);
        AffineTransform at = new AffineTransform();
        at.translate(x, y);
        if (rotation != 0) at.rotate(Math.toRadians(rotation));
        return new Area(at.createTransformedShape(stroked));
    }

    private Shape buildPolygonShape(double x, double y, double rotation, String pointsStr, double sf) {
        String[] pairs = pointsStr.trim().split("\\s+");
        Path2D.Double poly = new Path2D.Double();
        for (int i = 0; i < pairs.length; i++) {
            String[] coords = pairs[i].split(",");
            double px = Double.parseDouble(coords[0]) * sf;
            double py = Double.parseDouble(coords[1]) * sf;
            if (i == 0) poly.moveTo(px, py);
            else poly.lineTo(px, py);
        }
        poly.closePath();

        AffineTransform at = new AffineTransform();
        at.translate(x, y);
        if (rotation != 0) {
            at.rotate(Math.toRadians(rotation));
        }
        return at.createTransformedShape(poly);
    }

    // OPTIMIZATION: Direct GID-indexed arrays for O(1) tile lookups (replaces per-tile linear tileset scans)
    private Tile[]    gidToTile;
    private int[]     gidToRenderOrder;
    private boolean[] gidToDepthSort;
    private boolean[] gidToForeground;
    private boolean[] gidToBackground;
    private int[]     gidToSortYOffset;
    public  boolean[] gidToReflectsLight;

    // OPTIMIZATION: Cache whether any image layer is marked foreground — avoids O(n) scan every frame in drawForeground
    private boolean hasFgImagesCache = false;
    private boolean hasFgImagesCacheValid = false;

    // OPTIMIZATION: Dirty tracking — skip rebuild when viewport is identical to last frame
    private int lastVisMinCol = -99, lastVisMaxCol = -99;
    private int lastVisMinRow = -99, lastVisMaxRow = -99;
    private int lastCamWorldX = Integer.MIN_VALUE, lastCamWorldY = Integer.MIN_VALUE;

    // OPTIMIZATION: Reusable pool of VisibleTileDraw objects to avoid GC pressure
    private final ArrayList<VisibleTileDraw> tileDrawPool = new ArrayList<>();
    private int poolIndex = 0;

    private VisibleTileDraw getPooledTileDraw() {
        if (poolIndex < tileDrawPool.size()) {
            return tileDrawPool.get(poolIndex++);
        }
        VisibleTileDraw vtd = new VisibleTileDraw();
        tileDrawPool.add(vtd);
        poolIndex++;
        return vtd;
    }

    public void prepareVisibleTiles() {
        int cameraWorldX = gp.player.worldX - gp.player.screenX;
        int cameraWorldY = gp.player.worldY - gp.player.screenY;

        // Compute visible tile range
        int extraMargin = tileSize * 2; // load a few extra tiles off-screen to prevent pop-in when moving
        int minCol = Math.max(0, (cameraWorldX - extraMargin) / tileSize);
        int maxCol = Math.min(gp.maxWorldCol - 1, (cameraWorldX + gp.screenWidth  + extraMargin) / tileSize);
        int minRow = Math.max(0, (cameraWorldY - extraMargin) / tileSize);
        int maxRow = Math.min(gp.maxWorldRow - 1, (cameraWorldY + gp.screenHeight + extraMargin) / tileSize);

        boolean rangeChanged = (minCol != lastVisMinCol || maxCol != lastVisMaxCol
                             || minRow != lastVisMinRow || maxRow != lastVisMaxRow);
        boolean camMoved     = (cameraWorldX != lastCamWorldX || cameraWorldY != lastCamWorldY);

        // OPTIMIZATION 1: Nothing changed at all — reuse everything (player standing still)
        if (!rangeChanged && !camMoved) return;

        lastCamWorldX = cameraWorldX;
        lastCamWorldY = cameraWorldY;

        if (!rangeChanged) {
            // OPTIMIZATION 2: Same set of tiles, camera moved sub-tile.
            // Just recalculate screenX/Y from stored world coords — skip full rebuild AND sort.
            int bgSize = backgroundVisibleTiles.size();
            for (int i = 0; i < bgSize; i++) {
                VisibleTileDraw vtd = backgroundVisibleTiles.get(i);
                vtd.screenX = Math.round(vtd.worldX      - cameraWorldX * vtd.parallaxX);
                vtd.screenY = Math.round(vtd.screenBaseY - cameraWorldY * vtd.parallaxY);
            }
            int dpSize = depthVisibleTiles.size();
            for (int i = 0; i < dpSize; i++) {
                VisibleTileDraw vtd = depthVisibleTiles.get(i);
                vtd.screenX = Math.round(vtd.worldX      - cameraWorldX * vtd.parallaxX);
                vtd.screenY = Math.round(vtd.screenBaseY - cameraWorldY * vtd.parallaxY);
            }
            int fgSize = foregroundVisibleTiles.size();
            for (int i = 0; i < fgSize; i++) {
                VisibleTileDraw vtd = foregroundVisibleTiles.get(i);
                vtd.screenX = Math.round(vtd.worldX      - cameraWorldX * vtd.parallaxX);
                vtd.screenY = Math.round(vtd.screenBaseY - cameraWorldY * vtd.parallaxY);
            }
            return;
        }

        // OPTIMIZATION 3: Full rebuild — but using O(1) direct GID arrays instead of linear tileset scans
        lastVisMinCol = minCol; lastVisMaxCol = maxCol;
        lastVisMinRow = minRow; lastVisMaxRow = maxRow;

        backgroundVisibleTiles.clear();
        depthVisibleTiles.clear();
        foregroundVisibleTiles.clear();
        poolIndex = 0;

        // Use pre-computed firstAutoDepthLayerIdx — layers at or above it
        // auto-promote to depth-sort so they interleave correctly with entities.

        for (int layerIndex = 0; layerIndex < mapLayers.size(); layerIndex++) {
            int[][] map = mapLayers.get(layerIndex);
            byte[][] flipMap = layerIndex < mapFlipLayers.size() ? mapFlipLayers.get(layerIndex) : null;
            float px      = getLayerFloat(layerParallaxX, layerIndex, 1.0f);
            float py      = getLayerFloat(layerParallaxY, layerIndex, 1.0f);
            float opacity = getLayerFloat(layerOpacity, layerIndex, 1.0f);
            Color tint    = layerIndex < layerTint.size()      ? layerTint.get(layerIndex)       : null;
            float offX    = getLayerFloat(layerOffsetX, layerIndex, 0f);
            float offY    = getLayerFloat(layerOffsetY, layerIndex, 0f);
            int glIdx     = layerIndex < layerGlobalIndex.size() ? layerGlobalIndex.get(layerIndex) : layerIndex;

            // === Resolve layer bucket using explicit properties ONLY ===
            // Priority: render enum > legacy booleans > default (background)
            // No name-based heuristics. No auto-promote.
            String bucket = layerIndex < layerRenderBucket.size() ? layerRenderBucket.get(layerIndex) : null;
            boolean forceForeground = false;
            boolean forceBackground = false;
            boolean forceDepthSort  = false;

            if ("foreground".equals(bucket)) {
                forceForeground = true;
            } else if ("background".equals(bucket)) {
                forceBackground = true;
            } else if ("depth".equals(bucket)) {
                forceDepthSort = true;
            } else if (bucket == null || "auto".equals(bucket)) {
                // No render enum set — check legacy booleans only
                boolean legacyBg = layerIndex < layerBackground.size() && layerBackground.get(layerIndex);
                boolean legacyDs = layerIndex < layerDepthSort.size() && layerDepthSort.get(layerIndex);
                boolean legacyFg = layerIndex < layerForeground.size() && layerForeground.get(layerIndex);

                if (legacyFg)      forceForeground = true;
                else if (legacyBg) forceBackground = true;
                else if (legacyDs) forceDepthSort  = true;
                // else: no property → defaults to background
            }

            for (int worldRow = minRow; worldRow <= maxRow; worldRow++) {
                for (int worldCol = minCol; worldCol <= maxCol; worldCol++) {
                    int gid = map[worldCol][worldRow];
                    if (gid == 0) continue;

                    // Resolve animation frame — substitute animated GID if present
                    int drawGid = gid;
                    TileAnimation anim = gidToAnimation.get(gid);
                    if (anim != null) {
                        drawGid = anim.frameLocalIds[anim.currentFrame];
                        // drawGid here is the absolute GID of the current frame image
                    }

                    // O(1) direct lookup — no linear tileset scan
                    Tile currentTile = (gidToTile != null && drawGid < gidToTile.length) ? gidToTile[drawGid] : null;
                    if (currentTile == null || currentTile.image == null) continue;

                    int worldX      = worldCol * tileSize;
                    int worldY      = worldRow * tileSize;
                    int screenBaseY = worldY - currentTile.drawOffsetY;

                    VisibleTileDraw visibleTile = getPooledTileDraw();
                    visibleTile.image       = currentTile.image;
                    visibleTile.baseGid     = gid; // OPTIMIZATION: remember original GID for in-place animation patching
                    visibleTile.worldX      = worldX + Math.round(offX);
                    visibleTile.screenBaseY = screenBaseY + Math.round(offY);
                    visibleTile.screenX     = Math.round(worldX + offX - cameraWorldX * px);
                    visibleTile.screenY     = Math.round(screenBaseY + offY - cameraWorldY * py);
                    visibleTile.parallaxX   = px;
                    visibleTile.parallaxY   = py;
                    visibleTile.baseWorldY  = worldY;
                    visibleTile.layerIndex  = layerIndex;
                    visibleTile.worldCol    = worldCol;
                    visibleTile.worldRow    = worldRow;
                    visibleTile.opacity     = opacity;
                    visibleTile.tint        = tint;
                    visibleTile.flipFlags   = (flipMap != null) ? flipMap[worldCol][worldRow] : 0;
                    // Use global layer index so Tiled layer order is always respected,
                    // even when image layers or group layers are interleaved
                    visibleTile.renderOrder = glIdx;

                    // sortY = bottom edge of this tile + sortYOffset.
                    // sortYOffset values are in GAME pixels (not Tiled pixels) — no scaling needed.
                    // For multi-row structures, set sortYOffset on the TOP-row tiles
                    // so their sortY matches the bottom row's sortY.
                    int sortYOff = (gidToSortYOffset != null && gid < gidToSortYOffset.length) ? gidToSortYOffset[gid] : 0;
                    visibleTile.sortY = worldY + tileSize + sortYOff;

                    // Per-tile bucket overrides — read from gid lookup arrays
                    boolean isTileForeground = (gidToForeground != null && gid < gidToForeground.length && gidToForeground[gid]);
                    boolean isTileBackground = (gidToBackground != null && gid < gidToBackground.length && gidToBackground[gid]);
                    boolean isTileDepth      = (gidToDepthSort  != null && gid < gidToDepthSort.length  && gidToDepthSort[gid]);

                    // Classification priority:
                    // 1. Per-tile explicit properties always override the layer bucket.
                    //    foreground > background > depthSort (per-tile)
                    // 2. If no per-tile override, use the layer bucket.
                    // 3. Default → background.
                    if (isTileForeground) {
                        foregroundVisibleTiles.add(visibleTile);
                    } else if (isTileBackground) {
                        backgroundVisibleTiles.add(visibleTile);
                    } else if (isTileDepth) {
                        depthVisibleTiles.add(visibleTile);
                    } else if (forceForeground) {
                        foregroundVisibleTiles.add(visibleTile);
                    } else if (forceBackground) {
                        backgroundVisibleTiles.add(visibleTile);
                    } else if (forceDepthSort) {
                        depthVisibleTiles.add(visibleTile);
                    } else {
                        backgroundVisibleTiles.add(visibleTile);
                    }
                }
            }
        }

        backgroundVisibleTiles.sort(backgroundTileComparator);
        depthVisibleTiles.sort(depthTileComparator);
        foregroundVisibleTiles.sort(backgroundTileComparator);
    }

    // ---- Tile draw helper: handles flip transforms, layer opacity, and tint ----
    private void drawTile(Graphics2D g2, VisibleTileDraw vt) {
        // Apply layer opacity composite
        Composite origComposite = null;
        if (vt.opacity < 0.999f) {
            origComposite = g2.getComposite();
            g2.setComposite(cachedAlpha(vt.opacity));
        }

        // Draw the tile (with optional flip transform)
        if (vt.flipFlags == 0) {
            g2.drawImage(vt.image, vt.screenX, vt.screenY, null);
        } else {
            AffineTransform at = buildFlipTransform(vt.flipFlags, vt.screenX, vt.screenY,
                    vt.image.getWidth(), vt.image.getHeight());
            g2.drawImage(vt.image, at, null);
        }

        // Apply tint overlay (multiply-like, using SRC_OVER with low alpha)
        if (vt.tint != null) {
            Composite c = g2.getComposite();
            g2.setComposite(cachedAlpha(0.4f * vt.opacity));
            g2.setColor(vt.tint);
            g2.fillRect(vt.screenX, vt.screenY, vt.image.getWidth(), vt.image.getHeight());
            g2.setComposite(c);
        }

        if (origComposite != null) g2.setComposite(origComposite);
    }

    // OPTIMIZATION: Reusable AffineTransform — avoids allocation per flipped tile per frame
    private final AffineTransform reusableFlipAT = new AffineTransform();

    /**
     * Build an AffineTransform for the 7 Tiled flip/rotate combinations.
     * H  = mirror left/right
     * V  = mirror top/bottom
     * D  = anti-diagonal transpose (swap x,y — used combined with H or V to express rotations)
     * D+H = 90° CW,  D+V = 90° CCW,  H+V = 180°,  D+H+V = 270° (or 90° CCW after flip)
     */
    private AffineTransform buildFlipTransform(byte flags, int sx, int sy, int w, int h) {
        boolean fH = (flags & 1) != 0;
        boolean fV = (flags & 2) != 0;
        boolean fD = (flags & 4) != 0;
        // Matrix elements: [m00 m10 m01 m11 tx ty]  (column-major AffineTransform order)
        if ( fD &&  fH && !fV) reusableFlipAT.setTransform( 0,  1, -1,  0, sx + w, sy);         // 90° CW
        else if ( fD && !fH &&  fV) reusableFlipAT.setTransform( 0, -1,  1,  0, sx,     sy + h);      // 90° CCW
        else if (!fD &&  fH &&  fV) reusableFlipAT.setTransform(-1,  0,  0, -1, sx + w, sy + h);      // 180°
        else if ( fD &&  fH &&  fV) reusableFlipAT.setTransform( 0, -1, -1,  0, sx + w, sy + h);      // 270° CW
        else if (!fD &&  fH && !fV) reusableFlipAT.setTransform(-1,  0,  0,  1, sx + w, sy);          // flip H
        else if (!fD && !fH &&  fV) reusableFlipAT.setTransform( 1,  0,  0, -1, sx,     sy + h);      // flip V
        else /* fD && !fH && !fV */ reusableFlipAT.setTransform( 0,  1,  1,  0, sx,     sy);          // transpose
        return reusableFlipAT;
    }

    public void drawBackground(Graphics2D g2) {
        // Fill void areas outside the map with the map's background color
        g2.setColor(mapBackgroundColor);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // Interleave non-foreground image layers with background tiles based on their
        // global layer index, so image layers appear at the correct Tiled stack position.
        int imgCursor = 0;
        int numImg = imageLayers.size();
        int cameraWorldX = gp.player.worldX - gp.player.screenX;
        int cameraWorldY = gp.player.worldY - gp.player.screenY;
        int lastDrawnOrder = Integer.MIN_VALUE;

        for (int i = 0, n = backgroundVisibleTiles.size(); i < n; i++) {
            VisibleTileDraw vtd = backgroundVisibleTiles.get(i);
            // When crossing into a new renderOrder group, draw pending image layers
            if (vtd.renderOrder != lastDrawnOrder) {
                while (imgCursor < numImg) {
                    ImageLayerData ild = imageLayers.get(imgCursor);
                    if (ild.foreground) { imgCursor++; continue; }
                    if (ild.globalLayerIndex >= vtd.renderOrder) break;
                    drawSingleImageLayer(g2, ild, cameraWorldX, cameraWorldY);
                    imgCursor++;
                }
                lastDrawnOrder = vtd.renderOrder;
            }
            drawTile(g2, vtd);
        }

        // Draw remaining non-foreground image layers
        while (imgCursor < numImg) {
            ImageLayerData ild = imageLayers.get(imgCursor++);
            if (!ild.foreground) {
                drawSingleImageLayer(g2, ild, cameraWorldX, cameraWorldY);
            }
        }

        drawPathOverlay(g2);
    }

    public void drawForeground(Graphics2D g2) {
        // Fast path: no foreground image layers — just draw tiles
        if (!hasFgImagesCacheValid) {
            hasFgImagesCache = false;
            for (int j = 0; j < imageLayers.size(); j++) {
                if (imageLayers.get(j).foreground) { hasFgImagesCache = true; break; }
            }
            hasFgImagesCacheValid = true;
        }
        if (!hasFgImagesCache) {
            for (int i = 0, n = foregroundVisibleTiles.size(); i < n; i++) {
                drawTile(g2, foregroundVisibleTiles.get(i));
            }
            return;
        }

        // Interleave foreground image layers with foreground tiles by globalLayerIndex
        int cameraWorldX = gp.player.worldX - gp.player.screenX;
        int cameraWorldY = gp.player.worldY - gp.player.screenY;
        int imgCursor = 0;
        int numImg = imageLayers.size();
        int lastOrder = Integer.MIN_VALUE;

        for (int i = 0, n = foregroundVisibleTiles.size(); i < n; i++) {
            VisibleTileDraw vtd = foregroundVisibleTiles.get(i);
            if (vtd.renderOrder != lastOrder) {
                while (imgCursor < numImg) {
                    ImageLayerData ild = imageLayers.get(imgCursor);
                    if (!ild.foreground) { imgCursor++; continue; }
                    if (ild.globalLayerIndex >= vtd.renderOrder) break;
                    drawSingleImageLayer(g2, ild, cameraWorldX, cameraWorldY);
                    imgCursor++;
                }
                lastOrder = vtd.renderOrder;
            }
            drawTile(g2, vtd);
        }
        // Draw remaining foreground image layers
        while (imgCursor < numImg) {
            ImageLayerData ild = imageLayers.get(imgCursor++);
            if (ild.foreground) {
                drawSingleImageLayer(g2, ild, cameraWorldX, cameraWorldY);
            }
        }
    }

    public int getDepthTileCount() {
        return depthVisibleTiles.size();
    }

    public int getDepthTileSortY(int index) {
        return depthVisibleTiles.get(index).sortY;
    }

    public void drawDepthTile(Graphics2D g2, int index) {
        drawTile(g2, depthVisibleTiles.get(index));
    }

    /** Draw a single image layer at its world position relative to the camera. */
    private void drawSingleImageLayer(Graphics2D g2, ImageLayerData ild,
                                       int cameraWorldX, int cameraWorldY) {
        if (ild.image == null) return;
        int sx = Math.round(ild.worldX - cameraWorldX * ild.parallaxX);
        int sy = Math.round(ild.worldY - cameraWorldY * ild.parallaxY);
        Composite orig = null;
        if (ild.opacity < 0.999f) {
            orig = g2.getComposite();
            g2.setComposite(cachedAlpha(ild.opacity));
        }
        g2.drawImage(ild.image, sx, sy, null);
        if (ild.tintColor != null) {
            Composite c = g2.getComposite();
            g2.setComposite(cachedAlpha(0.4f * ild.opacity));
            g2.setColor(ild.tintColor);
            g2.fillRect(sx, sy, ild.image.getWidth(), ild.image.getHeight());
            g2.setComposite(c);
        }
        if (orig != null) g2.setComposite(orig);
    }

    private void drawPathOverlay(Graphics2D g2) {
        if ( gp.drawPath ) {
            int playerWorldX = gp.player.worldX;
            int playerWorldY = gp.player.worldY;
            g2.setColor(new java.awt.Color(255, 0, 0, 128));
            for ( int i = 0 ; i < gp.pFinder.pathList.size() ; i++ ) {
                int worldX = gp.pFinder.pathList.get(i).col * tileSize;
                int worldY = gp.pFinder.pathList.get(i).row * tileSize;
                int pathScreenX = worldX - playerWorldX + gp.player.screenX;
                int pathScreenY = worldY - playerWorldY + gp.player.screenY;

                g2.fillRect(pathScreenX, pathScreenY, tileSize, tileSize);
            }
        }
    }

    /**
     * Called once per game update tick (60 Hz). Advances animated-tile frame timers.
     *
     * OPTIMIZATION: Instead of forcing a full visible-tile rebuild when any animation
     * advances (old behaviour), patch the `image` field of already-visible animated tiles
     * in place. Water animating at 10 FPS no longer triggers 10 full rebuilds per second.
     */
    public void update() {
        if (!hasAnimatedTiles) return;
        // 60 UPS → each tick ≈ 16.67 ms; we approximate as 17 ms per tick.
        final int MS_PER_TICK = 17;
        boolean anyFrameChanged = false;
        for (TileAnimation anim : gidToAnimation.values()) {
            anim.timerMs += MS_PER_TICK;
            int frameDur = anim.frameDurationsMs[anim.currentFrame];
            if (anim.timerMs >= frameDur) {
                anim.timerMs -= frameDur;
                anim.currentFrame = (anim.currentFrame + 1) % anim.frameLocalIds.length;
                anyFrameChanged = true;
            }
        }
        if (anyFrameChanged) {
            patchAnimatedTileImages(backgroundVisibleTiles);
            patchAnimatedTileImages(depthVisibleTiles);
            patchAnimatedTileImages(foregroundVisibleTiles);
        }
    }

    /**
     * Walk a visible-tile bucket and update the `image` reference of any tile whose
     * baseGid is animated. O(visible tiles) — no sort, no collision re-check, no
     * per-layer iteration over the full world rect.
     */
    private void patchAnimatedTileImages(ArrayList<VisibleTileDraw> list) {
        if (gidToAnimation == null || gidToAnimation.isEmpty()) return;
        for (int i = 0, n = list.size(); i < n; i++) {
            VisibleTileDraw vtd = list.get(i);
            TileAnimation anim = gidToAnimation.get(vtd.baseGid);
            if (anim == null) continue;
            int drawGid = anim.frameLocalIds[anim.currentFrame];
            if (gidToTile != null && drawGid >= 0 && drawGid < gidToTile.length) {
                Tile t = gidToTile[drawGid];
                if (t != null && t.image != null) vtd.image = t.image;
            }
        }
    }

    private void rebuildTileLookup() {
        // Find max GID to size the direct lookup arrays
        int maxGIDValue = 0;
        for (Tileset ts : tilesets) {
            int tsMax = ts.firstGID + ts.tileCount - 1;
            if (tsMax > maxGIDValue) maxGIDValue = tsMax;
        }

        // Build O(1) direct GID lookup arrays — eliminates per-tile linear tileset scan
        gidToTile          = new Tile[maxGIDValue + 1];
        gidToRenderOrder   = new int[maxGIDValue + 1];
        gidToDepthSort     = new boolean[maxGIDValue + 1];
        gidToForeground    = new boolean[maxGIDValue + 1];
        gidToBackground    = new boolean[maxGIDValue + 1];
        gidToSortYOffset   = new int[maxGIDValue + 1];
        gidToReflectsLight = new boolean[maxGIDValue + 1];
        for (Tileset ts : tilesets) {
            for (int i = 0; i < ts.tileCount && i < ts.tiles.length; i++) {
                int gid = ts.firstGID + i;
                gidToTile[gid]        = ts.tiles[i];
                gidToRenderOrder[gid] = ts.renderOrder;
                // Depth-sort is determined ONLY by per-tile properties (depthSort, sortYOffset),
                // NOT by tileset-level flags. This ensures Tiled layer order is the authority;
                // tileset heuristics no longer pull ground/shadow tiles into the depth bucket.
                gidToDepthSort[gid]   = false;
                gidToForeground[gid]  = ts.foreground;
                if (ts.tiles[i] != null) {
                    if (ts.tiles[i].depthSort) gidToDepthSort[gid] = true;
                    if (ts.tiles[i].foreground) gidToForeground[gid] = true;
                    if (ts.tiles[i].background) { gidToDepthSort[gid] = false; gidToForeground[gid] = false; gidToBackground[gid] = true; }
                    gidToSortYOffset[gid] = ts.tiles[i].sortYOffset;
                    // Tiles with sortYOffset automatically become depth-sorted
                    if (ts.tiles[i].sortYOffset != 0 && !ts.tiles[i].background) {
                        gidToDepthSort[gid] = true;
                    }
                    if (ts.tiles[i].reflectsLight) gidToReflectsLight[gid] = true;
                }
            }
        }

        // Existing flat lookup (kept for external callers)
        int totalTiles = 0;
        for (Tileset ts : tilesets) totalTiles += ts.tiles.length;
        tile = new Tile[totalTiles];
        int index = 0;
        for (Tileset ts : tilesets) {
            for (Tile singleTile : ts.tiles) tile[index++] = singleTile;
        }

        // Invalidate dirty tracking so the next prepareVisibleTiles does a full rebuild
        lastVisMinCol = -99;
        hasFgImagesCacheValid = false;
    }

    private Tileset getTilesetForGID(int gid) {
        for (int i = tilesets.size() - 1; i >= 0; i--) {
            Tileset ts = tilesets.get(i);
            int index = gid - ts.firstGID;
            if (index >= 0 && index < ts.tileCount) {
                return ts;
            }
        }
        return null;
    }

    private boolean isWaterTileset(String name, String path) {
        String normalized = (name + " " + path).toLowerCase();
        return normalized.contains("water");
    }

    /**
     * Sample the center pixel colour of the tile image for a given GID.
     * Returns the Color of the center pixel, or null if the GID is invalid or has no image.
     * Samples the actual tile art for accurate terrain representation.
     */
    public Color sampleTileColor(int gid) {
        if (gid <= 0 || gidToTile == null || gid >= gidToTile.length) return null;
        Tile t = gidToTile[gid];
        if (t == null || t.image == null) return null;
        int cx = t.image.getWidth() / 2;
        int cy = t.image.getHeight() / 2;
        int argb = t.image.getRGB(cx, cy);
        // Skip fully transparent pixels
        if ((argb >>> 24) < 10) return null;
        return new Color(argb, true);
    }

    /**
     * Check if any configured collision tile layer has a non-empty tile at the given column/row.
     * Only layers whose name is in collisionTileLayers are checked.
     * By default collisionTileLayers is empty, so this returns false unless you add layer names.
     * Example: collisionTileLayers.add("water") to make the water tile layer block movement.
     */
    public boolean isTileBlocking(int col, int row) {
        if (col < 0 || col >= gp.maxWorldCol || row < 0 || row >= gp.maxWorldRow) return true;
        if (collisionTileLayers.isEmpty()) return false;
        for (int l = 0; l < mapLayers.size(); l++) {
            if (l >= layerNames.size()) continue;
            if (!collisionTileLayers.contains(layerNames.get(l))) continue;
            int gid = mapLayers.get(l)[col][row];
            if (gid != 0) return true;
        }
        return false;
    }

    private boolean isGrassTileset(Tileset tileset) {
        String normalized = (tileset.name + " " + tileset.sourcePath).toLowerCase();
        return normalized.contains("grass");
    }

    private boolean isSolidGroundTileset(Tileset tileset) {
        String normalized = (tileset.name + " " + tileset.sourcePath).toLowerCase();
        return normalized.contains("tileset2") || normalized.contains("fence") || normalized.contains("stone");
    }

    /**
     * Get tileset render order from an explicit Tiled property only.
     * No name-based heuristics. Returns 0 if no explicit property is set,
     * since render order is now driven by globalLayerIndex (Tiled layer stacking).
     */
    private int getTilesetRenderOrder(Element tilesetElement, String resourcePath) {
        int explicitRenderOrder = getIntProperty(tilesetElement, "renderOrder", Integer.MIN_VALUE);
        if (explicitRenderOrder != Integer.MIN_VALUE) {
            return explicitRenderOrder;
        }
        return 0;
    }

    private int getIntProperty(Element element, String propertyName, int defaultValue) {
        String value = getStringProperty(element, propertyName, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get tileset depthSort flag from explicit Tiled property only.
     * No name-based heuristics. Returns false if no property is set.
     */
    private boolean getTilesetDepthSort(Element tilesetElement, String resourcePath) {
        String value = getStringProperty(tilesetElement, "depthSort", null);
        if (value == null || value.isBlank()) {
            return false;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private boolean getTilesetForeground(Element tilesetElement) {
        String value = getStringProperty(tilesetElement, "foreground", null);
        if (value == null || value.isBlank()) {
            return false;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Reads per-tile custom properties from a <tileset> XML element and applies them
     * to the already-loaded Tileset's Tile objects.
     * Currently supports: sortYOffset (int) — shifts the depth-sort Y for that tile.
     */
    private void applyPerTileProperties(Tileset ts, Element tilesetElement) {
        NodeList children = tilesetElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element tileEl = (Element) children.item(i);
            if (!"tile".equals(tileEl.getTagName())) continue;
            String idStr = tileEl.getAttribute("id");
            if (idStr == null || idStr.isBlank()) continue;
            try {
                int tileId = Integer.parseInt(idStr.trim());
                if (tileId < 0 || tileId >= ts.tiles.length || ts.tiles[tileId] == null) continue;
                int sortYOff = getIntProperty(tileEl, "sortYOffset", 0);
                if (sortYOff != 0) ts.tiles[tileId].sortYOffset = sortYOff;

                String reflStr = getStringProperty(tileEl, "reflectsLight", null);
                if (reflStr != null) ts.tiles[tileId].reflectsLight = Boolean.parseBoolean(reflStr.trim());

                // Read unified "render" enum (RenderBucket) — takes priority over legacy booleans
                String renderProp = getStringProperty(tileEl, "render", null);
                if (renderProp != null && !renderProp.isBlank()) {
                    String r = renderProp.trim().toLowerCase();
                    if ("foreground".equals(r)) { ts.tiles[tileId].foreground = true; ts.tiles[tileId].background = false; ts.tiles[tileId].depthSort = false; }
                    else if ("background".equals(r)) { ts.tiles[tileId].background = true; ts.tiles[tileId].foreground = false; ts.tiles[tileId].depthSort = false; }
                    else if ("depth".equals(r)) { ts.tiles[tileId].depthSort = true; ts.tiles[tileId].foreground = false; ts.tiles[tileId].background = false; }
                    // "auto" → leave defaults
                } else {
                    // Legacy per-boolean properties
                    String fg = getStringProperty(tileEl, "foreground", null);
                    if (fg != null) ts.tiles[tileId].foreground = Boolean.parseBoolean(fg.trim());
                    String bg = getStringProperty(tileEl, "background", null);
                    if (bg != null) ts.tiles[tileId].background = Boolean.parseBoolean(bg.trim());
                    String ds = getStringProperty(tileEl, "depthSort", null);
                    if (ds != null) ts.tiles[tileId].depthSort = Boolean.parseBoolean(ds.trim());
                }

                // ---- Parse <animation> block ----
                NodeList animNodes = tileEl.getElementsByTagName("animation");
                if (animNodes.getLength() > 0) {
                    Element animEl = (Element) animNodes.item(0);
                    NodeList frameNodes = animEl.getElementsByTagName("frame");
                    if (frameNodes.getLength() > 0) {
                        int numFrames = frameNodes.getLength();
                        int[] frameAbsGids = new int[numFrames];
                        int[] frameDurs    = new int[numFrames];
                        boolean valid = true;
                        for (int f = 0; f < numFrames; f++) {
                            Element frameEl = (Element) frameNodes.item(f);
                            String ftid = frameEl.getAttribute("tileid");
                            String fdur = frameEl.getAttribute("duration");
                            if (ftid.isBlank() || fdur.isBlank()) { valid = false; break; }
                            int localFrameId = Integer.parseInt(ftid.trim());
                            // Convert local tile id to absolute GID for direct lookup in gidToTile
                            frameAbsGids[f] = ts.firstGID + localFrameId;
                            frameDurs[f]    = Integer.parseInt(fdur.trim());
                        }
                        if (valid) {
                            TileAnimation anim = new TileAnimation();
                            anim.frameLocalIds    = frameAbsGids; // stored as abs GIDs for O(1) image lookup
                            anim.frameDurationsMs = frameDurs;
                            // Key = absolute GID of the "base" tile (the one stored in the map layer)
                            gidToAnimation.put(ts.firstGID + tileId, anim);
                            hasAnimatedTiles = true;
                        }
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private String getStringProperty(Element element, String propertyName, String defaultValue) {
        // Only search <property> elements inside a direct <properties> child —
        // NOT recursive, so per-tile properties don't leak up to the tileset level.
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int c = 0; c < children.getLength(); c++) {
            if (!(children.item(c) instanceof Element)) continue;
            Element child = (Element) children.item(c);
            if (!"properties".equals(child.getTagName())) continue;
            org.w3c.dom.NodeList props = child.getChildNodes();
            for (int p = 0; p < props.getLength(); p++) {
                if (!(props.item(p) instanceof Element)) continue;
                Element property = (Element) props.item(p);
                if (!"property".equals(property.getTagName())) continue;
                if (!propertyName.equals(property.getAttribute("name"))) continue;
                String value = property.getAttribute("value");
                if (value == null || value.isBlank()) {
                    value = property.getTextContent();
                }
                return value;
            }
        }
        return defaultValue;
    }

    /**
     * Extract the 3 Tiled flip bits from a raw GID long into a compact byte:
     *   bit 0 = FlipH (bit 31 of raw GID)
     *   bit 1 = FlipV (bit 30)
     *   bit 2 = FlipD / anti-diagonal (bit 29)
     * Returns 0 when there are no flip flags.
     */
    private static byte extractFlipFlags(long rawGid) {
        byte flags = 0;
        if ((rawGid & GID_FLIP_H) != 0) flags |= 1;
        if ((rawGid & GID_FLIP_V) != 0) flags |= 2;
        if ((rawGid & GID_FLIP_D) != 0) flags |= 4;
        return flags;
    }

    /**
     * Parse a Tiled tintcolor / tint hex string ("#rrggbb" or "#aarrggbb") into a Color.
     * Returns null when the string is empty or malformed.
     */
    private static Color parseTintColor(String hex) {
        if (hex == null || hex.isBlank()) return null;
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            if (clean.length() == 6) {
                int rgb = (int) Long.parseLong(clean, 16);
                return new Color(rgb);
            } else if (clean.length() == 8) {
                long argb = Long.parseLong(clean, 16);
                int a = (int) ((argb >> 24) & 0xFF);
                int r = (int) ((argb >> 16) & 0xFF);
                int g = (int) ((argb >>  8) & 0xFF);
                int b = (int)  (argb        & 0xFF);
                return new Color(r, g, b, a);
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private String toResourcePath(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }

        String normalized = source.replace('\\', '/');
        int resIndex = normalized.indexOf("res/");
        if (resIndex >= 0) {
            return "/" + normalized.substring(resIndex);
        }

        if (normalized.startsWith("../tiles/") || normalized.startsWith("tiles/")) {
            return "/res/" + normalized.replaceFirst("^(\\.\\./)+", "");
        }

        // Fallback: extract filename and try /res/tiles/<filename>
        int lastSlash = normalized.lastIndexOf('/');
        String filename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        if (!filename.isEmpty()) {
            System.out.println("Resolving external tileset '" + source + "' -> /res/tiles/" + filename);
            return "/res/tiles/" + filename;
        }

        return null;
    }

    private float getFloatAttribute(Element element, String attributeName, float defaultValue) {
        String value = element.getAttribute(attributeName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private float getLayerFloat(ArrayList<Float> values, int index, float defaultValue) {
        if (index < 0 || index >= values.size()) {
            return defaultValue;
        }
        Float value = values.get(index);
        return value != null ? value : defaultValue;
    }

    private Document parseXmlResource(String path) throws Exception {
        Document document = ResourceCache.loadXml(path);
        if (document == null) {
            System.out.println("CRITICAL: XML resource not found: " + path);
            return null;
        }
        return document;
    }
}

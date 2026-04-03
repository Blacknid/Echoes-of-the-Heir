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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import main.GamePanel;
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
    }
    /** Ordered list of image layers (parsed alongside tile layers, drawn behind background tiles). */
    public ArrayList<ImageLayerData> imageLayers = new ArrayList<>();

    // ---- Layer opacity / tint ----
    /** Per-layer opacity (1.0 = fully opaque). Same indexing as mapLayers. */
    public ArrayList<Float>  layerOpacity = new ArrayList<>();
    /** Per-layer tint color (null = none). Same indexing as mapLayers. */
    public ArrayList<Color> layerTint   = new ArrayList<>();

    // Tile scaling
    final int originalTileSize = 32;
    final int scale = 2;
    public final int tileSize = originalTileSize * scale;

    // Per-map TMX tile size (updated when loading a map; used for scaling)
    int mapTileSize = originalTileSize;
    // Actual map dimensions in tiles (read from TMX <map width/height>)
    public int currentMapCols = 100;
    public int currentMapRows = 100;
    // Pixel offset for infinite maps (after shifting chunks to start at 0,0)
    int mapOffsetPixelsX = 0;
    int mapOffsetPixelsY = 0;

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
    // (layerOpacity and layerTint declared above near ImageLayerData)

    // Collision shapes (from Tiled objectgroup layers — rectangles, rotated rects, polygons, ellipses)
    public ArrayList<Shape> collisionShapes = new ArrayList<>();
    // Bounding boxes for each shape (used by spatial grid for broad-phase)
    public ArrayList<Rectangle> collisionBounds = new ArrayList<>();

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
                if (imageElement == null) {
                    continue;
                }

                String source = imageElement.getAttribute("source");
                String resourcePath = toResourcePath(source);
                if (resourcePath == null) {
                    System.out.println("Skipping external tileset source: " + source);
                    continue;
                }

                addTileset(
                    resourcePath,
                    Integer.parseInt(tilesetElement.getAttribute("firstgid")),
                    Integer.parseInt(tilesetElement.getAttribute("tilewidth")),
                    Integer.parseInt(tilesetElement.getAttribute("tileheight")),
                    Integer.parseInt(tilesetElement.getAttribute("tilecount")),
                    Integer.parseInt(tilesetElement.getAttribute("columns")),
                    tilesetElement.getAttribute("name"),
                    getTilesetRenderOrder(tilesetElement, resourcePath),
                    getTilesetDepthSort(tilesetElement, resourcePath),
                    getTilesetForeground(tilesetElement)
                );
                applyPerTileProperties(tilesets.get(tilesets.size() - 1), tilesetElement);
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
            layerOpacity.clear();
            layerTint.clear();
            imageLayers.clear();

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

            NodeList layers = doc.getElementsByTagName("layer");
            for (int l = 0; l < layers.getLength(); l++) {
                Element layer = (Element) layers.item(l);
                String layerName = layer.getAttribute("name");
                Element data = (Element) layer.getElementsByTagName("data").item(0);
                float parallaxX = getFloatAttribute(layer, "parallaxx", 1.0f);
                float parallaxY = getFloatAttribute(layer, "parallaxy", 1.0f);
                float opacity   = getFloatAttribute(layer, "opacity", 1.0f);
                Color tint      = parseTintColor(layer.getAttribute("tintcolor"));

                boolean isBackground = false;
                boolean isDepthSort  = false;
                boolean isForeground = false;
                boolean isCollision  = false;
                NodeList layerProps = layer.getChildNodes();
                for (int lp = 0; lp < layerProps.getLength(); lp++) {
                    if (layerProps.item(lp) instanceof Element) {
                        Element child = (Element) layerProps.item(lp);
                        if ("properties".equals(child.getTagName())) {
                            NodeList propList = child.getElementsByTagName("property");
                            for (int pp = 0; pp < propList.getLength(); pp++) {
                                Element prop = (Element) propList.item(pp);
                                String propName = prop.getAttribute("name");
                                if ("background".equals(propName)) {
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
                    // Infinite map: parse each chunk and place tiles at offset position
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
                    // Normal flat CSV
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
                layerOpacity.add(opacity);
                layerTint.add(tint);
            }

            // ---- Parse <imagelayer> elements ----
            double sf = (double) tileSize / mapTileSize;
            NodeList imagelayerNodes = doc.getElementsByTagName("imagelayer");
            for (int i = 0; i < imagelayerNodes.getLength(); i++) {
                Element ilEl = (Element) imagelayerNodes.item(i);
                Element imgEl = (Element) ilEl.getElementsByTagName("image").item(0);
                if (imgEl == null) continue;
                String srcRaw = imgEl.getAttribute("source");
                String srcPath = toResourcePath(srcRaw);
                if (srcPath == null) {
                    System.out.println("Skipping imagelayer — cannot resolve source: " + srcRaw);
                    continue;
                }
                BufferedImage rawImage;
                try {
                    rawImage = ResourceCache.loadImage(srcPath);
                } catch (java.io.IOException imageError) {
                    System.out.println("Skipping imagelayer — file not found: " + srcPath);
                    continue;
                }

                ImageLayerData ild = new ImageLayerData();
                ild.name      = ilEl.getAttribute("name");
                ild.worldX    = (float) (getFloatAttribute(ilEl, "offsetx", 0f) * sf + mapOffsetPixelsX);
                ild.worldY    = (float) (getFloatAttribute(ilEl, "offsety", 0f) * sf + mapOffsetPixelsY);
                ild.parallaxX = getFloatAttribute(ilEl, "parallaxx", 1f);
                ild.parallaxY = getFloatAttribute(ilEl, "parallaxy", 1f);
                ild.opacity   = getFloatAttribute(ilEl, "opacity",   1f);
                ild.tintColor = parseTintColor(ilEl.getAttribute("tintcolor"));
                // Scale image to match game tileSize
                int scaledW = Math.max(1, (int) Math.round(rawImage.getWidth()  * sf));
                int scaledH = Math.max(1, (int) Math.round(rawImage.getHeight() * sf));
                ild.image = ResourceCache.loadScaledImage(srcPath, scaledW, scaledH);
                imageLayers.add(ild);
                System.out.println("Loaded imagelayer: " + ild.name + " @ (" + ild.worldX + "," + ild.worldY + ")");
            }

            System.out.println("Loaded " + mapLayers.size() + " tile layers, "
                + imageLayers.size() + " image layers (mapTileSize=" + mapTileSize + "px)");
        } catch (Exception e) {
            System.out.println("Failed to load map: " + path);
            e.printStackTrace(System.out);
        }
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
                                }
                            } else {
                                System.out.println("WARNING: collisionTemplate '" + tmplName
                                        + "' not found for object #" + obj.getAttribute("id")
                                        + " (is the template in the same Collision layer?)");
                            }
                        } else {
                            Shape shape = parseCollisionObject(obj);
                            if (shape != null) {
                                collisionShapes.add(shape);
                                collisionBounds.add(shape.getBounds());
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
        int extraMargin = tileSize * 3; // load a few extra tiles off-screen to prevent pop-in when moving
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

        // Find the first layer explicitly marked depthSort — layers at or above it
        // auto-promote to depth-sort so they interleave correctly with entities.
        int firstDepthLayerIdx = Integer.MAX_VALUE;
        for (int i = 0; i < mapLayers.size(); i++) {
            if (i < layerDepthSort.size() && layerDepthSort.get(i)) {
                firstDepthLayerIdx = i;
                break;
            }
        }

        for (int layerIndex = 0; layerIndex < mapLayers.size(); layerIndex++) {
            int[][] map = mapLayers.get(layerIndex);
            byte[][] flipMap = layerIndex < mapFlipLayers.size() ? mapFlipLayers.get(layerIndex) : null;
            float px      = getLayerFloat(layerParallaxX, layerIndex, 1.0f);
            float py      = getLayerFloat(layerParallaxY, layerIndex, 1.0f);
            float opacity = getLayerFloat(layerOpacity, layerIndex, 1.0f);
            Color tint    = layerIndex < layerTint.size()      ? layerTint.get(layerIndex)       : null;
            boolean forceBackground = layerIndex < layerBackground.size() && layerBackground.get(layerIndex);
            boolean forceDepthSort = layerIndex < layerDepthSort.size() && layerDepthSort.get(layerIndex);
            boolean forceForeground = layerIndex < layerForeground.size() && layerForeground.get(layerIndex);

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
                    visibleTile.worldX      = worldX;
                    visibleTile.screenBaseY = screenBaseY;
                    visibleTile.screenX     = Math.round(worldX      - cameraWorldX * px);
                    visibleTile.screenY     = Math.round(screenBaseY - cameraWorldY * py);
                    visibleTile.parallaxX   = px;
                    visibleTile.parallaxY   = py;
                    visibleTile.baseWorldY  = worldY;
                    visibleTile.layerIndex  = layerIndex;
                    visibleTile.worldCol    = worldCol;
                    visibleTile.worldRow    = worldRow;
                    visibleTile.opacity     = opacity;
                    visibleTile.tint        = tint;
                    visibleTile.flipFlags   = (flipMap != null) ? flipMap[worldCol][worldRow] : 0;
                    // Use layerIndex as renderOrder so Tiled layer order is always respected
                    visibleTile.renderOrder = layerIndex;

                    // sortY = bottom edge of this tile + sortYOffset.
                    // sortYOffset values are in GAME pixels (not Tiled pixels) — no scaling needed.
                    // For multi-row structures, set sortYOffset on the TOP-row tiles
                    // so their sortY matches the bottom row's sortY.
                    int sortYOff = (gidToSortYOffset != null && gid < gidToSortYOffset.length) ? gidToSortYOffset[gid] : 0;
                    visibleTile.sortY = worldY + tileSize + sortYOff;

                    // Per-tile properties override layer-level settings
                    boolean isTileForeground = (gidToForeground != null && gid < gidToForeground.length && gidToForeground[gid]);
                    boolean isTileDepthSort  = (gidToDepthSort  != null && gid < gidToDepthSort.length  && gidToDepthSort[gid]);
                    boolean isTileBackground = (gidToBackground != null && gid < gidToBackground.length && gidToBackground[gid]);

                    // Classification priority:
                    // 1. Per-tile foreground always wins
                    // 2. Per-tile depthSort (including sortYOffset) overrides everything
                    // 3. Per-tile background overrides layer-level settings
                    // 4. Layer-level foreground
                    // 5. Layer-level background (only if layer is below depth-sort layers)
                    // 6. Layer-level depthSort
                    // 7. Auto-promote: layers at/above first depth-sort layer → depth-sort
                    // 8. Default: background
                    if (isTileForeground) {
                        foregroundVisibleTiles.add(visibleTile);
                    } else if (isTileDepthSort) {
                        depthVisibleTiles.add(visibleTile);
                    } else if (isTileBackground) {
                        backgroundVisibleTiles.add(visibleTile);
                    } else if (forceForeground) {
                        foregroundVisibleTiles.add(visibleTile);
                    } else if (forceBackground) {
                        backgroundVisibleTiles.add(visibleTile);
                    } else if (forceDepthSort || layerIndex >= firstDepthLayerIdx) {
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
        // AffineTransform(m00, m10, m01, m11, tx, ty)
        if ( fD &&  fH && !fV) return new AffineTransform( 0,  1, -1,  0, sx + w, sy);         // 90° CW
        if ( fD && !fH &&  fV) return new AffineTransform( 0, -1,  1,  0, sx,     sy + h);      // 90° CCW
        if (!fD &&  fH &&  fV) return new AffineTransform(-1,  0,  0, -1, sx + w, sy + h);      // 180°
        if ( fD &&  fH &&  fV) return new AffineTransform( 0, -1, -1,  0, sx + w, sy + h);      // 270° CW / 90° CCW flipped
        if (!fD &&  fH && !fV) return new AffineTransform(-1,  0,  0,  1, sx + w, sy);          // flip H
        if (!fD && !fH &&  fV) return new AffineTransform( 1,  0,  0, -1, sx,     sy + h);      // flip V
        /* fD && !fH && !fV */ return new AffineTransform( 0,  1,  1,  0, sx,     sy);          // transpose / anti-diagonal
    }

    public void drawBackground(Graphics2D g2) {
        // Draw image layers behind all tile layers
        drawImageLayers(g2);

        for (VisibleTileDraw visibleTile : backgroundVisibleTiles) {
            drawTile(g2, visibleTile);
        }

        drawPathOverlay(g2);
    }

    public void drawForeground(Graphics2D g2) {
        for (VisibleTileDraw visibleTile : foregroundVisibleTiles) {
            drawTile(g2, visibleTile);
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

    /** Draw all image layers (rendered behind background tile layers). */
    private void drawImageLayers(Graphics2D g2) {
        if (imageLayers.isEmpty()) return;
        int cameraWorldX = gp.player.worldX - gp.player.screenX;
        int cameraWorldY = gp.player.worldY - gp.player.screenY;
        for (ImageLayerData ild : imageLayers) {
            if (ild.image == null) continue;
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
     * Called once per game update tick (60 Hz). Advances animated-tile frame timers
     * and invalidates the visible-tile cache when any animation changes frame, so the
     * next prepareVisibleTiles() will substitute the new frame image.
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
            // Force a full visible-tile rebuild on next prepareVisibleTiles() call
            lastVisMinCol = -99;
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
        gidToTile         = new Tile[maxGIDValue + 1];
        gidToRenderOrder  = new int[maxGIDValue + 1];
        gidToDepthSort    = new boolean[maxGIDValue + 1];
        gidToForeground   = new boolean[maxGIDValue + 1];
        gidToBackground   = new boolean[maxGIDValue + 1];
        gidToSortYOffset  = new int[maxGIDValue + 1];
        for (Tileset ts : tilesets) {
            for (int i = 0; i < ts.tileCount && i < ts.tiles.length; i++) {
                int gid = ts.firstGID + i;
                gidToTile[gid]        = ts.tiles[i];
                gidToRenderOrder[gid] = ts.renderOrder;
                gidToDepthSort[gid]   = ts.depthSort;
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
     * Classify a GID for the minimap biome colour.
     * Returns: "water", "tree", "struct", "grass", "shadow", or null (unknown).
     * Uses tileset name/path heuristics so it works across any map.
     */
    public String classifyGidBiome(int gid) {
        if (gid <= 0) return null;
        for (int i = tilesets.size() - 1; i >= 0; i--) {
            Tileset ts = tilesets.get(i);
            if (gid >= ts.firstGID && gid < ts.firstGID + ts.tileCount) {
                String n = (ts.name + " " + ts.sourcePath).toLowerCase();
                if (n.contains("water") || n.contains("lake") || n.contains("river")) return "water";
                if (n.contains("tree") || n.contains("forest") || n.contains("canopy")) return "tree";
                if (n.contains("shadow") || n.contains("overlay") || n.contains("light")) return "shadow";
                if (n.contains("struct") || n.contains("house") || n.contains("build") || n.contains("castle")
                    || n.contains("wall") || n.contains("door") || n.contains("bridge") || n.contains("stone")
                    || n.contains("cave") || n.contains("rock") || n.contains("dungeon")) return "struct";
                if (n.contains("grass") || n.contains("ground") || n.contains("dirt") || n.contains("path")
                    || n.contains("floor") || n.contains("terrain") || n.contains("sand")) return "grass";
                // Fallback: if tileset name has no recognizable keyword, use generic grass
                return "grass";
            }
        }
        return null;
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

    private int getTilesetRenderOrder(Element tilesetElement, String resourcePath) {
        int explicitRenderOrder = getIntProperty(tilesetElement, "renderOrder", Integer.MIN_VALUE);
        if (explicitRenderOrder != Integer.MIN_VALUE) {
            return explicitRenderOrder;
        }

        String renderCategory = getStringProperty(tilesetElement, "renderCategory", "");
        if (!renderCategory.isBlank()) {
            return getDefaultRenderOrder(renderCategory, resourcePath);
        }

        return getDefaultRenderOrder(tilesetElement.getAttribute("name"), resourcePath);
    }

    private int getDefaultRenderOrder(String label, String resourcePath) {
        String normalized = (label + " " + resourcePath).toLowerCase();
        // Structures (fences, buildings, walls) sit above trees when at the same Y
        if (normalized.contains("fence") || normalized.contains("build") || normalized.contains("house")
                || normalized.contains("tower") || normalized.contains("wall")) {
            return 25;
        }
        if (normalized.contains("tree") || normalized.contains("decor") || normalized.contains("foliage")) {
            return 20;
        }
        if (normalized.contains("shadow")) {
            return 15;
        }
        // Ground / base layer tilesets must render BELOW shadows (15).
        // Any tileset whose name suggests it is a ground fill goes here.
        if (normalized.contains("grass") || normalized.contains("ground")
                || normalized.contains("field") || normalized.contains("dirt")
                || normalized.contains("sand") || normalized.contains("soil")) {
            return 5;
        }
        if (normalized.contains("water")) {
            return 5;
        }
        // Unknown/new tilesets default to 16 — just above shadows (15).
        // To draw BELOW shadows, set renderOrder = 10 (or lower) on the tileset in Tiled.
        // To draw even higher (above trees/fences), set renderOrder = 30 or higher.
        return 16;
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

    private boolean getTilesetDepthSort(Element tilesetElement, String resourcePath) {
        String value = getStringProperty(tilesetElement, "depthSort", null);
        if (value == null || value.isBlank()) {
            return isDepthSortedTileset(tilesetElement.getAttribute("name"), resourcePath);
        }

        return Boolean.parseBoolean(value.trim());
    }

    private boolean isDepthSortedTileset(String label, String resourcePath) {
        String normalized = (label + " " + resourcePath).toLowerCase();
        return normalized.contains("tree") || normalized.contains("decor") || normalized.contains("foliage")
            || normalized.contains("fence") || normalized.contains("build") || normalized.contains("house")
            || normalized.contains("tower") || normalized.contains("wall");
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
                String fg = getStringProperty(tileEl, "foreground", null);
                if (fg != null) ts.tiles[tileId].foreground = Boolean.parseBoolean(fg.trim());
                String bg = getStringProperty(tileEl, "background", null);
                if (bg != null) ts.tiles[tileId].background = Boolean.parseBoolean(bg.trim());
                String ds = getStringProperty(tileEl, "depthSort", null);
                if (ds != null) ts.tiles[tileId].depthSort = Boolean.parseBoolean(ds.trim());

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

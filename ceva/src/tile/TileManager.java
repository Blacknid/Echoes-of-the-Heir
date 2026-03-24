package tile;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import main.GamePanel;
import main.UtilityTool;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class TileManager {

    GamePanel gp;

    // Tile scaling
    final int originalTileSize = 32;
    final int scale = 2;
    public final int tileSize = originalTileSize * scale;

    // Per-map TMX tile size (updated when loading a map; used for scaling)
    int mapTileSize = originalTileSize;
    // Pixel offset for infinite maps (after shifting chunks to start at 0,0)
    int mapOffsetPixelsX = 0;
    int mapOffsetPixelsY = 0;

    // Tilesets
    public class Tileset {
        String name;
        String sourcePath;
        int firstGID;
        int tileCount;
        boolean waterEffect;
        int renderOrder;
        boolean depthSort;
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
            int byRenderOrder = Integer.compare(first.renderOrder, second.renderOrder);
            if (byRenderOrder != 0) {
                return byRenderOrder;
            }

            int bySortY = Integer.compare(first.sortY, second.sortY);
            if (bySortY != 0) {
                return bySortY;
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

    // Multi-layer map
    public ArrayList<int[][]> mapLayers = new ArrayList<>();
    public ArrayList<String> layerNames = new ArrayList<>();
    public ArrayList<Float> layerParallaxX = new ArrayList<>();
    public ArrayList<Float> layerParallaxY = new ArrayList<>();

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

        // Default: only the "Collision" objectgroup provides collision rectangles
        collisionObjectLayers.add("Collision");

        initializeDefaultMap();
        printCollisionConfig();
    }

    /** Print current collision configuration to console. */
    public void printCollisionConfig() {
        System.out.println("=== COLLISION CONFIGURATION ===");
        System.out.println("Object layers (rectangles): " + collisionObjectLayers);
        System.out.println("Tile layers (full tile blocking): " + collisionTileLayers);
        System.out.println("Loaded collision shapes: " + collisionShapes.size());
        System.out.println("Tile layer names in map: " + layerNames);
        System.out.println("===============================");
    }

    private void initializeDefaultMap() {
        loadMapFromTMX("/res/maps/harta.tmx");
        loadCollisionLayer("/res/maps/harta.tmx");
    }

    // ---------------- Load tilesets ----------------
    public void addTileset(String path, int firstGID, int tileWidth, int tileHeight, int tileCount, int columns, String name, int renderOrder, boolean depthSort) {
        try {
            System.out.println("Trying to load: " + path);
            java.net.URL url = getClass().getResource(path);
            if (url == null) {
                System.out.println("CRITICAL: File not found at path: " + path);
            } else {
                System.out.println("Success! Found at: " + url.getPath());
            }
            InputStream imageStream = getClass().getResourceAsStream(path);
            if (imageStream == null) {
                return;
            }

            BufferedImage tilesetImage = ImageIO.read(imageStream);
            int safeColumns = Math.max(1, columns);
            int safeTileCount = Math.max(1, tileCount);

            Tileset ts = new Tileset();
            ts.name = name;
            ts.sourcePath = path;
            ts.firstGID = firstGID;
            ts.tileCount = safeTileCount;
            ts.waterEffect = isWaterTileset(name, path);
            ts.renderOrder = renderOrder;
            ts.depthSort = depthSort;
            ts.tiles = new Tile[ts.tileCount];

            for (int index = 0; index < ts.tileCount; index++) {
                int tileX = (index % safeColumns) * tileWidth;
                int tileY = (index / safeColumns) * tileHeight;

                if (tileX + tileWidth > tilesetImage.getWidth() || tileY + tileHeight > tilesetImage.getHeight()) {
                    break;
                }

                ts.tiles[index] = new Tile();
                BufferedImage sub = tilesetImage.getSubimage(tileX, tileY, tileWidth, tileHeight);
                // Scale tiles proportionally: game tileSize / map tileSize gives the world scale factor.
                // This preserves oversized tiles (e.g. 190x200 trees) at their correct visual size.
                float tileScale = (float) tileSize / mapTileSize;
                int scaledWidth = Math.max(1, Math.round(tileWidth * tileScale));
                int scaledHeight = Math.max(1, Math.round(tileHeight * tileScale));
                ts.tiles[index].image = UtilityTool.scaleImage(sub, scaledWidth, scaledHeight);
                ts.tiles[index].drawOffsetY = Math.max(0, scaledHeight - tileSize);
            }

            tilesets.add(ts);
            System.out.println("Loaded tileset: " + path + ", firstGID=" + firstGID);
        } catch (Exception e) {
            System.out.println("Failed to load tileset: " + path);
            e.printStackTrace(System.out);
        }
    }

    public void loadTilesets(String mapPath) {
        tilesets.clear();
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
                    getTilesetDepthSort(tilesetElement, resourcePath)
                );
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

    // Mask to strip Tiled flip flags (horizontal, vertical, diagonal) from GIDs
    private static final long GID_MASK = 0x1FFFFFFFL;

    // ---------------- Load tile layers ----------------
    public void loadMapFromTMX(String path) {
        try {
            loadTilesets(path);
            mapLayers.clear();
            layerNames.clear();
            layerParallaxX.clear();
            layerParallaxY.clear();

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

                int[][] layerMap = new int[gp.maxWorldCol][gp.maxWorldRow];

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
                            int gid = (int) (Long.parseLong(numStr.trim()) & GID_MASK);
                            int mapCol = cx + col;
                            int mapRow = cy + row;
                            if (mapCol >= 0 && mapCol < gp.maxWorldCol && mapRow >= 0 && mapRow < gp.maxWorldRow) {
                                layerMap[mapCol][mapRow] = gid;
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
                        int gid = (int) (Long.parseLong(numStr.trim()) & GID_MASK);
                        if (col < gp.maxWorldCol && row < gp.maxWorldRow) {
                            layerMap[col][row] = gid;
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
                layerNames.add(layerName);
                layerParallaxX.add(parallaxX);
                layerParallaxY.add(parallaxY);
            }
            System.out.println("Loaded " + mapLayers.size() + " tile layers (mapTileSize=" + mapTileSize + "px)");
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
                for (int j = 0; j < objects.getLength(); j++) {
                    try {
                        Element obj = (Element) objects.item(j);
                        Shape shape = parseCollisionObject(obj);
                        if (shape != null) {
                            collisionShapes.add(shape);
                            collisionBounds.add(shape.getBounds());
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

        // --- Polyline (skip — open shape, no area for collision) ---
        NodeList polylineNodes = obj.getElementsByTagName("polyline");
        if (polylineNodes.getLength() > 0) {
            System.out.println("Skipping collision object #" + obj.getAttribute("id") + " (polyline — no area)");
            return null;
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
        int extraMargin = tileSize * 2;
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
            return;
        }

        // OPTIMIZATION 3: Full rebuild — but using O(1) direct GID arrays instead of linear tileset scans
        lastVisMinCol = minCol; lastVisMaxCol = maxCol;
        lastVisMinRow = minRow; lastVisMaxRow = maxRow;

        backgroundVisibleTiles.clear();
        depthVisibleTiles.clear();
        poolIndex = 0;

        for (int layerIndex = 0; layerIndex < mapLayers.size(); layerIndex++) {
            int[][] map = mapLayers.get(layerIndex);
            float px = layerIndex < layerParallaxX.size() ? layerParallaxX.get(layerIndex) : 1.0f;
            float py = layerIndex < layerParallaxY.size() ? layerParallaxY.get(layerIndex) : 1.0f;

            for (int worldRow = minRow; worldRow <= maxRow; worldRow++) {
                for (int worldCol = minCol; worldCol <= maxCol; worldCol++) {
                    int gid = map[worldCol][worldRow];
                    if (gid == 0) continue;

                    // O(1) direct lookup — no linear tileset scan
                    Tile currentTile = (gidToTile != null && gid < gidToTile.length) ? gidToTile[gid] : null;
                    if (currentTile == null || currentTile.image == null) continue;

                    int worldX     = worldCol * tileSize;
                    int worldY     = worldRow * tileSize;
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
                    visibleTile.renderOrder = (gidToRenderOrder != null && gid < gidToRenderOrder.length)
                                              ? gidToRenderOrder[gid] : 0;
                    visibleTile.sortY       = worldY;

                    if (gidToDepthSort != null && gid < gidToDepthSort.length && gidToDepthSort[gid]) {
                        depthVisibleTiles.add(visibleTile);
                    } else {
                        backgroundVisibleTiles.add(visibleTile);
                    }
                }
            }
        }

        backgroundVisibleTiles.sort(backgroundTileComparator);
        depthVisibleTiles.sort(depthTileComparator);
    }

    public void drawBackground(Graphics2D g2) {
        for (VisibleTileDraw visibleTile : backgroundVisibleTiles) {
            g2.drawImage(visibleTile.image, visibleTile.screenX, visibleTile.screenY, null);
        }

        drawPathOverlay(g2);
    }

    public int getDepthTileCount() {
        return depthVisibleTiles.size();
    }

    public int getDepthTileSortY(int index) {
        return depthVisibleTiles.get(index).sortY;
    }

    public void drawDepthTile(Graphics2D g2, int index) {
        VisibleTileDraw visibleTile = depthVisibleTiles.get(index);
        g2.drawImage(visibleTile.image, visibleTile.screenX, visibleTile.screenY, null);
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
        for (Tileset ts : tilesets) {
            for (int i = 0; i < ts.tileCount && i < ts.tiles.length; i++) {
                int gid = ts.firstGID + i;
                gidToTile[gid]        = ts.tiles[i];
                gidToRenderOrder[gid] = ts.renderOrder;
                gidToDepthSort[gid]   = ts.depthSort;
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
        if (normalized.contains("tree") || normalized.contains("decor") || normalized.contains("foliage")) {
            return 20;
        }
        if (normalized.contains("shadow")) {
            return 15;
        }
        if (normalized.contains("build") || normalized.contains("house") || normalized.contains("tower")) {
            return 10;
        }
        if (normalized.contains("water")) {
            return 5;
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

    private boolean getTilesetDepthSort(Element tilesetElement, String resourcePath) {
        String value = getStringProperty(tilesetElement, "depthSort", null);
        if (value == null || value.isBlank()) {
            return isDepthSortedTileset(tilesetElement.getAttribute("name"), resourcePath);
        }

        return Boolean.parseBoolean(value.trim());
    }

    private boolean isDepthSortedTileset(String label, String resourcePath) {
        String normalized = (label + " " + resourcePath).toLowerCase();
        return normalized.contains("tree") || normalized.contains("decor") || normalized.contains("foliage");
    }

    private String getStringProperty(Element element, String propertyName, String defaultValue) {
        NodeList properties = element.getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (!propertyName.equals(property.getAttribute("name"))) {
                continue;
            }

            String value = property.getAttribute("value");
            if (value == null || value.isBlank()) {
                value = property.getTextContent();
            }
            return value;
        }

        return defaultValue;
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

    private Document parseXmlResource(String path) throws Exception {
        InputStream resourceStream = getClass().getResourceAsStream(path);
        if (resourceStream == null) {
            System.out.println("CRITICAL: XML resource not found: " + path);
            return null;
        }

        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document document = builder.parse(resourceStream);
        document.getDocumentElement().normalize();
        return document;
    }
}

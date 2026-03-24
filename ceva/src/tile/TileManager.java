package tile;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
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
        int baseWorldY;
        int layerIndex;
        int worldCol;
        int renderOrder;
        boolean waterEffect;
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

    // Multi-layer map (visual only)
    public ArrayList<int[][]> mapLayers = new ArrayList<>();
    public ArrayList<Float> layerParallaxX = new ArrayList<>();
    public ArrayList<Float> layerParallaxY = new ArrayList<>();

    // Collision rectangles (from object layer)
    public ArrayList<java.awt.Rectangle> collisionRects = new ArrayList<>();

    public TileManager(GamePanel gp) {
        this.gp = gp;

        initializeDefaultMap();
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
                int scaledWidth = Math.max(1, tileWidth * scale);
                int scaledHeight = Math.max(1, tileHeight * scale);
                ts.tiles[index].image = UtilityTool.scaleImage(sub, scaledWidth, scaledHeight);
                ts.tiles[index].drawOffsetY = Math.max(0, scaledHeight - tileSize);
                ts.tiles[index].collision = false;
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

    // ---------------- Load tile layers ----------------
    public void loadMapFromTMX(String path) {
        try {
            loadTilesets(path);
            mapLayers.clear();
            layerParallaxX.clear();
            layerParallaxY.clear();

            Document doc = parseXmlResource(path);
            if (doc == null) {
                return;
            }

            NodeList layers = doc.getElementsByTagName("layer");
            for (int l = 0; l < layers.getLength(); l++) {
                Element layer = (Element) layers.item(l);
                Element data = (Element) layer.getElementsByTagName("data").item(0);
                float parallaxX = getFloatAttribute(layer, "parallaxx", 1.0f);
                float parallaxY = getFloatAttribute(layer, "parallaxy", 1.0f);
                String csv = data.getTextContent().trim().replaceAll("\\s+", "");
                String[] numbers = csv.split(",");

                int[][] layerMap = new int[gp.maxWorldCol][gp.maxWorldRow];
                int col = 0, row = 0;
                for (String numStr : numbers) {
                    int gid = Integer.parseInt(numStr.trim());
                    layerMap[col][row] = gid;
                    col++;
                    if (col == gp.maxWorldCol) {
                        col = 0;
                        row++;
                        if (row == gp.maxWorldRow) break;
                    }
                }
                mapLayers.add(layerMap);
                layerParallaxX.add(parallaxX);
                layerParallaxY.add(parallaxY);
            }
            System.out.println("Loaded " + mapLayers.size() + " tile layers");
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
                return;
            }

            collisionRects.clear();

            NodeList objectGroups = doc.getElementsByTagName("objectgroup");
            for (int i = 0; i < objectGroups.getLength(); i++) {
                Element og = (Element) objectGroups.item(i);
                if (!og.getAttribute("name").equals("Collision")) continue;

                NodeList objects = og.getElementsByTagName("object");
                for (int j = 0; j < objects.getLength(); j++) {
                    Element obj = (Element) objects.item(j);
                    int x = Math.round(Float.parseFloat(obj.getAttribute("x")));
                    int y = Math.round(Float.parseFloat(obj.getAttribute("y")));
                    int width = Math.round(Float.parseFloat(obj.getAttribute("width")));
                    int height = Math.round(Float.parseFloat(obj.getAttribute("height")));

                    // Scale according to your tile size
                    x = x * gp.tileSize / originalTileSize;
                    y = y * gp.tileSize / originalTileSize;
                    width = width * gp.tileSize / originalTileSize;
                    height = height * gp.tileSize / originalTileSize;

                    collisionRects.add(new java.awt.Rectangle(x, y, width, height));
                }
            }

            System.out.println("Loaded collision layer with " + collisionRects.size() + " rectangles");

        } catch (Exception e) {
            System.out.println("Failed to load collision layer: " + path);
            e.printStackTrace(System.out);
        }
    }

    // OPTIMIZATION: Cache viewport bounds to avoid repeated calculations
    // Variables for viewport culling
    private int cachedViewportMinX = -1;
    private int cachedViewportMaxX = -1;
    private int cachedViewportMinY = -1;
    private int cachedViewportMaxY = -1;
    private int cachedPlayerWorldX = -1;
    private int cachedPlayerWorldY = -1;

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

    // OPTIMIZATION: Improved viewport culling logic
    public void prepareVisibleTiles() {
        int playerWorldX = gp.player.worldX;
        int playerWorldY = gp.player.worldY;
        int cameraWorldX = playerWorldX - gp.player.screenX;
        int cameraWorldY = playerWorldY - gp.player.screenY;
        
        // Update viewport bounds
        cachedPlayerWorldX = playerWorldX;
        cachedPlayerWorldY = playerWorldY;
        cachedViewportMinX = cameraWorldX;
        cachedViewportMaxX = cameraWorldX + gp.screenWidth;
        cachedViewportMinY = cameraWorldY;
        cachedViewportMaxY = cameraWorldY + gp.screenHeight;

        backgroundVisibleTiles.clear();
        depthVisibleTiles.clear();
        poolIndex = 0;

        // OPTIMIZATION: Calculate visible column/row range to avoid iterating entire 100x100 world
        int extraMargin = tileSize * 2; // extra margin for oversized tiles
        int minCol = Math.max(0, (cachedViewportMinX - extraMargin) / tileSize);
        int maxCol = Math.min(gp.maxWorldCol - 1, (cachedViewportMaxX + extraMargin) / tileSize);
        int minRow = Math.max(0, (cachedViewportMinY - extraMargin) / tileSize);
        int maxRow = Math.min(gp.maxWorldRow - 1, (cachedViewportMaxY + extraMargin) / tileSize);

        for (int layerIndex = 0; layerIndex < mapLayers.size(); layerIndex++) {
            int[][] map = mapLayers.get(layerIndex);
            float parallaxX = layerIndex < layerParallaxX.size() ? layerParallaxX.get(layerIndex) : 1.0f;
            float parallaxY = layerIndex < layerParallaxY.size() ? layerParallaxY.get(layerIndex) : 1.0f;

            // Only iterate visible tile range instead of full map
            for (int worldRow = minRow; worldRow <= maxRow; worldRow++) {
                for (int worldCol = minCol; worldCol <= maxCol; worldCol++) {
                    int gid = map[worldCol][worldRow];
                    if (gid == 0) continue;
                    
                    Tile currentTile = getTileByGID(gid);
                    if (currentTile == null || currentTile.image == null) continue;

                    Tileset tileset = getTilesetForGID(gid);
                    if (tileset == null) continue;

                    int worldX = worldCol * tileSize;
                    int worldY = worldRow * tileSize;
                    int drawWorldY = worldY - currentTile.drawOffsetY;

                    int screenX = Math.round(worldX - (cameraWorldX * parallaxX));
                    int screenY = Math.round(drawWorldY - (cameraWorldY * parallaxY));

                    // OPTIMIZATION: Reuse pooled objects instead of allocating new ones
                    VisibleTileDraw visibleTile = getPooledTileDraw();
                    visibleTile.image = currentTile.image;
                    visibleTile.screenX = screenX;
                    visibleTile.screenY = screenY;
                    visibleTile.baseWorldY = worldY;
                    visibleTile.layerIndex = layerIndex;
                    visibleTile.worldCol = worldCol;
                    visibleTile.worldRow = worldRow;
                    visibleTile.renderOrder = tileset.renderOrder;
                    visibleTile.waterEffect = tileset.waterEffect;
                    visibleTile.sortY = worldY;

                    if (tileset.depthSort) {
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
            if (visibleTile.waterEffect && gp.mapShader != null) {
                int waveY = gp.mapShader.getWaterWaveOffset(visibleTile.worldCol, visibleTile.worldRow);
                g2.drawImage(visibleTile.image, visibleTile.screenX, visibleTile.screenY + waveY, null);
                int idx = gp.mapShader.getWaterShimmerIndex(visibleTile.worldCol, visibleTile.worldRow);
                g2.setColor(gp.mapShader.waterShimmerColors[idx]);
                g2.fillRect(visibleTile.screenX, visibleTile.screenY + waveY, tileSize, tileSize);
            } else {
                g2.drawImage(visibleTile.image, visibleTile.screenX, visibleTile.screenY, null);
            }
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
        if (visibleTile.waterEffect && gp.mapShader != null) {
            int waveY = gp.mapShader.getWaterWaveOffset(visibleTile.worldCol, visibleTile.worldRow);
            g2.drawImage(visibleTile.image, visibleTile.screenX, visibleTile.screenY + waveY, null);
            int idx = gp.mapShader.getWaterShimmerIndex(visibleTile.worldCol, visibleTile.worldRow);
            g2.setColor(gp.mapShader.waterShimmerColors[idx]);
            g2.fillRect(visibleTile.screenX, visibleTile.screenY + waveY, tileSize, tileSize);
        } else {
            g2.drawImage(visibleTile.image, visibleTile.screenX, visibleTile.screenY, null);
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

    private void rebuildTileLookup() {
        int totalTiles = 0;
        for (Tileset ts : tilesets) {
            totalTiles += ts.tiles.length;
        }

        tile = new Tile[totalTiles];
        int index = 0;
        for (Tileset ts : tilesets) {
            for (Tile singleTile : ts.tiles) {
                tile[index++] = singleTile;
            }
        }
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

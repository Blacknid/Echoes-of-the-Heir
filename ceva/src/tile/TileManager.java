package tile;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

import main.GamePanel;
import main.UtilityTool;

public class TileManager {

    GamePanel gp;

    // Tile scaling
    final int originalTileSize = 32;
    final int scale = 2;
    public final int tileSize = originalTileSize * scale;

    // Tilesets
    public class Tileset {
        BufferedImage image;
        int firstGID;
        int tileCount;
        Tile[] tiles;
    }
    ArrayList<Tileset> tilesets = new ArrayList<>();
    public Tile[] tile; // combined tiles for quick lookup

    // Multi-layer map (visual only)
    public ArrayList<int[][]> mapLayers = new ArrayList<>();

    // Collision rectangles (from object layer)
    public ArrayList<java.awt.Rectangle> collisionRects = new ArrayList<>();

    public TileManager(GamePanel gp) {
        this.gp = gp;

        loadTilesets();
        loadMapFromTMX("/res/maps/harta.tmx");
        loadCollisionLayer("/res/maps/harta.tmx");

        // Combine all tiles from tilesets
        int totalTiles = 0;
        for (Tileset ts : tilesets) totalTiles += ts.tiles.length;
        tile = new Tile[totalTiles];
        int index = 0;
        for (Tileset ts : tilesets) {
            for (Tile t : ts.tiles) {
                tile[index++] = t;
            }
        }
    }

    // ---------------- Load tilesets ----------------
    public void addTileset(String path, int firstGID) {
        try {
            System.out.println("Trying to load: " + path);
            java.net.URL url = getClass().getResource(path);
            if (url == null) {
                System.out.println("CRITICAL: File not found at path: " + path);
            } else {
                System.out.println("Success! Found at: " + url.getPath());
            }
            BufferedImage tilesetImage = ImageIO.read(getClass().getResourceAsStream(path));
            int cols = tilesetImage.getWidth() / originalTileSize;
            int rows = tilesetImage.getHeight() / originalTileSize;

            Tileset ts = new Tileset();
            ts.image = tilesetImage;
            ts.firstGID = firstGID;
            ts.tileCount = cols * rows;
            ts.tiles = new Tile[ts.tileCount];

            UtilityTool uTool = new UtilityTool();
            int index = 0;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    ts.tiles[index] = new Tile();
                    BufferedImage sub = tilesetImage.getSubimage(
                            x * originalTileSize,
                            y * originalTileSize,
                            originalTileSize,
                            originalTileSize
                    );
                    ts.tiles[index].image = uTool.scaleImage(sub, tileSize, tileSize);
                    ts.tiles[index].collision = false; // no TSX collision
                    index++;
                }
            }

            tilesets.add(ts);
            System.out.println("Loaded tileset: " + path + ", firstGID=" + firstGID);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadTilesets() {
        tilesets.clear();
        addTileset("/res/tiles/Grass_sheet.png", 1);
        addTileset("/res/tiles/Tileset2.png", 13);
        addTileset("/res/tiles/Water-sheet.png", 77);
    }

    // ---------------- Get tile by GID ----------------
    public Tile getTileByGID(int gid) {
        if (gid == 0) return null;
        for (int i = tilesets.size() - 1; i >= 0; i--) {
            Tileset ts = tilesets.get(i);
            if (gid >= ts.firstGID) {
                int index = gid - ts.firstGID;
                if (index < ts.tiles.length) return ts.tiles[index];
            }
        }
        return null;
    }

    // ---------------- Load tile layers ----------------
    public void loadMapFromTMX(String path) {
        try {
            InputStream is = getClass().getResourceAsStream(path);
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            doc.getDocumentElement().normalize();

            NodeList layers = doc.getElementsByTagName("layer");
            for (int l = 0; l < layers.getLength(); l++) {
                Element layer = (Element) layers.item(l);
                Element data = (Element) layer.getElementsByTagName("data").item(0);
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
            }
            System.out.println("Loaded " + mapLayers.size() + " tile layers");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------- Load Collision Layer ----------------
    public void loadCollisionLayer(String path) {
        try {
            InputStream is = getClass().getResourceAsStream(path);
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            doc.getDocumentElement().normalize();

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
            e.printStackTrace();
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

    // OPTIMIZATION: Improved viewport culling logic
    public void draw(Graphics2D g2) {
        int playerWorldX = gp.player.worldX;
        int playerWorldY = gp.player.worldY;
        
        // Update viewport bounds only if player moved significantly
        if (playerWorldX != cachedPlayerWorldX || playerWorldY != cachedPlayerWorldY) {
            cachedPlayerWorldX = playerWorldX;
            cachedPlayerWorldY = playerWorldY;
            cachedViewportMinX = playerWorldX - gp.player.screenX;
            cachedViewportMaxX = playerWorldX + (gp.screenWidth - gp.player.screenX);
            cachedViewportMinY = playerWorldY - gp.player.screenY;
            cachedViewportMaxY = playerWorldY + (gp.screenHeight - gp.player.screenY);
        }
        
        for (int layerIndex = 0; layerIndex < mapLayers.size(); layerIndex++) {
            int[][] map = mapLayers.get(layerIndex);

            for (int worldRow = 0; worldRow < gp.maxWorldRow; worldRow++) {
                for (int worldCol = 0; worldCol < gp.maxWorldCol; worldCol++) {
                    int gid = map[worldCol][worldRow];
                    if (gid == 0) continue; // Skip empty tiles
                    
                    Tile tile = getTileByGID(gid);
                    if (tile == null) continue;

                    int worldX = worldCol * tileSize;
                    int worldY = worldRow * tileSize;
                    
                    // Early culling: check if tile is in viewport
                    if (worldX + tileSize < cachedViewportMinX ||
                        worldX > cachedViewportMaxX ||
                        worldY + tileSize < cachedViewportMinY ||
                        worldY > cachedViewportMaxY) {
                        continue;
                    }

                    int screenX = worldX - playerWorldX + gp.player.screenX;
                    int screenY = worldY - playerWorldY + gp.player.screenY;
                    
                    g2.drawImage(tile.image, screenX, screenY, null);
                }
            }
        }
        if ( gp.drawPath == true ) {
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
}

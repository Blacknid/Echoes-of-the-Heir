package tile;

import java.awt.Graphics2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import javax.imageio.ImageIO;
import main.GamePanel;
import main.UtilityTool;

public final class TileManager {

    GamePanel gp;
    public Tile[] tile;
    public int mapTileNum[][];

    public TileManager(GamePanel gp){

        this.gp = gp;


/*PENTRU MODIFICAREA NUMARULUI DE TILES SE SCHIMBA NUMARUL DE ELEMENTE DIN ARRAY 
 * 
*/

        tile = new Tile[500];
        mapTileNum = new int[gp.maxWorldCol][gp.maxWorldRow];

        getTileImage();
        loadMap("/res/maps/harta3.txt");
    }

    public void getTileImage() {
        setup(0, "apa", false);
        setup(1, "Grass/grass 1", false);
        setup(2, "Grass/grass 10", false);
        setup(3, "Grass/grass 2", false);
        setup(4, "Grass/grass 3", false);
        setup(5, "Grass/grass 4", false);
        setup(6, "Grass/grass 5", false);
        setup(7, "Grass/grass 6", false);
        setup(8, "Grass/grass 7", false);
        setup(9, "Grass/grass 8", false);
        setup(10, "Grass/grass 9", false);

        // PATH

        setup(11, "Path/path 1", false);
        setup(12, "Path/path 10", false);
        setup(13, "Path/path 11", false);
        setup(14, "Path/path 12", false);
        setup(15, "Path/path 13", false);
        setup(16, "Path/path 14", false);
        setup(17, "Path/path 15", false);
        setup(18, "Path/path 16", false);
        setup(19, "Path/path 17", false);
        setup(20, "Path/path 18", false);
        setup(21, "Path/path 19", false);
        setup(22, "Path/path 2", false);
        setup(23, "Path/path 20", false);
        setup(24, "Path/path 21", false);
        setup(25, "Path/path 22", false);
        setup(26, "Path/path 23", false);
        setup(27, "Path/path 24", false);
        setup(28, "Path/path 25", false);
        setup(29, "Path/path 26", false);
        setup(30, "Path/path 3", false);
        setup(31, "Path/path 4", false);
        setup(32, "Path/path 5", false);
        setup(33, "Path/path 6", false);
        setup(34, "Path/path 7", false);
        setup(35, "Path/path 8", false);
        setup(36, "Path/path 9", false);

        // TREE / WALL

        setup(37, "tree", true);
        setup(38, "wall", true);
        setup(39, "Apa/xApa1", true);
        setup(40, "Apa/xApa2", true);
        setup(41, "Apa/xApa3", true);
        setup(42, "Apa/xMargine1", true);
        setup(43, "Apa/xMargine10", true);
        setup(44, "Apa/xMargine11", true);
        setup(45, "Apa/xMargine12", true);
        setup(46, "Apa/xMargine13", true);
        setup(47, "Apa/xMargine14", true);
        setup(48, "Apa/xMargine15", true);
        setup(49, "Apa/xMargine16", true);
        setup(50, "Apa/xMargine2", true);
        setup(51, "Apa/xMargine3", true);
        setup(52, "Apa/xMargine4", true);
        setup(53, "Apa/xMargine5", true);
        setup(54, "Apa/xMargine6", true);
        setup(55, "Apa/xMargine7", true);
        setup(56, "Apa/xMargine8", true);
        setup(57, "Apa/xMargine9", true);
        setup(58, "Apa/xValuri mari", true);
        setup(59, "Apa/xValuriMici", true);
        setup(60, "Apa/xw1", true);
        setup(61, "Apa/xw2", true);
        setup(62, "Apa/xw3", true);
        setup(63, "Apa/xw4", true);
    }

    public void setup(int index, String imageName, boolean collision) {

        UtilityTool uTool = new UtilityTool();
        
        try {
            tile[index] = new Tile();
            tile[index].image = ImageIO.read(getClass().getResourceAsStream("/res/tiles/" + imageName + ".png"));
            tile[index].image = uTool.scaleImage(tile[index].image, gp.tileSize, gp.tileSize);
            tile[index].collision = collision;


        }catch(IOException e) {
            e.printStackTrace();
        }
    }

    public void loadMap(String filePath) {
        try {
            InputStream is = getClass().getResourceAsStream(filePath);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                int col = 0;
                int row = 0;
                
                while(col < gp.maxWorldCol && row < gp.maxWorldRow) {
                    String line = br.readLine();
                    while (col < gp.maxWorldCol) {
                        String numbers[] = line.split(" ");
                        
                        int num = Integer.parseInt(numbers[col]);
                        
                        mapTileNum[col][row] = num;
                        col++;
                    }
                    
                    if (col == gp.maxWorldCol) {
                        col = 0;
                        row++;
                    }
                    
                }
            }
            
        } catch (Exception e) {

        }
    }

    public void draw(Graphics2D g2){

        int worldCol = 0;
        int worldRow = 0;

        while(worldCol < gp.maxWorldCol && worldRow < gp.maxWorldRow) {

            int tileNum = mapTileNum[worldCol] [worldRow];

            int worldX = worldCol * gp.tileSize;
            int worldY = worldRow * gp.tileSize;
            int screenX = worldX - gp.player.worldX + gp.player.screenX;
            int screenY = worldY - gp.player.worldY + gp.player.screenY;

            if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX && 
                worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
                worldY + gp.tileSize > gp.player.worldY - gp.player.screenY && 
                worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {
                    
                g2.drawImage(tile[tileNum].image, screenX, screenY, null);
            }

            worldCol++;

            if (worldCol == gp.maxWorldCol) {
                worldCol = 0;
                worldRow++;
                }
        }

    }

}

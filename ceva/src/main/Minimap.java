package main;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import entity.Entity;

/**
 * Corner minimap overlay showing the explored world at a glance.
 * Pre-bakes a terrain image from tile types, then overlays entity positions.
 */
public class Minimap {

    private final GamePanel gp;

    // Display settings
    private static final int MAP_SIZE = 128;       // display size in pixels
    private static final int MARGIN = 12;          // margin from screen edge
    private static final float ALPHA = 0.85f;      // overlay transparency
    private static final int BORDER = 2;           // border thickness

    // Colors
    private static final Color BG_COLOR    = new Color(20, 18, 25);
    private static final Color BORDER_COLOR = new Color(80, 75, 90);
    private static final Color GRASS_COLOR = new Color(58, 90, 48);
    private static final Color STONE_COLOR = new Color(85, 80, 75);
    private static final Color WATER_COLOR = new Color(50, 90, 140);
    private static final Color EMPTY_COLOR = new Color(30, 28, 35);
    private static final Color PLAYER_COLOR = Color.WHITE;
    private static final Color MONSTER_COLOR = new Color(220, 60, 60);
    private static final Color NPC_COLOR = new Color(80, 200, 80);

    // Pre-baked terrain image (1px per tile)
    private BufferedImage terrainImage;
    private boolean visible = true;

    public Minimap(GamePanel gp) {
        this.gp = gp;
    }

    /** Bake the terrain image from tile type data. Call after map loads. */
    public void bakeTerrainImage() {
        int w = gp.maxWorldCol;
        int h = gp.maxWorldRow;
        terrainImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int col = 0; col < w; col++) {
            for (int row = 0; row < h; row++) {
                int type = gp.tileM.getTileType(col, row);
                Color c = switch (type) {
                    case 1 -> GRASS_COLOR;
                    case 2 -> STONE_COLOR;
                    case 3 -> WATER_COLOR;
                    default -> EMPTY_COLOR;
                };
                terrainImage.setRGB(col, row, c.getRGB());
            }
        }
    }

    public void toggle() {
        visible = !visible;
    }

    public boolean isVisible() {
        return visible;
    }

    public void draw(Graphics2D g2) {
        if (!visible || terrainImage == null) return;

        // Position: top-right corner
        int x = gp.screenWidth - MAP_SIZE - MARGIN;
        int y = MARGIN;

        java.awt.Composite savedComp = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ALPHA));

        // Border
        g2.setColor(BORDER_COLOR);
        g2.fillRoundRect(x - BORDER, y - BORDER,
                MAP_SIZE + BORDER * 2, MAP_SIZE + BORDER * 2, 6, 6);

        // Background
        g2.setColor(BG_COLOR);
        g2.fillRect(x, y, MAP_SIZE, MAP_SIZE);

        // Terrain
        g2.drawImage(terrainImage, x, y, MAP_SIZE, MAP_SIZE, null);

        // Scale factor: world tiles to minimap pixels
        float scaleX = (float) MAP_SIZE / gp.maxWorldCol;
        float scaleY = (float) MAP_SIZE / gp.maxWorldRow;

        // Draw monsters as red dots
        for (Entity mon : gp.monster) {
            if (mon != null && mon.alive) {
                int mx = x + (int)((mon.worldX / (float) gp.tileSize) * scaleX);
                int my = y + (int)((mon.worldY / (float) gp.tileSize) * scaleY);
                g2.setColor(MONSTER_COLOR);
                g2.fillRect(mx, my, 2, 2);
            }
        }

        // Draw NPCs as green dots
        for (Entity npc : gp.npc) {
            if (npc != null && npc.alive) {
                int nx = x + (int)((npc.worldX / (float) gp.tileSize) * scaleX);
                int ny = y + (int)((npc.worldY / (float) gp.tileSize) * scaleY);
                g2.setColor(NPC_COLOR);
                g2.fillRect(nx, ny, 2, 2);
            }
        }

        // Draw player as white blip (larger, blinking)
        int px = x + (int)((gp.player.worldX / (float) gp.tileSize) * scaleX);
        int py = y + (int)((gp.player.worldY / (float) gp.tileSize) * scaleY);
        g2.setColor(PLAYER_COLOR);
        g2.fillRect(px - 1, py - 1, 3, 3);

        // Viewport rectangle (semi-transparent white)
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g2.setColor(Color.WHITE);
        int vpX = x + (int)(((gp.player.worldX - gp.player.screenX) / (float)(gp.tileSize)) * scaleX);
        int vpY = y + (int)(((gp.player.worldY - gp.player.screenY) / (float)(gp.tileSize)) * scaleY);
        int vpW = (int)(gp.maxScreenCol * scaleX);
        int vpH = (int)(gp.maxScreenRow * scaleY);
        g2.drawRect(vpX, vpY, vpW, vpH);

        g2.setComposite(savedComp);
    }
}

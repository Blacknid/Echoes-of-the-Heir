package tiles_interactive;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import entity.Entity;
import main.GamePanel;

/**
 * Breakable pot that drops a random pickup (heart, coin, mana) when destroyed.
 * Can be destroyed with any weapon.
 */
public class IT_Pot extends interactiveTile {

    public IT_Pot(GamePanel gp, int col, int row) {
        super(gp, col, row);
        this.gp = gp;
        destructible = true;

        // Generate a simple pot sprite procedurally
        down1 = generatePotSprite(gp.tileSize);
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        // Any weapon can break a pot
        return entity.currentWeapon != null;
    }

    public Color getParticleColor() {
        return new Color(160, 120, 80);
    }

    public int getParticleSize() { return 8; }

    public int getParticleSpeed() { return 1; }

    public int getParticleMaxLife() { return 15; }

    /** Generate a simple pixel-art pot sprite. */
    private BufferedImage generatePotSprite(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        int s = size;
        // Pot body
        g.setColor(new Color(160, 100, 60));
        g.fillOval(s / 6, s / 3, s * 2 / 3, s / 2);
        // Pot rim
        g.setColor(new Color(140, 85, 50));
        g.fillRect(s / 4, s / 4, s / 2, s / 8);
        // Highlight
        g.setColor(new Color(190, 140, 90));
        g.fillOval(s / 4, s * 2 / 5, s / 6, s / 5);
        // Shadow base
        g.setColor(new Color(0, 0, 0, 40));
        g.fillOval(s / 6, s * 3 / 4, s * 2 / 3, s / 6);

        g.dispose();
        return img;
    }
}

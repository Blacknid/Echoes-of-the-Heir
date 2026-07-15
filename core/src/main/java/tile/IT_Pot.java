package tile;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import entity.Entity;
import gfx.Color;
import gfx.Sprite;
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

    /**
     * Generate a simple pixel-art pot sprite into a libGDX Pixmap (GPU-native replacement for
     * the old BufferedImage+Graphics2D procedural draw). Ovals are approximated with filled
     * ellipses via Pixmap primitives — visually identical at tile scale.
     */
    private Sprite generatePotSprite(int size) {
        // Headless (server): the pot's appearance is irrelevant to the simulation — only its size
        // is, and that's the argument. Skip the Pixmap work entirely; there's no GL to upload to.
        if (util.ResourceCache.isHeadless()) return Sprite.headless(size, size);

        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.SourceOver);
        int s = size;

        fillOval(pm, s / 6, s / 3, s * 2 / 3, s / 2, 160, 100, 60, 255);          // body
        pm.setColor(140 / 255f, 85 / 255f, 50 / 255f, 1f);
        pm.fillRectangle(s / 4, s / 4, s / 2, s / 8);                              // rim
        fillOval(pm, s / 4, s * 2 / 5, s / 6, s / 5, 190, 140, 90, 255);          // highlight
        fillOval(pm, s / 6, s * 3 / 4, s * 2 / 3, s / 6, 0, 0, 0, 40);            // shadow

        Texture tex = gfx.GdxTextureUtil.managedFromPixmap(pm);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return new Sprite(tex);
    }

    /** Filled ellipse inscribed in (x,y,w,h), matching the old g.fillOval semantics. */
    private static void fillOval(Pixmap pm, int x, int y, int w, int h, int r, int g, int b, int a) {
        pm.setColor(r / 255f, g / 255f, b / 255f, a / 255f);
        float rx = w / 2f, ry = h / 2f;
        float cx = x + rx, cy = y + ry;
        for (int yy = 0; yy < h; yy++) {
            float ny = (yy + 0.5f - ry) / ry;
            float span = (float) Math.sqrt(Math.max(0, 1 - ny * ny)) * rx;
            int x0 = Math.round(cx - span);
            int x1 = Math.round(cx + span);
            pm.drawLine(x0, (int) (cy - ry + yy), x1, (int) (cy - ry + yy));
        }
    }
}

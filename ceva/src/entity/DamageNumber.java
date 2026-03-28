package entity;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

import main.GamePanel;
import util.ObjectPool.Poolable;

/**
 * Lightweight floating damage/heal number that rises and fades out.
 * Pooled via ObjectPool to avoid per-hit allocation.
 */
public class DamageNumber extends Entity implements Poolable {

    private String text;
    private Color color;
    private float alpha;
    private float floatY;     // smooth Y position
    private int life;
    private int maxLife;
    private float riseSpeed;

    private static final Font FONT_DAMAGE = new Font("Arial", Font.BOLD, 16);
    private static final Font FONT_CRIT = new Font("Arial", Font.BOLD, 22);

    private boolean critical;

    public DamageNumber(GamePanel gp) {
        super(gp);
        this.alive = false;
    }

    /** Spawn a damage number at the given world position. */
    public void set(int worldX, int worldY, String text, Color color, boolean critical) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.floatY = worldY;
        this.text = text;
        this.color = color;
        this.critical = critical;
        this.maxLife = critical ? 50 : 40;
        this.life = maxLife;
        this.alpha = 1f;
        this.riseSpeed = critical ? 1.8f : 1.2f;
        this.alive = true;
    }

    @Override
    public void update() {
        floatY -= riseSpeed;
        riseSpeed *= 0.96f; // decelerate
        worldY = (int) floatY;
        life--;
        // Fade out in last 40% of life
        float fadeStart = maxLife * 0.4f;
        if (life < fadeStart) {
            alpha = Math.max(0f, life / fadeStart);
        }
        if (life <= 0) {
            alive = false;
        }
    }

    @Override
    public void draw(Graphics2D g2) {
        if (!alive || text == null) return;

        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = (int) floatY - gp.player.worldY + gp.player.screenY;

        changeAlpha(g2, alpha);

        Font font = critical ? FONT_CRIT : FONT_DAMAGE;
        g2.setFont(font);

        // Shadow
        g2.setColor(new Color(0, 0, 0, (int)(alpha * 180)));
        g2.drawString(text, screenX + 1, screenY + 1);

        // Text
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(alpha * 255)));
        g2.drawString(text, screenX, screenY);

        changeAlpha(g2, 1f);
    }

    @Override
    public void reset() {
        alive = false;
        text = null;
        color = null;
        life = 0;
        alpha = 0;
    }
}

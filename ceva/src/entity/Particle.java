package entity;

import java.awt.Color;
import java.awt.Graphics2D;

import main.GamePanel;
import main.ObjectPool.Poolable;

public class Particle extends Entity implements Poolable {

    Entity generator;
    Color color;
    int size;
    int xd;
    int yd;

    public Particle(GamePanel gp, Entity generator, Color color, int size, int speed, int maxLife, int xd, int yd) {

        super(gp);

        this.generator = generator;
        this.color = color;
        this.size = size;
        this.speed = speed;
        this.maxLife = maxLife;
        this.xd = xd;
        this.yd = yd;

        life = maxLife;
        // Only set world coordinates if generator is provided
        if (generator != null) {
            int offset = (gp.tileSize / 2) - (size / 2);
            worldX = generator.worldX + offset;
            worldY = generator.worldY + offset;
        }
    }

    public void update() {

        worldX += xd * speed;
        worldY += yd * speed;

        life--;

        if ( life <= maxLife / 3) {
            yd++;
        }

        if (life <= 0) {
            alive = false;
        }

    }

    public void draw(Graphics2D g2) {

        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        g2.setColor(color);
        g2.fillRect(screenX, screenY, size, size);

    }

    public void set(Entity generator, Color color, int size, int speed, int maxLife, int xd, int yd) {
        setWithPosition(generator, generator, color, size, speed, maxLife, xd, yd);
    }

    public void setWithPosition(Entity generator, Entity positionEntity, Color color, int size, int speed, int maxLife, int xd, int yd) {
        this.generator = generator;
        this.color = color;
        this.size = size;
        this.speed = speed;
        this.maxLife = maxLife;
        this.xd = xd;
        this.yd = yd;
        this.life = maxLife;
        this.alive = true;
        int offset = (gp.tileSize / 2) - (size / 2);
        // Position particles at the positionEntity (e.g., where the hit occurred, not where the projectile came from)
        this.worldX = positionEntity.worldX + offset;
        this.worldY = positionEntity.worldY + offset;
    }

    @Override
    public void reset() {
        alive = false;
        generator = null;
        color = null;
        size = 0;
        xd = 0;
        yd = 0;
        life = 0;
        worldX = 0;
        worldY = 0;
    }

}

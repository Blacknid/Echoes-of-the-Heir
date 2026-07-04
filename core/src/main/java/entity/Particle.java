package entity;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;

import main.GamePanel;
import util.ObjectPool.Poolable;

public class Particle extends Entity implements Poolable {

    Entity generator;
    Color color;
    int size;
    int xd;
    int yd;
    int style = STYLE_DEFAULT;
    int initialLife;
    float fx;
    float fy;
    float velocityX;
    float velocityY;

    public static final int STYLE_DEFAULT = 0;
    public static final int STYLE_BLOOD = 1;
    public static final int STYLE_HIT = 2;
    public static final int STYLE_DUST = 3;
    public static final int STYLE_SPARK = 4;
    public static final int STYLE_TRAIL = 5;
    public static final int STYLE_BOB = 6;

    public Sprite image;

    private static Sprite[] BOB_IMAGES = null;
    static Sprite getRandomBob(GamePanel gp) {
        if (BOB_IMAGES == null) {
            int sz = gp.tileSize;
            BOB_IMAGES = new Sprite[] {
                util.ResourceCache.loadScaledImageIfPresent("/res/effects/bob1.png", sz, sz),
                util.ResourceCache.loadScaledImageIfPresent("/res/effects/bob2.png", sz, sz),
                util.ResourceCache.loadScaledImageIfPresent("/res/effects/bob3.png", sz, sz)
            };
        }
        return BOB_IMAGES[(int)(Math.random() * 3)];
    }

    private static final Color BLOOD_OUTER = new Color(120, 15, 20);
    private static final Color HIT_GLOW = new Color(255, 220, 120);
    private static final Color HIT_CROSS = new Color(255, 255, 235);
    private static final Color SPARK_CORE = new Color(255, 255, 220);
    private static final Color SPARK_OUTER = new Color(255, 180, 60);
    private static final Color TRAIL_COLOR = new Color(200, 190, 170, 160);

    public Particle(GamePanel gp, Entity generator, Color color, int size, int speed, int maxLife, int xd, int yd) {

        super(gp);

        this.generator = generator;
        this.color = color;
        this.size = size;
        this.speed = speed;
        this.maxLife = maxLife;
        this.xd = xd;
        this.yd = yd;
        this.style = STYLE_DEFAULT;

        life = maxLife;
        initialLife = maxLife;
        // Only set world coordinates if generator is provided
        if (generator != null) {
            int offset = (gp.tileSize / 2) - (size / 2);
            worldX = generator.worldX + offset;
            worldY = generator.worldY + offset;
            fx = worldX;
            fy = worldY;
        }
    }

    public void update() {
        fx += velocityX;
        fy += velocityY;

        switch (style) {
            case STYLE_BLOOD:
                velocityX *= 0.90f;
                velocityY = velocityY * 0.93f + 0.11f;
                break;
            case STYLE_HIT:
                velocityX *= 0.88f;
                velocityY = velocityY * 0.90f + 0.08f;
                break;
            case STYLE_DUST:
                velocityX *= 0.94f;
                velocityY = velocityY * 0.94f + 0.05f;
                break;
            case STYLE_SPARK:
                velocityX *= 0.85f;
                velocityY *= 0.85f;
                break;
            case STYLE_TRAIL:
                velocityX *= 0.92f;
                velocityY = velocityY * 0.92f - 0.04f; // rises slightly
                break;
            case STYLE_BOB:
                velocityX *= 0.88f;
                velocityY = velocityY * 0.90f - 0.06f; // gentle rise
                break;
            default:
                velocityY += 0.09f;
                break;
        }

        worldX = (int) fx;
        worldY = (int) fy;

        life--;

        if (life <= 0) {
            alive = false;
        }

    }

    public void draw(GdxRenderer g2) {

        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        float alpha = 1.0f;
        if (initialLife > 0) {
            alpha = Math.max(0f, Math.min(1f, (float) life / initialLife));
        }

        changeAlpha(g2, alpha);

        switch (style) {
            case STYLE_BLOOD:
                g2.setColor(BLOOD_OUTER);
                g2.fillOval(screenX, screenY, size, size);
                g2.setColor(color);
                int bloodCore = Math.max(2, size - 3);
                g2.fillOval(screenX + 1, screenY + 1, bloodCore, bloodCore);
                break;
            case STYLE_HIT:
                int glow = size + 3;
                g2.setColor(HIT_GLOW);
                g2.fillOval(screenX - 1, screenY - 1, glow, glow);
                g2.setColor(color);
                g2.fillOval(screenX, screenY, size, size);
                g2.setColor(HIT_CROSS);
                g2.fillRect(screenX + size / 2, screenY - 1, 1, size + 2);
                g2.fillRect(screenX - 1, screenY + size / 2, size + 2, 1);
                break;
            case STYLE_SPARK:
                g2.setColor(SPARK_OUTER);
                g2.fillOval(screenX - 1, screenY - 1, size + 2, size + 2);
                g2.setColor(SPARK_CORE);
                g2.fillOval(screenX, screenY, size, size);
                break;
            case STYLE_TRAIL:
                g2.setColor(TRAIL_COLOR);
                g2.fillOval(screenX, screenY, size, size);
                break;
            case STYLE_BOB:
                if (image != null) {
                    // progress 1→0 as particle ages
                    float progress = (float) life / initialLife;
                    // size: grows from 0.35 to 1.0 in the first 40% of life, then holds at 1.0
                    float sizeScale = progress > 0.6f
                        ? 0.35f + (1.0f - progress) / 0.4f * 0.65f
                        : 1.0f;
                    // opacity: full for first 30%, then linear fade to 0
                    float bobAlpha = progress > 0.7f ? 0.55f : (progress / 0.7f) * 0.55f;
                    int drawSize = Math.max(1, (int)(size * sizeScale));
                    int cx = screenX + size / 2 - drawSize / 2;
                    int cy = screenY + size / 2 - drawSize / 2;
                    changeAlpha(g2, bobAlpha);
                    g2.drawImage(image, cx, cy, drawSize, drawSize);
                }
                break;
            default:
                g2.setColor(color);
                g2.fillRect(screenX, screenY, size, size);
                break;
        }

        changeAlpha(g2, 1f);

    }

    public void set(Entity generator, Color color, int size, int speed, int maxLife, int xd, int yd) {
        setWithPosition(generator, generator, color, size, speed, maxLife, xd, yd);
    }

    public void setWithPosition(Entity generator, Entity positionEntity, Color color, int size, int speed, int maxLife, int xd, int yd) {
        setWithPosition(generator, positionEntity, color, size, speed, maxLife, xd, yd,
            (generator != null ? generator.getParticleStyle() : STYLE_DEFAULT));
    }

    public void setWithPosition(Entity generator, Entity positionEntity, Color color, int size, int speed, int maxLife, int xd, int yd, int style) {
        this.generator = generator;
        this.color = color;
        this.size = size;
        this.speed = speed;
        this.maxLife = maxLife;
        this.xd = xd;
        this.yd = yd;
        this.style = style;
        this.life = maxLife;
        this.initialLife = maxLife;
        this.alive = true;
        int offset = (gp.tileSize / 2) - (size / 2);
        // Position particles at the positionEntity (e.g., where the hit occurred, not where the projectile came from)
        this.fx = positionEntity.worldX + offset;
        this.fy = positionEntity.worldY + offset;
        this.worldX = (int) this.fx;
        this.worldY = (int) this.fy;

        float randomX = (float) ((Math.random() - 0.5) * 0.6);
        float randomY = (float) ((Math.random() - 0.5) * 0.6);
        this.velocityX = xd * speed + randomX;
        this.velocityY = yd * speed + randomY;
    }

    @Override
    public void reset() {
        alive = false;
        generator = null;
        color = null;
        size = 0;
        xd = 0;
        yd = 0;
        style = STYLE_DEFAULT;
        initialLife = 0;
        fx = 0;
        fy = 0;
        velocityX = 0;
        velocityY = 0;
        life = 0;
        worldX = 0;
        worldY = 0;
        image = null;
    }

}

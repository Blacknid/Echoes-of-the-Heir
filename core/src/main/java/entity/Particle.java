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
    // Image-based debris burst (e.g. destroyed grass): tumbles outward, shrinks, and fades. Unlike
    // STYLE_BOB it uses whatever Sprite the generator supplies via getParticleImage() — no fixed
    // asset set — so any destructible tile can reuse this just by returning its own sprite.
    public static final int STYLE_IMAGE_DEBRIS = 7;
    // Static one-shot stamp at a fixed world point (e.g. Strike_impact.png where a swing hit a wall/
    // object) — no movement, no tumble. Plays through `frames` (if set) as a simple flipbook over its
    // lifetime, else just shows `image` and fades.
    public static final int STYLE_IMPACT = 8;

    public Sprite image;
    public Sprite[] frames; // STYLE_IMPACT: optional flipbook, played once over the particle's life
    public float rotationDeg; // STYLE_IMAGE_DEBRIS: current tumble rotation

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
            case STYLE_IMAGE_DEBRIS:
                velocityX *= 0.90f;
                velocityY = velocityY * 0.92f + 0.10f; // falls back down like a flicked-off chunk
                rotationDeg += xd * 14f; // tumble; direction/speed come from the burst's xd sign
                break;
            case STYLE_IMPACT:
                break; // static — no velocity, just ages out (fade handled in draw())
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

        int screenX = worldX - gp.getCamWorldX() + gp.player.screenX;
        int screenY = worldY - gp.getCamWorldY() + gp.player.screenY;

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
            case STYLE_IMAGE_DEBRIS:
                if (image != null) {
                    float progress = (float) life / initialLife; // 1 -> 0 as it ages
                    changeAlpha(g2, Math.max(0f, progress));
                    g2.drawImageRotated(image, screenX, screenY, size, size,
                            size / 2f, size / 2f, rotationDeg);
                }
                break;
            case STYLE_IMPACT:
                Sprite frame = image;
                float age = 1f - (float) life / initialLife; // 0 -> 1 as it ages
                if (frames != null && frames.length > 0) {
                    int idx = Math.min(frames.length - 1, (int) (age * frames.length));
                    frame = frames[idx];
                }
                if (frame != null) {
                    // Punchy pop-in: scales up from 60% to 100% over the first fifth of its life,
                    // then holds at full size — reads as an impact rather than a static stamp.
                    float growT = Math.min(1f, age / 0.2f);
                    float scale = 0.6f + 0.4f * growT;
                    int drawSize = Math.max(1, Math.round(size * scale));
                    int cx = screenX + (size - drawSize) / 2;
                    int cy = screenY + (size - drawSize) / 2;

                    // Quick fade only on the last stretch of life, so the animation reads at full
                    // strength and just tapers out at the very end instead of fading the whole time.
                    float progress = (float) life / initialLife; // 1 -> 0 as it ages
                    float fadeAlpha = Math.min(1f, progress / 0.25f);
                    changeAlpha(g2, Math.max(0f, fadeAlpha));
                    g2.drawImage(frame, cx, cy, drawSize, drawSize);
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

    /**
     * Configures this (pooled) particle as an image-debris chunk flying outward at {@code angleRad}
     * from {@code centerX/centerY}, tumbling as it goes. Used for destructible-tile "burst" effects
     * (e.g. cut grass) that want a scattered radial pop rather than the fixed 4-direction burst from
     * {@link Entity#generateParticle}.
     */
    public void setAsImageDebris(Entity generator, Sprite sprite, int centerX, int centerY, int size,
                                  float angleRad, float burstSpeed, int lifeTicks) {
        this.generator = generator;
        this.image = sprite;
        this.color = null;
        this.size = size;
        this.style = STYLE_IMAGE_DEBRIS;
        this.rotationDeg = (float) (Math.random() * 360);
        this.xd = (Math.random() < 0.5) ? -1 : 1; // tumble direction
        this.life = lifeTicks;
        this.initialLife = lifeTicks;
        this.alive = true;
        this.fx = centerX - size / 2f;
        this.fy = centerY - size / 2f;
        this.worldX = (int) this.fx;
        this.worldY = (int) this.fy;
        this.velocityX = (float) Math.cos(angleRad) * burstSpeed;
        this.velocityY = (float) Math.sin(angleRad) * burstSpeed - 0.6f; // slight upward pop
    }

    /** Static one-shot image stamp at a fixed world point (centered), fading out over lifeTicks. */
    public void setAsImpact(Sprite sprite, int centerX, int centerY, int size, int lifeTicks) {
        setAsImpact(sprite, null, centerX, centerY, size, lifeTicks);
    }

    /**
     * Static one-shot stamp at a fixed world point (centered). If {@code frames} is non-null, plays
     * through them once as a flipbook over {@code lifeTicks}; otherwise just shows {@code sprite}.
     */
    public void setAsImpact(Sprite sprite, Sprite[] frames, int centerX, int centerY, int size, int lifeTicks) {
        this.generator = null;
        this.image = sprite;
        this.frames = frames;
        this.color = null;
        this.size = size;
        this.style = STYLE_IMPACT;
        this.life = lifeTicks;
        this.initialLife = lifeTicks;
        this.alive = true;
        this.fx = centerX - size / 2f;
        this.fy = centerY - size / 2f;
        this.worldX = (int) this.fx;
        this.worldY = (int) this.fy;
        this.velocityX = 0f;
        this.velocityY = 0f;
        // renderSorter/tile-interleave both key off "bottom edge" (tiles use worldY + tileSize; see
        // TileManager's sortY). worldY here is the sprite's TOP-LEFT corner (centerY - size/2), so
        // solidArea.height = size makes the sort key resolve to the stamp's BOTTOM edge
        // (centerY + size/2) — at least as deep as the wall tile it's stamped on, so it draws in
        // front of that tile instead of being hidden behind it (the class-default 48px box put the
        // key well above the tile's own bottom-edge key, which is why it drew underneath).
        this.solidArea.x = 0;
        this.solidArea.y = 0;
        this.solidArea.width = size;
        this.solidArea.height = size;
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
        frames = null;
        rotationDeg = 0;
        // setAsImpact() customizes solidArea for correct Y-sorting; restore the class default so a
        // pooled reuse for any other style (which never touches solidArea) isn't left with it.
        solidArea.x = 0;
        solidArea.y = 0;
        solidArea.width = 48;
        solidArea.height = 48;
    }

}

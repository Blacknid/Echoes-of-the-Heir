package tile;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;
import gfx.geom.Rect;

import entity.Entity;
import main.GamePanel;
import util.ResourceCache;
import util.UtilityTool;

public class IT_Grass extends interactiveTile {

    // Shared across all instances — loaded once
    private static Sprite imgBlade1 = null;
    private static Sprite imgBlade2 = null;

    // Per-instance sway state — blade 1
    private float phase1;
    private final float speed1;
    private float angle1;

    // Per-instance sway state — blade 2
    private float phase2;
    private final float speed2;
    private float angle2;

    // Deflect on player contact
    private float deflect1 = 0f, deflect2 = 0f;
    private static final float DEFLECT_ANGLE = 0.45f; // ~26 degrees

    private static final float MAX_SWAY = 0.21f; // ~12 degrees

    public IT_Grass(GamePanel gp, int col, int row) {
        super(gp, col, row);
        destructible = true;
        collision = false; // grass doesn't block movement

        // Load blade images once
        if (imgBlade1 == null) {
            Sprite raw1 = ResourceCache.loadImageIfPresent("/res/interactive/Grass_Blade1.png");
            Sprite raw2 = ResourceCache.loadImageIfPresent("/res/interactive/Grass_Blade2.png");
            System.out.println("[IT_Grass] blade1=" + raw1 + " blade2=" + raw2 + " tileSize=" + gp.tileSize);
            if (raw1 != null) imgBlade1 = UtilityTool.scaleImage(raw1, gp.tileSize, gp.tileSize);
            if (raw2 != null) imgBlade2 = UtilityTool.scaleImage(raw2, gp.tileSize, gp.tileSize);
        }
        System.out.println("[IT_Grass] spawned at col=" + col + " row=" + row + " worldX=" + worldX + " worldY=" + worldY);

        // Randomise phase/speed per tile so neighbours don't sync
        float seed = (col * 7 + row * 13) & 0xFF; // 0..255
        phase1 = seed * 0.0245f;
        phase2 = phase1 + 1.1f; // offset so they're out of phase with each other
        speed1 = 0.018f + (seed % 17) * 0.0012f;
        speed2 = 0.022f + (seed % 11) * 0.0015f;
    }

    @Override
    public void update() {
        super.update();

        // Idle wind sway
        phase1 += speed1;
        phase2 += speed2;
        angle1 = (float) Math.sin(phase1) * MAX_SWAY;
        angle2 = (float) Math.sin(phase2) * MAX_SWAY;

        // Detect player hitbox overlap with this tile's area
        Rect p = gp.player.solidArea;
        int px1 = gp.player.worldX + p.x;
        int py1 = gp.player.worldY + p.y;
        int px2 = px1 + p.width;
        int py2 = py1 + p.height;
        int tx1 = worldX;
        int ty1 = worldY;
        int tx2 = worldX + gp.tileSize;
        int ty2 = worldY + gp.tileSize;
        float targetDeflect1 = 0f, targetDeflect2 = 0f;
        if (px2 > tx1 && px1 < tx2 && py2 > ty1 && py1 < ty2) {
            int dx = (px1 + p.width / 2) - (worldX + gp.tileSize / 2);
            float pushSign = dx >= 0 ? -1f : 1f;
            targetDeflect1 = pushSign * DEFLECT_ANGLE;
            targetDeflect2 = pushSign * DEFLECT_ANGLE * 0.75f;
        }

        // Smooth lerp toward target (in or back to zero)
        deflect1 += (targetDeflect1 - deflect1) * 0.12f;
        deflect2 += (targetDeflect2 - deflect2) * 0.09f;
        if (Math.abs(deflect1) < 0.001f) deflect1 = 0f;
        if (Math.abs(deflect2) < 0.001f) deflect2 = 0f;
    }

    @Override
    public void draw(GdxRenderer g2) {
        System.out.println("[IT_Grass] draw called imgBlade1=" + imgBlade1 + " imgBlade2=" + imgBlade2);
        if (imgBlade1 == null && imgBlade2 == null) return;

        int screenX = worldX - gp.getCamWorldX() + gp.player.screenX;
        int screenY = worldY - gp.getCamWorldY() + gp.player.screenY;

        // Cull off-screen
        if (worldX + gp.tileSize <= gp.getCamWorldX() - gp.player.screenX ||
            worldX - gp.tileSize >= gp.getCamWorldX() + (gp.screenWidth - gp.player.screenX) ||
            worldY + gp.tileSize <= gp.getCamWorldY() - gp.player.screenY ||
            worldY - gp.tileSize >= gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY)) return;

        // Anchor = bottom-center of the tile (rotation pivot, relative to the sprite's top-left)
        float originX = gp.tileSize * 0.5f;
        float originY = gp.tileSize;

        if (imgBlade1 != null) {
            g2.drawImageRotated(imgBlade1, screenX, screenY, gp.tileSize, gp.tileSize,
                    originX, originY, (float) Math.toDegrees(angle1 + deflect1));
        }

        if (imgBlade2 != null) {
            g2.drawImageRotated(imgBlade2, screenX, screenY, gp.tileSize, gp.tileSize,
                    originX, originY, (float) Math.toDegrees(angle2 + deflect2));
        }
    }

    @Override
    public boolean isCorrectItem(Entity entity) {
        return entity.currentWeapon != null;
    }

    @Override public Color getParticleColor()  { return new Color(80, 160, 60); }
    @Override public int   getParticleSize()   { return 6; }
    @Override public int   getParticleSpeed()  { return 1; }
    @Override public int   getParticleMaxLife(){ return 18; }
}

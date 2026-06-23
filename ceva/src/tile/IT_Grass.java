package tile;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import entity.Entity;
import main.GamePanel;
import util.ResourceCache;
import util.UtilityTool;

public class IT_Grass extends interactiveTile {

    // Shared across all instances — loaded once
    private static BufferedImage imgBlade1 = null;
    private static BufferedImage imgBlade2 = null;

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
    private int deflectTimer1 = 0, deflectTimer2 = 0;
    private static final int DEFLECT_FRAMES = 22;
    private static final float DEFLECT_ANGLE = 0.45f; // ~26 degrees

    private static final float MAX_SWAY = 0.21f; // ~12 degrees

    public IT_Grass(GamePanel gp, int col, int row) {
        super(gp, col, row);
        destructible = true;
        collision = false; // grass doesn't block movement

        // Load blade images once
        if (imgBlade1 == null) {
            BufferedImage raw1 = ResourceCache.loadImageIfPresent("/res/interactive/Grass_Blade1.png");
            BufferedImage raw2 = ResourceCache.loadImageIfPresent("/res/interactive/Grass_Blade2.png");
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

        // Detect player proximity and push blades away
        int dx = (gp.player.worldX + gp.tileSize / 2) - (worldX + gp.tileSize / 2);
        int dy = (gp.player.worldY + gp.tileSize / 2) - (worldY + gp.tileSize / 2);
        int dist = (int) Math.sqrt(dx * dx + dy * dy);
        if (dist < gp.tileSize) {
            // Push direction: blades lean away from player
            float pushSign = dx >= 0 ? -1f : 1f;
            deflect1 = pushSign * DEFLECT_ANGLE;
            deflect2 = pushSign * DEFLECT_ANGLE * 0.75f;
            deflectTimer1 = DEFLECT_FRAMES;
            deflectTimer2 = DEFLECT_FRAMES;
        }

        // Decay deflect back to zero
        if (deflectTimer1 > 0) {
            deflectTimer1--;
            deflect1 *= 0.85f;
        } else {
            deflect1 = 0f;
        }
        if (deflectTimer2 > 0) {
            deflectTimer2--;
            deflect2 *= 0.85f;
        } else {
            deflect2 = 0f;
        }
    }

    @Override
    public void draw(Graphics2D g2) {
        System.out.println("[IT_Grass] draw called imgBlade1=" + imgBlade1 + " imgBlade2=" + imgBlade2);
        if (imgBlade1 == null && imgBlade2 == null) return;

        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        // Cull off-screen
        if (worldX + gp.tileSize <= gp.player.worldX - gp.player.screenX ||
            worldX - gp.tileSize >= gp.player.worldX + gp.player.screenX ||
            worldY + gp.tileSize <= gp.player.worldY - gp.player.screenY ||
            worldY - gp.tileSize >= gp.player.worldY + gp.player.screenY) return;

        // Anchor = bottom-center of the tile
        double anchorX = screenX + gp.tileSize * 0.5;
        double anchorY = screenY + gp.tileSize;

        AffineTransform saved = g2.getTransform();

        if (imgBlade1 != null) {
            g2.rotate(angle1 + deflect1, anchorX, anchorY);
            g2.drawImage(imgBlade1, screenX, screenY, gp.tileSize, gp.tileSize, null);
            g2.setTransform(saved);
        }

        if (imgBlade2 != null) {
            g2.rotate(angle2 + deflect2, anchorX, anchorY);
            g2.drawImage(imgBlade2, screenX, screenY, gp.tileSize, gp.tileSize, null);
            g2.setTransform(saved);
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

package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import main.GamePanel;

public class Lightning {

    GamePanel gp;
    BufferedImage darknessFilter;
    public int dayState;
    
    // Pre-cached darkness levels (avoids creating Color objects every frame)
    Color[] darknessLevels = new Color[100]; 

    // OPTIMIZATION: Pre-computed squared distance lookup for player light
    private static final int LIGHT_RADIUS = 7;
    
    // OPTIMIZATION: Pre-computed alpha lookup table for player light
    // Index by (distSq * 100), maps squared distance to alpha [0..1]
    // Max squared distance we care about = LIGHT_RADIUS^2 = 49
    private static final int LIGHT_LUT_SIZE = 50; // 0..49
    private final float[] lightAlphaLUT = new float[LIGHT_LUT_SIZE + 1];

    // ===================== COLORED LIGHT REGISTRY =====================
    private static final int MAX_LIGHTS = 20;
    public int[] lightWX = new int[MAX_LIGHTS];
    public int[] lightWY = new int[MAX_LIGHTS];
    public int[] lightRadiusPx = new int[MAX_LIGHTS];   // radius in pixels
    public Color[] lightColor = new Color[MAX_LIGHTS];
    public float[] lightIntensity = new float[MAX_LIGHTS];
    public int lightCount = 0;

    // Gradient image cache keyed by (r << 48 | g << 40 | b << 32 | radiusPx)
    private final HashMap<Long, BufferedImage> gradientCache = new HashMap<>();

    public Lightning(GamePanel gp2) {
        this.gp = gp2;
        
        for (int i = 0; i < darknessLevels.length; i++) {
            float alpha = Math.min(1f, Math.max(0f, i / 100f));
            darknessLevels[i] = new Color(0, 0, 0, alpha);
        }
        
        // Pre-compute light alpha: alpha = sqrt(distSq) / LIGHT_RADIUS
        for (int i = 0; i <= LIGHT_LUT_SIZE; i++) {
            lightAlphaLUT[i] = (float) Math.sqrt(i) / LIGHT_RADIUS;
        }
    }

    // ===================== COLORED LIGHT API =====================
    public void clearLights() {
        lightCount = 0;
    }

    public void addLight(int worldX, int worldY, int radiusPx, Color color, float intensity) {
        if (lightCount >= MAX_LIGHTS) return;
        lightWX[lightCount] = worldX;
        lightWY[lightCount] = worldY;
        lightRadiusPx[lightCount] = radiusPx;
        lightColor[lightCount] = color;
        lightIntensity[lightCount] = intensity;
        lightCount++;
    }

    private BufferedImage getGradient(Color c, int radiusPx) {
        long key = ((long)(c.getRed() & 0xFF) << 48)
                 | ((long)(c.getGreen() & 0xFF) << 40)
                 | ((long)(c.getBlue() & 0xFF) << 32)
                 | (radiusPx & 0xFFFFFFFFL);
        BufferedImage img = gradientCache.get(key);
        if (img != null) return img;

        int diameter = radiusPx * 2;
        img = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        Point2D center = new Point2D.Float(radiusPx, radiusPx);
        float[] dist = {0.0f, 0.4f, 1.0f};
        Color cCenter = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
        Color cMid    = new Color(c.getRed(), c.getGreen(), c.getBlue(), 80);
        Color cEdge   = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        RadialGradientPaint rgp = new RadialGradientPaint(center, radiusPx, dist, new Color[]{cCenter, cMid, cEdge});
        ig.setPaint(rgp);
        ig.fillOval(0, 0, diameter, diameter);
        ig.dispose();
        gradientCache.put(key, img);
        return img;
    }

    public void draw(Graphics2D g2, float currentMaxDarkness) {
        if (currentMaxDarkness <= 0.001f) return; // Skip if no darkness

        // Use camera position for viewport bounds so light stays centered on-screen
        float camCenterX = gp.cameraX + gp.player.screenX;
        float camCenterY = gp.cameraY + gp.player.screenY;
        int viewCenterCol = (int)(camCenterX / gp.tileSize);
        int viewCenterRow = (int)(camCenterY / gp.tileSize);

        // Sub-tile fractional player position for smooth light centering
        float playerTileColF = (float) gp.player.worldX / gp.tileSize;
        float playerTileRowF = (float) gp.player.worldY / gp.tileSize;

        // OPTIMIZATION: Tighter viewport bounds (only visible tiles + 1 margin)
        int screenTilesX = (gp.screenWidth / gp.tileSize) / 2 + 2;
        int screenTilesY = (gp.screenHeight / gp.tileSize) / 2 + 2;
        int leftCol = viewCenterCol - screenTilesX;
        int rightCol = viewCenterCol + screenTilesX;
        int topRow = viewCenterRow - screenTilesY;
        int bottomRow = viewCenterRow + screenTilesY;

        // Pre-compute torch data to avoid inner-loop array access
        int torchCount = 0;
        int[] torchCol = new int[gp.obj.length];
        int[] torchRow = new int[gp.obj.length];
        int[] torchRadiusSq = new int[gp.obj.length];
        float[] torchRadiusInv = new float[gp.obj.length];
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] != null && gp.obj[i].lightSource && gp.obj[i].lightRadius > 0) {
                torchCol[torchCount] = gp.obj[i].worldX / gp.tileSize;
                torchRow[torchCount] = gp.obj[i].worldY / gp.tileSize;
                torchRadiusSq[torchCount] = gp.obj[i].lightRadius * gp.obj[i].lightRadius;
                torchRadiusInv[torchCount] = 1.0f / gp.obj[i].lightRadius;
                torchCount++;
            }
        }

        int tileSize = gp.tileSize;
        int playerWorldX = gp.player.worldX;
        int playerWorldY = gp.player.worldY;
        int playerScreenX = gp.player.screenX;
        int playerScreenY = gp.player.screenY;
        
        // OPTIMIZATION: Pre-compute clamped maxDarkness alpha index to avoid per-tile clamping
        int maxAlphaIndex = (int)(currentMaxDarkness * 99);
        if (maxAlphaIndex > 99) maxAlphaIndex = 99;

        for (int col = leftCol; col <= rightCol; col++) {
            for (int row = topRow; row <= bottomRow; row++) {

                int screenX = col * tileSize - playerWorldX + playerScreenX;
                int screenY = row * tileSize - playerWorldY + playerScreenY;

                // OPTIMIZATION: Use integer distance squared and LUT instead of floating point sqrt
                float distX = col - playerTileColF;
                float distY = row - playerTileRowF;
                float distSq = distX * distX + distY * distY;
                
                // Use LUT for player light alpha (avoids sqrt per tile)
                float darknessAlpha;
                int distSqInt = (int)(distSq + 0.5f);
                if (distSqInt <= LIGHT_LUT_SIZE) {
                    darknessAlpha = lightAlphaLUT[distSqInt];
                } else {
                    darknessAlpha = 1.0f; // Beyond light radius = full darkness
                }

                // Check torches - also use squared distance with inverse radius
                for (int t = 0; t < torchCount; t++) {
                    float tDx = col - torchCol[t];
                    float tDy = row - torchRow[t];
                    float tDistSq = tDx * tDx + tDy * tDy;
                    // Skip torch if further than current best alpha (squared comparison)
                    if (tDistSq >= torchRadiusSq[t] && darknessAlpha <= 1.0f) continue;
                    float torchAlpha;
                    int tDistSqInt = (int)(tDistSq + 0.5f);
                    // For torches, compute alpha using pre-computed inverse radius
                    // alpha = sqrt(distSq) * invRadius
                    torchAlpha = (float) Math.sqrt(tDistSq) * torchRadiusInv[t];
                    if (torchAlpha < darknessAlpha) darknessAlpha = torchAlpha;
                }

                // Clamp and index into pre-cached colors
                int alphaIndex;
                if (darknessAlpha >= currentMaxDarkness) {
                    alphaIndex = maxAlphaIndex;
                } else if (darknessAlpha <= 0) {
                    continue; // No darkness tile to draw at all
                } else {
                    alphaIndex = (int)(darknessAlpha * 99);
                }

                g2.setColor(darknessLevels[alphaIndex]);
                g2.fillRect(screenX, screenY, tileSize, tileSize);
            }
        }

        // ===================== COLORED LIGHT PASS =====================
        if (lightCount > 0 && currentMaxDarkness > 0.05f) {
            Composite saved = g2.getComposite();
            for (int i = 0; i < lightCount; i++) {
                int lx = lightWX[i] - playerWorldX + playerScreenX;
                int ly = lightWY[i] - playerWorldY + playerScreenY;
                int rad = lightRadiusPx[i];
                // Frustum cull
                if (lx + rad < 0 || lx - rad > gp.screenWidth || ly + rad < 0 || ly - rad > gp.screenHeight) continue;

                BufferedImage grad = getGradient(lightColor[i], rad);
                float alpha = lightIntensity[i] * currentMaxDarkness;
                if (alpha > 1f) alpha = 1f;
                if (alpha < 0.01f) continue;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.drawImage(grad, lx - rad, ly - rad, null);
            }
            g2.setComposite(saved);
        }
    }
}
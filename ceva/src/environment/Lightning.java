package environment;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import main.GamePanel;

public class Lightning {

    GamePanel gp;
    BufferedImage darknessFilter;
    public int dayState;
    
    // Pre-cached darkness levels (avoids creating Color objects every frame)
    Color[] darknessLevels = new Color[100]; 

    // OPTIMIZATION: Pre-computed squared distance lookup for player light
    // Avoids Math.sqrt() per tile per frame
    private static final int LIGHT_RADIUS = 7;
    private static final float LIGHT_RADIUS_SQ = LIGHT_RADIUS * LIGHT_RADIUS;

    public Lightning(GamePanel gp2) {
        this.gp = gp2;
        
        for (int i = 0; i < darknessLevels.length; i++) {
            float alpha = Math.min(1f, Math.max(0f, i / 100f));
            darknessLevels[i] = new Color(0, 0, 0, alpha);
        }
    }

    public void draw(Graphics2D g2, float currentMaxDarkness) {
        if (currentMaxDarkness <= 0.001f) return; // Skip if no darkness

        // Use camera position for viewport bounds so light stays centered on-screen
        // even when camera lags behind during dodge
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

        for (int col = leftCol; col <= rightCol; col++) {
            for (int row = topRow; row <= bottomRow; row++) {

                int screenX = col * tileSize - playerWorldX + playerScreenX;
                int screenY = row * tileSize - playerWorldY + playerScreenY;

                // Use fractional player position for smooth light centering
                float distX = col - playerTileColF;
                float distY = row - playerTileRowF;
                float distSq = distX * distX + distY * distY;
                float darknessAlpha = (float) Math.sqrt(distSq) / LIGHT_RADIUS;

                // Check torches with squared distance
                for (int t = 0; t < torchCount; t++) {
                    float tDx = col - torchCol[t];
                    float tDy = row - torchRow[t];
                    float tDistSq = tDx * tDx + tDy * tDy;
                    float torchAlpha = (float) Math.sqrt(tDistSq) * torchRadiusInv[t];
                    if (torchAlpha < darknessAlpha) darknessAlpha = torchAlpha;
                }

                if (darknessAlpha > currentMaxDarkness) {
                    darknessAlpha = currentMaxDarkness;
                }
                if (darknessAlpha < 0) darknessAlpha = 0f;

                int alphaIndex = (int)(darknessAlpha * 99);
                g2.setColor(darknessLevels[alphaIndex]);
                g2.fillRect(screenX, screenY, tileSize, tileSize);
            }
        }
    }
}
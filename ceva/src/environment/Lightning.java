package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import main.GamePanel;

public class Lightning {

    GamePanel gp;
    BufferedImage darknessFilter;
    public int dayState; // Optional: To control Day/Night cycles later
    
    // Arrays to cache light levels for performance
    // This prevents creating new Color objects every single frame
    Color[] darknessLevels = new Color[100]; 

    public Lightning(GamePanel gp2) {
        this.gp = gp2;
        
        // 1. Pre-calculate darkness levels (0% to 100% opacity)
        // This saves processing power during the render loop
        for (int i = 0; i < darknessLevels.length; i++) {
            float alpha = i / 100f; 
            // Clamp alpha to ensure it doesn't exceed 1.0 or drop below 0
            if(alpha > 1f) alpha = 1f;
            if(alpha < 0f) alpha = 0f;
            
            darknessLevels[i] = new Color(0, 0, 0, alpha);
        }
    }

    // Pass the alpha from EnvironmentManager into this method
    public void draw(Graphics2D g2, float currentMaxDarkness) {

        // We only need the lightRadius for the player and torches
        int playerLightRadius = 7; 

        // Loop through visible tiles
        int leftCol = (gp.player.worldX / gp.tileSize) - 20;
        int rightCol = (gp.player.worldX / gp.tileSize) + 20;
        int topRow = (gp.player.worldY / gp.tileSize) - 20;
        int bottomRow = (gp.player.worldY / gp.tileSize) + 20;

        for (int col = leftCol; col <= rightCol; col++) {
            for (int row = topRow; row <= bottomRow; row++) {

                int screenX = col * gp.tileSize - gp.player.worldX + gp.player.screenX;
                int screenY = row * gp.tileSize - gp.player.worldY + gp.player.screenY;

                // Calculate distance to player
                double distX = Math.abs(col - (gp.player.worldX / gp.tileSize));
                double distY = Math.abs(row - (gp.player.worldY / gp.tileSize));
                double distance = Math.sqrt(distX * distX + distY * distY);

                // Base darkness for this tile
                float darknessAlpha = (float) (distance / playerLightRadius);

                // Check Torches
                for (int i = 0; i < gp.obj.length; i++) {
                    if (gp.obj[i] != null && gp.obj[i].lightSource) {
                        double oDistX = Math.abs(col - (gp.obj[i].worldX / gp.tileSize));
                        double oDistY = Math.abs(row - (gp.obj[i].worldY / gp.tileSize));
                        double oDist = Math.sqrt(oDistX * oDistX + oDistY * oDistY);

                        float torchAlpha = (float) (oDist / gp.obj[i].lightRadius);
                        if (torchAlpha < darknessAlpha) darknessAlpha = torchAlpha;
                    }
                }

                // CRITICAL CHANGE: 
                // Instead of clamping to a static 0.95f, we clamp to the current Day/Night alpha
                if (darknessAlpha > currentMaxDarkness) {
                    darknessAlpha = currentMaxDarkness;
                }

                if (darknessAlpha < 0) darknessAlpha = 0f;

                int alphaIndex = (int)(darknessAlpha * 99);
                g2.setColor(darknessLevels[alphaIndex]);
                g2.fillRect(screenX, screenY, gp.tileSize, gp.tileSize);
            }
        }
    }
}
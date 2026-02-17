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

    public void draw(Graphics2D g2) {
        int lightRadius = 7; // Player's default light radius
        float maxDarkness = 0.95f;
        
        // 1. Get visible area
        int leftCol = (gp.player.worldX / gp.tileSize) - 20;
        int rightCol = (gp.player.worldX / gp.tileSize) + 20;
        int topRow = (gp.player.worldY / gp.tileSize) - 20;
        int bottomRow = (gp.player.worldY / gp.tileSize) + 20;
        
        for (int col = leftCol; col <= rightCol; col++) {
            for (int row = topRow; row <= bottomRow; row++) {
            
                int screenX = col * gp.tileSize - gp.player.worldX + gp.player.screenX;
                int screenY = row * gp.tileSize - gp.player.worldY + gp.player.screenY;
            
                // 2. Start with Player's light
                double distX = Math.abs(col - (gp.player.worldX / gp.tileSize));
                double distY = Math.abs(row - (gp.player.worldY / gp.tileSize));
                double distance = Math.sqrt(distX * distX + distY * distY);
                
                float darknessAlpha = (float) (distance / lightRadius);
            
                // 3. CHECK OTHER LIGHT SOURCES (Torches, NPCs, etc.)
                // We loop through objects or NPCs to see if they are closer to this tile
                for (int i = 0; i < gp.obj.length; i++) {
                    if (gp.obj[i] != null && gp.obj[i].lightSource) {
                        
                        double oDistX = Math.abs(col - (gp.obj[i].worldX / gp.tileSize));
                        double oDistY = Math.abs(row - (gp.obj[i].worldY / gp.tileSize));
                        double oDistance = Math.sqrt(oDistX * oDistX + oDistY * oDistY);
                        
                        float torchAlpha = (float) (oDistance / gp.obj[i].lightRadius);
                        
                        // We take the SMALLEST alpha (because 0 is bright, 1 is dark)
                        if (torchAlpha < darknessAlpha) {
                            darknessAlpha = torchAlpha;
                        }
                    }
                }
            
                // 4. Final Clamping and Drawing
                if (darknessAlpha > maxDarkness) darknessAlpha = maxDarkness;
                if (darknessAlpha < 0) darknessAlpha = 0f;
            
                int alphaIndex = (int)(darknessAlpha * 99);
                g2.setColor(darknessLevels[alphaIndex]);
                g2.fillRect(screenX, screenY, gp.tileSize, gp.tileSize);
            }
        }
    }
}
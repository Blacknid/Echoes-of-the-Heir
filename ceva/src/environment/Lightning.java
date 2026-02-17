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
        
        // Settings for your light
        int lightRadius = 7; // How many tiles far the light reaches
        float maxDarkness = 0.95f; // The darkest the screen gets (0.0 to 1.0)

        // Optimization: Only loop through tiles currently visible on screen
        // We add a simplified buffer (-10 and +10) to ensure edges are drawn smoothly
        int leftCol = (gp.player.worldX / gp.tileSize) - 20;
        int rightCol = (gp.player.worldX / gp.tileSize) + 20;
        int topRow = (gp.player.worldY / gp.tileSize) - 20;
        int bottomRow = (gp.player.worldY / gp.tileSize) + 20;

        for (int col = leftCol; col <= rightCol; col++) {
            for (int row = topRow; row <= bottomRow; row++) {
                
                // Calculate the Screen X and Y for this specific tile
                int screenX = col * gp.tileSize - gp.player.worldX + gp.player.screenX;
                int screenY = row * gp.tileSize - gp.player.worldY + gp.player.screenY;

                // 1. Calculate distance from player (in tiles)
                // We divide by tileSize to work with grid coordinates, not pixels
                double distX = Math.abs(col - (gp.player.worldX / gp.tileSize));
                double distY = Math.abs(row - (gp.player.worldY / gp.tileSize));
                
                // Euclidean distance (Circle shape)
                double distance = Math.sqrt(distX * distX + distY * distY);
                
                // ALTERNATIVE: Manhattan distance (Diamond shape)
                // double distance = distX + distY; 

                // 2. Calculate Opacity based on distance
                // If distance is 0 (on player), darkness is 0. 
                // If distance is lightRadius, darkness is max.
                float darknessAlpha = (float) (distance / lightRadius);

                // 3. Clamp the values
                if (darknessAlpha > maxDarkness) {
                    darknessAlpha = maxDarkness;
                } else if (darknessAlpha < 0) {
                    darknessAlpha = 0f;
                }

                // 4. Draw the dark tile
                // We convert alpha (0.0 - 1.0) to an index (0 - 99) for our array
                int alphaIndex = (int)(darknessAlpha * 99);
                
                g2.setColor(darknessLevels[alphaIndex]);
                g2.fillRect(screenX, screenY, gp.tileSize, gp.tileSize);
            }
        }
    }
}
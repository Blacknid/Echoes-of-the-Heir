package object;

import entity.Entity;
import main.GamePanel;
import java.awt.image.BufferedImage;

public class OBJ_Torch extends Entity {

    public OBJ_Torch(GamePanel gp) {
        super(gp);

        type = type_obstacle;
        name = "Torch";
        
        // 1. LIGHTING PROPERTIES
        // These are the variables we added to the Entity class earlier
        lightSource = true;
        lightRadius = 6; // Adjust this to make the light bigger or smaller

        // 2. LOADING ANIMATION FRAMES
        // Most torch sprites have at least 2-3 frames to simulate flickering
        BufferedImage spritesheet[][] = loadSpriteMatrix("/res/objects/torchNew", gp.tileSize, gp.tileSize);
        down1 = spritesheet[0][0]; // First frame
        down2 = spritesheet[0][0]; // Second frame
        down3 = spritesheet[0][0]; // Third frame
        
        // 3. DESCRIPTION (short, flavourful)
        description = "Flickering torch.\nLights nearby darkness.";

        // 4. COLLISION
        // If you want the player to be able to walk OVER the torch, set collision = false
        collision = true; 
        solidArea.x = 16;  // Centered: 16px left + 32px width + 16px right = 64
        solidArea.y = 16;  // Torch pole starts mid-sprite
        solidArea.width = 32;  // Narrow torch pole
        solidArea.height = 40; // Torch height
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    // This makes the torch flicker by changing the light radius slightly every frame
    @Override
    public void update() {
        super.update(); // Keeps the sprite animation running

        // Optional: Simple flickering effect logic
        // We can slightly fluctuate the light radius based on the spriteNum
        if (spriteNum == 1) lightRadius = 6;
        if (spriteNum == 2) lightRadius = 5;
        if (spriteNum == 3) lightRadius = 7;
    }
}
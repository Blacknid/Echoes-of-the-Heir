package object;

import java.awt.image.BufferedImage;

import entity.Entity;
import main.Config;
import main.GamePanel;

public class OBJ_Torch extends Entity {

    public OBJ_Torch(GamePanel gp) {
        super(gp);

        type = TYPE_OBSTACLE;
        name = "Torch";
        
        // 1. LIGHTING PROPERTIES
        // These are the variables we added to the Entity class earlier
        lightSource = true;
        lightRadius = 6; // Adjust this to make the light bigger or smaller

        // 2. LOADING ANIMATION FRAMES
        // Most torch sprites have at least 2-3 frames to simulate flickering
        BufferedImage spritesheet[][] = loadSpriteMatrix("/res/objects/torchNew", Config.originalTileSize, Config.originalTileSize);
        down1 = spritesheet[0][0]; // First frame
        down2 = spritesheet[0][0]; // Second frame
        down3 = spritesheet[0][0]; // Third frame
        
        // 3. DESCRIPTION (short, flavourful)
        description = "Flickering torch.\nLights nearby darkness.";

        // 4. COLLISION
        // If you want the player to be able to walk OVER the torch, set collision = false
        collision = true; 
        solidArea.x      = gp.tileSize / 4;          // 16 at 64px — centered
        solidArea.y      = gp.tileSize / 4;          // 16 at 64px
        solidArea.width  = gp.tileSize / 2;          // 32 at 64px — narrow torch pole
        solidArea.height = gp.tileSize * 40 / 64;   // 40 at 64px
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
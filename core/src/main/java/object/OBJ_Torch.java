package object;

import gfx.Sprite;

import entity.Entity;
import main.Config;
import main.GamePanel;

public class OBJ_Torch extends Entity {

    public OBJ_Torch(GamePanel gp) {
        super(gp);

        type = TYPE_OBSTACLE;
        name = "Torch";
        
        lightSource = true;
        lightRadius = 6;

        Sprite spritesheet[][] = loadSpriteMatrix("/res/objects/torchNew", Config.originalTileSize, Config.originalTileSize);
        down1 = spritesheet[0][0];
        down2 = spritesheet[0][0];
        down3 = spritesheet[0][0];
        
        description = "Flickering torch.\nLights nearby darkness.";

        collision = true; 
        solidArea.x      = gp.tileSize / 4;          // 16 at 64px, centered
        solidArea.y      = gp.tileSize / 4;          // 16 at 64px
        solidArea.width  = gp.tileSize / 2;          // 32 at 64px, narrow torch pole
        solidArea.height = gp.tileSize * 40 / 64;   // 40 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    @Override
    public void update() {
        super.update();

        if (spriteNum == 1) lightRadius = 6;
        if (spriteNum == 2) lightRadius = 5;
        if (spriteNum == 3) lightRadius = 7;
    }
}
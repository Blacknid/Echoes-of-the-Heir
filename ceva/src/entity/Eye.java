package entity;

import java.awt.image.BufferedImage;
import main.GamePanel;

public class Eye extends Entity {

    public Eye(GamePanel gp) {
        super(gp);

        name = "Eye";
        direction = "down";
        collision = false; // decorative for now
        speed = 0;

        // Try to load an eye sprite if available. If not, image will be null.
        down1 = setup("/res/objects/eye", gp.tileSize, gp.tileSize);

        // Small hitbox in case it's needed later
        solidArea.x = 16;
        solidArea.y = 16;
        solidArea.width = 32;
        solidArea.height = 32;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }
}

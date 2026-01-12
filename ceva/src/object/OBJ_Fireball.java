package object;

import java.awt.image.BufferedImage;

import entity.Projectile;
import main.GamePanel;

public class OBJ_Fireball extends Projectile {

    GamePanel gp;

    public OBJ_Fireball(GamePanel gp) {
        super(gp);

        this.gp = gp;

        name = "Fireball";
        speed = 7;
        maxLife = 80;
        life = maxLife;
        attack = 2;
        useCost = 1;
        alive = false;

        getImage();
    }

    public void getImage() {
        // Load the sprite sheet dynamically
        BufferedImage[][] tempFrames = loadSpriteMatrix(
                "/res/projectiles/Arrow", // path to the spritesheet
                32,                  // width of one sprite
                32                 // height of one sprite
        );

        // If Projectile.frames is a 1D array, just pick the first frame
        frames = new BufferedImage[2];   // create array with 1 frame
        frames[0] = tempFrames[0][0];   // first sprite in first row
        frames[1] = tempFrames[0][1];
    }

}

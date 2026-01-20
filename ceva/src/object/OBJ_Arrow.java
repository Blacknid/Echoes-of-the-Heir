package object;

import java.awt.image.BufferedImage;

import entity.Projectile;
import main.GamePanel;

public class OBJ_Arrow extends Projectile {

    GamePanel gp;

    public OBJ_Arrow(GamePanel gp) {
        super(gp);
        this.gp = gp;
        
        name = "Arrow";
        speed = 5;
        maxLife = 80;
        life = maxLife;
        attack = 2;
        useCost = 1;
        alive = false;
        getImage();
    }

    public void getImage(){
        BufferedImage[][] temp_images = loadSpriteMatrix("/res/projectiles/Arrow", 32, 32);
        image1 = temp_images[0][0];
        image2 = temp_images[0][1];
    }





}

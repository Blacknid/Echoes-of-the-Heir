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
        up1 = temp_images[0][0];
        up2 = temp_images[0][1];
        down1 = temp_images[1][3];
        down2 = temp_images[1][3];
        left1 = temp_images[0][1];
        left2 = temp_images[0][2];
        right1 = temp_images[0][1];
        right2 = temp_images[0][2];
    }





}

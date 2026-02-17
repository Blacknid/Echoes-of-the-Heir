package object;

import java.awt.Color;
import java.awt.image.BufferedImage;

import entity.Entity;
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
        up1 = rotateImage(temp_images[1][3], -180);
        up2 = rotateImage(temp_images[1][3], -180);
        down1 = temp_images[1][3];
        down2 = temp_images[1][3];
        left1 = rotateImage(temp_images[0][0], -180);
        left2 = rotateImage(temp_images[0][0], -180);
        right1 = temp_images[0][0];
        right2 = temp_images[0][0];
    }
    public boolean haveResource(Entity user) {

        
        boolean haveResource = false;

        if ( user.mana >= useCost ) {

            haveResource = true;

        }
        return haveResource;

    }
    public void subtractResource(Entity user) {

        user.mana -= useCost;

    }
    public Color getParticleColor() {
        Color color = new Color(40, 50, 0);
        return color;
    }
    public int getParticleSize() {
        int size = 10; // pixels
        return size;
    }
    public int getParticleSpeed() {
        int speed = 1;
        return speed;
    }
    public int getParticleMaxLife() {
        int maxLife = 20;
        return maxLife;
    }



}

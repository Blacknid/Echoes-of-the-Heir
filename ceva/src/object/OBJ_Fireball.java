package object;

import java.awt.image.BufferedImage;
import entity.Entity;
import entity.Projectile;
import main.GamePanel;
import main.UtilityTool; // IMPORT ADDED

public class OBJ_Fireball extends Projectile {

    GamePanel gp;

    public OBJ_Fireball(GamePanel gp) {
        super(gp);
        this.gp = gp;

        name = "Fireball";
        speed = 5;
        maxLife = 80;
        life = maxLife;
        attack = 2;
        useCost = 1;
        alive = false;
        
        // Fix hitbox 
        solidArea.x = 12;
        solidArea.y = 12;
        solidArea.width = 24;
        solidArea.height = 24;

        getImage();
    }

    public void getImage() {
    
    UtilityTool uTool = new UtilityTool();

    BufferedImage[][] tempFrames = loadSpriteMatrix(
            "/res/projectiles/Arrow", 
            32, 
            32 
    );
    
    if (tempFrames != null && tempFrames.length > 0) {
        // Scaling images to world size
        BufferedImage frame1 = uTool.scaleImage(tempFrames[0][0], gp.tileSize, gp.tileSize);
        BufferedImage frame2 = uTool.scaleImage(tempFrames[0][1], gp.tileSize, gp.tileSize);

        // Assigning to directional sprites
        up1 = frame1;
        up2 = frame2;
        down1 = frame1;
        down2 = frame2;
        left1 = frame1;
        left2 = frame2;
        right1 = frame1;
        right2 = frame2;
    }
}

    // Set the user who shot the projectile
    public void set(int worldX, int worldY, String direction, boolean alive, Entity user) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.direction = direction;
        this.alive = alive;
        this.user = user;
        this.life = this.maxLife; 
    }

    public void update() {

        if (user == gp.player) {
            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            if (monsterIndex != 999) {
                // Updated to pass both index and this projectile's attack power
                gp.player.damageMonster(monsterIndex, this.attack); 
                alive = false; 
            }
        } 
        else {
            boolean contactPlayer = gp.cChecker.checkPlayer(this);
            if (contactPlayer == true && gp.player.invincible == false) {
                damagePlayer(attack);
                alive = false;
            }
        }
    
        // Movement
        switch (direction) {
            case "up": worldY -= speed; break;
            case "down": worldY += speed; break;
            case "left": worldX -= speed; break;
            case "right": worldX += speed; break;
        }
    
        life--;
        if (life <= 0) {
            alive = false;
        }
    
        spriteCounter++;
        if (spriteCounter > 12) {
            if (spriteNum == 1) spriteNum = 2;
            else if (spriteNum == 2) spriteNum = 1;
            spriteCounter = 0;
        }
    }
    
    public void damagePlayer(int attack) {
        if(gp.player.invincible == false) {
            gp.playSE(6); 
            int damage = attack - gp.player.defense;
            if(damage < 0) { damage = 0; }
            gp.player.life -= damage;
            gp.player.invincible = true;
        }
    }
}
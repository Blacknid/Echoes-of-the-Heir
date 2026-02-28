package entity;

import main.GamePanel;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

public class Projectile extends Entity {

    Entity user;

    public Projectile(GamePanel gp){
        super(gp);
    }

    public void set(int worldX, int worldY, String direction, boolean alive, Entity user) {

        this.worldX = worldX;
        this.worldY = worldY;
        this.direction = direction;
        this.alive = alive;
        this.user = user;
        this.life = this.maxLife;
    }

    public void update() {
        
        if(user == gp.player) {
            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            if(monsterIndex != 999){
                // projectiles also impart knockback based on their speed
                gp.player.damageMonster(monsterIndex, this.attack);
                if (monsterIndex != 999) {
                    int kb = Math.max(1, this.speed / 3);
                    gp.player.knockBack(gp.monster[monsterIndex], kb, worldX, worldY);
                }
                generateParticle(user.projectile, gp.monster[monsterIndex]);
                alive = false;
            }
        }
        if(user != gp.player){
            boolean contactPlayer = gp.cChecker.checkPlayer(this);
            if(contactPlayer == true && gp.player.invincible == false){
                generateParticle(user.projectile, gp.player);
                alive = false;
            }
        }

        switch(direction) {
            case "up": worldY -= speed; break;
            case "down": worldY += speed; break;
            case "left": worldX -= speed; break;
            case "right": worldX += speed; break;
        }

        life--;
        if(life <= 0){
            alive = false;
        }

        spriteCounter++;
        if(spriteCounter > 12){
            if(spriteNum == 1) {
                spriteNum = 2;
            }
            else if(spriteNum == 2){
                spriteNum = 1;
            }
            spriteCounter = 0;
        }
    }
    public boolean haveResource(Entity user) {

        boolean haveResource = false;
        return haveResource;

    }
    public static BufferedImage rotateImage(BufferedImage img, double degrees) {
        int w = img.getWidth();
        int h = img.getHeight();
        
        // Create a new image of the same size
        BufferedImage rotated = new BufferedImage(w, h, img.getType());
        Graphics2D g2d = rotated.createGraphics();
        
        // Apply rotation around the center
        g2d.rotate(Math.toRadians(degrees), w / 2, h / 2);
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();
    
        return rotated;
    }
    public void subtractResource(Entity user) {}
}

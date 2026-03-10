package entity;

import main.GamePanel;
import main.ObjectPool.Poolable;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

public class Projectile extends Entity implements Poolable {

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
            // Check NPCs as well (Eye is implemented as an NPC). Only apply
            // projectile damage to NPCs named "Eye" so other NPCs are unaffected.
            int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
            if (npcIndex != 999) {
                if (gp.npc[npcIndex].name != null && gp.npc[npcIndex].name.equals("Eye")) {
                    if (gp.npc[npcIndex].invincible == false) {
                        gp.playSE(5);
                        int kb = Math.max(1, this.speed / 3);
                        gp.player.knockBack(gp.npc[npcIndex], kb, worldX, worldY);
                        int damage = this.attack - gp.npc[npcIndex].defense;
                        if (damage < 0) damage = 0;
                        gp.npc[npcIndex].life -= damage;
                        generateParticle(user.projectile, gp.npc[npcIndex]);
                        gp.npc[npcIndex].invincible = true;
                        gp.npc[npcIndex].damageReaction();
                        if (gp.npc[npcIndex].life <= 0) {
                            gp.npc[npcIndex].dying = true;
                        }
                        alive = false;
                    }
                }
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

    @Override
    public void reset() {
        // Reset projectile to safe state for reuse
        alive = false;
        user = null;
        life = 0;
        worldX = 0;
        worldY = 0;
        direction = "down";
        spriteNum = 1;
        spriteCounter = 0;
    }
}

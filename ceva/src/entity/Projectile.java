package entity;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import main.GamePanel;

public class Projectile extends Entity {

    Entity user;

    protected BufferedImage[] frames; // in Projectile class
    protected int spriteNum = 0;
    protected int spriteCounter = 0;

    public Projectile(GamePanel gp) {
        super(gp);
    }

    public void set( int worldX, int worldY, String direction, boolean alive, Entity user ) {

        this.worldX = worldX;
        this.worldY = worldY;
        this.direction = direction;
        this.alive = alive;
        this.user = user;
        this.life = this.maxLife;

    }

    public void update() {

    
    if (!alive) return;

    // Animate
    spriteCounter++;
    if (spriteCounter > 6) { // speed of animation
        spriteNum++;
        if (spriteNum >= frames.length) spriteNum = 0; // loop
        spriteCounter = 0;
    }

    // Move projectile
    switch(direction) {
        case "up" -> worldY -= speed;
        case "down" -> worldY += speed;
        case "left" -> worldX -= speed;
        case "right" -> worldX += speed;
    }

    life--;
    if (life <= 0) alive = false;
}


@Override
public void draw(Graphics2D g2) {
    if (!alive) return;

    BufferedImage image = frames[spriteNum];

    double angle = switch (direction) {
        case "up" -> -90;
        case "down" -> 90;
        case "left" -> 180;
        case "right" -> 0;
        default -> 0;
    };

    int screenX = worldX - gp.player.worldX + gp.player.screenX;
    int screenY = worldY - gp.player.worldY + gp.player.screenY;

    int w = image.getWidth();
    int h = image.getHeight();

    Graphics2D g = (Graphics2D) g2.create();
    g.rotate(Math.toRadians(angle), screenX + w / 2, screenY + h / 2);
    g.drawImage(image, screenX, screenY, null);
    g.dispose();
}


public void set(int worldX, int worldY, String direction, boolean alive) {
    this.worldX = worldX;
    this.worldY = worldY;
    this.direction = direction;
    this.alive = alive;
    this.life = maxLife;
}
}

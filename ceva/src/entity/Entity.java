package entity;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import main.GamePanel;
import main.UtilityTool;

public class Entity {

    GamePanel gp;

    // PATHFINDING
    int pathUpdateCounter = 0;
    int pathUpdateInterval = 10;

    // POSITION & STATE
    public int worldX, worldY;
    public boolean alive = true;
    public boolean dying = false;
    boolean hpBarOn = false;

    // SPRITES
    public BufferedImage up1, up2, up3, up4, up5, up6, up7;
    public BufferedImage down1, down2, down3, down4, down5, down6, down7;
    public BufferedImage left1, left2, left3, left4, left5, left6, left7, left8;
    public BufferedImage right1, right2, right3, right4, right5, right6, right7, right8;
    public BufferedImage chest_1, chest_2;
    
    // ATTACK SPRITES
    public BufferedImage attackUp1, attackUp2, attackUp3, attackUp4, attackUp5, attackUp6;
    public BufferedImage attackDown1, attackDown2, attackDown3, attackDown4, attackDown5, attackDown6;
    public BufferedImage attackLeft1, attackLeft2, attackLeft3, attackLeft4, attackLeft5, attackLeft6;
    public BufferedImage attackRight1, attackRight2, attackRight3, attackRight4, attackRight5, attackRight6;
    
    public String direction = "down";

    // COUNTERS
    public int spriteCounter = 0;
    public int spriteCounter1 = 0;
    public int spriteNum = 1;
    public int spriteNum1 = 1;
    public int actionLockCounter = 0;
    public int invincibleCounter = 0;
    public int shotAvailableCounter = 0;
    int dyingCounter = 0;
    int hpBarCounter = 0;
    
    // DIALOGUE
    public String dialogues[][] = new String[100][100];
    public int dialogueIndex = 0;
    public int dialogueSet = 0;
    
    // IMAGES
    public BufferedImage image, image1, image2, image3, compas_image;

    // COLLISION & AREAS
    public Rectangle solidArea = new Rectangle(0, 0, 48, 48);
    public Rectangle attackArea = new Rectangle(0, 0, 0, 0);
    public int solidAreaDefaultX, solidAreaDefaultY;
    public boolean collisionOn = false;
    public boolean invincible = false;
    boolean attacking = false;
    public boolean collision = false;
    public boolean sleep = false;
    public boolean drawing = true;
    public boolean onPath = false;

    // TYPE CONSTANTS
    public int type;
    public final int type_player = 0;
    public final int type_npc = 1;
    public final int type_monster = 2;
    public final int type_sword = 3;
    public final int type_book = 4;
    public final int type_shield = 5;
    public final int type_consumable = 6;
    public final int type_pickupOnly = 7;
    public final int type_obstacle = 8;
    public final int type_buffs = 9;
    public final int type_ending = 10;

    // CHARACTER ATTRIBUTES
    public String name;
    public int speed;
    public int maxLife;
    public int life;
    public int maxMana;
    public int mana;
    public int level;
    public int strenght;
    public int dexterity;
    public int attack;
    public int defense;
    public int exp;
    public int nextLevelExp;
    public int coin;
    public Entity currentWeapon;
    public Entity currentShield;
    public Projectile projectile;


    // ITEM ATTRIBUTES
    public int attackValue;
    public int defenseValue;
    public String description = "";
    public int useCost;

    public Entity(GamePanel gp) {
        this.gp = gp;
    }

    // GETTERS

    public int getLeftX() { return worldX + solidArea.x; }
    public int getRightX() { return worldX + solidArea.x + solidArea.width; }
    public int getTopY() { return worldY + solidArea.y; }
    public int getBottomY() { return worldY + solidArea.y + solidArea.height; }
    public int getCol() { return (worldX + solidArea.x) / gp.tileSize; }
    public int getRow() { return (worldY + solidArea.y) / gp.tileSize; }
    public int getCenterX() { return worldX + solidArea.x + solidArea.width / 2; }    
    public int getCenterY() { return worldY + solidArea.y + solidArea.height / 2; }    
    public int getTileCol() { return getCenterX() / gp.tileSize; }    
    public int getTileRow() { return getCenterY() / gp.tileSize; } 
    public void resetCounter() {
        spriteCounter = 0;
        spriteCounter1 = 0;
        spriteNum = 1;
        spriteNum1 = 1;
        actionLockCounter = 0;
        invincibleCounter = 0;
        shotAvailableCounter = 0;
        dyingCounter = 0;
        hpBarCounter = 0;
    }
    public void setAction() {}
    public void speak() {}
    public void facePlayer() {
        switch (gp.player.direction) {
            case "up": direction = "down"; break;
            case "down": direction = "up"; break;
            case "left": direction = "right"; break;
            case "right": direction = "left"; break;
        }
    }
    public void interact() {}
    public void damageReaction() {}
    public boolean use(Entity entity) { return false;}
    public void startDialogue(Entity entity, int setNum) {
        gp.gameState = gp.dialogueState;
        gp.ui.npc = entity;
        dialogueSet = setNum;
    }
    public void checkCollision() {
        
        collisionOn = false;
        gp.cChecker.checkTile(this);
        gp.cChecker.checkObject(this, false);
        gp.cChecker.checkEntity(this, gp.npc);
        gp.cChecker.checkEntity(this, gp.monster);
        boolean contactPlayer = gp.cChecker.checkPlayer(this);

        if (type == type_monster && contactPlayer == true && gp.player.invincible == false) {
            
            gp.playSE(8);

            int damage = attack - gp.player.defense;
            if (damage < 1) {
                damage = 1;
            }

            gp.player.life -= damage;
            gp.player.invincible = true;
        }
    }
    public void update() {
        
        setAction();
        checkCollision();

        // IF COLLISION IS FALSE, MOVE
        // MODIFIED: Only use this block if NOT using pathfinding
        // Pathfinding (searchPath) now handles movement internally to allow diagonals
        if (collisionOn == false && onPath == false) {
            switch (direction) {
                case "up": worldY -= speed; break;
                case "down": worldY += speed; break;
                case "left": worldX -= speed; break;
                case "right": worldX += speed; break;
            }
        }

        spriteCounter++;
        if (spriteCounter > 12) {
            if (spriteNum == 1) { spriteNum = 2; }
            else if (spriteNum == 2) { spriteNum = 3; }
            else if (spriteNum == 3) { spriteNum = 1; }
            spriteCounter = 0;
        }

        if (invincible == true) {
            invincibleCounter++;
            if (invincibleCounter > 40) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
        if (shotAvailableCounter < 30) {
            shotAvailableCounter++;
        }
    }
    public void draw(Graphics2D g2) {
        
        BufferedImage image = null;
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX && 
            worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY && 
            worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {      

            switch (direction) {
                case "up":
                    if (spriteNum == 1) image = up1;
                    if (spriteNum == 2) image = up2;
                    if (spriteNum == 3) image = up3;
                    break;
                case "down":
                    if (spriteNum == 1) image = down1;
                    if (spriteNum == 2) image = down2;
                    if (spriteNum == 3) image = down3;
                    break;
                case "left":
                    if (spriteNum == 1) image = left1;
                    if (spriteNum == 2) image = left2;
                    if (spriteNum == 3) image = left3;
                    break;
                case "right":
                    if (spriteNum == 1) image = right1;
                    if (spriteNum == 2) image = right2;
                    if (spriteNum == 3) image = right3;
                    break;
            }

            // Monster HP Bar
            if (type == type_monster && hpBarOn == true) {
                double oneScale = (double)gp.tileSize / maxLife;
                double hpBarValue = oneScale * life;

                g2.setColor(new Color(35, 35, 35));
                g2.fillRect(screenX - 1, screenY - 16, gp.tileSize + 2, 12);

                g2.setColor(new Color(255, 0, 30));
                g2.fillRect(screenX, screenY - 15, (int)hpBarValue, 10);

                hpBarCounter++;
                if (hpBarCounter > 300) {
                    hpBarCounter = 0;
                    hpBarOn = false;
                }
            }

            if (invincible == true) {
                hpBarOn = true;
                hpBarCounter = 0;
                changeAlpha(g2, 0.4F);
            }
            if (dying == true) {
                dyingAnimation(g2);
            }
                
            g2.drawImage(image, screenX, screenY, gp.tileSize, gp.tileSize, null);
            changeAlpha(g2, 1F);
        }
    }
    public void dyingAnimation(Graphics2D g2) {
        dyingCounter++;
        int i = 5;
        if (dyingCounter <= i) { changeAlpha(g2, 0f); }
        if (dyingCounter > i && dyingCounter <= i*2) { changeAlpha(g2, 1f); }
        if (dyingCounter > i*2 && dyingCounter <= i*3) { changeAlpha(g2, 0f); }
        if (dyingCounter > i*3 && dyingCounter <= i*4) { changeAlpha(g2, 1f); }
        if (dyingCounter > i*4 && dyingCounter <= i*5) { changeAlpha(g2, 0f); }
        if (dyingCounter > i*5 && dyingCounter <= i*6) { changeAlpha(g2, 1f); }
        if (dyingCounter > i*6 && dyingCounter <= i*7) { changeAlpha(g2, 0f); }
        if (dyingCounter > i*7 && dyingCounter <= i*8) { changeAlpha(g2, 1f); }
        if (dyingCounter > i*8) {
            alive = false;
        }
    }
    public void changeAlpha(Graphics2D g2, float alphaValue) {
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alphaValue));
    }
    public BufferedImage setup(String imagePath, int width, int height) {
        UtilityTool uTool = new UtilityTool();
        BufferedImage image = null;
        try {
            image = ImageIO.read(getClass().getResourceAsStream(imagePath + ".png"));
            image = uTool.scaleImage(image, width, height);
        } catch(IOException e) {
            e.printStackTrace();
        }
        return image;
    }
    public int getDetected(Entity user, Entity target[], String targetName) {
        int index = 999;
        
        int nextWorldX = user.getLeftX();
        int nextWorldY = user.getTopY();

        switch (user.direction) {
            case "up": nextWorldY = user.getTopY() - 1; break;
            case "down": nextWorldY = user.getBottomY() + 1; break;
            case "left": nextWorldX = user.getLeftX() - 1; break;
            case "right": nextWorldX = user.getRightX() + 1; break;
        }

        int col = nextWorldX / gp.tileSize;
        int row = nextWorldY / gp.tileSize;

        for (int i = 0; i < target.length; i++) {
            if (target[i] != null) {
                if (target[i].getCol() == col &&
                    target[i].getRow() == row && 
                    target[i].name.equals(targetName)) {
                    index = i;
                    break;
                }
            }
        }
        return index;
    }
    public void searchPath(int goalCol, int goalRow) {
        
        int startCol = (worldX + solidArea.x) / gp.tileSize;
        int startRow = (worldY + solidArea.y) / gp.tileSize;

        gp.pFinder.setNodes(startCol, startRow, goalCol, goalRow, this);

        if (gp.pFinder.search() == true) {
            
            // Next WorldX and WorldY
            int nextX = gp.pFinder.pathList.get(0).col * gp.tileSize;
            int nextY = gp.pFinder.pathList.get(0).row * gp.tileSize;

            // Entity's solidArea position
            int enLeftX = worldX + solidArea.x;
            int enRightX = worldX + solidArea.x + solidArea.width;
            int enTopY = worldY + solidArea.y;
            int enBottomY = worldY + solidArea.y + solidArea.height;

            // NOTE: We now handle movement MANUALLY here to allow Diagonal movement.
            // We temporarily change 'direction' to check collision for each axis.
            
            // 1. Vertical Movement
            if (enTopY > nextY) {
                direction = "up";
                checkCollision();
                if (collisionOn == false) { worldY -= speed; }
            }
            else if (enTopY < nextY) {
                direction = "down";
                checkCollision();
                if (collisionOn == false) { worldY += speed; }
            }

            // 2. Horizontal Movement
            if (enLeftX > nextX) {
                direction = "left";
                checkCollision();
                if (collisionOn == false) { worldX -= speed; }
            }
            else if (enLeftX < nextX) {
                direction = "right";
                checkCollision();
                if (collisionOn == false) { worldX += speed; }
            }

            // 3. Reset direction for correct sprite animation
            // Determine which axis has the larger distance to prioritize the sprite direction
            int nextCenterX = nextX + gp.tileSize / 2;
            int nextCenterY = nextY + gp.tileSize / 2;
            int enCenterX = enLeftX + solidArea.width / 2;
            int enCenterY = enTopY + solidArea.height / 2;
            
            int dx = Math.abs(nextCenterX - enCenterX);
            int dy = Math.abs(nextCenterY - enCenterY);

            if (dx > dy) {
                if (enCenterX > nextCenterX) direction = "left";
                else direction = "right";
            } else {
                if (enCenterY > nextCenterY) direction = "up";
                else direction = "down";
            }

            int nextCol = gp.pFinder.pathList.get(0).col;
            int nextRow = gp.pFinder.pathList.get(0).row;
            if (nextCol == goalCol && nextRow == goalRow) {
                onPath = false;
            }
        }
    }   
    public boolean isPlayerInRange(int range) {
        int dx = Math.abs(getCenterX() - gp.player.getCenterX());
        int dy = Math.abs(getCenterY() - gp.player.getCenterY());
        return dx < range && dy < range;
    } 
    public BufferedImage[][] loadSheetVariable(String path, int[] framesPerRow) {
        int rows = framesPerRow.length;
        int maxCols = 0;
        for (int f : framesPerRow) if (f > maxCols) maxCols = f;

        BufferedImage sheet = setup(path, gp.tileSize * maxCols, gp.tileSize * rows);
        BufferedImage[][] frames = new BufferedImage[rows][];

        for (int y = 0; y < rows; y++) {
            frames[y] = new BufferedImage[framesPerRow[y]];
            for (int x = 0; x < framesPerRow[y]; x++) {
                frames[y][x] = sheet.getSubimage(
                        x * gp.tileSize,
                        y * gp.tileSize,
                        gp.tileSize,
                        gp.tileSize
                );
            }
        }
        return frames;
    }
    public BufferedImage[][] loadSpriteMatrix(
        String path,
        int spriteWidth,
        int spriteHeight
    ) {

        BufferedImage sheet;

        try {
            sheet = ImageIO.read(getClass().getResourceAsStream(path + ".png"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load spritesheet: " + path, e);
        }

        int columns = sheet.getWidth() / spriteWidth;
        int rows = sheet.getHeight() / spriteHeight;

        BufferedImage[][] matrix = new BufferedImage[rows][columns];

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                matrix[y][x] = sheet.getSubimage(
                        x * spriteWidth,
                        y * spriteHeight,
                        spriteWidth,
                        spriteHeight
                );
            }
        }

        return matrix;
    }
}

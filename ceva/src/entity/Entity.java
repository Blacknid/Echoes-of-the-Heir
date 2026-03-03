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
    public BufferedImage up1, up2, up3, up4, up5, up6, up7,
        down1, down2, down3, down4, down5, down6, down7,
        left1, left2, left3, left4, left5, left6, left7, left8,
        right1, right2, right3, right4, right5, right6, right7, right8;
    public BufferedImage upidle1, upidle2, upidle3, upidle4, upidle5, upidle6, 
        downidle1, downidle2, downidle3, downidle4, downidle5, downidle6, 
        leftidle1, leftidle2, leftidle3, leftidle4, leftidle5, leftidle6, 
        rightidle1, rightidle2, rightidle3, rightidle4, rightidle5, rightidle6;
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
    public int walkFrameCount = 3;
    public int animationFrameInterval = 8;
    private int walkFrameDirection = 1;
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
    // Renamed class level 'image' to 'activeImage' to avoid confusion, 
    // though usually you draw specific sprites (up1, etc)
    public BufferedImage image, image1, image2, image3, compas_image;

    // COLLISION & AREAS
    public Rectangle solidArea = new Rectangle(0, 0, 48, 48);
    public Rectangle attackArea = new Rectangle(0, 0, 0, 0);
    public int solidAreaDefaultX, solidAreaDefaultY;
    public boolean collisionOn = false;
    public boolean invincible = false;
    public boolean attacking = false;
    public boolean collision = false;
    public boolean sleep = false;
    public boolean drawing = true;
    public boolean onPath = false;
    public boolean knockBack = false;          // true while being pushed by an attack
    public int knockBackCounter = 0;           // leftover frames for old system (deprecated)
    public int knockBackDuration = 15;         // leftover frames for old system (deprecated)
    public int knockBackPower = 0;             // magnitude of the push (for debug display)
    // new vector-based knockback
    public int knockBackVectorX = 0;
    public int knockBackVectorY = 0;
    public double knockBackRemaining = 0;      // distance left to travel
    public boolean fleeing = false;            // AI state: running away from player
    public int fleeCounter = 0;
    public int fleeDuration = 60;
    public Entity loot;
    public boolean opened = false;

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
    public int defaultSpeed = 1;
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
    public boolean lightSource = false;
    public int lightRadius = 0;

    // ITEM ATTRIBUTES
    public int attackValue;
    public int defenseValue;
    public String description = "";
    public int useCost;
    public boolean stackable = false;
    public int amount = 1;

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
    public void setLoot(Entity loot) {}
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
    public boolean use(Entity entity) { return false; }
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
    public Color getParticleColor() {
        Color color = null;
        return color;
    }
    public int getParticleSize() {
        int size = 0; // pixels
        return size;
    }
    public int getParticleSpeed() {
        int speed = 0; // pixels per frame
        return speed;
    }
    public int getParticleMaxLife() {
        int maxLife = 0; // frames
        return maxLife;
    }
    public void generateParticle ( Entity generator, Entity target ) {

        Color color = generator.getParticleColor();
        int size = generator.getParticleSize();
        int speed = generator.getParticleSpeed();
        int maxLife = generator.getParticleMaxLife();

        // OPTIMIZATION: Use particle pool instead of creating new objects
        // Position particles at the TARGET location (where hit occurred), not the generator
        Particle p1 = gp.particlePool.get();
        p1.setWithPosition(generator, target, color, size, speed, maxLife, -1, -1);
        gp.particleList.add(p1);
        
        Particle p2 = gp.particlePool.get();
        p2.setWithPosition(generator, target, color, size, speed, maxLife, 0, -1);
        gp.particleList.add(p2);
        
        Particle p3 = gp.particlePool.get();
        p3.setWithPosition(generator, target, color, size, speed, maxLife, 1, -1);
        gp.particleList.add(p3);
        
        Particle p4 = gp.particlePool.get();
        p4.setWithPosition(generator, target, color, size, speed, maxLife, 0, 1);
        gp.particleList.add(p4);

    } 
    public void update() {
        // handling knockback first ensures the push isn't blocked by normal collision checks
        if (knockBack) {
            // move by vector regardless of collision state
            worldX += knockBackVectorX;
            worldY += knockBackVectorY;

            double travelled = Math.hypot(knockBackVectorX, knockBackVectorY);
            knockBackRemaining -= travelled;
            if (knockBackRemaining <= 0) {
                knockBack = false;
                knockBackVectorX = 0;
                knockBackVectorY = 0;
                knockBackRemaining = 0;
                knockBackPower = 0;
                // stop any active chase so monster isn't immediately drawn back
                onPath = false;
            }
            return;
        }

        int previousWorldX = worldX;
        int previousWorldY = worldY;

        setAction();
        checkCollision();

        // ------------------------------------------------------------------
        // NORMAL MOVEMENT
        // IF COLLISION IS FALSE, MOVE
        // Only run manual movement if pathfinding is NOT active
        if (collisionOn == false && onPath == false) {
            switch (direction) {
                case "up": worldY -= speed; break;
                case "down": worldY += speed; break;
                case "left": worldX -= speed; break;
                case "right": worldX += speed; break;
            }
        }
        
        // IMPORTANT: If we just finished pathfinding, we don't want to keep moving.
        // The searchPath logic handles movement when onPath is true.

        boolean movedThisFrame = worldX != previousWorldX || worldY != previousWorldY;

        if (movedThisFrame) {
            spriteCounter++;
            if (spriteCounter > animationFrameInterval) {
                int maxWalkFrames = Math.max(1, Math.min(walkFrameCount, 8));

                if (maxWalkFrames == 1) {
                    spriteNum = 1;
                } else {
                    spriteNum += walkFrameDirection;

                    if (spriteNum >= maxWalkFrames) {
                        spriteNum = maxWalkFrames;
                        walkFrameDirection = -1;
                    }
                    if (spriteNum <= 1) {
                        spriteNum = 1;
                        walkFrameDirection = 1;
                    }
                }

                spriteCounter = 0;
            }
        } else {
            spriteNum = 1;
            spriteCounter = 0;
            walkFrameDirection = 1;
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
        
        // Use a local variable to determine which sprite to draw
        BufferedImage currentSprite = null;
        
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX && 
            worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY && 
            worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {      

            switch (direction) {
                case "up":
                    currentSprite = getWalkFrameImage("up", spriteNum);
                    break;
                case "down":
                    currentSprite = getWalkFrameImage("down", spriteNum);
                    break;
                case "left":
                    currentSprite = getWalkFrameImage("left", spriteNum);
                    break;
                case "right":
                    currentSprite = getWalkFrameImage("right", spriteNum);
                    break;
            }

            if (currentSprite == null) {
                currentSprite = getWalkFrameImage(direction, 1);
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
                
            // Safe guard against null images
            if (currentSprite != null) {
                g2.drawImage(currentSprite, screenX, screenY, gp.tileSize, gp.tileSize, null);
            }
            
            changeAlpha(g2, 1F);
        }
    }

    private BufferedImage getWalkFrameImage(String facing, int frame) {
        return switch (facing) {
            case "up" -> switch (frame) {
                case 1 -> up1;  case 2 -> up2;case 3 -> up3;  case 4 -> up4;
                case 5 -> up5;  case 6 -> up6;
                case 7 -> up7;  default -> null;
            };
            case "down" -> switch (frame) {
                case 1 -> down1;    case 2 -> down2;
                case 3 -> down3;    case 4 -> down4;
                case 5 -> down5;    case 6 -> down6;
                case 7 -> down7;    default -> null;
            };
            case "left" -> switch (frame) {
                case 1 -> left1;    case 2 -> left2;
                case 3 -> left3;    case 4 -> left4;
                case 5 -> left5;    case 6 -> left6;
                case 7 -> left7;    case 8 -> left8;
                default -> null;
            };
            case "right" -> switch (frame) {
                case 1 -> right1;   case 2 -> right2;
                case 3 -> right3;   case 4 -> right4;
                case 5 -> right5;   case 6 -> right6;
                case 7 -> right7;   case 8 -> right8;
                default -> null;
            };
            default -> null;
        };
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
            case "up": nextWorldY = user.getTopY() - gp.player.speed; break;
            case "down": nextWorldY = user.getBottomY() + gp.player.speed; break;
            case "left": nextWorldX = user.getLeftX() - gp.player.speed; break;
            case "right": nextWorldX = user.getRightX() + gp.player.speed; break;
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
    
    // -----------------------------------------------------------------
    // FIXED SEARCH PATH
    // -----------------------------------------------------------------
    public void searchPath(int goalCol, int goalRow) {
        
        int startCol = (worldX + solidArea.x) / gp.tileSize;
        int startRow = (worldY + solidArea.y) / gp.tileSize;

        // If already on the goal tile, stop pathfinding
        if (startCol == goalCol && startRow == goalRow) {
            onPath = false;
            return;
        }

        gp.pFinder.setNodes(startCol, startRow, goalCol, goalRow, this);

        // If path found
        if (gp.pFinder.search() == true) {
            if (gp.pFinder.pathList.isEmpty()) {
                onPath = false;
                return;
            }

            // Next WorldX and WorldY
            int nextX = gp.pFinder.pathList.get(0).col * gp.tileSize;
            int nextY = gp.pFinder.pathList.get(0).row * gp.tileSize;

            // Entity's solidArea position
            int enLeftX = worldX + solidArea.x;
            int enTopY = worldY + solidArea.y;
            int enCenterX = enLeftX + solidArea.width / 2;
            int enCenterY = enTopY + solidArea.height / 2;

            // Calculate distance to next waypoint center
            int nextCenterX = nextX + gp.tileSize / 2;
            int nextCenterY = nextY + gp.tileSize / 2;
            int dx = Math.abs(nextCenterX - enCenterX);
            int dy = Math.abs(nextCenterY - enCenterY);

            // Check if we've reached the current waypoint (within speed threshold)
            if (dx <= speed + 1 && dy <= speed + 1) {
                // Snap and move to next waypoint
                gp.pFinder.pathList.remove(0);
                if (gp.pFinder.pathList.isEmpty()) {
                    onPath = false;
                }
                return;
            }

            // PREVENT DIAGONAL MOVEMENT: Move in one direction per frame
            // Prioritize the axis with greater distance
            boolean moved = false;

            if (dy > dx) {
                // Try vertical first
                moved = tryMoveVertical(enTopY, nextY);
                // If blocked vertically, fallback to horizontal
                if (!moved) {
                    moved = tryMoveHorizontal(enLeftX, nextX);
                }
            } else {
                // Try horizontal first
                moved = tryMoveHorizontal(enLeftX, nextX);
                // If blocked horizontally, fallback to vertical
                if (!moved) {
                    moved = tryMoveVertical(enTopY, nextY);
                }
            }
        } else {
            // A* failed to find a path — use direct chase as fallback
            directChase(goalCol, goalRow);
        }
    }

    // Try to move vertically toward the target. Returns true if movement occurred.
    private boolean tryMoveVertical(int enTopY, int nextY) {
        if (enTopY > nextY) {
            direction = "up";
            checkCollision();
            if (!collisionOn) { worldY -= speed; return true; }
        } else if (enTopY < nextY) {
            direction = "down";
            checkCollision();
            if (!collisionOn) { worldY += speed; return true; }
        }
        return false;
    }

    // Try to move horizontally toward the target. Returns true if movement occurred.
    private boolean tryMoveHorizontal(int enLeftX, int nextX) {
        if (enLeftX > nextX) {
            direction = "left";
            checkCollision();
            if (!collisionOn) { worldX -= speed; return true; }
        } else if (enLeftX < nextX) {
            direction = "right";
            checkCollision();
            if (!collisionOn) { worldX += speed; return true; }
        }
        return false;
    }

    // Direct chase fallback: move toward the goal tile without A*
    protected void directChase(int goalCol, int goalRow) {
        int goalWorldX = goalCol * gp.tileSize;
        int goalWorldY = goalRow * gp.tileSize;
        int dx = goalWorldX - worldX;
        int dy = goalWorldY - worldY;

        boolean moved = false;
        if (Math.abs(dy) > Math.abs(dx)) {
            // Try vertical
            if (dy < 0) { direction = "up"; checkCollision(); if (!collisionOn) { worldY -= speed; moved = true; } }
            else if (dy > 0) { direction = "down"; checkCollision(); if (!collisionOn) { worldY += speed; moved = true; } }
            if (!moved) {
                if (dx < 0) { direction = "left"; checkCollision(); if (!collisionOn) { worldX -= speed; } }
                else if (dx > 0) { direction = "right"; checkCollision(); if (!collisionOn) { worldX += speed; } }
            }
        } else {
            // Try horizontal
            if (dx < 0) { direction = "left"; checkCollision(); if (!collisionOn) { worldX -= speed; moved = true; } }
            else if (dx > 0) { direction = "right"; checkCollision(); if (!collisionOn) { worldX += speed; moved = true; } }
            if (!moved) {
                if (dy < 0) { direction = "up"; checkCollision(); if (!collisionOn) { worldY -= speed; } }
                else if (dy > 0) { direction = "down"; checkCollision(); if (!collisionOn) { worldY += speed; } }
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

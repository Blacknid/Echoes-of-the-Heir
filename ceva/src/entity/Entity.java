package entity;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

import main.GamePanel;
import main.SFX;
import main.UtilityTool;

public class Entity {

    protected GamePanel gp;

    // PATHFINDING
    int pathUpdateCounter = 0;
    int pathUpdateInterval = 10;

    // DIRECTION CONSTANTS
    public static final int DIR_DOWN  = 0;
    public static final int DIR_LEFT  = 1;
    public static final int DIR_RIGHT = 2;
    public static final int DIR_UP    = 3;
    public static final int DIR_ANY   = -1; // for event checks (any facing)

    // POSITION & STATE
    public int worldX, worldY;
    public boolean alive = true;
    public boolean dying = false;
    boolean hpBarOn = false;

    // SPRITES - legacy named fields (still used by object/item classes)
    public BufferedImage up1, up2, up3, up4, up5, up6, up7,
        down1, down2, down3, down4, down5, down6, down7,
        left1, left2, left3, left4, left5, left6, left7, left8,
        right1, right2, right3, right4, right5, right6, right7, right8;
    // (idle/chest legacy sprite fields removed — use idleFrames[][] array instead)

    // SPRITES - array-based storage: [dirIndex][frameIndex], dir = DIR_DOWN/LEFT/RIGHT/UP
    public BufferedImage[][] walkFrames;   // walk animation per direction
    public BufferedImage[][] idleFrames;   // idle animation per direction
    public BufferedImage[][] attackFrames; // attack animation per direction
    
    // (attack legacy sprite fields removed — use attackFrames[][] array instead)
    
    public int direction = DIR_DOWN;

    // COUNTERS
    public int spriteCounter = 0;
    public int spriteNum = 1;
    public int walkFrameCount = 3;
    public int animationFrameInterval = 8;
    private int walkFrameDirection = 1;
    public int actionLockCounter = 0;
    public int invincibleCounter = 0;
    public int invincibleDuration = 10; // frames of i-frames after hit (short for combo-friendly combat)
    public int shotAvailableCounter = 0;
    int dyingCounter = 0;
    int hpBarCounter = 0;
    public int crowdControlTimer = 0;
    public boolean deathRewardsQueued = false;
    public int deathRewardExp = 0;
    public int deathRewardQuestKills = 0;
    public int deathRewardCoins = 0;

    // HIT FLASH: white overlay on damage
    public int hitFlashCounter = 0;
    private static final int HIT_FLASH_DURATION = 6;
    // OPTIMIZATION: Pre-allocated Color constants to avoid per-frame allocation
    private static final Color HP_BAR_BG = new Color(35, 35, 35);
    private static final Color HP_BAR_FG = new Color(255, 0, 30);
    private static final Color SPARK_COLOR_1 = new Color(255, 235, 120);
    private static final Color SPARK_COLOR_2 = new Color(255, 200, 80);
    private static final Color COIN_MSG_COLOR = new Color(255, 210, 90);
    // OPTIMIZATION: Reusable flash image to avoid per-frame BufferedImage allocation
    private BufferedImage hitFlashBuffer;
    private int hitFlashBufferW, hitFlashBufferH;

    // TILE PARTICLES: throttle counter for footstep particle emission
    public int footstepParticleCounter = 0;
    
    // DIALOGUE (lazy-initialized to avoid wasting memory on entities that don't talk)
    public String dialogues[][];
    public int dialogueIndex = 0;
    public int dialogueSet = 0;
    
    // OPTIMIZATION: Lazy dialogue access - only allocates when first written
    public String[][] ensureDialogues() {
        if (dialogues == null) {
            dialogues = new String[20][20];
        }
        return dialogues;
    }
    
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
    public java.awt.Color lightColor = null; // custom light tint (null = default orange)

    // TILED / EDITOR METADATA
    public String objectId = null;      // persistent ID set from Tiled 'id' property
    public boolean invisible = false;   // set from Tiled 'invisible' property (no draw)
    public int aggroRange = 160;        // aggro distance in pixels (default ~2.5 tiles)
    public int wanderRadius = 0;        // max wander pixel offset from spawn (0 = free)

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
        spriteNum = 1;
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
            case DIR_UP:    direction = DIR_DOWN;  break;
            case DIR_DOWN:  direction = DIR_UP;    break;
            case DIR_LEFT:  direction = DIR_RIGHT; break;
            case DIR_RIGHT: direction = DIR_LEFT;  break;
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

        if (type == type_monster && contactPlayer && !gp.player.invincible) {
            
            gp.playSE(SFX.PLAYER_HIT);

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
    public int getParticleStyle() {
        return Particle.STYLE_DEFAULT;
    }
    public void generateParticle ( Entity generator, Entity target ) {

        Color color = generator.getParticleColor();
        int size = generator.getParticleSize();
        int speed = generator.getParticleSpeed();
        int maxLife = generator.getParticleMaxLife();
        int style = generator.getParticleStyle();

        // OPTIMIZATION: Use particle pool instead of creating new objects
        // Position particles at the TARGET location (where hit occurred), not the generator
        Particle p1 = gp.particlePool.get();
        p1.setWithPosition(generator, target, color, size, speed, maxLife, -1, -1, style);
        gp.particleList.add(p1);
        
        Particle p2 = gp.particlePool.get();
        p2.setWithPosition(generator, target, color, size, speed, maxLife, 0, -1, style);
        gp.particleList.add(p2);
        
        Particle p3 = gp.particlePool.get();
        p3.setWithPosition(generator, target, color, size, speed, maxLife, 1, -1, style);
        gp.particleList.add(p3);
        
        Particle p4 = gp.particlePool.get();
        p4.setWithPosition(generator, target, color, size, speed, maxLife, 0, 1, style);
        gp.particleList.add(p4);

    } 
    public void update() {
        if (crowdControlTimer > 0) {
            crowdControlTimer--;
            if (invincible) {
                invincibleCounter++;
                if (invincibleCounter > invincibleDuration) {
                    invincible = false;
                    invincibleCounter = 0;
                }
            }
            if (hitFlashCounter > 0) hitFlashCounter--;
            return;
        }

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
        if (!collisionOn && !onPath) {
            switch (direction) {
                case DIR_UP:    worldY -= speed; break;
                case DIR_DOWN:  worldY += speed; break;
                case DIR_LEFT:  worldX -= speed; break;
                case DIR_RIGHT: worldX += speed; break;
            }
        }
        
        // IMPORTANT: If we just finished pathfinding, we don't want to keep moving.
        // The searchPath logic handles movement when onPath is true.

        boolean movedThisFrame = worldX != previousWorldX || worldY != previousWorldY;

        // TILE PARTICLES: emit footstep particles when moving
        if (movedThisFrame && gp.tileParticleEmitter != null) {
            footstepParticleCounter++;
            if (footstepParticleCounter >= gp.tileParticleEmitter.getEmitInterval()) {
                footstepParticleCounter = 0;
                int col = getCenterX() / gp.tileSize;
                int row = (worldY + gp.tileSize - 1) / gp.tileSize; // feet row
                int tileType = gp.tileM.getTileType(col, row);
                gp.tileParticleEmitter.emit(worldX, worldY, tileType, direction);
            }
        } else {
            footstepParticleCounter = 0;
        }

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

        if (invincible) {
            invincibleCounter++;
            if (invincibleCounter > invincibleDuration) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
        // HIT FLASH countdown
        if (hitFlashCounter > 0) hitFlashCounter--;
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

            int drawW = gp.tileSize;
            int drawH = gp.tileSize;

            currentSprite = getWalkFrameImage(direction, spriteNum);
            if (currentSprite == null) {
                currentSprite = getWalkFrameImage(direction, 1);
            }

            // Monster HP Bar
            if (type == type_monster && hpBarOn) {
                double oneScale = (double)gp.tileSize / maxLife;
                double hpBarValue = oneScale * life;

                g2.setColor(HP_BAR_BG);
                g2.fillRect(screenX - 1, screenY - 16, gp.tileSize + 2, 12);

                g2.setColor(HP_BAR_FG);
                g2.fillRect(screenX, screenY - 15, (int)hpBarValue, 10);

                hpBarCounter++;
                if (hpBarCounter > 300) {
                    hpBarCounter = 0;
                    hpBarOn = false;
                }
            }

            if (invincible) {
                hpBarOn = true;
                hpBarCounter = 0;
                changeAlpha(g2, 0.4F);
            }
            if (dying) {
                int deathJitter = Math.max(0, 6 - dyingCounter / 8);
                if (deathJitter > 0) {
                    screenX += (int) ((Math.random() * (deathJitter * 2 + 1)) - deathJitter);
                    screenY += (int) ((Math.random() * (deathJitter * 2 + 1)) - deathJitter);
                }

                // Arcade squash/pop before fade out.
                if (dyingCounter < 22) {
                    float t = dyingCounter / 22f;
                    float stretchX = 1.0f + (float)Math.sin(t * Math.PI) * 0.25f;
                    float squashY = 1.0f - (float)Math.sin(t * Math.PI) * 0.20f;
                    drawW = Math.max(1, (int)(gp.tileSize * stretchX));
                    drawH = Math.max(1, (int)(gp.tileSize * squashY));
                }
                dyingAnimation(g2);
            }
                
            // Safe guard against null images
            if (currentSprite != null) {
                int drawX = screenX - (drawW - gp.tileSize) / 2;
                int drawY = screenY - (drawH - gp.tileSize);
                g2.drawImage(currentSprite, drawX, drawY, drawW, drawH, null);
            }

            // HIT FLASH: tint sprite white when recently damaged
            // OPTIMIZATION: Reuse a single buffer instead of allocating a new BufferedImage every frame
            if (hitFlashCounter > 0 && currentSprite != null) {
                float flashAlpha = Math.min(1f, hitFlashCounter / (float) HIT_FLASH_DURATION * 0.8f);
                int sprW = currentSprite.getWidth();
                int sprH = currentSprite.getHeight();
                // Lazily allocate or resize the reusable flash buffer
                if (hitFlashBuffer == null || hitFlashBufferW < sprW || hitFlashBufferH < sprH) {
                    hitFlashBufferW = sprW;
                    hitFlashBufferH = sprH;
                    hitFlashBuffer = new BufferedImage(sprW, sprH, BufferedImage.TYPE_INT_ARGB);
                }
                java.awt.Graphics2D fg = hitFlashBuffer.createGraphics();
                // Clear previous contents
                fg.setComposite(AlphaComposite.Clear);
                fg.fillRect(0, 0, hitFlashBufferW, hitFlashBufferH);
                // Draw sprite then overlay white
                fg.setComposite(AlphaComposite.SrcOver);
                fg.drawImage(currentSprite, 0, 0, null);
                fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, flashAlpha));
                fg.setColor(Color.WHITE);
                fg.fillRect(0, 0, sprW, sprH);
                fg.dispose();
                g2.drawImage(hitFlashBuffer, screenX, screenY, gp.tileSize, gp.tileSize, null);
            }
            
            changeAlpha(g2, 1F);
        }
    }

    protected BufferedImage getWalkFrameImage(int dir, int frame) {
        // Try new array-based storage first (Player, Monster, NPC)
        if (walkFrames != null && dir >= 0 && dir < walkFrames.length && walkFrames[dir] != null) {
            int idx = frame - 1;
            if (idx >= 0 && idx < walkFrames[dir].length) return walkFrames[dir][idx];
        }
        // Fall back to legacy named fields (Object/Item classes)
        return switch (dir) {
            case DIR_UP -> switch (frame) {
                case 1 -> up1;  case 2 -> up2;  case 3 -> up3;  case 4 -> up4;
                case 5 -> up5;  case 6 -> up6;  case 7 -> up7;  default -> null;
            };
            case DIR_DOWN -> switch (frame) {
                case 1 -> down1; case 2 -> down2; case 3 -> down3; case 4 -> down4;
                case 5 -> down5; case 6 -> down6; case 7 -> down7; default -> null;
            };
            case DIR_LEFT -> switch (frame) {
                case 1 -> left1; case 2 -> left2; case 3 -> left3; case 4 -> left4;
                case 5 -> left5; case 6 -> left6; case 7 -> left7; case 8 -> left8;
                default -> null;
            };
            case DIR_RIGHT -> switch (frame) {
                case 1 -> right1; case 2 -> right2; case 3 -> right3; case 4 -> right4;
                case 5 -> right5; case 6 -> right6; case 7 -> right7; case 8 -> right8;
                default -> null;
            };
            default -> null;
        };
    }
    
    public void dyingAnimation(Graphics2D g2) {
        dyingCounter++;

        // Phase 1 (frames 1-24): arcade flicker + pulse
        if (dyingCounter <= 24) {
            int interval = 3;
            int phase = (dyingCounter - 1) / interval;
            changeAlpha(g2, (phase % 2 == 0) ? 0.25f : 0.95f);

            if (dyingCounter % 6 == 0) {
                for (int i = 0; i < 4; i++) {
                    Particle p = gp.particlePool.get();
                    float angle = (float)(Math.random() * Math.PI * 2.0);
                    int dx = (int)Math.round(Math.cos(angle) * 2);
                    int dy = (int)Math.round(Math.sin(angle) * 2);
                    p.setWithPosition(this, this, SPARK_COLOR_1, 4, 2, 14, dx, dy, Particle.STYLE_SPARK);
                    gp.particleList.add(p);
                }
            }
        }
        // Phase 2 (frames 25-44): fade out
        else if (dyingCounter <= 44) {
            float alpha = 1f - ((dyingCounter - 24) / 20f);
            changeAlpha(g2, Math.max(0f, alpha));
        }
        // Phase 3: emit death burst particles and remove
        else {
            grantQueuedDeathRewards();

            // Burst of 12 spark particles on death
            for (int i = 0; i < 12; i++) {
                Particle p = gp.particlePool.get();
                float angle = (float)(i * Math.PI * 2 / 12);
                int dx = (int)(Math.cos(angle) * 3);
                int dy = (int)(Math.sin(angle) * 3);
                p.setWithPosition(this, this, SPARK_COLOR_2, 5, 3, 22, dx, dy, Particle.STYLE_SPARK);
                gp.particleList.add(p);
            }
            alive = false;
        }
    }

    public void applyCrowdControl(int frames) {
        if (frames > crowdControlTimer) {
            crowdControlTimer = frames;
        }
    }

    public void beginDeath(int rewardExp, int rewardQuestKills, int rewardCoins) {
        if (dying || !alive) return;

        dying = true;
        collision = false;
        onPath = false;
        knockBack = false;
        speed = 0;
        crowdControlTimer = 0;
        dyingCounter = 0;
        deathRewardExp = Math.max(0, rewardExp);
        deathRewardQuestKills = Math.max(0, rewardQuestKills);
        deathRewardCoins = Math.max(0, rewardCoins);
        deathRewardsQueued = true;
    }

    private void grantQueuedDeathRewards() {
        if (!deathRewardsQueued) return;

        deathRewardsQueued = false;

        if (type == type_monster) {
            if (name != null) {
                gp.ui.addMessage("Killed the " + name + "!", Color.WHITE);
            }
            if (deathRewardExp > 0) {
                gp.player.exp += deathRewardExp;
                gp.player.checkLevelUp();
            }
            if (deathRewardQuestKills > 0 && gp.questManager != null) {
                gp.questManager.progress("slay_monsters", deathRewardQuestKills);
            }
            if (deathRewardCoins > 0) {
                gp.player.coin += deathRewardCoins;
                gp.ui.addMessage("+" + deathRewardCoins + " coins", COIN_MSG_COLOR);
            }
        }
    }
    public void changeAlpha(Graphics2D g2, float alphaValue) {
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alphaValue));
    }
    public BufferedImage setup(String imagePath, int width, int height) {
        BufferedImage image = null;
        try {
            image = ImageIO.read(getClass().getResourceAsStream(imagePath + ".png"));
            image = UtilityTool.scaleImage(image, width, height);
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
            case DIR_UP:    nextWorldY = user.getTopY() - gp.player.speed; break;
            case DIR_DOWN:  nextWorldY = user.getBottomY() + gp.player.speed; break;
            case DIR_LEFT:  nextWorldX = user.getLeftX() - gp.player.speed; break;
            case DIR_RIGHT: nextWorldX = user.getRightX() + gp.player.speed; break;
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
        if (gp.pFinder.search()) {
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
            direction = DIR_UP;
            checkCollision();
            if (!collisionOn) { worldY -= speed; return true; }
        } else if (enTopY < nextY) {
            direction = DIR_DOWN;
            checkCollision();
            if (!collisionOn) { worldY += speed; return true; }
        }
        return false;
    }

    // Try to move horizontally toward the target. Returns true if movement occurred.
    private boolean tryMoveHorizontal(int enLeftX, int nextX) {
        if (enLeftX > nextX) {
            direction = DIR_LEFT;
            checkCollision();
            if (!collisionOn) { worldX -= speed; return true; }
        } else if (enLeftX < nextX) {
            direction = DIR_RIGHT;
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
            if (dy < 0) { direction = DIR_UP; checkCollision(); if (!collisionOn) { worldY -= speed; moved = true; } }
            else if (dy > 0) { direction = DIR_DOWN; checkCollision(); if (!collisionOn) { worldY += speed; moved = true; } }
            if (!moved) {
                if (dx < 0) { direction = DIR_LEFT; checkCollision(); if (!collisionOn) { worldX -= speed; } }
                else if (dx > 0) { direction = DIR_RIGHT; checkCollision(); if (!collisionOn) { worldX += speed; } }
            }
        } else {
            // Try horizontal
            if (dx < 0) { direction = DIR_LEFT; checkCollision(); if (!collisionOn) { worldX -= speed; moved = true; } }
            else if (dx > 0) { direction = DIR_RIGHT; checkCollision(); if (!collisionOn) { worldX += speed; moved = true; } }
            if (!moved) {
                if (dy < 0) { direction = DIR_UP; checkCollision(); if (!collisionOn) { worldY -= speed; } }
                else if (dy > 0) { direction = DIR_DOWN; checkCollision(); if (!collisionOn) { worldY += speed; } }
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

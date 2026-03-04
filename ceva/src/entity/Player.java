package entity; import java.awt.AlphaComposite; import java.awt.Color; import java.awt.Graphics2D; import java.awt.Rectangle; import java.awt.image.BufferedImage; import java.util.ArrayList; import main.GamePanel; import main.KeyHandler; import object.OBJ_Arrow; import object.OBJ_Shield_Wood; import object.OBJ_Sword_Normal; public class Player extends Entity {

    // Constants
    private final int attackDuration = 20; // Total duration of attack animation in frames
    public final int maxInventorySize = 20;

    // Instance variables
    KeyHandler keyH;
    public final int screenX;
    public final int screenY;
    public ArrayList<Entity> inventory = new ArrayList<>();
    public int hasKey = 0;
    public int hasArtefact = 0;
    public int hasGem = 0;
    int counter = 0;
    // Combat
    public boolean attackCanceled = false;
    public int attackSpeed = 1; // Number of frames between attacks

    // Dash ability
    private boolean dashing = false;
    private int dashCounter = 0;
    private final int dashDuration = 10; // frames
    private int dashCooldown = 0;
    private final int dashCooldownMax = 60; // 1 second at 60fps
    private boolean dashParticle = false;
    private int idleCounter = 0;
    private int idleFrameDirection = 1;
    private final int idleFrameInterval = 10;
    private final int idleStartDelayFrames = 120;
    private int idleDelayCounter = 0;
    private boolean movingThisFrame = false;

    public Player(GamePanel gp, KeyHandler keyH) {
        super(gp);
        this.keyH = keyH;
        screenX = gp.screenWidth / 2 - (gp.tileSize / 2);
        screenY = gp.screenHeight / 2 - (gp.tileSize / 2);
        solidArea = new Rectangle();
        solidArea.x = 20;  // 20px padding left = 20px right, centered
        solidArea.y = 22;  // Slight top offset for upper body
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        solidArea.width = 24;  // 20 + 24 + 20 = 64 (full width)
        solidArea.height = 22; // Covers main body mass
        setDefaultValues();
    }

    // Initialization methods
    public void setDefaultValues() {
        worldX = (int)(gp.tileSize * 71.5);
        worldY = (int)(gp.tileSize * 21.5);
        defaultSpeed = 4;
        speed = defaultSpeed;
        direction = "down";
        level = 1;
        maxLife = 3;
        life = maxLife;
        strenght = 1;
        dexterity = 1;
        exp = 0;
        nextLevelExp = 5;
        coin = 0;
        maxMana = 5;
        mana = maxMana;
        currentWeapon = new OBJ_Sword_Normal(gp);
        currentShield = new OBJ_Shield_Wood(gp);
        projectile = new OBJ_Arrow(gp);
        attack = getAttack();
        defense = getDefense();
        getPlayerImages();
        getPlayerIdleImages();
        getPlayerAttackImages();
        setItems();
        setDialogue();
    }

    public void setItems() {
        inventory.add(currentWeapon);
        inventory.add(currentShield);
    }

    public void setDialogue() {
        dialogues[0][0] = "You are level " + level + " now!\n" + "Your stats have increased, keep going!";
    }

    public void setDefaultPositions() {
        worldX = (int)(gp.tileSize * 24.5);
        worldY = (int)(gp.tileSize * 15.5);
        direction = "down";
    }

    public void setPlayerStats(int life, int strenght, int dexterity, int speed, int mana) {
        this.maxLife = life;
        this.life = this.maxLife;
        this.strenght = strenght;
        this.dexterity = dexterity;
        this.speed = speed;
        this.maxMana = mana;
        this.mana = this.maxMana;
        this.attack = getAttack();
        this.defense = getDefense();
    }

    public void restoreLifeAndMana() {
        life = maxLife;
        mana = maxMana;
    }

    // Image loading methods
    /**
     * Adaugam un sistem de incarcare a unui spritesheet cu un numar variabil de cadre pe rand.
     */
    public void getPlayerImages() {
        // Specify frames per direction (randuri)
        int[] framesPerRow = {7, 8, 8, 7}; // down, left, right, up
        BufferedImage[][] frames = loadSheetVariable("/res/player/Player_walking-sheet", framesPerRow);
        // Assign down frames
        down1 = frames[0][0];
        down2 = frames[0][1];
        down3 = frames[0][2];
        down4 = frames[0][3];
        down5 = frames[0][4];
        down6 = frames[0][5];
        down7 = frames[0][6];
        // Assign left frames
        left1 = frames[1][0];
        left2 = frames[1][1];
        left3 = frames[1][2];
        left4 = frames[1][3];
        left5 = frames[1][4];
        left6 = frames[1][5];
        left7 = frames[1][6];
        left8 = frames[1][7];
        // Assign right frames
        right1 = frames[2][0];
        right2 = frames[2][1];
        right3 = frames[2][2];
        right4 = frames[2][3];
        right5 = frames[2][4];
        right6 = frames[2][5];
        right7 = frames[2][6];
        right8 = frames[2][7];
        // Assign up frames
        up1 = frames[3][0];
        up2 = frames[3][1];
        up3 = frames[3][2];
        up4 = frames[3][3];
        up5 = frames[3][4];
        up6 = frames[3][5];
        up7 = frames[3][6];
    }

    public void getPlayerAttackImages() {
        attackUp1 = setup("/res/player/b.attack/up/u1", gp.tileSize, gp.tileSize * 2);
        attackUp2 = setup("/res/player/b.attack/up/u2", gp.tileSize, gp.tileSize * 2);
        attackUp3 = setup("/res/player/b.attack/up/u3", gp.tileSize, gp.tileSize * 2);
        attackUp4 = setup("/res/player/b.attack/up/u4", gp.tileSize, gp.tileSize * 2);
        attackUp5 = setup("/res/player/b.attack/up/u5", gp.tileSize, gp.tileSize * 2);
        attackDown1 = setup("/res/player/b.attack/front/f1", gp.tileSize, gp.tileSize * 2);
        attackDown2 = setup("/res/player/b.attack/front/f2", gp.tileSize, gp.tileSize * 2);
        attackDown3 = setup("/res/player/b.attack/front/f3", gp.tileSize, gp.tileSize * 2);
        attackDown4 = setup("/res/player/b.attack/front/f4", gp.tileSize, gp.tileSize * 2);
        attackDown5 = setup("/res/player/b.attack/front/f5", gp.tileSize, gp.tileSize * 2);
        attackLeft1 = setup("/res/player/b.attack/left/l1", gp.tileSize * 2, gp.tileSize);
        attackLeft2 = setup("/res/player/b.attack/left/l2", gp.tileSize * 2, gp.tileSize);
        attackLeft3 = setup("/res/player/b.attack/left/l3", gp.tileSize * 2, gp.tileSize);
        attackLeft4 = setup("/res/player/b.attack/left/l4", gp.tileSize * 2, gp.tileSize);
        attackLeft5 = setup("/res/player/b.attack/left/l5", gp.tileSize * 2, gp.tileSize);
        attackRight1 = setup("/res/player/b.attack/right/r1", gp.tileSize * 2, gp.tileSize);
        attackRight2 = setup("/res/player/b.attack/right/r2", gp.tileSize * 2, gp.tileSize);
        attackRight3 = setup("/res/player/b.attack/right/r3", gp.tileSize * 2, gp.tileSize);
        attackRight4 = setup("/res/player/b.attack/right/r4", gp.tileSize * 2, gp.tileSize);
        attackRight5 = setup("/res/player/b.attack/right/r5", gp.tileSize * 2, gp.tileSize);
    }

    public void getPlayerIdleImages() {

        int[] framesPerRow = {6, 6, 6, 6}; // up, down, left, right
        BufferedImage[][] frames = loadSheetVariable("/res/player/Player_idle-sheet", framesPerRow);

        // Assign up idle frames

        upidle1 = frames[0][0];  upidle2 = frames[0][1];  upidle3 = frames[0][2];
        upidle4 = frames[0][3];  upidle5 = frames[0][4];  upidle6 = frames[0][5];

        // Assign down idle frames

        downidle1 = frames[1][0];  downidle2 = frames[1][1];  downidle3 = frames[1][2];
        downidle4 = frames[1][3];  downidle5 = frames[1][4];  downidle6 = frames[1][5];

        // Assign left idle frames

        leftidle1 = frames[2][0];  leftidle2 = frames[2][1];  leftidle3 = frames[2][2];
        leftidle4 = frames[2][3];  leftidle5 = frames[2][4];  leftidle6 = frames[2][5];

        // Assign right idle frames

        rightidle1 = frames[3][0];  rightidle2 = frames[3][1];  rightidle3 = frames[3][2];
        rightidle4 = frames[3][3];  rightidle5 = frames[3][4];  rightidle6 = frames[3][5];

    }

    // Update and movement methods
    public void update() {
        // Cancel attack if in dialogue or cutscene
        if (gp.gameState == gp.dialogueState || gp.gameState == gp.cutsceneState) {
            attacking = false;
            spriteCounter = 0;
            spriteNum = 1;
            idleDelayCounter = 0;
            movingThisFrame = false;
        }

        // Handle dash
        if (keyH.dashPressed && dashCooldown == 0 && !dashing) {
            dashing = true;
            dashCounter = dashDuration;
            dashCooldown = dashCooldownMax;
            dashParticle = true;
            generateParticle(this, this); // Dash particles
            dashParticle = false;
        }
        if (dashing) {
            speed = defaultSpeed * 2;
            dashCounter--;
            if (dashCounter <= 0) {
                dashing = false;
                speed = defaultSpeed;
            }
        }
        if (dashCooldown > 0) dashCooldown--;

        // Handle attacking first
        if (attacking && !attackCanceled) {
            attacking();
            // Slight attack movement if holding the same direction
            int attackMoveSpeed = Math.max(1, speed / 2);
            int nextX = worldX;
            int nextY = worldY;
            switch(direction) {
                case "up": if (keyH.upPressed) nextY -= attackMoveSpeed; break;
                case "down": if (keyH.downPressed) nextY += attackMoveSpeed; break;
                case "left": if (keyH.leftPressed) nextX -= attackMoveSpeed; break;
                case "right": if (keyH.rightPressed) nextX += attackMoveSpeed; break;
            }
            // Save original position
            int originalX = worldX;
            int originalY = worldY;
            // Temporarily move to next position for collision check
            worldX = nextX;
            worldY = nextY;
            collisionOn = false;
            gp.cChecker.checkTile(this);
            // Check for object collision
            int objIndex = gp.cChecker.checkObject(this, true);
            if (objIndex != 999) collisionOn = true;
            // Check for NPC collision
            int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
            if (npcIndex != 999) collisionOn = true;
            // Check for monster collision
            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            if (monsterIndex != 999) collisionOn = true;
            // Check for interactive tile collision
            int iTileIndex = gp.cChecker.checkEntity(this, gp.iTile);
            if (iTileIndex != 999) collisionOn = true;
            // If collision, reset position
            if (collisionOn) {
                worldX = originalX;
                worldY = originalY;
            }
        } else {
            // Determine direction based on keys — vertical takes priority for animation
            boolean movingUp = keyH.upPressed;
            boolean movingDown = keyH.downPressed;
            boolean movingLeft = keyH.leftPressed;
            boolean movingRight = keyH.rightPressed;
            boolean movingVertical = movingUp || movingDown;
            boolean movingHorizontal = movingLeft || movingRight;
            boolean diagonal = movingVertical && movingHorizontal;
            boolean moving = movingVertical || movingHorizontal;
            movingThisFrame = moving;
            
            // Set facing direction: vertical priority (up/down animation for diagonals)
            if (movingUp) direction = "up";
            else if (movingDown) direction = "down";
            else if (movingLeft) direction = "left";
            else if (movingRight) direction = "right";

            if (moving || keyH.enterPressed) {
                idleCounter = 0;
                idleFrameDirection = 1;
                idleDelayCounter = 0;

                // Calculate movement speeds
                // Diagonal movement: use 1/sqrt(2) ≈ 0.7071 per axis
                // This ensures total diagonal distance = cardinal distance (standard in Zelda, Diablo, Stardew Valley)
                int moveSpeedX = speed;
                int moveSpeedY = speed;
                if (diagonal) {
                    // 70.71% per axis — total vector equals cardinal speed, balanced feel
                    moveSpeedX = Math.max(1, (int)(speed * 0.7071));
                    moveSpeedY = Math.max(1, (int)(speed * 0.7071));
                }

                // --- PER-AXIS COLLISION: check and move each axis independently ---
                int originalX = worldX;
                int originalY = worldY;

                // Horizontal axis
                if (movingHorizontal && !keyH.enterPressed) {
                    // Temporarily set direction for collision checker prediction
                    String savedDir = direction;
                    direction = movingLeft ? "left" : "right";
                    collisionOn = false;
                    gp.cChecker.checkTile(this);
                    gp.cChecker.checkObject(this, false);
                    gp.cChecker.checkEntity(this, gp.npc);
                    gp.cChecker.checkEntity(this, gp.monster);
                    gp.cChecker.checkEntity(this, gp.iTile);
                    if (!collisionOn) {
                        if (movingLeft) worldX -= moveSpeedX;
                        if (movingRight) worldX += moveSpeedX;
                    }
                    direction = savedDir; // restore for vertical check
                }

                // Vertical axis
                if (movingVertical && !keyH.enterPressed) {
                    String savedDir = direction;
                    direction = movingUp ? "up" : "down";
                    collisionOn = false;
                    gp.cChecker.checkTile(this);
                    gp.cChecker.checkObject(this, false);
                    gp.cChecker.checkEntity(this, gp.npc);
                    gp.cChecker.checkEntity(this, gp.monster);
                    gp.cChecker.checkEntity(this, gp.iTile);
                    if (!collisionOn) {
                        if (movingUp) worldY -= moveSpeedY;
                        if (movingDown) worldY += moveSpeedY;
                    }
                    direction = savedDir;
                }

                // --- Full collision pass for pickups, NPC interaction, events ---
                // Reset direction to the animation direction for game logic
                if (movingUp) direction = "up";
                else if (movingDown) direction = "down";
                else if (movingLeft) direction = "left";
                else if (movingRight) direction = "right";

                collisionOn = false;
                gp.cChecker.checkTile(this);
                int objIndex = gp.cChecker.checkObject(this, true);
                pickUpObject(objIndex);
                int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
                interactNPC(npcIndex);
                int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
                contactMonster(monsterIndex);
                int iTileIndex = gp.cChecker.checkEntity(this, gp.iTile);
                gp.eHandler.checkEvent();

                // Start attack if enter pressed
                if (keyH.enterPressed && !attackCanceled) {
                    attacking = true;
                    spriteCounter = 0;
                }
                attackCanceled = false;
                keyH.enterPressed = false;
                // Update animation sprites
                updateSprite();

                // TILE PARTICLES: emit footstep particles when player is moving
                if (gp.tileParticleEmitter != null) {
                    footstepParticleCounter++;
                    if (footstepParticleCounter >= gp.tileParticleEmitter.getEmitInterval()) {
                        footstepParticleCounter = 0;
                        int col = getCenterX() / gp.tileSize;
                        int row = (worldY + gp.tileSize - 1) / gp.tileSize;
                        int tileType = gp.tileM.getTileType(col, row);
                        gp.tileParticleEmitter.emit(worldX, worldY, tileType, direction);
                    }
                }
            } else {
                footstepParticleCounter = 0;
                // Wait 2 seconds before playing idle animation
                idleDelayCounter++;

                if (idleDelayCounter >= idleStartDelayFrames) {
                    updateIdleSprite();
                } else {
                    spriteNum = 1;
                    spriteNum1 = 1;
                }
            }
            if(gp.keyH.shotKeyPressed == true && projectile.alive == false && shotAvailableCounter == 30 && projectile.haveResource(this) == true) {
                //SET DEFAULT COORDINATES, DIRECTIONS AND USER
                projectile.set(worldX, worldY, direction, true, this);
                // SUBSTRACT RESOURCES
                projectile.subtractResource(this);
                //ADD TO ARRAY
                gp.projectilesList.add(projectile);
                shotAvailableCounter = 0;
                //gp.playSE(12);
            }
            if (shotAvailableCounter < 30) {
                shotAvailableCounter++;
            }
            // Handle invincibility timer
            if (invincible) {
                invincibleCounter++;
                if (invincibleCounter > 60) {
                    invincible = false;
                    invincibleCounter = 0;
                }
            }
        }
    }

    private void updateSprite() {
        spriteCounter++;
        spriteCounter1++;
        // Movement animation - speed
        if (spriteCounter1 > 8) {
            spriteNum1 = (spriteNum1 % 8) + 1; // loops 1-8
            spriteCounter1 = 0;
        }
        if (spriteCounter > 7) {
            spriteNum = (spriteNum % 7) + 1; // loops 1-7
            spriteCounter = 0;
        }
    }

    private void updateIdleSprite() {
        idleCounter++;
        int maxIdleFrame = 6;

        if (idleCounter > idleFrameInterval) {
            spriteNum += idleFrameDirection;

            if (spriteNum >= maxIdleFrame) {
                spriteNum = maxIdleFrame;
                idleFrameDirection = -1;
            }
            if (spriteNum <= 1) {
                spriteNum = 1;
                idleFrameDirection = 1;
            }

            idleCounter = 0;
        }

        spriteNum1 = 1;
    }

    public void attacking() {
        spriteCounter++;
        int frameLength = attackDuration / 5; // 5 animation frames
        int currentFrame = spriteCounter / frameLength + 1;
        if (currentFrame > 5) currentFrame = 5;
        spriteNum = currentFrame;
        // HIT on frame 3 only (middle swing)
        if (currentFrame == 3 && spriteCounter % frameLength == 0) {
            performAttackHitbox();
        }
        // End attack
        if (spriteCounter >= attackDuration) {
            spriteCounter = 0;
            attacking = false;
        }
    }

    private void performAttackHitbox() {
        // Temporary entity for attack hitbox
        Entity attackHitbox = new Entity(gp);
        attackHitbox.worldX = worldX;
        attackHitbox.worldY = worldY;
        attackHitbox.solidArea = new Rectangle(solidArea);
        
        int ts = gp.tileSize;
        // IMPROVED: Attack hitbox is now bigger and more forgiving, matches sprite dimensions
        switch(direction) {
            case "up":
                // Vertical attack sprite is tileSize × tileSize*2, extends upward
                attackHitbox.solidArea.width = ts - 16;  // 48 width (centered)
                attackHitbox.solidArea.height = ts + 16; // 80 height (larger reach)
                attackHitbox.worldX += 8;   // Center horizontally
                attackHitbox.worldY -= ts + 16; // Extend upward
                break;
            case "down":
                // Vertical attack sprite is tileSize × tileSize*2, extends downward
                attackHitbox.solidArea.width = ts - 16;  // 48 width (centered)
                attackHitbox.solidArea.height = ts + 16; // 80 height (larger reach)
                attackHitbox.worldX += 8;   // Center horizontally
                attackHitbox.worldY += ts;  // Extend downward from player
                break;
            case "left":
                // Horizontal attack sprite is tileSize*2 × tileSize, extends leftward
                attackHitbox.solidArea.width = ts + 16;  // 80 width (larger reach)
                attackHitbox.solidArea.height = ts - 16; // 48 height (centered)
                attackHitbox.worldX -= ts + 16; // Extend leftward
                attackHitbox.worldY += 8;   // Center vertically
                break;
            case "right":
                // Horizontal attack sprite is tileSize*2 × tileSize, extends rightward
                attackHitbox.solidArea.width = ts + 16;  // 80 width (larger reach)
                attackHitbox.solidArea.height = ts - 16; // 48 height (centered)
                attackHitbox.worldX += ts;  // Extend rightward from player
                attackHitbox.worldY += 8;   // Center vertically
                break;
        }
        // Check for tile using the attack hitbox instead of player
        int iTileIndex = gp.cChecker.checkEntity(attackHitbox, gp.iTile);
        damageInteractiveTile(iTileIndex);
        // Check for monster using the attack hitbox
        int monsterIndex = gp.cChecker.checkEntity(attackHitbox, gp.monster);
        damageMonster(monsterIndex, attack);
    }

    // Combat methods
    public void damageMonster(int i, int attack) {
        if (i != 999) {
            if (gp.monster[i].invincible == false) {
                gp.playSE(5); // Play hit sound
                // determine knockback strength based on equipped weapon (smaller)
                int kb = (currentWeapon != null) ? currentWeapon.knockBackPower / 2 : 1;
                if (kb < 1) kb = 1;
                knockBack(gp.monster[i], kb, worldX, worldY);
                int damage = attack - gp.monster[i].defense;
                if (damage < 0) {
                    damage = 0;
                }
                gp.monster[i].life -= damage;
                generateParticle(this, gp.monster[i]);
                gp.ui.addMessage(damage + " damage!", Color.WHITE);
                gp.monster[i].invincible = true;
                gp.monster[i].damageReaction();
                if (gp.monster[i].life <= 0) {
                    gp.monster[i].dying = true;
                    gp.ui.addMessage("Killed the " + gp.monster[i].name + "!", Color.WHITE);
                    exp += gp.monster[i].exp;
                    checkLevelUp();
                }
            }
        }
    }

    /**
     * Apply a simplified knockback to a target entity.
     * The victim is pushed in the direction the player is currently facing,
     * at a speed equal to `power` pixels per frame, and travels a short
     * distance proportional to power.
     *
     * @param entity victim
     * @param power  strength (larger means farther/longer)
     * @param srcX   ignored (kept for compatibility)
     * @param srcY   ignored (kept for compatibility)
     */
    public void knockBack(Entity entity, int power, int srcX, int srcY) {
        // determine vector from player's facing
        int vx = 0, vy = 0;
        switch(direction) {
            case "up":    vy = -power; break;
            case "down":  vy = power;  break;
            case "left":  vx = -power; break;
            case "right": vx = power;  break;
        }
        // assign vector and travel distance (quarter tile per power unit)
        entity.knockBackVectorX = vx;
        entity.knockBackVectorY = vy;
        entity.knockBackRemaining = power * (gp.tileSize / 4);
        entity.knockBackPower = power; // debug value
        entity.knockBack = true;
    }

    public void contactMonster(int i) {
        if (i != 999 && !invincible) {
            // ignore dying/dead monsters entirely
            if (gp.monster[i].dying || !gp.monster[i].alive) {
                return;
            }
            gp.playSE(8);
            int damage = gp.monster[i].attack - defense;
            // Only player bleeds when taking damage (removed generateParticle for monster)
            bleed(); // Generate blood particles on player
            if (damage < 1) {
                damage = 1;
            }
            life -= damage;
            invincible = true;
            // compute knockback direction away from the monster
            int dx = worldX - gp.monster[i].worldX;
            int dy = worldY - gp.monster[i].worldY;
            if (Math.abs(dx) > Math.abs(dy)) {
                direction = (dx > 0) ? "right" : "left";
            } else {
                direction = (dy > 0) ? "down" : "up";
            }
            // monster hit power is roughly its attack value (smaller)
            int kb = (gp.monster[i].attack + 1) / 2;
            if (kb < 1) kb = 1;
            knockBack(this, kb, gp.monster[i].worldX, gp.monster[i].worldY);
        }
    }

    /**
     * Generate extra blood particles when player takes damage
     */
    public void bleed() {
        Color bloodColorA = new Color(220, 35, 45);
        Color bloodColorB = new Color(150, 20, 30);
        
        Particle p1 = gp.particlePool.get();
        p1.setWithPosition(this, this, bloodColorA, 9, 2, 24, -1, -2, Particle.STYLE_BLOOD);
        gp.particleList.add(p1);
        
        Particle p2 = gp.particlePool.get();
        p2.setWithPosition(this, this, bloodColorA, 9, 2, 24, 1, -2, Particle.STYLE_BLOOD);
        gp.particleList.add(p2);
        
        Particle p3 = gp.particlePool.get();
        p3.setWithPosition(this, this, bloodColorB, 7, 2, 20, 0, -1, Particle.STYLE_BLOOD);
        gp.particleList.add(p3);

        Particle p4 = gp.particlePool.get();
        p4.setWithPosition(this, this, bloodColorB, 6, 1, 18, -1, -1, Particle.STYLE_BLOOD);
        gp.particleList.add(p4);
    }

    // Interaction methods
    public void pickUpObject(int i) {
        if (i != 999) {
            // PICKUP ONLY OBJECTS
            if (gp.obj[i].type == type_pickupOnly) {
                attackCanceled = true;
                if (gp.obj[i].use(this) == true) {
                    gp.obj[i] = null;
                } else return;
            }
            // INTERACTABLE OBJECTS
            else if (gp.obj[i].type == type_obstacle) {
                if(keyH.enterPressed == true) {
                    attackCanceled = true;
                    gp.obj[i].interact();
                }
            }
            // OBTAINABLE OBJECTS
            else if (canObtainItem(gp.obj[i]) == true) {
                attackCanceled = true;
                gp.playSE(2);
                String objectName = gp.obj[i].name;
                switch (objectName) {
                    // case "Compas" -> {
                    //     gp.teleportation = true;
                    //     gp.ui.addMessage("Teleportation unlocked!", Color.WHITE);
                    //     break;
                    // }
                    case "Potion" -> {
                        gp.obj[i] = null;
                        gp.ui.addMessage("You've got a potion!", Color.WHITE);
                        break;
                    }
                    case "Boots" -> {
                        gp.obj[i] = null;
                        gp.ui.addMessage("Speed increased!", Color.WHITE);
                        gp.player.speed += 1;
                        gp.bootsUnlocked = true;
                        break;
                    }
                    case "Key" -> {
                        gp.playSE(2);
                        hasKey++;
                        gp.obj[i] = null;
                        gp.ui.addMessage("You got a key!", Color.WHITE);
                    }
                    case "Tent" -> {
                        gp.playSE(2);
                        gp.ui.addMessage("You got a tent!", Color.WHITE);
                    }
                    case "Spell book" -> {
                        gp.playSE(2);
                        gp.obj[i] = null;
                        gp.ui.addMessage("You got a new weapon!", Color.WHITE);
                    }
                }
            }
        }
    }

    public void interactNPC(int i) {
        if (gp.keyH.enterPressed == true) {
            if (i != 999) {
                attackCanceled = true;
                gp.npc[i].speak();
            } else {
                gp.playSE(10);
                attacking = true;
            }
        }
    }

    public void damageInteractiveTile(int i) {
        if (i != 999 && gp.iTile[i].destructible == true && gp.iTile[i].isCorrectItem(this) == true && gp.iTile[i].invincible == false) {
            Entity tile = gp.iTile[i]; // ✅ Save reference first
            gp.iTile[i] = null; // Remove tile
            generateParticle(tile, tile); // ✅ Use saved reference
        }
    }

    // Inventory methods
    public void selectItem() {
        int itemIndex = gp.ui.getItemIndexOnSlot();
        if (itemIndex < inventory.size()) {
            Entity selectedItem = inventory.get(itemIndex);

            if (selectedItem.type == type_sword || selectedItem.type == type_book) {
                currentWeapon = selectedItem;
                attack = getAttack();
                gp.ui.addMessage("Equipped " + selectedItem.name + "!", Color.WHITE);
                gp.playSE(9); // equip sound
            } else if (selectedItem.type == type_shield) {
                currentShield = selectedItem;
                defense = getDefense();
                gp.ui.addMessage("Equipped " + selectedItem.name + "!", Color.WHITE);
                gp.playSE(9);
            } else if (selectedItem.type == type_consumable) {
                attackCanceled = true;
                if (selectedItem.use(this) == true && selectedItem.name != "Potion") {
                    gp.ui.addMessage("Used " + selectedItem.name + ".", Color.WHITE);
                    gp.playSE(11); // generic use sound
                    if (selectedItem.amount > 1) {
                        selectedItem.amount--;
                    } else {
                        inventory.remove(itemIndex);
                    }
                }
            }
        }
    }

    public int searchItemInInventory(String itemName) {
        int itemIndex = 999;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).name.equals(itemName)) {
                itemIndex = i;
                break;
            }
        }
        return itemIndex;
    }

    public boolean canObtainItem(Entity item) {
        if (item == null) {
            System.err.println("canObtainItem() called with null item!");
            return false;
        }
        boolean canObtain = false;
        // CHECK IF ITEM IS STACKABLE
        if (item.stackable == true) {
            int index = searchItemInInventory(item.name);
            if (index != 999) {
                inventory.get(index).amount++;
                canObtain = true;
            } else {
                if (inventory.size() != maxInventorySize) {
                    inventory.add(item);
                    canObtain = true;
                }
            }
        } else {
            if (inventory.size() != maxInventorySize) {
                inventory.add(item);
                canObtain = true;
            }
        }
        return canObtain;
    }

    public int getCurrentWeaponSlot() {
        int currentWeaponSlot = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i) == currentWeapon) {
                currentWeaponSlot = i;
            }
        }
        return currentWeaponSlot;
    }

    public int getCurrentShieldSlot() {
        int currentShieldSlot = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i) == currentShield) {
                currentShieldSlot = i;
            }
        }
        return currentShieldSlot;
    }

    // drop the currently selected item from inventory (does not spawn in world)
    public void dropItem() {
        int itemIndex = gp.ui.getItemIndexOnSlot();
        if (itemIndex < inventory.size()) {
            Entity item = inventory.get(itemIndex);
            inventory.remove(itemIndex);
            gp.ui.addMessage("Dropped " + item.name + ".", Color.WHITE);
            gp.playSE(12); // use a drop sound or simple click
        }
    }

    // Rendering methods
    public void draw(Graphics2D g2) {
        BufferedImage image = null;
        int tempScreenX = screenX;
        int tempScreenY = screenY;
        switch(direction) {
            case "up":
                if(attacking == false){
                    if (!movingThisFrame) {
                        if(spriteNum == 1) {image = upidle1;}
                        if(spriteNum == 2) {image = upidle2;}
                        if(spriteNum == 3) {image = upidle3;}
                        if(spriteNum == 4) {image = upidle4;}
                        if(spriteNum == 5) {image = upidle5;}
                        if(spriteNum == 6) {image = upidle6;}
                        break;
                    }
                    if(spriteNum == 1) {image = up1;}
                    if(spriteNum == 2) {image = up2;}
                    if(spriteNum == 3) {image = up3;}
                    if(spriteNum == 4) {image = up4;}
                    if(spriteNum == 5) {image = up5;}
                    if(spriteNum == 6) {image = up6;}
                    if(spriteNum == 7) {image = up7;}
                    break;
                }
                if(attacking == true){
                    tempScreenY = tempScreenY - gp.tileSize;
                    if(spriteNum == 1){image = attackUp1;}
                    if(spriteNum == 2){image = attackUp2;}
                    if(spriteNum == 3){image = attackUp3;}
                    if(spriteNum == 4){image = attackUp4;}
                    if(spriteNum == 5){image = attackUp5;}
                    break;
                }
            case "down":
                if(attacking == false){
                    if (!movingThisFrame) {
                        if(spriteNum == 1) {image = downidle1;}
                        if(spriteNum == 2) {image = downidle2;}
                        if(spriteNum == 3) {image = downidle3;}
                        if(spriteNum == 4) {image = downidle4;}
                        if(spriteNum == 5) {image = downidle5;}
                        if(spriteNum == 6) {image = downidle6;}
                        break;
                    }
                    if(spriteNum == 1) {image = down1;}
                    if(spriteNum == 2) {image = down2;}
                    if(spriteNum == 3) {image = down3;}
                    if(spriteNum == 4) {image = down4;}
                    if(spriteNum == 5) {image = down5;}
                    if(spriteNum == 6) {image = down6;}
                    if(spriteNum == 7) {image = down7;}
                    break;
                }
                if(attacking == true){
                    tempScreenY = screenY;
                    if(spriteNum == 1){image = attackDown1;}
                    if(spriteNum == 2){image = attackDown2;}
                    if(spriteNum == 3){image = attackDown3;}
                    if(spriteNum == 4){image = attackDown4;}
                    if(spriteNum == 5){image = attackDown5;}
                    break;
                }
            case "left":
                if(attacking == false){
                    if (!movingThisFrame) {
                        if(spriteNum == 1) {image = leftidle1;}
                        if(spriteNum == 2) {image = leftidle2;}
                        if(spriteNum == 3) {image = leftidle3;}
                        if(spriteNum == 4) {image = leftidle4;}
                        if(spriteNum == 5) {image = leftidle5;}
                        if(spriteNum == 6) {image = leftidle6;}
                        break;
                    }
                    if(spriteNum == 1) {image = left1;}
                    if(spriteNum == 2) {image = left2;}
                    if(spriteNum == 3) {image = left3;}
                    if(spriteNum == 4) {image = left4;}
                    if(spriteNum == 5) {image = left5;}
                    if(spriteNum == 6) {image = left6;}
                    if(spriteNum == 7) {image = left7;}
                    if(spriteNum == 8) {image = left8;}
                    break;
                }
                if(attacking == true){
                    tempScreenX = screenX - gp.tileSize;
                    if(spriteNum == 1){image = attackLeft1;}
                    if(spriteNum == 2){image = attackLeft2;}
                    if(spriteNum == 3){image = attackLeft3;}
                    if(spriteNum == 4){image = attackLeft4;}
                    if(spriteNum == 5){image = attackLeft5;}
                    break;
                }
            case "right":
                if(attacking == false){
                    if (!movingThisFrame) {
                        if(spriteNum == 1) {image = rightidle1;}
                        if(spriteNum == 2) {image = rightidle2;}
                        if(spriteNum == 3) {image = rightidle3;}
                        if(spriteNum == 4) {image = rightidle4;}
                        if(spriteNum == 5) {image = rightidle5;}
                        if(spriteNum == 6) {image = rightidle6;}
                        break;
                    }
                    if(spriteNum == 1) {image = right1;}
                    if(spriteNum == 2) {image = right2;}
                    if(spriteNum == 3) {image = right3;}
                    if(spriteNum == 4) {image = right4;}
                    if(spriteNum == 5) {image = right5;}
                    if(spriteNum == 6) {image = right6;}
                    if(spriteNum == 7) {image = right7;}
                    if(spriteNum == 8) {image = right8;}
                    break;
                }
                if(attacking == true){
                    tempScreenX = screenX;
                    if(spriteNum == 1){image = attackRight1;}
                    if(spriteNum == 2){image = attackRight2;}
                    if(spriteNum == 3){image = attackRight3;}
                    if(spriteNum == 4){image = attackRight4;}
                    if(spriteNum == 5){image = attackRight5;}
                    break;
                }
        }
        if (invincible == true) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        }
        g2.drawImage(image, tempScreenX, tempScreenY, null);
        // RESET ALPHA
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }

    // Utility methods
    public int getAttack() {
        attackArea = currentWeapon.attackArea;
        return attack = strenght * currentWeapon.attackValue;
    }

    public int getDefense() {
        return defense = dexterity * currentShield.defenseValue;
    }

    public void checkLevelUp() {
        if (exp >= nextLevelExp) {
            level++;
            nextLevelExp = nextLevelExp * 2;
            maxLife++;
            strenght++;
            dexterity++;
            life = maxLife;
            attack = getAttack();
            defense = getDefense();
            gp.playSE(11);
            setDialogue();
            startDialogue(this, 0);
        }
    }

    // Particle methods
    public Color getParticleColor() {
        if (dashParticle) {
            return new Color(160, 160, 160); // gray dust for dash
        }
        return new Color(210, 32, 45);
    }

    public int getParticleSize() {
        if (dashParticle) {
            return 6; // smaller for dash
        }
        return 10;
    }

    public int getParticleSpeed() {
        if (dashParticle) {
            return 2; // faster for dash
        }
        return 2;
    }

    public int getParticleMaxLife() {
        if (dashParticle) {
            return 15; // shorter life for dash
        }
        return 24;
    }

    @Override
    public int getParticleStyle() {
        if (dashParticle) {
            return Particle.STYLE_DUST;
        }
        return Particle.STYLE_BLOOD;
    }

    public Color getParticleColor1() {
        Color color = new Color(255, 50, 0);
        return color;
    }

    public int getParticleSize1() {
        int size = 10; // pixels
        return size;
    }

    public int getParticleSpeed1() {
        int speed = 1;
        return speed;
    }

    public int getParticleMaxLife1() {
        int maxLife = 20;
        return maxLife;
    }

    // Dash particles
    public Color getParticleColorDash() {
        Color color = new Color(150, 150, 150); // gray dust
        return color;
    }

    public int getParticleSizeDash() {
        int size = 6; // smaller
        return size;
    }

    public int getParticleSpeedDash() {
        int speed = 2; // faster
        return speed;
    }

    public int getParticleMaxLifeDash() {
        int maxLife = 15; // shorter life
        return maxLife;
    }

}
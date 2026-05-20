package entity;

import java.awt.AlphaComposite;
 import java.awt.Color;
 import java.awt.Graphics2D;
 import java.awt.Rectangle;
 import java.awt.image.BufferedImage;
 import java.util.ArrayList;

 import audio.SFX;
 import main.GamePanel;
 import main.KeyHandler;
 import main.SkillTree;
 import object.OBJ_Arrow;

public class Player extends Entity {

    // Pre-allocated Color constants (avoid per-frame GC pressure)
    private static final Color COLOR_OVERDRIVE_AURA = new Color(255, 160, 90);
    private static final Color COLOR_COMBO_GOLDEN = new Color(255, 200, 80);
    private static final Color COLOR_COMBO_FIERY = new Color(255, 120, 50);
    private static final Color COLOR_COMBO_PALE = new Color(255, 220, 100);
    private static final Color COLOR_SHOCKWAVE_SPARK = new Color(255, 190, 90);
    private static final Color COLOR_VOID_SNARE = new Color(140, 110, 220);
    private static final Color COLOR_FROST_NOVA = new Color(150, 210, 255);
    private static final Color COLOR_OVERDRIVE_MSG = new Color(255, 185, 100);
    private static final Color COLOR_BLOOD_A = new Color(220, 35, 45);
    private static final Color COLOR_BLOOD_B = new Color(150, 20, 30);
    private static final Color COLOR_TELEPORT = new Color(100, 180, 255);
    private static final Color COLOR_DUST_LIGHT = new Color(165, 140, 105);
    private static final Color COLOR_DUST_DARK = new Color(140, 120, 90);
    private static final Color COLOR_DUST_WARM = new Color(180, 155, 120);
    private static final Color COLOR_DUST_PUFF = new Color(155, 135, 105);
    private static final Color COLOR_DUST_LAND = new Color(150, 130, 100);
    private static final Color COLOR_DMG_RED = new Color(255, 80, 60);
    private static final Color COLOR_DMG_GRAY = new Color(180, 180, 180);
    private static final Color COLOR_EVADE_FLASH = new Color(255, 245, 220);
    private static final Color COLOR_LEVELUP_MSG = new Color(255, 220, 110);
    private static final Color COLOR_LEVELUP_RING = new Color(160, 200, 255);

    // Dash afterimage arrays (avoid per-frame allocation)
    private static final float[] DASH_ALPHAS = {0.28f, 0.16f, 0.07f};
    private static final int[] DASH_OFFSETS = {5, 10, 15};

    public BufferedImage[][] hitFrames;    // getHit sprite sheet [dir][frame]
    public BufferedImage[][] deathFrames;  // death sprite sheet  [dir][frame]
    public boolean playerDying = false;     // death animation in progress
    public int playerDeathCounter = 0;      // tick counter for death anim
    public int playerDeathFrame = 0;        // current death frame index
    public int hitAnimCounter = 0;          // tick counter for hit anim
    public int hitAnimFrame = 0;            // current hit frame index
    public int hitAnimDirection = DIR_DOWN;  // locked facing direction for hit anim
    public int deathDirection = DIR_DOWN;    // locked facing direction for death anim
    private static final int HIT_ANIM_SPEED = 6;     // ticks per hit frame
    private static final int DEATH_ANIM_SPEED = 10;   // ticks per death frame
    private static final int DEATH_HOLD_DELAY = 60;   // hold last frame before game over
    private static final int DEATH_TOTAL_FRAMES = 5;  // frames in death sheet per direction
    private static final int HIT_TOTAL_FRAMES = 4;    // frames in hit sheet per direction

    public final int maxInventorySize = 20;

    KeyHandler keyH;
    public int screenX;
    public int screenY;
    // Smooth camera: camScreenX/Y lerp toward the clamped target screenX/Y each tick
    private float camScreenX;
    private float camScreenY;
    private static final float CAM_LERP = 0.15f;
    public float getCamScreenX() { return camScreenX; }
    public float getCamScreenY() { return camScreenY; }
    public ArrayList<Entity> inventory = new ArrayList<>();
    public int hasKey = 0;
    public int hasArtefact = 0;
    public int hasGem = 0;
    public boolean attackCanceled = false;
    public int attackSpeed = 1;

    private int comboStep = 0;
    private int comboWindow = 0;     // frames remaining to chain next attack
    private static final int COMBO_WINDOW_MAX = 20;
    private boolean attackBuffered = false;

    public int levelUpChoice = 0;
    public String[] levelUpOptions;
    public int[] levelUpValues;
    public int skillPoints = 20;
    public final SkillTree skillTree = new SkillTree();

    public float meleeDamageMultiplier = 1f;
    public float damageTakenMultiplier = 1f;
    public boolean dashUnlocked = true;
    public int dashCooldownBonus = 0;
    public int teleportCooldownBonus = 0;
    public boolean shockwaveUnlocked = false;
    public boolean voidSnareUnlocked = false;
    public boolean frostNovaUnlocked = false;
    public boolean overdriveUnlocked = false;
    public boolean soulReaperUnlocked = false;
    public boolean berserkerFuryUnlocked = false;
    public boolean shadowStepUnlocked = false;
    public boolean manaSiphonUnlocked = false;
    public boolean manaShieldUnlocked = false;
    public boolean thornsUnlocked = false;
    public boolean secondWindUnlocked = false;
    public boolean secondWindAvailable = true;
    public boolean vampiricStrikeUnlocked = false;
    public boolean lastStandUnlocked = false;
    public boolean undyingWillUnlocked = false;
    public int undyingWillCooldown = 0;

    public int levelUpBannerTimer = 0;
    public String levelUpBannerText = "";

    private boolean dashing = false;
    private int dashCounter = 0;
    private final int dashDuration = 10;  // frames (~0.17s — snappy burst)
    private int dashCooldown = 0;
    private final int dashCooldownMax = 38; // ~0.63s cooldown
    private boolean dashParticle = false;
    private int evadeRecovery = 0;         // brief post-evade slowdown (footing)
    private static final int EVADE_RECOVERY_FRAMES = 3;
    private int evadeFlashTimer = 0;       // armor-glint flash on evade start
    private int shockwaveCooldown = 0;
    private int voidSnareCooldown = 0;
    private int frostNovaCooldown = 0;
    private int overdriveCooldown = 0;
    private int overdriveTimer = 0;

    private static final int SHOCKWAVE_COOLDOWN_MAX = 150;
    private static final int VOID_SNARE_COOLDOWN_MAX = 220;
    private static final int FROST_NOVA_COOLDOWN_MAX = 200;
    private static final int OVERDRIVE_COOLDOWN_MAX = 420;
    private static final int OVERDRIVE_DURATION = 180;

    // Attack anticipation (wind-up hold on frame 1)
    private int anticipationTimer = -1;

    // Visual-only offset during attacks (lean into the swing without moving hitbox)
    private int animOffsetX = 0;
    private int animOffsetY = 0;

    // Swing trail afterimages
    private static final int TRAIL_SIZE = 4;
    private final int[] trailWorldX = new int[TRAIL_SIZE];
    private final int[] trailWorldY = new int[TRAIL_SIZE];
    private int trailIndex = 0;
    private int trailCount = 0;
    private boolean trailActive = false;

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
        solidArea.x = gp.tileSize * 20 / 64;   // 20px at 64px tile, scales proportionally
        solidArea.y = gp.tileSize * 22 / 64;   // 22px at 64px tile
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        solidArea.width  = gp.tileSize * 24 / 64;  // 24px at 64px tile
        solidArea.height = gp.tileSize * 22 / 64;  // 22px at 64px tile
        setDefaultValues();
    }

    public void setDefaultValues() {
        worldX = 0;
        worldY = 0;
        defaultSpeed = 5;
        speed = defaultSpeed;
        direction = DIR_DOWN;
        level = 100;
        maxLife = 100;
        life = maxLife;
        strenght = 2;
        dexterity = 1;
        exp = 0;
        nextLevelExp = 5;
        coin = 0;
        maxMana = 3;
        mana = maxMana;
        skillPoints = 100;
        meleeDamageMultiplier = 1f;
        damageTakenMultiplier = 1f;
        dashUnlocked = false;
        dashCooldownBonus = 0;
        teleportCooldownBonus = 0;
        shockwaveUnlocked = false;
        voidSnareUnlocked = false;
        frostNovaUnlocked = false;
        overdriveUnlocked = false;
        soulReaperUnlocked = false;
        berserkerFuryUnlocked = false;
        shadowStepUnlocked = false;
        manaSiphonUnlocked = false;
        manaShieldUnlocked = false;
        thornsUnlocked = false;
        secondWindUnlocked = false;
        secondWindAvailable = true;
        vampiricStrikeUnlocked = false;
        lastStandUnlocked = false;
        undyingWillUnlocked = false;
        undyingWillCooldown = 0;
        levelUpBannerTimer = 0;
        levelUpBannerText = "";
        shockwaveCooldown = 0;
        voidSnareCooldown = 0;
        frostNovaCooldown = 0;
        overdriveCooldown = 0;
        overdriveTimer = 0;
        currentWeapon = null;
        currentShield = null;
        projectile = new OBJ_Arrow(gp);
        attack = getAttack();
        defense = getDefense();
        getPlayerImages();
        getPlayerIdleImages();
        getPlayerAttackImages();
        getPlayerHitImages();
        getPlayerDeathImages();
        setItems();
        setDialogue();
    }

    public void setItems() {
        // Equipment is set but not added to inventory at game start
    }

    public void setDialogue() {
        ensureDialogues()[0][0] = "You are level " + level + " now!\n" + "Your stats have increased, keep going!";
    }

    public void setDefaultPositions() {
        if (gp.mapManager.defaultSpawnCol >= 0 && gp.mapManager.defaultSpawnRow >= 0) {
            worldX = gp.mapManager.defaultSpawnCol * gp.tileSize;
            worldY = gp.mapManager.defaultSpawnRow * gp.tileSize;
        } else {
            worldX = (int)(gp.tileSize * 24.5);
            worldY = (int)(gp.tileSize * 15.5);
        }
        direction = DIR_DOWN;
        // Snap camera instantly to new spawn position (no lerp drift across map transitions)
        camScreenX = 0f;
        camScreenY = 0f;
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
        playerDying = false;
        playerDeathCounter = 0;
        playerDeathFrame = 0;
        hitAnimCounter = 0;
        hitAnimFrame = 0;
    }

    /**
     * Adaugam un sistem de incarcare a unui spritesheet cu un numar variabil de cadre pe rand.
     */
    public void getPlayerImages() {
        // Sheet order: down=row0, left=row1, right=row2, up=row3 — maps directly to DIR_DOWN=0,LEFT=1,RIGHT=2,UP=3
        int[] framesPerRow = {7, 8, 8, 7};
        walkFrames = loadSheetVariable("/res/player/Player_walking-sheet", framesPerRow);
    }

    public void getPlayerAttackImages() {
        attackFrames = new BufferedImage[4][5];
        attackFrames[DIR_UP][0] = setup("/res/player/b.attack/attack 1/up/u1", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_UP][1] = setup("/res/player/b.attack/attack 1/up/u2", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_UP][2] = setup("/res/player/b.attack/attack 1/up/u3", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_UP][3] = setup("/res/player/b.attack/attack 1/up/u4", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_UP][4] = setup("/res/player/b.attack/attack 1/up/u5", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_DOWN][0] = setup("/res/player/b.attack/attack 1/front/f1", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_DOWN][1] = setup("/res/player/b.attack/attack 1/front/f2", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_DOWN][2] = setup("/res/player/b.attack/attack 1/front/f3", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_DOWN][3] = setup("/res/player/b.attack/attack 1/front/f4", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_DOWN][4] = setup("/res/player/b.attack/attack 1/front/f5", gp.tileSize, gp.tileSize * 2);
        attackFrames[DIR_LEFT][0] = setup("/res/player/b.attack/attack 1/left/l1", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_LEFT][1] = setup("/res/player/b.attack/attack 1/left/l2", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_LEFT][2] = setup("/res/player/b.attack/attack 1/left/l3", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_LEFT][3] = setup("/res/player/b.attack/attack 1/left/l4", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_LEFT][4] = setup("/res/player/b.attack/attack 1/left/l5", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_RIGHT][0] = setup("/res/player/b.attack/attack 1/right/r1", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_RIGHT][1] = setup("/res/player/b.attack/attack 1/right/r2", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_RIGHT][2] = setup("/res/player/b.attack/attack 1/right/r3", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_RIGHT][3] = setup("/res/player/b.attack/attack 1/right/r4", gp.tileSize * 2, gp.tileSize);
        attackFrames[DIR_RIGHT][4] = setup("/res/player/b.attack/attack 1/right/r5", gp.tileSize * 2, gp.tileSize);
        // Combo step 1 — attack2_sheet (4 rows × 5 cols, 1-tile each: down/left/right/up)
        int[] a2 = {5, 5, 5, 5};
        BufferedImage[][] raw2 = loadSheetVariable("/res/player/b.attack/attack2_sheet", a2);
        attackFrames2 = new BufferedImage[4][];
        attackFrames2[DIR_DOWN]  = raw2[0];
        attackFrames2[DIR_LEFT]  = raw2[1];
        attackFrames2[DIR_RIGHT] = raw2[2];
        attackFrames2[DIR_UP]    = raw2[3];
        // Combo step 2 — attack3_sheet (4 rows × 6 cols, 1-tile each: down/left/right/up)
        int[] a3 = {6, 6, 6, 6};
        BufferedImage[][] raw3 = loadSheetVariable("/res/player/b.attack/attack3_sheet", a3);
        attackFrames3 = new BufferedImage[4][];
        attackFrames3[DIR_DOWN]  = raw3[0];
        attackFrames3[DIR_LEFT]  = raw3[1];
        attackFrames3[DIR_RIGHT] = raw3[2];
        attackFrames3[DIR_UP]    = raw3[3];
    }

    public void getPlayerHitImages() {
        int[] framesPerRow = {4, 4, 4, 4}; // sheet rows: down, left, up, right
        BufferedImage[][] frames = loadSheetVariable("/res/player/Player_getHit", framesPerRow);
        hitFrames = new BufferedImage[4][];
        hitFrames[DIR_UP]    = frames[0]; // row 0 = up
        hitFrames[DIR_RIGHT] = frames[3]; // row 1 = right
        hitFrames[DIR_DOWN]  = frames[1]; // row 2 = down
        hitFrames[DIR_LEFT]  = frames[2]; // row 3 = left
    }

    public void getPlayerDeathImages() {
        int[] framesPerRow = {5, 5, 5, 5}; // sheet rows: up, down, left, right
        BufferedImage[][] frames = loadSheetVariable("/res/player/Player_death", framesPerRow);
        deathFrames = new BufferedImage[4][];
        deathFrames[DIR_DOWN]  = frames[0]; // row 0 = down
        deathFrames[DIR_RIGHT] = frames[2]; // row 2 = right
        deathFrames[DIR_UP]    = frames[1]; // row 1 = up
        deathFrames[DIR_LEFT]  = frames[3]; // row 3 = left
    }

    public void getPlayerIdleImages() {
        int[] framesPerRow = {6, 6, 6, 6}; // sheet rows: up, down, left, right
        BufferedImage[][] frames = loadSheetVariable("/res/player/Player_idle-sheet", framesPerRow);
        idleFrames = new BufferedImage[4][];
        idleFrames[DIR_UP]    = frames[0]; // row 0 = up
        idleFrames[DIR_DOWN]  = frames[1]; // row 1 = down
        idleFrames[DIR_LEFT]  = frames[2]; // row 2 = left
        idleFrames[DIR_RIGHT] = frames[3]; // row 3 = right
    }

    @Override
    public void update() {
        if (levelUpBannerTimer > 0) {
            levelUpBannerTimer--;
        }

        if (playerDying) {
            playerDeathCounter++;
            int frameIndex = playerDeathCounter / DEATH_ANIM_SPEED;
            if (frameIndex < DEATH_TOTAL_FRAMES) {
                playerDeathFrame = frameIndex;
            } else {
                playerDeathFrame = DEATH_TOTAL_FRAMES - 1; // hold last frame
                // After holding the last frame for DEATH_HOLD_DELAY, trigger game over
                int holdTick = playerDeathCounter - (DEATH_TOTAL_FRAMES * DEATH_ANIM_SPEED);
                if (holdTick >= DEATH_HOLD_DELAY) {
                    if (gp.gameState != GamePanel.gameOverState) {
                        gp.ui.commandNum = 0;
                    }
                    gp.gameState = GamePanel.gameOverState;
                    gp.stopMusic();
                    if (!gp.deathSoundPlayed) {
                        gp.playSE(4);
                        gp.deathSoundPlayed = true;
                    }
                }
            }
            return; // block all movement/combat during death
        }

        if (hitAnimCounter > 0) {
            hitAnimCounter--;
            hitAnimFrame = (HIT_TOTAL_FRAMES - 1) - (hitAnimCounter / HIT_ANIM_SPEED);
            if (hitAnimFrame >= HIT_TOTAL_FRAMES) hitAnimFrame = HIT_TOTAL_FRAMES - 1;
            if (hitAnimFrame < 0) hitAnimFrame = 0;
        }

        if (gp.gameState == GamePanel.dialogueState || gp.gameState == GamePanel.cutsceneState) {
            attacking = false;
            spriteCounter = 0;
            spriteNum = 1;
            anticipationTimer = -1;
            animOffsetX = 0;
            animOffsetY = 0;
            idleDelayCounter = 0;
            movingThisFrame = false;
        }

        if (dashUnlocked && keyH.dashPressed && dashCooldown == 0 && !dashing && !attacking && evadeRecovery == 0) {
            dashing = true;
            dashCounter = dashDuration;
            dashCooldown = getDashCooldownMax();
            invincible = true;
            evadeFlashTimer = 3;  // brief armor glint
            spawnDashBurst(true);
            gp.playSE(SFX.WEAPON_SWING); // quick whoosh
        }
        if (dashing) {
            float t = Math.max(0f, Math.min(1f, (dashDuration - dashCounter) / (float)Math.max(1, dashDuration)));
            float speedMultiplier = 3.2f * (1f - t * t);  // burst → decel
            speed = Math.max(defaultSpeed + 1, Math.round(defaultSpeed * speedMultiplier));
            invincible = true;
            invincibleCounter = 0;  // hold i-frames during evade
            dashCounter--;
            if (dashCounter % 3 == 0) {
                spawnDashTrail();
            }
            if (dashCounter <= 0) {
                dashing = false;
                speed = Math.max(1, defaultSpeed - 1); // brief heavy landing
                evadeRecovery = EVADE_RECOVERY_FRAMES;
                spawnDashBurst(false);
                invincibleCounter = 54;
                if (shadowStepUnlocked) {
                    for (int si = 0; si < gp.monster.length; si++) {
                        Entity m = gp.monster[si];
                        if (m != null && m.alive && !m.dying && !m.invincible) {
                            int sdx = Math.abs(getCenterX() - m.getCenterX());
                            int sdy = Math.abs(getCenterY() - m.getCenterY());
                            if (sdx < gp.tileSize * 2 && sdy < gp.tileSize * 2) {
                                int dmg = Math.max(1, (int)(attack * getTotalMeleeMultiplier()) - m.defense);
                                m.life -= dmg;
                                m.invincible = true;
                                m.hitFlashCounter = 6;
                                m.damageReaction();
                                spawnDamageNumber(m, dmg, false);
                                if (m.life <= 0) killMonster(m);
                                // Sync mob damage to other players in multiplayer
                                if (gp.mpClient != null && gp.mpClient.isConnected()) {
                                    gp.mpClient.sendMobDamage(si, dmg, m.life, m.maxLife, m.name);
                                }
                            }
                        }
                    }
                    gp.screenShake.shakeLight();
                }
            }
        }
        if (evadeRecovery > 0) {
            evadeRecovery--;
            if (evadeRecovery == 0) {
                speed = defaultSpeed;
            }
        }
        if (evadeFlashTimer > 0) evadeFlashTimer--;
        if (dashCooldown > 0) dashCooldown--;

        // (invincibility alpha handled in draw)

        if (shockwaveCooldown > 0) shockwaveCooldown--;
        if (voidSnareCooldown > 0) voidSnareCooldown--;
        if (frostNovaCooldown > 0) frostNovaCooldown--;
        if (overdriveCooldown > 0) overdriveCooldown--;
        if (undyingWillCooldown > 0) undyingWillCooldown--;

        if (slowedTimer > 0 && --slowedTimer == 0) slowed = false;
        if (rootedTimer > 0 && --rootedTimer == 0) rooted = false;

        if (overdriveTimer > 0) {
            overdriveTimer--;
            if (!dashing) {
                speed = defaultSpeed + 1;
            }
            if (overdriveTimer % 20 == 0) {
                Particle aura = gp.particlePool.get();
                aura.setWithPosition(this, this, COLOR_OVERDRIVE_AURA, 4, 2, 14, 0, -1, Particle.STYLE_SPARK);
                gp.particleList.add(aura);
            }
        } else if (!dashing) {
            speed = defaultSpeed;
        }

        handleAbilityInputs();

        if (comboWindow > 0) comboWindow--;
        if (comboWindow <= 0 && !attacking) comboStep = 0;

        if (attacking && !attackCanceled) {
            if (keyH.enterPressed) {
                attackBuffered = true;
                keyH.enterPressed = false;
            }
            attacking();
            int attackMoveSpeed = Math.max(1, speed / 2);
            int nextX = worldX;
            int nextY = worldY;
            switch(direction) {
                case DIR_UP:    if (keyH.upPressed)    nextY -= attackMoveSpeed; break;
                case DIR_DOWN:  if (keyH.downPressed)  nextY += attackMoveSpeed; break;
                case DIR_LEFT:  if (keyH.leftPressed)  nextX -= attackMoveSpeed; break;
                case DIR_RIGHT: if (keyH.rightPressed) nextX += attackMoveSpeed; break;
            }
            int originalX = worldX;
            int originalY = worldY;
            worldX = nextX;
            worldY = nextY;
            collisionOn = false;
            gp.cChecker.checkTile(this);
            int objIndex = gp.cChecker.checkObject(this, true);
            if (objIndex != 999) collisionOn = true;
            int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
            if (npcIndex != 999 && gp.npc[npcIndex].collision) collisionOn = true;
            int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
            if (monsterIndex != 999 && gp.monster[monsterIndex].collision) collisionOn = true;
            int iTileIndex = gp.cChecker.checkEntity(this, gp.iTile);
            if (iTileIndex != 999 && gp.iTile[iTileIndex] != null) {
                if (gp.iTile[iTileIndex].collision) collisionOn = true;
                if (gp.iTile[iTileIndex] instanceof tile.IT_Coins) {
                    tile.IT_Coins coinTile = (tile.IT_Coins) gp.iTile[iTileIndex];
                    coin += coinTile.coinValue;
                    gp.playSE(SFX.GOT_GEM);
                    generateParticle(coinTile, coinTile);
                    gp.iTile[iTileIndex] = null;
                }
            }
            if (collisionOn) {
                worldX = originalX;
                worldY = originalY;
            }
        } else {
            if (gp.inputLocked) return;
            boolean canMove = !rooted;
            boolean movingUp    = canMove && keyH.upPressed;
            boolean movingDown  = canMove && keyH.downPressed;
            boolean movingLeft  = canMove && keyH.leftPressed;
            boolean movingRight = canMove && keyH.rightPressed;
            boolean movingVertical = movingUp || movingDown;
            boolean movingHorizontal = movingLeft || movingRight;
            boolean diagonal = movingVertical && movingHorizontal;
            boolean moving = movingVertical || movingHorizontal;
            movingThisFrame = moving;
            
            // Set facing direction: vertical priority (up/down animation for diagonals)
            if (movingUp) direction = DIR_UP;
            else if (movingDown) direction = DIR_DOWN;
            else if (movingLeft) direction = DIR_LEFT;
            else if (movingRight) direction = DIR_RIGHT;

            if (moving || keyH.enterPressed) {
                idleCounter = 0;
                idleFrameDirection = 1;
                idleDelayCounter = 0;

                // Diagonal movement: use 1/sqrt(2) ≈ 0.7071 per axis
                // This ensures total diagonal distance = cardinal distance (standard in Zelda, Diablo, Stardew Valley)
                int moveSpeedX = speed;
                int moveSpeedY = speed;
                if (diagonal) {
                    // 70.71% per axis — total vector equals cardinal speed, balanced feel
                    moveSpeedX = Math.max(1, Math.round(speed * 0.7071f));
                    moveSpeedY = Math.max(1, Math.round(speed * 0.7071f));
                }
                // Slowed: halve effective movement speed (e.g. Canvas Moth dust debuff)
                if (slowed && !dashing) {
                    moveSpeedX = Math.max(1, moveSpeedX / 2);
                    moveSpeedY = Math.max(1, moveSpeedY / 2);
                }
                // Water terrain: wading slows movement to ~60% when inside a WaterZone rectangle
                if (!dashing && gp.eHandler.isInWaterZone(
                        worldX + solidArea.x, worldY + solidArea.y,
                        solidArea.width, solidArea.height)) {
                    moveSpeedX = Math.max(1, moveSpeedX * 3 / 5);
                    moveSpeedY = Math.max(1, moveSpeedY * 3 / 5);
                }

                if (movingHorizontal && !keyH.enterPressed) {
                    int savedDir = direction;
                    direction = movingLeft ? DIR_LEFT : DIR_RIGHT;
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

                if (movingVertical && !keyH.enterPressed) {
                    int savedDir = direction;
                    direction = movingUp ? DIR_UP : DIR_DOWN;
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

                if (movingUp) direction = DIR_UP;
                else if (movingDown) direction = DIR_DOWN;
                else if (movingLeft) direction = DIR_LEFT;
                else if (movingRight) direction = DIR_RIGHT;

                collisionOn = false;
                gp.cChecker.checkTile(this);
                int objIndex = gp.cChecker.checkObject(this, true);
                gp.nearbyInteractable = null; // reset each frame
                pickUpObject(objIndex);
                int npcIndex = gp.cChecker.checkEntity(this, gp.npc);
                interactNPC(npcIndex);
                int monsterIndex = gp.cChecker.checkEntity(this, gp.monster);
                contactMonster(monsterIndex);
                gp.cChecker.checkEntity(this, gp.iTile);
                gp.eHandler.checkEvent();

                if ((keyH.enterPressed || attackBuffered) && !attackCanceled && !dashing && currentWeapon != null) {
                    attacking = true;
                    spriteCounter = 0;
                    attackBuffered = false;
                    if (comboWindow > 0 && comboStep < 2) {
                        comboStep++;
                    } else if (comboWindow <= 0) {
                        comboStep = 0;
                    }
                }
                attackCanceled = false;
                attackBuffered = false;
                keyH.enterPressed = false;
                updateSprite();

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
                idleDelayCounter++;

                if (idleDelayCounter >= idleStartDelayFrames) {
                    updateIdleSprite();
                } else {
                    spriteNum = 1;
                }
            }
            if(gp.keyH.shotKeyPressed && !projectile.alive && shotAvailableCounter == 30 && projectile.haveResource(this)) {
                projectile.set(worldX, worldY, direction, true, this);
                projectile.subtractResource(this);
                gp.projectilesList.add(projectile);
                shotAvailableCounter = 0;
                gp.playSE(SFX.ARROW);
            }
            if (shotAvailableCounter < 30) {
                shotAvailableCounter++;
            }
            if (invincible) {
                invincibleCounter++;
                if (invincibleCounter > 60) {
                    invincible = false;
                    invincibleCounter = 0;
                }
            }
        }

        int mapPixelW = gp.tileM.currentMapCols * gp.tileSize;
        int mapPixelH = gp.tileM.currentMapRows * gp.tileSize;
        int defaultScreenX = gp.screenWidth / 2 - (gp.tileSize / 2);
        int defaultScreenY = gp.screenHeight / 2 - (gp.tileSize / 2);

        int targetScreenX, targetScreenY;
        if (mapPixelW <= gp.screenWidth) {
            targetScreenX = worldX + (gp.screenWidth - mapPixelW) / 2;
        } else {
            int offX = worldX - defaultScreenX;
            if (offX < 0)                              targetScreenX = worldX;
            else if (offX > mapPixelW - gp.screenWidth) targetScreenX = worldX - (mapPixelW - gp.screenWidth);
            else                                        targetScreenX = defaultScreenX;
        }
        if (mapPixelH <= gp.screenHeight) {
            targetScreenY = worldY + (gp.screenHeight - mapPixelH) / 2;
        } else {
            int offY = worldY - defaultScreenY;
            if (offY < 0)                               targetScreenY = worldY;
            else if (offY > mapPixelH - gp.screenHeight) targetScreenY = worldY - (mapPixelH - gp.screenHeight);
            else                                         targetScreenY = defaultScreenY;
        }

        if (camScreenX == 0f && camScreenY == 0f) {
            camScreenX = targetScreenX;
            camScreenY = targetScreenY;
        }
        final float CAM_DEADZONE = 24f;
        float dxCam = targetScreenX - camScreenX;
        float dyCam = targetScreenY - camScreenY;
        if (Math.abs(dxCam) > CAM_DEADZONE) {
            camScreenX += (dxCam - Math.signum(dxCam) * CAM_DEADZONE) * CAM_LERP;
        }
        if (Math.abs(dyCam) > CAM_DEADZONE) {
            camScreenY += (dyCam - Math.signum(dyCam) * CAM_DEADZONE) * CAM_LERP;
        }

        if (mapPixelW >= gp.screenWidth) {
            float minCamX = worldX - (mapPixelW - gp.screenWidth);
            float maxCamX = worldX;
            camScreenX = Math.max(minCamX, Math.min(maxCamX, camScreenX));
        }
        if (mapPixelH >= gp.screenHeight) {
            float minCamY = worldY - (mapPixelH - gp.screenHeight);
            float maxCamY = worldY;
            camScreenY = Math.max(minCamY, Math.min(maxCamY, camScreenY));
        }

        screenX = Math.round(camScreenX);
        screenY = Math.round(camScreenY);
    }

    private void updateSprite() {
        spriteCounter++;
        // Tie animation cadence to movement speed so feet never slide:
        // at defaultSpeed (5) interval = 40/5 = 8 ticks — same as before.
        // During a dash speed rises to ~16 so interval drops to ~2 (snappy).
        int interval = Math.max(1, 40 / Math.max(1, speed));
        if (spriteCounter > interval) {
            spriteNum = (spriteNum % 7) + 1; // loops 1–7
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
    }

    public void attacking() {
        // Variable per-frame hold durations for each combo step.
        // Format: {frame1, frame2, frame3, frame4, frame5}
        // Frames 2-3 (swing) are snappiest, frame 1 (windup) and 4-5 (recovery) linger.
        int[][] frameDurations = {
            {4, 2, 2, 4, 4},        // step 0: quick jab,       5 frames, 16 ticks
            {4, 3, 3, 4, 4},        // step 1: swift slash,     5 frames, 18 ticks
            {3, 2, 2, 3, 3, 3},     // step 2: heavy finisher,  6 frames, 16 ticks
        };
        int[] durations = frameDurations[Math.min(comboStep, 2)];

        // --- ANTICIPATION PHASE: brief hold on first frame for weight ---
        if (anticipationTimer < 0) {
            anticipationTimer = switch (comboStep) {
                case 0 -> 3;   // quick jab: snappy
                case 1 -> 2;   // swift slash: minimal
                default -> 4;  // heavy finisher: deliberate
            };
            spriteCounter = 0;
            trailCount = 0;
            trailIndex = 0;
            trailActive = false;
            animOffsetX = 0;
            animOffsetY = 0;
        }
        if (anticipationTimer > 0) {
            anticipationTimer--;
            spriteNum = 1;
            int leanPx = (comboStep == 2) ? 2 : 1;
            switch (direction) {
                case DIR_UP    -> animOffsetY = leanPx;
                case DIR_DOWN  -> animOffsetY = -leanPx;
                case DIR_LEFT  -> animOffsetX = leanPx;
                case DIR_RIGHT -> animOffsetX = -leanPx;
            }
            return;
        }

        spriteCounter++;

        int elapsed = 0;
        int maxFrames = durations.length;
        int currentFrame = maxFrames;
        for (int f = 0; f < maxFrames; f++) {
            elapsed += durations[f];
            if (spriteCounter <= elapsed) {
                currentFrame = f + 1;
                break;
            }
        }
        spriteNum = currentFrame;
        int totalDuration = 0;
        for (int d : durations) totalDuration += d;

        int maxLean = (comboStep == 2) ? 3 : 2;
        if (currentFrame <= 3) {
            float progress = Math.min(1f, currentFrame / 3f);
            int lean = Math.round(maxLean * progress);
            switch (direction) {
                case DIR_UP    -> { animOffsetX = 0; animOffsetY = -lean; }
                case DIR_DOWN  -> { animOffsetX = 0; animOffsetY = lean; }
                case DIR_LEFT  -> { animOffsetX = -lean; animOffsetY = 0; }
                case DIR_RIGHT -> { animOffsetX = lean; animOffsetY = 0; }
            }
        } else {
            float recovery = (currentFrame - 3) / 2f;
            int lean = Math.round(maxLean * (1f - recovery));
            switch (direction) {
                case DIR_UP    -> { animOffsetX = 0; animOffsetY = -lean; }
                case DIR_DOWN  -> { animOffsetX = 0; animOffsetY = lean; }
                case DIR_LEFT  -> { animOffsetX = -lean; animOffsetY = 0; }
                case DIR_RIGHT -> { animOffsetX = lean; animOffsetY = 0; }
            }
        }

        if (currentFrame >= 2 && currentFrame <= 4) {
            trailWorldX[trailIndex] = worldX;
            trailWorldY[trailIndex] = worldY;
            trailIndex = (trailIndex + 1) % TRAIL_SIZE;
            if (trailCount < TRAIL_SIZE) trailCount++;
        }

        int frame3Start = durations[0] + durations[1] + 1;
        if (currentFrame == 3 && spriteCounter == frame3Start) {
            performAttackHitbox();
        }
        if (spriteCounter >= totalDuration) {
            spriteCounter = 0;
            attacking = false;
            anticipationTimer = -1;
            trailActive = false;
            trailCount = 0;
            animOffsetX = 0;
            animOffsetY = 0;
            comboWindow = (comboStep == 2) ? 10 : COMBO_WINDOW_MAX;
        }
    }

    private void performAttackHitbox() {
        Entity attackHitbox = new Entity(gp);
        attackHitbox.worldX = worldX;
        attackHitbox.worldY = worldY;
        attackHitbox.solidArea = new Rectangle(solidArea);
        
        int ts = gp.tileSize;
        int quarter = ts / 4;  // 16px at 64px tile — scales with tile size
        int eighth  = ts / 8;  // 8px  at 64px tile — scales with tile size
        switch(direction) {
            case DIR_UP:
                attackHitbox.solidArea.width  = ts - quarter;  // 48 at 64px
                attackHitbox.solidArea.height = ts + quarter;  // 80 at 64px
                attackHitbox.worldX += eighth;                 // center horizontally
                attackHitbox.worldY -= ts + quarter;           // extend upward
                break;
            case DIR_DOWN:
                attackHitbox.solidArea.width  = ts - quarter;  // 48 at 64px
                attackHitbox.solidArea.height = ts + quarter;  // 80 at 64px
                attackHitbox.worldX += eighth;                 // center horizontally
                attackHitbox.worldY += ts;                     // extend downward
                break;
            case DIR_LEFT:
                attackHitbox.solidArea.width  = ts + quarter;  // 80 at 64px
                attackHitbox.solidArea.height = ts - quarter;  // 48 at 64px
                attackHitbox.worldX -= ts + quarter;           // extend leftward
                attackHitbox.worldY += eighth;                 // center vertically
                break;
            case DIR_RIGHT:
                attackHitbox.solidArea.width  = ts + quarter;  // 80 at 64px
                attackHitbox.solidArea.height = ts - quarter;  // 48 at 64px
                attackHitbox.worldX += ts;                     // extend rightward
                attackHitbox.worldY += eighth;                 // center vertically
                break;
        }
        int iTileIndex = gp.cChecker.checkEntity(attackHitbox, gp.iTile);
        damageInteractiveTile(iTileIndex);
        int monsterIndex = gp.cChecker.checkEntity(attackHitbox, gp.monster);
        damageMonster(monsterIndex, attack);
    }

    // Combat methods
    public void damageMonster(int i, int attack) {        if (i != 999) {
            if (!gp.monster[i].invincible) {
                gp.playSE(SFX.MONSTER_HIT);
                boolean isHeavy = (comboStep == 2);
                float comboMultiplier = switch (comboStep) {
                    case 1  -> 1.15f;
                    case 2  -> 1.6f;
                    default -> 1.0f;
                };
                int effectiveAttack = Math.max(1, (int)(attack * comboMultiplier * getTotalMeleeMultiplier()));
                int kb = (currentWeapon != null) ? currentWeapon.knockBackPower / 2 : 1;
                if (kb < 1) kb = 1;
                if (isHeavy) kb = (int)(kb * 2.0f);
                knockBack(gp.monster[i], kb, worldX, worldY);
                int damage = effectiveAttack - gp.monster[i].defense;
                if (damage < 0) damage = 0;
                if (gp.monster[i].frontalArmor) {
                    int md = gp.monster[i].direction;
                    boolean isFrontalHit =
                        (md == DIR_DOWN  && direction == DIR_UP)   ||
                        (md == DIR_UP    && direction == DIR_DOWN)  ||
                        (md == DIR_LEFT  && direction == DIR_RIGHT) ||
                        (md == DIR_RIGHT && direction == DIR_LEFT);
                    if (isFrontalHit) damage = Math.max(1, damage / 2);
                }
                gp.monster[i].life -= damage;
                generateParticle(this, gp.monster[i]);

                Color sparkColor = switch (comboStep) {
                    case 1  -> COLOR_COMBO_GOLDEN;  // golden
                    case 2  -> COLOR_COMBO_FIERY;  // fiery orange
                    default -> COLOR_COMBO_PALE; // pale yellow
                };
                generateComboSparks(gp.monster[i], sparkColor, comboStep);
                spawnDamageNumber(gp.monster[i], damage, isHeavy);
                gp.monster[i].hitFlashCounter = isHeavy ? 10 : 6;

                switch (comboStep) {
                    case 0 -> gp.screenShake.shakeLight();
                    case 1 -> gp.screenShake.shakeMedium();
                    case 2 -> gp.screenShake.shakeHeavy();
                }

                switch (comboStep) {
                    case 0 -> gp.triggerHitstop(2);
                    case 1 -> gp.triggerHitstop(3);
                    case 2 -> gp.triggerHitstop(5);
                }

                gp.monster[i].invincible = true;
                gp.monster[i].damageReaction();
                // Mana Siphon: restore 1 mana on melee hit
                if (manaSiphonUnlocked && mana < maxMana) {
                    mana++;
                }
                // Vampiric Strike: 15% chance to heal 1 HP on melee hit
                if (vampiricStrikeUnlocked && Math.random() < 0.15 && life < maxLife) {
                    life++;
                }
                if (gp.monster[i].life <= 0) {
                    killMonster(gp.monster[i]);
                }
                // Sync mob damage to other players in multiplayer
                if (gp.mpClient != null && gp.mpClient.isConnected()) {
                    gp.mpClient.sendMobDamage(i, damage, gp.monster[i].life, gp.monster[i].maxLife, gp.monster[i].name);
                }
            }
        }
    }

    /** Emit directional sparks with count/size scaling by combo step. */
    private void generateComboSparks(Entity target, Color color, int step) {
        int count = 4 + step * 2; // 4, 6, 8 sparks
        int baseSize = 3 + step;   // 3, 4, 5
        int baseX = 0, baseY = 0;
        switch (direction) {
            case DIR_UP    -> baseY = -1;
            case DIR_DOWN  -> baseY = 1;
            case DIR_LEFT  -> baseX = -1;
            case DIR_RIGHT -> baseX = 1;
        }
        for (int i = 0; i < count; i++) {
            Particle spark = gp.particlePool.get();
            int dx = baseX + (int)((Math.random() - 0.5) * 3);
            int dy = baseY + (int)((Math.random() - 0.5) * 3);
            if (dx == 0 && dy == 0) dx = 1;
            spark.setWithPosition(this, target, color, baseSize, 3, 12 + step * 2, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(spark);
        }
    }

    private void handleAbilityInputs() {
        if (keyH.shockwavePressed) {
            if (shockwaveUnlocked && shockwaveCooldown == 0 && mana >= 1 && !attacking && !dashing) {
                castShockwave();
            }
            keyH.shockwavePressed = false;
        }

        if (keyH.voidSnarePressed) {
            if (voidSnareUnlocked && voidSnareCooldown == 0 && mana >= 1 && !attacking && !dashing) {
                castVoidSnare();
            }
            keyH.voidSnarePressed = false;
        }

        if (keyH.frostNovaPressed) {
            if (frostNovaUnlocked && frostNovaCooldown == 0 && mana >= 2 && !attacking && !dashing) {
                castFrostNova();
            }
            keyH.frostNovaPressed = false;
        }

        if (keyH.overdrivePressed) {
            if (overdriveUnlocked && overdriveCooldown == 0 && mana >= 2 && !attacking) {
                castOverdrive();
            }
            keyH.overdrivePressed = false;
        }
    }

    private void castShockwave() {
        mana -= 1;
        shockwaveCooldown = getShockwaveCooldownMax();
        gp.playSE(SFX.LEVEL_UP);
        gp.screenShake.shakeLight();

        int radius = gp.tileSize * 2;
        for (int i = 0; i < gp.monster.length; i++) {
            Entity m = gp.monster[i];
            if (m == null || !m.alive || m.dying) continue;

            int dx = m.getCenterX() - getCenterX();
            int dy = m.getCenterY() - getCenterY();
            if (dx * dx + dy * dy > radius * radius) continue;

            if (!m.invincible) {
                int damage = Math.max(1, (int)(attack * 0.9f * getTotalMeleeMultiplier()) - m.defense);
                m.life -= damage;
                m.invincible = true;
                m.hitFlashCounter = 6;
                m.damageReaction();
                applyRadialKnockback(m, 3);
                generateParticle(this, m);
                generateSparks(m);
                spawnDamageNumber(m, damage, false);
                if (m.life <= 0) {
                    killMonster(m);
                }
                // Sync mob damage to other players in multiplayer
                if (gp.mpClient != null && gp.mpClient.isConnected()) {
                    gp.mpClient.sendMobDamage(i, damage, m.life, m.maxLife, m.name);
                }
            }
        }

        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2.0 * i) / 16.0;
            int dx = (int)Math.round(Math.cos(angle) * 2);
            int dy = (int)Math.round(Math.sin(angle) * 2);
            Particle p = gp.particlePool.get();
            p.setWithPosition(this, this, COLOR_SHOCKWAVE_SPARK, 6, 3, 20, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(p);
        }
    }

    private void castVoidSnare() {
        mana -= 1;
        voidSnareCooldown = getVoidSnareCooldownMax();
        gp.playSE(SFX.MENU_SELECT);

        int radius = gp.tileSize * 3;
        for (int i = 0; i < gp.monster.length; i++) {
            Entity m = gp.monster[i];
            if (m == null || !m.alive || m.dying) continue;

            int dx = getCenterX() - m.getCenterX();
            int dy = getCenterY() - m.getCenterY();
            int distSq = dx * dx + dy * dy;
            if (distSq > radius * radius) continue;

            double dist = Math.max(1.0, Math.sqrt(distSq));
            int pullX = (int)Math.round((dx / dist) * 22.0);
            int pullY = (int)Math.round((dy / dist) * 22.0);
            m.worldX += pullX;
            m.worldY += pullY;
            m.applyCrowdControl(30);

            Particle p = gp.particlePool.get();
            p.setWithPosition(this, m, COLOR_VOID_SNARE, 5, 2, 18, pullX == 0 ? 0 : pullX / Math.abs(pullX), pullY == 0 ? 0 : pullY / Math.abs(pullY), Particle.STYLE_SPARK);
            gp.particleList.add(p);
        }
    }

    private void castFrostNova() {
        mana -= 2;
        frostNovaCooldown = getFrostNovaCooldownMax();
        gp.playSE(SFX.MENU_SELECT);

        int radius = (int)(gp.tileSize * 2.4);
        for (int i = 0; i < gp.monster.length; i++) {
            Entity m = gp.monster[i];
            if (m == null || !m.alive || m.dying) continue;

            int dx = m.getCenterX() - getCenterX();
            int dy = m.getCenterY() - getCenterY();
            if (dx * dx + dy * dy > radius * radius) continue;

            m.applyCrowdControl(95);
            m.hitFlashCounter = 4;

            if (!m.invincible) {
                int damage = Math.max(1, (int)(attack * 0.45f) - m.defense / 2);
                m.life -= damage;
                m.invincible = true;
                spawnDamageNumber(m, damage, false);
                if (m.life <= 0) {
                    killMonster(m);
                }
                // Sync mob damage to other players in multiplayer
                if (gp.mpClient != null && gp.mpClient.isConnected()) {
                    gp.mpClient.sendMobDamage(i, damage, m.life, m.maxLife, m.name);
                }
            }

            Particle p = gp.particlePool.get();
            p.setWithPosition(this, m, COLOR_FROST_NOVA, 6, 2, 22, 0, -1, Particle.STYLE_HIT);
            gp.particleList.add(p);
        }
    }

    private void castOverdrive() {
        mana -= 2;
        overdriveCooldown = getOverdriveCooldownMax();
        overdriveTimer = OVERDRIVE_DURATION;
        gp.playSE(SFX.LEVEL_UP);
        gp.ui.addMessage("OVERDRIVE engaged!", COLOR_OVERDRIVE_MSG);
    }

    private void applyRadialKnockback(Entity target, int power) {
        int dx = target.getCenterX() - getCenterX();
        int dy = target.getCenterY() - getCenterY();
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));

        int vx = (int)Math.round((dx / dist) * power);
        int vy = (int)Math.round((dy / dist) * power);
        if (vx == 0 && vy == 0) vx = 1;

        target.knockBackVectorX = vx;
        target.knockBackVectorY = vy;
        target.knockBackRemaining = power * (gp.tileSize / 3.0);
        target.knockBackPower = power;
        target.knockBack = true;
    }

    private void killMonster(Entity monster) {
        if (monster == null || monster.dying || !monster.alive) return;
        int coinDrop = Math.max(1, monster.exp / 2);
        monster.beginDeath(monster.exp, 1, coinDrop);
        gp.screenShake.shakeMedium();
        // Soul Reaper: heal 1 HP on kill
        if (soulReaperUnlocked && life < maxLife) {
            life++;
        }
        // Find monster index and sync death to other players in multiplayer
        if (gp.mpClient != null && gp.mpClient.isConnected()) {
            for (int i = 0; i < gp.monster.length; i++) {
                if (gp.monster[i] == monster) {
                    gp.mpClient.sendMobDeath(i, monster.name);
                    break;
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
            case DIR_UP:    vy = -power; break;
            case DIR_DOWN:  vy = power;  break;
            case DIR_LEFT:  vx = -power; break;
            case DIR_RIGHT: vx = power;  break;
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
            gp.playSE(SFX.PLAYER_HIT);
            int damage = gp.monster[i].attack - defense;
            // Last Stand: +3 defense when below 20% HP
            if (lastStandUnlocked && life <= maxLife * 0.2f) {
                damage -= 3;
            }
            damage = (int)(damage * damageTakenMultiplier);
            // Mana Shield: spend 2 mana to block 2 damage
            if (manaShieldUnlocked && mana >= 2 && damage > 1) {
                mana -= 2;
                damage = Math.max(1, damage - 2);
            }
            // Only player bleeds when taking damage (removed generateParticle for monster)
            bleed(); // Generate blood particles on player
            if (damage < 1) {
                damage = 1;
            }
            life -= damage;
            invincible = true;
            // Thorns: reflect 2 damage to attacker
            if (thornsUnlocked && gp.monster[i] != null) {
                gp.monster[i].life -= 2;
                gp.monster[i].hitFlashCounter = 4;
                if (gp.monster[i].life <= 0) {
                    killMonster(gp.monster[i]);
                }
            }

            // HIT FLASH + SHAKE + HITSTOP when player takes damage
            hitFlashCounter = 6;
            hitAnimCounter = HIT_TOTAL_FRAMES * HIT_ANIM_SPEED; // start hit sprite animation
            hitAnimFrame = 0;
            hitAnimDirection = direction; // lock current facing for hit animation
            gp.screenShake.shakeMedium();
            gp.triggerHitstop(3);
            // compute knockback direction away from the monster
            int dx = worldX - gp.monster[i].worldX;
            int dy = worldY - gp.monster[i].worldY;
            if (Math.abs(dx) > Math.abs(dy)) {
                direction = (dx > 0) ? DIR_RIGHT : DIR_LEFT;
            } else {
                direction = (dy > 0) ? DIR_DOWN : DIR_UP;
            }
            // monster hit power is roughly its attack value (smaller)
            int kb = (gp.monster[i].attack + 1) / 2;
            if (kb < 1) kb = 1;
            // Last Stand: immune to knockback when below 20% HP
            if (lastStandUnlocked && life > 0 && life <= maxLife * 0.2f) {
                kb = 0;
            }
            if (kb > 0) knockBack(this, kb, gp.monster[i].worldX, gp.monster[i].worldY);
        }
    }

    /**
     * Generate extra blood particles when player takes damage
     */
    public void bleed() {
        Color bloodColorA = COLOR_BLOOD_A;
        Color bloodColorB = COLOR_BLOOD_B;
        
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

    /** Emit directional spark particles at the target hit point. */
    private void generateSparks(Entity target) {
        Color sparkColor = COLOR_COMBO_PALE;
        // Determine spark direction based on player facing
        int baseX = 0, baseY = 0;
        switch (direction) {
            case DIR_UP    -> baseY = -1;
            case DIR_DOWN  -> baseY = 1;
            case DIR_LEFT  -> baseX = -1;
            case DIR_RIGHT -> baseX = 1;
        }
        for (int i = 0; i < 6; i++) {
            Particle spark = gp.particlePool.get();
            int dx = baseX + (int)((Math.random() - 0.5) * 3);
            int dy = baseY + (int)((Math.random() - 0.5) * 3);
            if (dx == 0 && dy == 0) dx = 1;
            spark.setWithPosition(this, target, sparkColor, 4, 3, 14, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(spark);
        }
    }

    /** Spawn teleport particles — called from KeyHandler. */
    public void spawnTeleportParticles(boolean implode) {
        Color tpColor = COLOR_TELEPORT;
        int count = 10;
        int half = gp.tileSize / 2;
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / count) * i;
            int dx = (int) Math.round(Math.cos(angle));
            int dy = (int) Math.round(Math.sin(angle));
            if (implode) { dx = -dx; dy = -dy; }
            if (dx == 0 && dy == 0) dx = 1;

            Particle p = gp.particlePool.get();
            p.setWithPosition(this, this, tpColor, 5 + (int)(Math.random() * 3),
                implode ? 2 : 3, implode ? 12 : 18, dx, dy, Particle.STYLE_SPARK);
            // Offset from center
            p.fx = worldX + half + (float)((Math.random() - 0.5) * 16);
            p.fy = worldY + half + (float)((Math.random() - 0.5) * 16);
            p.worldX = (int) p.fx;
            p.worldY = (int) p.fy;
            gp.particleList.add(p);
        }
    }

    private void spawnDashTrail() {
        int dirX = 0;
        int dirY = 0;
        switch (direction) {
            case DIR_UP    -> dirY = -1;
            case DIR_DOWN  -> dirY = 1;
            case DIR_LEFT  -> dirX = -1;
            case DIR_RIGHT -> dirX = 1;
        }

        // Dirt wisps trailing behind (earth tones)
        for (int i = 0; i < 2; i++) {
            Particle trail = gp.particlePool.get();
            int sideX = (int)((Math.random() - 0.5) * 3.0);
            int sideY = (int)((Math.random() - 0.5) * 3.0);
            // Earthy brown
            Color dustColor = (i == 0) ? COLOR_DUST_LIGHT : COLOR_DUST_DARK;
            trail.setWithPosition(this, this, dustColor, 3 + i, 1, 10,
                    -dirX + sideX, -dirY + sideY, Particle.STYLE_DUST);
            gp.particleList.add(trail);
        }
    }

    private void spawnDashBurst(boolean start) {
        if (start) {
            // Ground dust cloud on evade start — earthy burst from feet
            Color dustColor = COLOR_DUST_WARM;  // warm stone
            for (int i = 0; i < 6; i++) {
                double angle = (Math.PI * 2.0 * i) / 6;
                int dx = (int)Math.round(Math.cos(angle));
                int dy = (int)Math.round(Math.sin(angle));
                Particle p = gp.particlePool.get();
                p.setWithPosition(this, this, dustColor, 5, 2, 12,
                        dx, dy, Particle.STYLE_DUST);
                gp.particleList.add(p);
            }
            // 2 larger central dust puffs rising up
            for (int i = 0; i < 2; i++) {
                Particle puff = gp.particlePool.get();
                int sx = (int)((Math.random() - 0.5) * 2);
                puff.setWithPosition(this, this, COLOR_DUST_PUFF, 6, 1, 16,
                        sx, -1, Particle.STYLE_DUST);
                gp.particleList.add(puff);
            }
        } else {
            // Landing dirt scatter — small stones/dust on arrival
            Color landColor = COLOR_DUST_LAND;  // darker earth
            for (int i = 0; i < 4; i++) {
                double angle = (Math.PI * 2.0 * i) / 4 + Math.random() * 0.5;
                int dx = (int)Math.round(Math.cos(angle));
                int dy = (int)Math.round(Math.sin(angle));
                Particle p = gp.particlePool.get();
                p.setWithPosition(this, this, landColor, 3, 1, 8,
                        dx, dy, Particle.STYLE_DUST);
                gp.particleList.add(p);
            }
        }
    }

    /** Spawn a floating damage number above the target. */
    private void spawnDamageNumber(Entity target, int damage, boolean critical) {
        DamageNumber dn = gp.damageNumberPool.get();
        int x = target.worldX + gp.tileSize / 4;
        int y = target.worldY - 8;
        Color color = damage > 0 ? COLOR_DMG_RED : COLOR_DMG_GRAY;
        dn.set(x, y, String.valueOf(damage), color, critical);
        gp.damageNumbers.add(dn);
    }

    // Interaction methods
    public void pickUpObject(int i) {
        if (i != 999) {
            // Non-interactive technical entities (light sources, markers) are never pickable
            if (gp.obj[i].lightSource) return;

            // PICKUP ONLY OBJECTS
            if (gp.obj[i].type == TYPE_PICKUP_ONLY) {
                attackCanceled = true;
                if (gp.obj[i].use(this)) {
                    gp.obj[i] = null;
                } else return;
            }
            // INTERACTABLE OBJECTS (chests, doors, etc.)
            else if (gp.obj[i].type == TYPE_OBSTACLE) {
                gp.nearbyInteractable = gp.obj[i]; // track for UI prompt
                if(keyH.enterPressed) {
                    attackCanceled = true;
                    gp.obj[i].interact();
                    keyH.enterPressed = false; // consume input so attack doesn't also trigger
                }
            }
            // OBTAINABLE OBJECTS
            else if (canObtainItem(gp.obj[i])) {
                attackCanceled = true;
                gp.playSE(SFX.EQUIP);
                Entity pickedObject = gp.obj[i];
                String objectName = pickedObject.name;
                int pickedAmount = Math.max(1, pickedObject.amount);
                switch (objectName) {
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
                        gp.playSE(SFX.EQUIP);
                        hasKey++;
                        gp.obj[i] = null;
                        gp.ui.addMessage("You got a key!", Color.WHITE);

                    }
                    case "Tent" -> {
                        gp.playSE(SFX.EQUIP);
                        gp.ui.addMessage("You got a tent!", Color.WHITE);
                        break;
                    }
                    case "Spell book" -> {
                        gp.playSE(SFX.EQUIP);
                        gp.obj[i] = null;
                        gp.ui.addMessage("You got a new weapon!", Color.WHITE);
                        break;
                    }
                    default -> {
                        if (pickedAmount > 1) {
                            gp.ui.addMessage("You got " + pickedAmount + " " + objectName + "!", Color.WHITE);
                        } else {
                            gp.ui.addMessage("You got " + objectName + "!", Color.WHITE);
                        }
                        break;
                    }
                }
                if (pickedObject.removeOnPickup && gp.obj[i] == pickedObject) {
                    gp.obj[i] = null;
                }
            }
        }
    }

    public void interactNPC(int i) {
        if (gp.keyH.enterPressed) {
            if (i != 999) {
                attackCanceled = true;
                gp.npc[i].speak();
            } else {
                int rangedIdx = findRangedInteractNPC();
                if (rangedIdx != 999) {
                    attackCanceled = true;
                    gp.npc[rangedIdx].speak();
                } else if (!attackCanceled && currentWeapon != null) {
                    gp.playSE(SFX.WEAPON_SWING);
                    attacking = true;
                }
            }
        }
    }

    /** Returns the index of the nearest NPC with interactRange > 0 that is within
     *  range and roughly in the player's facing direction, or 999 if none found. */
    private int findRangedInteractNPC() {
        int playerCX = getCenterX();
        int playerCY = getCenterY();
        int bestIdx  = 999;
        int bestDistSq = Integer.MAX_VALUE;
        for (int k = 0; k < gp.npc.length; k++) {
            Entity n = gp.npc[k];
            if (n == null || n.interactRange <= 0) continue;
            int dx = n.getCenterX() - playerCX;
            int dy = n.getCenterY() - playerCY;
            int distSq = dx * dx + dy * dy;
            int range  = n.interactRange;
            if (distSq > range * range) continue;
            // Must be in the forward hemisphere of the player's facing direction
            int dot;
            switch (direction) {
                case DIR_DOWN  -> dot =  dy;
                case DIR_UP    -> dot = -dy;
                case DIR_LEFT  -> dot = -dx;
                case DIR_RIGHT -> dot =  dx;
                default        -> dot = 0;
            }
            if (dot <= 0) continue;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestIdx = k;
            }
        }
        return bestIdx;
    }

    public void damageInteractiveTile(int i) {
        if (i != 999 && gp.iTile[i].destructible && gp.iTile[i].isCorrectItem(this) && !gp.iTile[i].invincible) {
            Entity tile = gp.iTile[i];
            gp.iTile[i] = null;
            generateParticle(tile, tile);
            gp.screenShake.shakeLight();

            // Random drop from destructible pots
            if (tile instanceof tile.IT_Pot) {
                double roll = Math.random();
                if (roll < 0.3) {
                    // 30% chance: drop a heart at pot location
                    for (int j = 0; j < gp.obj.length; j++) {
                        if (gp.obj[j] == null) {
                            gp.obj[j] = new object.OBJ_Heart(gp);
                            gp.obj[j].worldX = tile.worldX;
                            gp.obj[j].worldY = tile.worldY;
                            break;
                        }
                    }
                } else if (roll < 0.6) {
                    // 30% chance: drop coins
                    for (int j = 0; j < gp.obj.length; j++) {
                        if (gp.obj[j] == null) {
                            gp.obj[j] = new object.OBJ_Coins(gp);
                            gp.obj[j].worldX = tile.worldX;
                            gp.obj[j].worldY = tile.worldY;
                            break;
                        }
                    }
                }
                // 40% chance: drop nothing
            }
        }
    }

    // Inventory methods
    public void selectItem() {
        int itemIndex = gp.ui.getItemIndexOnSlot();
        if (itemIndex < inventory.size()) {
            Entity selectedItem = inventory.get(itemIndex);

            if (selectedItem.type == TYPE_SWORD || selectedItem.type == TYPE_BOOK) {
                currentWeapon = selectedItem;
                attack = getAttack();
                gp.ui.addMessage("Equipped " + selectedItem.name + "!", Color.WHITE);
                gp.playSE(SFX.MONSTER_HIT); // equip sound
            } else if (selectedItem.type == TYPE_SHIELD) {
                currentShield = selectedItem;
                defense = getDefense();
                gp.ui.addMessage("Equipped " + selectedItem.name + "!", Color.WHITE);
                gp.playSE(SFX.MONSTER_HIT);
            } else if (selectedItem.type == TYPE_CONSUMABLE) {
                attackCanceled = true;
                if (selectedItem.use(this) && !"Potion".equals(selectedItem.name)) {
                    gp.ui.addMessage("Used " + selectedItem.name + ".", Color.WHITE);
                    gp.playSE(SFX.LEVEL_UP); // generic use sound
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
            Entity item = inventory.get(i);
            if (item.name.equalsIgnoreCase(itemName)
                    || (item.itemId != null && item.itemId.equalsIgnoreCase(itemName))) {
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
        int pickupAmount = Math.max(1, item.amount);
        // CHECK IF ITEM IS STACKABLE
        if (item.stackable) {
            int index = searchItemInInventory(item.name);
            if (index != 999) {
                inventory.get(index).amount += pickupAmount;
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
            gp.playSE(SFX.ARROW); // use a drop sound or simple click
        }
    }

    // Rendering methods
    @Override
    public void draw(Graphics2D g2) {
        // ── DEATH ANIMATION ──
        if (playerDying) {
            int drawX = screenX;
            int drawY = screenY;
            BufferedImage deathImg = null;
            if (deathFrames != null && deathDirection >= 0 && deathDirection < deathFrames.length
                    && deathFrames[deathDirection] != null && playerDeathFrame < deathFrames[deathDirection].length) {
                deathImg = deathFrames[deathDirection][playerDeathFrame];
            }
            if (deathImg != null) {
                // Fade out during the hold phase (after all frames played)
                int holdTick = playerDeathCounter - (DEATH_TOTAL_FRAMES * DEATH_ANIM_SPEED);
                if (holdTick > 0) {
                    float fadeAlpha = Math.max(0.15f, 1f - (holdTick / (float) DEATH_HOLD_DELAY));
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeAlpha));
                }
                g2.drawImage(deathImg, drawX, drawY, gp.tileSize, gp.tileSize, null);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
            }
            return;
        }

        BufferedImage image;
        int tempScreenX = screenX;
        int tempScreenY = screenY;
        int frame = Math.max(1, spriteNum);

        int drawW = (int)(gp.tileSize * spriteScale);
        int drawH = (int)(gp.tileSize * spriteScale);

        if (attacking) {
            image = getAttackFrame(direction, frame);
            // Attack1 sprites are 2-tile — set doubled draw size and offset (attack2/3 are 1-tile)
            if (comboStep == 0) {
                switch(direction) {
                    case DIR_UP -> {
                        drawW = (int)(gp.tileSize * spriteScale);
                        drawH = (int)(gp.tileSize * 2 * spriteScale);
                        tempScreenY -= gp.tileSize * spriteScale;
                    }
                    case DIR_DOWN -> {
                        drawW = (int)(gp.tileSize * spriteScale);
                        drawH = (int)(gp.tileSize * 2 * spriteScale);
                    }
                    case DIR_LEFT -> {
                        drawW = (int)(gp.tileSize * 2 * spriteScale);
                        drawH = (int)(gp.tileSize * spriteScale);
                        tempScreenX -= gp.tileSize * spriteScale;
                    }
                    case DIR_RIGHT -> {
                        drawW = (int)(gp.tileSize * 2 * spriteScale);
                        drawH = (int)(gp.tileSize * spriteScale);
                    }
                }
            }

            // --- SWING TRAIL: draw warm-tinted afterimages behind the active swing ---
            if (trailActive && trailCount > 0 && image != null) {
                java.awt.Composite savedTrail = g2.getComposite();
                float[] trailAlphas = {0.22f, 0.14f, 0.08f, 0.04f};
                int playerWX = gp.player.worldX;
                int playerWY = gp.player.worldY;
                int playerSX = gp.player.screenX;
                int playerSY = gp.player.screenY;
                for (int ti = 0; ti < trailCount; ti++) {
                    // Read from ring buffer: newest = (trailIndex - 1), oldest first
                    int idx = (trailIndex - trailCount + ti + TRAIL_SIZE) % TRAIL_SIZE;
                    int tSX = trailWorldX[idx] - playerWX + playerSX;
                    int tSY = trailWorldY[idx] - playerWY + playerSY;
                    // Apply same attack offset as main sprite (only for attack1's 2-tile sprites)
                    if (comboStep == 0) {
                        switch (direction) {
                            case DIR_UP   -> tSY -= gp.tileSize;
                            case DIR_LEFT -> tSX -= gp.tileSize;
                        }
                    }
                    float alpha = (ti < trailAlphas.length) ? trailAlphas[ti] : 0.03f;
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    g2.drawImage(image, tSX, tSY, drawW, drawH, null);
                }
                g2.setComposite(savedTrail);
            }
        } else if (hitAnimCounter > 0 && hitFrames != null && hitAnimDirection >= 0 && hitAnimDirection < hitFrames.length
                   && hitFrames[hitAnimDirection] != null && hitAnimFrame < hitFrames[hitAnimDirection].length) {
            image = hitFrames[hitAnimDirection][hitAnimFrame];
        } else if (!movingThisFrame) {
            image = getIdleFrame(direction, frame);
        } else {
            image = getWalkFrameImage(direction, frame);
        }

        // Fallback to first walk frame if null
        if (image == null) {
            image = getWalkFrameImage(direction, 1);
            drawW = gp.tileSize;
            drawH = gp.tileSize;
        }

        int drawX = tempScreenX + animOffsetX;
        int drawY = tempScreenY + animOffsetY;

        if (dashing) {
            // Subtle directional lean — character leans into the evade
            float t = (dashDuration - dashCounter) / (float)Math.max(1, dashDuration);
            float lean = (float)Math.sin(t * Math.PI) * 0.06f; // subtle
            if (direction == DIR_LEFT || direction == DIR_RIGHT) {
                drawW = (int)(gp.tileSize * (1.0f + lean));
                drawH = (int)(gp.tileSize * (1.0f - lean * 0.4f));
            } else {
                drawW = (int)(gp.tileSize * (1.0f - lean * 0.4f));
                drawH = (int)(gp.tileSize * (1.0f + lean));
            }
            drawX = tempScreenX - (drawW - gp.tileSize) / 2;
            drawY = tempScreenY - (drawH - gp.tileSize) / 2;

            // Tight motion-blur afterimages (3 closely spaced)
            int dirX = 0, dirY = 0;
            switch (direction) {
                case DIR_UP    -> dirY = -1;
                case DIR_DOWN  -> dirY = 1;
                case DIR_LEFT  -> dirX = -1;
                case DIR_RIGHT -> dirX = 1;
            }
            java.awt.Composite saved = g2.getComposite();
            for (int i = 0; i < 3; i++) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DASH_ALPHAS[i]));
                g2.drawImage(image,
                    drawX - dirX * DASH_OFFSETS[i], drawY - dirY * DASH_OFFSETS[i],
                    drawW, drawH, null);
            }
            g2.setComposite(saved);
        }

        // Armor-glint flash on evade start
        if (evadeFlashTimer > 0) {
            float flashAlpha = evadeFlashTimer / 3f * 0.35f;
            java.awt.Composite saved = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, flashAlpha));
            g2.setColor(COLOR_EVADE_FLASH);
            g2.fillRect(drawX + 8, drawY + 8, drawW - 16, drawH - 16);
            g2.setComposite(saved);
        }

        if (invincible) {
            // Simple flicker during i-frames — no lingering gray
            float alpha = (invincibleCounter % 4 < 2) ? 0.5f : 0.85f;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        }
        g2.drawImage(image, drawX, drawY, drawW, drawH, null);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }

    private BufferedImage getIdleFrame(int dir, int frame) {
        if (idleFrames != null && dir >= 0 && dir < idleFrames.length && idleFrames[dir] != null) {
            int idx = frame - 1;
            if (idx >= 0 && idx < idleFrames[dir].length) return idleFrames[dir][idx];
            return idleFrames[dir][0]; // fallback first frame
        }
        return null;
    }

    private BufferedImage getAttackFrame(int dir, int frame) {
        BufferedImage[][] frames = switch (comboStep) {
            case 1  -> attackFrames2 != null ? attackFrames2 : attackFrames;
            case 2  -> attackFrames3 != null ? attackFrames3 : attackFrames;
            default -> attackFrames;
        };
        if (frames != null && dir >= 0 && dir < frames.length && frames[dir] != null) {
            int idx = frame - 1;
            if (idx >= 0 && idx < frames[dir].length) return frames[dir][idx];
            return frames[dir][0];
        }
        return null;
    }

    // getWalkFrameImage is inherited from Entity (uses walkFrames array with legacy fallback)

    // Utility methods
    public int getAttack() {
        if (currentWeapon != null) {
            attackArea = currentWeapon.attackArea;
            return attack = strenght * currentWeapon.attackValue;
        }
        attackArea.width = 20;
        attackArea.height = 20;
        return attack = strenght;
    }

    public int getDefense() {
        if (currentShield != null) {
            return defense = dexterity * currentShield.defenseValue;
        }
        return defense = 0;
    }

    public void checkLevelUp() {
        while (exp >= nextLevelExp) {
            exp -= nextLevelExp;  // subtract — XP bar resets each level
            level++;
            nextLevelExp = 4 + level * 3;  // linear growth: 7, 10, 13, 16, 19...

            // +1 skill point per level
            skillPoints++;
            // Heal a bit on level up
            life = Math.min(life + 2, maxLife);

            triggerLevelUpEffects();
            generateLevelUpChoices();
            gp.gameState = GamePanel.levelUpState;
        }
    }

    /** Build 3 random stat upgrade options for the level-up choice screen. */
    private void generateLevelUpChoices() {
        // Pool of possible stat upgrades
        String[][] pool = {
            {"maxLife",   "+1 Max HP",       "1"},
            {"strenght",  "+1 Strength",      "1"},
            {"dexterity", "+1 Dexterity",     "1"},
            {"speed",     "+1 Speed",         "1"},
            {"maxMana",   "+1 Max Mana",      "1"},
        };

        // Shuffle and pick 3 distinct options
        java.util.List<String[]> options = new java.util.ArrayList<>(java.util.Arrays.asList(pool));
        java.util.Collections.shuffle(options);

        levelUpOptions = new String[3];
        levelUpValues = new int[3];
        levelUpStatKeys = new String[3];

        for (int i = 0; i < 3; i++) {
            levelUpStatKeys[i] = options.get(i)[0];
            levelUpOptions[i] = options.get(i)[1];
            levelUpValues[i] = Integer.parseInt(options.get(i)[2]);
        }
        levelUpChoice = 0;
    }

    private void triggerLevelUpEffects() {
        gp.playSE(SFX.LEVEL_UP);
        spawnLevelUpBoom();
        gp.screenShake.shakeMedium();

        levelUpBannerText = getLevelUpFlavorText(level);
        levelUpBannerTimer = 180;
        gp.ui.addMessage("Level " + level + "! +1 Skill Point  Choose a stat!", COLOR_LEVELUP_MSG);
    }

    private String getLevelUpFlavorText(int newLevel) {
        if (newLevel >= 20) return "LEGEND AWAKENS";
        if (newLevel >= 15) return "ARCANE BLOOD SURGES";
        if (newLevel >= 10) return "YOUR POWER SPIKES";
        if (newLevel >= 5) return "THE HUNT GETS SERIOUS";
        if (newLevel == 2) return "FIRST BREAKTHROUGH";
        return "LEVEL UP";
    }

    private void spawnLevelUpBoom() {
        // Main radial burst
        for (int i = 0; i < 20; i++) {
            double a = (Math.PI * 2.0 * i) / 20.0;
            int dx = (int)Math.round(Math.cos(a) * 3);
            int dy = (int)Math.round(Math.sin(a) * 3);
            Particle p = gp.particlePool.get();
            p.setWithPosition(this, this, COLOR_COMBO_PALE, 8, 2, 28, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(p);
        }

        // Secondary shock ring
        for (int i = 0; i < 12; i++) {
            double a = (Math.PI * 2.0 * i) / 12.0;
            int dx = (int)Math.round(Math.cos(a) * 2);
            int dy = (int)Math.round(Math.sin(a) * 2);
            Particle p = gp.particlePool.get();
            p.setWithPosition(this, this, COLOR_LEVELUP_RING, 6, 2, 20, dx, dy, Particle.STYLE_HIT);
            gp.particleList.add(p);
        }
    }

    public int getDashCooldownMax() {
        int cd = dashCooldownMax - dashCooldownBonus;
        return Math.max(18, cd);
    }

    public int getTeleportCooldownMax() {
        int cd = main.KeyHandler.TELEPORT_COOLDOWN_MAX - teleportCooldownBonus;
        return Math.max(25, cd);
    }

    public int getShockwaveCooldownMax() {
        return SHOCKWAVE_COOLDOWN_MAX;
    }

    public int getVoidSnareCooldownMax() {
        return VOID_SNARE_COOLDOWN_MAX;
    }

    public int getFrostNovaCooldownMax() {
        return FROST_NOVA_COOLDOWN_MAX;
    }

    public int getOverdriveCooldownMax() {
        return OVERDRIVE_COOLDOWN_MAX;
    }

    public int getShockwaveCooldown() {
        return shockwaveCooldown;
    }

    public int getVoidSnareCooldown() {
        return voidSnareCooldown;
    }

    public int getFrostNovaCooldown() {
        return frostNovaCooldown;
    }

    public int getOverdriveCooldown() {
        return overdriveCooldown;
    }

    public int getOverdriveTimer() {
        return overdriveTimer;
    }

    private float getTotalMeleeMultiplier() {
        float total = meleeDamageMultiplier;
        if (overdriveTimer > 0) {
            total *= 1.35f;
        }
        if (berserkerFuryUnlocked && life <= maxLife * 0.3f) {
            total *= 1.5f;
        }
        return total;
    }

    public void applySkillNodeEffect(String nodeId) {
        switch (nodeId) {
            case "VITALITY_CORE" -> {
                maxLife += 2;
                life = maxLife;
            }
            case "BLADE_MASTERY" -> meleeDamageMultiplier += 0.15f;
            case "AETHER_RESERVE" -> {
                maxMana += 2;
                mana = maxMana;
            }
            case "WINDSTEP" -> {
                dashUnlocked = true;
                dashCooldownBonus += 12;
            }
            case "PHASE_TUNING" -> teleportCooldownBonus += 25;
            case "IRON_WILL" -> damageTakenMultiplier *= 0.85f;
            case "SHOCKWAVE" -> shockwaveUnlocked = true;
            case "VOID_SNARE" -> voidSnareUnlocked = true;
            case "FROST_NOVA" -> frostNovaUnlocked = true;
            case "OVERDRIVE" -> overdriveUnlocked = true;
            case "QUICK_RECOVERY" -> {
                dashCooldownBonus += dashCooldownMax / 2;
                defaultSpeed += 1;
                speed = defaultSpeed;
            }
            case "ARCANE_MASTERY" -> {
                maxMana += 3;
                mana = maxMana;
            }
            case "SOUL_REAPER" -> soulReaperUnlocked = true;
            case "BERSERKER_FURY" -> berserkerFuryUnlocked = true;
            case "SHADOW_STEP" -> shadowStepUnlocked = true;
            case "MOMENTUM" -> {
                defaultSpeed += 1;
                speed = defaultSpeed;
                dashCooldownBonus += 10;
            }
            case "MANA_SIPHON" -> manaSiphonUnlocked = true;
            case "MANA_SHIELD" -> manaShieldUnlocked = true;
            case "THICK_SKIN" -> defense += 1;
            case "THORNS" -> thornsUnlocked = true;
            case "SECOND_WIND" -> {
                secondWindUnlocked = true;
                secondWindAvailable = true;
            }
            case "VAMPIRIC_STRIKE" -> vampiricStrikeUnlocked = true;
            case "LAST_STAND" -> lastStandUnlocked = true;
            case "UNDYING_WILL" -> undyingWillUnlocked = true;
            default -> {
                return;
            }
        }

        gp.ui.addMessage("Unlocked skill: " + nodeId.replace('_', ' '), new Color(120, 210, 255));
        gp.playSE(SFX.MENU_SELECT);
    }

    // Stored stat keys for level-up application
    private String[] levelUpStatKeys;

    /** Apply the selected level-up stat boost. Called from KeyHandler. */
    public void applyLevelUpChoice() {
        if (levelUpStatKeys == null) return;
        String key = levelUpStatKeys[levelUpChoice];
        int val = levelUpValues[levelUpChoice];
        switch (key) {
            case "maxLife" -> { maxLife += val; life = maxLife; }
            case "strenght" -> { strenght += val; attack = getAttack(); }
            case "dexterity" -> { dexterity += val; defense = getDefense(); }
            case "speed" -> { defaultSpeed += val; speed = defaultSpeed; }
            case "maxMana" -> { maxMana += val; mana = maxMana; }
        }
        // Particle burst on stat gain
        for (int i = 0; i < 8; i++) {
            Particle p = gp.particlePool.get();
            int dx = (int)(Math.cos(i * Math.PI / 4) * 2);
            int dy = (int)(Math.sin(i * Math.PI / 4) * 2);
            p.setWithPosition(this, this, new Color(255, 220, 80), 6, 2, 20, dx, dy, Particle.STYLE_SPARK);
            gp.particleList.add(p);
        }
        gp.screenShake.shakeLight();
        gp.gameState = GamePanel.playState;
    }

    // Particle methods
    @Override
    public Color getParticleColor() {
        if (dashParticle) {
            return new Color(170, 145, 110); // earthy dust
        }
        return new Color(210, 32, 45);
    }

    @Override
    public int getParticleSize() {
        if (dashParticle) {
            return 5;
        }
        return 10;
    }

    @Override
    public int getParticleSpeed() {
        if (dashParticle) {
            return 1;
        }
        return 2;
    }

    @Override
    public int getParticleMaxLife() {
        if (dashParticle) {
            return 10;
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

    // Dash particles (medieval earth tones)
    public Color getParticleColorDash() {
        return new Color(170, 145, 110);
    }

    public int getParticleSizeDash() {
        return 5;
    }

    public int getParticleSpeedDash() {
        return 1;
    }

    public int getParticleMaxLifeDash() {
        return 10;
    }

}
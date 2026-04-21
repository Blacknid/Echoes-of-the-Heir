package entity;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;

import audio.SFX;
import main.GamePanel;
import util.ResourceCache;

public class Entity {

    protected GamePanel gp;

    // PER-ENTITY PATH CACHE — recalculate only when the goal tile changes
    private final java.util.ArrayList<int[]> cachedWaypoints = new java.util.ArrayList<>();
    private int waypointIdx      = 0;
    private int pathCacheGoalCol = -1;
    private int pathCacheGoalRow = -1;
    private int pathStallCounter = 0;  // frames without reaching the next waypoint
    private static final int PATH_STALL_LIMIT = 10; // force repath after this many stalled frames

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
    public BufferedImage[][] attackFrames;  // attack animation per direction (combo step 0)
    public BufferedImage[][] attackFrames2; // combo step 1, 1-tile sprites
    public BufferedImage[][] attackFrames3; // combo step 2, 1-tile sprites
    
    // (attack legacy sprite fields removed — use attackFrames[][] array instead)

    // ── ACTIVITY ANIMATION SYSTEM ──
    // Named animation sets beyond walk/idle (e.g. "forge", "sweep", "sleep").
    // Loaded from JSON (NPCFactory) or Tiled properties via MapObjectLoader.
    public java.util.HashMap<String, BufferedImage[][]> activityAnimations;   // key → [dir][frame]
    public java.util.HashMap<String, Integer> activityAnimSpeeds;             // key → ticks between frames
    public String  currentActivity = null;       // active animation key (null = use default walk/idle)
    public int     activitySpriteNum = 1;        // current frame (1-based, bouncing)
    public int     activitySpriteCounter = 0;    // tick counter for frame advance
    private int    activityFrameDirection = 1;   // +1 or -1 (bounce direction)

    public int direction = DIR_DOWN;

    // IDLE ANIMATION
    public int idleDirection = -1;          // -1 = use current direction; >= 0 = forced idle dir
    public int idleSpriteNum = 1;
    public int idleSpriteCounter = 0;
    private int idleFrameDirection = 1;
    public int idleAnimationInterval = 24;  // ticks between idle frame advances (~0.4 s at 60 UPS)
    protected boolean entityIdle = true;

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

    // ── STATUS EFFECTS ──
    public boolean slowed = false;              // movement speed halved (e.g. Canvas Moth dust)
    public int     slowedTimer = 0;             // frames remaining
    public boolean rooted = false;              // cannot move (e.g. Hollow Stump grab)
    public int     rootedTimer = 0;             // frames remaining
    public boolean phasing = false;             // toggles invulnerability on a cycle (Portrait Ghost, Drowned Sketch)
    public int     phasingCycleCounter  = 0;    // current position within the cycle
    public int     phasingCycleDuration = 180;  // frames per full cycle (half vulnerable, half invulnerable)

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
    private java.awt.Graphics2D hitFlashG2; // OPTIMIZATION: cached Graphics2D for hit flash overlay



    // TILE PARTICLES: throttle counter for footstep particle emission
    public int footstepParticleCounter = 0;


    
    // DIALOGUE (lazy-initialized to avoid wasting memory on entities that don't talk)
    public String dialogues[][];
    public int dialogueIndex = 0;
    public int dialogueSet = 0;
    public java.util.HashMap<String, Integer> dialogueNameMap = null; // named dialogue key → set index


    
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
    public boolean frontalArmor = false;       // blocks 50% of frontal hits (Painted Guard, Painted Crab)
    public int     rootOnContactDuration = 0; // roots the player on contact for N frames (Hollow Stump)
    public Entity loot;
    public boolean opened = false;



    // TYPE CONSTANTS
    public int type;
    public static final int TYPE_PLAYER = 0;
    public static final int TYPE_NPC = 1;
    public static final int TYPE_MONSTER = 2;
    public static final int TYPE_SWORD = 3;
    public static final int TYPE_BOOK = 4;
    public static final int TYPE_SHIELD = 5;
    public static final int TYPE_CONSUMABLE = 6;
    public static final int TYPE_PICKUP_ONLY = 7;
    public static final int TYPE_OBSTACLE = 8;
    public static final int TYPE_BUFFS = 9;
    public static final int TYPE_ENDING = 10;
    public static final int TYPE_UTILITY = 11;



    // CHARACTER ATTRIBUTES
    public String name;
    public int defaultSpeed = 1;
    public int speed;
    public int maxLife; //maximul teoretic de viata
    public int life; //viata curenta
    public int maxMana; //maximul teoretic de mana
    public int mana; //mana curenta
    public int level;
    public int strenght;
    public int dexterity;
    public int attack; //cat atac se da asupra entitatilor
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
    public boolean eventLayerLight = false; // transient static light loaded from the TMX Events layer
    public boolean removeOnPickup = true;   // for touch-pickups in the Objects layer; remove from world after a successful pickup

    // TILED / EDITOR METADATA
    public String objectId = null;      // persistent ID set from Tiled 'id' property
    public boolean invisible = false;   // set from Tiled 'invisible' property (no draw)
    public int aggroRange = 160;        // aggro distance in pixels (default ~2.5 tiles)
    public int wanderRadius = 0;        // max wander pixel offset from spawn (0 = free)
    public Rectangle confinementZone = null; // if set, monster cannot leave this rectangle (world pixels)
    public boolean staticNPC = false;   // NPC never wanders or follows paths — stays in place
    public boolean guardMode  = false;  // Static from spawn; faces the player every tick. Set onPath=true to unlock movement.
    public String portraitPath = null;  // optional portrait image path (e.g. "/res/NPC/alucard_portrait.png")
    public int walkToCol = -1;          // after interaction: walk to this tile column, then re-enter guardMode (-1 = unused)
    public int walkToRow = -1;          // after interaction: walk to this tile row
    public int walkToDialogueSet = -1;  // dialogue set to use after arriving at walkTo destination (-1 = keep existing logic)

    // ── NPC STEP CHAIN: sequence of (dialogueSet, walkToCol, walkToRow) steps ──
    // Each step is int[3]: {dialogueSet, col, row}.  col/row < 0 means "stay here" (final step).
    public final java.util.ArrayList<int[]> npcSteps = new java.util.ArrayList<>();
    public int npcStepIndex = 0;

    public String onSpeakQuestId = null;   // quest id to progress when player talks to this NPC
    public int    onSpeakQuestAmount = 1;  // how much to add to that quest on each speak

    public String requiredItem = null;          // item name the player must have to trigger alternate dialogue
    public int    requiredItemDialogueSet = -1; // which dialogue set to use when the player has the item
    public boolean requiredItemConsumed = false; // if true, the item is removed from inventory on delivery
    public String requiredItemQuestId = null;   // quest id progressed when required item is delivered
    public int    requiredItemQuestAmount = 1;  // how much to add on delivery
    public int    requiredItemPostQuestSet = -1; // dialogue set used after the delivery phase is complete
    public int    requiredItemRewardCoins = 0;  // coin reward granted on delivery
    public String requiredItemRewardItemId = null; // ItemFactory id granted on delivery
    public String requiredItemRewardFragmentId = null; // MemoryJournal id granted on delivery

    public String  giveItemId          = null;  // ItemFactory id to give on first interaction
    public int     giveItemDialogueSet  = 0;    // dialogue set played while giving
    public boolean giveItemGiven        = false; // true after item was given once
    public String  giveItemQuestId      = null; // quest added when the first gift is given
    public String  giveItemQuestName    = null;
    public String  giveItemQuestDesc    = null;
    public int     giveItemQuestTarget  = 1;

    public String  giveItem2Id         = null;  // ItemFactory id to give after NPC finishes helping (post-walk)
    public int     giveItem2DialogueSet = -1;   // dialogue set played while giving item 2 (-1 = use walkToDialogueSet)
    public boolean giveItem2Given       = false; // true after item 2 was given

    // ── MEMORY FRAGMENT SYSTEM ──
    public String   
    memoryFragmentId   = null;   // unique ID (e.g. "kings_face")
    public String   memoryFragmentName = null;   // display name ("His Last Expression")
    public String[] memoryFragmentText = null;   // 1–5 lines of flashback text
    public boolean  memoryFragmentClaimed = false;
    // Trigger conditions — checked to decide if the fragment can be claimed
    public int    fragmentRequiredCount = -1;     // minimum fragment count (-1 = no requirement)
    public String fragmentRequiredItem  = null;   // player must hold this item
    public int    fragmentRequiredBoss  = -1;     // boss # that must be defeated (1–4, -1 = none)
    public String fragmentRequiredQuest = null;   // quest ID that must be complete

    // ── CHOICE DIALOGUE SYSTEM ──
    public String[] dialogueChoices    = null;   // option texts (null = linear dialogue)
    public int      selectedChoice     = 0;      // cursor index during choice selection
    public int[]    choiceNextSet      = null;   // dialogue set to jump to per choice index
    public String   choiceResultKey    = null;   // key to store chosen option in GameState



    // ITEM ATTRIBUTES
    public int attackValue; //pentru arme: cat adauga la atac cand e echipata
    public int defenseValue; //pentru scuturi: cat adauga la aparare cand e echipat
    public String itemId = null; // stable ItemFactory identifier for save/load and Tiled references
    public String description = "";
    public int useCost; //DOAR pentru consumabile: cat mana consuma cand e folosita
    public boolean stackable = false; //daca un item poate fi adunat intr-un stack de n iteme
    public int amount = 1; //stack-ul incepe initial de la 1, fie ca e stackable sau nu
    public float working_power; //cat ia din durabilitatea obiectului (toporul ia mai mult din durabilitatea lemnului decat o sabie)
    public String tool_type; //pentru ce e folosita unealta (topor pentru lemn)
    public float spriteScale = 1.5f;           // 1.0 = normal size, 2.0 = double (boss phase scaling)

    public Entity(GamePanel gp) {
        this.gp = gp;
    }



    // GETTERS, sau cum ii numesti...
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

    // --- Combat methods ---
    public int getMaxLife() { return maxLife; }
    public int getLife() { return life; }
    public void setLife(int life) { this.life = life; }
    public int getAttack() { return attack; }
    public int getDefense() { return defense; }
    public boolean isInvincible() { return invincible; }
    public boolean isDying() { return dying; }

    // --- Animation methods ---
    public BufferedImage getWalkFrame(int direction, int frameIndex) { return getWalkFrameImage(direction, frameIndex); }
    public int getSpriteNum() { return spriteNum; }
    public int getDirection() { return direction; }

    // --- Pathfinding methods ---
    public boolean isOnPath() { return onPath; }
    public void setOnPath(boolean onPath) { this.onPath = onPath; }
    public int getSpeed() { return speed; }
    public void setSpeed(int speed) { this.speed = speed; }
    
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

    /**
     * Checks if the player has the required item; if so, starts the alternate dialogue.
     * Call at the top of speak() — returns true if the item dialogue was triggered.
     */
    protected boolean checkRequiredItemDialogue() {
        if (requiredItem != null && requiredItemDialogueSet >= 0) {
            int itemIndex = gp.player.searchItemInInventory(requiredItem);
            if (itemIndex == 999) return false;

            if (requiredItemConsumed && itemIndex < gp.player.inventory.size()) {
                Entity item = gp.player.inventory.get(itemIndex);
                if (item.amount > 1) item.amount--;
                else gp.player.inventory.remove(itemIndex);
            }

            if (requiredItemQuestId != null && gp.questManager != null) {
                gp.questManager.progress(requiredItemQuestId, Math.max(1, requiredItemQuestAmount));
            }

            if (requiredItemRewardCoins > 0) {
                gp.player.coin += requiredItemRewardCoins;
                gp.ui.addMessage("Received " + requiredItemRewardCoins + " coins!", COIN_MSG_COLOR);
            }

            if (requiredItemRewardItemId != null) {
                Entity rewardItem = data.ItemFactory.create(gp, requiredItemRewardItemId);
                if (rewardItem != null) {
                    if (gp.player.canObtainItem(rewardItem)) {
                        gp.ui.addMessage("Received " + rewardItem.name + "!", Color.WHITE);
                    } else {
                        gp.ui.addMessage("Inventory full. Could not receive " + rewardItem.name + ".", Color.WHITE);
                    }
                }
            }

            if (requiredItemRewardFragmentId != null && gp.memoryJournal != null) {
                data.MemoryJournal.MemoryFragment fragment = gp.memoryJournal.collect(requiredItemRewardFragmentId);
                if (fragment == null && !gp.memoryJournal.has(requiredItemRewardFragmentId)) {
                    gp.memoryJournal.addById(requiredItemRewardFragmentId);
                    gp.ui.addMessage("Memory fragment received: " + requiredItemRewardFragmentId, Color.WHITE);
                } else if (fragment != null) {
                    gp.ui.addMessage("Memory fragment received: " + fragment.name, Color.WHITE);
                }
            }

            int deliveryDialogueSet = requiredItemDialogueSet;
            if (requiredItemPostQuestSet >= 0) walkToDialogueSet = requiredItemPostQuestSet;
            requiredItemDialogueSet = -1;
            requiredItem = null;
            startDialogue(this, deliveryDialogueSet);
            return true;
        }
        return false;
    }
    public void syncQuestDrivenNpcState() {
        if (gp == null || gp.questManager == null) return;

        if (giveItemQuestId != null && gp.questManager.hasQuest(giveItemQuestId)) {
            giveItemGiven = true;
        }

        String completionQuestId = (requiredItemQuestId != null && !requiredItemQuestId.isBlank())
                ? requiredItemQuestId : giveItemQuestId;
        if (completionQuestId != null && gp.questManager.isComplete(completionQuestId)) {
            giveItemGiven = true;
            if (requiredItemPostQuestSet >= 0) walkToDialogueSet = requiredItemPostQuestSet;
            requiredItemDialogueSet = -1;
        }
    }
    public void setLoot(Entity loot) {}
    public void facePlayer() {
        direction = switch (gp.player.direction) {
            case DIR_UP -> DIR_DOWN;
            case DIR_DOWN -> DIR_UP;
            case DIR_LEFT -> DIR_RIGHT;
            case DIR_RIGHT -> DIR_LEFT;
            default -> direction;
        };
    }
    /** Turns this entity to face toward the player's actual position, ignoring the player's facing direction. */
    public void faceTowardPlayer() {
        int dx = gp.player.worldX - worldX;
        int dy = gp.player.worldY - worldY;
        if (Math.abs(dx) >= Math.abs(dy)) {
            direction = dx >= 0 ? DIR_RIGHT : DIR_LEFT;
        } else {
            direction = dy >= 0 ? DIR_DOWN : DIR_UP;
        }
    }
    /**
     * Called on walkTo arrival. Loads the next step's walkTo coords so the
     * next speak() will know where to send the NPC (and which dialogue to play).
     */
    protected void advanceNpcStep() {
        if (npcSteps.isEmpty()) return;
        // npcStepIndex points at the next step to load after the walk that just finished
        if (npcStepIndex < npcSteps.size()) {
            int[] step = npcSteps.get(npcStepIndex);
            walkToDialogueSet = step[0];
            walkToCol = step[1];
            walkToRow = step[2];
            npcStepIndex++; // advance now so next arrival loads the one after this
        }
    }
    public void interact() {}
    public void damageReaction() {}
    public boolean use(Entity entity) { return false; }
    public void startDialogue(Entity entity, int setNum) {
        gp.gameState = GamePanel.dialogueState;
        gp.ui.npc = entity;
        dialogueSet = setNum;
        // Auto-progress a quest when the player starts talking to this NPC
        if (entity.onSpeakQuestId != null && gp.questManager != null) {
            gp.questManager.progress(entity.onSpeakQuestId, entity.onSpeakQuestAmount);
        }
    }
    /** Start dialogue by named key. Resolves via dialogueNameMap, falls back to parseInt, then 0. */
    public void startNamedDialogue(Entity entity, String dialogueName) {
        if (dialogueName == null) { startDialogue(entity, 0); return; }
        if (entity.dialogueNameMap != null) {
            Integer idx = entity.dialogueNameMap.get(dialogueName);
            if (idx != null) { startDialogue(entity, idx); return; }
        }
        try { startDialogue(entity, Integer.parseInt(dialogueName)); }
        catch (NumberFormatException e) { startDialogue(entity, 0); }
    }
    public void checkCollision() {
        
        collisionOn = false;
        gp.cChecker.checkTile(this);
        gp.cChecker.checkObject(this, false);
        gp.cChecker.checkEntity(this, gp.npc);
        gp.cChecker.checkEntity(this, gp.monster);
        boolean contactPlayer = gp.cChecker.checkPlayer(this);

        if (type == TYPE_MONSTER && contactPlayer && !gp.player.invincible) {
            
            gp.playSE(SFX.PLAYER_HIT);

            int damage = attack - gp.player.defense;
            if (damage < 1) {
                damage = 1;
            }

            gp.player.life -= damage;
            gp.player.invincible = true;

            // Grab: root the player if this monster has a rootOnContactDuration
            if (rootOnContactDuration > 0 && !gp.player.rooted) {
                gp.player.rooted = true;
                gp.player.rootedTimer = rootOnContactDuration;
            }
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
        return 0;
    }
    public int getParticleMaxLife() {
        return 0;
    }
    public int getParticleStyle() {
        return Particle.STYLE_DEFAULT;
    }
    public void generateParticle ( Entity generator, Entity target ) {

        Color color = generator.getParticleColor();
        int size = generator.getParticleSize();
        int particleSpeed = generator.getParticleSpeed();
        int particleMaxLife = generator.getParticleMaxLife();
        int style = generator.getParticleStyle();

        // OPTIMIZATION: Use particle pool instead of creating new objects
        // Position particles at the TARGET location (where hit occurred), not the generator
        Particle p1 = gp.particlePool.get();
        p1.setWithPosition(generator, target, color, size, particleSpeed, particleMaxLife, -1, -1, style);
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

        // ── STATUS EFFECTS: tick timers ──
        if (slowedTimer > 0 && --slowedTimer == 0) slowed = false;
        if (rootedTimer > 0 && --rootedTimer == 0) rooted = false;
        // Phasing cycle: first half = invulnerable, second half = vulnerable
        if (phasing) {
            if (++phasingCycleCounter >= phasingCycleDuration) phasingCycleCounter = 0;
            boolean shouldBeInvulnerable = phasingCycleCounter < phasingCycleDuration / 2;
            if (shouldBeInvulnerable != invincible) {
                invincible = shouldBeInvulnerable;
                invincibleCounter = 0;
            }
        }
        // Rooted: freeze AI and movement this frame while still ticking other timers
        if (rooted) {
            if (invincible) { invincibleCounter++; if (invincibleCounter > invincibleDuration) { invincible = false; invincibleCounter = 0; } }
            if (hitFlashCounter > 0) hitFlashCounter--;
            if (shotAvailableCounter < 30) shotAvailableCounter++;
            return;
        }

        int previousWorldX = worldX;
        int previousWorldY = worldY;

        // guardMode: block setAction unless we currently have an active walkTo path
        if (!staticNPC && (!guardMode || onPath)) setAction();
        // guardMode: face the player each tick when not walking
        if (guardMode && !onPath) faceTowardPlayer();
        checkCollision();

        // ------------------------------------------------------------------
        // NORMAL MOVEMENT
        // IF COLLISION IS FALSE, MOVE
        // Only run manual movement if pathfinding is NOT active
        if (!collisionOn && !onPath && !staticNPC && !guardMode) {
            int moveSpeed = slowed ? Math.max(1, speed / 2) : speed;
            switch (direction) {
                case DIR_UP -> worldY -= moveSpeed;
                case DIR_DOWN -> worldY += moveSpeed;
                case DIR_LEFT -> worldX -= moveSpeed;
                case DIR_RIGHT -> worldX += moveSpeed;
                default -> {
                }
            }
        }
        
        // IMPORTANT: If we just finished pathfinding, we don't want to keep moving.
        // The searchPath logic handles movement when onPath is true.

        // ── CONFINEMENT ZONE: clamp monster inside its spawn zone ──
        if (confinementZone != null) {
            int solidLeft   = worldX + solidArea.x;
            int solidRight  = worldX + solidArea.x + solidArea.width;
            int solidTop    = worldY + solidArea.y;
            int solidBottom = worldY + solidArea.y + solidArea.height;
            boolean hitBoundary = false;
            if (solidLeft < confinementZone.x) {
                worldX = confinementZone.x - solidArea.x;
                hitBoundary = true;
            } else if (solidRight > confinementZone.x + confinementZone.width) {
                worldX = confinementZone.x + confinementZone.width - solidArea.x - solidArea.width;
                hitBoundary = true;
            }
            if (solidTop < confinementZone.y) {
                worldY = confinementZone.y - solidArea.y;
                hitBoundary = true;
            } else if (solidBottom > confinementZone.y + confinementZone.height) {
                worldY = confinementZone.y + confinementZone.height - solidArea.y - solidArea.height;
                hitBoundary = true;
            }
            if (hitBoundary) {
                // Pick a new random direction to wander away from the edge
                actionLockCounter = 0;
                onPath = false;
                direction = switch (direction) {
                    case DIR_UP -> DIR_DOWN;
                    case DIR_DOWN -> DIR_UP;
                    case DIR_LEFT -> DIR_RIGHT;
                    case DIR_RIGHT -> DIR_LEFT;
                    default -> direction;
                };
            }
        }

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
            entityIdle = false;
            idleSpriteNum = 1;
            idleSpriteCounter = 0;
            idleFrameDirection = 1;
            // Reset activity animation when NPC starts walking
            activitySpriteNum = 1;
            activitySpriteCounter = 0;
            activityFrameDirection = 1;

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
            entityIdle = true;

            // Cycle idle animation if idle frames are available
            if (idleFrames != null) {
                int dir = (idleDirection >= 0) ? idleDirection : direction;
                if (dir >= 0 && dir < idleFrames.length && idleFrames[dir] != null && idleFrames[dir].length > 0) {
                    idleSpriteCounter++;
                    if (idleSpriteCounter > idleAnimationInterval) {
                        int maxIdle = idleFrames[dir].length;
                        if (maxIdle == 1) {
                            idleSpriteNum = 1;
                        } else {
                            idleSpriteNum += idleFrameDirection;
                            if (idleSpriteNum >= maxIdle) {
                                idleSpriteNum = maxIdle;
                                idleFrameDirection = -1;
                            }
                            if (idleSpriteNum <= 1) {
                                idleSpriteNum = 1;
                                idleFrameDirection = 1;
                            }
                        }
                        idleSpriteCounter = 0;
                    }
                }
            }

            // ── ACTIVITY ANIMATION: cycle frames when idle and an activity is set ──
            if (currentActivity != null && activityAnimations != null) {
                BufferedImage[][] actFrames = activityAnimations.get(currentActivity);
                if (actFrames != null) {
                    int dir = (idleDirection >= 0) ? idleDirection : direction;
                    if (dir >= 0 && dir < actFrames.length && actFrames[dir] != null && actFrames[dir].length > 0) {
                        int interval = idleAnimationInterval;
                        if (activityAnimSpeeds != null) {
                            Integer customSpeed = activityAnimSpeeds.get(currentActivity);
                            if (customSpeed != null) interval = customSpeed;
                        }
                        activitySpriteCounter++;
                        if (activitySpriteCounter > interval) {
                            int maxFrames = actFrames[dir].length;
                            if (maxFrames == 1) {
                                activitySpriteNum = 1;
                            } else {
                                activitySpriteNum += activityFrameDirection;
                                if (activitySpriteNum >= maxFrames) {
                                    activitySpriteNum = maxFrames;
                                    activityFrameDirection = -1;
                                }
                                if (activitySpriteNum <= 1) {
                                    activitySpriteNum = 1;
                                    activityFrameDirection = 1;
                                }
                            }
                            activitySpriteCounter = 0;
                        }
                    }
                }
            }
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

            int drawW = (int)(gp.tileSize * spriteScale);
            int drawH = (int)(gp.tileSize * spriteScale);

            // ── ACTIVITY ANIMATION: takes priority over idle when standing still ──
            if (currentSprite == null && entityIdle && currentActivity != null && activityAnimations != null) {
                BufferedImage[][] actFrames = activityAnimations.get(currentActivity);
                if (actFrames != null) {
                    int actDir = (idleDirection >= 0) ? idleDirection : direction;
                    if (actDir >= 0 && actDir < actFrames.length && actFrames[actDir] != null) {
                        int idx = activitySpriteNum - 1;
                        if (idx >= 0 && idx < actFrames[actDir].length) {
                            currentSprite = actFrames[actDir][idx];
                        }
                    }
                }
            }

            // Use idle animation when standing still and idle frames exist
            if (currentSprite == null && entityIdle && idleFrames != null) {
                int idleDir = (idleDirection >= 0) ? idleDirection : direction;
                if (idleDir >= 0 && idleDir < idleFrames.length && idleFrames[idleDir] != null) {
                    int idx = idleSpriteNum - 1;
                    if (idx >= 0 && idx < idleFrames[idleDir].length) {
                        currentSprite = idleFrames[idleDir][idx];
                    }
                }
            }
            if (currentSprite == null) {
                currentSprite = getWalkFrameImage(direction, spriteNum);
            }
            if (currentSprite == null) {
                currentSprite = getWalkFrameImage(direction, 1);
            }

            // Monster HP Bar
            if (type == TYPE_MONSTER && hpBarOn) {
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
                // Phasing monsters: more transparent during invulnerable phase
                changeAlpha(g2, phasing ? 0.25F : 0.4F);
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
            // OPTIMIZATION: Reuse a single buffer and cached Graphics2D instead of createGraphics() every frame
            if (hitFlashCounter > 0 && currentSprite != null) {
                float flashAlpha = Math.min(1f, hitFlashCounter / (float) HIT_FLASH_DURATION * 0.8f);
                int sprW = currentSprite.getWidth();
                int sprH = currentSprite.getHeight();
                // Lazily allocate or resize the reusable flash buffer
                if (hitFlashBuffer == null || hitFlashBufferW < sprW || hitFlashBufferH < sprH) {
                    hitFlashBufferW = sprW;
                    hitFlashBufferH = sprH;
                    hitFlashBuffer = new BufferedImage(sprW, sprH, BufferedImage.TYPE_INT_ARGB);
                    if (hitFlashG2 != null) hitFlashG2.dispose();
                    hitFlashG2 = hitFlashBuffer.createGraphics();
                }
                java.awt.Graphics2D fg = hitFlashG2;
                // Clear previous contents
                fg.setComposite(AlphaComposite.Clear);
                fg.fillRect(0, 0, hitFlashBufferW, hitFlashBufferH);
                // Draw sprite then overlay white
                fg.setComposite(AlphaComposite.SrcOver);
                fg.drawImage(currentSprite, 0, 0, null);
                fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, flashAlpha));
                fg.setColor(Color.WHITE);
                fg.fillRect(0, 0, sprW, sprH);
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

    /**
     * Unified sprite access: returns a sprite for the given animation type, direction, and frame.
     * New entities should use this instead of legacy named fields.
     * @param type "walk", "idle", or "attack"
     * @param dir direction constant (DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP)
     * @param frame 0-based frame index
     * @return the sprite image, or null if not available
     */
    public BufferedImage getSprite(String type, int dir, int frame) {
        BufferedImage[][] frames = switch (type) {
            case "walk" -> walkFrames;
            case "idle" -> idleFrames;
            case "attack" -> attackFrames;
            default -> {
                // Check activity animations for custom types (e.g. "forge", "sweep")
                if (activityAnimations != null) yield activityAnimations.get(type);
                yield null;
            }
        };
        if (frames != null && dir >= 0 && dir < frames.length && frames[dir] != null) {
            if (frame >= 0 && frame < frames[dir].length) return frames[dir][frame];
        }
        // Walk fallback: try legacy named fields (1-based)
        if ("walk".equals(type)) {
            return getWalkFrameImage(dir, frame + 1);
        }
        return null;
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

        if (type == TYPE_MONSTER) {
            if (name != null) {
                gp.ui.addMessage("Killed the " + name + "!", Color.WHITE);
            }
            if (deathRewardExp > 0) {
                gp.player.exp += deathRewardExp;
                gp.player.checkLevelUp();
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
        BufferedImage scaledImage = null;
        try {
            scaledImage = ResourceCache.loadScaledImage(imagePath + ".png", width, height);
        } catch(IOException e) {
            System.out.println("Entity: failed to load image '" + imagePath + ".png': " + e.getMessage());
        }
        return scaledImage;
    }
    public int getDetected(Entity user, Entity target[], String targetName) {
        int index = 999;
        
        int nextWorldX = user.getLeftX();
        int nextWorldY = user.getTopY();

        switch (user.direction) {
            case DIR_UP -> nextWorldY = user.getTopY() - gp.player.speed;
            case DIR_DOWN -> nextWorldY = user.getBottomY() + gp.player.speed;
            case DIR_LEFT -> nextWorldX = user.getLeftX() - gp.player.speed;
            case DIR_RIGHT -> nextWorldX = user.getRightX() + gp.player.speed;
            default -> {
            }
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
    // FIXED SEARCH PATH (with per-entity result caching)
    // -----------------------------------------------------------------
    public void searchPath(int goalCol, int goalRow) {

        int startCol = (worldX + solidArea.x) / gp.tileSize;
        int startRow = (worldY + solidArea.y) / gp.tileSize;

        // Already standing on the goal — nothing to do
        if (startCol == goalCol && startRow == goalRow) {
            onPath = false;
            cachedWaypoints.clear();
            waypointIdx = 0;
            if (walkToCol == goalCol && walkToRow == goalRow) {
                guardMode = true;
                walkToCol = -1;
                walkToRow = -1;
                advanceNpcStep();
            }
            return;
        }

        // Reuse cached path while the goal tile hasn't changed
        if (goalCol == pathCacheGoalCol && goalRow == pathCacheGoalRow
                && waypointIdx < cachedWaypoints.size()
                && pathStallCounter < PATH_STALL_LIMIT) {
            followWaypoints();
            return;
        }

        // Goal changed or stalled too long — run A* and cache the result
        pathStallCounter = 0;
        pathCacheGoalCol = goalCol;
        pathCacheGoalRow = goalRow;
        cachedWaypoints.clear();
        waypointIdx = 0;

        gp.pFinder.setNodes(startCol, startRow, goalCol, goalRow, this);
        if (gp.pFinder.search()) {
            for (int i = 0; i < gp.pFinder.pathList.size(); i++) {
                cachedWaypoints.add(new int[]{
                    gp.pFinder.pathList.get(i).col,
                    gp.pFinder.pathList.get(i).row
                });
            }
            if (!cachedWaypoints.isEmpty()) {
                followWaypoints();
            } else {
                onPath = false;
            }
        } else {
            directChase(goalCol, goalRow);
        }
    }

    private void followWaypoints() {
        if (waypointIdx >= cachedWaypoints.size()) {
            onPath = false;
            cachedWaypoints.clear();
            waypointIdx = 0;
            return;
        }

        int[] next  = cachedWaypoints.get(waypointIdx);
        int nextX   = next[0] * gp.tileSize;
        int nextY   = next[1] * gp.tileSize;

        int enLeftX   = worldX + solidArea.x;
        int enTopY    = worldY + solidArea.y;
        int enCenterX = enLeftX + solidArea.width  / 2;
        int enCenterY = enTopY  + solidArea.height / 2;

        int nextCenterX = nextX + gp.tileSize / 2;
        int nextCenterY = nextY + gp.tileSize / 2;
        int dx = Math.abs(nextCenterX - enCenterX);
        int dy = Math.abs(nextCenterY - enCenterY);

        // Reached current waypoint — advance to next
        if (dx <= speed + 1 && dy <= speed + 1) {
            waypointIdx++;
            pathStallCounter = 0;
            if (waypointIdx >= cachedWaypoints.size()) {
                onPath = false;
                cachedWaypoints.clear();
                waypointIdx = 0;
                if (walkToCol == pathCacheGoalCol && walkToRow == pathCacheGoalRow) {
                    guardMode = true;
                    walkToCol = -1;
                    walkToRow = -1;
                    advanceNpcStep();
                }
            }
            return;
        }

        // Move toward the current waypoint
        if (dy > dx) {
            boolean moved = tryMoveVertical(enTopY, nextY);
            if (!moved) tryMoveHorizontal(enLeftX, nextX);
        } else {
            boolean moved = tryMoveHorizontal(enLeftX, nextX);
            if (!moved) tryMoveVertical(enTopY, nextY);
        }

        // Always count frames spent on this waypoint — triggers repath around obstacles
        pathStallCounter++;
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
            sheet = ResourceCache.loadImage(path + ".png");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load spritesheet: " + path, e);
        }

        int columns = sheet.getWidth() / spriteWidth;
        int rows = sheet.getHeight() / spriteHeight;
        // Check if there's a partial extra row (e.g. 400px / 128 = 3 but 4th row visible)
        int remainderH = sheet.getHeight() - rows * spriteHeight;
        if (remainderH > spriteHeight / 2) {
            rows++; // include the partial row
        }

        BufferedImage[][] matrix = new BufferedImage[rows][columns];

        for (int y = 0; y < rows; y++) {
            int srcY = y * spriteHeight;
            int srcH = Math.min(spriteHeight, sheet.getHeight() - srcY);
            for (int x = 0; x < columns; x++) {
                if (srcH == spriteHeight) {
                    matrix[y][x] = sheet.getSubimage(
                            x * spriteWidth,
                            srcY,
                            spriteWidth,
                            spriteHeight
                    );
                } else {
                    // Partial row: copy into a full-size image
                    BufferedImage padded = new BufferedImage(spriteWidth, spriteHeight, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D pg = padded.createGraphics();
                    pg.drawImage(sheet.getSubimage(x * spriteWidth, srcY, spriteWidth, srcH), 0, 0, null);
                    pg.dispose();
                    matrix[y][x] = padded;
                }
            }
        }

        return matrix;
    }
}

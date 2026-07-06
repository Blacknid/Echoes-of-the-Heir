package entity;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;
import gfx.geom.Rect;
import java.io.IOException;

import main.GamePanel;
import util.ResourceCache;

public class Entity {

    protected GamePanel gp;

    private int[] waypointCols = new int[32];
    private int[] waypointRows = new int[32];
    private int   waypointCount  = 0;
    private int   waypointIdx    = 0;
    private int pathCacheGoalCol = -1;
    private int pathCacheGoalRow = -1;
    private int pathStallCounter = 0;  // frames without reaching the next waypoint
    private static final int PATH_STALL_LIMIT = 10; // force repath after this many stalled frames

    public static final int DIR_DOWN  = 0;
    public static final int DIR_LEFT  = 1;
    public static final int DIR_RIGHT = 2;
    public static final int DIR_UP    = 3;
    public static final int DIR_ANY   = -1; // for event checks (any facing)



    public int worldX, worldY;
    public boolean alive = true;
    public boolean dying = false;
    boolean hpBarOn = false;



    public Sprite up1, up2, up3, up4, up5, up6, up7,
        down1, down2, down3, down4, down5, down6, down7,
        left1, left2, left3, left4, left5, left6, left7, left8,
        right1, right2, right3, right4, right5, right6, right7, right8;
    public Sprite[][] walkFrames;   // walk animation per direction
    public Sprite[][] idleFrames;   // idle animation per direction
    public Sprite[][] attackFrames;  // attack animation per direction (combo step 0)
    public Sprite[][] attackFrames2; // combo step 1, 1-tile sprites
    public Sprite[][] attackFrames3; // combo step 2, 1-tile sprites

    public java.util.HashMap<String, Sprite[][]> activityAnimations;   // key → [dir][frame]
    public java.util.HashMap<String, Integer> activityAnimSpeeds;             // key → ticks between frames
    public String  currentActivity = null;       // active animation key (null = use default walk/idle)
    public int     activitySpriteNum = 1;        // current frame (1-based, bouncing)
    public int     activitySpriteCounter = 0;    // tick counter for frame advance
    private int    activityFrameDirection = 1;   // +1 or -1 (bounce direction)

    // Data-driven "npcs.json" activity key to switch to for as long as this NPC is being talked to
    // (e.g. the blacksmith stops forging and just idles while you're mid-conversation). Null = no
    // override, currentActivity is left entirely to the normal state machine.
    public String dialogueActivity = null;
    private String preDialogueActivity = null;
    private boolean dialogueActivityActive = false;

    public int direction = DIR_DOWN;

    public int idleDirection = -1;          // -1 = use current direction; >= 0 = forced idle dir
    public int idleSpriteNum = 1;
    public int idleSpriteCounter = 0;
    private int idleFrameDirection = 1;
    public int idleAnimationInterval = 24;  // ticks between idle frame advances (~0.4 s at 60 UPS)
    protected boolean entityIdle = true;

    public int spriteCounter = 0;
    public int spriteNum = 1;
    public int walkFrameCount = 3;
    public int animationFrameInterval = 8;
    private int walkFrameDirection = 1;

    // ── libGDX-backed animation (walk / idle / activity) ──
    // These wrap the existing walkFrames/idleFrames/activityAnimations arrays in
    // gfx.SpriteAnimation (LOOP_PINGPONG = the old 1..N..1 bounce). Frames are advanced by a float
    // stateTime (fixed 60-UPS step) and the resulting index is written back into spriteNum /
    // idleSpriteNum / activitySpriteNum so the existing draw code (which reads those) is unchanged.
    private gfx.SpriteAnimation[] walkAnim;
    private gfx.SpriteAnimation[] idleAnim;
    private final java.util.HashMap<String, gfx.SpriteAnimation[]> activityAnim = new java.util.HashMap<>();
    private float walkStateTime = 0f, idleStateTime = 0f, activityStateTime = 0f;
    private static final float DT = 1f / 60f; // fixed sim step (swap to Gdx.graphics.getDeltaTime() for real delta)
    public int actionLockCounter = 0;
    public int invincibleCounter = 0;
    public int invincibleDuration = 10; // frames of i-frames after hit (short for combo-friendly combat)
    public int shotAvailableCounter = 0;
    int dyingCounter = 0;
    int hpBarCounter = 0;
    public int crowdControlTimer = 0;

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



    public int hitFlashCounter = 0;
    private static final int HIT_FLASH_DURATION = 6;
    public int attackWindupFlash = 0;
    private static final Color HP_BAR_BG = new Color(35, 35, 35);
    private static final Color HP_BAR_FG = new Color(255, 0, 30);
    private static final Color SPARK_COLOR_1 = new Color(255, 235, 120);
    private static final Color SPARK_COLOR_2 = new Color(255, 200, 80);
    private static final Color COIN_MSG_COLOR = new Color(255, 210, 90);
    // (Hit-flash/telegraph now tint on the GPU via the batch — no intermediate buffer needed.)



    public int footstepParticleCounter = 0;


    
    public String dialogues[][];
    public int dialogueIndex = 0;
    public int dialogueSet = 0;
    public java.util.HashMap<String, Integer> dialogueNameMap = null; // named dialogue key → set index


    
    public String[][] ensureDialogues() {
        if (dialogues == null) {
            dialogues = new String[20][20];
        }
        return dialogues;
    }


    
    public Sprite image, image1, image2, image3, compas_image;



    public Rect solidArea = new Rect(0, 0, 48, 48);
    public Rect attackArea = new Rect(0, 0, 0, 0);
    public int solidAreaDefaultX, solidAreaDefaultY;

    // Optional polygon hurtbox for hit-detection only (tile/movement collision still uses solidArea).
    // Set via setOctagonHurt() in subclass constructors. Stored in local entity space (origin = worldX/worldY).
    public gfx.geom.IntPolygon hurtPolygon = null;

    /** Replace hurtPolygon with a regular octagon. cx/cy are the center offset from worldX/worldY; r is the radius. */
    public void setOctagonHurt(int cx, int cy, int r) {
        int[] xs = new int[8];
        int[] ys = new int[8];
        int cut = (int) Math.round(r * 0.2);
        xs[0] = cx - r + cut; ys[0] = cy - r;
        xs[1] = cx + r - cut; ys[1] = cy - r;
        xs[2] = cx + r;       ys[2] = cy - r + cut;
        xs[3] = cx + r;       ys[3] = cy + r - cut;
        xs[4] = cx + r - cut; ys[4] = cy + r;
        xs[5] = cx - r + cut; ys[5] = cy + r;
        xs[6] = cx - r;       ys[6] = cy + r - cut;
        xs[7] = cx - r;       ys[7] = cy - r + cut;
        hurtPolygon = new gfx.geom.IntPolygon(xs, ys, 8);
    }
    public boolean collisionOn = false;
    public boolean invincible = false;
    public boolean attacking = false;
    public boolean collision = false;
    public boolean sleep = false;
    public boolean drawing = true;
    public boolean onPath = false;
    public boolean knockBack = false;          // true while being pushed by an attack
    public int knockBackPower = 0;             // magnitude of the push (for debug display)
    // Knockback: callers set knockBackVectorX/Y as the INITIAL burst velocity (pixels/frame) — a
    // quick pop in the hit direction. Entity.update() then decays it every frame (knockBackDecay),
    // so the motion is fast-then-slow instead of a constant slide, and stops itself once the
    // velocity trails off (or on collision) rather than needing a separate travel-distance budget.
    public int knockBackVectorX = 0;
    public int knockBackVectorY = 0;
    public double knockBackRemaining = 0;      // distance left to travel (legacy; unused by the decay model, kept for any external readers)
    private float knockBackVelX = 0f, knockBackVelY = 0f;   // current (decaying) sub-pixel velocity
    private float knockBackAccumX = 0f, knockBackAccumY = 0f; // sub-pixel remainder carried between frames
    private static final float KNOCKBACK_DECAY = 0.80f;     // velocity multiplier applied every frame
    private static final float KNOCKBACK_STOP_SPEED = 0.4f; // stop once speed drops below this (px/frame)
    // Callers' power values were tuned for the old constant-speed slide; multiply the initial pop so
    // the new decay-based burst covers a comparable total distance before it tails off.
    private static final float KNOCKBACK_BURST_MULTIPLIER = 3.2f;
    public boolean fleeing = false;            // AI state: running away from player
    public int fleeCounter = 0;
    public int fleeDuration = 60;
    public boolean frontalArmor = false;       // blocks 50% of frontal hits (Painted Guard, Painted Crab)
    public int     rootOnContactDuration = 0; // roots the player on contact for N frames (Hollow Stump)
    public int alertTick = 0;                 // counts down from ALERT_DURATION when monster first spots player
    public boolean everAggroed = false;       // true once monster has spotted player; resets only when it loses the player
    public Entity loot;
    public boolean opened = false;



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
    // Shadow casting (Stage 2 lighting): objects (trees, crates, statues) opt in so their sprite
    // silhouette casts a dynamic shadow away from nearby lights. Characters cast by default (see
    // castsShadow()); this flag lets solid decorative objects join in without being characters.
    public boolean castsShadow = false;
    public gfx.Color lightColor = null; // custom light tint (null = default orange)
    public boolean eventLayerLight = false; // transient static light loaded from the TMX Events layer
    public boolean removeOnPickup = true;   // for touch-pickups in the Objects layer; remove from world after a successful pickup

    // TILED / EDITOR METADATA
    public String objectId = null;      // persistent ID set from Tiled 'id' property
    public boolean invisible = false;   // set from Tiled 'invisible' property (no draw)
    public int aggroRange = 160;        // aggro distance in pixels (default ~2.5 tiles)
    public int interactRange = 0;       // if > 0, player can interact when within this radius (facing the NPC)
    public int depthSortYOffset = 0;    // added to entityY in depth sort — positive pushes entity to draw in front of lower tiles
    public int wanderRadius = 0;        // max wander pixel offset from spawn (0 = free)
    public Rect confinementZone = null; // if set, monster cannot leave this rectangle (world pixels)
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

    public String[] dialogueChoices    = null;   // option texts (null = linear dialogue)
    public int      selectedChoice     = 0;      // cursor index during choice selection
    public int[]    choiceNextSet      = null;   // dialogue set to jump to per choice index
    public String   choiceResultKey    = null;   // key to store chosen option in GameState



    public int attackValue; //pentru arme: cat adauga la atac cand e echipata
    public int defenseValue; //pentru scuturi: cat adauga la aparare cand e echipat
    public String itemId = null; // stable ItemFactory identifier for save/load and Tiled references
    public String description = "";
    public int useCost; //DOAR pentru consumabile: cat mana consuma cand e folosita
    public boolean stackable = false; //daca un item poate fi adunat intr-un stack de n iteme
    public int amount = 1; //stack-ul incepe initial de la 1, fie ca e stackable sau nu
    public float working_power; //cat ia din durabilitatea obiectului (toporul ia mai mult din durabilitatea lemnului decat o sabie)
    public String tool_type; //pentru ce e folosita unealta (topor pentru lemn)
    public float spriteScale = 1.0f;           // 1.0 = normal size, 2.0 = double (boss phase scaling)

    public Entity(GamePanel gp) {
        this.gp = gp;
    }



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

    public int getMaxLife() { return maxLife; }
    public int getLife() { return life; }
    public void setLife(int life) { this.life = life; }
    public int getAttack() { return attack; }
    public int getDefense() { return defense; }
    public boolean isInvincible() { return invincible; }
    public boolean isDying() { return dying; }

    public Sprite getWalkFrame(int direction, int frameIndex) { return getWalkFrameImage(direction, frameIndex); }
    public int getSpriteNum() { return spriteNum; }
    public int getDirection() { return direction; }

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
    /** Re-evaluate which data-driven activity/dialogueSet state is active. Called every update()
     *  regardless of staticNPC (unlike setAction(), which static NPCs skip entirely). No-op by
     *  default; overridden by NPC_Generic. */
    public void tickActivityState() {}

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

        // Swap to the dialogue-only activity (e.g. blacksmith stops forging and just idles while
        // being talked to). Restored in endDialogueActivity(), called when dialogue closes.
        if (dialogueActivity != null && !dialogueActivityActive) {
            preDialogueActivity = currentActivity;
            currentActivity = dialogueActivity;
            activitySpriteNum = 1;
            activitySpriteCounter = 0;
            dialogueActivityActive = true;
        }

        // Cutscene-style framing: turn the NPC toward the player and set the dialogue camera to zoom
        // in and recenter on the midpoint of player+NPC. ONLY for real world NPCs — event-driven
        // dialogues (DialogueTrigger, healing pool, campfire, etc.) speak through a placeholder
        // eventMaster Entity with no world position, so framing on it would fling the camera to
        // (0,0). Those keep the camera centered on the player (neutral dialogue camera).
        // Also skips scripted cutscene dialogue, which sets cutsceneState and owns its own camera.
        if (gp.gameState == GamePanel.dialogueState && entity instanceof NPC_Generic) {
            entity.faceTowardPlayer();
            float pSx = gp.player.screenX + gp.tileSize / 2f;
            float pSy = gp.player.screenY + gp.tileSize / 2f;
            float nSx = entity.worldX - gp.player.worldX + gp.player.screenX + gp.tileSize / 2f;
            float nSy = entity.worldY - gp.player.worldY + gp.player.screenY + gp.tileSize / 2f;
            float midX = (pSx + nSx) / 2f, midY = (pSy + nSy) / 2f;
            gp.dlgZoomTarget = GamePanel.DLG_ZOOM;
            gp.dlgPanTargetX = (gp.screenWidth  / 2f) - midX;
            gp.dlgPanTargetY = (gp.screenHeight / 2f) - midY;
            gp.dlgBarsTarget = 1f;
        } else {
            // Non-NPC dialogue: keep the view centered on the player, no zoom/pan/bars.
            gp.dlgZoomTarget = 1f;
            gp.dlgPanTargetX = 0f;
            gp.dlgPanTargetY = 0f;
            gp.dlgBarsTarget = 0f;
        }
        // Auto-progress a quest when the player starts talking to this NPC
        if (entity.onSpeakQuestId != null && gp.questManager != null) {
            gp.questManager.progress(entity.onSpeakQuestId, entity.onSpeakQuestAmount);
        }
    }

    /** Restore whatever activity was playing before {@link #startDialogue} swapped it out. Called
     *  when dialogue with this NPC ends. No-op if no dialogueActivity override was active. */
    public void endDialogueActivity() {
        if (!dialogueActivityActive) return;
        currentActivity = preDialogueActivity;
        activitySpriteNum = 1;
        activitySpriteCounter = 0;
        dialogueActivityActive = false;
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
        gp.cChecker.checkEntity(this, gp.iTile);
        boolean contactPlayer = gp.cChecker.checkPlayer(this);

        if (type == TYPE_MONSTER && contactPlayer && !gp.player.invincible) {
            int damage = Math.max(1, attack - gp.player.defense);
            int knockbackPower = Math.max(1, (attack + 1) / 2);
            gp.player.onHitByEnemy(damage, worldX, worldY, knockbackPower);

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
    /** Sprite used by image-based particle styles (e.g. STYLE_IMAGE_DEBRIS). Null = no image. */
    public Sprite getParticleImage() {
        return null;
    }
    public void generateParticle ( Entity generator, Entity target ) {

        Color color = generator.getParticleColor();
        int size = generator.getParticleSize();
        int particleSpeed = generator.getParticleSpeed();
        int particleMaxLife = generator.getParticleMaxLife();
        int style = generator.getParticleStyle();
        Sprite image = generator.getParticleImage();

        Particle p1 = gp.particlePool.get();
        p1.setWithPosition(generator, target, color, size, particleSpeed, particleMaxLife, -1, -1, style);
        p1.image = image;
        gp.particleList.add(p1);

        Particle p2 = gp.particlePool.get();
        p2.setWithPosition(generator, target, color, size, particleSpeed, particleMaxLife, 0, -1, style);
        p2.image = image;
        gp.particleList.add(p2);

        Particle p3 = gp.particlePool.get();
        p3.setWithPosition(generator, target, color, size, particleSpeed, particleMaxLife, 1, -1, style);
        p3.image = image;
        gp.particleList.add(p3);

        Particle p4 = gp.particlePool.get();
        p4.setWithPosition(generator, target, color, size, particleSpeed, particleMaxLife, 0, 1, style);
        p4.image = image;
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

        // handling knockback first — check collision before moving to prevent wall phasing
        if (tickKnockback()) return;

        if (slowedTimer > 0 && --slowedTimer == 0) slowed = false;
        if (rootedTimer > 0 && --rootedTimer == 0) rooted = false;
        if (phasing) {
            if (++phasingCycleCounter >= phasingCycleDuration) phasingCycleCounter = 0;
            boolean shouldBeInvulnerable = phasingCycleCounter < phasingCycleDuration / 2;
            if (shouldBeInvulnerable != invincible) {
                invincible = shouldBeInvulnerable;
                invincibleCounter = 0;
            }
        }
        if (rooted) {
            if (invincible) { invincibleCounter++; if (invincibleCounter > invincibleDuration) { invincible = false; invincibleCounter = 0; } }
            if (hitFlashCounter > 0) hitFlashCounter--;
            if (shotAvailableCounter < 30) shotAvailableCounter++;
            return;
        }

        int previousWorldX = worldX;
        int previousWorldY = worldY;

        // Activity-state evaluation (which animation/dialogueSet is active) must run every frame
        // regardless of staticNPC — setAction() itself is skipped for static NPCs (they never
        // wander), but a stationary NPC (e.g. the blacksmith) still needs its activity re-evaluated
        // continuously so state changes (quest progress) pick a new animation without requiring an
        // interaction first.
        tickActivityState();
        if (!staticNPC && (!guardMode || onPath)) setAction();
        if (guardMode && !onPath) faceTowardPlayer();
        checkCollision();

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
            // Reset idle/activity cycles so they restart cleanly next time the entity stops.
            idleSpriteNum = 1; idleStateTime = 0f;
            activitySpriteNum = 1; activityStateTime = 0f;
            // Advance the walk cycle by the fixed sim step; cadence tied to speed (48/speed ticks).
            advanceWalk();
        } else {
            spriteNum = 1;
            walkStateTime = 0f;
            entityIdle = true;
            advanceIdle();
            advanceActivity();
        }

        if (invincible) {
            invincibleCounter++;
            if (invincibleCounter > invincibleDuration) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
        if (hitFlashCounter > 0) hitFlashCounter--;
        if (alertTick > 0) alertTick--;
        if (shotAvailableCounter < 30) {
            shotAvailableCounter++;
        }
    }

    /**
     * Advances idle and activity animation counters without running any AI or movement.
     * Safe to call during dialogue state so NPC animations keep playing while talking.
     */
    public void tickAnimations() {
        entityIdle = true;
        advanceIdle();
        advanceActivity();
    }

    // ── Animation advance helpers (libGDX-backed, fixed 60-UPS step) ──
    // Each rebuilds its SpriteAnimation lazily if the backing frame array changed, advances a float
    // stateTime, and writes the resulting 1-based index back into the *SpriteNum field the draw code
    // reads. PlayMode.LOOP_PINGPONG reproduces the old 1..N..1 bounce.

    private gfx.SpriteAnimation animFor(Sprite[] frames, gfx.SpriteAnimation cached, float durationSec) {
        if (frames == null || frames.length == 0) return null;
        // Rebuild if missing or frame array identity/length changed.
        if (cached == null || cached.frameCount() != frames.length) {
            return new gfx.SpriteAnimation(frames, durationSec,
                com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP_PINGPONG);
        }
        cached.setFrameDuration(durationSec);
        return cached;
    }

    private void advanceWalk() {
        int dir = direction;
        Sprite[] frames = (walkFrames != null && dir >= 0 && dir < walkFrames.length) ? walkFrames[dir] : null;
        if (frames == null || frames.length == 0) {
            // Legacy entities (Object/Item) store frames in named up1/down1/... fields, not walkFrames,
            // and animate via getWalkFrameImage(dir, spriteNum). Keep the old tick-based bounce here.
            advanceLegacyWalkCounter();
            return;
        }
        if (walkAnim == null || walkAnim.length != walkFrames.length) walkAnim = new gfx.SpriteAnimation[walkFrames.length];
        float dur = gfx.SpriteAnimation.durationForTicks(Math.max(2, 48 / Math.max(1, speed)));
        walkAnim[dir] = animFor(frames, walkAnim[dir], dur);
        walkStateTime += DT;
        if (walkAnim[dir] != null) spriteNum = walkAnim[dir].getFrameIndex(walkStateTime) + 1;
    }

    /** Old counter/bounce for legacy named-field entities (walkFrames == null). */
    private void advanceLegacyWalkCounter() {
        spriteCounter++;
        int walkInterval = Math.max(2, 48 / Math.max(1, speed));
        if (spriteCounter > walkInterval) {
            int maxWalkFrames = Math.max(1, Math.min(walkFrameCount, 8));
            if (maxWalkFrames == 1) {
                spriteNum = 1;
            } else {
                spriteNum += walkFrameDirection;
                if (spriteNum >= maxWalkFrames) { spriteNum = maxWalkFrames; walkFrameDirection = -1; }
                if (spriteNum <= 1)             { spriteNum = 1;             walkFrameDirection =  1; }
            }
            spriteCounter = 0;
        }
    }

    private void advanceIdle() {
        if (idleFrames == null) return;
        int dir = (idleDirection >= 0) ? idleDirection : direction;
        Sprite[] frames = (dir >= 0 && dir < idleFrames.length) ? idleFrames[dir] : null;
        if (frames == null || frames.length == 0) return;
        if (idleAnim == null || idleAnim.length != idleFrames.length) idleAnim = new gfx.SpriteAnimation[idleFrames.length];
        idleAnim[dir] = animFor(frames, idleAnim[dir], gfx.SpriteAnimation.durationForTicks(idleAnimationInterval));
        idleStateTime += DT;
        if (idleAnim[dir] != null) idleSpriteNum = idleAnim[dir].getFrameIndex(idleStateTime) + 1;
    }

    private void advanceActivity() {
        if (currentActivity == null || activityAnimations == null) return;
        Sprite[][] actFrames = activityAnimations.get(currentActivity);
        if (actFrames == null) return;
        int dir = (idleDirection >= 0) ? idleDirection : direction;
        Sprite[] frames = (dir >= 0 && dir < actFrames.length) ? actFrames[dir] : null;
        if (frames == null || frames.length == 0) return;
        int interval = idleAnimationInterval;
        if (activityAnimSpeeds != null) {
            Integer customSpeed = activityAnimSpeeds.get(currentActivity);
            if (customSpeed != null) interval = customSpeed;
        }
        gfx.SpriteAnimation[] anims = activityAnim.get(currentActivity);
        if (anims == null || anims.length != actFrames.length) {
            anims = new gfx.SpriteAnimation[actFrames.length];
            activityAnim.put(currentActivity, anims);
        }
        anims[dir] = animFor(frames, anims[dir], gfx.SpriteAnimation.durationForTicks(interval));
        activityStateTime += DT;
        if (anims[dir] != null) activitySpriteNum = anims[dir].getFrameIndex(activityStateTime) + 1;
    }

    public void draw(GdxRenderer g2) {

        Sprite currentSprite = null;
        
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + (gp.screenWidth - gp.player.screenX) &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + (gp.screenHeight - gp.player.screenY)) {

            int drawW = (int)(gp.tileSize * spriteScale);
            int drawH = (int)(gp.tileSize * spriteScale);

            currentSprite = resolveCurrentSprite();

            // Monster HP Bar — never draw once the monster is dying/dead, even if hpBarOn was still
            // set from the killing hit (a lingering bar with residual HP looks like a bug).
            if (type == TYPE_MONSTER && hpBarOn && !dying && alive) {
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
                
            // Shadows are a consequence of light: the sprite's own pixels are drawn into the occluder
            // mask (drawOccluder) and the light shader ray-marches them. No hardcoded blob shadow — the
            // old oval drop-shadow was a fake "shadow object" independent of any light and is removed.

            // Safe guard against null images
            if (currentSprite != null) {
                int drawX = screenX - (drawW - gp.tileSize) / 2;
                int drawY = screenY - (drawH - gp.tileSize);
                g2.drawImage(currentSprite, drawX, drawY, drawW, drawH);
            }

            // ATTACK TELEGRAPH: tint sprite red when about to attack.
            // GPU-native: redraw the sprite tinted red at telegraphAlpha — the sprite's own alpha
            // masks the tint to its silhouette (equivalent to the old SRC_ATOP buffer composite).
            if (attackWindupFlash > 0 && currentSprite != null && hitFlashCounter == 0) {
                float telegraphAlpha = Math.min(0.7f, attackWindupFlash / 20f * 0.7f);
                g2.drawImageTinted(currentSprite, screenX, screenY, gp.tileSize, gp.tileSize,
                        TELEGRAPH_TINT, telegraphAlpha);
                attackWindupFlash--;
            }

            // HIT FLASH: tint sprite white when recently damaged (same GPU-native silhouette tint).
            if (hitFlashCounter > 0 && currentSprite != null) {
                float flashAlpha = Math.min(1f, hitFlashCounter / (float) HIT_FLASH_DURATION * 0.8f);
                g2.drawImageTinted(currentSprite, screenX, screenY, gp.tileSize, gp.tileSize,
                        Color.WHITE, flashAlpha);
            }

            g2.setAlpha(1F);
        }
    }

    private static final Color TELEGRAPH_TINT = new Color(255, 60, 60);

    /**
     * Resolve the sprite frame this entity would draw THIS frame — activity → idle → walk fallback,
     * mirroring the selection order in {@link #draw}. Extracted so the shadow-occluder pass casts the
     * exact silhouette the entity renders (same pose, same facing), keeping shadow and sprite in sync.
     */
    protected Sprite resolveCurrentSprite() {
        Sprite s = null;
        if (entityIdle && currentActivity != null && activityAnimations != null) {
            Sprite[][] actFrames = activityAnimations.get(currentActivity);
            if (actFrames != null) {
                int actDir = (idleDirection >= 0) ? idleDirection : direction;
                if (actDir >= 0 && actDir < actFrames.length && actFrames[actDir] != null) {
                    int idx = activitySpriteNum - 1;
                    if (idx >= 0 && idx < actFrames[actDir].length) s = actFrames[actDir][idx];
                }
            }
        }
        if (s == null && entityIdle && idleFrames != null) {
            int idleDir = (idleDirection >= 0) ? idleDirection : direction;
            if (idleDir >= 0 && idleDir < idleFrames.length && idleFrames[idleDir] != null) {
                int idx = idleSpriteNum - 1;
                if (idx >= 0 && idx < idleFrames[idleDir].length) s = idleFrames[idleDir][idx];
            }
        }
        if (s == null) s = getWalkFrameImage(direction, spriteNum);
        if (s == null) s = getWalkFrameImage(direction, 1);
        return s;
    }

    /**
     * Whether this entity casts a shadow. Overridden per-type; default: NPCs, monsters, and the player
     * cast (solid characters), pickups/projectiles/particles don't. Objects opt in via lightOccluder.
     */
    public boolean castsShadow() {
        return type == TYPE_PLAYER || type == TYPE_NPC || type == TYPE_MONSTER || castsShadow;
    }

    /**
     * Flat ground shadow pass, called once per frame by RenderPipeline BEFORE any entity draws, so
     * shadows always sit under everyone regardless of depth-sort order. No-op by default; overridden
     * by types that draw a flat ground shadow (currently IT_Tree).
     */
    public void drawGroundShadowPass(GdxRenderer g2) {}

    /**
     * Draw this entity's silhouette into the shadow-occluder mask (Stage 2 lighting). The occluder pass
     * clears to transparent and draws every caster's current sprite in solid black; the light shader then
     * ray-marches this mask so lit pixels behind a silhouette fall into shadow. We draw the SAME frame at
     * the SAME screen rect as {@link #draw}, so the cast shadow matches the visible pose. Color/tint are
     * ignored — only the sprite's alpha (its true silhouette, including canopy gaps) matters.
     */
    public void drawOccluder(GdxRenderer g2) {
        if (!castsShadow()) return;
        Sprite s = resolveCurrentSprite();
        if (s == null) return;
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;
        int drawW = (int)(gp.tileSize * spriteScale);
        int drawH = (int)(gp.tileSize * spriteScale);
        int drawX = screenX - (drawW - gp.tileSize) / 2;
        int drawY = screenY - (drawH - gp.tileSize);
        // Solid black tint at full alpha: the sprite's own alpha carves the silhouette into the mask.
        g2.drawImageTinted(s, drawX, drawY, drawW, drawH, Color.BLACK, 1f);
    }

    /**
     * Flat, always-on ground shadow (a soft dark ellipse at the caster's feet), independent of the
     * darkness/light shader. The occluder-mask shadow in {@link #drawOccluder} only exists while
     * Lightning's darkness pass is active (it early-returns at ambientLight 0, e.g. daytime maps), so
     * without this a caster like a tree has no shadow at all in full daylight. Cheap: one alpha-blended
     * ellipse, no shader/FBO involved. w/h are in tiles, offsetX/offsetY in tiles from the caster's feet.
     */
    protected void drawGroundShadow(GdxRenderer g2, int screenX, int screenY, int drawSize,
                                     float w, float h, float offsetX, float offsetY) {
        // anchorWorldX/Y mirrors screenX/Y's own derivation (worldX/Y offset for a drawSize bigger
        // than one tile — see IT_Tree.anchorX/anchorY) via screenX - player.screenX + player.worldX,
        // so it stays the exact same fixed point screenX is built from, whatever caster this is.
        int anchorWorldX = screenX - gp.player.screenX + gp.player.worldX;
        int anchorWorldY = screenY - gp.player.screenY + gp.player.worldY;
        int offX = Math.round(offsetX * gp.tileSize);
        int offY = Math.round(offsetY * gp.tileSize);
        int cx = screenX + drawSize / 2 + offX;
        int cy = screenY + drawSize + offY;
        int sw = Math.round(gp.tileSize * w);
        int sh = Math.round(gp.tileSize * h);
        // Step sized so the oval reads as a 32x32-px sprite scaled up to fit its bounding box (like a
        // real pixel-art shadow texture would be), instead of a fixed real-world block size — the step
        // shrinks/grows with the shadow's own footprint so bigger trees don't get chunkier pixels.
        final int SHADOW_SRC_PX = 32;
        int step = Math.max(1, Math.max(sw, sh) / SHADOW_SRC_PX);
        g2.setColor(new gfx.Color(0, 0, 0, 90));
        g2.fillPixelOval(cx - sw / 2, cy - sh / 2, sw, sh, step, step,
                anchorWorldX + drawSize / 2 + offX, anchorWorldY + drawSize + offY);
        g2.setColor(Color.WHITE);
    }

    protected Sprite getWalkFrameImage(int dir, int frame) {
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
    public Sprite getSprite(String type, int dir, int frame) {
        Sprite[][] frames = switch (type) {
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
    
    public void dyingAnimation(GdxRenderer g2) {
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

    /**
     * Advances an in-progress knockback by one frame: fast burst that decays every frame instead of
     * a constant-speed slide, stopping once the velocity trails off or a collision is hit. Callers
     * trigger a knockback by setting {@code knockBackVectorX/Y} (initial pixels/frame in the hit
     * direction) and {@code knockBack = true}; everything else is handled here.
     *
     * @return true if a knockback was active and handled this frame (caller should skip its normal
     *         movement/update logic for the frame, same as the old inline block did).
     */
    protected boolean tickKnockback() {
        if (!knockBack) return false;

        // A caller just triggered this knockback this frame: seed the burst velocity from the
        // vector they set, then clear it so it isn't re-seeded every frame while decaying.
        // Scaled up (BURST_MULTIPLIER) since callers tuned their power values for the old
        // constant-speed slide — the decay model needs a punchier initial pop to cover a similar
        // total distance before it tails off.
        if (knockBackVectorX != 0 || knockBackVectorY != 0) {
            knockBackVelX = knockBackVectorX * KNOCKBACK_BURST_MULTIPLIER;
            knockBackVelY = knockBackVectorY * KNOCKBACK_BURST_MULTIPLIER;
            knockBackAccumX = 0f;
            knockBackAccumY = 0f;
            knockBackVectorX = 0;
            knockBackVectorY = 0;
            spawnKnockbackBurst();
        }

        // Sub-pixel accumulation: at high decay the per-frame step can be well under 1px, so we
        // carry the fractional remainder forward instead of losing it to int truncation — the
        // burst still reads as smooth motion as it tails off, not a jump then a dead stop.
        knockBackAccumX += knockBackVelX;
        knockBackAccumY += knockBackVelY;
        int stepX = (int) knockBackAccumX;
        int stepY = (int) knockBackAccumY;
        knockBackAccumX -= stepX;
        knockBackAccumY -= stepY;

        int nextX = worldX + stepX;
        int nextY = worldY + stepY;
        gp.cChecker.checkTileNext(this, nextX, nextY);
        if (!collisionOn) {
            worldX = nextX;
            worldY = nextY;
        }

        // Fast burst that quickly tails off, rather than a constant-speed slide.
        knockBackVelX *= KNOCKBACK_DECAY;
        knockBackVelY *= KNOCKBACK_DECAY;

        double speed = Math.hypot(knockBackVelX, knockBackVelY);
        if (speed < KNOCKBACK_STOP_SPEED || collisionOn) {
            knockBack = false;
            knockBackVelX = 0f;
            knockBackVelY = 0f;
            knockBackAccumX = 0f;
            knockBackAccumY = 0f;
            knockBackRemaining = 0;
            knockBackPower = 0;
            // stop any active chase so monster isn't immediately drawn back
            onPath = false;
        }
        return true;
    }

    /**
     * Small puff of bob1-3 particles at the moment a knockback starts, selling the "impact burst"
     * — a quick scatter roughly opposite the push direction, like debris kicked up by the hit.
     */
    private void spawnKnockbackBurst() {
        if (gp.particlePool == null) return;
        int count = 3;
        for (int i = 0; i < count; i++) {
            Particle p = gp.particlePool.get();
            p.image = Particle.getRandomBob(gp);
            float scatterX = (float) ((Math.random() - 0.5) * gp.tileSize * 0.5);
            float scatterY = (float) ((Math.random() - 0.5) * gp.tileSize * 0.3);
            p.fx = getCenterX() - gp.tileSize / 2f + scatterX;
            p.fy = getCenterY() - gp.tileSize / 2f + scatterY;
            p.worldX = (int) p.fx;
            p.worldY = (int) p.fy;
            // Drift opposite the knockback direction, like kicked-up debris trailing behind the hit.
            p.velocityX = -knockBackVelX * 0.05f + (float) ((Math.random() - 0.5) * 0.1);
            p.velocityY = -knockBackVelY * 0.05f - 0.15f;
            p.size = (int) (gp.tileSize * 0.4);
            p.style = Particle.STYLE_BOB;
            p.life = 16 + (int) (Math.random() * 6);
            p.initialLife = p.life;
            p.alive = true;
            p.generator = this;
            p.depthSortYOffset = -gp.tileSize * 2;
            gp.particleList.add(p);
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
        life = 0;
        hpBarOn = false;
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
    public void changeAlpha(GdxRenderer g2, float alphaValue) {
        g2.setAlpha(alphaValue);
    }
    public Sprite setup(String imagePath, int width, int height) {
        return ResourceCache.loadScaledImageIfPresent(imagePath + ".png", width, height);
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
            waypointCount = 0;
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
                && waypointIdx < waypointCount
                && pathStallCounter < PATH_STALL_LIMIT) {
            followWaypoints();
            return;
        }

        // Goal changed or stalled too long — run A* and cache the result
        pathStallCounter = 0;
        pathCacheGoalCol = goalCol;
        pathCacheGoalRow = goalRow;
        waypointCount = 0;
        waypointIdx = 0;

        gp.pFinder.setNodes(startCol, startRow, goalCol, goalRow, this);
        if (gp.pFinder.search()) {
            int pathSize = gp.pFinder.pathList.size();
            // Grow backing arrays if needed — rare (only when path is longer than previous max)
            if (pathSize > waypointCols.length) {
                int newCap = Math.max(pathSize, waypointCols.length * 2);
                waypointCols = new int[newCap];
                waypointRows = new int[newCap];
            }
            for (int i = 0; i < pathSize; i++) {
                ai.Node nd = gp.pFinder.pathList.get(i);
                waypointCols[i] = nd.col;
                waypointRows[i] = nd.row;
            }
            waypointCount = pathSize;
            if (waypointCount > 0) {
                followWaypoints();
            } else {
                onPath = false;
            }
        } else {
            directChase(goalCol, goalRow);
        }
    }

    private void followWaypoints() {
        if (waypointIdx >= waypointCount) {
            onPath = false;
            waypointCount = 0;
            waypointIdx = 0;
            return;
        }

        int nextX = waypointCols[waypointIdx] * gp.tileSize;
        int nextY = waypointRows[waypointIdx] * gp.tileSize;

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
            if (waypointIdx >= waypointCount) {
                onPath = false;
                waypointCount = 0;
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
    public Sprite[][] loadSheetVariable(String path, int[] framesPerRow) {
        int rowsWanted = framesPerRow.length;
        int maxColsWanted = 0;
        for (int f : framesPerRow) if (f > maxColsWanted) maxColsWanted = f;

        try {
            Sprite sheet = ResourceCache.loadImage(path + ".png");

            // Determine if the sheet is arranged in multiple rows as requested,
            // otherwise treat it as a single-row strip and duplicate that row
            // across the expected directions.
            int rowsActual;
            int cellSize;
            if (sheet.getHeight() % rowsWanted == 0 && sheet.getHeight() / rowsWanted > 0) {
                rowsActual = rowsWanted;
                cellSize = sheet.getHeight() / rowsWanted;
            } else {
                // Fallback: single-row strip
                rowsActual = 1;
                cellSize = sheet.getHeight();
            }

            int colsActual = Math.max(1, sheet.getWidth() / cellSize);

            Sprite[][] frames = new Sprite[rowsWanted][];

            for (int y = 0; y < rowsWanted; y++) {
                int expected = framesPerRow[y];
                if (colsActual <= 0) {
                    // No frames available — leave nulls; draw code guards against null sprites.
                    frames[y] = new Sprite[expected];
                    continue;
                }

                int available = Math.min(expected, colsActual);
                Sprite[] temp = new Sprite[available];

                for (int x = 0; x < available; x++) {
                    int srcRow = (rowsActual == rowsWanted) ? y : 0;
                    int sx = x * cellSize;
                    int sy = srcRow * cellSize;
                    int sw = Math.min(cellSize, sheet.getWidth() - sx);
                    int sh = Math.min(cellSize, sheet.getHeight() - sy);

                    // Zero-copy sub-region (no padding buffer needed on the GPU); report tileSize logical.
                    Sprite crop = sheet.getSubimage(sx, sy, sw, sh);
                    temp[x] = util.UtilityTool.scaleImage(crop, gp.tileSize, gp.tileSize);
                }

                // Fill expected count, pad by duplicating last available frame if needed
                frames[y] = new Sprite[expected];
                for (int x = 0; x < expected; x++) {
                    if (x < temp.length) frames[y][x] = temp[x];
                    else frames[y][x] = temp[temp.length - 1];
                }
            }

            return frames;
        } catch (IOException e) {
            // Fallback to original (scaled full-sheet) behaviour for compatibility
            Sprite sheet = setup(path, gp.tileSize * maxColsWanted, gp.tileSize * rowsWanted);
            Sprite[][] frames = new Sprite[rowsWanted][];

            for (int y = 0; y < rowsWanted; y++) {
                frames[y] = new Sprite[framesPerRow[y]];
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
    }
    public Sprite[][] loadSpriteMatrix(
        String path,
        int spriteWidth,
        int spriteHeight
    ) {

        Sprite sheet;

        try {
            sheet = ResourceCache.loadImage(path + ".png");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load spritesheet: " + path, e);
        }

        // NOTE: `spriteWidth`/`spriteHeight` are expected to be the native
        // cell sizes authored in the spritesheet (e.g. 32x32). Callers should
        // pass Config.originalTileSize for native sheets. If the sheet image
        // dimensions are not exact multiples of the provided cell size, we
        // continue with floor-division but emit a warning to help debugging
        // resource mismatches (e.g. fruit_trader looking incorrect).
        if (sheet.getWidth() % spriteWidth != 0 || sheet.getHeight() % spriteHeight != 0) {
            System.out.println("[Entity] Warning: sprite sheet dimensions not multiples of given cell size for '" + path + "' (sheet: "
                + sheet.getWidth() + "x" + sheet.getHeight() + ", cell: " + spriteWidth + "x" + spriteHeight + ")");
        }

        int columns = Math.max(1, sheet.getWidth() / spriteWidth);
        int rows = Math.max(1, sheet.getHeight() / spriteHeight);
        // Check if there's a partial extra row (e.g. 400px / 128 = 3 but 4th row visible)
        int remainderH = sheet.getHeight() - rows * spriteHeight;
        if (remainderH > spriteHeight / 2) {
            rows++; // include the partial row
        }

        Sprite[][] matrix = new Sprite[rows][columns];

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
                    // Partial row: take the available sub-region but report the full cell as logical
                    // size (GPU pads transparently — no buffer copy needed).
                    matrix[y][x] = sheet.getSubimage(x * spriteWidth, srcY, spriteWidth, srcH)
                            .withLogicalSize(spriteWidth, spriteHeight);
                }
            }
        }

        return matrix;
    }
}

package entity;

import java.util.ArrayList;

 import audio.SFX;
 import gfx.Color;
 import gfx.GdxRenderer;
 import gfx.Sprite;
 import gfx.geom.Rect;
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
    private static final Color COLOR_LEVELUP_MSG = new Color(255, 220, 110);
    private static final Color COLOR_LEVELUP_RING = new Color(160, 200, 255);

    private Sprite[][] hitFrames;    // getHit sprite sheet [dir][frame]
    private Sprite[][] deathFrames;  // death sprite sheet  [dir][frame]
    private Sprite[][] swimFrames;   // swimming sheet [dir][frame] — only 2 frames/direction
    public boolean isSwimming = false; // true while standing in a WaterZone (see EventHandler.isInWaterZone)
    public boolean playerDying = false;     // death animation in progress
    public int playerDeathCounter = 0;      // tick counter for death anim
    public int playerDeathFrame = 0;        // current death frame index
    private int hitAnimCounter = 0;          // tick counter for hit anim
    private int hitAnimFrame = 0;            // current hit frame index
    private int hitAnimDirection = DIR_DOWN;  // locked facing direction for hit anim
    public int deathDirection = DIR_DOWN;    // locked facing direction for death anim
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

    public float spawnFadeAlpha = 1.0f; // fades 0→1 on each map entrance
    private static final float CAM_LERP = 0.15f;
    public float getCamScreenX() { return camScreenX; }
    public float getCamScreenY() { return camScreenY; }
    public ArrayList<Entity> inventory = new ArrayList<>();
    public int hasKey = 0;
    public int hasArtefact = 0;
    public int hasGem = 0;
    public boolean attackCanceled = false;

    private int comboStep = 0;
    private int comboWindow = 0;     // frames remaining to chain next attack
    private static final int COMBO_WINDOW_MAX = 20;
    private boolean attackBuffered = false;

    // Free-aim attack angle (radians, atan2 convention) — independent of the cardinal `direction`
    // field. `direction` stays cardinal for body sprite/frontal-armor/knockback/AI; `attackAngle`
    // drives the cone hitbox, the rotated slice VFX, and the attack kick.
    private double attackAngle = 0.0;
    public gfx.geom.Cone attackCone;
    private static final double ATTACK_CONE_RADIUS_SCALE = 1.35; // × tileSize
    private static final double ATTACK_CONE_HALF_ANGLE = Math.toRadians(55);
    // Knockback power cap for melee hits: total slide distance = power * 16px (see Entity's
    // KNOCKBACK_BURST_MULTIPLIER/KNOCKBACK_DECAY). Keeping it well under the attack cone's own
    // reach (ATTACK_CONE_RADIUS_SCALE * tileSize) means spamming hits on one target can't shove it
    // out of range between swings.
    private static final int MAX_KNOCKBACK_POWER = 2;

    /** Nearest-cardinal snap of a continuous angle, for body-sprite `direction` selection. */
    private static int angleToCardinal(double angleRad) {
        double deg = Math.toDegrees(angleRad);
        int sector = Math.floorMod(Math.round((float) (deg / 90f)), 4);
        return switch (sector) {
            case 0 -> DIR_RIGHT;
            case 1 -> DIR_DOWN;
            case 2 -> DIR_LEFT;
            default -> DIR_UP; // 3
        };
    }

    // Slash VFX — sliceAnim.png is a 2-frame strip (2 cols x 1 row, 32x96 per cell): frame 0 =
    // slash peak, frame 1 = fade/trail. Rotated to attackAngle each swing; native tall/narrow
    // aspect is preserved (uniformly scaled, never stretched independently per axis).
    private Sprite[] sliceFrames; // [0]=peak, [1]=trail
    private static final int SLICE_CELL_W = 32, SLICE_CELL_H = 96;
    // How far in front of the player (× tileSize) the slash VFX is centered. Bump this up to push
    // the crescent farther out from the player.
    private static final float SLICE_VFX_FRONT_DIST_SCALE = 1.1f;
    // Alternates each swing so consecutive attacks mirror each other (default, flipped, default, ...)
    // for a smoother back-and-forth feel. Starts false so the FIRST swing is the default (unflipped)
    // orientation; toggled at swing END so the current swing draws with the value set at its start.
    private boolean sliceFlip = false;

    // Attack "kick" — a brief forward impulse along attackAngle during the swing, collision-checked.
    private float kickOffsetX = 0f, kickOffsetY = 0f;
    private static final float KICK_MAX_PX = 7f;

    // 0..1 across the swing's active frames, -1 when the slice VFX should be hidden.
    private float swingProgress = -1f;

    public int levelUpChoice = 0;
    public String[] levelUpOptions;
    private int[] levelUpValues;
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
    private final int dashCooldownMax = 60; // ~1s cooldown
    private boolean dashParticle = false;
    private int evadeRecovery = 0;         // brief post-evade slowdown (footing)
    private static final int EVADE_RECOVERY_FRAMES = 3;
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

    private final int idleStartDelayFrames = 120;

    // ── Player animations: frames + ms-per-frame bundled in AnimClip (built in get*Images()). ──
    // Player drives its own spriteNum for both walk and idle (draw reads spriteNum), so these are
    // Player-local. walk = LOOP (wrap), idle = LOOP_PINGPONG (bounce), matching the old counters.
    private gfx.AnimClip walkClip, idleClip, hitClip, deathClip;
    private float pWalkStateTime = 0f, pIdleStateTime = 0f;
    private static final float PDT = 1f / 60f;
    private int idleDelayCounter = 0;
    private boolean movingThisFrame = false;
    private boolean wasMovingLastFrame = false;

    // Wind-down: play 2 more walk frames after keys released before going idle
    private boolean windingDown = false;
    private int windDownFramesLeft = 0;
    private int windDownCounter = 0;
    private static final int WIND_DOWN_FRAMES = 2;

    // Physics-based movement: F_D = 0.5 * rho * v^2 * C_D * A
    // rho=1.225 kg/m^3, C_D=1.0, A=0.5 m^2, mass=60 kg — scaled to pixel/frame units.
    private static final float PLAYER_MASS  = 60f;    // kg
    private static final float DRAG_K       = 1.44f;  // 0.5 * rho * C_D * A, tuned to pixel space
    private static final float DRIVE_ACCEL  = 0.4f;   // px/frame^2 drive force per unit mass while key held
    // Pure quadratic drag (k*v^2) vanishes as v->0, so the glide never actually finishes decelerating
    // and the player coasts for seconds. A flat minimum brake keeps the stop crisp at low speed.
    private static final float MIN_STOP_DRAG = 0.18f;  // px/frame^2 floor applied only while coasting
    private float currentSpeed = 0f;
    private float inertiaVelX = 0f;
    private float inertiaVelY = 0f;

    // Wind: a real force vector sampled from the map's WindField, applied as F/mass per frame.
    // Only the component along the player's movement axis is used (tailwind faster / headwind
    // slower) — the sideways component is discarded so the player is never pushed off-course.
    private static final float WIND_FORCE_SCALE = 9.0f; // converts WindField strength (0..1) to px/frame^2 force
    // The "inertia hitbox" — the body area the wind pushes on. Larger than the collision solidArea
    // so it is visually balanced with the on-screen player sprite (a person-sized sail, not just feet).
    private final Rect inertiaArea = new Rect();

    public Player(GamePanel gp, KeyHandler keyH) {
        super(gp);
        this.keyH = keyH;
        screenX = gp.screenWidth / 2 - (gp.tileSize / 2);
        screenY = gp.screenHeight / 2 - (gp.tileSize / 2);
        solidArea = new Rect();
        solidArea.x = gp.tileSize * 20 / 64;   // 20px at 64px tile, scales proportionally
        solidArea.y = gp.tileSize * 22 / 64;   // 22px at 64px tile
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        solidArea.width  = gp.tileSize * 24 / 64;  // 24px at 64px tile
        solidArea.height = gp.tileSize * 22 / 64;  // 22px at 64px tile
        // Inertia/wind hitbox: covers most of the on-screen body so wind pushes on a
        // person-sized area, not just the feet. ~44px wide × ~52px tall at a 64px tile.
        inertiaArea.x = gp.tileSize * 10 / 64;
        inertiaArea.y = gp.tileSize *  8 / 64;
        inertiaArea.width  = gp.tileSize * 44 / 64;
        inertiaArea.height = gp.tileSize * 52 / 64;
        setDefaultValues();
    }

    public void setDefaultValues() {
        worldX = 0;
        worldY = 0;
        defaultSpeed = 5;
        speed = defaultSpeed;
        currentSpeed = 0f;
        inertiaVelX = 0f;
        inertiaVelY = 0f;
        direction = DIR_DOWN;
        level = 1;
        maxLife = 4;
        life = maxLife;
        strenght = 2;
        dexterity = 1;
        exp = 0;
        nextLevelExp = 5;
        coin = 1000;
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
        inventory.clear();
        skillTree.reset();
        projectile = new OBJ_Arrow(gp);
        attack = getAttack();
        defense = getDefense();
        getPlayerImages();
        getPlayerIdleImages();
        getPlayerAttackImages();
        getPlayerHitImages();
        getPlayerDeathImages();
        getPlayerSwimImages();
        setItems();
        setDialogue();
    }

    private void setItems() {
        // Equipment is set but not added to inventory at game start
    }

    private void setDialogue() {
        ensureDialogues()[0][0] = "You are level " + level + " now!\n" + "Your stats have increased, keep going!";
    }

    public void setDefaultPositions() {
        if (gp.mapManager.defaultSpawnCol >= 0 && gp.mapManager.defaultSpawnRow >= 0) {
            // Nudge out of collision if the map's default spawn tile sits inside a wall,
            // so new-game start never leaves the player stuck (matches the MP server).
            int[] sp = gp.mapManager.safeSpawn(
                gp.mapManager.defaultSpawnCol, gp.mapManager.defaultSpawnRow);
            worldX = sp[0] * gp.tileSize;
            worldY = sp[1] * gp.tileSize;
        } else {
            worldX = (int)(gp.tileSize * 24.5);
            worldY = (int)(gp.tileSize * 15.5);
        }
        direction = DIR_DOWN;
        currentSpeed = 0f;
        inertiaVelX = 0f;
        inertiaVelY = 0f;
        snapCamera();
    }

    /** Instantly snaps the smooth camera to its default position — call after a window resize
     *  to avoid the camera lerping from the old position to the new screen centre. */
    public void snapCamera() {
        int mapPixelW = gp.tileM.currentMapCols * gp.tileSize;
        int mapPixelH = gp.tileM.currentMapRows * gp.tileSize;
        int defaultScreenX = gp.screenWidth  / 2 - gp.tileSize / 2;
        int defaultScreenY = gp.screenHeight / 2 - gp.tileSize / 2;

        if (mapPixelW <= gp.screenWidth) {
            camScreenX = worldX + (gp.screenWidth - mapPixelW) / 2f;
        } else {
            int offX = worldX - defaultScreenX;
            if (offX < 0)                               camScreenX = worldX;
            else if (offX > mapPixelW - gp.screenWidth) camScreenX = worldX - (mapPixelW - gp.screenWidth);
            else                                         camScreenX = defaultScreenX;
        }

        if (mapPixelH <= gp.screenHeight) {
            camScreenY = worldY + (gp.screenHeight - mapPixelH) / 2f;
        } else {
            int offY = worldY - defaultScreenY;
            if (offY < 0)                                camScreenY = worldY;
            else if (offY > mapPixelH - gp.screenHeight) camScreenY = worldY - (mapPixelH - gp.screenHeight);
            else                                          camScreenY = defaultScreenY;
        }

        screenX = Math.round(camScreenX);
        screenY = Math.round(camScreenY);
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
    private void getPlayerImages() {
        // Sheet order: down=row0, left=row1, right=row2, up=row3 — maps directly to DIR_DOWN=0,LEFT=1,RIGHT=2,UP=3
        int[] framesPerRow = {8, 8, 8, 7};
        walkFrames = loadSheetVariable("/res/player/player_walking-sheet_test", framesPerRow);
        // 150 ms/frame at default speed; scaled by movement speed at runtime so feet don't slide.
        walkClip = new gfx.AnimClip(walkFrames, 200, com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP);
    }

    public void getPlayerAttackImages() {
        // Unified attack sheet (4 rows × 5 cols, 1-tile each: down/left/right/up) — same sheet
        // reused for all 3 combo steps; only per-step timing (frameDurations in attacking())
        // and the free-aim cone/slice differ, not the body sprite.
        int[] a = {5, 5, 5, 5};
        Sprite[][] raw = loadSheetVariable("/res/player/player_attacking_test", a);
        attackFrames = new Sprite[4][];
        attackFrames[DIR_DOWN]  = raw[0];
        attackFrames[DIR_LEFT]  = raw[1];
        attackFrames[DIR_RIGHT] = raw[2];
        attackFrames[DIR_UP]    = raw[3];
        attackFrames2 = attackFrames;
        attackFrames3 = attackFrames;

        getSliceEffectImages();
    }

    /** sliceAnim.png is a 2-frame strip (2 cols x 1 row, 32x96 per cell) — not square, so it
     *  can't go through loadSheetVariable (which force-scales cells to a square tileSize).
     *  Sliced manually, keeping the native tall/narrow aspect for the rotated VFX quad. */
    private void getSliceEffectImages() {
        Sprite sheet = util.ResourceCache.loadImageIfPresent("/res/effects/sliceAnim.png");
        if (sheet == null) { sliceFrames = null; return; }
        sliceFrames = new Sprite[2];
        sliceFrames[0] = sheet.getSubimage(0, 0, SLICE_CELL_W, SLICE_CELL_H);
        sliceFrames[1] = sheet.getSubimage(SLICE_CELL_W, 0, SLICE_CELL_W, SLICE_CELL_H);
    }

    private void getPlayerHitImages() {
        int[] framesPerRow = {4, 4, 4, 4}; // sheet rows: down, left, up, right
        Sprite[][] frames = loadSheetVariable("/res/player/Player_getHit", framesPerRow);
        hitFrames = new Sprite[4][];
        hitFrames[DIR_UP]    = frames[0]; // row 0 = up
        hitFrames[DIR_RIGHT] = frames[3]; // row 1 = right
        hitFrames[DIR_DOWN]  = frames[1]; // row 2 = down
        hitFrames[DIR_LEFT]  = frames[2]; // row 3 = left
        hitClip = new gfx.AnimClip(hitFrames, 100, com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP); // 100 ms/frame
    }

    /** Hit recovery plays on a raw counter; source its per-frame ticks from hitClip's ms (100 ms = 6 ticks). */
    private int hitTicksPerFrame() { return Math.max(1, Math.round(hitClip.frameMs * 60f / 1000f)); }

    private void getPlayerDeathImages() {
        int[] framesPerRow = {5, 5, 5, 5}; // sheet rows: up, down, left, right
        Sprite[][] frames = loadSheetVariable("/res/player/Player_death", framesPerRow);
        deathFrames = new Sprite[4][];
        deathFrames[DIR_DOWN]  = frames[0]; // row 0 = down
        deathFrames[DIR_RIGHT] = frames[2]; // row 2 = right
        deathFrames[DIR_UP]    = frames[1]; // row 1 = up
        deathFrames[DIR_LEFT]  = frames[3]; // row 3 = left
        deathClip = new gfx.AnimClip(deathFrames, 167, com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP); // 167 ms/frame
    }

    /** Death plays on a raw counter (also drives game-over/fade timing); source its per-frame ticks
     *  from deathClip's ms (167 ms = 10 ticks), so all coupled timing stays in ticks. */
    private int deathTicksPerFrame() { return Math.max(1, Math.round(deathClip.frameMs * 60f / 1000f)); }

    private void getPlayerSwimImages() {
        int[] framesPerRow = {2, 2, 2, 2}; // sheet rows: down, up, left, right (2 frames/dir)
        Sprite[][] frames = loadSheetVariable("/res/player/player_swimming", framesPerRow);
        swimFrames = new Sprite[4][];
        swimFrames[DIR_DOWN]  = frames[1]; // row 0 = down (facing camera)
        swimFrames[DIR_UP]    = frames[0]; // row 1 = up (back of head)
        swimFrames[DIR_LEFT]  = frames[2]; // row 2 = left profile
        swimFrames[DIR_RIGHT] = frames[3]; // row 3 = right profile
    }

    /** Swim sheet only has 2 frames/direction; wrap the normal walk/idle frame counter into that range. */
    private Sprite getSwimFrame(int dir, int frame) {
        if (swimFrames == null || dir < 0 || dir >= swimFrames.length || swimFrames[dir] == null
                || swimFrames[dir].length == 0) return null;
        int len = swimFrames[dir].length;
        int idx = Math.floorMod(frame - 1, len);
        return swimFrames[dir][idx];
    }

    private void getPlayerIdleImages() {
        int[] framesPerRow = {6, 6, 6, 6}; // sheet rows: up, down, left, right
        Sprite[][] frames = loadSheetVariable("/res/player/Player_idle-sheet", framesPerRow);
        idleFrames = new Sprite[4][];
        idleFrames[DIR_UP]    = frames[0]; // row 0 = up
        idleFrames[DIR_DOWN]  = frames[1]; // row 1 = down
        idleFrames[DIR_LEFT]  = frames[2]; // row 2 = left
        idleFrames[DIR_RIGHT] = frames[3]; // row 3 = right
        // 167 ms/frame; LOOP_PINGPONG = the old 1..6..1 bounce.
        idleClip = new gfx.AnimClip(idleFrames, 175, com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP_PINGPONG);
    }

    @Override
    public void update() {
        if (levelUpBannerTimer > 0) levelUpBannerTimer--;

        isSwimming = gp.eHandler.isInWaterZone(
                worldX + solidArea.x, worldY + solidArea.y,
                solidArea.width, solidArea.height);

        if (updateDeathAnimation()) return;   // death consumes the frame
        updateHitAnimation();
        freezeForDialogueOrCutscene();
        if (tickKnockback()) return;          // knockback burst consumes the frame, smooth decay
        tickEnvironmentRecoil();
        updateDashAndEvade();
        tickCooldownsAndTimers();
        handleAbilityInputs();
        tickComboWindow();
        updateGrassOverlapDepthSort();
        if (updateAttackingOrMovement()) return; // returns true when input is locked mid-frame
        updateCamera();
    }

    private static final int GRASS_OVERLAP_DEPTH_BOOST = 10_000;

    private void updateGrassOverlapDepthSort() {
        depthSortYOffset = 0;
        int px = worldX + solidArea.x, py = worldY + solidArea.y;
        int pw = solidArea.width, ph = solidArea.height;
        for (Entity e : gp.iTile) {
            if (e == null || !(e instanceof tile.IT_GrassPatch)) continue;
            int gx = e.worldX + e.solidArea.x, gy = e.worldY + e.solidArea.y;
            if (px < gx + e.solidArea.width && px + pw > gx && py < gy + e.solidArea.height && py + ph > gy) {
                depthSortYOffset = GRASS_OVERLAP_DEPTH_BOOST;
                return;
            }
        }
    }

    /** Advances the death animation and triggers game-over. Returns true while dying (frame consumed). */
    private boolean updateDeathAnimation() {
        if (!playerDying) return false;
        playerDeathCounter++;
        int frameIndex = playerDeathCounter / deathTicksPerFrame();
        if (frameIndex < DEATH_TOTAL_FRAMES) {
            playerDeathFrame = frameIndex;
        } else {
            playerDeathFrame = DEATH_TOTAL_FRAMES - 1; // hold last frame
            // After holding the last frame for DEATH_HOLD_DELAY, trigger game over
            int holdTick = playerDeathCounter - (DEATH_TOTAL_FRAMES * deathTicksPerFrame());
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
        return true; // block all movement/combat during death
    }

    private void updateHitAnimation() {
        if (hitAnimCounter <= 0) return;
        hitAnimCounter--;
        int spd = hitTicksPerFrame();
        hitAnimFrame = (HIT_TOTAL_FRAMES - 1) - (hitAnimCounter / spd);
        if (hitAnimFrame >= HIT_TOTAL_FRAMES) hitAnimFrame = HIT_TOTAL_FRAMES - 1;
        if (hitAnimFrame < 0) hitAnimFrame = 0;
    }

    private void freezeForDialogueOrCutscene() {
        if (gp.gameState != GamePanel.dialogueState && gp.gameState != GamePanel.cutsceneState) return;
        attacking = false;
        spriteCounter = 0;
        spriteNum = 1;
        anticipationTimer = -1;
        animOffsetX = 0;
        animOffsetY = 0;
        idleDelayCounter = 0;
        movingThisFrame = false;
    }

    /**
     * Keeps the player's idle animation playing while dialogue or the inventory/character screen
     * has locked out input — {@link #update()} (and thus {@link #freezeForDialogueOrCutscene()}
     * and {@link #updateIdleSprite()}) doesn't run in those states, so without this the sprite
     * would hold whatever frame it was on the instant the menu opened. Safe to call every frame;
     * does not touch movement/attack state.
     */
    public void tickIdleWhileMenuOpen() {
        attacking = false;
        movingThisFrame = false;
        updateIdleSprite();
    }

    /** Dash trigger, dash physics (with shadow-step strike), and the post-dash evade recovery. */
    private void updateDashAndEvade() {
        if (dashUnlocked && keyH.dashPressed && dashCooldown == 0 && !dashing && !attacking && evadeRecovery == 0) {
            dashing = true;
            dashCounter = dashDuration;
            dashCooldown = getDashCooldownMax();
            invincible = true;
            keyH.dashPressed = false;
            if (isSwimming) spawnSplashBurst(); else spawnBobBurst();
            gp.playSE(SFX.WEAPON_SWING); // quick whoosh
            currentSpeed = defaultSpeed; // start dash from full speed, bypassing ramp-up
            inertiaVelX = 0f;
            inertiaVelY = 0f;
        }
        if (dashing) {
            float t = Math.max(0f, Math.min(1f, (dashDuration - dashCounter) / (float)Math.max(1, dashDuration)));
            float burstStrength = isSwimming ? 1.4f : 3.2f; // dashing through water is much weaker
            float speedMultiplier = burstStrength * (1f - t * t);  // burst → decel
            speed = Math.max(defaultSpeed + 1, Math.round(defaultSpeed * speedMultiplier));
            invincible = true;
            invincibleCounter = 0;  // hold i-frames during evade
            dashCounter--;
            if (dashCounter <= 0) {
                dashing = false;
                speed = Math.max(1, defaultSpeed - 1); // brief heavy landing
                evadeRecovery = EVADE_RECOVERY_FRAMES;
                invincibleCounter = 54;
                if (shadowStepUnlocked) applyShadowStepStrike();
            }
        }
        if (evadeRecovery > 0) {
            evadeRecovery--;
            if (evadeRecovery == 0) {
                speed = defaultSpeed;
            }
        }
    }

    /** On dash end (if unlocked), strike any monsters within 2 tiles of the player. */
    private void applyShadowStepStrike() {
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
                    if (gp.bleSession != null && gp.bleSession.isActive()) {
                        gp.bleSession.sendMobDamage(si, dmg, m.life, m.maxLife);
                    }
                }
            }
        }
        gp.screenShake.shakeLight();
    }

    /** Decrement all per-frame cooldowns/timers and apply the overdrive aura + speed reset. */
    private void tickCooldownsAndTimers() {
        if (dashCooldown > 0) dashCooldown--;
        if (spawnFadeAlpha < 1.0f) spawnFadeAlpha = Math.min(1.0f, spawnFadeAlpha + 0.025f);

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
    }

    private void tickComboWindow() {
        if (comboWindow > 0) comboWindow--;
        if (comboWindow <= 0 && !attacking) comboStep = 0;
    }

    /**
     * The main combat/movement branch. When mid-attack, runs the lunge + coin scan; otherwise runs
     * the full movement/wind/inertia physics, interaction, and mouse/projectile actions.
     * Returns true when input is locked (frame consumed before the camera update).
     */
    private boolean updateAttackingOrMovement() {
        if (attacking && !attackCanceled) {
            updateAttackingState();
            return false;
        }

        if (gp.inputLocked) return true;

        boolean canMove = !rooted;
        boolean movingUp    = canMove && keyH.upPressed;
        boolean movingDown  = canMove && keyH.downPressed;
        boolean movingLeft  = canMove && keyH.leftPressed;
        boolean movingRight = canMove && keyH.rightPressed;
        // During a dash the burst must propel the player even if they've released the movement key
        // (a dash is a committed lunge, not a hold-to-move). Force motion in the facing direction
        // so the dash always travels; without this the speed boost applied above did nothing when
        // no direction key was held, which is why the dash "did nothing".
        if (dashing && canMove && !movingUp && !movingDown && !movingLeft && !movingRight) {
            switch (direction) {
                case DIR_UP    -> movingUp    = true;
                case DIR_DOWN  -> movingDown  = true;
                case DIR_LEFT  -> movingLeft  = true;
                case DIR_RIGHT -> movingRight = true;
            }
        }
        boolean movingH = movingLeft || movingRight;
        boolean movingV = movingUp   || movingDown;
        boolean moving  = movingH    || movingV;
        movingThisFrame = moving;

        if (moving) {
            // Vertical direction takes priority for diagonals (walk up/down anim while strafing)
            if      (movingUp)    direction = DIR_UP;
            else if (movingDown)  direction = DIR_DOWN;
            else if (movingLeft)  direction = DIR_LEFT;
            else if (movingRight) direction = DIR_RIGHT;
        }

        if (moving || keyH.enterPressed) {
            windingDown = false;
            idleDelayCounter = 0;

            // Accelerate currentSpeed toward target speed
            float targetSpeed = speed;
            if (slowed && !dashing) targetSpeed *= 0.5f;
            if (!dashing && isSwimming) {
                targetSpeed *= 0.6f;
            }
            if (dashing) {
                // Dash is a burst, not a driven walk: snap straight to the dash speed. The quadratic
                // air-drag model below has a terminal velocity of ~4 px/frame (0.4 = 0.024*v²), which
                // is BELOW walking speed — so ramping toward it made the dash feel like normal walking.
                // Bypassing drag here is what makes the dash actually lunge forward.
                currentSpeed = targetSpeed;
            } else {
                // Drive force accelerates toward target; air drag (quadratic) opposes motion.
                float drag = DRAG_K * currentSpeed * currentSpeed / PLAYER_MASS;
                currentSpeed = Math.min(targetSpeed, currentSpeed + DRIVE_ACCEL - drag);
            }

            // Wind: project the wind force onto the player's movement direction only.
            // Tailwind (force aligned with motion) adds speed; headwind subtracts it.
            // The perpendicular component is discarded — no sideways shove.
            float windAccel = windForceAlongMovement(movingLeft, movingRight, movingUp, movingDown);
            currentSpeed = Math.max(0.5f, currentSpeed + windAccel);
            // Allow tailwind to push the player past their normal top speed (capped).
            currentSpeed = Math.min(currentSpeed, targetSpeed + Math.max(0f, windAccel) * 6f);

            // Diagonal: scale each axis by 1/√2 so total speed equals cardinal
            boolean diagonal = movingH && movingV;
            int sx = diagonal ? Math.max(1, Math.round(currentSpeed * 0.7071f)) : Math.max(1, Math.round(currentSpeed));
            int sy = sx;

            // Build velocity vector from current keys
            float csX = currentSpeed;
            float csY = currentSpeed;
            if (diagonal) {
                csX = Math.max(0.5f, csX * 0.7071f);
                csY = Math.max(0.5f, csY * 0.7071f);
            }

            inertiaVelX = movingLeft ? -csX : movingRight ? csX : 0f;
            inertiaVelY = movingUp   ? -csY : movingDown  ? csY : 0f;

            if (movingH && !keyH.enterPressed) moveAxis(movingLeft ? DIR_LEFT : DIR_RIGHT, movingLeft ? -sx : sx, 0);
            else if (!movingH) { inertiaVelX = 0f; }

            if (movingV && !keyH.enterPressed) moveAxis(movingUp ? DIR_UP : DIR_DOWN, 0, movingUp ? -sy : sy);
            else if (!movingV) { inertiaVelY = 0f; }

            // Restore final facing direction after axis checks
            if      (movingUp)    direction = DIR_UP;
            else if (movingDown)  direction = DIR_DOWN;
            else if (movingLeft)  direction = DIR_LEFT;
            else if (movingRight) direction = DIR_RIGHT;

            collisionOn = false;
            gp.cChecker.checkTile(this);
            int objIndex = gp.cChecker.checkObject(this, true);
            gp.nearbyInteractable = null;
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
                attackAngle = main.MouseHandler.angleForDirection(direction);
                // Cycle 0 -> 1 -> 2 -> 0 -> ... while chained fast enough (comboWindow > 0); a fresh
                // swing after the window lapsed also starts back at 0. Without the wraparound, spam-
                // clicking fast enough to keep re-opening the window (every hit does) would get stuck
                // at step 2 forever instead of resetting like a real 3-hit combo.
                comboStep = (comboWindow > 0) ? (comboStep + 1) % 3 : 0;
                gp.playSE(SFX.WEAPON_SWING);
            }
            attackCanceled = false;
            attackBuffered = false;
            keyH.enterPressed = false;
            if (!wasMovingLastFrame) {
                if (!isSwimming) spawnBobParticle(1);
                footstepParticleCounter = 0;
                pWalkStateTime = 0f; // restart the walk cycle cleanly on movement start
            }
            wasMovingLastFrame = true;
            updateSprite();
            if (!dashing && !isSwimming) {
                footstepParticleCounter++;
                int bobInterval = Math.max(2, 48 / Math.max(1, speed)) * 4;
                if (footstepParticleCounter >= bobInterval) {
                    footstepParticleCounter = 0;
                    spawnBobParticle(1);
                }
            }

        } else {
            footstepParticleCounter = 0;
            applyInertiaGlide();
            updateWindDownOrIdle();
        }

        fireMouseAttackIfRequested();
        fireProjectileIfRequested();
        updateInvincibility();
        return false;
    }

    /** Mid-attack: run the lunge (via attacking()), bleed off walk speed, and scan for coins under the lunge. */
    private void updateAttackingState() {
        if (keyH.enterPressed) {
            attackBuffered = true;
            keyH.enterPressed = false;
        }
        attacking();
        // While attacking the player STOPS walking — no key-driven movement; only the lunge from
        // attacking() carries them forward. Speed is bled off so that resuming walking after the
        // swing has to ramp back up from near-zero (see post-attack ramp below).
        currentSpeed = Math.min(currentSpeed, defaultSpeed * 0.25f);
        movingThisFrame = false;
        // Still scan the tile/entity the player is standing on so pickups/coins under the lunge
        // register, but don't apply any positional key movement.
        collisionOn = false;
        int iTileIndex = gp.cChecker.checkEntity(this, gp.iTile);
        if (iTileIndex != 999 && gp.iTile[iTileIndex] != null
                && gp.iTile[iTileIndex] instanceof tile.IT_Coins) {
            tile.IT_Coins coinTile = (tile.IT_Coins) gp.iTile[iTileIndex];
            coin += coinTile.coinValue;
            gp.playSE(SFX.GOT_GEM);
            generateParticle(coinTile, coinTile);
            gp.iTile[iTileIndex] = null;
        }
    }

    /** When not pressing a direction: coast on air-drag using the last inertia vector, collision-checked. */
    private void applyInertiaGlide() {
        // Air-drag deceleration: F_D = k*v^2, a = -F_D/m
        if (currentSpeed <= 0f) return;
        float drag = Math.max(MIN_STOP_DRAG, DRAG_K * currentSpeed * currentSpeed / PLAYER_MASS);
        currentSpeed = Math.max(0f, currentSpeed - drag);
        if (currentSpeed > 0f && (Math.abs(inertiaVelX) > 0f || Math.abs(inertiaVelY) > 0f)) {
            float mag = (float) Math.sqrt(inertiaVelX * inertiaVelX + inertiaVelY * inertiaVelY);
            float nx = inertiaVelX / mag;
            float ny = inertiaVelY / mag;
            int ivx = Math.round(nx * currentSpeed);
            int ivy = Math.round(ny * currentSpeed);

            if (ivx != 0) {
                int savedDir = direction;
                direction = ivx < 0 ? DIR_LEFT : DIR_RIGHT;
                collisionOn = false;
                gp.cChecker.checkTile(this);
                gp.cChecker.checkObject(this, false);
                gp.cChecker.checkEntity(this, gp.npc);
                gp.cChecker.checkEntity(this, gp.monster);
                gp.cChecker.checkEntity(this, gp.iTile);
                if (!collisionOn) worldX += ivx;
                else { inertiaVelX = 0f; }
                direction = savedDir;
            }
            if (ivy != 0) {
                int savedDir = direction;
                direction = ivy < 0 ? DIR_UP : DIR_DOWN;
                collisionOn = false;
                gp.cChecker.checkTile(this);
                gp.cChecker.checkObject(this, false);
                gp.cChecker.checkEntity(this, gp.npc);
                gp.cChecker.checkEntity(this, gp.monster);
                gp.cChecker.checkEntity(this, gp.iTile);
                if (!collisionOn) worldY += ivy;
                else { inertiaVelY = 0f; }
                direction = savedDir;
            }
        }
    }

    /** After stopping: play the brief wind-down walk frames, then fall into the delayed idle bounce. */
    private void updateWindDownOrIdle() {
        // Wind-down: trigger whenever we just stopped moving, regardless of which frame we're on
        if (!windingDown && wasMovingLastFrame) {
            windingDown = true;
            windDownFramesLeft = WIND_DOWN_FRAMES;
            windDownCounter = 0;
        }
        wasMovingLastFrame = false;

        if (windingDown) {
            int interval = Math.max(1, 32 / Math.max(1, speed));
            windDownCounter++;
            if (windDownCounter > interval) {
                windDownCounter = 0;
                int frameCount = (walkFrames != null && walkFrames[direction] != null)
                        ? walkFrames[direction].length : 7;
                spriteNum = (spriteNum % frameCount) + 1;
                windDownFramesLeft--;
            }
            if (windDownFramesLeft <= 0) {
                windingDown = false;
                spriteNum = 1;
                idleDelayCounter = 0;
            }
        } else {
            idleDelayCounter++;
            if (idleDelayCounter >= idleStartDelayFrames) {
                updateIdleSprite();
            } else {
                spriteNum = 1;
            }
        }
    }

    /** Left-click free-aim attack (suppressed while the wind painter is active). */
    private void fireMouseAttackIfRequested() {
        // Mouse left-click attack — fires whether standing still or moving
        // (suppressed while the wind painter is active: the mouse is painting, not attacking)
        boolean windPainting = gp.windPainter != null && gp.windPainter.isActive();
        if (windPainting) gp.mouseH.leftClicked = false;
        if (gp.mouseH.leftClicked && !attacking && !dashing && currentWeapon != null) {
            gp.mouseH.leftClicked = false;
            attackAngle = gp.mouseH.getAttackAngleFromMouse();
            direction = angleToCardinal(attackAngle); // body sprite snaps to nearest cardinal
            attacking = true;
            spriteCounter = 0;
            windingDown = false;
            // See the enter-key attack site for why this wraps 2 -> 0 instead of clamping at 2.
            comboStep = (comboWindow > 0) ? (comboStep + 1) % 3 : 0;
            gp.playSE(SFX.WEAPON_SWING);
        }
    }

    private void fireProjectileIfRequested() {
        if (gp.keyH.shotKeyPressed) {
            System.out.println("[DBG] fireProjectileIfRequested: shotKeyPressed=true alive=" + projectile.alive
                + " counter=" + shotAvailableCounter + " mana=" + mana
                + " haveResource=" + projectile.haveResource(this));
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
    }

    // Debug-menu cheat: holds invincible true indefinitely, bypassing the normal i-frame timeout.
    public boolean godMode = false;

    private void updateInvincibility() {
        if (godMode) {
            invincible = true;
            invincibleCounter = 0;
            return;
        }
        if (invincible) {
            invincibleCounter++;
            if (invincibleCounter > 60) {
                invincible = false;
                invincibleCounter = 0;
            }
        }
    }

    /** Follow the player with a dead-zone lerp, clamped to the map bounds; writes screenX/screenY. */
    private void updateCamera() {
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

    private void moveAxis(int axisDir, int dx, int dy) {
        int savedDir = direction;
        direction = axisDir;
        collisionOn = false;
        gp.cChecker.checkTile(this);
        gp.cChecker.checkObject(this, false);
        gp.cChecker.checkEntity(this, gp.npc);
        gp.cChecker.checkEntity(this, gp.monster);
        gp.cChecker.checkEntity(this, gp.iTile);
        if (!collisionOn) {
            worldX += dx;
            worldY += dy;
        } else {
            if (dx != 0) inertiaVelX = 0f;
            if (dy != 0) inertiaVelY = 0f;
        }
        direction = savedDir;
    }

    /**
     * Returns the wind force (as a per-frame acceleration, px/frame²) projected onto the
     * player's current movement direction. Positive = tailwind (speed up), negative = headwind
     * (slow down). The perpendicular component is intentionally dropped so wind never pushes the
     * player sideways. Force is sampled over the enlarged {@link #inertiaArea} and divided by mass.
     */
    private float windForceAlongMovement(boolean left, boolean right, boolean up, boolean down) {
        if (gp.windField == null) return 0f;
        // Sample wind at the centre of the inertia hitbox (the body, not the feet).
        int sampleX = worldX + inertiaArea.x + inertiaArea.width  / 2;
        int sampleY = worldY + inertiaArea.y + inertiaArea.height / 2;
        float wfx = gp.windField.sampleX(sampleX, sampleY);
        float wfy = gp.windField.sampleY(sampleX, sampleY);
        if (wfx == 0f && wfy == 0f) return 0f;

        // Unit movement vector from current keys.
        float mvx = (left ? -1f : 0f) + (right ? 1f : 0f);
        float mvy = (up   ? -1f : 0f) + (down  ? 1f : 0f);
        float mag = (float) Math.sqrt(mvx * mvx + mvy * mvy);
        if (mag == 0f) return 0f;
        mvx /= mag; mvy /= mag;

        // Dot product = component of the wind force along the movement direction.
        float along = wfx * mvx + wfy * mvy;           // in WindField strength units
        return (along * WIND_FORCE_SCALE) / PLAYER_MASS; // F/m → acceleration
    }

    private void updateSprite() {
        // Walk cadence tied to movement speed so feet never slide (baseline 150 ms at speed 5).
        walkClip.setFrameMs(walkMsForSpeed());
        pWalkStateTime += PDT;
        spriteNum = walkClip.frameIndex(direction, pWalkStateTime) + 1;
    }

    /** Walk baseline 150 ms at speed 5 (=9 ticks). Scale inversely with speed, same law as before. */
    private int walkMsForSpeed() {
        int ticks = Math.max(2, 48 / Math.max(1, speed));
        return Math.round(ticks * 1000f / 60f);
    }

    private void updateIdleSprite() {
        // Idle cycle: LOOP_PINGPONG = the old 1..6..1 bounce.
        if (idleClip == null || idleClip.frameCount(direction) == 0) return;
        pIdleStateTime += PDT;
        spriteNum = idleClip.frameIndex(direction, pIdleStateTime) + 1;
    }

    private void attacking() {
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
            animOffsetX = 0;
            animOffsetY = 0;
            kickOffsetX = 0f;
            kickOffsetY = 0f;
            swingProgress = -1f; // hides the slice VFX during anticipation
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
        float kickMax = (comboStep == 2) ? KICK_MAX_PX * 1.5f : KICK_MAX_PX;

        // Lunge driven off the CONTINUOUS spriteCounter (ticks every 60Hz frame) rather than the coarse
        // integer currentFrame, so the push interpolates smoothly instead of snapping in ~3 steps. The
        // out-phase spans the first ~65% of the swing; a smoothstep (ease-in-out) accelerates then
        // decelerates so it glides into the final position. After that it HOLDS (Moonshire lunge — keep
        // the ground gained; only the visual lean recovers, worldX/Y stay put, no snap-back).
        float swingT = totalDuration > 0 ? Math.min(1f, spriteCounter / (float) totalDuration) : 1f;
        float outEnd = 0.85f; // spread the push across most of the swing for a longer, softer glide
        float lungeT = Math.min(1f, swingT / outEnd);
        // smootherstep (6t^5-15t^4+10t^3): flatter start AND flatter arrival than smoothstep, so the
        // lunge eases in and settles without any abrupt stop at the end.
        float eased = lungeT * lungeT * lungeT * (lungeT * (lungeT * 6f - 15f) + 10f);
        applyAttackKick(kickMax * eased);

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

        // Slash VFX progress spans the swing window (frames 2..4); -1 outside it (hidden).
        swingProgress = (currentFrame >= 2 && currentFrame <= 4)
            ? Math.min(1f, (currentFrame - 2) / 2f) : -1f;

        int frame3Start = durations[0] + durations[1] + 1;
        if (currentFrame == 3 && spriteCounter == frame3Start) {
            performAttackHitbox();
        }
        if (spriteCounter >= totalDuration) {
            spriteCounter = 0;
            attacking = false;
            sliceFlip = !sliceFlip; // next swing mirrors this one (default, flipped, default, ...)
            anticipationTimer = -1;
            animOffsetX = 0;
            animOffsetY = 0;
            kickOffsetX = 0f;
            kickOffsetY = 0f;
            swingProgress = -1f;
            attackCone = null; // clear so debug draw never shows a stale cone after the swing ends
            comboWindow = (comboStep == 2) ? 10 : COMBO_WINDOW_MAX;
        }
    }

    /** Eases the player a few px forward along attackAngle during the swing, wall-checked.
     *  Uses checkTileNext (tests a hypothetical position without depending on cardinal
     *  `direction`) since the kick vector is a free angle, not one of the 4 cardinals. */
    private void applyAttackKick(float targetPx) {
        float kx = (float) (Math.cos(attackAngle) * targetPx);
        float ky = (float) (Math.sin(attackAngle) * targetPx);
        int dxPx = Math.round(kx - kickOffsetX);
        int dyPx = Math.round(ky - kickOffsetY);
        // Also block the lunge against solid entities (objects/interactive tiles/NPCs/monsters), not
        // just static map tiles — checkTileNext alone let the lunge push the player straight through
        // e.g. a chest or an unbroken destructible it was attacking, since only tile geometry was
        // guarded against.
        if (dxPx != 0) {
            gp.cChecker.checkTileNext(this, worldX + dxPx, worldY);
            if (!collisionOn && !gp.cChecker.isSolidAt(this, worldX + dxPx, worldY)) {
                worldX += dxPx; kickOffsetX += dxPx;
            }
        }
        if (dyPx != 0) {
            gp.cChecker.checkTileNext(this, worldX, worldY + dyPx);
            if (!collisionOn && !gp.cChecker.isSolidAt(this, worldX, worldY + dyPx)) {
                worldY += dyPx; kickOffsetY += dyPx;
            }
        }
        collisionOn = false;
    }

    // Lazily loaded so nothing breaks before the asset exists — ResourceCache.loadImageIfPresent
    // already tolerates a missing file (returns null), same pattern as sliceAnim.png above.
    private static Sprite[] strikeImpactFrames;
    private static boolean strikeImpactLoadAttempted = false;

    // Fixed visual size/lifetime for the wall-impact stamp — same punch on every hit, no distance
    // scaling, so it always reads clearly.
    private static final float IMPACT_SIZE_SCALE = 1.5f;
    private static final int IMPACT_LIFE_TICKS = 18;

    // The debug cone-visual (drawAttackConeDebug) draws the swing as a fan of 17 ticks spanning the
    // full ATTACK_CONE_HALF_ANGLE spread. Only a graze near the outer edge of that fan shouldn't be
    // "enough" to trigger the wall impact/recoil — narrow the check to roughly the middle 3 of those
    // 17 ticks (so only a near-direct hit on the aim direction counts), by running the environment-hit
    // test against a slimmer cone. Tune by changing this one fraction.
    private static final double ENVIRONMENT_HIT_HALF_ANGLE_FRACTION = 3.0 / 16.0;

    private void performAttackHitbox() {
        double radius = gp.tileSize * ATTACK_CONE_RADIUS_SCALE;
        attackCone = new gfx.geom.Cone(getCenterX(), getCenterY(), radius, attackAngle, ATTACK_CONE_HALF_ANGLE);

        int iTileIndex = gp.cChecker.checkEntityCone(attackCone, gp.iTile);
        damageInteractiveTile(iTileIndex);
        if (iTileIndex != 999 && gp.iTile[iTileIndex] != null) {
            gp.iTile[iTileIndex].onAttackHit(this);
        }
        for (int idx : gp.cChecker.checkEntityConeAll(attackCone, gp.monster)) {
            damageMonster(idx, attack);
        }

        // Swinging into a solid, non-monster collision (a wall, Tiled collision shape, object, NPC)
        // bounces the player back and stamps the strike-impact animation at the tip of the attack
        // cone. Monsters are excluded — hitting one already knocks THEM back via damageMonster above;
        // this is purely for "you swung into something solid that didn't take the hit". Checked
        // against a narrower cone than the actual swing (see ENVIRONMENT_HIT_HALF_ANGLE_FRACTION)
        // so only a hit near the aim direction counts, not a graze at the swing's outer edge.
        gfx.geom.Cone environmentHitCone = new gfx.geom.Cone(getCenterX(), getCenterY(), radius,
                attackAngle, ATTACK_CONE_HALF_ANGLE * ENVIRONMENT_HIT_HALF_ANGLE_FRACTION);
        gfx.geom.Rect contact = new gfx.geom.Rect();
        if (gp.cChecker.checkAttackEnvironmentHit(environmentHitCone, contact)) {
            spawnStrikeImpact(contact.x, contact.y);
            startEnvironmentRecoil();
            gp.screenShake.shake(3f, 8);
            gp.triggerHitstop(2);
        }
    }

    // Environment-recoil state: a short, eased multi-frame push away from whatever the swing hit,
    // instead of one instant worldX/Y snap (which read as a teleport, not a "bounced off it" push).
    private double recoilTotalPx = 0;
    private double recoilAngle = 0;
    private int recoilTicksTotal = 0;
    private int recoilTicksLeft = 0;
    private double recoilPxDoneSoFar = 0;

    /**
     * Kick off a short recoil away from the attack direction — same push on every hit, regardless of
     * how far into the swing's reach it landed. This is a separate, simpler linear-ease push (see
     * tickEnvironmentRecoil) used only for "swung into a wall" feedback; actual hit knockback (being
     * struck by a monster/hazard) goes through the shared knockBackVectorX/Y + tickKnockback() burst
     * instead (see Player.knockBack() / Entity.tickKnockback()).
     */
    private void startEnvironmentRecoil() {
        recoilTotalPx = gp.tileSize * 0.34;
        recoilAngle = attackAngle + Math.PI; // straight back, away from the swing direction
        recoilTicksTotal = 10;
        recoilTicksLeft = recoilTicksTotal;
        recoilPxDoneSoFar = 0;
    }

    /** Advances the environment-recoil push by one frame — smootherstep (fast start, soft settle). */
    private void tickEnvironmentRecoil() {
        if (recoilTicksLeft <= 0) return;
        int elapsed = recoilTicksTotal - recoilTicksLeft;
        float tPrev = elapsed / (float) recoilTicksTotal;
        float tNow = (elapsed + 1) / (float) recoilTicksTotal;
        // Smootherstep (6t^5-15t^4+10t^3), same curve the attack lunge itself uses (applyAttackKick)
        // so this recoil reads consistently with the rest of the combat feel — flatter start AND
        // flatter arrival than a plain cubic ease-out, settling without any abrupt stop.
        double easedPrev = tPrev * tPrev * tPrev * (tPrev * (tPrev * 6f - 15f) + 10f);
        double easedNow = tNow * tNow * tNow * (tNow * (tNow * 6f - 15f) + 10f);
        int stepPx = (int) Math.round(recoilTotalPx * (easedNow - easedPrev) + recoilPxDoneSoFar);
        recoilPxDoneSoFar = recoilTotalPx * (easedNow - easedPrev) + recoilPxDoneSoFar - stepPx;

        int dxPx = (int) Math.round(Math.cos(recoilAngle) * stepPx);
        int dyPx = (int) Math.round(Math.sin(recoilAngle) * stepPx);
        if (dxPx != 0) {
            gp.cChecker.checkTileNext(this, worldX + dxPx, worldY);
            if (!collisionOn && !gp.cChecker.isSolidAt(this, worldX + dxPx, worldY)) worldX += dxPx;
        }
        if (dyPx != 0) {
            gp.cChecker.checkTileNext(this, worldX, worldY + dyPx);
            if (!collisionOn && !gp.cChecker.isSolidAt(this, worldX, worldY + dyPx)) worldY += dyPx;
        }
        collisionOn = false;
        recoilTicksLeft--;
    }

    private static final int STRIKE_IMPACT_FRAME_COUNT = 3;

    /** Stamps the Strike_impact.png flipbook at a fixed world point (attack cone tip), fixed size. */
    private void spawnStrikeImpact(int worldPx, int worldPy) {
        if (!strikeImpactLoadAttempted) {
            strikeImpactLoadAttempted = true;
            Sprite sheet = util.ResourceCache.loadImageIfPresent("/res/effects/Strike_impact.png");
            if (sheet != null) {
                int frameW = sheet.getWidth() / STRIKE_IMPACT_FRAME_COUNT;
                strikeImpactFrames = new Sprite[STRIKE_IMPACT_FRAME_COUNT];
                for (int i = 0; i < STRIKE_IMPACT_FRAME_COUNT; i++) {
                    strikeImpactFrames[i] = sheet.getSubimage(i * frameW, 0, frameW, sheet.getHeight());
                }
            }
        }
        if (strikeImpactFrames == null || gp.particlePool == null) return;
        Particle p = gp.particlePool.get();
        int size = Math.max(1, Math.round(gp.tileSize * IMPACT_SIZE_SCALE));
        p.setAsImpact(null, strikeImpactFrames, worldPx, worldPy, size, IMPACT_LIFE_TICKS);
        // Tall multi-tile structures (cliffs, trees) share ONE sortY across their whole sprite (their
        // base row's), which can sit far below the contact point painted mid-face — a plain worldY-
        // based key isn't enough to guarantee drawing in front of those. Push it forward hard, same
        // "always draw on top of what's near it" trick the codebase already uses elsewhere via
        // depthSortYOffset, so the stamp reliably reads as painted ON the surface it hit.
        p.depthSortYOffset = gp.tileSize * 4;
        gp.particleList.add(p);
    }

    // Combat methods
    public void damageMonster(int i, int attack) {        if (i != 999) {
            if (gp.monster[i].tryDodgeIncomingHit()) return;
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
                // Cap total knockback travel so a hit can never shove the target past the attack
                // cone's own reach — otherwise spamming attacks on the same target could launch it
                // just out of range between swings, whiffing the follow-up with no visible cause.
                kb = Math.min(kb, MAX_KNOCKBACK_POWER);
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
                if (gp.bleSession != null && gp.bleSession.isActive()) {
                    gp.bleSession.sendMobDamage(i, damage, gp.monster[i].life, gp.monster[i].maxLife);
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
                if (gp.bleSession != null && gp.bleSession.isActive()) {
                    gp.bleSession.sendMobDamage(i, damage, m.life, m.maxLife);
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
                if (gp.bleSession != null && gp.bleSession.isActive()) {
                    gp.bleSession.sendMobDamage(i, damage, m.life, m.maxLife);
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
        if ((gp.mpClient != null && gp.mpClient.isConnected())
                || (gp.bleSession != null && gp.bleSession.isActive())) {
            for (int i = 0; i < gp.monster.length; i++) {
                if (gp.monster[i] == monster) {
                    if (gp.mpClient != null && gp.mpClient.isConnected()) gp.mpClient.sendMobDeath(i, monster.name);
                    if (gp.bleSession != null && gp.bleSession.isActive()) gp.bleSession.sendMobDeath(i);
                    break;
                }
            }
        }
    }

    /**
     * Apply knockback to a target entity: pushed in the direction the player is currently facing,
     * at a speed equal to `power` pixels per frame, travelling a distance proportional to power.
     * Same push on every hit regardless of how far into the attack's reach it landed.
     *
     * @param entity victim
     * @param power  strength (larger means farther/longer)
     * @param srcX   unused (kept for call-site compatibility)
     * @param srcY   unused (kept for call-site compatibility)
     */
    public void knockBack(Entity entity, int power, int srcX, int srcY) {
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

    private void contactMonster(int i) {
        if (i != 999 && !invincible) {
            // ignore dying/dead monsters entirely
            if (gp.monster[i].dying || !gp.monster[i].alive) {
                return;
            }
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
            if (damage < 1) {
                damage = 1;
            }

            // monster hit power is roughly its attack value (smaller)
            int kb = (gp.monster[i].attack + 1) / 2;
            if (kb < 1) kb = 1;
            // Last Stand: immune to knockback when below 20% HP
            if (lastStandUnlocked && life > 0 && life <= maxLife * 0.2f) {
                kb = 0;
            }

            onHitByEnemy(damage, gp.monster[i].worldX, gp.monster[i].worldY, kb);

            // Thorns: reflect 2 damage to attacker
            if (thornsUnlocked) {
                gp.monster[i].life -= 2;
                gp.monster[i].hitFlashCounter = 4;
                if (gp.monster[i].life <= 0) {
                    killMonster(gp.monster[i]);
                }
            }
        }
    }

    /**
     * Everything that should happen, in one place, whenever ANY enemy source damages the player:
     * sound, blood, floating damage number, hit-flash + hit-pose animation, screen shake, hitstop,
     * and a knockback away from where the hit came from. Called from every player-damage path
     * (monster contact, generic Entity.checkCollision, projectiles, traps, boss attacks) so tuning
     * "how a hit feels" only ever needs to change here. Faces the player away from the hit.
     *
     * @param damage        already-final damage amount (defense/perks already applied)
     * @param sourceWorldX  world X the hit came from, used to face/knock the player away from it
     * @param sourceWorldY  world Y the hit came from
     * @param knockbackPower 0 to skip knockback entirely (e.g. traps, or Last Stand immunity)
     */
    public void onHitByEnemy(int damage, int sourceWorldX, int sourceWorldY, int knockbackPower) {
        onHitByEnemy(damage, sourceWorldX, sourceWorldY, knockbackPower, true);
    }

    /** Same as {@link #onHitByEnemy(int, int, int, int)}, with control over whether it turns the
     *  player to face away from the hit — false keeps whatever direction they were already facing
     *  (e.g. still walking into a hazard), while the knockback itself still pushes away from the
     *  source regardless. */
    public void onHitByEnemy(int damage, int sourceWorldX, int sourceWorldY, int knockbackPower, boolean faceAwayFromHit) {
        gp.playSE(SFX.PLAYER_HIT);
        bleed();
        spawnDamageNumber(this, damage, false);
        life -= damage;
        invincible = true;

        hitFlashCounter = 6;
        hitAnimCounter = HIT_TOTAL_FRAMES * hitTicksPerFrame(); // start hit sprite animation
        hitAnimFrame = 0;
        hitAnimDirection = direction; // lock current facing for hit animation
        gp.screenShake.shakeMedium();
        gp.triggerHitstop(3);

        if (faceAwayFromHit) {
            int dx = worldX - sourceWorldX;
            int dy = worldY - sourceWorldY;
            if (Math.abs(dx) > Math.abs(dy)) {
                direction = (dx > 0) ? DIR_RIGHT : DIR_LEFT;
            } else {
                direction = (dy > 0) ? DIR_DOWN : DIR_UP;
            }
        }
        // Knockback always pushes straight away from the source point, independent of facing.
        if (knockbackPower > 0) knockBackAwayFrom(sourceWorldX, sourceWorldY, knockbackPower);
    }

    /** Pushes the player directly away from (srcX, srcY), at any angle — unlike knockBack(), which
     *  only pushes along the player's current cardinal facing. */
    private void knockBackAwayFrom(int srcX, int srcY, int power) {
        double dx = worldX - srcX;
        double dy = worldY - srcY;
        double dist = Math.hypot(dx, dy);
        if (dist < 0.01) { dx = 1; dy = 0; dist = 1; } // degenerate case: same point, pick a direction
        knockBackVectorX = (int) Math.round(dx / dist * power);
        knockBackVectorY = (int) Math.round(dy / dist * power);
        knockBackRemaining = power * (gp.tileSize / 4);
        knockBackPower = power;
        knockBack = true;
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


    private void spawnBobBurst() {
        // Semi-circle of 5 bobs fanning out behind/around the player on dash start.
        // Base angle points opposite to movement direction; particles spread ±70° from it.
        double baseAngle;
        switch (direction) {
            case DIR_UP    -> baseAngle = Math.PI / 2;   // burst downward
            case DIR_DOWN  -> baseAngle = -Math.PI / 2;  // burst upward
            case DIR_LEFT  -> baseAngle = 0;              // burst rightward
            default        -> baseAngle = Math.PI;        // burst leftward (DIR_RIGHT)
        }
        int count = 5;
        float cx = worldX + gp.tileSize / 2f;
        float cy = worldY + gp.tileSize - 20f;
        float spread = (float)(Math.PI * 0.7); // ±70° total spread (~140°)
        for (int i = 0; i < count; i++) {
            double angle = baseAngle + spread * (i / (float)(count - 1) - 0.5f);
            float radius = 4 + (float)(Math.random() * 5); // 4–9 px offset
            Particle p = gp.particlePool.get();
            p.image = Particle.getRandomBob(gp);
            p.fx = cx + (float)Math.cos(angle) * radius - 9;
            p.fy = cy + (float)Math.sin(angle) * radius;
            p.worldX = (int) p.fx;
            p.worldY = (int) p.fy;
            p.velocityX = (float)Math.cos(angle) * 0.9f;
            p.velocityY = (float)Math.sin(angle) * 0.9f - 0.3f;
            p.size = 26 + (int)(Math.random() * 8);
            p.style = Particle.STYLE_BOB;
            p.life = 22 + (int)(Math.random() * 10);
            p.initialLife = p.life;
            p.alive = true;
            p.generator = this;
            p.depthSortYOffset = -gp.tileSize * 2;
            gp.particleList.add(p);
        }
    }

    /** Splash burst for dashing through water — same fan layout as spawnBobBurst() but more
     *  particles, color-only (no bob sprite), and splashy outward-then-falling physics. */
    private void spawnSplashBurst() {
        double baseAngle;
        switch (direction) {
            case DIR_UP    -> baseAngle = Math.PI / 2;   // burst downward
            case DIR_DOWN  -> baseAngle = -Math.PI / 2;  // burst upward
            case DIR_LEFT  -> baseAngle = 0;              // burst rightward
            default        -> baseAngle = Math.PI;        // burst leftward (DIR_RIGHT)
        }
        int count = 9;
        float cx = worldX + gp.tileSize / 2f;
        float cy = worldY + gp.tileSize - 20f;
        float spread = (float)(Math.PI * 0.9); // wider spread than the dust bob burst
        for (int i = 0; i < count; i++) {
            double angle = baseAngle + spread * (i / (float)(count - 1) - 0.5f);
            float radius = 3 + (float)(Math.random() * 6);
            Particle p = gp.particlePool.get();
            p.image = null;
            p.color = Math.random() < 0.5 ? new gfx.Color(210, 235, 255) : new gfx.Color(90, 150, 210);
            p.fx = cx + (float)Math.cos(angle) * radius - 3;
            p.fy = cy + (float)Math.sin(angle) * radius;
            p.worldX = (int) p.fx;
            p.worldY = (int) p.fy;
            p.velocityX = (float)Math.cos(angle) * 1.6f;
            p.velocityY = (float)Math.sin(angle) * 1.6f - 1.0f; // sharper pop than the dust bob
            p.size = 5 + (int)(Math.random() * 4);
            p.style = Particle.STYLE_SPLASH;
            p.life = 16 + (int)(Math.random() * 8);
            p.initialLife = p.life;
            p.alive = true;
            p.generator = this;
            p.depthSortYOffset = -gp.tileSize * 2;
            gp.particleList.add(p);
        }
    }

    private void spawnBobParticle(int count) {
        for (int i = 0; i < count; i++) {
            Particle p = gp.particlePool.get();
            p.image = Particle.getRandomBob(gp);
            float sideX = (float)((Math.random() - 0.5) * 10);
            float sideY = (float)((Math.random() - 0.5) * 4);
            // Feet position: bottom-center of tile, slightly raised from pixel-bottom so bobs sit at ankle level
            p.fx = worldX + gp.tileSize / 2f - 9 + sideX;
            p.fy = worldY + gp.tileSize - 12 + sideY;
            p.worldX = (int) p.fx;
            p.worldY = (int) p.fy;
            p.velocityX = sideX * 0.06f;
            p.velocityY = -0.25f - (float)(Math.random() * 0.15f);
            p.size = 18;
            p.style = Particle.STYLE_BOB;
            p.life = 26 + (int)(Math.random() * 8);
            p.initialLife = p.life;
            p.alive = true;
            p.generator = this;
            p.depthSortYOffset = -gp.tileSize * 2;
            gp.particleList.add(p);
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
    private void pickUpObject(int i) {
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

    private void interactNPC(int i) {
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

    private void damageInteractiveTile(int i) {
        if (i != 999 && gp.iTile[i].destructible && gp.iTile[i].isCorrectItem(this) && !gp.iTile[i].invincible) {
            Entity tile = gp.iTile[i];
            gp.iTile[i] = null;
            if (tile instanceof tile.IT_GrassPatch grassPatch) {
                grassPatch.spawnDestroyBurst();
            } else if (tile instanceof tile.Breakable breakable) {
                breakable.spawnDestroyBurst();
            } else {
                generateParticle(tile, tile);
            }
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
            unequipIfCurrent(item);
            inventory.remove(itemIndex);
            gp.ui.addMessage("Dropped " + item.name + ".", Color.WHITE);
            gp.playSE(SFX.ARROW); // use a drop sound or simple click
        }
    }

    /** Clears currentWeapon/currentShield if they point at this item, so leaving it (drop, sell,
     *  quest consume) can never leave the player attacking/blocking with an item no longer held. */
    public void unequipIfCurrent(Entity item) {
        if (currentWeapon == item) currentWeapon = null;
        if (currentShield == item) currentShield = null;
    }

    // Rendering methods
    @Override
    public void draw(GdxRenderer g2) {
        // Normally screenX/screenY (the player's fixed on-screen anchor) is exactly where the player
        // draws — the camera IS the player. During a locked-camera cutscene (see ui.BossIntroCutscene)
        // the camera can pan away from the player's real position; this offset shifts the player's
        // draw position the opposite way so it stays visually anchored at its real WORLD position
        // (appears to get left behind / recede into the distance) instead of following the camera
        // around the screen. Zero whenever the camera isn't locked (normal play).
        int camOffsetX = worldX - gp.getCamWorldX();
        int camOffsetY = worldY - gp.getCamWorldY();

        // ── DEATH ANIMATION ──
        if (playerDying) {
            int drawX = screenX + camOffsetX;
            int drawY = screenY + camOffsetY;
            Sprite deathImg = null;
            if (deathFrames != null && deathDirection >= 0 && deathDirection < deathFrames.length
                    && deathFrames[deathDirection] != null && playerDeathFrame < deathFrames[deathDirection].length) {
                deathImg = deathFrames[deathDirection][playerDeathFrame];
            }
            if (deathImg != null) {
                // Fade out during the hold phase (after all frames played)
                int holdTick = playerDeathCounter - (DEATH_TOTAL_FRAMES * deathTicksPerFrame());
                if (holdTick > 0) {
                    float fadeAlpha = Math.max(0.15f, 1f - (holdTick / (float) DEATH_HOLD_DELAY));
                    g2.setAlpha(fadeAlpha);
                }
                g2.drawImage(deathImg, drawX, drawY, gp.tileSize, gp.tileSize);
                g2.setAlpha(1f);
            }
            return;
        }

        Sprite image;
        int tempScreenX = screenX + camOffsetX;
        int tempScreenY = screenY + camOffsetY;
        int frame = Math.max(1, spriteNum);

        int drawW = (int)(gp.tileSize * spriteScale);
        int drawH = (int)(gp.tileSize * spriteScale);

        if (attacking) {
            image = getAttackFrame(direction, frame);

            // --- SLASH VFX: single arc texture, rotated to attackAngle, eased scale/alpha ---
            drawSliceVfx(g2, tempScreenX, tempScreenY);
        } else if (hitAnimCounter > 0 && hitFrames != null && hitAnimDirection >= 0 && hitAnimDirection < hitFrames.length
                   && hitFrames[hitAnimDirection] != null && hitAnimFrame < hitFrames[hitAnimDirection].length) {
            image = hitFrames[hitAnimDirection][hitAnimFrame];
        } else if (isSwimming && getSwimFrame(direction, frame) != null) {
            image = getSwimFrame(direction, frame);
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

        }

        float drawAlpha = spawnFadeAlpha;
        if (invincible && !dashing) drawAlpha *= (invincibleCounter % 4 < 2) ? 0.5f : 0.85f;
        if (drawAlpha < 1.0f) g2.setAlpha(Math.max(0f, drawAlpha));
        g2.drawImage(image, drawX, drawY, drawW, drawH);
        g2.setAlpha(1f);
    }

    /**
     * Draws the slash VFX: sliceAnim.png's 2 frames (peak/trail) are shown across the swing's
     * active window (swingProgress 0..1), rotated to attackAngle. Anchored at the attack cone's
     * apex (player center); the sprite's own long axis runs along its height (32 wide x 96 tall),
     * so the rotation origin sits near the bottom of the cell (the "hilt" end) so the crescent
     * sweeps outward from the player rather than pivoting on its own midpoint.
     *
     * <p>The applied rotation is {@code attackAngle + 90} (see the rotationDeg comment below).
     */
    private void drawSliceVfx(GdxRenderer g2, int tempScreenX, int tempScreenY) {
        if (sliceFrames == null || swingProgress < 0f) return;
        // Frame 0 (peak) for the first tick of the window, frame 1 (trail) for the rest — the old
        // 0.6 threshold only let frame 1 show on the single last tick before the swing ended,
        // making it effectively invisible.
        Sprite frame = sliceFrames[swingProgress < 0.34f ? 0 : 1];
        if (frame == null) return;

        float scale = (gp.tileSize / (float) SLICE_CELL_W) * (comboStep == 2 ? 1.15f : 1f);
        float alpha = 1f - (float) Math.pow(swingProgress, 3); // hold, then fade near the end
        if (alpha <= 0.02f) return;

        float w = SLICE_CELL_W * scale;
        float h = SLICE_CELL_H * scale;
        // Player center in screen space.
        float pcx = tempScreenX + solidArea.x + solidArea.width  / 2f;
        float pcy = tempScreenY + solidArea.y + solidArea.height / 2f;
        // Place the crescent's own center a fixed distance in FRONT of the player along attackAngle,
        // so it sits directly ahead rather than off to one side.
        float frontDist = gp.tileSize * 1f;
        float centerX = pcx + (float) Math.cos(attackAngle) * frontDist;
        float centerY = pcy + (float) Math.sin(attackAngle) * frontDist;
        // Pivot at the sprite's geometric center so rotation spins in place about that front point.
        float originX = w / 2f;
        float originY = h / 2f;
        float drawX = centerX - originX;
        float drawY = centerY - originY;
        // sliceAnim.png's neutral (unrotated) crescent is vertical (long axis top-to-bottom), convex
        // bulging RIGHT and concave facing LEFT — i.e. the neutral pose already IS a correct right-facing
        // slash (concave toward a player on the left). attackAngle uses the atan2 convention
        // (0=right, +90=down) matching drawImageRotated's clockwise-positive screen rotation, so rotating
        // the neutral pose straight by attackAngle keeps the concave side facing the player for every
        // direction (e.g. down attack -> concave faces up toward the player). No offset, no flip.
        float rotationDeg = (float) Math.toDegrees(attackAngle);

        g2.setAlpha(alpha);
        // sliceFlip alternates each swing: flipY mirrors the crescent top<->bottom in its local space,
        // swapping the arc direction for a back-and-forth slash feel without changing which side the
        // concave faces (that stays toward the player).
        g2.drawImageRotated(frame, drawX, drawY, w, h, originX, originY, rotationDeg, false, sliceFlip);
        g2.setAlpha(1f);
    }

    private Sprite getIdleFrame(int dir, int frame) {
        if (idleFrames != null && dir >= 0 && dir < idleFrames.length && idleFrames[dir] != null) {
            int idx = frame - 1;
            if (idx >= 0 && idx < idleFrames[dir].length) return idleFrames[dir][idx];
            return idleFrames[dir][0]; // fallback first frame
        }
        return null;
    }

    private Sprite getAttackFrame(int dir, int frame) {
        Sprite[][] frames = switch (comboStep) {
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

    private int getDashCooldownMax() {
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

}
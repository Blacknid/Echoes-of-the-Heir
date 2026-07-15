package main;

import java.util.ArrayList;
import java.util.Map;

import ai.PathFinder;
import audio.AudioManager;
import audio.SFX;
import data.SaveLoad;
import entity.Entity;
import entity.Particle;
import entity.Player;
import entity.Projectile;
import environment.EnvironmentManager;
import environment.MapShaderManager;
import gfx.Color;
import gfx.Font;
import gfx.GdxRenderer;
import gfx.Sprite;
import map.AssetSetter;
import map.EventHandler;
import map.MapManager;
import map.MapObjectLoader;
import map.MobSpawner;
import tile.TileManager;
import tile.interactiveTile;
import ui.BossIntroCutscene;
import ui.CutsceneManager;
import ui.Minimap;
import ui.RenderPipeline;
import ui.ScreenShake;
import ui.ThoughtBubble;
import ui.UI;
import util.ObjectPool;
import util.ResourceCache;

// libGDX port: GamePanel is no longer a Swing JPanel/Runnable. It holds all game state and
// logic; the libGDX MichiGame ApplicationListener drives it (fixed-timestep update() + per-frame
// draw(GdxRenderer)). Window/fullscreen/resize is handled by Gdx.graphics; input by KeyHandler/
// MouseHandler (libGDX InputProcessors).
public class GamePanel {

    // Tile sizing is centralized in Config to support runtime scaling.
    public final int originalTileSize = Config.originalTileSize; // 32 x 32 pixel (native)
    public final double scale = Config.scale;

    public final int tileSize = Config.tileSize; // runtime tile size (originalTileSize * scale)
    // Logical resolution — the integer-scaled game view size (device pixels / pixelScale). All world,
    // HUD and input layout is authored in these logical pixels; the camera/GL viewport magnify them by
    // the whole-number pixelScale for crisp pixel art. Used for BOTH windowed and fullscreen.
    public int screenWidth  = 1280;
    public int screenHeight = 720;
    // Raw device (window/framebuffer) size in physical pixels — what the OS gives us.
    public int deviceWidth  = 1280;
    public int deviceHeight = 720;
    // Moonshire-style integer pixel scale: how many device pixels map to one logical game pixel.
    // Chosen as the largest whole number that keeps the 1280x720 baseline fitting on screen, so
    // pixel art stays crisp (no fractional sampling). The leftover remainder (device not being an
    // exact multiple) enlarges the logical viewport, which the tile culling turns into MORE visible
    // map tiles rather than black bars.
    public int pixelScale = 1;
    // Display transform — set to pixelScale so panelToGame maps device coords back to logical space.
    private float displayScaleF  = 1f;
    private int   displayOffsetX = 0;
    private int   displayOffsetY = 0;
    // Visible tile counts — recalculated whenever the resolution changes
    public int maxScreenCol = (int)Math.ceil((double)screenWidth / tileSize) + 1;
    public int maxScreenRow = (int)Math.ceil((double)screenHeight / tileSize) + 1;
    /** UI horizontal scale factor: how much bigger the screen is than the 1280-wide reference. */
    public float uiSf()  { return screenWidth  / (float) Config.UI_BASE_W; }
    /** UI vertical scale factor: how much bigger the screen is than the 768-high reference. */
    public float uiSfH() { return screenHeight / (float) Config.UI_BASE_H; }

    public boolean HitBoxes = false;
    public boolean drawPath = false;
    public boolean debugModeEnabled = false;
    public boolean debugMenuOpen = false;
    // Pending debug reload — set from EDT, consumed by the game-loop thread at the start of update()
    private volatile boolean pendingReloadAll     = false;
    private volatile boolean pendingReloadNPCs    = false;
    private volatile boolean pendingReloadMonsters= false;
    private volatile boolean pendingReloadObjects = false;
    public int debugMenuSelectedIndex = 0;
    public java.util.List<String> debugMapList = new java.util.ArrayList<>();
    public int debugMapSelectedIndex = 0;

    // World grid dimensions — DYNAMIC per map. Default to 100x100 (the historical fixed size) so
    // arrays allocated before the first TMX load are valid; setWorldDimensions() updates these from
    // each map's TMX width/height and reallocates the dependent per-tile arrays.
    public int maxWorldCol = 100;
    public int maxWorldRow = 100;
    public int worldWidth = tileSize * maxWorldCol;
    public int worldHeight = tileSize * maxWorldRow;

    /**
     * Resize the world grid to a new map's dimensions and reallocate everything sized by it. Call
     * this BEFORE tile layers / lit maps / pathfinder nodes are (re)allocated for the new map. A
     * minimum of 1x1 is enforced; passing the current size is a cheap no-op for the arrays that key
     * off it (they're rebuilt per map anyway).
     */
    public void setWorldDimensions(int cols, int rows) {
        maxWorldCol = Math.max(1, cols);
        maxWorldRow = Math.max(1, rows);
        worldWidth  = tileSize * maxWorldCol;
        worldHeight = tileSize * maxWorldRow;
        // PathFinder allocates a node per tile in its ctor; resize it to the new grid.
        if (pFinder != null) pFinder.instantiateNodes();
    }
    
    // (No Sprite back-buffer / GdxRenderer on the GPU — MichiGame renders straight to the
    // default framebuffer via a GdxRenderer each frame.)
    public boolean fullScreenOn = false;
    public boolean vSyncOn = true;

    private static final int TARGET_UPS = 60; // Fixed simulation rate (game speed)
    int FPS = 60;  // Render target FPS (independent from game speed)
    public int currentFPS = 0;
    public int maxFPS = 0;
    int monitorRefreshRate = 60; // Detected at startup
    private int tickCounter = 0;

    public TileManager tileM = new TileManager(this);
    public KeyHandler keyH = new KeyHandler(this);
    public MouseHandler mouseH = new MouseHandler(this);
    public main.input.InputActions actions = new main.input.InputActions();
    public AudioManager audio = new AudioManager();
    public CollisionChecker cChecker = new CollisionChecker(this);
    public AssetSetter aSetter = new AssetSetter(this);
    public MapObjectLoader mapObjectLoader = new MapObjectLoader(this);
    public UI ui = new UI(this);
    public EventHandler eHandler = new EventHandler(this);
    public Config config = new Config(this);
    public CutsceneManager csManager = new CutsceneManager(this);
    public BossIntroCutscene bossIntroCutscene = new BossIntroCutscene(this);
    public PathFinder pFinder = new PathFinder(this);
    public EnvironmentManager eManager = new EnvironmentManager(this);
    public environment.WindField windField = new environment.WindField(this);
    public environment.WindPainter windPainter = new environment.WindPainter(this);
    public environment.CloudLayer cloudLayer = new environment.CloudLayer(this);
    public environment.DustFogLayer dustFogLayer = new environment.DustFogLayer(this);
    public environment.FireflyLayer fireflyLayer = new environment.FireflyLayer(this);
    public environment.TensionBeats tensionBeats = new environment.TensionBeats(this);
    public MapShaderManager mapShader;
    public environment.TileParticleEmitter tileParticleEmitter;
    public ScreenShake screenShake = new ScreenShake();
    public MobSpawner mobSpawner;
    public SaveLoad saveLoad = new SaveLoad(this);

    private static final gfx.Color PLAYER_GLOW_COLOR = new gfx.Color(255, 240, 220);
    private static final gfx.Color DEFAULT_TORCH_COLOR = new gfx.Color(255, 170, 60);

    // Debug overlay is drawn in DEVICE-pixel space (see GdxRenderer.beginDeviceSpace) so it renders at a
    // fixed, crisp on-screen size regardless of pixelScale — never Nearest-magnified. 16px reads well on
    // both windowed and fullscreen; the box geometry below is likewise in device pixels.
    private static final gfx.Font DEBUG_FONT = new gfx.Font("Consolas", gfx.Font.PLAIN, 16);
    private static final gfx.Color DEBUG_BG_COLOR = new gfx.Color(0, 0, 0, 160);
    private static final gfx.Color DEBUG_FPS_GREEN = new gfx.Color(80, 255, 80);
    private static final gfx.Color DEBUG_FPS_YELLOW = new gfx.Color(255, 220, 60);
    private static final gfx.Color DEBUG_FPS_RED = new gfx.Color(255, 80, 80);
    private static final gfx.Color DEBUG_INFO_BLUE = new gfx.Color(120, 200, 255);
    private static final gfx.Color MP_TINT_COLOR  = new gfx.Color(100, 180, 255);
    private static final gfx.Color MP_FILL_COLOR  = new gfx.Color(80, 160, 240, 200);
    private static final gfx.Color MP_BORDER_CLR  = new gfx.Color(50, 120, 200);
    private static final gfx.Color MP_NAME_SHADOW = new gfx.Color(0, 0, 0, 160);
    private static final gfx.Color MP_NAME_COLOR  = new gfx.Color(180, 220, 255);
    private static final gfx.Color MP_BAR_BG      = new gfx.Color(40, 40, 40, 180);
    private static final gfx.Color MP_BAR_GREEN   = new gfx.Color(60, 200, 80);
    private static final gfx.Color MP_BAR_RED     = new gfx.Color(220, 60, 60);
    private static final gfx.Stroke MP_STROKE_2 = new gfx.Stroke(2f);
    private static final int DEBUG_ROW_DEBUG_TEXT = 0;
    private static final int DEBUG_ROW_HITBOXES = 1;
    private static final int DEBUG_ROW_PATHS = 2;
    private static final int DEBUG_ROW_SEPIA = 3;
    private static final int DEBUG_ROW_INVINCIBLE = 4;
    private static final int DEBUG_ROW_RELOAD_ALL = 5;
    private static final int DEBUG_ROW_RELOAD_NPCS = 6;
    private static final int DEBUG_ROW_RELOAD_MONSTERS = 7;
    private static final int DEBUG_ROW_RELOAD_OBJECTS = 8;
    private static final int DEBUG_ROW_FLASHBACK = 9;
    private static final int DEBUG_ROW_FRAGMENTS = 10;
    private static final int DEBUG_ROW_AWAKENING = 11;
    private static final int DEBUG_ROW_TARGET_MAP = 12;
    private static final int DEBUG_ROW_TELEPORT_TARGET = 13;
    private static final int DEBUG_MENU_ROWS = 14;
    private gfx.Font mpNametagFont;


    public Minimap minimap;

    public RenderPipeline renderPipeline;

    public QuestManager questManager;

    public data.MemoryJournal memoryJournal;
    public environment.MemoryFlashback memoryFlashback;

    public ThoughtBubble thoughts;

    public boolean boss1Defeated;
    public boolean boss2Defeated;
    public boolean boss3Defeated;
    public boolean boss4Defeated;
    public int storyAct;       // 0=tutorial, 1=shatterLake, 2=ashenWoods, 3=citadel, 4=gallery, 5=frame
    public int endingChosen;   // 0=none, 1=confront, 2=sacrifice, 3=forgive

    public java.util.Set<String> openedGates = new java.util.HashSet<>();
    public java.util.Set<String> metNPCs      = new java.util.HashSet<>();
    /** Remaining stock for shops with finite stock, keyed "shopId:itemId". Absent = full/infinite. */
    public java.util.Map<String, Integer> shopStock = new java.util.HashMap<>();

    public MapManager mapManager;

    public int globalHitstopTimer = 0;

    public boolean cameraLocked  = false;
    public int     cameraWorldX  = 0;   // world X to center on when locked
    public int     cameraWorldY  = 0;   // world Y to center on when locked

    // Dialogue camera: a subtle zoom-in + recenter that frames the player+NPC when talking, and
    // eases back when the dialogue ends. All values lerp toward their *Target each frame. Applied at
    // render time (RenderPipeline) via g2.translate + g2.setWorldZoom.
    public float dlgZoom = 1f, dlgZoomTarget = 1f;
    public float dlgPanX = 0f, dlgPanTargetX = 0f;
    public float dlgPanY = 0f, dlgPanTargetY = 0f;
    public float dlgBars = 0f, dlgBarsTarget = 0f;              // 0..1 letterbox bar progress
    public static final float DLG_ZOOM     = 1.3f;             // subtle push-in (tunable)
    public static final float DLG_LERP     = 0.12f;            // ease speed toward targets
    public static final int   DLG_BAR_MAX_H = 28;              // max letterbox bar height (logical px)

    /** Ease the dialogue-camera zoom/pan/bars toward their targets. No-op when already settled. */
    public void updateDialogueCamera() {
        dlgZoom += (dlgZoomTarget - dlgZoom) * DLG_LERP;
        dlgPanX += (dlgPanTargetX - dlgPanX) * DLG_LERP;
        dlgPanY += (dlgPanTargetY - dlgPanY) * DLG_LERP;
        dlgBars += (dlgBarsTarget - dlgBars) * DLG_LERP;
    }

    /**
     * The world position everything (tiles, entities, particles, weather, minimap, UI prompts) draws
     * relative to. Normally this is just the player's own position — but during a locked-camera
     * cutscene (see ui.BossIntroCutscene) it can point anywhere, panning the visible world away from
     * the player without moving the player itself. player.screenX/screenY (the fixed on-screen anchor
     * point) never changes — only which world position maps to that anchor point changes.
     *
     * Every draw call site that used to read gp.player.worldX/worldY as "the camera" should read
     * these instead; gp.player.screenX/screenY stay as-is everywhere (they're the anchor, not the
     * panning quantity).
     */
    public int getCamWorldX() { return cameraLocked ? cameraWorldX : player.worldX; }
    public int getCamWorldY() { return cameraLocked ? cameraWorldY : player.worldY; }
    /**
     * Map raw window-space pointer coords (libGDX touch) to game-space coords, returned as
     * int[]{x,y}. libGDX touch coords already share the game's top-left origin (y-down camera);
     * in dynamic-viewport mode the transform is 1:1 (displayScaleF=1, no offset).
     */
    public int[] panelToGame(int px, int py) {
        if (displayScaleF <= 0f) return new int[]{px, py};
        int gx = (int)((px - displayOffsetX) / displayScaleF);
        int gy = (int)((py - displayOffsetY) / displayScaleF);
        gx = Math.max(0, Math.min(screenWidth  - 1, gx));
        gy = Math.max(0, Math.min(screenHeight - 1, gy));
        return new int[]{gx, gy};
    }
    public void lockCamera(int tileCol, int tileRow) {
        cameraWorldX = tileCol * tileSize;
        cameraWorldY = tileRow * tileSize;
        cameraLocked = true;
    }
    public void unlockCamera() { cameraLocked = false; }

    /** Finds a live (alive, not dying) boss in gp.monster[] by its display name — used by BossIntroTrigger. */
    public entity.Boss findBossByName(String bossName) {
        for (Entity m : monster) {
            if (m instanceof entity.Boss boss && boss.alive && !boss.dying && bossName.equals(boss.name)) {
                return boss;
            }
        }
        return null;
    }

    public ObjectPool<entity.DamageNumber> damageNumberPool;
    public java.util.ArrayList<entity.DamageNumber> damageNumbers = new java.util.ArrayList<>();
    public Thread gameThread;

    public Player player = new Player(this,keyH);
    public Entity obj[] = new Entity[100];
    // 20, not 10 — Canvas_Village_rework.tmx alone places 11 NPC_Generic objects; the old cap of 10
    // silently dropped the 11th (MapObjectLoader logs a warning, but it's easy to miss), so nothing
    // appeared for it even though its JSON/sprite/placement were all otherwise correct.
    public Entity npc[] = new Entity[20];
    public Entity monster[] = new Entity[20];
    public interactiveTile iTile[] = new interactiveTile[100];
    public ArrayList<Entity> projectilesList = new ArrayList<>();
    public ArrayList<Entity> particleList = new ArrayList<>();
    public ObjectPool<Projectile> projectilePool;
    public ObjectPool<Particle> particlePool;

    public Entity nearbyInteractable;
    public boolean inputLocked = false;

    // GAME STATE — integer constants kept for backward compatibility
    public int gameState;
    private int previousGameState = -1; // tracks titleState entry/exit to drive title music
    public static final int titleState = 0;
    public static final int playState = 1;
    public static final int pauseState = 2;
    public static final int dialogueState = 3;
    public static final int characterState = 4; //inventory state
    public static final int optionsState = 5;
    public static final int gameOverState = 6;
    public static final int cutsceneState = 7;
    public static final int transitionState = 8;
    public static final int levelUpState = 9;
    public static final int skillTreeState = 10;
    public static final int multiplayerPlayState = 11;
    public static final int journalState = 12;
    public static final int shopState = 13;

    public boolean teleportation = false;
    public boolean bootsUnlocked = false;
    public boolean deathSoundPlayed = false;

    public MultiplayerClient mpClient;
    public ServerListManager serverList;
    public FriendsListManager friendsListManager;
    public boolean multiplayerMode = false;
    public BleMultiplayerSession bleSession;

    /**
     * Puppet {@link entity.RemotePlayerEntity} per connected remote player (TCP mpClient peers keyed
     * "tcp:<id>", BLE session peers keyed "ble:<id>" — namespaced since both id spaces start at 0 and
     * a session only ever uses one transport at a time, but this keeps it collision-proof either way).
     * Rebuilt every tick in syncRemotePlayerEntities() so any system that scans Entity arrays — right
     * now just the lighting gather in EnvironmentManager/Lightning — treats remote players as real
     * light-emitting entities instead of the bespoke draw-only RemotePlayerState rectangles.
     */
    public final java.util.Map<String, entity.RemotePlayerEntity> remotePlayerEntities = new java.util.HashMap<>();

    // BACKWARD-COMPATIBLE DELEGATION: Map fields now live in MapManager.
    // These accessors keep old code compiling while we migrate callers.
    // Use gp.mapManager.fieldName directly in new code.
    public Map<String, String> getMapRegistry() { return mapManager.mapRegistry; }
    public String getCurrentMapId() { return mapManager.currentMapId; }

    public GamePanel() {
        // libGDX: no Swing listeners/panel setup. Input is registered by MichiGame
        // (Gdx.input.setInputProcessor over keyH/mouseH); the window is owned by Gdx.graphics.
    }

    public void setupGame() {

    new AssetValidator().validate();

    mapManager = new MapManager(this);

    mapManager.discoverMaps();

    if (!mapManager.loadingGame) {
        mapManager.currentMapId = "awakening_cave"; // default starting map
        aSetter.setObject();
        eManager.setup();
        aSetter.setInteractiveTile();
    }
    
    aSetter.setNPC();
    aSetter.setMonster();

    if (!mapManager.loadingGame) {
        aSetter.loadEntitiesFromTMX();
    }
    aSetter.loadEventsFromTMX();
    if (!mapManager.loadingGame) {
        player.setDefaultPositions();
        String initialPath = mapManager.mapRegistry.getOrDefault(mapManager.currentMapId, "");
        if (!initialPath.isEmpty()) {
            mapObjectLoader.loadMapProperties(initialPath);
        }
        if (!mapManager.pendingDialogueTrigger.isEmpty()) {
            ui.addMessage(mapManager.pendingDialogueTrigger, new gfx.Color(255, 240, 180), mapManager.pendingDialogueTriggerDuration);
            mapManager.pendingDialogueTrigger = "";
        }
        windField.loadForMap(mapManager.currentMapId, tileM.currentMapCols, tileM.currentMapRows);
    }
    mobSpawner = new MobSpawner(this);
    thoughts = new ThoughtBubble(this);
    gameState = titleState;

    mpClient = new MultiplayerClient(this);
    serverList = new ServerListManager();
    friendsListManager = new FriendsListManager(saveLoad.getCloudSaveService());
    bleSession = new BleMultiplayerSession(this);

    cChecker.updateCollisionRectsCache();

    projectilePool = new ObjectPool<>(
        () -> new Projectile(this),
        20,
        10
    );
    
    particlePool = new ObjectPool<>(
        () -> new Particle(this, null, null, 0, 0, 0, 0, 0),
        40,
        20
    );

    detectAndSetRefreshRate();
    setVSync(vSyncOn);

    // Shaders, vignette bake, gradient textures — all GL, all purely visual. The server skips it.
    if (!Headless.isEnabled()) {
        mapShader = new MapShaderManager(this);
        mapShader.setup();
    }

    tileParticleEmitter = new environment.TileParticleEmitter(this);

    damageNumberPool = new ObjectPool<>(
        () -> new entity.DamageNumber(this),
        15, 10
    );

    // Purely visual: the minimap bakes a terrain texture, which is GPU work the simulation never
    // consults. The authoritative server skips it (see main.Headless) and leaves the field null —
    // safe because every caller already null-checks it (KeyHandler, RenderPipeline, reloadMap).
    if (!Headless.isEnabled()) {
        minimap = new Minimap(this);
        minimap.bakeTerrainImage();
    }

    questManager = new QuestManager(this);

    memoryJournal = new data.MemoryJournal();
    memoryFlashback = new environment.MemoryFlashback(this);

    memoryJournal.registerFragment("frag_cave", "Awakening Cave",
        new String[]{"The air was thick with dust and silence.", "He had no memory of how he got here.", "Only a faint glimmer of light in the distance."},
        1, "battle_cave", 5f);
    memoryJournal.registerFragment("frag_familiar_weight", "Familiar Weight",
        new String[]{"The weight on his back was familiar.", "He had felt it before.", "But where?"},
        2, "shatter_lake");
    // [TEST] Register sample memory fragments (remove when real fragments are wired)
    memoryJournal.registerFragment("frag_prologue", "The Shattered Throne",
        new String[]{"The crown fell before the war ended.", "No one claimed it.", "No one dared."},
        3, "prologue");
    memoryJournal.registerFragment("frag_forest", "Voices in the Ash",
        new String[]{"She heard her name in the smoke.", "She did not turn back."},
        4, "ashen_woods");
    memoryJournal.registerFragment("frag_lake", "Shatter Lake",
        new String[]{"The water remembered everything.", "He had stood here before.", "So had she.", "Never together."},
        5, "shatter_lake");
    memoryJournal.registerFragment("echo", "Echo, the Unseen",
        new String[]{"Echo is one of the most feared Phantoms of this Realm," , "due to his teleportation moves and aggrive bites.", "He is the weakest brother.", "To this day, it's still unknown how many of his type are there.", "Also known as 'Echo, the Unseen'."},
        6, "Unknown", 5f);

    // Rendering only — the server never calls drawCurrentState(), so it never needs the pipeline
    // (which builds shaders and framebuffers, i.e. GL objects it has no context for).
    if (!Headless.isEnabled()) {
        renderPipeline = new RenderPipeline(this);
    }
    // GPU port: no Sprite back-buffer — MichiGame renders to the screen via GdxRenderer.
    }

    public void registerMap(String id, String tmxPath) { mapManager.registerMap(id, tmxPath); }
    public void startTransition(String mapId, int spawnCol, int spawnRow) { mapManager.startTransition(mapId, spawnCol, spawnRow); }
    public void changeMap() { mapManager.changeMap(); }
    public void changeMap(String mapIdOrPath, int spawnCol, int spawnRow) { mapManager.changeMap(mapIdOrPath, spawnCol, spawnRow); }
    public void resetGame(boolean restart) { mapManager.resetGame(restart); }

    /**
     * Tear down any live multiplayer session and wipe every trace of the run that just ended —
     * world, player stats, inventory, skills, story flags, quests, journal.
     *
     * <p>Singleplayer and multiplayer share ONE {@link GamePanel}: the same {@code player}, the same
     * {@code obj/npc/monster/iTile} arrays, the same story flags. Nothing about entering or leaving a
     * session used to clear any of that, so state bled straight across the boundary — a multiplayer
     * session inherited whatever singleplayer left in memory (inventory, level, opened chests, the
     * streamed-over map), and quitting back to the title left the multiplayer world in place for the
     * next singleplayer run to start on top of. Both directions have to start from a clean slate,
     * which is why this is called on the way IN to a session and on the way OUT of one.
     *
     * <p>This is the same wipe {@code KeyHandler.startNewGame()} performs before the awakening
     * cutscene — it is factored out here so the mode-change paths cannot drift from it.
     */
    public void resetSession() {
        // Drop the network session first: a live mpClient/BLE peer would otherwise keep writing
        // into the very entity arrays and player fields we are about to reset.
        multiplayerMode = false;
        if (mpClient != null) mpClient.disconnect();
        if (bleSession != null) {
            if (bleSession.isHosting()) bleSession.stopHosting();
            if (bleSession.isActive()) bleSession.leaveHost();
        }
        remotePlayerEntities.clear();

        // Story / progression flags.
        mapManager.shownActTitles.clear();
        openedGates.clear();
        metNPCs.clear();
        boss1Defeated = boss2Defeated = boss3Defeated = boss4Defeated = false;
        storyAct = 0;
        endingChosen = 0;
        teleportation = false;
        bootsUnlocked = false;

        if (memoryJournal != null) memoryJournal.reset();
        if (questManager != null) questManager.clearQuests();
        if (eManager != null) eManager.reset();
        ui.clearMessages();

        // resetGame(true) rebuilds the world from the starting map and calls
        // player.setDefaultValues(), which clears the inventory and restores level/life/mana/
        // stats/skill unlocks — the "stats and inventory" half of the reset.
        resetGame(true);
    }

    public void setFullScreen() {
        applyFullScreenSetting(true);
    }

    // Remembers the last windowed WINDOW size (device px) so leaving fullscreen restores it, rather
    // than the smaller logical size (which would shrink the window after an integer-scaled fullscreen).
    private int lastWindowedW = 0, lastWindowedH = 0;

    /** Toggle fullscreen via libGDX. The window/display is owned by Gdx.graphics. */
    public void applyFullScreenSetting(boolean enable) {
        if (fullScreenOn == enable) return;
        fullScreenOn = enable;
        if (com.badlogic.gdx.Gdx.graphics != null) {
            if (enable) {
                lastWindowedW = com.badlogic.gdx.Gdx.graphics.getWidth();
                lastWindowedH = com.badlogic.gdx.Gdx.graphics.getHeight();
                com.badlogic.gdx.Gdx.graphics.setFullscreenMode(
                    com.badlogic.gdx.Gdx.graphics.getDisplayMode());
            } else {
                int w = lastWindowedW > 0 ? lastWindowedW : MichiGame.BASE_W;
                int h = lastWindowedH > 0 ? lastWindowedH : MichiGame.BASE_H;
                com.badlogic.gdx.Gdx.graphics.setWindowedMode(w, h);
            }
        }
        config.saveConfig();
    }

    /** Back-compat 1:1 overload (no integer scaling). */
    public void applyNewResolution(int w, int h) {
        applyNewResolution(w, h, 1, 0, 0, w, h);
    }

    /**
     * Adapts the LOGICAL resolution to the window. Called from MichiGame.syncCamera, which has
     * already chosen the integer {@code pixelScale} and computed the logical viewport
     * ({@code logicalW/H = deviceW/H / pixelScale}) plus the centering margins.
     *
     * <p>Moonshire-style behaviour: the world/UI are authored and culled in LOGICAL pixels, then
     * the GL viewport magnifies them by the whole-number {@code pixelScale} for crisp pixel art.
     * Because logicalW/H keep the full integer-division remainder, a bigger screen becomes MORE
     * visible map tiles (via the existing culling on {@code screenWidth/Height}) rather than black
     * bars or stretching. {@code displayScaleF}/offsets mirror the render transform so
     * {@link #panelToGame} maps device pointer coords back to logical space exactly.
     */
    public void applyNewResolution(int logicalW, int logicalH, int pixelScale,
                                   int marginX, int marginY, int deviceW, int deviceH) {
        screenWidth  = logicalW;
        screenHeight = logicalH;
        deviceWidth  = deviceW;
        deviceHeight = deviceH;
        this.pixelScale     = pixelScale;
        this.displayScaleF  = pixelScale;   // device px -> logical px (inverse of the render magnify)
        this.displayOffsetX = marginX;
        this.displayOffsetY = marginY;
        maxScreenCol = (int)Math.ceil((double)screenWidth  / tileSize) + 1;
        maxScreenRow = (int)Math.ceil((double)screenHeight / tileSize) + 1;
        if (player != null) {
            player.screenX = screenWidth  / 2 - tileSize / 2;
            player.screenY = screenHeight / 2 - tileSize / 2;
            player.snapCamera();
        }
        vpCacheValid = false;
        if (mapShader != null) mapShader.setup();
        if (ui       != null) ui.onResolutionChanged();
    }

    // (Custom in-canvas window controls/resize/drag removed — the libGDX LWJGL3 window provides
    // native decorations; F11 toggles fullscreen via applyFullScreenSetting.)

    public void setVSync(boolean enabled) {
        vSyncOn = enabled;
        if (enabled) {
            System.setProperty("sun.java2d.vsync", "True");
            FPS = monitorRefreshRate;
            System.out.println("[V-SYNC] Enabled - Game synced to monitor refresh rate (" + monitorRefreshRate + " Hz)");
        } else {
            System.setProperty("sun.java2d.vsync", "False");
            FPS = 0;
            System.out.println("[V-SYNC] Disabled - Render target UNCAPPED");
        }
        applyFpsTarget(config.fpsTarget);
    }

    public void applyFpsTarget(int target) {
        config.fpsTarget = target;
        if (target > 0) {
            FPS = target;
            System.out.println("[FPS] Cap set to " + target + " FPS");
        } else {
            FPS = vSyncOn ? monitorRefreshRate : 0;
        }
    }

    private void detectAndSetRefreshRate() {
        try {
            if (com.badlogic.gdx.Gdx.graphics != null) {
                int refreshRate = com.badlogic.gdx.Gdx.graphics.getDisplayMode().refreshRate;
                if (refreshRate >= 30 && refreshRate <= 300) {
                    monitorRefreshRate = refreshRate;
                    System.out.println("[DISPLAY] Monitor detected: " + refreshRate + " Hz");
                } else {
                    System.out.println("[V-SYNC] Monitor refresh rate " + refreshRate + " Hz out of range, using default 60 Hz");
                }
            }
        } catch (Exception e) {
            System.out.println("[V-SYNC] Could not detect monitor refresh rate, using default 60 Hz");
        }
    }

    // libGDX drives the loop: MichiGame calls stepUpdates(deltaSeconds) at a fixed 60 UPS and
    // draw(GdxRenderer) each frame. The old Swing game-thread + run() + paintComponent are gone.
    public void startGameThread() { /* no-op: MichiGame owns the loop now */ }

    private double updateAccumulator = 0;
    private long fpsTimerNs = 0;
    private int  fpsFrameCount = 0;

    /**
     * Advance the fixed-timestep simulation. Called once per rendered frame by MichiGame with the
     * real frame delta; runs update() at TARGET_UPS (60 Hz), capping catch-up to avoid spiral.
     */
    public void stepUpdates(float deltaSeconds) {
        final double updateInterval = 1.0 / TARGET_UPS;
        updateAccumulator += deltaSeconds;
        if (updateAccumulator > 5 * updateInterval) updateAccumulator = 5 * updateInterval; // cap
        while (updateAccumulator >= updateInterval) {
            update();
            updateAccumulator -= updateInterval;
        }
        // FPS counter
        fpsFrameCount++;
        fpsTimerNs += (long) (deltaSeconds * 1_000_000_000L);
        if (fpsTimerNs >= 1_000_000_000L) {
            currentFPS = fpsFrameCount;
            if (currentFPS > maxFPS) maxFPS = currentFPS;
            fpsFrameCount = 0;
            fpsTimerNs = 0;
        }
    }


    /** Trigger a global hit-stop freeze. Only overrides if stronger than current. */
    public void triggerHitstop(int frames) {
        if (frames > globalHitstopTimer) globalHitstopTimer = frames;
    }

    public void update() {
        tickCounter++;

        // TITLE MUSIC: start Main_Theme on entering the title screen, stop it on leaving —
        // one place instead of every titleState entry/exit site (new game, continue, quit to
        // title from pause/game-over, cutscene end, etc.).
        if (gameState != previousGameState) {
            if (gameState == titleState) {
                playMusic(SFX.MAIN_THEME);
            } else if (previousGameState == titleState) {
                stopMusic();
            }
            // Whatever key/click closed the previous screen may have left a bound action's
            // one-shot "pending press" flag stale (e.g. E read via a raw key check instead of
            // consumePressed()) — cleared here, one place, instead of every individual
            // screen-close site having to remember to drain it. See InputActions.clearAllPending().
            actions.clearAllPending();
            previousGameState = gameState;
        }

        // PENDING DEBUG RELOADS — executed here on the game-loop thread to avoid race conditions
        if (pendingReloadAll)      { pendingReloadAll      = false; doReloadAll(); }
        if (pendingReloadNPCs)     { pendingReloadNPCs     = false; doReloadNPCs(); }
        if (pendingReloadMonsters) { pendingReloadMonsters = false; doReloadMonsters(); }
        if (pendingReloadObjects)  { pendingReloadObjects  = false; doReloadObjects(); }

        if (memoryFlashback != null && memoryFlashback.isActive()) {
            memoryFlashback.update();
            if (memoryFlashback.getState() == environment.MemoryFlashback.DONE) {
                memoryFlashback.finish();
            }
            return; // freeze everything else during flashback
        }

        // THOUGHT BUBBLE: ticks during play and cutscene states
        if (thoughts != null) thoughts.update();

        keyH.update();

        // UI ANIMATION TICK: always advance at the fixed 60 Hz update rate,
        // independent of render FPS so pulse/breathe animations never speed up on fast machines
        // or slow down on weak ones.
        ui.updateAnimations();

        // NFC cold-launch auto-join (see platform.NfcLaunch, androidlauncher.nfc.Ndef4Service):
        // if this run/tap brought the game to front via a hosting phone's tag, skip straight to
        // JOIN GAME instead of requiring the player to navigate the title menu manually. Checked
        // every tick (not just once at boot) so a repeat tap while already idling on the title
        // screen (singleTask re-delivers via onNewIntent) still triggers it.
        if (gameState == titleState && platform.NfcLaunch.consumeLaunchedViaNfc()) {
            ui.startJoinGameFromNfcLaunch();
        }

        // Player keeps idling (breathing/blinking sprite animation) instead of freezing on a single
        // frame while dialogue or the inventory/character screen has their input. Player uses its own
        // sprite/idle-clip system (spriteNum + idleClip), not the generic Entity.tickAnimations()
        // idleFrames path, so it needs its own tick method.
        if (gameState == dialogueState || gameState == characterState || gameState == shopState) {
            player.tickIdleWhileMenuOpen();
        }
        if (gameState == dialogueState) {
            for (int i = 0; i < npc.length; i++) {
                if (npc[i] != null && isEntityInViewport(npc[i], tileSize * 2)) {
                    npc[i].tickAnimations();
                }
            }
        }

        // DIALOGUE LOGIC (60 UPS): typewriter tick + Enter input + gameState transitions.
        // Must NOT run in the draw path — at 400 FPS that makes dialogue FPS-dependent
        // and saturates the CPU with wrapText() calls, starving the EDT.
        if (gameState == dialogueState || gameState == cutsceneState) {
            ui.updateDialogueState();
        }

        if (bossIntroCutscene.isActive()) {
            bossIntroCutscene.update();
        }

        // World keeps simulating during dialogue, while the inventory/character screen is open, AND
        // during a cutscene (NPCs, monsters, particles, wind, lighting, etc.) so the game doesn't
        // visibly freeze in the background — only the player is held out (via the playState-only
        // player.update() below) so they can't move/act while a menu/cutscene has taken over. The boss
        // being shown off by BossIntroCutscene freezes itself into an idle pose (see the check at the
        // top of Boss.update()) so it doesn't wander off mid-shot while everything else keeps moving.
        if(gameState == playState || gameState == dialogueState || gameState == characterState
                || gameState == cutsceneState || gameState == shopState) {
            vpCacheValid = false;
            updateViewportCache();

            // Ease the dialogue zoom/pan/letterbox. Ticks during dialogue AND during the first
            // playState frames after it ends (targets reset to neutral) so the zoom-out animates.
            updateDialogueCamera();

            if (globalHitstopTimer > 0) {
                globalHitstopTimer--;
                for (int i = particleList.size() - 1; i >= 0; i--) {
                    Entity particle = particleList.get(i);
                    if (particle != null) {
                        if (particle.alive) particle.update();
                        else { particleList.remove(i); if (particle instanceof Particle) particlePool.release((Particle) particle); }
                    }
                }
                if (tileParticleEmitter != null) tileParticleEmitter.update();
                if (mapShader != null) mapShader.update();
                cloudLayer.update();
                dustFogLayer.update();
                fireflyLayer.update();
                screenShake.update();
                for (int i = damageNumbers.size() - 1; i >= 0; i--) {
                    entity.DamageNumber dn = damageNumbers.get(i);
                    if (dn.alive) dn.update();
                    else { damageNumbers.remove(i); damageNumberPool.release(dn); }
                }
                return; // skip all entity/world logic
            }

            // Player input/movement is frozen during dialogue (tickAnimations() above keeps
            // its sprite animating in place); everything below still simulates.
            if (gameState == playState) {
                player.update();
            }
            for (int i = 0; i < obj.length; i++) {
                if (obj[i] instanceof object.OBJ_Door door && door.doorOpening) {
                    door.update();
                }
            }
            for ( int i = 0 ; i < npc.length; i++ ) {
                if ( npc[i] != null ) {
                    if (isEntityInViewport(npc[i], tileSize * 2)) {
                        npc[i].update();
                    }
                }
            }
            for ( int i = 0 ; i < monster.length ; i++ ) {
                if ( monster[i] != null ) {
                    if ( monster[i].alive && !monster[i].dying ) {
                        if (isEntityInViewport(monster[i], tileSize * 2)) {
                            monster[i].update();
                        } else if (isEntityInViewport(monster[i], tileSize * 6)
                                   && (tickCounter & 3) == 0
                                   && bossIntroCutscene.getBoss() != monster[i]) {
                            // Distant monsters: run AI every 4 frames to prevent snap-teleport on re-entry.
                            // This calls setAction() directly, bypassing Boss.update()'s intro-cutscene
                            // freeze check — so a boss the camera hasn't panned to yet (still "distant")
                            // could otherwise keep chasing the player throughout its own intro. Skip it
                            // entirely while it's the cutscene's subject.
                            monster[i].setAction();
                        }
                    }
                    if ( !monster[i].alive ) {
                        if (monster[i] instanceof entity.BossMonster bm) {
                            bm.onDeath();
                        }
                        monster[i] = null;
                    }
                }
            }
            for ( int i = projectilesList.size() - 1 ; i >= 0 ; i-- ) {
                Entity proj = projectilesList.get(i);
                if (proj != null) {
                    if (proj.alive) {
                        proj.update();
                    } else {
                        projectilesList.remove(i);
                        projectilePool.release((Projectile) proj);
                    }
                }
            }
            for ( int i = particleList.size() - 1 ; i >= 0 ; i-- ) {
                Entity particle = particleList.get(i);
                if (particle != null) {
                    if (particle.alive) {
                        particle.update();
                    } else {
                        particleList.remove(i);
                        if (particle instanceof Particle) {
                            particlePool.release((Particle) particle);
                        }
                    }
                }
            }
            for ( int i = 0 ; i < iTile.length ; i++ ) {
                if ( iTile[i] != null ) {
                    // Margin covers the tallest interactive tile sprite (IT_Prop, up to 5 tiles) so
                    // it isn't updated/culled based on just its 1-tile base position.
                    if (iTile[i].invincible || isEntityInViewport(iTile[i], tileSize * 4)) {
                        iTile[i].update();
                    }
                }
            }
            // Atmosphere: clouds, fog, fireflies, wind, music tension. None of it changes a
            // gameplay outcome — it only decides what the frame looks and sounds like — and all of
            // it lazily bakes GPU textures. The authoritative server has no frame and no GL, so it
            // skips the lot; everything below this block (spawning, events, entities) it still runs.
            if (!Headless.isEnabled()) {
                eManager.update();
                windField.update();
                windPainter.update();
                cloudLayer.update();
                dustFogLayer.update();
                fireflyLayer.update();
                tensionBeats.update();
            }
            mobSpawner.update();
            eHandler.updateSpawnZones();
            tileM.update();
            syncRemotePlayerEntities();

            if (eManager.lightning != null) {
                eManager.lightning.clearLights();
                eManager.lightning.addLight(
                    player.worldX + tileSize / 2, player.worldY + tileSize / 2,
                    tileSize * 4, PLAYER_GLOW_COLOR, 0.25f);
                float flickerBase = System.nanoTime() * 0.000000003f;
                addColoredGlows(obj, flickerBase, 0f);
                // NPC lights (e.g. a glowing figure beckoning in a dark cave) get a colored glow too.
                addColoredGlows(npc, flickerBase, 100f);
                // Remote players (BLE "invite player" guests/host, or TCP multiplayer server peers)
                // broadcast the same warm glow as the local player's own torch light — see
                // RemotePlayerEntity and syncRemotePlayerEntities().
                addRemotePlayerGlows();
                fireflyLayer.addLights(eManager.lightning);
            }

            if (mapShader != null) {
                mapShader.update();
            }

            if (tileParticleEmitter != null) {
                tileParticleEmitter.update();
            }

            screenShake.update();

            for (int i = damageNumbers.size() - 1; i >= 0; i--) {
                entity.DamageNumber dn = damageNumbers.get(i);
                if (dn.alive) {
                    dn.update();
                } else {
                    damageNumbers.remove(i);
                    damageNumberPool.release(dn);
                }
            }
        }
            if (multiplayerMode && mpClient != null && mpClient.isConnected()) {
                mpClient.update();
            }
            if (bleSession != null && bleSession.isActive()) {
                bleSession.update();
            }

            if (player.life <= 0) {
                // Death-save skills
                boolean saved = false;
                if (player.undyingWillUnlocked && player.undyingWillCooldown <= 0) {
                    player.life = 1;
                    player.undyingWillCooldown = 5400; // 90 seconds at 60 FPS
                    player.invincible = true;
                    ui.addMessage("Undying Will activates!", new gfx.Color(255, 215, 0));
                    screenShake.shakeHeavy();
                    saved = true;
                } else if (player.secondWindUnlocked && player.secondWindAvailable) {
                    player.life = Math.max(1, (int)(player.maxLife * 0.3f));
                    player.secondWindAvailable = false;
                    player.invincible = true;
                    ui.addMessage("Second Wind!", new gfx.Color(100, 200, 100));
                    screenShake.shakeMedium();
                    saved = true;
                }

                if (!saved) {
                    player.life = 0; // safety clamp

                    // Start player death animation instead of instant game over
                    if (!player.playerDying) {
                        player.playerDying = true;
                        player.playerDeathCounter = 0;
                        player.playerDeathFrame = 0;
                        player.deathDirection = player.direction; // lock facing for death anim
                        player.attacking = false;
                        player.knockBack = false;
                        // Death animation handles the transition to gameOverState
                    }
                }
            }
    }

    /**
     * Register a flickering colored ambient glow for every light-emitting entity in an array. Shared by
     * torches (obj) and NPCs (npc) so a glowing NPC gives off its lightColor. {@code phaseOffset} keeps
     * each array's flicker out of phase. Uses DEFAULT_TORCH_COLOR when the entity has no explicit tint.
     */
    private void addColoredGlows(Entity[] arr, float flickerBase, float phaseOffset) {
        if (arr == null || eManager.lightning == null) return;
        for (int i = 0; i < arr.length; i++) {
            Entity e = arr[i];
            if (e == null || !e.lightSource || e.lightRadius <= 0) continue;
            float flicker = 0.22f + 0.06f * MapShaderManager.fastSin(flickerBase + phaseOffset + i * 1.7);
            gfx.Color lc = (e.lightColor != null) ? e.lightColor : DEFAULT_TORCH_COLOR;
            eManager.lightning.addLight(
                e.worldX + tileSize / 2, e.worldY + tileSize / 2,
                e.lightRadius * tileSize, lc, flicker);
        }
    }

    /**
     * Rebuilds {@link #remotePlayerEntities} from the live session state (TCP {@link #mpClient} and/or
     * BLE {@link #bleSession}) every tick, so remote players are real {@link entity.Entity} instances —
     * not just draw-only rectangles — for any system (currently: lighting) that wants to treat them as
     * such. Stale entities (player left / session ended) are pruned; existing ones are reused in place
     * rather than reallocated so this stays allocation-free during steady-state play.
     */
    private void syncRemotePlayerEntities() {
        long nowNs = System.nanoTime();
        int lightRadiusTiles = (eManager.lightning != null) ? eManager.lightning.playerLightRadius : 2;
        java.util.HashSet<String> liveKeys = new java.util.HashSet<>();

        if (mpClient != null && mpClient.isConnected()) {
            for (var entry : mpClient.remotePlayers.entrySet()) {
                String key = "tcp:" + entry.getKey();
                liveKeys.add(key);
                entity.RemotePlayerEntity re = remotePlayerEntities.computeIfAbsent(key,
                        k -> new entity.RemotePlayerEntity(this));
                re.syncFrom(entry.getValue(), nowNs, lightRadiusTiles);
            }
        }
        if (bleSession != null && bleSession.isActive()) {
            for (var entry : bleSession.remotePlayers.entrySet()) {
                String key = "ble:" + entry.getKey();
                liveKeys.add(key);
                entity.RemotePlayerEntity re = remotePlayerEntities.computeIfAbsent(key,
                        k -> new entity.RemotePlayerEntity(this));
                re.syncFrom(entry.getValue(), nowNs, lightRadiusTiles);
            }
        }
        if (remotePlayerEntities.size() != liveKeys.size()) {
            remotePlayerEntities.keySet().retainAll(liveKeys);
        }
    }

    /** Colored-glow light per remote player entity, same warm player tint as the local player's torch
     *  (no flicker — a player's glow should read steady, unlike ambient torch/NPC lights). */
    private void addRemotePlayerGlows() {
        if (eManager.lightning == null || remotePlayerEntities.isEmpty()) return;
        for (entity.RemotePlayerEntity re : remotePlayerEntities.values()) {
            if (!re.lightSource || re.lightRadius <= 0) continue;
            gfx.Color lc = (re.lightColor != null) ? re.lightColor : DEFAULT_TORCH_COLOR;
            eManager.lightning.addLight(
                re.worldX + tileSize / 2, re.worldY + tileSize / 2,
                re.lightRadius * tileSize, lc, 0.28f);
        }
    }

    private int vpMinX, vpMaxX, vpMinY, vpMaxY;
    private boolean vpCacheValid = false;

    private void updateViewportCache() {
        vpMinX = getCamWorldX() - player.screenX;
        vpMaxX = vpMinX + screenWidth;
        vpMinY = getCamWorldY() - player.screenY;
        vpMaxY = vpMinY + screenHeight;
        vpCacheValid = true;
    }

    public boolean isEntityInViewport(Entity entity, int margin) {
        if (!vpCacheValid) updateViewportCache();
        return entity.worldX + tileSize > vpMinX - margin &&
               entity.worldX < vpMaxX + margin &&
               entity.worldY + tileSize > vpMinY - margin &&
               entity.worldY < vpMaxY + margin;
    }

    /** Render one frame. Called by MichiGame each frame with the active GdxRenderer (screen already cleared). */
    public void draw(GdxRenderer g2) {

    renderPipeline.drawCurrentState(g2);

    // MEMORY FLASHBACK OVERLAY (drawn on top of everything)
    if (memoryFlashback != null && memoryFlashback.isActive()) {
        memoryFlashback.draw(g2);
    }

    // DEBUG TEXT — drawn in DEVICE-pixel space so it stays crisp and fixed-size in fullscreen
    // (logical-space text is magnified by pixelScale via Nearest filtering, which made it jaggy).
    if(keyH.showDebugText) {
        g2.beginDeviceSpace();
        final int x = 18;
        int y = 36;                 // baseline of the first line, from the top of the window
        final int lineHeight = 24;
        final int padX = 10, padY = 8;
        final int lines = 9 + (tileParticleEmitter != null ? 1 : 0);
        final int boxW = 280, boxH = lines * lineHeight + padY * 2;

        g2.setColor(DEBUG_BG_COLOR);
        g2.fillRoundRect(x - padX, y - lineHeight - padY, boxW, boxH, 10, 10);

        g2.setFont(DEBUG_FONT);
        final int safeFPS = currentFPS > 0 ? currentFPS : 1;
        final String frameTimeStr = String.format("%.2f", 1000.0 / safeFPS);
        final String minFrameTimeStr = maxFPS > 0 ? String.format("%.2f", 1000.0 / maxFPS) : "--";

        if (currentFPS >= FPS)                   g2.setColor(DEBUG_FPS_GREEN);
        else if (currentFPS >= FPS / 2)          g2.setColor(DEBUG_FPS_YELLOW);
        else                                     g2.setColor(DEBUG_FPS_RED);
        g2.drawString("FPS: " + currentFPS + " / " + FPS, x, y); y += lineHeight;

        g2.setColor(DEBUG_INFO_BLUE);
        g2.drawString("Max FPS: " + maxFPS, x, y); y += lineHeight;
        g2.drawString("Frame time: " + frameTimeStr + " ms", x, y); y += lineHeight;
        g2.drawString("Best frame: " + minFrameTimeStr + " ms", x, y); y += lineHeight;

        g2.setColor(Color.WHITE);
        g2.drawString("Map: " + mapManager.currentMapId, x, y); y += lineHeight;
        g2.drawString("WorldX: " + player.worldX, x, y); y += lineHeight;
        g2.drawString("WorldY: " + player.worldY, x, y); y += lineHeight;
        g2.drawString("Col: " + (player.worldX + player.solidArea.x) / tileSize, x, y); y += lineHeight;
        g2.drawString("Row: " + (player.worldY + player.solidArea.y) / tileSize, x, y); y += lineHeight;
        if (tileParticleEmitter != null) {
            g2.drawString("TileParticles: " + tileParticleEmitter.getActiveCount(), x, y); y += lineHeight;
        }
        g2.endDeviceSpace();
    }

    if (debugMenuOpen) drawDebugMenu(g2);
    }

    /** Rebuild the debug map list from the current registry (sorted). */
    public void refreshDebugMapList() {
        debugMapList = new java.util.ArrayList<>(mapManager.mapRegistry.keySet());
        java.util.Collections.sort(debugMapList);
        // Select the currently loaded map
        int idx = debugMapList.indexOf(mapManager.currentMapId);
        debugMapSelectedIndex = idx >= 0 ? idx : 0;
    }

    public void toggleDebugMenu() {
        debugMenuOpen = !debugMenuOpen;
        if (debugMenuOpen) {
            refreshDebugMapList();
            debugMenuSelectedIndex = 0;
            keyH.upPressed = false;
            keyH.downPressed = false;
            keyH.leftPressed = false;
            keyH.rightPressed = false;
            keyH.shotKeyPressed = false;
            keyH.enterPressed = false;
            keyH.dashPressed = false;
        }
        playSE(SFX.MENU_SELECT);
    }

    public void moveDebugMenuSelection(int delta) {
        if (!debugMenuOpen) return;
        debugMenuSelectedIndex = Math.floorMod(debugMenuSelectedIndex + delta, DEBUG_MENU_ROWS);
        playSE(SFX.MENU_CURSOR);
    }

    public void adjustDebugMenuValue(int delta) {
        if (!debugMenuOpen || debugMapList.isEmpty()) return;
        if (debugMenuSelectedIndex == DEBUG_ROW_TARGET_MAP || debugMenuSelectedIndex == DEBUG_ROW_TELEPORT_TARGET) {
            debugMapSelectedIndex = Math.floorMod(debugMapSelectedIndex + delta, debugMapList.size());
            playSE(SFX.MENU_CURSOR);
        }
    }

    public void activateDebugMenuSelection() {
        System.out.println("[DBG] activateDebugMenuSelection called, selectedIndex=" + debugMenuSelectedIndex + " (DEBUG_ROW_HITBOXES=" + DEBUG_ROW_HITBOXES + ")");
        switch (debugMenuSelectedIndex) {
            case DEBUG_ROW_DEBUG_TEXT -> {
                keyH.showDebugText = !keyH.showDebugText;
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_HITBOXES -> {
                HitBoxes = !HitBoxes;
                System.out.println("[DBG] HitBoxes toggled to " + HitBoxes);
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_PATHS -> {
                drawPath = !drawPath;
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_SEPIA -> toggleDebugSepia();
            case DEBUG_ROW_INVINCIBLE -> {
                player.godMode = !player.godMode;
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_RELOAD_ALL      -> reloadCurrentMapDebug();
            case DEBUG_ROW_RELOAD_NPCS     -> reloadNPCsDebug();
            case DEBUG_ROW_RELOAD_MONSTERS -> reloadMonstersDebug();
            case DEBUG_ROW_RELOAD_OBJECTS  -> reloadObjectsDebug();
            case DEBUG_ROW_FLASHBACK -> triggerDebugFlashback();
            case DEBUG_ROW_FRAGMENTS -> collectDebugFragments();
            case DEBUG_ROW_AWAKENING -> teleportToAwakeningDebug();
            case DEBUG_ROW_TARGET_MAP -> adjustDebugMenuValue(1);
            case DEBUG_ROW_TELEPORT_TARGET -> teleportToSelectedDebugMap();
            default -> {
            }
        }
    }

    public void toggleDebugSepia() {
        if (mapShader == null) return;
        mapShader.sepiaMode = !mapShader.sepiaMode;
        playSE(SFX.MENU_SELECT);
    }

    public void triggerDebugFlashback() {
        if (memoryFlashback == null) return;
        debugMenuOpen = false;
        data.MemoryJournal.MemoryFragment testFrag = new data.MemoryJournal.MemoryFragment(
                "test_sepia", "A Lost Moment",
                new String[]{"The rain fell on cobblestones...", "She never looked back.", "Neither did he."},
                0, "debug");
        memoryFlashback.trigger(testFrag);
        playSE(SFX.MENU_SELECT);
    }

    public void collectDebugFragments() {
        if (memoryJournal == null) return;
        debugMenuOpen = false;
        memoryJournal.collect("frag_prologue");
        memoryJournal.collect("frag_forest");
        memoryJournal.collect("frag_lake");
        memoryJournal.collect("echo");
        gameState = GamePanel.journalState;
        playSE(SFX.MENU_SELECT);
    }

    public void teleportToAwakeningDebug() {
        debugMenuOpen = false;
        playSE(SFX.MENU_SELECT);
        startTransition("awakening_cave", 20, 15);
    }

    public void teleportToSelectedDebugMap() {
        if (debugMapList.isEmpty()) return;
        String targetId = debugMapList.get(debugMapSelectedIndex);
        debugMenuOpen = false;
        playSE(SFX.MENU_SELECT);
        startTransition(targetId, -1, -1);
    }

    // ── Called from EDT (key/menu) — just schedules; actual work runs on game-loop thread ──
    public void reloadCurrentMapDebug() { pendingReloadAll      = true; }
    public void reloadNPCsDebug()       { pendingReloadNPCs     = true; }
    public void reloadMonstersDebug()   { pendingReloadMonsters = true; }
    public void reloadObjectsDebug()    { pendingReloadObjects  = true; }

    // ── Actual reload implementations — MUST be called from the game-loop thread ──
    private void doReloadAll() {
        if (mapManager == null) return;
        data.NPCFactory.invalidateCache();
        String mapId = mapManager.currentMapId;
        String path = mapManager.mapRegistry.getOrDefault(mapId, mapId);
        ResourceCache.invalidateXml(path);
        tileM.loadMapFromTMX(path);
        tileM.loadCollisionLayer(path);
        tileM.initTileLitMap();
        mapObjectLoader.loadMapProperties(path);
        mapManager.clearSavedMapEntities(mapId);
        for (int i = 0; i < obj.length; i++) obj[i] = null;
        for (int i = 0; i < npc.length; i++) npc[i] = null;
        for (int i = 0; i < monster.length; i++) monster[i] = null;
        for (int i = 0; i < iTile.length; i++) iTile[i] = null;
        projectilesList.clear();
        particleList.clear();
        if (eManager != null && eManager.lightning != null) eManager.lightning.clearShadowCaches();
        eHandler.reset();
        windField.loadForMap(mapId, tileM.currentMapCols, tileM.currentMapRows);
        aSetter.setObject();
        aSetter.setInteractiveTile();
        aSetter.setNPC();
        aSetter.setMonster();
        aSetter.loadEntitiesFromTMX();
        aSetter.loadEventsFromTMX();
        cChecker.updateCollisionRectsCache();
        if (minimap != null) {
            minimap.invalidateTerrainCache(mapId);
            minimap.bakeTerrainImage();
        }
        ui.addMessage("Map fully reloaded", new gfx.Color(100, 255, 140), 120);
        playSE(SFX.MENU_SELECT);
    }

    private void doReloadNPCs() {
        if (mapManager == null) return;
        data.NPCFactory.invalidateCache();
        mapManager.clearSavedMapEntities(mapManager.currentMapId);
        for (int i = 0; i < npc.length; i++) npc[i] = null;
        aSetter.setNPC();
        aSetter.loadEntitiesFromTMX();
        ui.addMessage("NPCs reloaded", new gfx.Color(100, 200, 255), 120);
        playSE(SFX.MENU_SELECT);
    }

    private void doReloadMonsters() {
        if (mapManager == null) return;
        mapManager.clearSavedMapEntities(mapManager.currentMapId);
        for (int i = 0; i < monster.length; i++) monster[i] = null;
        projectilesList.clear();
        aSetter.setMonster();
        aSetter.loadEntitiesFromTMX();
        ui.addMessage("Monsters reloaded", new gfx.Color(255, 120, 120), 120);
        playSE(SFX.MENU_SELECT);
    }

    private void doReloadObjects() {
        if (mapManager == null) return;
        mapManager.clearSavedMapEntities(mapManager.currentMapId);
        for (int i = 0; i < obj.length; i++) obj[i] = null;
        for (int i = 0; i < iTile.length; i++) iTile[i] = null;
        aSetter.setObject();
        aSetter.setInteractiveTile();
        aSetter.loadEntitiesFromTMX();
        ui.addMessage("Objects reloaded", new gfx.Color(255, 220, 100), 120);
        playSE(SFX.MENU_SELECT);
    }

    private String debugToggleLabel(String label, boolean enabled) {
        return label + ": " + (enabled ? "ON" : "OFF");
    }

    private String getDebugMenuLabel(int row) {
        return switch (row) {
            case DEBUG_ROW_DEBUG_TEXT -> debugToggleLabel("Debug text", keyH.showDebugText);
            case DEBUG_ROW_HITBOXES -> debugToggleLabel("Hitboxes", HitBoxes);
            case DEBUG_ROW_PATHS -> debugToggleLabel("Path overlay", drawPath);
            case DEBUG_ROW_SEPIA -> mapShader == null
                    ? "Sepia shader: unavailable"
                    : debugToggleLabel("Sepia shader", mapShader.sepiaMode);
            case DEBUG_ROW_INVINCIBLE -> debugToggleLabel("Invincible", player.godMode);
            case DEBUG_ROW_RELOAD_ALL      -> "Full reload (tiles + entities)";
            case DEBUG_ROW_RELOAD_NPCS     -> "Reload NPCs";
            case DEBUG_ROW_RELOAD_MONSTERS -> "Reload Monsters";
            case DEBUG_ROW_RELOAD_OBJECTS  -> "Reload Objects";
            case DEBUG_ROW_FLASHBACK -> "Trigger memory flashback";
            case DEBUG_ROW_FRAGMENTS -> "Collect test fragments";
            case DEBUG_ROW_AWAKENING -> "Teleport: Awakening Cave";
            case DEBUG_ROW_TARGET_MAP -> "Target map: " + (debugMapList.isEmpty() ? "<none>" : debugMapList.get(debugMapSelectedIndex));
            case DEBUG_ROW_TELEPORT_TARGET -> "Teleport to target map";
            default -> "";
        };
    }

    /** Draw the debug panel overlay. Rendered in device-pixel space (like the FPS overlay) so its
     *  Consolas text stays crisp in fullscreen instead of being Nearest-magnified by pixelScale. */
    private void drawDebugMenu(GdxRenderer g2) {
        g2.beginDeviceSpace();
        final int panelW = 520;
        final int rowH = 24;
        final int panelH = DEBUG_MENU_ROWS * rowH + 82;
        // Center in the full window (device pixels), not the logical view.
        final int px = com.badlogic.gdx.Gdx.graphics.getBackBufferWidth()  / 2 - panelW / 2;
        final int py = com.badlogic.gdx.Gdx.graphics.getBackBufferHeight() / 2 - panelH / 2;

        // Background
        g2.setColor(new Color(10, 10, 30, 220));
        g2.fillRoundRect(px, py, panelW, panelH, 12, 12);
        g2.setColor(new Color(100, 180, 255));
        g2.drawRoundRect(px, py, panelW, panelH, 12, 12);

        // Title
        g2.setFont(new Font("Consolas", Font.BOLD, 14));
        g2.setColor(new Color(100, 180, 255));
        g2.drawString("[F9] DEBUG PANEL", px + 12, py + 20);

        g2.setFont(DEBUG_FONT);

        g2.setColor(new Color(160, 160, 160));
        g2.drawString("W/S select  |  A/D change map  |  ENTER toggle/run  |  ESC close", px + 12, py + 40);

        for (int row = 0; row < DEBUG_MENU_ROWS; row++) {
            int ry = py + 64 + row * rowH;

            if (row == debugMenuSelectedIndex) {
                g2.setColor(new Color(60, 120, 220, 180));
                g2.fillRoundRect(px + 6, ry - rowH + 5, panelW - 12, rowH, 6, 6);
                g2.setColor(Color.WHITE);
            } else {
                g2.setColor(new Color(180, 180, 180));
            }
            String line = getDebugMenuLabel(row);
            if (row == DEBUG_ROW_TARGET_MAP && !debugMapList.isEmpty()
                    && debugMapList.get(debugMapSelectedIndex).equals(mapManager.currentMapId)
                    && row != debugMenuSelectedIndex) {
                g2.setColor(new Color(80, 255, 140));
            }
            String prefix = (row == debugMenuSelectedIndex) ? "> " : "  ";
            g2.drawString(prefix + line, px + 14, ry);
        }

        g2.setColor(new Color(120, 120, 120));
        g2.drawString("Current map: " + mapManager.currentMapId,
                px + 10, py + panelH - 8);
        g2.endDeviceSpace();
    }

    public void drawToScreen() { /* libGDX renders every frame; no explicit repaint needed */ }

    public void playMusic(int i) {
        audio.playMusic(i);
    }
    public void stopMusic() {
        audio.stopMusic();
    }
    public void playSE(int i) {
        audio.playSE(i);
    }
    public void startDialogueTyping() {
        audio.startDialogueTyping();
    }
    public void stopDialogueTyping() {
        audio.stopDialogueTyping();
    }

    private static final gfx.Color NAMETAG_BOX_BG     = new gfx.Color(10, 8, 20, 170);
    private static final gfx.Color NAMETAG_BOX_BORDER = new gfx.Color(200, 185, 255, 90);
    private static final gfx.Color NAMETAG_TEXT_LOCAL = new gfx.Color(255, 240, 180);
    private static final gfx.Stroke NAMETAG_STROKE = new gfx.Stroke(1f);

    /**
     * Draw a translucent nametag box centered above a sprite.
     * centerX = horizontal center of the sprite on screen.
     * spriteTopY = top of the sprite on screen.
     * isLocal = true for the local player (gold text), false for remote players (light-blue text).
     */
    private void drawNametagBox(GdxRenderer g2, String name, int centerX, int spriteTopY, boolean isLocal) {
        if (name == null || name.isEmpty()) return;
        if (mpNametagFont == null) mpNametagFont = new Font("Arial", Font.BOLD, 12);
        g2.setFont(mpNametagFont);
        gfx.FontMetrics fm = g2.getFontMetrics();

        int padX = 7, padY = 3;
        int textW = fm.stringWidth(name);
        int textH = fm.getAscent();
        int boxW = textW + padX * 2;
        int boxH = textH + padY * 2;
        int boxX = centerX - boxW / 2;
        int boxY = spriteTopY - boxH - 4;

        // Translucent background
        float saved = g2.getAlpha();
        g2.setAlpha(0.82f);
        g2.setColor(NAMETAG_BOX_BG);
        g2.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
        g2.setColor(NAMETAG_BOX_BORDER);
        g2.setStroke(NAMETAG_STROKE);
        g2.drawRoundRect(boxX, boxY, boxW, boxH, 6, 6);
        g2.setAlpha(saved);

        // Name text
        int textX = boxX + padX;
        int textY = boxY + padY + textH - 1;
        g2.setColor(new gfx.Color(0, 0, 0, 130));
        g2.drawString(name, textX + 1, textY + 1);
        g2.setColor(isLocal ? NAMETAG_TEXT_LOCAL : MP_NAME_COLOR);
        g2.drawString(name, textX, textY);
    }

    /**
     * Draw local player nametag if a username has been set.
     * Called from RenderPipeline during the world render pass.
     */
    public void drawLocalPlayerNametag(GdxRenderer g2) {
        String name = ui.playerUsername;
        if (name == null || name.isEmpty()) return;
        int centerX = player.screenX + tileSize / 2;
        int spriteTopY = player.screenY;
        drawNametagBox(g2, name, centerX, spriteTopY, true);
    }

    /**
     * Draw all remote players from the multiplayer client.
     * Renders a simple colored rectangle with translucent nametag box above each player.
     * Uses the local player's sprite frames when available.
     */
    public void drawRemotePlayers(GdxRenderer g2) {
        drawRemotePlayers(g2, mpClient.remotePlayers);
        if (bleSession != null && bleSession.isActive()) {
            drawRemotePlayers(g2, bleSession.remotePlayers);
        }
    }

    private void drawRemotePlayers(GdxRenderer g2, java.util.Map<Integer, MultiplayerClient.RemotePlayerState> players) {
        long nowNs = System.nanoTime();
        for (java.util.Map.Entry<Integer, MultiplayerClient.RemotePlayerState> entry : players.entrySet()) {
            MultiplayerClient.RemotePlayerState rp = entry.getValue();
            float[] interp = rp.evalSpline(nowNs);
            int screenPosX = Math.round(interp[0]) - player.worldX + player.screenX;
            int screenPosY = Math.round(interp[1]) - player.worldY + player.screenY;

            // Skip if off-screen
            if (screenPosX + tileSize < -tileSize || screenPosX > screenWidth + tileSize) continue;
            if (screenPosY + tileSize < -tileSize || screenPosY > screenHeight + tileSize) continue;

            // Try to use player's walk frames for the remote player sprite
            Sprite sprite = null;
            if (player.walkFrames != null && rp.direction >= 0 && rp.direction < player.walkFrames.length) {
                Sprite[] dirFrames = player.walkFrames[rp.direction];
                if (dirFrames != null && dirFrames.length > 0) {
                    int frame = Math.max(0, Math.min(rp.spriteNum - 1, dirFrames.length - 1));
                    sprite = dirFrames[frame];
                }
            }

            if (sprite != null) {
                float old = g2.getAlpha();
                g2.drawImage(sprite, screenPosX, screenPosY, tileSize, tileSize);
                g2.setAlpha(0.15f);
                g2.setColor(MP_TINT_COLOR);
                g2.fillRect(screenPosX, screenPosY, tileSize, tileSize);
                g2.setAlpha(old);
            } else {
                // Fallback: colored rectangle
                g2.setColor(MP_FILL_COLOR);
                g2.fillRoundRect(screenPosX + 8, screenPosY + 8, tileSize - 16, tileSize - 16, 8, 8);
                g2.setColor(MP_BORDER_CLR);
                g2.setStroke(MP_STROKE_2);
                g2.drawRoundRect(screenPosX + 8, screenPosY + 8, tileSize - 16, tileSize - 16, 8, 8);
            }

            // Translucent nametag box above sprite
            drawNametagBox(g2, rp.name, screenPosX + tileSize / 2, screenPosY, false);

            // HP bar just above the nametag box
            if (rp.maxLife > 0) {
                int barW = 40;
                int barH = 4;
                int barX = screenPosX + tileSize / 2 - barW / 2;
                int barY = screenPosY - 28;
                g2.setColor(MP_BAR_BG);
                g2.fillRoundRect(barX, barY, barW, barH, 3, 3);
                float ratio = (float) rp.life / rp.maxLife;
                g2.setColor(ratio > 0.3f ? MP_BAR_GREEN : MP_BAR_RED);
                g2.fillRoundRect(barX, barY, (int) (barW * ratio), barH, 3, 3);
            }
        }
    }
}
package main;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

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
import map.AssetSetter;
import map.EventHandler;
import map.MapManager;
import map.MapObjectLoader;
import map.MobSpawner;
import tile.TileManager;
import tile.interactiveTile;
import ui.CutsceneManager;
import ui.Minimap;
import ui.RenderPipeline;
import ui.ScreenShake;
import ui.ThoughtBubble;
import ui.UI;
import util.ObjectPool;
import util.ResourceCache;

public class GamePanel extends JPanel implements Runnable{

    // SCREEN SETTINGS

    // Tile sizing is centralized in Config to support runtime scaling and
    // a single authoritative source for original/native tile size and scale.
    public final int originalTileSize = Config.originalTileSize; // 32 x 32 pixel (native)
    public final double scale = Config.scale;

    public final int tileSize = Config.tileSize; // runtime tile size (originalTileSize * scale)
    // Fixed display resolution — independent of tile size so zooming in doesn't change window size
    public final int screenWidth = 1280;
    public final int screenHeight = 720; // 16:9
    // Visible tile counts derived from resolution + tile size (+1 to cover partial edge tiles)
    public final int maxScreenCol = (int)Math.ceil((double)screenWidth / tileSize) + 1;
    public final int maxScreenRow = (int)Math.ceil((double)screenHeight / tileSize) + 1;
    /** UI horizontal scale factor: how much bigger the screen is than the 1280-wide reference. */
    public float uiSf()  { return screenWidth  / (float) Config.UI_BASE_W; }
    /** UI vertical scale factor: how much bigger the screen is than the 768-high reference. */
    public float uiSfH() { return screenHeight / (float) Config.UI_BASE_H; }

    // DEBUG
    public boolean HitBoxes = false;
    public boolean drawPath = false;
    // Debug panel (F9)
    public boolean debugMenuOpen = false;
    public int debugMenuSelectedIndex = 0;
    public java.util.List<String> debugMapList = new java.util.ArrayList<>();
    public int debugMapSelectedIndex = 0;

    // WORLD SETTINGS
    public final int maxWorldCol = 100;
    public final int maxWorldRow = 100;
    public final int worldWidth = tileSize * maxWorldCol;
    public final int worldHeight = tileSize * maxWorldRow;
    
    // FOR FULLSCREEN
    Rectangle windowedBounds;
    BufferedImage tempScreen;
    Graphics2D g2;
    // Saved identity transform — reset at the start of every frame to prevent
    // AffineTransform accumulation when a translate is not perfectly undone
    private java.awt.geom.AffineTransform identityTransform;

    public boolean fullScreenOn = false;

    // WINDOW CONTROL BUTTONS (drawn on-panel; window is always undecorated)
    private static final int WCB_SIZE = 18;
    private static final int WCB_GAP  = 4;
    private static final int WCB_TOP  = 5;
    private volatile java.awt.Point wcbHover = null;
    public boolean vSyncOn = true; // V-Sync toggle: sync rendering to monitor refresh rate

    // TIMING
    private static final int TARGET_UPS = 60; // Fixed simulation rate (game speed)
    int FPS = 60;  // Render target FPS (independent from game speed)
    public int currentFPS = 0;
    public int maxFPS = 0;      // Session peak FPS
    int monitorRefreshRate = 60; // Detected at startup
    private int tickCounter = 0; // Monotonic update tick for throttling

    // SYSTEM
    public TileManager tileM = new TileManager(this);
    public KeyHandler keyH = new KeyHandler(this);
    public AudioManager audio = new AudioManager();
    public CollisionChecker cChecker = new CollisionChecker(this);
    public AssetSetter aSetter = new AssetSetter(this);
    public MapObjectLoader mapObjectLoader = new MapObjectLoader(this);
    public UI ui = new UI(this);
    public EventHandler eHandler = new EventHandler(this);
    public Config config = new Config(this);
    public CutsceneManager csManager = new CutsceneManager(this);
    public PathFinder pFinder = new PathFinder(this);
    public EnvironmentManager eManager = new EnvironmentManager(this);
    public MapShaderManager mapShader;
    public environment.TileParticleEmitter tileParticleEmitter;
    public ScreenShake screenShake = new ScreenShake();
    public MobSpawner mobSpawner;
    public SaveLoad saveLoad = new SaveLoad(this);

    // PRE-ALLOCATED COLORS (avoid per-frame allocation)
    private static final java.awt.Color PLAYER_GLOW_COLOR = new java.awt.Color(255, 240, 220);
    private static final java.awt.Color DEFAULT_TORCH_COLOR = new java.awt.Color(255, 170, 60);

    // PRE-ALLOCATED DEBUG OVERLAY OBJECTS (avoid per-frame allocation)
    private static final java.awt.Font DEBUG_FONT = new java.awt.Font("Consolas", java.awt.Font.PLAIN, 13);
    private static final java.awt.Color DEBUG_BG_COLOR = new java.awt.Color(0, 0, 0, 160);
    private static final java.awt.Color DEBUG_FPS_GREEN = new java.awt.Color(80, 255, 80);
    private static final java.awt.Color DEBUG_FPS_YELLOW = new java.awt.Color(255, 220, 60);
    private static final java.awt.Color DEBUG_FPS_RED = new java.awt.Color(255, 80, 80);
    private static final java.awt.Color DEBUG_INFO_BLUE = new java.awt.Color(120, 200, 255);
    private static final java.awt.Color MP_TINT_COLOR  = new java.awt.Color(100, 180, 255);
    private static final java.awt.Color MP_FILL_COLOR  = new java.awt.Color(80, 160, 240, 200);
    private static final java.awt.Color MP_BORDER_CLR  = new java.awt.Color(50, 120, 200);
    private static final java.awt.Color MP_NAME_SHADOW = new java.awt.Color(0, 0, 0, 160);
    private static final java.awt.Color MP_NAME_COLOR  = new java.awt.Color(180, 220, 255);
    private static final java.awt.Color MP_BAR_BG      = new java.awt.Color(40, 40, 40, 180);
    private static final java.awt.Color MP_BAR_GREEN   = new java.awt.Color(60, 200, 80);
    private static final java.awt.Color MP_BAR_RED     = new java.awt.Color(220, 60, 60);
    private static final java.awt.BasicStroke MP_STROKE_2 = new java.awt.BasicStroke(2f);
    private static final int DEBUG_ROW_DEBUG_TEXT = 0;
    private static final int DEBUG_ROW_HITBOXES = 1;
    private static final int DEBUG_ROW_PATHS = 2;
    private static final int DEBUG_ROW_SEPIA = 3;
    private static final int DEBUG_ROW_RELOAD_MAP = 4;
    private static final int DEBUG_ROW_FLASHBACK = 5;
    private static final int DEBUG_ROW_FRAGMENTS = 6;
    private static final int DEBUG_ROW_AWAKENING = 7;
    private static final int DEBUG_ROW_TARGET_MAP = 8;
    private static final int DEBUG_ROW_TELEPORT_TARGET = 9;
    private static final int DEBUG_MENU_ROWS = 10;
    private java.awt.Font mpNametagFont; // lazily derived


    // WORLD MAP (full-screen overlay, no corner HUD)
    public Minimap minimap;

    // RENDER PIPELINE (extracted from GamePanel)
    public RenderPipeline renderPipeline;

    // QUEST SYSTEM
    public QuestManager questManager;

    // MEMORY SYSTEM
    public data.MemoryJournal memoryJournal;
    public environment.MemoryFlashback memoryFlashback;

    // THOUGHT BUBBLE (non-blocking inner monologue)
    public ThoughtBubble thoughts;

    // STORY PROGRESS
    public boolean boss1Defeated;
    public boolean boss2Defeated;
    public boolean boss3Defeated;
    public boolean boss4Defeated;
    public int storyAct;       // 0=tutorial, 1=shatterLake, 2=ashenWoods, 3=citadel, 4=gallery, 5=frame
    public int endingChosen;   // 0=none, 1=confront, 2=sacrifice, 3=forgive

    // PERMANENTLY OPENED GATES (LevelGates with permanentOpen=true that the player has passed through)
    public java.util.Set<String> openedGates = new java.util.HashSet<>();

    // MAP MANAGEMENT (extracted from GamePanel)
    public MapManager mapManager;

    // GLOBAL HIT-STOP: freezes all entities for impactful hits
    public int globalHitstopTimer = 0;

    // CAMERA LOCK: for cutscenes that detach the camera from the player
    public boolean cameraLocked  = false;
    public int     cameraWorldX  = 0;   // world X to center on when locked
    public int     cameraWorldY  = 0;   // world Y to center on when locked

    /** Returns the world X the camera is currently tracking. */
    public int getCamWorldX() { return cameraLocked ? cameraWorldX : player.worldX; }
    /** Returns the world Y the camera is currently tracking. */
    public int getCamWorldY() { return cameraLocked ? cameraWorldY : player.worldY; }
    /** Returns the screen X offset for world-to-screen projection. Always matches player.screenX. */
    public int getCamScreenX() { return player.screenX; }
    /** Returns the screen Y offset for world-to-screen projection. Always matches player.screenY. */
    public int getCamScreenY() { return player.screenY; }
    /**
     * Lock the camera to a world position (tile coordinates).
     * Use for cutscenes, boss intros, etc. Call unlockCamera() to restore.
     */
    public void lockCamera(int tileCol, int tileRow) {
        cameraWorldX = tileCol * tileSize;
        cameraWorldY = tileRow * tileSize;
        cameraLocked = true;
    }
    /** Restore the camera to follow the player. */
    public void unlockCamera() { cameraLocked = false; }

    // DAMAGE NUMBERS
    public ObjectPool<entity.DamageNumber> damageNumberPool;
    public java.util.ArrayList<entity.DamageNumber> damageNumbers = new java.util.ArrayList<>();
    public Thread gameThread;

    //ENTITY AND OBJECT
    public Player player = new Player(this,keyH);
    public Entity obj[] = new Entity[100];
    public Entity npc[] = new Entity[10];
    public Entity monster[] = new Entity[20];
    public interactiveTile iTile[] = new interactiveTile[30]; // expanded for breakable pots
    public ArrayList<Entity> projectilesList = new ArrayList<>();
    public ArrayList<Entity> particleList = new ArrayList<>();
    // OPTIMIZATION: Object pools for reusable projectiles and particles
    public ObjectPool<Projectile> projectilePool;
    public ObjectPool<Particle> particlePool;

    // INTERACTION PROMPT: set by Player when near an interactable obstacle
    public Entity nearbyInteractable;
    /** When true, player movement and actions are temporarily blocked (e.g. door animation). */
    public boolean inputLocked = false;

    // GAME STATE — integer constants kept for backward compatibility
    // New code should use GameState enum where possible.
    public int gameState;
    public static final int titleState = 0;
    public static final int playState = 1;
    public static final int pauseState = 2;
    public static final int dialogueState = 3;
    public static final int characterState = 4;
    public static final int optionsState = 5;
    public static final int gameOverState = 6;
    public static final int cutsceneState = 7;
    public static final int transitionState = 8;
    public static final int levelUpState = 9;
    public static final int skillTreeState = 10;
    public static final int multiplayerPlayState = 11;
    public static final int journalState = 12;

    // MULTIPLAYER
    public MultiplayerClient mpClient;
    public ServerListManager serverList;
    public boolean multiplayerMode = false;
    public boolean teleportation = false;
    public boolean bootsUnlocked = false;
    public boolean deathSoundPlayed = false;

    // BACKWARD-COMPATIBLE DELEGATION: Map fields now live in MapManager.
    // These accessors keep old code compiling while we migrate callers.
    // Use gp.mapManager.fieldName directly in new code.
    public Map<String, String> getMapRegistry() { return mapManager.mapRegistry; }
    public String getCurrentMapId() { return mapManager.currentMapId; }

    public GamePanel() {

        this.setPreferredSize(new Dimension(screenWidth, screenHeight));
        this.setBackground(Color.black);
        this.setDoubleBuffered(true);
        this.addKeyListener(keyH);
        this.setFocusable(true);

        // Window drag — the window is always undecorated (no title bar), so we let
        // the player drag it by clicking anywhere on the panel when in windowed mode.
        // Ignored in fullscreen (dragging a maximized window does nothing anyway).
        java.awt.Point[] dragStart = {null};
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                java.awt.Rectangle[] btns = getWCBRects();
                if (btns[0].contains(e.getPoint())) { System.exit(0); return; }
                if (btns[1].contains(e.getPoint())) { applyFullScreenSetting(!fullScreenOn); return; }
                if (btns[2].contains(e.getPoint())) {
                    if (Main.window != null) Main.window.setExtendedState(JFrame.ICONIFIED); return;
                }
                if (!fullScreenOn) dragStart[0] = e.getLocationOnScreen();
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                dragStart[0] = null;
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                wcbHover = null;
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                wcbHover = e.getPoint();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (!fullScreenOn && dragStart[0] != null && Main.window != null) {
                    java.awt.Point cur = e.getLocationOnScreen();
                    java.awt.Point loc = Main.window.getLocation();
                    Main.window.setLocation(loc.x + cur.x - dragStart[0].x,
                                            loc.y + cur.y - dragStart[0].y);
                    dragStart[0] = cur;
                }
            }
        });
    }

    public void setupGame() {

    // Validate critical assets
    new AssetValidator().validate();

    // Initialize map manager
    mapManager = new MapManager(this);

    // Auto-discover all maps from /res/maps/ directory
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
    // Apply the TMX-defined spawn point now that events are loaded
    if (!mapManager.loadingGame) {
        player.setDefaultPositions();
        // Load map-level properties (music, weather, dialogueTrigger, etc.) for the initial map
        String initialPath = mapManager.mapRegistry.getOrDefault(mapManager.currentMapId, "");
        if (!initialPath.isEmpty()) {
            mapObjectLoader.loadMapProperties(initialPath);
        }
        // Show spawn message if the map defines one
        if (!mapManager.pendingDialogueTrigger.isEmpty()) {
            ui.addMessage(mapManager.pendingDialogueTrigger, new java.awt.Color(255, 240, 180), mapManager.pendingDialogueTriggerDuration);
            mapManager.pendingDialogueTrigger = "";
        }
    }
    mobSpawner = new MobSpawner(this);
    thoughts = new ThoughtBubble(this);
    gameState = titleState;

    // MULTIPLAYER: initialize client and server list
    mpClient = new MultiplayerClient(this);
    serverList = new ServerListManager();

    // OPTIMIZATION: Initialize collision cache
    cChecker.updateCollisionRectsCache();

    // OPTIMIZATION: Initialize object pools for projectiles and particles
    // Pool config: initial size = 20, expand by 10 when empty
    projectilePool = new ObjectPool<>(
        () -> new Projectile(this),
        20,  // initial pool size
        10   // expand size
    );
    
    // Pool config: initial size = 40 (particles created 4 at a time), expand by 20
    particlePool = new ObjectPool<>(
        () -> new Particle(this, null, null, 0, 0, 0, 0, 0),
        40,  // initial pool size
        20   // expand size
    );

    // DETECT MONITOR REFRESH RATE + APPLY USER V-SYNC SETTING
    detectAndSetRefreshRate();
    setVSync(vSyncOn);

    // SHADER EFFECTS: Initialize map shader manager (water shimmer, particles, vignette, color grading)
    mapShader = new MapShaderManager(this);
    mapShader.setup();

    // TILE PARTICLES: footstep dust/grass/stone particles when entities move
    tileParticleEmitter = new environment.TileParticleEmitter(this);

    // DAMAGE NUMBERS: pooled floating text
    damageNumberPool = new ObjectPool<>(
        () -> new entity.DamageNumber(this),
        15, 10
    );

    // WORLD MAP: create and bake initial terrain
    minimap = new Minimap(this);
    minimap.bakeTerrainImage();

    // QUEST SYSTEM
    questManager = new QuestManager(this);

    // MEMORY SYSTEM
    memoryJournal = new data.MemoryJournal();
    memoryFlashback = new environment.MemoryFlashback(this);

    memoryJournal.registerFragment("frag_cave", "Awakening Cave",
        new String[]{"The air was thick with dust and silence.", "He had no memory of how he got here.", "Only a faint glimmer of light in the distance."},
        1, "battle_cave");
    // [TEST] Register sample memory fragments (remove when real fragments are wired)
    memoryJournal.registerFragment("frag_prologue", "The Shattered Throne",
        new String[]{"The crown fell before the war ended.", "No one claimed it.", "No one dared."},
        2, "prologue");
    memoryJournal.registerFragment("frag_forest", "Voices in the Ash",
        new String[]{"She heard her name in the smoke.", "She did not turn back."},
        3, "ashen_woods");
    memoryJournal.registerFragment("frag_lake", "Shatter Lake",
        new String[]{"The water remembered everything.", "He had stood here before.", "So had she.", "Never together."},
        4, "shatter_lake");

    // RENDER PIPELINE
    renderPipeline = new RenderPipeline(this);

    // OPTIMIZATION: Hardware-compatible back buffer — stored in VRAM when possible
    java.awt.GraphicsConfiguration gc = java.awt.GraphicsEnvironment
        .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
    tempScreen = gc.createCompatibleImage(screenWidth, screenHeight, java.awt.Transparency.OPAQUE);
    g2 = (Graphics2D) tempScreen.getGraphics();
    
    // OPTIMIZATION: Set rendering hints once at setup instead of per-frame
    g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_SPEED);
    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    g2.setRenderingHint(java.awt.RenderingHints.KEY_COLOR_RENDERING, java.awt.RenderingHints.VALUE_COLOR_RENDER_SPEED);

    // Save the clean identity transform so we can reset to it at the start of every frame
    identityTransform = g2.getTransform();

    // Startup fullscreen is applied in Main.java (invokeAndWait) before this method
    // runs, so no action needed here.
    }

    // --- MAP MANAGEMENT DELEGATION (methods now in MapManager) ---
    public void registerMap(String id, String tmxPath) { mapManager.registerMap(id, tmxPath); }
    public void startTransition(String mapId, int spawnCol, int spawnRow) { mapManager.startTransition(mapId, spawnCol, spawnRow); }
    public void changeMap() { mapManager.changeMap(); }
    public void changeMap(String mapIdOrPath, int spawnCol, int spawnRow) { mapManager.changeMap(mapIdOrPath, spawnCol, spawnRow); }
    public void resetGame(boolean restart) { mapManager.resetGame(restart); }

    public void setFullScreen() {

        applyWindowMode(true);

    }

    public void applyFullScreenSetting(boolean enable) {
        if (fullScreenOn == enable) {
            return;
        }

        fullScreenOn = enable;

        // Ensure Swing window changes happen on EDT to avoid runtime UI issues.
        SwingUtilities.invokeLater(() -> {
            applyWindowMode(enable);
            config.saveConfig();
        });
    }

    private void applyWindowMode(boolean enableFullScreen) {
        JFrame window = Main.window;
        if (window == null) return;

        // The window is always undecorated (set in Main.java before first setVisible).
        // This means we never need dispose() to change decoration — we just toggle
        // the extended state. No dispose = no OpenGL surface destruction = no black screen.
        if (enableFullScreen) {
            windowedBounds = window.getBounds();  // save for restore
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            window.setExtendedState(JFrame.MAXIMIZED_BOTH);
            // Explicitly set bounds after MAXIMIZED_BOTH — on some Windows+OpenGL
            // configurations the state change alone doesn't trigger a proper geometry
            // update, leaving the panel at its old size and causing a black frame.
            window.setBounds(0, 0, screen.width, screen.height);
        } else {
            window.setExtendedState(JFrame.NORMAL);
            if (windowedBounds != null && windowedBounds.width > 0) {
                window.setBounds(windowedBounds);
            } else if (config.windowX >= 0 && config.windowY >= 0) {
                window.setSize(screenWidth, screenHeight);
                window.setLocation(config.windowX, config.windowY);
            } else {
                window.setSize(screenWidth, screenHeight);
                window.setLocationRelativeTo(null);
            }
        }

        window.validate();
        window.toFront();
        requestFocusInWindow();

        // Recreate the hardware back-buffer after the window mode change.
        // With sun.java2d.opengl=True the CompatibleImage backing texture is tied to
        // the window's GL surface; resizing the window (especially to full-screen)
        // invalidates that texture. drawImage then silently draws nothing, producing
        // a persistent black screen. Creating a fresh CompatibleImage from the
        // current GraphicsConfiguration gives us a clean, valid surface.
        // The second invokeLater lets the window settle (validate/repaint cycle)
        // before we swap the buffers, so we get the correct GC for the new size.
        SwingUtilities.invokeLater(this::recreateBackBuffer);
    }

    private void recreateBackBuffer() {
        java.awt.GraphicsConfiguration gc = java.awt.GraphicsEnvironment
            .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage newScreen = gc.createCompatibleImage(
            screenWidth, screenHeight, java.awt.Transparency.OPAQUE);
        Graphics2D newG2 = (Graphics2D) newScreen.getGraphics();
        newG2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
            java.awt.RenderingHints.VALUE_RENDER_SPEED);
        newG2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        newG2.setRenderingHint(java.awt.RenderingHints.KEY_COLOR_RENDERING,
            java.awt.RenderingHints.VALUE_COLOR_RENDER_SPEED);
        java.awt.geom.AffineTransform newIdentity = newG2.getTransform();
        synchronized (this) {
            if (g2 != null) g2.dispose();
            g2 = newG2;
            tempScreen = newScreen;
            identityTransform = newIdentity;
        }
    }

    private java.awt.Rectangle[] getWCBRects() {
        int pw = getWidth();
        int closeX = pw - WCB_GAP - WCB_SIZE;
        int fullX  = closeX - WCB_GAP - WCB_SIZE;
        int minX   = fullX  - WCB_GAP - WCB_SIZE;
        return new java.awt.Rectangle[] {
            new java.awt.Rectangle(closeX, WCB_TOP, WCB_SIZE, WCB_SIZE),
            new java.awt.Rectangle(fullX,  WCB_TOP, WCB_SIZE, WCB_SIZE),
            new java.awt.Rectangle(minX,   WCB_TOP, WCB_SIZE, WCB_SIZE),
        };
    }

    private void drawWindowControls(Graphics2D g2d) {
        java.awt.Rectangle[] rects = getWCBRects();
        java.awt.Point hover = wcbHover;

        java.awt.RenderingHints savedHints = g2d.getRenderingHints();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        java.awt.Stroke savedStroke = g2d.getStroke();
        g2d.setStroke(new java.awt.BasicStroke(1.6f,
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));

        for (int i = 0; i < 3; i++) {
            java.awt.Rectangle r = rects[i];
            boolean hovered = (hover != null && r.contains(hover));

            Color base = (i == 0) ? new Color(160, 45, 45, 210)
                       : (i == 1) ? new Color(45, 110, 175, 210)
                                  : new Color(70, 70, 70, 190);
            g2d.setColor(hovered ? base.brighter() : base);
            g2d.fillRoundRect(r.x, r.y, r.width, r.height, 5, 5);

            g2d.setColor(new Color(255, 255, 255, 215));
            int cx = r.x + r.width  / 2;
            int cy = r.y + r.height / 2;
            int m  = 4;

            if (i == 0) {
                g2d.drawLine(cx - m, cy - m, cx + m, cy + m);
                g2d.drawLine(cx + m, cy - m, cx - m, cy + m);
            } else if (i == 1) {
                if (fullScreenOn) {
                    g2d.drawRect(cx - m + 2, cy - m,     m * 2 - 3, m * 2 - 3);
                    g2d.drawRect(cx - m,     cy - m + 2, m * 2 - 3, m * 2 - 3);
                } else {
                    g2d.drawRect(cx - m, cy - m, m * 2, m * 2);
                }
            } else {
                g2d.drawLine(cx - m, cy + 2, cx + m, cy + 2);
            }
        }

        g2d.setStroke(savedStroke);
        g2d.setRenderingHints(savedHints);
    }

    /**
     * Toggle V-Sync on/off. Affects whether the game syncs to monitor refresh rate or runs uncapped.
     * Note: Enable recommended for smooth gameplay, disable for maximum FPS in benchmarks.
     */
    public void setVSync(boolean enabled) {
        vSyncOn = enabled;
        if (enabled) {
            System.setProperty("sun.java2d.vsync", "True");
            FPS = monitorRefreshRate;
            System.out.println("[V-SYNC] Enabled - Game synced to monitor refresh rate (" + monitorRefreshRate + " Hz)");
        } else {
            System.setProperty("sun.java2d.vsync", "False");
            FPS = 0; // Uncapped — render as fast as possible
            System.out.println("[V-SYNC] Disabled - Render target UNCAPPED");
        }
        applyFpsTarget(config.fpsTarget); // override cap if performance mode is active
    }

    /**
     * Apply an explicit FPS cap (30 = performance mode, 60 = normal, 0 = use vSync/uncapped).
     * Takes priority over V-Sync when non-zero and lower than monitor rate.
     */
    public void applyFpsTarget(int target) {
        config.fpsTarget = target;
        if (target > 0) {
            FPS = target;
            System.out.println("[FPS] Cap set to " + target + " FPS");
        } else {
            // 0 means defer to vSync setting
            FPS = vSyncOn ? monitorRefreshRate : 0;
        }
    }

    /**
     * Detect the primary monitor's refresh rate and adjust game FPS to match.
     * This eliminates screen tearing by syncing game updates to the monitor's vertical refresh.
     */
    private void detectAndSetRefreshRate() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] gd = ge.getScreenDevices();
            if (gd.length > 0) {
                int refreshRate = gd[0].getDisplayMode().getRefreshRate();
                // Only use detected rate if it's reasonable (60-300 Hz typical range)
                if (refreshRate >= 30 && refreshRate <= 300) {
                    monitorRefreshRate = refreshRate;
                    System.out.println("[DISPLAY] Monitor detected: " + refreshRate + " Hz");
                } else {
                    System.out.println("[V-SYNC] Monitor refresh rate " + refreshRate + " Hz out of range, using default 60 Hz");
                }
            }
        } catch (Exception e) {
            System.out.println("[V-SYNC] Could not detect monitor refresh rate, using default 60 Hz");
            e.printStackTrace();
        }
    }

    public void startGameThread(){

        gameThread = new Thread(this);
        gameThread.start();
    }

    @Override
    public void run() {

        final double updateInterval = 1_000_000_000.0 / TARGET_UPS;
        double updateDelta = 0;
        double renderDelta = 0;
        double drawInterval = (FPS > 0) ? 1_000_000_000.0 / Math.max(30, FPS) : 0;
        boolean uncapped = (FPS <= 0);
        long lastTime = System.nanoTime();
        long currentTime;
        long timer = 0;
        int frameCount = 0;
        int cachedFPS = FPS;

        while(gameThread != null) {

            currentTime = System.nanoTime();
            long elapsed = currentTime - lastTime;

            updateDelta += elapsed / updateInterval;

            // Only recalculate draw interval when FPS target changes (e.g. V-Sync toggle)
            if (cachedFPS != FPS) {
                cachedFPS = FPS;
                uncapped = (cachedFPS <= 0);
                drawInterval = uncapped ? 0 : 1_000_000_000.0 / Math.max(30, cachedFPS);
            }
            if (!uncapped) {
                renderDelta += elapsed / drawInterval;
            }

            timer += elapsed;
            lastTime = currentTime;

            // Cap update catch-up to prevent spiral of death
            if (updateDelta > 5) updateDelta = 5;

            while (updateDelta >= 1) {
                update();
                updateDelta--;
            }

            // Render: uncapped mode renders every loop iteration, capped mode uses delta
            if (uncapped || renderDelta >= 1) {
                synchronized (this) {
                    drawToTempScreen();
                }
                repaint();
                frameCount++;
                if (!uncapped) {
                    renderDelta -= 1;
                    if (renderDelta > 1) renderDelta = 1;
                }
            }

            if(timer >= 1_000_000_000) {
                currentFPS = frameCount;
                if (currentFPS > maxFPS) maxFPS = currentFPS;
                frameCount = 0;
                timer = 0;
            }

            // Smart sleep: only when capped and there's enough headroom
            if (!uncapped) {
                long now = System.nanoTime();
                long nextFrame = lastTime + (long) drawInterval;
                long remaining = nextFrame - now;
                if (remaining > 2_000_000) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (remaining > 0) {
                    Thread.onSpinWait();
                }
            } else {
                // Uncapped: yield briefly to prevent 100% CPU core usage
                Thread.onSpinWait();
            }

        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        int panelW = getWidth();
        int panelH = getHeight();
        // Guard: panel not yet laid out (happens during window initialization/dispose cycle)
        if (panelW <= 0 || panelH <= 0) return;

        synchronized (this) {
            Graphics2D g2d = (Graphics2D) g;

            // Deep atmospheric dark — matches the game's dungeon/night palette rather
            // than jarring pure black; fills letterbox/pillarbox bars around the game image.
            g2d.setColor(new java.awt.Color(8, 6, 14));
            g2d.fillRect(0, 0, panelW, panelH);

            if (tempScreen != null) {
                // Aspect-ratio-correct scale: largest uniform scale that fits the game
                // resolution inside the panel without distorting square pixels.
                float scaleX = (float) panelW / screenWidth;
                float scaleY = (float) panelH / screenHeight;
                float scale  = Math.min(scaleX, scaleY);

                int dstW = (int)(screenWidth  * scale);
                int dstH = (int)(screenHeight * scale);
                int dstX = (panelW - dstW) / 2;  // center horizontally
                int dstY = (panelH - dstH) / 2;  // center vertically

                g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2d.drawImage(tempScreen, dstX, dstY, dstW, dstH, null);

                // Subtle 1px accent border — frames the game image intentionally
                // against the bars so it looks designed, not accidental.
                if (dstX > 0 || dstY > 0) {
                    g2d.setColor(new java.awt.Color(55, 45, 35, 110));
                    g2d.drawRect(dstX, dstY, dstW - 1, dstH - 1);
                }
            }
        }
        drawWindowControls((Graphics2D) g);
        Toolkit.getDefaultToolkit().sync();
    }


    /** Trigger a global hit-stop freeze. Only overrides if stronger than current. */
    public void triggerHitstop(int frames) {
        if (frames > globalHitstopTimer) globalHitstopTimer = frames;
    }

    public void update() {
        tickCounter++;

        // MEMORY FLASHBACK: update even during other states (it's an overlay)
        if (memoryFlashback != null && memoryFlashback.isActive()) {
            memoryFlashback.update();
            if (memoryFlashback.getState() == environment.MemoryFlashback.DONE) {
                memoryFlashback.finish();
            }
            return; // freeze everything else during flashback
        }

        // THOUGHT BUBBLE: ticks during play and cutscene states
        if (thoughts != null) thoughts.update();

        // INPUT COOLDOWNS & MENU KEY-REPEAT: must tick every frame regardless of state
        keyH.update();

        // UI ANIMATION TICK: always advance at the fixed 60 Hz update rate,
        // independent of render FPS so pulse/breathe animations never speed up on fast machines
        // or slow down on weak ones.
        ui.updateAnimations();

        // ANIMATIONS DURING DIALOGUE: keep player and NPC animations playing while talking
        if (gameState == dialogueState) {
            player.tickAnimations();
            for (int i = 0; i < npc.length; i++) {
                if (npc[i] != null && isEntityInViewport(npc[i], tileSize * 2)) {
                    npc[i].tickAnimations();
                }
            }
        }

        if(gameState == playState) {
            // Refresh viewport cache once per frame
            vpCacheValid = false;
            updateViewportCache();

            // GLOBAL HIT-STOP: freeze entities but keep visual feedback running
            if (globalHitstopTimer > 0) {
                globalHitstopTimer--;
                // Still update visual-only systems during freeze
                for (int i = particleList.size() - 1; i >= 0; i--) {
                    Entity particle = particleList.get(i);
                    if (particle != null) {
                        if (particle.alive) particle.update();
                        else { particleList.remove(i); if (particle instanceof Particle) particlePool.release((Particle) particle); }
                    }
                }
                if (tileParticleEmitter != null) tileParticleEmitter.update();
                if (mapShader != null) mapShader.update();
                screenShake.update();
                for (int i = damageNumbers.size() - 1; i >= 0; i--) {
                    entity.DamageNumber dn = damageNumbers.get(i);
                    if (dn.alive) dn.update();
                    else { damageNumbers.remove(i); damageNumberPool.release(dn); }
                }
                return; // skip all entity/world logic
            }

            // PLAYER
            player.update();
            // DOOR ANIMATIONS: only tick doors that are actively opening
            for (int i = 0; i < obj.length; i++) {
                if (obj[i] instanceof object.OBJ_Door door && door.doorOpening) {
                    door.update();
                }
            }
            // NPC
            for ( int i = 0 ; i < npc.length; i++ ) {
                if ( npc[i] != null ) {
                    if (isEntityInViewport(npc[i], tileSize * 2)) {
                        npc[i].update();
                    }
                }
            }
            // MONSTER
            for ( int i = 0 ; i < monster.length ; i++ ) {
                if ( monster[i] != null ) {
                    if ( monster[i].alive && !monster[i].dying ) {
                        // OPTIMIZATION: Only update monsters that are in or near viewport
                        if (isEntityInViewport(monster[i], tileSize * 2)) {
                            monster[i].update();
                        } else if (isEntityInViewport(monster[i], tileSize * 6)
                                   && (tickCounter & 3) == 0) {
                            // Distant monsters: run AI every 4 frames to prevent snap-teleport on re-entry
                            monster[i].setAction();
                        }
                    }
                    if ( !monster[i].alive ) {
                        // Boss death: trigger story progression
                        if (monster[i] instanceof entity.BossMonster bm) {
                            bm.onDeath();
                        }
                        monster[i] = null;
                    }
                }
            }
            // OPTIMIZATION: Use backwards iteration to safely remove while iterating
            for ( int i = projectilesList.size() - 1 ; i >= 0 ; i-- ) {
                Entity proj = projectilesList.get(i);
                if (proj != null) {
                    if (proj.alive) {
                        proj.update();
                    } else {
                        projectilesList.remove(i);
                        // OPTIMIZATION: Return projectile to pool for reuse
                        projectilePool.release((Projectile) proj);
                    }
                }
            }
            // OPTIMIZATION: Use backwards iteration to safely remove while iterating
            for ( int i = particleList.size() - 1 ; i >= 0 ; i-- ) {
                Entity particle = particleList.get(i);
                if (particle != null) {
                    if (particle.alive) {
                        particle.update();
                    } else {
                        particleList.remove(i);
                        // OPTIMIZATION: Return particle to pool for reuse (skip non-poolable entities like BossSwingEffect)
                        if (particle instanceof Particle) {
                            particlePool.release((Particle) particle);
                        }
                    }
                }
            }
            for ( int i = 0 ; i < iTile.length ; i++ ) {
                if ( iTile[i] != null ) {
                    if (iTile[i].invincible || isEntityInViewport(iTile[i], tileSize)) {
                        iTile[i].update();
                    }
                }
            }
            eManager.update();
            mobSpawner.update();
            eHandler.updateSpawnZones();
            // ANIMATED TILES: advance tile animation frames
            tileM.update();

            // COLORED LIGHTS: Register dynamic light sources each frame
            if (eManager.lightning != null) {
                eManager.lightning.clearLights();
                // Player warm glow
                eManager.lightning.addLight(
                    player.worldX + tileSize / 2, player.worldY + tileSize / 2,
                    tileSize * 4, PLAYER_GLOW_COLOR, 0.25f);
                // Torch objects: warm orange with subtle flicker
                float flickerBase = System.nanoTime() * 0.000000003f;
                for (int i = 0; i < obj.length; i++) {
                    if (obj[i] != null && obj[i].lightSource && obj[i].lightRadius > 0) {
                        float flicker = 0.22f + 0.06f * MapShaderManager.fastSin(flickerBase + i * 1.7);
                        java.awt.Color lc = (obj[i].lightColor != null) ? obj[i].lightColor : DEFAULT_TORCH_COLOR;
                        eManager.lightning.addLight(
                            obj[i].worldX + tileSize / 2, obj[i].worldY + tileSize / 2,
                            obj[i].lightRadius * tileSize, lc, flicker);
                    }
                }
            }

            // SHADER EFFECTS: advance animation tick & ambient particles
            if (mapShader != null) {
                mapShader.update();
            }

            // TILE PARTICLES: update footstep particles
            if (tileParticleEmitter != null) {
                tileParticleEmitter.update();
            }

            // SCREEN SHAKE
            screenShake.update();

            // DAMAGE NUMBERS
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
            // MULTIPLAYER: send position data to server
            if (multiplayerMode && mpClient != null && mpClient.isConnected()) {
                mpClient.update();
            }

            if (player.life <= 0) {
                // Death-save skills
                boolean saved = false;
                if (player.undyingWillUnlocked && player.undyingWillCooldown <= 0) {
                    player.life = 1;
                    player.undyingWillCooldown = 5400; // 90 seconds at 60 FPS
                    player.invincible = true;
                    ui.addMessage("Undying Will activates!", new java.awt.Color(255, 215, 0));
                    screenShake.shakeHeavy();
                    saved = true;
                } else if (player.secondWindUnlocked && player.secondWindAvailable) {
                    player.life = Math.max(1, (int)(player.maxLife * 0.3f));
                    player.secondWindAvailable = false;
                    player.invincible = true;
                    ui.addMessage("Second Wind!", new java.awt.Color(100, 200, 100));
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
     * OPTIMIZATION: Check if an entity is within viewport range (with margin buffer).
     * Only entities on or near the screen need their update() called. 
     * @param entity The entity to check
     * @param margin Extra distance beyond screen boundaries to include (for smooth transitions)
     * @return true if the entity should be updated
     */
    // OPTIMIZATION: Cache viewport bounds per frame instead of recalculating per entity
    private int vpMinX, vpMaxX, vpMinY, vpMaxY;
    private boolean vpCacheValid = false;

    private void updateViewportCache() {
        vpMinX = player.worldX - player.screenX;
        vpMaxX = vpMinX + screenWidth;
        vpMinY = player.worldY - player.screenY;
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

    public void drawToTempScreen() {

    // Reset the Graphics2D transform to identity each frame — prevents AffineTransform
    // accumulation if any drawing code forgets to undo a translate/rotate.
    g2.setTransform(identityTransform);
    // Clear the back buffer to black; without this, ghosting occurs whenever
    // the tile or UI layers don't cover every pixel (e.g. transitions, title screen).
    g2.setBackground(mapManager != null ? mapManager.mapBackgroundColor : Color.BLACK);
    g2.clearRect(0, 0, screenWidth, screenHeight);

    renderPipeline.drawCurrentState(g2);

    // MEMORY FLASHBACK OVERLAY (drawn on top of everything)
    if (memoryFlashback != null && memoryFlashback.isActive()) {
        memoryFlashback.draw(g2);
    }

    // DEBUG TEXT
    if(keyH.showDebugText) {
        final int x = 10;
        int y = 400;
        final int lineHeight = 20;
        final int padX = 8, padY = 6;
        final int lines = 9 + (tileParticleEmitter != null ? 1 : 0);
        final int boxW = 230, boxH = lines * lineHeight + padY * 2;

        // Semi-transparent background panel
        g2.setColor(DEBUG_BG_COLOR);
        g2.fillRoundRect(x - padX, y - lineHeight - padY, boxW, boxH, 8, 8);

        g2.setFont(DEBUG_FONT);
        final int safeFPS = currentFPS > 0 ? currentFPS : 1;
        final String frameTimeStr = String.format("%.2f", 1000.0 / safeFPS);
        final String minFrameTimeStr = maxFPS > 0 ? String.format("%.2f", 1000.0 / maxFPS) : "--";

        // FPS line — colour-coded: green >= target, yellow >= half, red below
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
        switch (debugMenuSelectedIndex) {
            case DEBUG_ROW_DEBUG_TEXT -> {
                keyH.showDebugText = !keyH.showDebugText;
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_HITBOXES -> {
                HitBoxes = !HitBoxes;
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_PATHS -> {
                drawPath = !drawPath;
                playSE(SFX.MENU_SELECT);
            }
            case DEBUG_ROW_SEPIA -> toggleDebugSepia();
            case DEBUG_ROW_RELOAD_MAP -> reloadCurrentMapDebug();
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

    public void reloadCurrentMapDebug() {
        if (mapManager == null) return;
        String path = mapManager.mapRegistry.getOrDefault(mapManager.currentMapId, mapManager.currentMapId);
        ResourceCache.invalidateXml(path);
        tileM.loadMapFromTMX(path);
        tileM.loadCollisionLayer(path);
        mapObjectLoader.loadMapProperties(path);
        eHandler.reset();
        aSetter.loadEventsFromTMX();
        cChecker.updateCollisionRectsCache();
        if (minimap != null) {
            minimap.invalidateTerrainCache(mapManager.currentMapId);
            minimap.bakeTerrainImage();
        }
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
            case DEBUG_ROW_RELOAD_MAP -> "Reload current map";
            case DEBUG_ROW_FLASHBACK -> "Trigger memory flashback";
            case DEBUG_ROW_FRAGMENTS -> "Collect test fragments";
            case DEBUG_ROW_AWAKENING -> "Teleport: Awakening Cave";
            case DEBUG_ROW_TARGET_MAP -> "Target map: " + (debugMapList.isEmpty() ? "<none>" : debugMapList.get(debugMapSelectedIndex));
            case DEBUG_ROW_TELEPORT_TARGET -> "Teleport to target map";
            default -> "";
        };
    }

    /** Draw the debug panel overlay. */
    private void drawDebugMenu(Graphics2D g2) {
        final int panelW = 520;
        final int rowH = 24;
        final int panelH = DEBUG_MENU_ROWS * rowH + 82;
        final int px = screenWidth / 2 - panelW / 2;
        final int py = screenHeight / 2 - panelH / 2;

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
    }

    public void drawToScreen() {
        repaint();
    }

    public void playMusic(int i) {
        audio.playMusic(i);
    }
    public void stopMusic() {
        audio.stopMusic();
    }
    public void playSE(int i) {
        audio.playSE(i);
    }

    /**
     * Draw all remote players from the multiplayer client.
     * Renders a simple colored rectangle with nametag.
     * Uses the local player's sprite frames when available.
     */
    @SuppressWarnings("unused")
    private void drawRemotePlayers(Graphics2D g2) {
        for (var entry : mpClient.remotePlayers.entrySet()) {
            MultiplayerClient.RemotePlayerState rp = entry.getValue();
            int screenPosX = rp.worldX - player.worldX + player.screenX;
            int screenPosY = rp.worldY - player.worldY + player.screenY;

            // Skip if off-screen
            if (screenPosX + tileSize < -tileSize || screenPosX > screenWidth + tileSize) continue;
            if (screenPosY + tileSize < -tileSize || screenPosY > screenHeight + tileSize) continue;

            // Try to use player's walk frames for the remote player sprite
            BufferedImage sprite = null;
            if (player.walkFrames != null && rp.direction >= 0 && rp.direction < player.walkFrames.length) {
                BufferedImage[] dirFrames = player.walkFrames[rp.direction];
                if (dirFrames != null && dirFrames.length > 0) {
                    int frame = Math.max(0, Math.min(rp.spriteNum - 1, dirFrames.length - 1));
                    sprite = dirFrames[frame];
                }
            }

            if (sprite != null) {
                // Tint the sprite slightly to distinguish from local player
                java.awt.Composite old = g2.getComposite();
                g2.drawImage(sprite, screenPosX, screenPosY, tileSize, tileSize, null);
                // Draw a subtle colored overlay
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.15f));
                g2.setColor(MP_TINT_COLOR);
                g2.fillRect(screenPosX, screenPosY, tileSize, tileSize);
                g2.setComposite(old);
            } else {
                // Fallback: colored rectangle
                g2.setColor(MP_FILL_COLOR);
                g2.fillRoundRect(screenPosX + 8, screenPosY + 8, tileSize - 16, tileSize - 16, 8, 8);
                g2.setColor(MP_BORDER_CLR);
                g2.setStroke(MP_STROKE_2);
                g2.drawRoundRect(screenPosX + 8, screenPosY + 8, tileSize - 16, tileSize - 16, 8, 8);
            }

            // Nametag above the sprite
            if (mpNametagFont == null) mpNametagFont = g2.getFont().deriveFont(Font.BOLD, 12f);
            g2.setFont(mpNametagFont);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int nameW = fm.stringWidth(rp.name);
            int nameX = screenPosX + tileSize / 2 - nameW / 2;
            int nameY = screenPosY - 6;

            // Name shadow
            g2.setColor(MP_NAME_SHADOW);
            g2.drawString(rp.name, nameX + 1, nameY + 1);
            g2.setColor(MP_NAME_COLOR);
            g2.drawString(rp.name, nameX, nameY);

            // HP bar below nametag
            if (rp.maxLife > 0) {
                int barW = 40;
                int barH = 4;
                int barX = screenPosX + tileSize / 2 - barW / 2;
                int barY = screenPosY - 12;
                g2.setColor(MP_BAR_BG);
                g2.fillRoundRect(barX, barY, barW, barH, 3, 3);
                float ratio = (float) rp.life / rp.maxLife;
                g2.setColor(ratio > 0.3f ? MP_BAR_GREEN : MP_BAR_RED);
                g2.fillRoundRect(barX, barY, (int) (barW * ratio), barH, 3, 3);
            }
        }
    }
}
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
import audio.Sound;
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
import tiles_interactive.interactiveTile;
import ui.CutsceneManager;
import ui.Minimap;
import ui.RenderPipeline;
import ui.ScreenShake;
import ui.UI;
import util.ObjectPool;

public class GamePanel extends JPanel implements Runnable{

    // SCREEN SETTINGS

    final int originalTileSize = 32; // 32 x 32 pixel
    final int scale = 2;

    public final int tileSize = originalTileSize * scale; // 64 x 64 pixel
    public final int maxScreenCol = 20;
    public final int maxScreenRow = 12;
    public final int screenWidth = tileSize * maxScreenCol; // 1280 pixels
    public final int screenHeight = tileSize * maxScreenRow; // 768 pixels

    // DEBUG
    public boolean HitBoxes = false;
    public boolean drawPath = false;

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
    public boolean vSyncOn = true; // V-Sync toggle: sync rendering to monitor refresh rate

    // TIMING
    private static final int TARGET_UPS = 60; // Fixed simulation rate (game speed)
    int FPS = 60;  // Render target FPS (independent from game speed)
    public int currentFPS = 0;
    public int maxFPS = 0;      // Session peak FPS
    int monitorRefreshRate = 60; // Detected at startup

    // SYSTEM
    public TileManager tileM = new TileManager(this);
    public KeyHandler keyH = new KeyHandler(this);
    public AudioManager audio = new AudioManager();
    // Legacy delegates — forward to AudioManager so old code still compiles
    Sound music = audio.getMusicSound();
    Sound se = audio.getSESound();
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

    // SMOOTH CAMERA
    public float cameraX, cameraY;          // smooth world-space camera top-left
    private static final float CAM_LERP = 0.12f;
    private static final int CAM_DEADZONE = 32; // pixels before camera starts catching up

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
    private java.awt.Font mpNametagFont; // lazily derived

    // MINIMAP
    public Minimap minimap;

    // RENDER PIPELINE (extracted from GamePanel)
    public RenderPipeline renderPipeline;

    // QUEST SYSTEM
    public QuestManager questManager;

    // MAP MANAGEMENT (extracted from GamePanel)
    public MapManager mapManager;

    // GLOBAL HIT-STOP: freezes all entities for impactful hits
    public int globalHitstopTimer = 0;

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
    }

    public void setupGame() {

    // Validate critical assets
    new AssetValidator().validate();

    // Initialize map manager
    mapManager = new MapManager(this);

    // Register all maps once at startup
    mapManager.registerMap("harta", "/res/maps/harta.tmx");
    mapManager.registerMap("test", "/res/maps/test.tmx");
    mapManager.registerMap("Dungeon1", "/res/maps/Dungeon1.tmx");

    if (!mapManager.loadingGame) {
        mapManager.currentMapId = "harta";
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
    }
    mobSpawner = new MobSpawner(this);
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

    // SMOOTH CAMERA: initialize to player position
    cameraX = player.worldX - player.screenX;
    cameraY = player.worldY - player.screenY;

    // MINIMAP: create and bake initial terrain
    minimap = new Minimap(this);
    minimap.bakeTerrainImage();

    // QUEST SYSTEM
    questManager = new QuestManager(this);
    questManager.addQuest("find_keys", "Find the Keys", "Collect 3 keys to open the gate", 3);
    questManager.addQuest("slay_monsters", "Monster Slayer", "Defeat 5 monsters", 5);

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

    if (fullScreenOn) {
        setFullScreen();
    }
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
        if (window == null) {
            return;
        }

        if (enableFullScreen) {
            if (!window.isUndecorated()) {
                windowedBounds = window.getBounds();
            }
            if (window.isDisplayable()) {
                window.dispose();
            }
            window.setUndecorated(true);
            window.setResizable(false);
            window.setExtendedState(JFrame.NORMAL);

            Rectangle screenBounds = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            window.setBounds(screenBounds);
            window.setVisible(true);
        } else {
            if (window.isDisplayable()) {
                window.dispose();
            }
            window.setUndecorated(false);
            window.setResizable(true);
            window.setVisible(true);
            if (windowedBounds != null) {
                window.setBounds(windowedBounds);
            } else {
                window.pack();
                window.setLocationRelativeTo(null);
            }
        }

        window.toFront();
        requestFocusInWindow();
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
            System.out.println("[V-SYNC] Enabled - Game synced to monitor refresh rate");
        } else {
            System.setProperty("sun.java2d.vsync", "False");
            FPS = 60;
            System.out.println("[V-SYNC] Disabled - Render target set to " + FPS + " FPS");
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
        double drawInterval = 1_000_000_000.0 / Math.max(30, FPS);
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
                drawInterval = 1_000_000_000.0 / Math.max(30, cachedFPS);
            }
            renderDelta += elapsed / drawInterval;

            timer += elapsed;
            lastTime = currentTime;

            // Cap update catch-up to prevent spiral of death
            if (updateDelta > 5) updateDelta = 5;

            while (updateDelta >= 1) {
                update();
                updateDelta--;
            }

            if (renderDelta >= 1) {
                synchronized (this) {
                    drawToTempScreen();
                }
                repaint();
                frameCount++;
                renderDelta -= 1;
                if (renderDelta > 1) renderDelta = 1; // cap to prevent frame debt spiral
            }

            if(timer >= 1_000_000_000) {
                currentFPS = frameCount;
                if (currentFPS > maxFPS) maxFPS = currentFPS;
                frameCount = 0;
                timer = 0;
            }

            // Smart sleep: only Thread.sleep when there's enough headroom,
            // otherwise spin-wait to avoid Windows ~15 ms sleep granularity.
            long now = System.nanoTime();
            long nextFrame = lastTime + (long) drawInterval;
            long remaining = nextFrame - now;
            if (remaining > 2_000_000) { // >2 ms headroom → safe to sleep 1 ms
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else if (remaining > 0) {
                Thread.onSpinWait(); // CPU-friendly busy-wait hint
            }

        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        synchronized (this) {
            if (tempScreen != null) {
                g.drawImage(tempScreen, 0, 0, getWidth(), getHeight(), null);
            }
        }
        Toolkit.getDefaultToolkit().sync();
    }


    /** Trigger a global hit-stop freeze. Only overrides if stronger than current. */
    public void triggerHitstop(int frames) {
        if (frames > globalHitstopTimer) globalHitstopTimer = frames;
    }

    public void update() {

        if(gameState == playState) {
            // Refresh viewport cache once per frame
            vpCacheValid = false;
            updateViewportCache();

            // INPUT COOLDOWNS
            keyH.update();

            // GLOBAL HIT-STOP: freeze entities but keep visual feedback running
            if (globalHitstopTimer > 0) {
                globalHitstopTimer--;
                // Still update visual-only systems during freeze
                for (int i = particleList.size() - 1; i >= 0; i--) {
                    Entity particle = particleList.get(i);
                    if (particle != null) {
                        if (particle.alive) particle.update();
                        else { particleList.remove(i); particlePool.release((Particle) particle); }
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
                        }
                    }
                    if ( !monster[i].alive ) {
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
                        // OPTIMIZATION: Return particle to pool for reuse
                        particlePool.release((Particle) particle);
                    }
                }
            }
            for ( int i = 0 ; i < iTile.length ; i++ ) {
                if ( iTile[i] != null ) {
                    iTile[i].update();
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

            // SMOOTH CAMERA: lerp towards player
            float targetCamX = player.worldX - player.screenX;
            float targetCamY = player.worldY - player.screenY;
            float dx = targetCamX - cameraX;
            float dy = targetCamY - cameraY;
            if (Math.abs(dx) > CAM_DEADZONE) cameraX += dx * CAM_LERP;
            else cameraX += dx * 0.4f;
            if (Math.abs(dy) > CAM_DEADZONE) cameraY += dy * CAM_LERP;
            else cameraY += dy * 0.4f;

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
            player.life = 0; // safety clamp

            if (gameState != gameOverState) {
                ui.commandNum = 0;
            }
            gameState = gameOverState;

            stopMusic();
            if (!deathSoundPlayed) {
                playSE(4);
                deathSoundPlayed = true;
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
        vpMinX = Math.round(cameraX);
        vpMaxX = vpMinX + screenWidth;
        vpMinY = Math.round(cameraY);
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
    g2.setBackground(Color.BLACK);
    g2.clearRect(0, 0, screenWidth, screenHeight);

    renderPipeline.drawCurrentState(g2);

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
            int screenPosX = rp.worldX - (int) cameraX;
            int screenPosY = rp.worldY - (int) cameraY;

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
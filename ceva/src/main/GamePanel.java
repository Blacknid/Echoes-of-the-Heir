package main;

import ai.PathFinder;
import data.SaveLoad;
import entity.Entity;
import entity.Particle;
import entity.Player;
import entity.Projectile;
import environment.EnvironmentManager;
import environment.MapShaderManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import tile.TileManager;
import tiles_interactive.interactiveTile;

public class GamePanel extends JPanel implements Runnable{

    // SCREEN SETTINGS

    final int originalTileSize = 32; // 32 x 32 pixel
    final int scale = 2;

    public final int tileSize = originalTileSize * scale; // 64 x 64 pixel
    public final int maxScreenCol = 20;
    public final int maxScreenRow = 12;
    public final int screenWidth = tileSize * maxScreenCol; // 1280 pixels
    public final int screenHeight = tileSize * maxScreenRow; // 768 pixels

    public boolean Hitboxes = true;

    // SETARIILE LUMI
    public final int maxWorldCol = 100;
    public final int maxWorldRow = 100;
    public final int worldWidth = tileSize * maxWorldCol;
    public final int worldHeight = tileSize * maxWorldRow;
    
    // FOR FULLSCREEN
    Rectangle windowedBounds;
    BufferedImage tempScreen;
    Graphics2D g2;

    public boolean fullScreenOn = false;
    public boolean vSyncOn = true; // V-Sync toggle: sync rendering to monitor refresh rate

    //DEBUG
    public boolean HitBoxes = false;
    public boolean drawPath = false;

    // TIMING
    private static final int TARGET_UPS = 60; // Fixed simulation rate (game speed)
    int FPS = 60;  // Render target FPS (independent from game speed)
    public int currentFPS = 0;
    int monitorRefreshRate = 60; // Detected at startup

    // SYSTEM
    public TileManager tileM = new TileManager(this);
    public KeyHandler keyH = new KeyHandler(this);
    Sound music = new Sound();
    Sound se = new Sound();
    public CollisionChecker cChecker = new CollisionChecker(this);
    public AssetSetter aSetter = new AssetSetter(this);
    public UI ui = new UI(this);
    public EventHandler eHandler = new EventHandler(this);
    Config config = new Config(this);
    public CutsceneManager csManager = new CutsceneManager(this);
    public PathFinder pFinder = new PathFinder(this);
    public EnvironmentManager eManager = new EnvironmentManager(this);
    public MapShaderManager mapShader;
    public environment.TileParticleEmitter tileParticleEmitter;
    public ScreenShake screenShake = new ScreenShake();
    public MobSpawner mobSpawner;
    SaveLoad saveLoad = new SaveLoad(this);

    // SMOOTH CAMERA
    public float cameraX, cameraY;          // smooth world-space camera top-left
    private static final float CAM_LERP = 0.12f;
    private static final int CAM_DEADZONE = 32; // pixels before camera starts catching up

    // MINIMAP
    public Minimap minimap;

    // QUEST SYSTEM
    public QuestManager questManager;

    // GLOBAL HIT-STOP: freezes all entities for impactful hits
    public int globalHitstopTimer = 0;

    // DAMAGE NUMBERS
    public ObjectPool<entity.DamageNumber> damageNumberPool;
    public java.util.ArrayList<entity.DamageNumber> damageNumbers = new java.util.ArrayList<>();
    // Map registry: id -> tmx path
    public Map<String, String> mapRegistry = new HashMap<>();
    // Track which map is currently active (matches a key in mapRegistry)
    public String currentMapId = "harta";
    Thread gameThread;
    public boolean loadingGame = false;

    //ENTITY AND OBJECT
    public Player player = new Player(this,keyH);
    public Entity obj[] = new Entity[100];
    public Entity npc[] = new Entity[10];
    public Entity monster[] = new Entity[20];
    public interactiveTile iTile[] = new interactiveTile[30]; // expanded for breakable pots
    public ArrayList<Entity> projectilesList = new ArrayList<>();
    public ArrayList<Entity> particleList = new ArrayList<>();    
    // MAP ENTITY STORAGE: Preserve entity states when switching between maps
    private Map<String, Entity[]> savedObjects = new HashMap<>();
    private Map<String, Entity[]> savedNPCs = new HashMap<>();
    private Map<String, Entity[]> savedMonsters = new HashMap<>();
    private Map<String, interactiveTile[]> savedITiles = new HashMap<>();    
    // OPTIMIZATION: Object pools for reusable projectiles and particles
    public ObjectPool<Projectile> projectilePool;
    public ObjectPool<Particle> particlePool;
    
    // OPTIMIZATION: Pre-allocate entityList with estimated capacity to reduce resizing
    ArrayList<Entity> entityList = new ArrayList<>(150);
    int entityListIndex = 0; // Track insertion point for efficient reuse
    
    // OPTIMIZATION: Define Comparator once to avoid garbage collection lag
    Comparator<Entity> renderSorter = (e1, e2) -> Integer.compare(e1.worldY, e2.worldY);

    // GAME STATE 
    public int gameState;
    public final int titleState = 0;
    public final int playState = 1;
    public final int pauseState = 2;
    public final int dialogueState = 3;
    public final int characterState = 4;
    public final int optionsState = 5;
    public final int gameOverState = 6;
    public final int cutsceneState = 7;
    public final int transitionState = 8;
    public final int levelUpState = 9;
    public final int skillTreeState = 10;

    //ABILITY
    public boolean teleportation = false;
    public boolean bootsUnlocked = false;
    public boolean deathSoundPlayed = false;

    // TRANSITION
    public String nextMapId;
    public int nextCol;
    public int nextRow;

    // ENTRY POINT TRACKING: Remember where we came from when switching maps
    // When entering a new map, these store the source map and trigger position (the tile we stepped on)
    public String previousMapId = "harta";
    public int previousTriggerCol = 24;   // tile column of the entry trigger
    public int previousTriggerRow = 15;   // tile row of the entry trigger

    // DOOR ENTRY TRACKING: Remember which door was used to enter the map
    public int doorEntryCol = -1;         // door tile column (-1 = no door entry)
    public int doorEntryRow = -1;         // door tile row (-1 = no door entry)

    public GamePanel() {

        this.setPreferredSize(new Dimension(screenWidth, screenHeight));
        this.setBackground(Color.black);
        this.setDoubleBuffered(true);
        this.addKeyListener(keyH);
        this.setFocusable(true);
    }

    public void setupGame() {

    // Register all maps once at startup
    registerMap("harta", "/res/maps/harta.tmx");
    registerMap("test", "/res/maps/test.tmx");
    registerMap("Dungeon1", "/res/maps/Dungeon1.tmx");

    if (!loadingGame) {
        currentMapId = "harta";
        aSetter.setObject(); // NEW GAME ONLY
        eManager.setup();
        aSetter.setInteractiveTile();
        aSetter.setEvents();
    }
    
    aSetter.setNPC();
    aSetter.setMonster();
    mobSpawner = new MobSpawner(this);
    gameState = titleState;

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

    // OPTIMIZATION: Use TYPE_INT_ARGB_PRE for faster alpha blending in Java2D
    tempScreen = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_ARGB_PRE);
    g2 = (Graphics2D) tempScreen.getGraphics();
    
    // OPTIMIZATION: Set rendering hints once at setup instead of per-frame
    g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_SPEED);
    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    g2.setRenderingHint(java.awt.RenderingHints.KEY_COLOR_RENDERING, java.awt.RenderingHints.VALUE_COLOR_RENDER_SPEED);

    if (fullScreenOn) {
        setFullScreen();
    }
    }

    public void registerMap(String id, String tmxPath) {
        mapRegistry.put(id, tmxPath);
    }

    /**
     * Begin a smooth fade-to-black → map load → fade-from-black transition.
     * Call this from doors/events instead of changeMap() directly.
     *
     * @param mapId    registered map id or TMX path
     * @param spawnCol tile column where the player appears on the new map
     * @param spawnRow tile row where the player appears on the new map
     */
    public void startTransition(String mapId, int spawnCol, int spawnRow) {
        nextMapId = mapId;
        nextCol = spawnCol;
        nextRow = spawnRow;
        // Reset transition state so the fade always starts from fully transparent
        ui.transitionAlpha = 0f;
        ui.subState = 0;
        gameState = transitionState;
    }

    /** Called by UI.drawTransition() at peak darkness — do NOT call directly. */
    public void changeMap() {
        changeMap(nextMapId, nextCol, nextRow);
    }

    public void changeMap(String mapIdOrPath, int spawnCol, int spawnRow) {
        String path = mapRegistry.getOrDefault(mapIdOrPath, mapIdOrPath);

        // Save the current map. The trigger position (entry point) is stored by EventHandler
        // before transition is triggered, so it's already in eHandler.lastTriggerCol/Row
        previousMapId = currentMapId;
        previousTriggerCol = eHandler.lastTriggerCol;
        previousTriggerRow = eHandler.lastTriggerRow;

        // Update which map is now active
        // If mapIdOrPath is a registered id, use it; otherwise derive from path
        if (mapRegistry.containsKey(mapIdOrPath)) {
            currentMapId = mapIdOrPath;
        } else {
            // Try to find the id from path, fallback to the raw string
            currentMapId = mapIdOrPath;
            for (Map.Entry<String, String> entry : mapRegistry.entrySet()) {
                if (entry.getValue().equals(mapIdOrPath)) {
                    currentMapId = entry.getKey();
                    break;
                }
            }
        }

        // Load new map layers and collision layer
        tileM.mapLayers.clear();
        tileM.loadMapFromTMX(path);
        tileM.loadCollisionLayer(path);

        // Update collision cache used by CollisionChecker
        cChecker.updateCollisionRectsCache();

        // Rebake minimap for new map
        if (minimap != null) minimap.bakeTerrainImage();

        // --- Save current map's entities before switching ---
        saveMapEntities(previousMapId);

        // --- Clear ALL existing entities so nothing leaks from the previous map ---
        for (int i = 0; i < obj.length; i++) obj[i] = null;
        for (int i = 0; i < npc.length; i++) npc[i] = null;
        for (int i = 0; i < monster.length; i++) monster[i] = null;
        for (int i = 0; i < iTile.length; i++) iTile[i] = null;
        projectilesList.clear();
        particleList.clear();

        // Reset event handler FULLY (rects + transitions) before registering new ones
        eHandler.reset();

        // Restore or create entities for the new map
        if (savedObjects.containsKey(currentMapId)) {
            // Returning to a previously visited map - restore saved entities
            restoreMapEntities(currentMapId);
        } else {
            // First visit to this map - create fresh entities
            aSetter.setObject();
            aSetter.setInteractiveTile();
            aSetter.setNPC();
            aSetter.setMonster();
        }
        
        // Always set up events (these can be dynamic based on game state)
        aSetter.setEvents();

        // Place player at spawn position (centered on tile)
        player.worldX = spawnCol * tileSize;
        player.worldY = spawnRow * tileSize;

        // Reset door entry tracking for the next transition
        doorEntryCol = -1;
        doorEntryRow = -1;

        // NOTE: do NOT set gameState here — the transition fade-out in
        // UI.drawTransition() will set playState once the screen fades back in.
    }

    private void saveMapEntities(String mapId) {
        // Create copies of the entity arrays to preserve their state
        Entity[] objCopy = new Entity[obj.length];
        Entity[] npcCopy = new Entity[npc.length];
        Entity[] monsterCopy = new Entity[monster.length];
        interactiveTile[] iTileCopy = new interactiveTile[iTile.length];
        
        System.arraycopy(obj, 0, objCopy, 0, obj.length);
        System.arraycopy(npc, 0, npcCopy, 0, npc.length);
        System.arraycopy(monster, 0, monsterCopy, 0, monster.length);
        System.arraycopy(iTile, 0, iTileCopy, 0, iTile.length);
        
        savedObjects.put(mapId, objCopy);
        savedNPCs.put(mapId, npcCopy);
        savedMonsters.put(mapId, monsterCopy);
        savedITiles.put(mapId, iTileCopy);
    }

    private void restoreMapEntities(String mapId) {
        // Restore the saved entity arrays for this map
        Entity[] objCopy = savedObjects.get(mapId);
        Entity[] npcCopy = savedNPCs.get(mapId);
        Entity[] monsterCopy = savedMonsters.get(mapId);
        interactiveTile[] iTileCopy = savedITiles.get(mapId);
        
        if (objCopy != null) System.arraycopy(objCopy, 0, obj, 0, obj.length);
        if (npcCopy != null) System.arraycopy(npcCopy, 0, npc, 0, npc.length);
        if (monsterCopy != null) System.arraycopy(monsterCopy, 0, monster, 0, monster.length);
        if (iTileCopy != null) System.arraycopy(iTileCopy, 0, iTile, 0, iTile.length);
    }

    public void resetGame(boolean restart) {

        deathSoundPlayed = false;

        if ( restart ) {
            // Full restart — reload the main map from scratch
            currentMapId = "harta";
            String path = mapRegistry.getOrDefault(currentMapId, "/res/maps/harta.tmx");
            tileM.mapLayers.clear();
            tileM.loadMapFromTMX(path);
            tileM.loadCollisionLayer(path);
            cChecker.updateCollisionRectsCache();

            // Rebake minimap for new map
            if (minimap != null) minimap.bakeTerrainImage();

            // Clear all saved map states
            savedObjects.clear();
            savedNPCs.clear();
            savedMonsters.clear();
            savedITiles.clear();

            for (int i = 0; i < obj.length; i++) obj[i] = null;
            for (int i = 0; i < npc.length; i++) npc[i] = null;
            for (int i = 0; i < monster.length; i++) monster[i] = null;
            for (int i = 0; i < iTile.length; i++) iTile[i] = null;
            projectilesList.clear();
            particleList.clear();
            eHandler.reset();

            player.setDefaultValues();
            aSetter.setObject();
            aSetter.setInteractiveTile();
            aSetter.setEvents();
        }

        player.setDefaultPositions();
        player.restoreLifeAndMana();
        aSetter.setNPC();
        aSetter.setMonster();
    }
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
            FPS = 240;
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
                drawToTempScreen();
                repaint();
                frameCount++;
                renderDelta = 0;
            }

            if(timer >= 1_000_000_000) {
                currentFPS = frameCount;
                frameCount = 0;
                timer = 0;
            }

            // Reduce CPU usage: sleep instead of spin-waiting
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (tempScreen != null) {
            g.drawImage(tempScreen, 0, 0, getWidth(), getHeight(), null);
            Toolkit.getDefaultToolkit().sync();
        }
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

            // COLORED LIGHTS: Register dynamic light sources each frame
            if (eManager.lightning != null) {
                eManager.lightning.clearLights();
                // Player warm glow
                eManager.lightning.addLight(
                    player.worldX + tileSize / 2, player.worldY + tileSize / 2,
                    tileSize * 4, new java.awt.Color(255, 240, 220), 0.25f);
                // Torch objects: warm orange with subtle flicker
                for (int i = 0; i < obj.length; i++) {
                    if (obj[i] != null && obj[i].lightSource && obj[i].lightRadius > 0) {
                        float flicker = 0.22f + 0.06f * (float) Math.sin(System.nanoTime() * 0.000000003 + i * 1.7);
                        eManager.lightning.addLight(
                            obj[i].worldX + tileSize / 2, obj[i].worldY + tileSize / 2,
                            obj[i].lightRadius * tileSize, new java.awt.Color(255, 170, 60), flicker);
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
    //DEBUG
    long drawStart = 0;
    if(keyH.showDebugText) {
        drawStart = System.nanoTime();
    }

    drawCurrentState();

    // DEBUG TEXT
    if(keyH.showDebugText) {
        g2.setFont(new Font("Arial",Font.PLAIN, 20));
        g2.setColor(Color.WHITE);
        int x = 10;
        int y = 400;
        int lineHeigh = 20;

        g2.drawString("FPS: " + currentFPS, x, y); y += lineHeigh;
        g2.drawString("Map: " + currentMapId, x, y); y += lineHeigh;
        g2.drawString("WorldX: " + player.worldX, x, y); y += lineHeigh;
        g2.drawString("WorldY: " + player.worldY, x, y); y += lineHeigh;
        g2.drawString("Col: " + (player.worldX + player.solidArea.x) / tileSize, x, y); y += lineHeigh;
        g2.drawString("Row: " + (player.worldY + player.solidArea.y) / tileSize, x, y); y += lineHeigh;
        if (tileParticleEmitter != null) {
            g2.drawString("TileParticles: " + tileParticleEmitter.getActiveCount(), x, y); y += lineHeigh;
        }
    }
}

    private void drawCurrentState() {
        switch (gameState) {
            case titleState:
                ui.draw(g2);
                break;
            case transitionState:
                // Draw the world underneath, then UI draws the fade overlay on top
                drawWorldState();
                break;
            case playState:
            case pauseState:
            case dialogueState:
            case characterState:
            case optionsState:
            case gameOverState:
            case cutsceneState:
            case skillTreeState:
            default:
                drawWorldState();
                break;
        }
    }

    private void drawWorldState() {

        // OPTIMIZATION: Ensure anti-aliasing is OFF during world rendering (tiles, entities)
        // AA is expensive for fillRect/drawImage and not needed for pixel art
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        // SMOOTH CAMERA + SCREEN SHAKE: offset the entire world
        int camOffX = Math.round(cameraX - (player.worldX - player.screenX));
        int camOffY = Math.round(cameraY - (player.worldY - player.screenY));
        int shakeX = screenShake.getOffsetX();
        int shakeY = screenShake.getOffsetY();
        int totalOffX = camOffX + shakeX;
        int totalOffY = camOffY + shakeY;
        if (totalOffX != 0 || totalOffY != 0) {
            g2.translate(totalOffX, totalOffY);
        }

        tileM.prepareVisibleTiles();
        tileM.drawBackground(g2);

        collectRenderableEntities();

        // SORT (only sort the active portion of the list)
        Collections.sort(entityList.subList(0, entityListIndex), renderSorter);

        // TILE PARTICLES: prepare Y-sorted particle indices for interleaved drawing
        int tpCount = 0;
        if (tileParticleEmitter != null) {
            tpCount = tileParticleEmitter.prepareSortedIndices();
        }
        int tpIdx = 0;
        int depthTileCount = tileM.getDepthTileCount();
        int depthTileIdx = 0;

        // DRAW ENTITIES + TILE PARTICLES interleaved by Y (depth-correct)
        // Depth-sorted tiles and particles with sortY <= entity.worldY are drawn BEFORE the entity.
        java.awt.Composite savedComp = g2.getComposite();
        for (int i = 0; i < entityListIndex; i++) {
            int entityY = entityList.get(i).worldY;

            while (true) {
                float nextDepthTileY = depthTileIdx < depthTileCount ? tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
                float nextParticleY = tpIdx < tpCount ? tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;
                float nextY = Math.min(nextDepthTileY, nextParticleY);

                if (nextY > entityY) {
                    break;
                }

                if (nextDepthTileY <= nextParticleY) {
                    g2.setComposite(savedComp);
                    tileM.drawDepthTile(g2, depthTileIdx);
                    depthTileIdx++;
                } else {
                    tileParticleEmitter.drawSingle(g2, tpIdx);
                    tpIdx++;
                }
            }

            // Restore composite in case a particle changed it
            g2.setComposite(savedComp);
            entityList.get(i).draw(g2);
        }

        while (depthTileIdx < depthTileCount || tpIdx < tpCount) {
            float nextDepthTileY = depthTileIdx < depthTileCount ? tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
            float nextParticleY = tpIdx < tpCount ? tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;

            if (nextDepthTileY <= nextParticleY) {
                g2.setComposite(savedComp);
                tileM.drawDepthTile(g2, depthTileIdx);
                depthTileIdx++;
            } else {
                tileParticleEmitter.drawSingle(g2, tpIdx);
                tpIdx++;
            }
        }
        g2.setComposite(savedComp);

        clearRenderableEntities();

        // DAMAGE NUMBERS (draw above entities, below UI)
        for (int i = 0; i < damageNumbers.size(); i++) {
            entity.DamageNumber dn = damageNumbers.get(i);
            if (dn.alive) dn.draw(g2);
        }

        // CUTSCENE
        csManager.draw(g2);

        // DEBUG HITBOXES — drawn in world-space (camera transform still active)
        if (HitBoxes) {
            drawHitboxDebug(g2);
        }

        // UNDO CAMERA + SHAKE before drawing screen-space effects and UI
        if (totalOffX != 0 || totalOffY != 0) {
            g2.translate(-totalOffX, -totalOffY);
        }

        // ENVIRONMENT — drawn in screen-space after camera undo so the overlay is always
        // anchored at (0,0) and covers the full screen regardless of camera movement or dash
        eManager.draw(g2);

        // SHADER: screen-space effects (drawn after camera undo so they stay fixed on screen)
        if (mapShader != null) {
            mapShader.drawAmbientParticles(g2);
            mapShader.drawWeather(g2);
            mapShader.drawColorGrading(g2);
            mapShader.drawVignette(g2);
        }

        // UI
        ui.draw(g2);

        // MINIMAP (drawn on top of UI, under debug)
        if (minimap != null && gameState == playState) {
            minimap.draw(g2);
        }

        // QUEST TRACKER (below minimap)
        if (questManager != null && gameState == playState) {
            questManager.drawTracker(g2);
        }

        // QUEST LOG OVERLAY
        if (questManager != null && questManager.isLogOpen()) {
            questManager.drawLog(g2);
        }

        if (HitBoxes) {
            // Hitboxes are now drawn in drawHitboxDebug() inside camera transform
        }
    }

    private void drawHitboxDebug(Graphics2D g2) {
        g2.setColor(new Color(255, 0, 0, 128)); // red semi-transparent

        // PLAYER
        Rectangle r = player.solidArea;
        int px = player.screenX + r.x;
        int py = player.screenY + r.y;
        g2.fillRect(px, py, r.width, r.height);
        if (player.knockBack) {
            g2.setColor(new Color(255, 0, 255, 128));
            g2.fillRect(px, py, r.width, r.height);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 12));
            g2.drawString(String.valueOf(player.knockBackPower), px, py - 4);
            int cx = px + r.width/2;
            int cy = py + r.height/2;
            int vx = player.knockBackVectorX * 2;
            int vy = player.knockBackVectorY * 2;
            g2.drawLine(cx, cy, cx + vx, cy + vy);
            g2.fillOval(cx + vx - 2, cy + vy - 2, 4, 4);
            g2.setColor(new Color(255, 0, 0, 128));
        }

        // PLAYER ATTACK HITBOX
        if (player.attacking) {
            g2.setColor(new Color(255, 100, 0, 128));
            int ts = tileSize;
            int attackWorldX = player.worldX;
            int attackWorldY = player.worldY;
            int aw = 0, ah = 0;
            switch(player.direction) {
                case Entity.DIR_UP:    aw = ts - 16; ah = ts + 16; attackWorldX += 8; attackWorldY -= ts + 16; break;
                case Entity.DIR_DOWN:  aw = ts - 16; ah = ts + 16; attackWorldX += 8; attackWorldY += ts; break;
                case Entity.DIR_LEFT:  aw = ts + 16; ah = ts - 16; attackWorldX -= ts + 16; attackWorldY += 8; break;
                case Entity.DIR_RIGHT: aw = ts + 16; ah = ts - 16; attackWorldX += ts; attackWorldY += 8; break;
            }
            int screenX = attackWorldX - player.worldX + player.screenX;
            int screenY = attackWorldY - player.worldY + player.screenY;
            g2.fillRect(screenX, screenY, aw, ah);
        }

        // NPC
        g2.setColor(new Color(255, 0, 0, 128));
        for(Entity n : npc) {
            if(n != null) {
                r = n.solidArea;
                int nx = n.worldX - player.worldX + player.screenX + r.x;
                int ny = n.worldY - player.worldY + player.screenY + r.y;
                g2.fillRect(nx, ny, r.width, r.height);
            }
        }

        // MONSTERS
        g2.setColor(new Color(255, 255, 0, 128));
        for(Entity m : monster) {
            if(m != null) {
                r = m.solidArea;
                int mx = m.worldX - player.worldX + player.screenX + r.x;
                int my = m.worldY - player.worldY + player.screenY + r.y;
                g2.fillRect(mx, my, r.width, r.height);
                if (m.knockBack) {
                    g2.setColor(new Color(255, 0, 255, 128));
                    g2.fillRect(mx, my, r.width, r.height);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Arial", Font.PLAIN, 12));
                    g2.drawString(String.valueOf(m.knockBackPower), mx, my - 4);
                    int cx = mx + r.width/2;
                    int cy = my + r.height/2;
                    int vx = m.knockBackVectorX * 2;
                    int vy = m.knockBackVectorY * 2;
                    g2.drawLine(cx, cy, cx + vx, cy + vy);
                    g2.fillOval(cx + vx - 2, cy + vy - 2, 4, 4);
                    g2.setColor(new Color(255, 255, 0, 128));
                }
            }
        }

        // OBJECTS
        g2.setColor(new Color(255, 0, 0, 128));
        for(Entity o : obj) {
            if(o != null) {
                r = o.solidArea;
                int ox = o.worldX - player.worldX + player.screenX + r.x;
                int oy = o.worldY - player.worldY + player.screenY + r.y;
                g2.fillRect(ox, oy, r.width, r.height);
            }
        }

        // INTERACTIVE TILES
        g2.setColor(new Color(0, 255, 255, 128));
        for(int i = 0; i < iTile.length; i++) {
            if(iTile[i] != null) {
                r = iTile[i].solidArea;
                int ix = iTile[i].worldX - player.worldX + player.screenX + r.x;
                int iy = iTile[i].worldY - player.worldY + player.screenY + r.y;
                g2.fillRect(ix, iy, r.width, r.height);
            }
        }

        // COLLISION SHAPES (rectangles, rotated rects, polygons, ellipses)
        if(tileM.collisionShapes != null && !tileM.collisionShapes.isEmpty()) {
            g2.setColor(new Color(0, 0, 255, 128));
            AffineTransform oldTransform = g2.getTransform();
            g2.translate(-player.worldX + player.screenX, -player.worldY + player.screenY);
            for(Shape shape : tileM.collisionShapes) {
                g2.fill(shape);
            }
            g2.setTransform(oldTransform);
        }
    }

    private void collectRenderableEntities() {
        // OPTIMIZATION: Reuse entityList by tracking index instead of add/clear
        entityListIndex = 0;

        addToRenderList(player);

        for (int i = 0; i < npc.length; i++) {
            if (npc[i] != null) {
                addToRenderList(npc[i]);
            }
        }

        for (int i = 0; i < obj.length; i++) {
            if (obj[i] != null) {
                addToRenderList(obj[i]);
            }
        }

        for (int i = 0; i < monster.length; i++) {
            if (monster[i] != null) {
                addToRenderList(monster[i]);
            }
        }

        int projSize = projectilesList.size();
        for (int i = 0; i < projSize; i++) {
            Entity proj = projectilesList.get(i);
            if (proj != null) {
                addToRenderList(proj);
            }
        }

        int partSize = particleList.size();
        for (int i = 0; i < partSize; i++) {
            Entity particle = particleList.get(i);
            if (particle != null) {
                addToRenderList(particle);
            }
        }

        for (int i = 0; i < iTile.length; i++) {
            if (iTile[i] != null) {
                addToRenderList(iTile[i]);
            }
        }
    }

    private void addToRenderList(Entity entity) {
        // OPTIMIZATION: Skip entities outside viewport (no need to sort/draw them)
        if (!isEntityInViewport(entity, tileSize)) return;
        if (entityListIndex < entityList.size()) {
            entityList.set(entityListIndex, entity);
        } else {
            entityList.add(entity);
        }
        entityListIndex++;
    }

    private void clearRenderableEntities() {
        // Clear only the used portion of the list for next frame
        for (int i = 0; i < entityListIndex; i++) {
            entityList.set(i, null);
        }
    }


    public void drawToScreen() {
        repaint();
    }

    public void playMusic(int i) {

        music.setFile(i);
        music.play();
        music.loop();
    }
    public void stopMusic() {

        music.stop();
    }
    public void playSE(int i) {

        se.setFile(i);
        se.play();
    }
}
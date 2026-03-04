package main;
import java.util.Timer;

import entity.Entity;
import entity.Player;
import entity.Projectile;
import entity.Particle;
import environment.EnvironmentManager;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import javax.swing.JPanel;
import java.awt.Rectangle;

import ai.PathFinder;
import data.SaveLoad;
import tile.TileManager;
import tiles_interactive.interactiveTile;
import java.util.HashMap;
import java.util.Map;

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
    public final int worldHeight = tileSize * maxScreenRow;
    
    // FOR FULLSCREEN
    int screenWidth2 = screenWidth;
    int screenHeight2 = screenHeight;
    BufferedImage tempScreen;
    Graphics2D g2;

    public boolean fullScreenOn = false;

    //DEBUG
    public boolean HitBoxes = false;
    public boolean drawPath = false;

    // FPS

    int FPS = 60;
    public int currentFPS = 0;

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
    SaveLoad saveLoad = new SaveLoad(this);
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
    public interactiveTile iTile[] = new interactiveTile[10]; // size depends on how many interactive tiles you have
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
    Comparator<Entity> renderSorter = new Comparator<Entity>() {
        @Override
        public int compare(Entity e1, Entity e2) {
            return Integer.compare(e1.worldY, e2.worldY);
        }
    };

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

    if (!loadingGame) {
        currentMapId = "harta";
        aSetter.setObject(); // NEW GAME ONLY
        eManager.setup();
        aSetter.setInteractiveTile();
        aSetter.setEvents();
    }
    
    aSetter.setNPC();
    aSetter.setMonster();
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

    tempScreen = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_ARGB);
    g2 = (Graphics2D) tempScreen.getGraphics();

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

        if ( restart == true ) {
            // Full restart — reload the main map from scratch
            currentMapId = "harta";
            String path = mapRegistry.getOrDefault(currentMapId, "/res/maps/harta.tmx");
            tileM.mapLayers.clear();
            tileM.loadMapFromTMX(path);
            tileM.loadCollisionLayer(path);
            cChecker.updateCollisionRectsCache();

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

        // GET LOCAL SCREEN DEVICE
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        gd.setFullScreenWindow(Main.window);

        // GET FULL SCREEN WIDTH AND HEIGHT
        screenWidth2 = Main.window.getWidth();
        screenHeight2 = Main.window.getHeight();

    }
    public void startGameThread(){

        gameThread = new Thread(this);
        gameThread.start();
    }

    public void run() {

        double drawInterval = 1000000000 / FPS;
        double delta = 0;
        long lastTime = System.nanoTime();
        long currentTime;
        long timer = 0;
        int drawCount = 0; 

        while(gameThread != null) {

            currentTime = System.nanoTime();

            delta += (currentTime - lastTime) / drawInterval;
            timer += (currentTime - lastTime);
            lastTime = currentTime;

            if(delta >= 1) {
                update();
                drawToTempScreen(); // draw = Buffered image
                drawToScreen(); // screen = Buffered image
                delta--;
                drawCount++;
            }

            if(timer >= 1000000000) {
                currentFPS = drawCount;
                drawCount = 0;
                timer = 0;
            }

        }
    }


    public void update() {

        if(gameState == playState) {
            // PLAYER
            player.update();
            // NPC
            for ( int i = 0 ; i < npc.length; i++ ) {
                if ( npc[i] != null ) {
                    // OPTIMIZATION: Only update NPCs that are in or near viewport
                    if (isEntityInViewport(npc[i], tileSize * 2)) {
                        npc[i].update();
                    }
                }
            }
            // MONSTER
            for ( int i = 0 ; i < monster.length ; i++ ) {
                if ( monster[i] != null ) {
                    if ( monster[i].alive == true && monster[i].dying == false ) {
                        // OPTIMIZATION: Only update monsters that are in or near viewport
                        if (isEntityInViewport(monster[i], tileSize * 2)) {
                            monster[i].update();
                        }
                    }
                    if ( monster[i].alive == false ) {
                        monster[i] = null;
                    }
                }
            }
            // OPTIMIZATION: Use backwards iteration to safely remove while iterating
            for ( int i = projectilesList.size() - 1 ; i >= 0 ; i-- ) {
                Entity proj = projectilesList.get(i);
                if (proj != null) {
                    if (proj.alive == true) {
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
                    if (particle.alive == true) {
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
        }
            if (player.life <= 0) {
            player.life = 0; // safety clamp

            gameState = gameOverState;
            ui.commandNum = 0;

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
    public boolean isEntityInViewport(Entity entity, int margin) {
        int minX = player.worldX - player.screenX - margin;
        int maxX = player.worldX - player.screenX + screenWidth + margin;
        int minY = player.worldY - player.screenY - margin;
        int maxY = player.worldY - player.screenY + screenHeight + margin;
        
        return entity.worldX + tileSize > minX &&
               entity.worldX < maxX &&
               entity.worldY + tileSize > minY &&
               entity.worldY < maxY;
    }

    public void drawToTempScreen() {
    //DEBUG
    long drawStart = 0;
    if(keyH.showDebugText == true ) {
        drawStart = System.nanoTime();
    }

    drawCurrentState();

    // DEBUG TEXT
    if(keyH.showDebugText == true ) {
        long drawEnd = System.nanoTime();
        long passed = drawEnd - drawStart;

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
            default:
                drawWorldState();
                break;
        }
    }

    private void drawWorldState() {

        // TILE
        tileM.draw(g2);

        collectRenderableEntities();

        // SORT (only sort the active portion of the list)
        Collections.sort(entityList.subList(0, entityListIndex), renderSorter);

        // DRAW ENTITIES (only draw active entities)
        for (int i = 0; i < entityListIndex; i++) {
            entityList.get(i).draw(g2);
        }

        clearRenderableEntities();

        // CUTSCENE
        csManager.draw(g2);

        // ENVIRONMENT
        eManager.draw(g2);

        // UI
        ui.draw(g2);

        if (HitBoxes == true) {
    
            g2.setColor(new Color(255, 0, 0, 128)); // red semi-transparent

            // PLAYER
            Rectangle r = player.solidArea;
            int px = player.worldX - player.worldX + player.screenX + r.x;
            int py = player.worldY - player.worldY + player.screenY + r.y;
            g2.fillRect(px, py, r.width, r.height);
            if (player.knockBack) {
                g2.setColor(new Color(255, 0, 255, 128));
                g2.fillRect(px, py, r.width, r.height);
                g2.setColor(new Color(255, 0, 0, 128));
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.PLAIN, 12));
                g2.drawString(String.valueOf(player.knockBackPower), px, py - 4);
                // draw vector arrow
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
                g2.setColor(new Color(255, 100, 0, 128)); // Orange semi-transparent for attack
                
                // Recreate attack hitbox for visualization (same logic as performAttackHitbox)
                Rectangle attackRect = new Rectangle();
                int ts = tileSize;
                int attackWorldX = player.worldX;
                int attackWorldY = player.worldY;
                
                switch(player.direction) {
                    case "up":
                        attackRect.width = ts - 16;
                        attackRect.height = ts + 16;
                        attackWorldX += 8;
                        attackWorldY -= ts + 16;
                        break;
                    case "down":
                        attackRect.width = ts - 16;
                        attackRect.height = ts + 16;
                        attackWorldX += 8;
                        attackWorldY += ts;
                        break;
                    case "left":
                        attackRect.width = ts + 16;
                        attackRect.height = ts - 16;
                        attackWorldX -= ts + 16;
                        attackWorldY += 8;
                        break;
                    case "right":
                        attackRect.width = ts + 16;
                        attackRect.height = ts - 16;
                        attackWorldX += ts;
                        attackWorldY += 8;
                        break;
                }
                // Convert to screen coordinates and draw
                int screenX = attackWorldX - player.worldX + player.screenX;
                int screenY = attackWorldY - player.worldY + player.screenY;
                g2.fillRect(screenX, screenY, attackRect.width, attackRect.height);
            }

            // NPC
            for(Entity n : npc) {
                if(n != null) {
                    r = n.solidArea;
                    int nx = n.worldX - player.worldX + player.screenX + r.x;
                    int ny = n.worldY - player.worldY + player.screenY + r.y;
                    g2.fillRect(nx, ny, r.width, r.height);
                }
            }

            // MONSTERS
            g2.setColor(new Color(255, 255, 0, 128)); // Yellow for monsters
            for(Entity m : monster) {
                if(m != null) {
                    r = m.solidArea;
                    int mx = m.worldX - player.worldX + player.screenX + r.x;
                    int my = m.worldY - player.worldY + player.screenY + r.y;
                    g2.fillRect(mx, my, r.width, r.height);
                    // visualise knockback state with magenta overlay
                    if (m.knockBack) {
                        g2.setColor(new Color(255, 0, 255, 128));
                        g2.fillRect(mx, my, r.width, r.height);
                        g2.setColor(new Color(255, 255, 0, 128));
                        // show knockback power as debug text
                        g2.setColor(Color.WHITE);
                        g2.setFont(new Font("Arial", Font.PLAIN, 12));
                        g2.drawString(String.valueOf(m.knockBackPower), mx, my - 4);
                        // draw arrow for vector direction
                        int cx = mx + r.width/2;
                        int cy = my + r.height/2;
                        int vx = m.knockBackVectorX * 2; // scale for visibility
                        int vy = m.knockBackVectorY * 2;
                        g2.drawLine(cx, cy, cx + vx, cy + vy);
                        g2.fillOval(cx + vx - 2, cy + vy - 2, 4, 4);
                        g2.setColor(new Color(255, 255, 0, 128));
                    }
                }
            }

            // OBJECTS
            for(Entity o : obj) {
                if(o != null) {
                    r = o.solidArea;
                    int ox = o.worldX - player.worldX + player.screenX + r.x;
                    int oy = o.worldY - player.worldY + player.screenY + r.y;
                    g2.fillRect(ox, oy, r.width, r.height);
                }
            }

            // INTERACTIVE TILES
            g2.setColor(new Color(0, 255, 255, 128)); // Cyan for interactive tiles
            for(int i = 0; i < iTile.length; i++) {
                if(iTile[i] != null) {
                    r = iTile[i].solidArea;
                    int ix = iTile[i].worldX - player.worldX + player.screenX + r.x;
                    int iy = iTile[i].worldY - player.worldY + player.screenY + r.y;
                    g2.fillRect(ix, iy, r.width, r.height);
                }
            }

            // COLLISION LAYER RECTANGLES
            if(tileM.collisionRects != null) {
                g2.setColor(new Color(0, 0, 255, 128)); // blue semi-transparent
                for(Rectangle cr : tileM.collisionRects) {
                    int cx = cr.x - player.worldX + player.screenX;
                    int cy = cr.y - player.worldY + player.screenY;
                    g2.fillRect(cx, cy, cr.width, cr.height);
                }
            }
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

        Graphics g = getGraphics();
        g.drawImage(tempScreen, 0, 0, getWidth(), getHeight(), null);
        g.dispose(); 
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
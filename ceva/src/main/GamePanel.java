package main;

import entity.Entity;
import entity.Player;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import javax.swing.JPanel;
import java.awt.Rectangle;

import ai.PathFinder;
import data.SaveLoad;
import tile.TileManager;

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

    public boolean HitBoxes = false;

    // FPS

    int FPS = 60;

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
    SaveLoad saveLoad = new SaveLoad(this);
    Thread gameThread;
    public boolean loadingGame = false;

    //ENTITY AND OBJECT
    public Player player = new Player(this,keyH);
    public Entity obj[] = new Entity[100];
    public Entity npc[] = new Entity[10];
    public Entity monster[] = new Entity[20];
    public ArrayList<Entity> projectilesList = new ArrayList<>();
    ArrayList<Entity> entityList = new ArrayList<>();

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

    //ABILITY
    public boolean teleportation = false;
    public boolean bootsUnlocked = false;

    public GamePanel() {

        this.setPreferredSize(new Dimension(screenWidth, screenHeight));
        this.setBackground(Color.black);
        this.setDoubleBuffered(true);
        this.addKeyListener(keyH);
        this.setFocusable(true);
    }

    public void setupGame() {

    if (!loadingGame) {
        aSetter.setObject(); // NEW GAME ONLY
    }

    aSetter.setNPC();
    aSetter.setMonster();
    gameState = titleState;

    tempScreen = new BufferedImage(screenWidth, screenHeight, BufferedImage.TYPE_INT_ARGB);
    g2 = (Graphics2D) tempScreen.getGraphics();

    if (fullScreenOn) {
        setFullScreen();
    }
}

    public void resetGame(boolean restart) {

        player.setDefaultPositions();
        player.restoreLifeAndMana();
        aSetter.setNPC();
        aSetter.setMonster();

        if ( restart == true ) {
            player.setDefaultValues();
            aSetter.setObject();
            // aSetter.setInteractibeTile();
        }
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
                    npc[i].update();
                }
            }
            // MONSTER
            for ( int i = 0 ; i < monster.length ; i++ ) {
                if ( monster[i] != null ) {
                    if ( monster[i].alive == true && monster[i].dying == false ) {
                        monster[i].update();
                    }
                    if ( monster[i].alive == false ) {
                        monster[i] = null;
                    }
                }
            }
            for ( int i = 0 ; i < projectilesList.size() ; i++ ) {
                if ( projectilesList.get(i) != null ) {
                    if ( projectilesList.get(i).alive == true ) {
                        projectilesList.get(i).update();
                    }
                    if ( projectilesList.get(i).alive == false ) {
                        projectilesList.remove(i);
                        i--;
                    }
                }
            }
        }
        if (player.life <= 0) {
        player.life = 0; // safety clamp

        gameState = gameOverState;
        ui.commandNum = -1;

        stopMusic();
        playSE(4);
    }
    }
    public void drawToTempScreen() {
    //DEBUG
    long drawStart = 0;
    if(keyH.showDebugText == true ) {
        drawStart = System.nanoTime();
    }

    // TITLE SCREEN
    if (gameState == titleState) {
        ui.draw(g2);
    }
    // OTHERS
    else {

        // TILE
        tileM.draw(g2);

        // ADD ENTITIES TO THE LIST
        entityList.add(player);

        for ( int i = 0 ; i < npc.length ; i++ ) {
            if ( npc[i] != null ) {
                entityList.add(npc[i]);
            }
        }

        for ( int i = 0 ; i < obj.length ; i++ ) {
            if ( obj[i] != null ) {
                entityList.add(obj[i]);
            }
        }

        for ( int i = 0 ; i < monster.length ; i++ ) {
            if ( monster[i] != null ) {
                entityList.add(monster[i]);
            }
        }
        for ( int i = 0 ; i < projectilesList.size() ; i++ ) {
            if ( projectilesList.get(i) != null ) {
                entityList.add(projectilesList.get(i));
            }
        }

        // SORT
        Collections.sort(entityList, new Comparator<Entity>() {
            @Override
            public int compare(Entity e1, Entity e2) {
                int result = Integer.compare(e1.worldY, e2.worldY);
                return result;
            }
        });

        // DRAW ENTITIES
        for (int i = 0; i < entityList.size(); i++) {
            entityList.get(i).draw(g2);
        }
        // EMPTY ENTITY LIST
        entityList.clear();

        // CUTSCENE 
        csManager.draw(g2);

        // UI
        ui.draw(g2);

        // DEBUG HITBOX START
        if ( HitBoxes == true ) {
    
            g2.setColor(new Color(255, 0, 0, 128)); // red semi-transparent

            // PLAYER
            Rectangle r = player.solidArea;
            int px = player.worldX - player.worldX + player.screenX + r.x;
            int py = player.worldY - player.worldY + player.screenY + r.y;
            g2.fillRect(px, py, r.width, r.height);

            // NPC
            for(Entity n : npc) {
                if(n != null) {
                    r = n.solidArea;
                    int nx = n.worldX - player.worldX + player.screenX + r.x;
                    int ny = n.worldY - player.worldY + player.screenY + r.y;
                    g2.fillRect(nx, ny, r.width, r.height);
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
        // DEBUG HITBOX END
    }

    // DEBUG TEXT
    if(keyH.showDebugText == true ) {
        long drawEnd = System.nanoTime();
        long passed = drawEnd - drawStart;

        g2.setFont(new Font("Arial",Font.PLAIN, 20));
        g2.setColor(Color.WHITE);
        int x = 10;
        int y = 400;
        int lineHeigh = 20;

        g2.drawString("WorldX" + player.worldX, x, y); y += lineHeigh;
        g2.drawString("WorldY" + player.worldY, x, y); y += lineHeigh;
        g2.drawString("Col" + (player.worldX + player.solidArea.x) / tileSize, x, y); y += lineHeigh;
        g2.drawString("Row" + (player.worldY + player.solidArea.y) / tileSize, x, y); y += lineHeigh;
    }
}


    public void drawToScreen() {

        Graphics g = getGraphics();
        g.drawImage(tempScreen, 0, 0, screenWidth2, screenHeight2, null);
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
package ui;

import gfx.Color;
import gfx.Font;
import gfx.FontMetrics;
import gfx.GdxRenderer;
import gfx.Stroke;

import audio.SFX;
import main.GamePanel;

public class CutsceneManager {

    GamePanel gp;
    GdxRenderer g2;
    public int sceneNum;
    public int scenePhase;

    private Font pixelFont;
    private final java.util.HashMap<Float, Font> fontCache = new java.util.HashMap<>();

    // Cached cutscene fonts — one per distinct pixel size used in scenes.
    private final java.util.HashMap<Integer, Font> cutsceneFontCache = new java.util.HashMap<>();
    private Font getCutsceneFont(int size) {
        return cutsceneFontCache.computeIfAbsent(size, s -> pixelFont.deriveFont(Font.ITALIC, s));
    }

    public final int NA = 0;
    public final int awakening = 1;
    public final int ending = 2;
    public int counter = 0;

    /** Returns true when ambient lights/mapShader should be suppressed (text-only phases). */
    public boolean suppressAmbientLights() {
        return sceneNum == awakening && scenePhase >= 1 && scenePhase <= 5;
    }
    float alpha = 0f;
    int y;
    String endCredit;

    // Ending scene rain effect (screen-space particles)
    private static final int RAIN_COUNT = 120;
    private final float[] rX     = new float[RAIN_COUNT];
    private final float[] rY     = new float[RAIN_COUNT];
    private final float[] rSpeed = new float[RAIN_COUNT];
    private final float[] rLen   = new float[RAIN_COUNT];
    private boolean rainInitialized = false;
    private final java.util.Random rng = new java.util.Random();

    // Section header names for gold-coloured credit titles
    private static final java.util.Set<String> CREDIT_HEADERS = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "Directed by", "Programmed by", "Designed by",
            "Written by", "Music by", "Special Thanks to",
            "Sources from", "Thank you for playing!"
        )
    );

    // Awakening cutscene fields
    private int typewriterIndex = 0;
    private int typewriterCounter = 0;
    private float cameraPanX, cameraPanY;
    private float cameraPanTargetX, cameraPanTargetY;
    private static final int TYPEWRITER_SPEED = 4; // frames per character
    

    public CutsceneManager ( GamePanel gp ) {
        this.gp = gp;

        try {
            pixelFont = Font.createFont(Font.TRUETYPE_FONT,
                    util.ResourceCache.openClasspathStream("/res/fonts/Pixeloid Sans.ttf"),
                    "Pixeloid Sans");
        } catch (Exception e) {
            pixelFont = new Font("Segoe UI", Font.PLAIN, 12);
        }

        endCredit = "Thank you for playing!\n"
                + "\n\n\n\n"
                + "Directed by\n"
                + "Ciuca Andrei-Corneliu / Lupu Iulian-Nicolae\n"
                + "\n\n\n"
                + "Programmed by\n"
                + "Ciuca Andrei-Corneliu / Lupu Iulian-Nicolae\n"
                + "\n\n\n"
                + "Designed by\n"
                + "Avram Dennis-Sebastian / Lupu Mirabela\n"
                + "\n\n\n"
                + "Written by\n"
                + "Lupu Cristian / Ciuca Andrei-Corneliu / Lupu Iulian-Nicolae\n"
                + "\n\n\n"
                + "Music by\n"
                + "Lupu Stefan\n"
                + "\n\n\n"
                + "Special Thanks to\n"
                + "Our friends for their support and encouragement\n"
                + "Drila Luca-Laurentiu , Virgil Mihaiu-Sebastian , Nedescu Adrian-Rafael\n"
                + "Stremiuc Neftali-Samuel, Avram Dennis-Sebastian\n"
                + "\n\n\n"
                + "Sources from\n"
                + "Youtube helper : RyiSnow\n"
                + "Sites : Pixabay.com , CraftPix.net";
    }
    public void draw ( GdxRenderer g2 ) {
        this.g2 = g2;

        switch (sceneNum) {

            case NA: break;
            case awakening: scene_awakening(); break;
            case ending: scene_ending(); break;


        }
    }
    // White screen → "..." → "Where... am I?" → world fades in →
    // camera pans to player → player appears → play state
    public void scene_awakening() {

        // Phase 0: INIT — hide player, lock camera, stop music
        if (scenePhase == 0) {
            gp.stopMusic();
            gp.player.invisible = true;
            // Lock camera to a nearby position (offset from player so the pan is visible)
            int spawnCol = gp.player.worldX / gp.tileSize;
            int spawnRow = gp.player.worldY / gp.tileSize;
            gp.lockCamera(spawnCol - 4, spawnRow - 3);
            cameraPanTargetX = gp.player.worldX;
            cameraPanTargetY = gp.player.worldY;
            alpha = 0f;
            counter = 0;
            typewriterIndex = 0;
            typewriterCounter = 0;
            scenePhase = 1;
        }

        // Phase 1: FADE IN from black to dark background
        if (scenePhase == 1) {
            drawBlackBackground(1f);
            alpha += 0.018f;
            if (alpha > 1f) alpha = 1f;
            drawDarkBackground(alpha);
            if (alpha >= 1f) {
                counter = 0;
                scenePhase = 2;
            }
        }

        // Phase 2: HOLD dark screen briefly
        if (scenePhase == 2) {
            drawDarkBackground(1f);
            if (counterReached(40)) {
                scenePhase = 3;
            }
        }

        // Phase 3: SHOW "..." typewriter on dark background
        if (scenePhase == 3) {
            drawDarkBackground(1f);
            String text = "...";
            drawTypewriterText(text, 0.5f, 38f);
            typewriterCounter++;
            if (typewriterCounter >= TYPEWRITER_SPEED) {
                typewriterCounter = 0;
                if (typewriterIndex < text.length()) typewriterIndex++;
            }
            if (typewriterIndex >= text.length() && counterReached(80)) {
                typewriterIndex = 0;
                typewriterCounter = 0;
                scenePhase = 4;
            }
        }

        // Phase 4: SHOW "Where... am I?"
        if (scenePhase == 4) {
            drawDarkBackground(1f);
            String text = "Where... am I?";
            drawTypewriterText(text, 0.5f, 42f);
            typewriterCounter++;
            if (typewriterCounter >= TYPEWRITER_SPEED) {
                typewriterCounter = 0;
                if (typewriterIndex < text.length()) typewriterIndex++;
            }
            if (typewriterIndex >= text.length() && counterReached(100)) {
                typewriterIndex = 0;
                typewriterCounter = 0;
                scenePhase = 5;
            }
        }

        // Phase 5: SHOW "ACT I: The Awakening" — larger, centred
        if (scenePhase == 5) {
            drawDarkBackground(1f);
            String text = "ACT I: The Awakening";
            drawTypewriterText(text, 0.5f, 46f);
            typewriterCounter++;
            if (typewriterCounter >= TYPEWRITER_SPEED) {
                typewriterCounter = 0;
                if (typewriterIndex < text.length()) typewriterIndex++;
            }
            if (typewriterIndex >= text.length() && counterReached(180)) {
                scenePhase = 6;
                alpha = 1f;
            }
        }

        // Phase 6: DARK FADES OUT — reveal game world + start music
        if (scenePhase == 6) {
            alpha -= 0.010f;
            if (alpha <= 0f) {
                alpha = 0f;
                gp.playMusic(SFX.AWAKENING_CAVE);
                scenePhase = 7;
                // Initialize camera pan from current lock position
                cameraPanX = gp.cameraWorldX;
                cameraPanY = gp.cameraWorldY;
                counter = 0;
            }
            drawDarkBackground(alpha);
        }

        // Phase 7: CAMERA PANS smoothly toward player position
        if (scenePhase == 7) {
            float panSpeed = 0.03f;
            cameraPanX += (cameraPanTargetX - cameraPanX) * panSpeed;
            cameraPanY += (cameraPanTargetY - cameraPanY) * panSpeed;
            gp.cameraWorldX = (int) cameraPanX;
            gp.cameraWorldY = (int) cameraPanY;

            float dx = Math.abs(cameraPanTargetX - cameraPanX);
            float dy = Math.abs(cameraPanTargetY - cameraPanY);
            if ((dx < 2 && dy < 2) || counterReached(0)) { // safety timeout in case of any issues
                gp.cameraWorldX = (int) cameraPanTargetX;
                gp.cameraWorldY = (int) cameraPanTargetY;
                scenePhase = 8;
                alpha = 0f;
                counter = 0;
            }
        }

        // Phase 8: PLAYER FADES IN — show player, brief pause
        if (scenePhase == 8) {
            gp.player.invisible = false;
            if (counterReached(5)) {
                scenePhase = 10;
                typewriterIndex = 0;
                typewriterCounter = 0;
            }
        }

        /*// Phase 9: "This place... it's drawn." text overlay
        if (scenePhase == 9) {
            String text = "This place... it's drawn.";
            drawOverlayText(text, 0.85f, 36f);
            typewriterCounter++;
            if (typewriterCounter >= TYPEWRITER_SPEED) {
                typewriterCounter = 0;
                if (typewriterIndex < text.length()) typewriterIndex++;
            }
            if (typewriterIndex >= text.length() && counterReached(120)) {
                scenePhase = 10;
            }
        }*/

        // Phase 10: CLEANUP — unlock camera, return to play state
        if (scenePhase == 10) {
            gp.unlockCamera();
            gp.player.invisible = false;
            sceneNum = NA;
            scenePhase = 0;
            counter = 0;
            alpha = 0f;
            gp.gameState = GamePanel.playState;

            // Show actTitle for the starting map if it was marked as newGame-only
            showNewGameActTitle();

            // First inner monologue after waking up
            if (gp.thoughts != null) {
                gp.thoughts.show("I can't remember anything... Just fragments... like a dream I can't hold.", 150, 60);
            }
            // Start all autoStart quests now that a new game has fully begun
            if (gp.questManager != null) gp.questManager.startAutoQuests();
        }
    }

    /** Skip directly to the end of the awakening cutscene. */
    public void skipAwakening() {
        if (sceneNum != awakening) return;
        gp.player.invisible = false;
        gp.unlockCamera();
        if (scenePhase < 6) {
            // Music hasn't started yet
            gp.playMusic(SFX.AWAKENING_CAVE);
        }
        sceneNum = NA;
        scenePhase = 0;
        counter = 0;
        alpha = 0f;
        gp.gameState = GamePanel.playState;

        // Show actTitle for the starting map if it was marked as newGame-only
        showNewGameActTitle();
        // Start all autoStart quests now that a new game has fully begun
        if (gp.questManager != null) gp.questManager.startAutoQuests();

        // First inner monologue after waking up
        if (gp.thoughts != null) {
            gp.thoughts.show("I can't remember anything... Just fragments... like a dream I can't hold.", 150, 60);
        }
    }

    /** Shows the pending actTitle if it is flagged as newGame-only and hasn't been shown yet this run. */
    private void showNewGameActTitle() {
        if (!gp.mapManager.pendingActTitle.isEmpty() && gp.mapManager.pendingActTitleNewGameOnly) {
            String mapId = gp.mapManager.currentMapId;
            if (!gp.mapManager.shownActTitles.contains(mapId)) {
                gp.mapManager.shownActTitles.add(mapId);
                gp.ui.showActTitle(gp.mapManager.pendingActTitle);
            }
            gp.mapManager.pendingActTitle = "";
        }
    }


    private void drawWhiteBackground(float a) {
        float clamped = Math.max(0f, Math.min(1f, a));
        // At full alpha this opaque fill completely replaces whatever is beneath —
        // prevents game-world lights/glows from bleeding through the cutscene overlay.
        g2.setAlpha(clamped);
        g2.setColor(Color.white);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setAlpha(1f);
    }

    // Deep dark warm background — like waking in near-darkness
    private static final Color DARK_BG = new Color(8, 5, 12);

    private void drawDarkBackground(float a) {
        float clamped = Math.max(0f, Math.min(1f, a));
        g2.setAlpha(clamped);
        g2.setColor(DARK_BG);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setAlpha(1f);
    }

    /** Draw typewriter text centered on the dark cutscene background. */
    private void drawTypewriterText(String fullText, float yFraction, float fontSize) {
        String visible = fullText.substring(0, Math.min(typewriterIndex, fullText.length()));
        g2.setFont(getCutsceneFont((int) fontSize));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (gp.screenWidth - fm.stringWidth(visible)) / 2;
        int ty = (int) (gp.screenHeight * yFraction);
        // Subtle shadow then warm cream text
        g2.setColor(new Color(0, 0, 0, 120));
        g2.drawString(visible, tx + 2, ty + 2);
        g2.setColor(new Color(220, 205, 175));
        g2.drawString(visible, tx, ty);
    }

    /** Draw semi-transparent overlay text at the bottom of the screen during gameplay reveal. */
    private void drawOverlayText(String fullText, float yFraction, float fontSize) {
        String visible = fullText.substring(0, Math.min(typewriterIndex, fullText.length()));
        int barH = 60;
        int barY = (int) (gp.screenHeight * yFraction) - 35;
        g2.setAlpha(0.5f);
        g2.setColor(Color.black);
        g2.fillRect(0, barY, gp.screenWidth, barH);
        g2.setAlpha(1f);
        g2.setFont(getCutsceneFont((int) fontSize));
        g2.setColor(new Color(240, 230, 210));
        FontMetrics fmOverlay = g2.getFontMetrics();
        int txOverlay = (gp.screenWidth - fmOverlay.stringWidth(visible)) / 2;
        int tyOverlay = (int) (gp.screenHeight * yFraction);
        g2.drawString(visible, txOverlay, tyOverlay);
    }

    public void scene_ending() {

        gp.eManager.setWeatherByName("CLEAR");
        if ( scenePhase == 0 ) {

            gp.stopMusic();
            gp.ui.npc = null;
            gp.eManager.pinnedFilterAlpha = 0.0f;
            gp.eManager.setWeatherByName("RAIN");
            scenePhase++;
        }
        if ( scenePhase == 1 ) {

            scenePhase++;
        }
        if ( scenePhase == 2 ) {

            // PLAY THE FANFARE while staying in cutscene mode
            gp.gameState = GamePanel.cutsceneState;
            gp.playSE(SFX.VICTORY);
            scenePhase++;
        } 
        if ( scenePhase == 3 ) {

            // WAIT UNTIL THE SOUND EFFECT ENDS
            if ( counterReached(200) ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 4 ) {

            alpha += 0.005f;
            if ( alpha > 1f ) {
                alpha = 1f;
            }
            drawBlackBackground(alpha);
            if (alpha > 0.2f) updateAndDrawRain(alpha);

            if ( alpha == 1f ) {
                alpha = 0;
                scenePhase++;
            }
        }
        if ( scenePhase == 5 ) {

            drawBlackBackground(1f);
            updateAndDrawRain(1f);

            alpha += 0.005f;
            if ( alpha > 1f ) {
                alpha = 1f;
            }

            String text = "After a long way, \nour Spirit found the Unknown Memory \nand reached a new power! \n[TO BE CONTINUED]";
            drawString(alpha, 38f, 200, text, 70);

            if ( counterReached(600) ) {
                scenePhase++;
            }

        }
        if ( scenePhase == 6 ) {

            drawBlackBackground(1f);
            updateAndDrawRain(1f);

            drawString(1f, 120f, gp.screenHeight / 2, "Echoes of the Heir", 40);
        
            if ( counterReached(300) ) {
                scenePhase++;
            }
        }
        
        if ( scenePhase == 7 ) {

            drawBlackBackground(1f);
            updateAndDrawRain(1f);

            y = gp.screenHeight / 2;
            drawString(1f, 38f, y, endCredit, 40);

            if ( counterReached(480) ) {
                scenePhase++;
            }
        }

        if ( scenePhase == 8 ) {

            drawBlackBackground(1f);
            updateAndDrawRain(1f);

            y--;    
            drawString(1f, 38f, y, endCredit, 40);

            if ( counterReached(2500) ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 9 ) {

            drawBlackBackground(1f);
            updateAndDrawRain(1f);

            drawString(1f, 120f, gp.screenHeight / 2, "Echoes of the Heir", 40);

            if ( counterReached(480) ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 10 ) {
            gp.eManager.pinnedFilterAlpha = -1f;
            gp.eManager.pinnedWeather = -1;
            gp.eManager.setWeather(0);
            gp.ui.titleScreenState = 0;
            gp.gameState = GamePanel.titleState;
        }

    }
    private void initRain() {
        for (int i = 0; i < RAIN_COUNT; i++) resetDrop(i, true);
        rainInitialized = true;
    }

    private void resetDrop(int i, boolean randomY) {
        rX[i]     = rng.nextFloat() * gp.screenWidth;
        rY[i]     = randomY ? rng.nextFloat() * gp.screenHeight : -(rng.nextFloat() * 40 + 10);
        rSpeed[i] = 8 + rng.nextFloat() * 7;
        rLen[i]   = 6 + rng.nextFloat() * 8;
    }

    private void updateAndDrawRain(float overallAlpha) {
        if (!rainInitialized) initRain();
        g2.setAlpha(0.45f * overallAlpha);
        g2.setColor(new Color(180, 210, 255));
        g2.setStroke(new Stroke(1f));
        for (int i = 0; i < RAIN_COUNT; i++) {
            rY[i] += rSpeed[i];
            rX[i] -= 1.2f;
            if (rY[i] > gp.screenHeight + 20 || rX[i] < -20) resetDrop(i, false);
            g2.drawLine((int) rX[i], (int) rY[i],
                        (int)(rX[i] + 2), (int)(rY[i] + rLen[i]));
        }
        g2.setAlpha(1f);
        g2.setStroke(new Stroke(1f));
    }

    private boolean isSectionHeader(String line) {
        return CREDIT_HEADERS.contains(line.trim());
    }

    public boolean counterReached ( int target ) {

        boolean counterReached = false;

        
        counter++;
        if ( counter > target ) {
            counterReached = true;
            counter = 0;
        }

        return counterReached;
    }
    public void drawBlackBackground ( float alpha ) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        g2.setAlpha(clamped);
        g2.setColor(Color.black);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setAlpha(1f);
    }

    public void drawString( float alpha, float fontSize, int y, String text, int lineHeigh) {
        g2.setAlpha(alpha);
        g2.setFont(fontCache.computeIfAbsent(fontSize, s -> pixelFont.deriveFont(Font.PLAIN, s)));
        FontMetrics fm = g2.getFontMetrics();
        for ( String line: text.split("\n")) {
            int x = (gp.screenWidth - fm.stringWidth(line)) / 2;
            g2.setColor(isSectionHeader(line) ? new Color(255, 215, 100) : Color.white);
            g2.drawString(line, x, y);
            y += lineHeigh;
        }
        g2.setAlpha(1f);
    }
}

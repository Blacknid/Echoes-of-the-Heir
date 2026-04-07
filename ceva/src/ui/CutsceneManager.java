package ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import audio.SFX;
import main.GamePanel;
import object.OBJ_Gem;

public class CutsceneManager {

    GamePanel gp;
    Graphics2D g2;
    public int sceneNum;
    public int scenePhase;

    // OPTIMIZATION: Font cache to avoid deriveFont() per frame
    private final java.util.HashMap<Float, Font> fontCache = new java.util.HashMap<>();

    // Scene Number
    public final int NA = 0;
    public final int awakening = 1;
    public final int ending = 2;
    int counter = 0;
    float alpha = 0f;
    int y;
    String endCredit;

    // Awakening cutscene fields
    private int typewriterIndex = 0;
    private int typewriterCounter = 0;
    private float cameraPanX, cameraPanY;
    private float cameraPanTargetX, cameraPanTargetY;
    private static final int TYPEWRITER_SPEED = 4; // frames per character
    

    public CutsceneManager ( GamePanel gp ) {
        this.gp = gp;

        endCredit = "Directed by\n" 
                + "Ciuca Andrei-Corneliu / Lupu Iulian-Nicolae\n"
                + "\n\n\n\n\n\n\n\n\n\n\n\n"
                + "Programmed by\n"
                + "Ciuca Andrei-Corneliu / Lupu Iulian-Nicolae\n"
                + "\n\n\n"
                + "Designed by\n"
                + "Avram Dennis-Sebastian / Lupu Mirabela\n"
                + "\n\n\n"
                + "Written by\n"
                + "Lupu Cristian\n"
                + "\n\n\n"
                + "Music by\n"
                + "Lupu Stefan\n"
                + "\n\n\n\n\n\n"
                + "Sources from\n"
                + "Youtube helper : RyiSnow\n"
                + "Sites : Pixabay.com , CraftPix.net";
    }
    public void draw ( Graphics2D g2 ) {
        this.g2 = g2;

        switch (sceneNum) {

            case NA: break;
            case awakening: scene_awakening(); break;
            case ending: scene_ending(); break;


        }
    }
    // ── AWAKENING CUTSCENE ──────────────────────────────────────────────
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

        // Phase 1: FADE TO WHITE from black (title screen)
        if (scenePhase == 1) {
            drawBlackBackground(1f);
            alpha += 0.012f;
            if (alpha > 1f) alpha = 1f;
            drawWhiteBackground(alpha);
            if (alpha >= 1f) {
                alpha = 1f;
                counter = 0;
                scenePhase = 2;
            }
        }

        // Phase 2: HOLD WHITE SCREEN (60 frames = 1 second)
        if (scenePhase == 2) {
            drawWhiteBackground(1f);
            if (counterReached(60)) {
                scenePhase = 3;
            }
        }

        // Phase 3: SHOW "..." with typewriter on white
        if (scenePhase == 3) {
            drawWhiteBackground(1f);
            String text = "...";
            drawTypewriterText(text, 0.5f, 42f);
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

        // Phase 4: SHOW "Where... am I?" with typewriter on white
        if (scenePhase == 4) {
            drawWhiteBackground(1f);
            String text = "Where... am I?";
            drawTypewriterText(text, 0.5f, 48f);
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

        // Phase 5: SHOW "I can't remember anything..." with typewriter on white
        if (scenePhase == 5) {
            drawWhiteBackground(1f);
            String text = "ACT I: The Awakening";
            drawTypewriterText(text, 0.5f, 52f);
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

        // Phase 6: WHITE FADES OUT — reveal game world + start music
        if (scenePhase == 6) {
            alpha -= 0.008f;
            if (alpha <= 0f) {
                alpha = 0f;
                gp.playMusic(SFX.AWAKENING_CAVE);
                scenePhase = 7;
                // Initialize camera pan from current lock position
                cameraPanX = gp.cameraWorldX;
                cameraPanY = gp.cameraWorldY;
                counter = 0;
            }
            drawWhiteBackground(alpha);
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

            // First inner monologue after waking up
            if (gp.thoughts != null) {
                gp.thoughts.show("I can't remember anything... Just fragments... like a dream I can't hold.", 150, 60);
            }
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

        // First inner monologue after waking up
        if (gp.thoughts != null) {
            gp.thoughts.show("I can't remember anything... Just fragments... like a dream I can't hold.", 150, 60);
        }
    }

    // ── AWAKENING DRAWING HELPERS ────────────────────────────────────────

    private void drawWhiteBackground(float a) {
        float clamped = Math.max(0f, Math.min(1f, a));
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamped));
        g2.setColor(Color.white);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }

    /** Draw typewriter text centered on a white-background cutscene. */
    private void drawTypewriterText(String fullText, float yFraction, float fontSize) {
        String visible = fullText.substring(0, Math.min(typewriterIndex, fullText.length()));
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(new Font("Serif", Font.ITALIC, (int) fontSize));
        g2.setColor(new Color(60, 50, 50));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (gp.screenWidth - fm.stringWidth(visible)) / 2;
        int ty = (int) (gp.screenHeight * yFraction);
        g2.drawString(visible, tx, ty);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    /** Draw semi-transparent overlay text at the bottom of the screen during gameplay reveal. */
    private void drawOverlayText(String fullText, float yFraction, float fontSize) {
        String visible = fullText.substring(0, Math.min(typewriterIndex, fullText.length()));
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Dark semi-transparent bar behind text
        int barH = 60;
        int barY = (int) (gp.screenHeight * yFraction) - 35;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g2.setColor(Color.black);
        g2.fillRect(0, barY, gp.screenWidth, barH);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
        // Text
        g2.setFont(new Font("Serif", Font.ITALIC, (int) fontSize));
        g2.setColor(new Color(240, 230, 210));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (gp.screenWidth - fm.stringWidth(visible)) / 2;
        int ty = (int) (gp.screenHeight * yFraction);
        g2.drawString(visible, tx, ty);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    public void scene_ending() {

        if ( scenePhase == 0 ) {

            gp.stopMusic();
            // Keep the current gem dialogue source if it was already set by pickup.
            // Fallback only if cutscene was started without an NPC context.
            if (gp.ui.npc == null) {
                gp.ui.npc = new OBJ_Gem(gp);
            }
            scenePhase++;
        }
        if ( scenePhase == 1 ) {

            // DISPLAY DIALOGUES
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

            // THE SCREEN GETS DARKER
            alpha += 0.005f;
            if ( alpha > 1f ) {
                alpha = 1f;
            }
            drawBlackBackground(alpha);

            if ( alpha == 1f ) {
                alpha = 0;
                scenePhase++;
            }
        }
        if ( scenePhase == 5 ) {

            drawBlackBackground(1f);

            alpha += 0.005f;
            if ( alpha > 1f ) {
                alpha = 1f;
            }

            String text = "After a long way, \nour Spirit found the Dark Heart \nand reached a new power! \n[TO BE CONTINUED]";
            drawString(alpha, 38f, 200, text, 70);

            if ( counterReached(600) ) {
                scenePhase++;
            }

        }
        if ( scenePhase == 6 ) {

            drawBlackBackground(1f);

            drawString(1f, 120f, gp.screenHeight / 2, "Michiduta Adventure", 40);
        
            if ( counterReached(300) ) {
                scenePhase++;
            }
        }
        
        if ( scenePhase == 7 ) {

            drawBlackBackground(1f);

            y = gp.screenHeight / 2;
            drawString(1f, 38f, y, endCredit, 40);

            if ( counterReached(480) ) {
                scenePhase++;
            }
        }

        if ( scenePhase == 8 ) {

            drawBlackBackground(1f);

            // SCROLLING THE CREDIT
            
            y--;    
            drawString(1f, 38f, y, endCredit, 40);

            if ( counterReached(2500) ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 9 ) {

            drawBlackBackground(1f);

            drawString(1f, 120f, gp.screenHeight / 2, "Michiduta Adventure", 40);

            if ( counterReached(480) ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 10 ) {
            gp.ui.titleScreenState = 0;
            gp.gameState = GamePanel.titleState;
        }

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

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.setColor(Color.black);
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }
    public void drawString( float alpha, float fontSize, int y, String text, int lineHeigh) {

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.setColor(Color.white);  
        g2.setFont(fontCache.computeIfAbsent(fontSize, s -> g2.getFont().deriveFont(s)));
        
        for ( String line: text.split("\n")) {
            int x = gp.ui.getXforCenteredText(line);
            g2.drawString(line, x, y);
            y += lineHeigh;
        }
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }
}

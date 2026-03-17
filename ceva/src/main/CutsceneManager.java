package main;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import object.OBJ_Gem;

public class CutsceneManager {

    GamePanel gp;
    Graphics2D g2;
    public int sceneNum;
    public int scenePhase;

    // Scene Number
    public final int NA = 0;
    public final int ending = 2;
    int counter = 0;
    float alpha = 0f;
    int y;
    String endCredit;
    

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
            case ending: scene_ending(); break;


        }
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
            gp.gameState = gp.cutsceneState;
            gp.playSE(6);
            scenePhase++;
        } 
        if ( scenePhase == 3 ) {

            // WAIT UNTIL THE SOUND EFFECT ENDS
            if ( counterReached(200) == true ) {
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

            if ( counterReached(600) == true ) {
                //gp.playMusic(ending);
                scenePhase++;
            }

        }
        if ( scenePhase == 6 ) {

            drawBlackBackground(1f);

            drawString(1f, 120f, gp.screenHeight / 2, "Michiduta Adventure", 40);
        
            if ( counterReached(300) == true ) {
                scenePhase++;
            }
        }
        
        if ( scenePhase == 7 ) {

            drawBlackBackground(1f);

            y = gp.screenHeight / 2;
            drawString(1f, 38f, y, endCredit, 40);

            if ( counterReached(480) == true ) {
                scenePhase++;
            }
        }

        if ( scenePhase == 8 ) {

            drawBlackBackground(1f);

            // SCROLLING THE CREDIT
            
            y--;    
            drawString(1f, 38f, y, endCredit, 40);

            if ( counterReached(2500) == true ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 9 ) {

            drawBlackBackground(1f);

            drawString(1f, 120f, gp.screenHeight / 2, "Michiduta Adventure", 40);

            if ( counterReached(480) == true ) {
                scenePhase++;
            }
        }
        if ( scenePhase == 10 ) {
            gp.ui.titleScreenState = 0;
            gp.gameState = gp.titleState;
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
        g2.setFont(g2.getFont().deriveFont(fontSize));
        
        for ( String line: text.split("\n")) {
            int x = gp.ui.getXforCenteredText(line);
            g2.drawString(line, x, y);
            y += lineHeigh;
        }
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }
}

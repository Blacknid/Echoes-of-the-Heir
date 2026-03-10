package main;

import entity.Entity;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import object.OBJ_Heart;
import object.OBJ_Key;
import object.OBJ_ManaCrystal;

public class UI {

    GamePanel gp;
    Graphics2D g2;
    Font arial_40, arial_80B;
    BufferedImage Hearts_Full, Hearts_Empty, Key, Crystal_Full, Crystal_Empty;
    public BufferedImage Compas;
    public BufferedImage titleBackground;
    public boolean messageOn = false;
    //public String message = "";
    //int messageCounter = 0;
    ArrayList<String> message = new ArrayList<>();
    ArrayList<Integer> messageCounter = new ArrayList<>();
    ArrayList<Color> messageColor = new ArrayList<>();
    public boolean gameFinished = false;
    public String currentDialogue = "";
    public int commandNum = 0;
    public int titleScreenState = 0; // 0 : the first screen, 1: the second screen
    public int slotCol = 0;
    public int slotRow = 0;
    int subState = 0;
    int counter = 0;
    public Entity npc;
    int charIndex = 0;
    public float transitionAlpha = 0f;
    String combinedText = "";


    public UI(GamePanel gp) {
        this.gp = gp;

        arial_40 = new Font("Georgia", Font.PLAIN, 40);
        arial_80B = new Font("Arial", Font.BOLD, 80);

        // CREATE HUB OBJECT
        Entity heart = new OBJ_Heart(gp);
        Hearts_Full = heart.image;
        Hearts_Empty = heart.image1;

        Entity crystal = new OBJ_ManaCrystal(gp);
        Crystal_Full = crystal.image2;
        Crystal_Empty = crystal.image1;
        

        Entity key = new OBJ_Key(gp);
        Key = key.down1;

        // LOAD TITLE BACKGROUND
        try {
            titleBackground = ImageIO.read(getClass().getResourceAsStream("/res/background.png"));
            if (titleBackground != null) {
                UtilityTool uTool = new UtilityTool();
                titleBackground = uTool.scaleImage(titleBackground, gp.screenWidth, gp.screenHeight);
                System.out.println("Title background loaded successfully!");
            } else {
                System.out.println("Title background file found but could not be loaded");
            }
        } catch(Exception e) {
            System.out.println("Title background not found at /res/background.png, using default black background");
            e.printStackTrace();
        }

    }
    public void addMessage(String text, Color color) {

        message.add(text);
        messageColor.add(color);
        messageCounter.add(0);
    }
    public void draw(Graphics2D g2) {

        this.g2 = g2;
    
        g2.setFont(arial_40);
        g2.setColor(Color.white);

        //TITLE STATE
        if(gp.gameState == gp.titleState) {
            drawTitleScreen();
        }

        //PLAY STATE
        if(gp.gameState == gp.playState) {
            drawPlayerLife();
            drawMessage();
        }

        // PAUSE STATE
        if(gp.gameState == gp.pauseState) {
            drawPauseScreen();
        }

        //DIALOGUE STATE
        if(gp.gameState == gp.dialogueState){
            drawPlayerLife();
            drawDialogueScreen();
        }

        // CHARACHTER STATE
        if ( gp.gameState == gp.characterState ) {
            drawCharacterScreen();
            drawInventory();
        }

        // OPTIONS STATE
        if(gp.gameState == gp.optionsState){
            drawOptionsScreen();
        }

        // GAME OVER STATE
        if(gp.gameState == gp.gameOverState){
            drawGameOverScreen();
        }
        // TRANSITION STATE
        if (gp.gameState == gp.transitionState) {
            drawTransition(g2);
        }

        if ( gp.gameState == gp.cutsceneState ) {
            drawDialogueScreen();
        }

        if( gameFinished == true ) {

            g2.setFont(arial_40);
            g2.setColor(Color.white);

            String text;
            int textLenght;
            int x;
            int y;

            text = "To Be Continued ! ";
            textLenght = (int)g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            x = gp.screenWidth/2 - textLenght/2;
            y = gp.screenHeight/2 - (gp.tileSize*3);
            g2.drawString(text, x, y);

            g2.setFont(arial_80B);
            g2.setColor(Color.blue);
            text = "Congratulations!";
            textLenght = (int)g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            x = gp.screenWidth/2 - textLenght/2;
            y = gp.screenHeight/2 + (gp.tileSize*2);
            g2.drawString(text, x, y);

            gp.gameThread = null;
        }
        else {

            // AFISEAZA CATE CHEI ARE PLAYER UL
            if(gp.gameState == gp.characterState) {

                g2.setFont(arial_40);
                g2.setColor(Color.white);
                //g2.drawImage(Key, gp.tileSize/2, gp.tileSize/2, gp.tileSize, gp.tileSize, null);
                //g2.drawString("x "+ gp.player.hasKey, 95, 80);
            }
    } 
}
    public void drawPlayerLife() {

        int startX = (int)(20 * gp.screenWidth / 1280f);
        int startY = (int)(20 * gp.screenWidth / 1280f);

        int heartSize = (int)(32 * gp.screenWidth / 1280f);      // base heart size
        int spacing   = (int)(8  * gp.screenWidth / 1280f);      // space between hearts


        int x = startX;
        int y = startY;

    // DRAW EMPTY HEARTS (MAX LIFE)
    for (int i = 0; i < gp.player.maxLife; i++) {
        g2.drawImage(Hearts_Empty, x, y, heartSize, heartSize, null);
        x += heartSize + spacing;
    }

    // RESET X
    x = startX;

    // DRAW FILLED HEARTS (CURRENT LIFE)
    for (int i = 0; i < gp.player.life; i++) {
        g2.drawImage(Hearts_Full, x, y, heartSize, heartSize, null);
        x += heartSize + spacing;
    }

    // DRAW EMPTY CRYSTALS (MAX MANA)
    y += heartSize + spacing;
    x = startX;

    // =========================
    // DRAW EMPTY CRYSTALS (MAX MANA)
    // =========================
    for (int i = 0; i < gp.player.maxMana; i++) {
        g2.drawImage(Crystal_Empty, x, y, heartSize, heartSize, null);
        x += heartSize + spacing;
    }

    // RESET X
    x = startX;

    // =========================
    // DRAW FILLED CRYSTALS (CURRENT MANA)
    // =========================
    for (int i = 0; i < gp.player.mana; i++) {
        g2.drawImage(Crystal_Full, x, y, heartSize, heartSize, null);
        x += heartSize + spacing;
    }

    // SMALL HUD inventory indicator (current count only)
    String invCount = String.valueOf(gp.player.inventory.size());
    int rectW = (int)(110 * gp.screenWidth / 1280f);
    int rectH = heartSize;
    int rx = gp.screenWidth - 20 - rectW;
    int ry = startY;
    // background pill
    g2.setColor(new Color(0, 0, 0, 150));
    g2.fillRoundRect(rx, ry, rectW, rectH, 12, 12);
    // border
    g2.setColor(new Color(255, 255, 255, 200));
    g2.setStroke(new BasicStroke(2));
    g2.drawRoundRect(rx, ry, rectW, rectH, 12, 12);
    // label and number
    g2.setFont(g2.getFont().deriveFont(14F));
    g2.setColor(new Color(200, 200, 200));
    g2.drawString("Inv", rx + 8, ry + rectH/2 + 6);
    g2.setFont(g2.getFont().deriveFont(16F));
    int numTail = rx + rectW - 10;
    int numX = getXforAlignToRightText(invCount, numTail);
    g2.setColor(Color.white);
    g2.drawString(invCount, numX, ry + rectH/2 + 6);
}
    public void drawMessage() {

    int messageX = gp.tileSize;
    int messageY = gp.tileSize * 4;
    g2.setFont(g2.getFont().deriveFont(Font.BOLD, 32F));

    for (int i = 0; i < message.size(); i++) {

        if (message.get(i) != null) {

            int counter = messageCounter.get(i) + 1;
            messageCounter.set(i, counter);

            // Calculate fade (alpha from 255 -> 0 over last 60 frames) Animatie de fade out a textului
            int alpha = 255;
            if (counter > 120) { // fade starts after 120 frames (2 seconds)
                alpha = 255 - (int)((counter - 120) * (255.0 / 60));
                if (alpha < 0) alpha = 0;
            }

            // Umbra textului
            g2.setColor(new Color(0, 0, 0, alpha));
            g2.drawString(message.get(i), messageX + 2, messageY + 2);

            // Text in its color with alpha
            Color baseColor = messageColor.get(i);
            g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
            g2.drawString(message.get(i), messageX, messageY);

            messageY += 50;

            // Remove after 180 frames (3 seconds)
            if (counter > 180) {
                message.remove(i);
                messageCounter.remove(i);
                messageColor.remove(i);
                i--; // adjust index after removal
            }
        }
    }
}
    public void drawTitleScreen() {

        // DRAW BACKGROUND IMAGE
        if (titleBackground != null) {
            // Set opacity to 70% to reduce brightness
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            g2.drawImage(titleBackground, 0, 0, null);
            // Reset to full opacity for text
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        } else {
            // Fallback to black background
            g2.setColor(new Color(0, 0, 0));
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }

        if(titleScreenState == 0) {
            
            //TITLE NAME
            g2.setFont(g2.getFont().deriveFont(Font.BOLD,96F));
            String text = "Michiduta Adventure";
            int x = getXforCenteredText(text);
            int y = gp.tileSize*3;

            //SHADOW
            g2.setColor(new Color(50, 50, 50)); // Darker shadow
            g2.drawString(text, x+5, y+5);
            // MAIN COLOR - GOLDEN FANTASY THEME
            g2.setColor(new Color(255, 215, 0)); // Gold
            g2.drawString(text, x, y);

            // MICHIDUTA IMAGE
            x = gp.screenWidth/2 - (gp.tileSize*2)/2;
            y += gp.tileSize*2;
            g2.drawImage(gp.player.down1, x, y, gp.tileSize*2, gp.tileSize*2, null);

            // VINTAGE MENU BUTTONS
            g2.setFont(g2.getFont().deriveFont(Font.BOLD,48F));
            String[] menuItems = {"NEW GAME", "LOAD GAME", "QUIT"};
            int buttonWidth = gp.tileSize * 6;
            int buttonHeight = gp.tileSize;
            int buttonX = gp.screenWidth/2 - buttonWidth/2;
            // This offset is to position the round rect relative to the text's y-baseline
            int buttonRectYoffset = -gp.tileSize + 20; 

            y += gp.tileSize*3.5;

            for (int i = 0; i < menuItems.length; i++) {
                text = menuItems[i];
                x = getXforCenteredText(text);
                
                int currentButtonRectY = (int)y + buttonRectYoffset;

                if (commandNum == i) {
                    // Selected button style
                    g2.setColor(new Color(255, 255, 255, 70)); // Brighter semi-transparent white
                    g2.fillRoundRect(buttonX, currentButtonRectY, buttonWidth, buttonHeight, 25, 25);
                    
                    g2.setColor(new Color(255, 215, 0)); // Gold border
                    g2.setStroke(new BasicStroke(3));
                    g2.drawRoundRect(buttonX, currentButtonRectY, buttonWidth, buttonHeight, 25, 25);
                    
                    g2.setColor(new Color(255, 215, 0)); // Gold text
                } else {
                    // Unselected button style
                    g2.setColor(new Color(0, 0, 0, 70)); // Darker semi-transparent black
                    g2.fillRoundRect(buttonX, currentButtonRectY, buttonWidth, buttonHeight, 25, 25);
                    
                    g2.setColor(new Color(255, 223, 128)); // Soft gold text
                }
                
                g2.setStroke(new BasicStroke(1)); // Reset stroke for other drawings
                g2.drawString(text, x, (int)y);
                
                if (i < menuItems.length - 1) {
                    y += gp.tileSize; // Move to next button position
                }
            }

            // INFO MENU
            g2.setFont(g2.getFont().deriveFont(Font.BOLD,28F));

            text = "PRESS i FOR INFO";
            x = gp.tileSize / 2;
            y += gp.tileSize;
            g2.setColor(new Color(192, 192, 192)); // Silver
            g2.drawString(text, x, y);
        }
        else if ( titleScreenState == 1) {

            // CLASS SELECTION SCREEN
            g2.setColor(Color.white);
            g2.setFont(g2.getFont().deriveFont(42f));

            String text = "Select your class!";
            int x = getXforCenteredText(text);
            int y = gp.tileSize*3;
            g2.drawString(text, x, y);

            text = "Fighter";
            x = getXforCenteredText(text);
            y += gp.tileSize*3;
            g2.drawString(text, x, y);
            if (commandNum == 0) {
                g2.drawString(">", x-gp.tileSize, y);
            }

            text = "Ronin";
            x = getXforCenteredText(text);
            y += gp.tileSize;
            g2.drawString(text, x, y);
            if (commandNum == 1) {
                g2.drawString(">", x-gp.tileSize, y);
            }

            text = "Magician";
            x = getXforCenteredText(text);
            y += gp.tileSize;
            g2.drawString(text, x, y);
            if (commandNum == 2) {
                g2.drawString(">", x-gp.tileSize, y);
            }

            text = "Back";
            x = getXforCenteredText(text);
            y += gp.tileSize*2;
            g2.drawString(text, x, y);
            if (commandNum == 3) {
                g2.drawString(">", x-gp.tileSize, y);
            }
        }
        /*else if ( titleScreenState == 2 ) {

            // UPDATE LOG SCREEN 
            g2.setColor(Color.white);
            g2.setFont(g2.getFont().deriveFont(42f));

            String text = "Update log 0.1 [ALPHA]";
            int x = getXforCenteredText(text);
            int y = gp.tileSize*3;
            g2.drawString(text, x, y);


            g2.setFont(g2.getFont().deriveFont(28f));
            text = "- New Map";
            x = getXforCenteredText(text) - 3 * gp.tileSize;
            y = gp.tileSize * 5;
            g2.drawString(text, x, y);

            text = "- New Tiles";
            x = getXforCenteredText(text) - (int)(2.95 * gp.tileSize);
            y += 1.5 * gp.tileSize;
            g2.drawString(text, x, y);

            text = "- New Ability ( TELEPORT )";
            x = getXforCenteredText(text) - (int)(1.4 * gp.tileSize);
            y += 1.5 * gp.tileSize;
            g2.drawString(text, x, y);

            text = "Back";
            x = getXforCenteredText(text);
            y += gp.tileSize*2;
            g2.drawString(text, x, y);
            if (commandNum == 0) {
                g2.drawString(">", x-gp.tileSize / 2, y);
            }
        }*/
        else if ( titleScreenState == 2 ) {
            // UPDATE LOG SCREEN #2
            g2.setColor(Color.white);
            g2.setFont(g2.getFont().deriveFont(42f));

            String text = "Update log 0.2 [ALPHA]";
            int x = getXforCenteredText(text);
            int y = gp.tileSize*3;
            g2.drawString(text, x, y);


            g2.setFont(g2.getFont().deriveFont(28f));
            text = "- New Map";
            x = getXforCenteredText(text) - 3 * gp.tileSize;
            y = gp.tileSize * 5;
            g2.drawString(text, x, y);

            text = "- New Mobes";
            x = getXforCenteredText(text) - (int)(2.95 * gp.tileSize);
            y += 1.5 * gp.tileSize;
            g2.drawString(text, x, y);

            text = "- New Attack Mechanics";
            x = getXforCenteredText(text) - (int)(1.95 * gp.tileSize);
            y += 1.5 * gp.tileSize;
            g2.drawString(text, x, y);

            text = "- Leveling up Mechanics";
            x = getXforCenteredText(text) - (int)(1.95 * gp.tileSize);
            y += 1.5 * gp.tileSize;
            g2.drawString(text, x, y);

            text = "Back";
            x = getXforCenteredText(text);
            y += gp.tileSize*2;
            g2.drawString(text, x, y);
            if (commandNum == 0) {
                g2.drawString(">", x-gp.tileSize / 2, y);
            }
        }
    }
    public void drawPauseScreen() {

        /*g2.setFont(g2.getFont().deriveFont(Font.PLAIN,80F));
        String text = "PAUSED";
        int x = getXforCenteredText(text);
        int y = gp.screenHeight/2;

        g2.drawString(text, x, y);*/
    }
    public void drawDialogueScreen() {

        //WINDOW
        int x = gp.tileSize * 2;
        int y = gp.tileSize / 2;
        int width = gp.screenWidth - (gp.tileSize * 4);
        int height = gp.tileSize * 5;

        drawSubWindow(x, y, width, height);

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 28F));
        x += gp.tileSize;
        y += gp.tileSize;

        if ( npc.dialogues[npc.dialogueSet][npc.dialogueIndex] != null ) {

            currentDialogue = npc.dialogues[npc.dialogueSet][npc.dialogueIndex];

            char characters[] = npc.dialogues[npc.dialogueSet][npc.dialogueIndex].toCharArray();

            if ( charIndex < characters.length ) {

                String s = String.valueOf(characters[charIndex]);
                combinedText = combinedText + s;
                currentDialogue = combinedText;
                charIndex++;
            }

            if ( gp.keyH.enterPressed == true ) {

                charIndex = 0;
                combinedText = "";

                if ( gp.gameState == gp.dialogueState || gp.gameState == gp.cutsceneState ) {

                        npc.dialogueIndex++;
                        gp.keyH.enterPressed = false;               
                }
            }
        }
        else { // IF NO TEXT IS IN THE ARRAY
            npc.dialogueIndex = 0;

            if ( gp.gameState == gp.dialogueState ) {
                gp.gameState = gp.playState;
            }
            if ( gp.gameState == gp.cutsceneState ) {
                gp.csManager.scenePhase++;
            }
        }


        
        for ( String line : currentDialogue.split("\n")) {
            g2.drawString(line, x, y);
            y += 40;
        }
    }
    public void drawGameOverScreen() {

        g2.setColor(new Color (0, 0, 0, 150));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        int x;
        int y;
        String text;
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 110f));

        text = "Game Over";

        // SHADOW  
        g2.setColor(Color.black);
        x = getXforCenteredText(text);
        y = gp.tileSize * 4;
        g2.drawString(text, x, y);

        // MAIN
        g2.setColor(Color.white);
        g2.drawString(text, x - 4, y - 4);

        // RETRY
        g2.setFont(g2.getFont().deriveFont(50f));
        text = "Retry";
        x = getXforCenteredText(text);
        y += gp.tileSize * 4;
        g2.drawString(text, x, y);
        if ( commandNum == 0 ) {
            g2.drawString(">", x - 40, y);
        }

        // BACK TO THE TITLE SCREEN
        text = "Quit";
        x = getXforCenteredText(text);
        y += 55;
        g2.drawString(text, x, y);
        if ( commandNum == 1 ) {
            g2.drawString(">", x - 40, y);
        }

    }
    public void drawOptionsScreen() {

        g2.setColor(Color.white);
        g2.setFont(g2.getFont().deriveFont(32F));

        // SUB WINDOW
        int frameX = gp.tileSize * 6;
        int frameY = gp.tileSize;
        int frameWidth = gp.tileSize * 8;
        int frameHeight = gp.tileSize * 10;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight);

        switch (subState) {
            case 0: options_top ( frameX, frameY ); break;
            case 1: options_fullScreenNotification ( frameX, frameY ); break;
            case 2: options_control ( frameX, frameY ); break;
            case 3: options_endGameConfirmation ( frameX, frameY ); break;
        }

        gp.keyH.enterPressed = false;
    }
    public void drawCharacterScreen() {

        // CREATE A FRAME
        final int frameX = gp.tileSize * 2;
        final int frameY = gp.tileSize / 2;
        final int frameWidth = gp.tileSize * 5;
        final int frameHeight = gp.tileSize * 11;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight);

        // TEXT
        g2.setColor(Color.white);
        g2.setFont(g2.getFont().deriveFont(32F));

        int textX = frameX + 20;
        int textY = frameY + gp.tileSize;
        final int lineHeight = 50;

        //  NAME FOR EACH STATS

        g2.drawString("Level", textX, textY);
        textY += lineHeight;
        g2.drawString("Life", textX, textY);
        textY += lineHeight;
        g2.drawString("Mana", textX, textY);
        textY += lineHeight;
        g2.drawString("Strength", textX, textY);
        textY += lineHeight;
        g2.drawString("Dexterity", textX, textY);
        textY += lineHeight;
        g2.drawString("Attack", textX, textY);
        textY += lineHeight;
        g2.drawString("Defense", textX, textY);
        textY += lineHeight;
        g2.drawString("Exp", textX, textY);
        textY += lineHeight;
        g2.drawString("Next Level", textX, textY);
        textY += lineHeight;
        g2.drawString("Coin", textX, textY);
        textY += lineHeight + 20;
        g2.drawString("Weapon", textX, textY);
        textY += lineHeight + 20;
        g2.drawString("Shield", textX, textY);
        textY += lineHeight;

        // AFISAREA VALORILOR PENTRU FIECARE STAT

        int tailX = ( frameX + frameWidth ) - 30;

        // RESET TEXT Y

        textY = frameY + gp.tileSize;
        String value;

        value = String.valueOf(gp.player.level);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.life + "/" + gp.player.maxLife);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.mana + "/" + gp.player.maxMana);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.strenght);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.dexterity);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.attack);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.defense);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.exp);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.nextLevelExp);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        value = String.valueOf(gp.player.coin);
        textX = getXforAlignToRightText(value, tailX);
        g2.drawString(value, textX, textY);
        textY += lineHeight;

        g2.drawImage(gp.player.currentWeapon.down1, tailX - gp.tileSize, textY - 22, null);
        textY += gp.tileSize;
        g2.drawImage(gp.player.currentShield.down1, tailX - gp.tileSize, textY - 22, null);

    }
    public void drawInventory() {

        int frameX = gp.tileSize * 12;
        int frameY = gp.tileSize;
        int frameWidth = gp.tileSize * 6;
        int frameHeight = gp.tileSize * 5;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight);

        // Title and size (size shown only in inventory screen)
        g2.setColor(Color.white);
            // simple animation counter for subtle UI motions
            counter++;
            float pulse = (float)((Math.sin(counter * 0.06) + 1.0) * 0.5); // 0..1

            // Title (moved above frame) with shadow and pulsing gold tint
            String invTitle = "Inventory";
            int invTitleY = frameY - gp.tileSize/2;
            g2.setFont(g2.getFont().deriveFont(28F));
            // shadow
            g2.setColor(new Color(0,0,0,160));
            int tlen = (int)g2.getFontMetrics().getStringBounds(invTitle, g2).getWidth();
            g2.drawString(invTitle, frameX + (frameWidth/2) - tlen/2 + 3, invTitleY + 3);
            // pulsing gold
            int r = (int)(218 + 37 * pulse);
            int gcol = (int)(165 + 20 * pulse);
            int b = (int)(32 + 8 * pulse);
            g2.setColor(new Color(Math.min(255,r), Math.min(255,gcol), Math.min(255,b)));
            g2.drawString(invTitle, frameX + (frameWidth/2) - tlen/2, invTitleY);

            // Occupied size moved under the title (left side)
            String invText = "Occupied: " + gp.player.inventory.size() + " / " + gp.player.maxInventorySize;
            g2.setFont(g2.getFont().deriveFont(16F));
            g2.setColor(new Color(200,200,200,220));
            g2.drawString(invText, frameX + 18, invTitleY + 26);

            // header strip inside the window for visual grouping
            Color headerBg = new Color(30,30,30,120);
            g2.setColor(headerBg);
            g2.fillRoundRect(frameX + 8, frameY + 10, frameWidth - 16, gp.tileSize - 6, 12, 12);
        final int slotXstart = frameX + 30;
        final int slotYstart = frameY + 30;
        int slotSize = gp.tileSize + 3;
        int maxCol = 5;
        int maxRow = 4;

        // DRAW EMPTY SLOTS
        g2.setColor(new Color(50, 50, 50, 150));
        for (int row = 0; row < maxRow; row++) {
            for (int col = 0; col < maxCol; col++) {
                int x = slotXstart + col * slotSize;
                int y = slotYstart + row * slotSize;
                g2.fillRoundRect(x, y, gp.tileSize, gp.tileSize, 10, 10);
            }
        }

        // DRAW PLAYER'S ITEMS
        for (int i = 0; i < gp.player.inventory.size(); i++) {
            int row = i / maxCol;
            int col = i % maxCol;
            int slotX = slotXstart + col * slotSize;
            int slotY = slotYstart + row * slotSize;

            // EQUIPPED ITEM HIGHLIGHT
            if (gp.player.inventory.get(i) == gp.player.currentShield ||
                    gp.player.inventory.get(i) == gp.player.currentWeapon) {
                g2.setColor(new Color(240, 190, 90));
                g2.fillRoundRect(slotX, slotY, gp.tileSize, gp.tileSize, 10, 10);
            }

            g2.drawImage(gp.player.inventory.get(i).down1, slotX, slotY, null);

            // STACKABLE ITEM AMOUNT
            if (gp.player.inventory.get(i).amount > 1) {
                g2.setFont(g2.getFont().deriveFont(32f));
                String s = "" + gp.player.inventory.get(i).amount;
                int amountX = getXforAlignToRightText(s, slotX + 70);
                int amountY = slotY + gp.tileSize;

                // SHADOW
                g2.setColor(new Color(60, 60, 60));
                g2.drawString(s, amountX, amountY);
                // TEXT
                g2.setColor(Color.white);
                g2.drawString(s, amountX - 3, amountY - 3);
            }
        }

        // CURSOR
        int cursorX = slotXstart + (slotSize * slotCol);
        int cursorY = slotYstart + (slotSize * slotRow);
        int cursorWidth = gp.tileSize;
        int cursorHeight = gp.tileSize;
        // translucent fill for selected slot
        g2.setColor(new Color(255, 255, 255, 40));
        g2.fillRoundRect(cursorX, cursorY, cursorWidth, cursorHeight, 10, 10);
        // pulsing border for selected slot
        float strokeWidth = 2f + 2f * (float)((Math.sin(counter * 0.12) + 1.0) * 0.5f);
        g2.setColor(Color.white);
        g2.setStroke(new BasicStroke(strokeWidth));
        g2.drawRoundRect(cursorX, cursorY, cursorWidth, cursorHeight, 10, 10);

        // HINT STRIP (between inventory frame and description window)
        int itemIndex = getItemIndexOnSlot();
        String actionHint = "";
        if (itemIndex < gp.player.inventory.size()) {
            Entity itemForHint = gp.player.inventory.get(itemIndex);
            if (itemForHint != null) {
                if (itemForHint.type == gp.player.type_consumable) actionHint = "Use (ENTER)";
                else if (itemForHint.type == gp.player.type_sword || itemForHint.type == gp.player.type_shield || itemForHint.type == gp.player.type_book) actionHint = "Equip (ENTER)";
            }
        }

        int hintAreaY = frameY + frameHeight; // directly below inventory frame
        int hintAreaH = gp.tileSize - 8;
        // background strip for hints
        g2.setColor(new Color(20,20,20,140));
        g2.fillRoundRect(frameX + 8, hintAreaY + 6, frameWidth - 16, hintAreaH, 12, 12);
        // hints text
        g2.setFont(g2.getFont().deriveFont(18F));
        g2.setColor(new Color(200,200,200));
        int hintTextY = hintAreaY + 6 + hintAreaH/2 + 6;
        g2.drawString("Drop (BACKSPACE)", frameX + 20, hintTextY);
        if (!actionHint.isEmpty()) {
            int tailX = frameX + frameWidth - 20;
            int ax = getXforAlignToRightText(actionHint, tailX);
            g2.drawString(actionHint, ax, hintTextY);
        }

        // DESCRIPTION FRAME (kept clear for description text)
        int dFrameX = frameX;
        int dFrameY = frameY + frameHeight + hintAreaH + 12; // shift down to make room for hint strip
        int dFrameWidth = frameWidth;
        int dFrameHeight = gp.tileSize * 3;

        // DRAW DESCRIPTION TEXT
        int textX = dFrameX + 30;
        int textY = dFrameY + gp.tileSize;
        g2.setFont(g2.getFont().deriveFont(28F));

        if (itemIndex < gp.player.inventory.size()) {
            Entity item = gp.player.inventory.get(itemIndex);
            if (item != null && (item.type == gp.player.type_consumable || item == gp.player.currentShield || item == gp.player.currentWeapon || item.type == gp.player.type_buffs || item.type == gp.player.type_book)) {
                drawSubWindow(dFrameX, dFrameY, dFrameWidth, dFrameHeight);
                // draw item icon and name at top of description
                int iconX = dFrameX + 20;
                int iconY = dFrameY + 20;
                g2.drawImage(item.down1, iconX, iconY, gp.tileSize, gp.tileSize, null);
                g2.setFont(g2.getFont().deriveFont(32F));
                g2.setColor(Color.white);
                g2.drawString(item.name, iconX + gp.tileSize + 10, iconY + gp.tileSize / 2 + 10);

                textY = iconY + gp.tileSize + 20;
                g2.setFont(g2.getFont().deriveFont(28F));
                for (String line : item.description.split("\n")) {
                    g2.drawString(line, textX, textY);
                    textY += 32;
                }
            }
        }
    }
    public int getItemIndexOnSlot() {
        int itemIndex = slotCol + ( slotRow * 5 );
        return itemIndex;
    }
    // ── CACHED COLORS / STROKES for options screen (avoid per-frame allocation) ──
    private static final Color OPT_BG_DARK    = new Color(15, 10, 8, 230);
    private static final Color OPT_BORDER     = new Color(180, 140, 60);
    private static final Color OPT_BORDER_IN  = new Color(100, 75, 30, 160);
    private static final Color OPT_GOLD       = new Color(218, 175, 62);
    private static final Color OPT_GOLD_DIM   = new Color(160, 130, 50);
    private static final Color OPT_TEXT       = new Color(220, 210, 190);
    private static final Color OPT_TEXT_DIM   = new Color(140, 130, 110);
    private static final Color OPT_SEL_BG     = new Color(180, 140, 60, 35);
    private static final Color OPT_SEL_BORDER = new Color(218, 175, 62, 100);
    private static final Color OPT_CHECK_BG   = new Color(30, 25, 18);
    private static final Color OPT_CHECK_ON   = new Color(200, 160, 50);
    private static final Color OPT_BAR_BG     = new Color(30, 25, 18);
    private static final Color OPT_BAR_FILL   = new Color(180, 140, 50);
    private static final Color OPT_BAR_GLOW   = new Color(220, 190, 80, 120);
    private static final Color OPT_SEPARATOR  = new Color(100, 75, 30, 80);
    private static final Color OPT_BACK_TEXT  = new Color(170, 140, 80);
    private static final BasicStroke OPT_STROKE_BORDER = new BasicStroke(3);
    private static final BasicStroke OPT_STROKE_THIN   = new BasicStroke(1);
    private static final BasicStroke OPT_STROKE_SEL    = new BasicStroke(2);

    public void options_top( int frameX, int frameY ) {

        int fw = gp.tileSize * 8;
        int fh = gp.tileSize * 10;
        int pad = 20;                   // inner padding
        int lineH = 52;                 // row height for menu items
        int rightCol = frameX + fw - pad - 155; // right column for controls/sliders

        // ── TITLE ──
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 36F));
        String title = "Settings";
        int titleW = (int) g2.getFontMetrics().getStringBounds(title, g2).getWidth();
        int titleX = frameX + fw / 2 - titleW / 2;
        int titleY = frameY + 48;
        // shadow
        g2.setColor(new Color(0, 0, 0, 150));
        g2.drawString(title, titleX + 2, titleY + 2);
        // gold text
        g2.setColor(OPT_GOLD);
        g2.drawString(title, titleX, titleY);
        // decorative line under title
        int lineYDeco = titleY + 10;
        g2.setColor(OPT_SEPARATOR);
        g2.fillRect(frameX + pad + 20, lineYDeco, fw - pad * 2 - 40, 2);

        // ── MENU ITEMS ──
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 26F));
        int startY = titleY + 42;       // first item Y baseline
        int textX = frameX + pad + 15;

        String[] labels = { "Full Screen", "V-Sync", "Music", "Sound FX", "Controls", "End Game", "Save Game", "Back" };
        int totalItems = labels.length;  // 8 items, indices 0-7

        for (int i = 0; i < totalItems; i++) {
            int itemY = startY + i * lineH;
            boolean selected = (commandNum == i);
            boolean isBack = (i == 7);

            // draw separator before "Back"
            if (isBack) {
                int sepY = itemY - lineH / 2 + 4;
                g2.setColor(OPT_SEPARATOR);
                g2.fillRect(frameX + pad + 10, sepY, fw - pad * 2 - 20, 1);
            }

            // selection highlight bar
            if (selected) {
                int barX = frameX + pad + 4;
                int barY = itemY - lineH + 16;
                int barW = fw - pad * 2 - 8;
                int barH = lineH - 4;
                g2.setColor(OPT_SEL_BG);
                g2.fillRoundRect(barX, barY, barW, barH, 10, 10);
                g2.setColor(OPT_SEL_BORDER);
                g2.setStroke(OPT_STROKE_SEL);
                g2.drawRoundRect(barX, barY, barW, barH, 10, 10);
            }

            // label
            if (isBack) {
                // center "Back" and use dimmer gold
                g2.setColor(selected ? OPT_GOLD : OPT_BACK_TEXT);
                int bw = (int) g2.getFontMetrics().getStringBounds(labels[i], g2).getWidth();
                g2.drawString(labels[i], frameX + fw / 2 - bw / 2, itemY);
            } else {
                g2.setColor(selected ? OPT_GOLD : OPT_TEXT);
                g2.drawString(labels[i], textX, itemY);
            }

            // ── RIGHT-SIDE CONTROLS ──
            int ctrlY = itemY - 17; // vertical center for controls

            if (i == 0) { // FullScreen toggle
                drawMedievalToggle(rightCol + 100, ctrlY, gp.fullScreenOn);
                if (selected && gp.keyH.enterPressed) {
                    gp.applyFullScreenSetting(!gp.fullScreenOn);
                    gp.playSE(3);
                    gp.keyH.enterPressed = false;
                }
            }
            else if (i == 1) { // V-Sync toggle
                drawMedievalToggle(rightCol + 100, ctrlY, gp.vSyncOn);
                if (selected && gp.keyH.enterPressed) {
                    gp.setVSync(!gp.vSyncOn);
                    gp.keyH.enterPressed = false;
                }
            }
            else if (i == 2) { // Music volume bar
                drawMedievalSlider(rightCol, ctrlY, gp.music.volumeScale, 5);
            }
            else if (i == 3) { // SE volume bar
                drawMedievalSlider(rightCol, ctrlY, gp.se.volumeScale, 5);
            }
            else if (i == 4) { // Controls
                if (selected) drawArrowHint(rightCol + 120, itemY);
                if (selected && gp.keyH.enterPressed) { subState = 2; commandNum = 0; }
            }
            else if (i == 5) { // End Game
                if (selected && gp.keyH.enterPressed) { subState = 3; commandNum = 0; }
            }
            else if (i == 6) { // Save Game
                if (selected && gp.keyH.enterPressed) {
                    gp.saveLoad.save();
                    addMessage("Game saved.", Color.WHITE);
                    gp.playSE(3);
                    gp.keyH.enterPressed = false;
                }
            }
            else if (i == 7) { // Back
                if (selected && gp.keyH.enterPressed) { gp.gameState = gp.playState; commandNum = 0; }
            }
        }

        gp.config.saveConfig();
    }

    /** Draw a medieval-style on/off toggle (small ornate checkbox). */
    private void drawMedievalToggle(int x, int y, boolean on) {
        int size = 22;
        // outer box
        g2.setColor(OPT_CHECK_BG);
        g2.fillRoundRect(x, y, size, size, 5, 5);
        g2.setColor(OPT_BORDER_IN);
        g2.setStroke(OPT_STROKE_THIN);
        g2.drawRoundRect(x, y, size, size, 5, 5);
        if (on) {
            // filled golden square
            g2.setColor(OPT_CHECK_ON);
            g2.fillRoundRect(x + 3, y + 3, size - 6, size - 6, 3, 3);
            // bright center pip
            g2.setColor(OPT_GOLD);
            g2.fillRect(x + 7, y + 7, size - 14, size - 14);
        }
    }

    /** Draw a medieval-style volume slider bar. */
    private void drawMedievalSlider(int x, int y, int value, int max) {
        int barW = 150;
        int barH = 18;
        int cy = y + 2;
        // background track
        g2.setColor(OPT_BAR_BG);
        g2.fillRoundRect(x, cy, barW, barH, 6, 6);
        g2.setColor(OPT_BORDER_IN);
        g2.setStroke(OPT_STROKE_THIN);
        g2.drawRoundRect(x, cy, barW, barH, 6, 6);
        // filled portion
        int fillW = (int) ((barW - 4) * ((float) value / max));
        if (fillW > 0) {
            g2.setColor(OPT_BAR_FILL);
            g2.fillRoundRect(x + 2, cy + 2, fillW, barH - 4, 4, 4);
            // subtle highlight on top half
            g2.setColor(OPT_BAR_GLOW);
            g2.fillRoundRect(x + 2, cy + 2, fillW, (barH - 4) / 2, 4, 4);
        }
        // notch marks
        g2.setColor(OPT_SEPARATOR);
        for (int i = 1; i < max; i++) {
            int nx = x + (barW * i / max);
            g2.drawLine(nx, cy + 2, nx, cy + barH - 2);
        }
    }

    /** Small " > " arrow hint for sub-menu items. */
    private void drawArrowHint(int x, int y) {
        g2.setColor(OPT_GOLD_DIM);
        g2.setFont(g2.getFont().deriveFont(20F));
        g2.drawString("\u25B6", x, y);  // ▶ unicode arrow
        g2.setFont(g2.getFont().deriveFont(26F));  // restore
    }
    public void options_fullScreenNotification ( int frameX, int frameY ) {

        int fw = gp.tileSize * 8;
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 30F));
        g2.setColor(OPT_GOLD);
        String noteTitle = "Notice";
        int ntw = (int) g2.getFontMetrics().getStringBounds(noteTitle, g2).getWidth();
        g2.drawString(noteTitle, frameX + fw / 2 - ntw / 2, frameY + 50);

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 24F));
        g2.setColor(OPT_TEXT);
        int textX = frameX + 30;
        int textY = frameY + gp.tileSize * 3;

        currentDialogue = "The change will take effect\nafter restarting the game.";

        for ( String line: currentDialogue.split("\n")) {
            g2.drawString(line, textX, textY);
            textY += 36;
        }

        // BACK button centered
        g2.setFont(g2.getFont().deriveFont(26F));
        String back = "Back";
        int bw = (int) g2.getFontMetrics().getStringBounds(back, g2).getWidth();
        int backX = frameX + fw / 2 - bw / 2;
        int backY = frameY + gp.tileSize * 9 - 20;
        boolean sel = (commandNum == 0);
        if (sel) {
            g2.setColor(OPT_SEL_BG);
            g2.fillRoundRect(backX - 20, backY - 28, bw + 40, 36, 10, 10);
            g2.setColor(OPT_SEL_BORDER);
            g2.setStroke(OPT_STROKE_SEL);
            g2.drawRoundRect(backX - 20, backY - 28, bw + 40, 36, 10, 10);
        }
        g2.setColor(sel ? OPT_GOLD : OPT_BACK_TEXT);
        g2.drawString(back, backX, backY);
        if ( sel && gp.keyH.enterPressed ) {
            subState = 0;
        }
    }
    public void options_control ( int frameX, int frameY ) {

        int fw = gp.tileSize * 8;
        int pad = 30;

        // Title
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 34F));
        g2.setColor(OPT_GOLD);
        String ctrlTitle = "Controls";
        int ctw = (int) g2.getFontMetrics().getStringBounds(ctrlTitle, g2).getWidth();
        g2.drawString(ctrlTitle, frameX + fw / 2 - ctw / 2, frameY + 48);
        // decorative line
        g2.setColor(OPT_SEPARATOR);
        g2.fillRect(frameX + pad, frameY + 58, fw - pad * 2, 2);

        // Key bindings table
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 24F));
        String[] actions = { "Move", "Confirm", "Pause", "Options" };
        String[] keys    = { "W A S D", "ENTER", "P", "ESC" };
        int textX = frameX + pad;
        int keyX  = frameX + fw - pad;
        int textY = frameY + 100;
        int rowH  = 50;

        for (int i = 0; i < actions.length; i++) {
            int ry = textY + i * rowH;
            // zebra stripe
            if (i % 2 == 0) {
                g2.setColor(new Color(40, 35, 25, 60));
                g2.fillRoundRect(frameX + 12, ry - 26, fw - 24, rowH - 4, 8, 8);
            }
            // action label
            g2.setColor(OPT_TEXT);
            g2.drawString(actions[i], textX + 5, ry);
            // key label right-aligned in gold
            g2.setColor(OPT_GOLD_DIM);
            int kw = (int) g2.getFontMetrics().getStringBounds(keys[i], g2).getWidth();
            g2.drawString(keys[i], keyX - kw - 5, ry);
        }

        // BACK button centered
        g2.setFont(g2.getFont().deriveFont(26F));
        String back = "Back";
        int bw = (int) g2.getFontMetrics().getStringBounds(back, g2).getWidth();
        int backX = frameX + fw / 2 - bw / 2;
        int backY = frameY + gp.tileSize * 9 - 20;
        boolean sel = (commandNum == 0);
        if (sel) {
            g2.setColor(OPT_SEL_BG);
            g2.fillRoundRect(backX - 20, backY - 28, bw + 40, 36, 10, 10);
            g2.setColor(OPT_SEL_BORDER);
            g2.setStroke(OPT_STROKE_SEL);
            g2.drawRoundRect(backX - 20, backY - 28, bw + 40, 36, 10, 10);
        }
        g2.setColor(sel ? OPT_GOLD : OPT_BACK_TEXT);
        g2.drawString(back, backX, backY);
        if ( sel && gp.keyH.enterPressed ) {
            subState = 0;
            commandNum = 4;
        }
    }
    public void options_endGameConfirmation( int frameX, int frameY  ) {

        int fw = gp.tileSize * 8;

        // Warning title
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 30F));
        g2.setColor(new Color(200, 80, 60));
        String warn = "Quit Game?";
        int ww = (int) g2.getFontMetrics().getStringBounds(warn, g2).getWidth();
        g2.drawString(warn, frameX + fw / 2 - ww / 2, frameY + 50);

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 24F));
        g2.setColor(OPT_TEXT);
        int textX = frameX + 30;
        int textY = frameY + gp.tileSize * 3;

        currentDialogue = "Quit the game and return\nto the title screen?";

        for ( String line: currentDialogue.split("\n")) {
            g2.drawString(line, textX, textY);
            textY += 36;
        }

        // YES / NO buttons
        g2.setFont(g2.getFont().deriveFont(26F));
        String[] opts = { "Yes", "No" };
        int btnY = frameY + gp.tileSize * 6;
        for (int i = 0; i < 2; i++) {
            int bw = (int) g2.getFontMetrics().getStringBounds(opts[i], g2).getWidth();
            int bx = frameX + fw / 2 - bw / 2;
            int by = btnY + i * 52;
            boolean sel = (commandNum == i);
            if (sel) {
                g2.setColor(OPT_SEL_BG);
                g2.fillRoundRect(bx - 30, by - 28, bw + 60, 36, 10, 10);
                g2.setColor(OPT_SEL_BORDER);
                g2.setStroke(OPT_STROKE_SEL);
                g2.drawRoundRect(bx - 30, by - 28, bw + 60, 36, 10, 10);
            }
            g2.setColor(sel ? OPT_GOLD : OPT_TEXT_DIM);
            g2.drawString(opts[i], bx, by);
        }

        if ( commandNum == 0 && gp.keyH.enterPressed ) {
            subState = 0;
            titleScreenState = 0;
            gp.stopMusic();
            gp.gameState = gp.titleState;
        }
        if ( commandNum == 1 && gp.keyH.enterPressed ) {
            subState = 0;
            commandNum = 5;
        }
    }
    public void drawTransition(Graphics2D g2) {
        // Phase 1: Fade to black
        if (subState == 0) {
            transitionAlpha += 0.02f; // Slower fade (50 frames)
            if (transitionAlpha >= 1.0f) {
                transitionAlpha = 1.0f;
                // At peak darkness, trigger the map change
                gp.changeMap();
                subState = 1; // Move to fade-out phase
            }
        }
        // Phase 2: Fade from black
        else if (subState == 1) {
            transitionAlpha -= 0.02f;
            if (transitionAlpha <= 0f) {
                transitionAlpha = 0f;
                subState = 0; // Reset for next transition
                gp.gameState = gp.playState; // Return to gameplay
            }
        }

        // Draw the black rectangle with the current alpha
        g2.setColor(new Color(0, 0, 0, transitionAlpha));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
    }
    public void drawSubWindow(int x, int y, int width, int height) {

        // Dark background with leather feel
        g2.setColor(OPT_BG_DARK);
        g2.fillRoundRect(x, y, width, height, 20, 20);

        // Outer gold border
        g2.setColor(OPT_BORDER);
        g2.setStroke(OPT_STROKE_BORDER);
        g2.drawRoundRect(x + 3, y + 3, width - 6, height - 6, 16, 16);

        // Inner subtle border for depth
        g2.setColor(OPT_BORDER_IN);
        g2.setStroke(OPT_STROKE_THIN);
        g2.drawRoundRect(x + 7, y + 7, width - 14, height - 14, 12, 12);
    }
    public int getXforCenteredText(String text) {

        int length = (int)g2.getFontMetrics().getStringBounds(text, g2).getWidth();
        int x = gp.screenWidth/2 - length/2;
        return x;
    }
    public int getXforAlignToRightText(String text, int tailX) {

        int length = (int)g2.getFontMetrics().getStringBounds(text, g2).getWidth();
        int x = tailX - length;
        return x;

    }
}

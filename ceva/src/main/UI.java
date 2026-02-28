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
    String combinedText = "";


    public UI(GamePanel gp) {
        this.gp = gp;

        arial_40 = new Font("Cambria", Font.PLAIN, 40);
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

            // MENU
            g2.setFont(g2.getFont().deriveFont(Font.BOLD,48F));

            text = "NEW GAME";
            x = getXforCenteredText(text);
            y += gp.tileSize*3.5;
            g2.setColor(new Color(255, 223, 128)); // Soft gold
            g2.drawString(text, x, y);
            if(commandNum == 0) {
                g2.setColor(new Color(255, 215, 0)); // Bright gold selector
                g2.drawString(">", x-gp.tileSize, y);
            }

            text = "LOAD GAME";
            x = getXforCenteredText(text);
            y += gp.tileSize;
            g2.setColor(new Color(255, 223, 128)); // Soft gold
            g2.drawString(text, x, y);
            if(commandNum == 1) {
                g2.setColor(new Color(255, 215, 0)); // Bright gold selector
                g2.drawString(">", x-gp.tileSize, y);
            }

            text = "QUIT";
            x = getXforCenteredText(text);
            y += gp.tileSize;
            g2.setColor(new Color(255, 223, 128)); // Soft gold
            g2.drawString(text, x, y);
            if(commandNum == 2) {
                g2.setColor(new Color(255, 215, 0)); // Bright gold selector
                g2.drawString(">", x-gp.tileSize, y);
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
    public void options_top( int frameX, int frameY ) {

        int textX;
        int textY;

        // TITLE   
        String text = "Options";
        textX = getXforCenteredText(text);
        textY = frameY + gp.tileSize;
        g2.drawString(text, textX, textY);

        // FULL SCREEN ON/OFF
        textX = frameX + gp.tileSize;
        textY += gp.tileSize * 2;
        g2.drawString("FullScreen", textX, textY);
        if ( commandNum == 0 ) {
            g2.drawString(">", textX - 25, textY);
            if ( gp.keyH.enterPressed == true ) {
                if ( gp.fullScreenOn == false ) {
                    gp.fullScreenOn = true;
                }
                else if ( gp.fullScreenOn == true ) {
                    gp.fullScreenOn = false;
                }
                subState = 1;
            }
        }

        // MUSIC
        textY += gp.tileSize;
        g2.drawString("Music", textX, textY);
        if ( commandNum == 1 ) {
            g2.drawString(">", textX - 25, textY);
        }

        // SE
        textY += gp.tileSize;
        g2.drawString("SE", textX, textY);
        if ( commandNum == 2 ) {
            g2.drawString(">", textX - 25, textY);
        }

        // CONTROL
        textY += gp.tileSize;
        g2.drawString("Control", textX, textY);
        if ( commandNum == 3 ) {
            g2.drawString(">", textX - 25, textY);
            if ( gp.keyH.enterPressed == true ) {
                subState = 2;
                commandNum = 0;
            }
        }

        // END GAME
        textY += gp.tileSize;
        g2.drawString("End Game", textX, textY);
        if ( commandNum == 4 ) {
            g2.drawString(">", textX - 25, textY);
            if ( gp.keyH.enterPressed == true ) {
                subState = 3;
                commandNum = 0;
            }
        }

        // BACK
        textY += gp.tileSize * 2;
        g2.drawString("Back", textX, textY);
        if ( commandNum == 5 ) {
            g2.drawString(">", textX - 25, textY);
            if ( gp.keyH.enterPressed == true ) {
                gp.gameState = gp.playState;
                commandNum = 0;
            }
        }

        // FULLSCREEN CHECK BOX
        textX = frameX + gp.tileSize * 5;
        textY = frameY + gp.tileSize * 2 + 42;
        g2.setStroke(new BasicStroke(3));
        g2.drawRect(textX, textY, 24, 24);
        if ( gp.fullScreenOn == true ) {
            g2.fillRect(textX, textY, 24, 24);
        }

        // MUSIC VOLUME
        textY += gp.tileSize;
        g2.drawRect(textX, textY, 150, 24); // 150 impartit la 5 casute = 30
        int volumeWidth = 30 * gp.music.volumeScale;
        g2.fillRect(textX, textY, volumeWidth, 24);

        // SE VOLUME
        textY += gp.tileSize;
        g2.drawRect(textX, textY, 150, 24);
        volumeWidth = 30 * gp.se.volumeScale;
        g2.fillRect(textX, textY, volumeWidth, 24);

        gp.config.saveConfig();
    }
    public void options_fullScreenNotification ( int frameX, int frameY ) {

        int textX = frameX + gp.tileSize;
        int textY = frameY + gp.tileSize * 3;

        currentDialogue = "The change will take effect \nafter restarting the game.";

        for ( String line: currentDialogue.split("\n")) {
            g2.drawString(line, textX, textY);
            textY += 40;
        }

        // BACK
        textY = frameY + gp.tileSize * 9;
        g2.drawString("Back", textX, textY);
        if ( commandNum == 0 ) {
            g2.drawString(">", textX-25, textY);
            if ( gp.keyH.enterPressed == true ) {
                subState = 0;
            }
        }
    }
    public void options_control ( int frameX, int frameY ) {
       
        int textX;
        int textY;

        // TITLE
        String text = "Control";
        textX = getXforCenteredText(text);
        textY = frameY + gp.tileSize;
        g2.drawString(text, textX, textY);

        textX = frameX + gp.tileSize;
        textY += gp.tileSize;
        g2.drawString("Move", textX, textY); textY += gp.tileSize;
        g2.drawString("Confirm", textX, textY); textY += gp.tileSize;
        //g2.drawString("Shoot/Cast", textX, textY); textY += gp.tileSize;
        //g2.drawString("Chacarter Screen", textX, textY); textY += gp.tileSize;
        g2.drawString("Pause", textX, textY); textY += gp.tileSize;
        g2.drawString("Options", textX, textY); textY += gp.tileSize;

        textX = frameX + gp.tileSize * 6;
        textY = frameY + gp.tileSize * 2;
        g2.drawString("WASD", textX, textY); textY += gp.tileSize;
        g2.drawString("ENTER", textX, textY); textY += gp.tileSize;
        //g2.drawString("F", textX, textY); textY += gp.tileSize;
        //g2.drawString("C", textX, textY); textY += gp.tileSize;
        g2.drawString("P", textX, textY); textY += gp.tileSize;
        g2.drawString("ESC", textX, textY); textY += gp.tileSize;

        // BACK
        textX = frameX + gp.tileSize;
        textY = frameY + gp.tileSize * 9;
        g2.drawString("Back", textX, textY);
        if ( commandNum == 0 ) {
            g2.drawString(">", textX-25, textY);
            if ( gp.keyH.enterPressed == true ) {
                subState = 0;
                commandNum = 3;
            }
        }
    }
    public void options_endGameConfirmation( int frameX, int frameY  ) {

        int textX = frameX + gp.tileSize;
        int textY = frameY + gp.tileSize * 3;

        currentDialogue = "Quit the game and return \nto the title screen?";

        for ( String line: currentDialogue.split("\n")) {
            g2.drawString(line, textX, textY);
            textY += 40;
        }

        // YES
        String text = "Yes";
        textX = getXforCenteredText(text);
        textY += gp.tileSize * 3;
        g2.drawString(text, textX, textY);
        if ( commandNum == 0 ) {
            g2.drawString(">", textX-25, textY);
            if ( gp.keyH.enterPressed == true ) {
                subState = 0;
                titleScreenState = 0;
                gp.stopMusic();
                gp.gameState = gp.titleState;
            }
        }

        // NO
        text = "No";
        textX = getXforCenteredText(text);
        textY += gp.tileSize;
        g2.drawString(text, textX, textY);
        if ( commandNum == 1 ) {
            g2.drawString(">", textX-25, textY);
            if ( gp.keyH.enterPressed == true ) {
                subState = 0;
                commandNum = 4;
            }
        }
    }
    public void drawSubWindow(int x, int y, int width, int height) {

        Color c = new Color(0, 0, 0, 200);
        g2.setColor(c);
        g2.fillRoundRect(x, y, width, height, 35, 35);

        c = new Color(255, 255, 255);
        g2.setColor(c);
        g2.setStroke(new BasicStroke(5));
        g2.drawRoundRect(x + 5, y + 5, width - 10, height - 10, 25, 25);

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


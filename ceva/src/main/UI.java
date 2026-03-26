package main;

import entity.Entity;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
    ArrayList<String> message = new ArrayList<>();
    ArrayList<Integer> messageCounter = new ArrayList<>();
    ArrayList<Color> messageColor = new ArrayList<>();
    ArrayList<BufferedImage> messageIcon = new ArrayList<>();
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

    // ── ANIMATION STATE ──
    private int animTick = 0;          // global UI animation ticker
    private float smoothLife = -1f;    // for smooth health bar interpolation
    private float smoothMana = -1f;    // for smooth mana bar interpolation
    private float smoothExp  = -1f;    // for smooth XP bar interpolation
    private float gameOverAlpha = 0f;  // fade-in for game over screen
    private float pauseAlpha = 0f;     // fade-in for pause overlay

    // ── HUD COLORS (modern pixel-game palette) ──
    private static final Color HP_BAR_BG      = new Color(50, 12, 18, 220);
    private static final Color HP_BAR_FILL    = new Color(230, 55, 70);
    private static final Color HP_BAR_GLOW    = new Color(255, 120, 100, 140);
    private static final Color MP_BAR_BG      = new Color(12, 16, 55, 220);
    private static final Color MP_BAR_FILL    = new Color(50, 140, 255);
    private static final Color MP_BAR_GLOW    = new Color(110, 190, 255, 150);
    private static final Color XP_BAR_BG      = new Color(12, 30, 12, 220);
    private static final Color XP_BAR_FILL    = new Color(60, 220, 80);
    private static final Color XP_BAR_GLOW    = new Color(130, 255, 140, 140);
    private static final Color COIN_GOLD      = new Color(255, 220, 60);
    private static final Color LVL_BADGE      = new Color(255, 200, 70);
    private static final Color DIALOGUE_NAME  = new Color(255, 215, 100);
    private static final Color DIALOGUE_CONT  = new Color(200, 200, 200, 180);


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
                titleBackground = UtilityTool.scaleImage(titleBackground, gp.screenWidth, gp.screenHeight);
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
        messageIcon.add(null);
    }

    public void addMessage(String text, Color color, BufferedImage icon) {
        message.add(text);
        messageColor.add(color);
        messageCounter.add(0);
        messageIcon.add(icon);
    }
    public void draw(Graphics2D g2) {

        this.g2 = g2;
    
        // Enable anti-aliasing for smoother text and shapes
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setFont(arial_40);
        g2.setColor(Color.white);

        // Reset animation state when leaving certain screens
        if (gp.gameState != gp.gameOverState) gameOverAlpha = 0f;
        if (gp.gameState != gp.pauseState) pauseAlpha = 0f;

        //TITLE STATE
        if(gp.gameState == gp.titleState) {
            drawTitleScreen();
        }

        //PLAY STATE
        if(gp.gameState == gp.playState) {
            drawPlayerLife();
            drawMessage();
            drawLevelUpBanner();
        }

        // PAUSE STATE
        if(gp.gameState == gp.pauseState) {
            drawPlayerLife();
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

        // LEVEL UP STATE
        if (gp.gameState == gp.levelUpState) {
            drawPlayerLife();
            drawLevelUpScreen();
        }

        // SKILL TREE STATE
        if (gp.gameState == gp.skillTreeState) {
            drawPlayerLife();
            drawSkillTreeScreen();
        }

        if ( gp.gameState == gp.cutsceneState ) {
            drawDialogueScreen();
        }

        if( gameFinished ) {

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

        animTick++;

        // ── SCALE FACTORS ──
        float sf = gp.screenWidth / 1280f;
        int margin = (int)(12 * sf);
        int barH = (int)(12 * sf);

        // ── SMOOTH INTERPOLATION for bars ──
        float targetLife = (float) gp.player.life / Math.max(1, gp.player.maxLife);
        float targetMana = (float) gp.player.mana / Math.max(1, gp.player.maxMana);
        float targetExp  = (gp.player.nextLevelExp > 0) ? (float) gp.player.exp / gp.player.nextLevelExp : 0;
        if (smoothLife < 0) smoothLife = targetLife;
        if (smoothMana < 0) smoothMana = targetMana;
        if (smoothExp  < 0) smoothExp  = targetExp;
        smoothLife += (targetLife - smoothLife) * 0.08f;
        smoothMana += (targetMana - smoothMana) * 0.08f;
        smoothExp  += (targetExp  - smoothExp)  * 0.08f;

        // ── ALIVE PULSE (breathing animation) ──
        float pulse = (float)((Math.sin(animTick * 0.05) + 1.0) * 0.5); // 0..1 slow breathe
        float fastPulse = (float)((Math.sin(animTick * 0.15) + 1.0) * 0.5);

        // ── HUD PANEL ──
        int panelW = (int)(270 * sf);
        int panelH = (int)(82 * sf);
        // Dark backdrop with subtle gradient feel
        g2.setColor(new Color(6, 4, 14, 210));
        g2.fillRoundRect(margin, margin, panelW, panelH, 12, 12);
        // Colored top accent line (HP red tint)
        int accentH = (int)(2 * sf);
        g2.setColor(new Color(230, 60, 80, (int)(60 + 30 * pulse)));
        g2.fillRoundRect(margin + 4, margin, panelW - 8, accentH, 4, 4);
        // Thin border with faint glow
        g2.setColor(new Color(70, 60, 90, (int)(60 + 20 * pulse)));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(margin, margin, panelW, panelH, 12, 12);

        // ── LEVEL BADGE (rounded, breathing glow) ──
        int badgeSize = (int)(34 * sf);
        int badgeX = margin + (int)(8 * sf);
        int badgeY = margin + (int)(6 * sf);
        // Glow behind badge
        int glowPad = (int)(3 + 2 * pulse);
        g2.setColor(new Color(255, 200, 60, (int)(25 + 20 * pulse)));
        g2.fillRoundRect(badgeX - glowPad, badgeY - glowPad, badgeSize + glowPad * 2, badgeSize + glowPad * 2, 12, 12);
        // Badge fill
        g2.setColor(new Color(20, 16, 8, 240));
        g2.fillRoundRect(badgeX, badgeY, badgeSize, badgeSize, 10, 10);
        g2.setColor(new Color(255, 200, 60, (int)(100 + 40 * pulse)));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(badgeX, badgeY, badgeSize, badgeSize, 10, 10);
        // Level number
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 15f * sf));
        String lvlStr = String.valueOf(gp.player.level);
        FontMetrics fmLvl = g2.getFontMetrics();
        int lvlX = badgeX + badgeSize / 2 - fmLvl.stringWidth(lvlStr) / 2;
        int lvlY = badgeY + badgeSize / 2 + fmLvl.getAscent() / 2 - 2;
        g2.setColor(LVL_BADGE);
        g2.drawString(lvlStr, lvlX, lvlY);
        // "LV" tag
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8f * sf));
        g2.setColor(new Color(200, 170, 70, 180));
        String lvLabel = "LV";
        int lvLabelW = g2.getFontMetrics().stringWidth(lvLabel);
        g2.drawString(lvLabel, badgeX + badgeSize / 2 - lvLabelW / 2, badgeY + badgeSize + (int)(10 * sf));

        // ── BARS LAYOUT ──
        int barsX = badgeX + badgeSize + (int)(12 * sf);
        int barsY = margin + (int)(9 * sf);
        int fullBarW = panelW - (badgeSize + (int)(34 * sf));
        int rowH = (int)(19 * sf);

        // ── HP ROW ──
        int smallIcon = (int)(14 * sf);
        g2.drawImage(Hearts_Full, barsX, barsY, smallIcon, smallIcon, null);
        int barStartX = barsX + smallIcon + (int)(4 * sf);
        int barContentW = fullBarW - smallIcon - (int)(4 * sf);
        drawStatBar(barStartX, barsY + (int)(1 * sf), barContentW, barH, smoothLife, HP_BAR_BG, HP_BAR_FILL, HP_BAR_GLOW);
        // Low HP warning pulse
        if (smoothLife < 0.3f) {
            g2.setColor(new Color(255, 40, 40, (int)(40 * fastPulse)));
            g2.fillRoundRect(barStartX, barsY + (int)(1 * sf), barContentW, barH, barH, barH);
        }

        // ── MP ROW ──
        int mpY = barsY + rowH;
        g2.drawImage(Crystal_Full, barsX, mpY, smallIcon, smallIcon, null);
        int mpBarH = (int)(barH * 0.85f);
        drawStatBar(barStartX, mpY + (int)(1 * sf), barContentW, mpBarH, smoothMana, MP_BAR_BG, MP_BAR_FILL, MP_BAR_GLOW);

        // ── XP BAR (full panel width, anchored to bottom) ──
        int xpPad = (int)(10 * sf);
        int xpBarX = margin + xpPad;
        int xpBarW = panelW - xpPad * 2;
        int xpBarH = (int)(8 * sf);
        int xpBarY = margin + panelH - xpBarH - (int)(7 * sf);
        drawStatBar(xpBarX, xpBarY, xpBarW, xpBarH, smoothExp, XP_BAR_BG, XP_BAR_FILL, XP_BAR_GLOW);
        // XP label centered above
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8f * sf));
        g2.setColor(new Color(120, 230, 120, (int)(170 + 40 * pulse)));
        String xpTxt = "XP " + gp.player.exp + "/" + gp.player.nextLevelExp;
        int xpTxtW = g2.getFontMetrics().stringWidth(xpTxt);
        g2.drawString(xpTxt, xpBarX + xpBarW / 2 - xpTxtW / 2, xpBarY - (int)(2 * sf));

        // ── RIGHT-SIDE INFO STACK ──
        int pillW = (int)(94 * sf);
        int pillH = (int)(28 * sf);
        int pillGap = (int)(5 * sf);
        int pillRX = gp.screenWidth - margin - pillW;
        int pillRY = margin;
        int pillRound = 10;

        // Coin pill
        g2.setColor(new Color(6, 4, 14, 210));
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        // Gold accent line on left edge
        g2.setColor(new Color(255, 210, 50, (int)(100 + 40 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(new Color(70, 60, 90, 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        // Coin icon
        int coinIconSz = (int)(15 * sf);
        int coinIconX = pillRX + (int)(10 * sf);
        int coinIconY = pillRY + pillH / 2 - coinIconSz / 2;
        g2.setColor(COIN_GOLD);
        g2.fillOval(coinIconX, coinIconY, coinIconSz, coinIconSz);
        g2.setColor(new Color(180, 140, 20));
        g2.setStroke(new BasicStroke(1f));
        g2.drawOval(coinIconX, coinIconY, coinIconSz, coinIconSz);
        // Coin text
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f * sf));
        g2.setColor(COIN_GOLD);
        String coinStr = String.valueOf(gp.player.coin);
        int coinTxtX = pillRX + pillW - (int)(9 * sf) - g2.getFontMetrics().stringWidth(coinStr);
        g2.drawString(coinStr, coinTxtX, pillRY + pillH / 2 + g2.getFontMetrics().getAscent() / 2 - 1);

        // Inventory pill
        pillRY += pillH + pillGap;
        g2.setColor(new Color(6, 4, 14, 210));
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setColor(new Color(140, 160, 180, (int)(60 + 20 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(new Color(70, 60, 90, 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f * sf));
        g2.setColor(new Color(160, 170, 180));
        g2.drawString("Inv", pillRX + (int)(10 * sf), pillRY + pillH / 2 + 4);
        String invStr = gp.player.inventory.size() + "/" + gp.player.maxInventorySize;
        g2.setColor(new Color(210, 220, 230));
        int invTxtX = pillRX + pillW - (int)(9 * sf) - g2.getFontMetrics().stringWidth(invStr);
        g2.drawString(invStr, invTxtX, pillRY + pillH / 2 + 4);

        // SP pill
        pillRY += pillH + pillGap;
        g2.setColor(new Color(6, 4, 14, 210));
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setColor(new Color(255, 200, 60, (int)(80 + 30 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(new Color(70, 60, 90, 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f * sf));
        g2.setColor(new Color(255, 225, 120));
        g2.drawString("SP", pillRX + (int)(10 * sf), pillRY + pillH / 2 + 4);
        String spStr = String.valueOf(gp.player.skillPoints);
        int spTxtX = pillRX + pillW - (int)(9 * sf) - g2.getFontMetrics().stringWidth(spStr);
        g2.drawString(spStr, spTxtX, pillRY + pillH / 2 + 4);

        // ── TELEPORT COOLDOWN ──
        if (gp.teleportation) {
            int tpX = margin;
            int tpY = margin + panelH + (int)(8 * sf);
            int tpW = (int)(145 * sf);
            int tpH = (int)(24 * sf);
            g2.setColor(new Color(6, 4, 14, 210));
            g2.fillRoundRect(tpX, tpY, tpW, tpH, 8, 8);
            // Cyan accent line
            float tpPct = 1f - (float) gp.keyH.teleportCooldown / gp.player.getTeleportCooldownMax();
            g2.setColor(new Color(80, 180, 255, (int)(50 + 40 * (tpPct >= 1f ? fastPulse : 0))));
            g2.fillRoundRect(tpX, tpY + 3, (int)(3 * sf), tpH - 6, 3, 3);
            g2.setColor(new Color(70, 60, 90, 70));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(tpX, tpY, tpW, tpH, 8, 8);

            int barX = tpX + (int)(8 * sf);
            int barY = tpY + tpH / 2 + (int)(1 * sf);
            int barW2 = tpW - (int)(16 * sf);
            int barH2 = (int)(5 * sf);
            g2.setColor(new Color(15, 25, 45, 210));
            g2.fillRoundRect(barX, barY, barW2, barH2, barH2, barH2);
            int fillW2 = (int)(barW2 * tpPct);
            if (fillW2 > 0) {
                Color tpFill = tpPct >= 1f ? new Color(80, 200, 255) : new Color(50, 110, 170);
                g2.setColor(tpFill);
                g2.fillRoundRect(barX, barY, fillW2, barH2, barH2, barH2);
                if (tpPct >= 1f) {
                    g2.setColor(new Color(160, 230, 255, (int)(60 + 40 * fastPulse)));
                    g2.fillRoundRect(barX, barY, fillW2, barH2 / 2, barH2, barH2);
                }
            }
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f * sf));
            g2.setColor(tpPct >= 1f ? new Color(130, 220, 255, (int)(200 + 55 * fastPulse)) : new Color(90, 110, 140));
            g2.drawString(tpPct >= 1f ? "BLINK  READY" : "BLINK", tpX + (int)(9 * sf), tpY + tpH / 2 - (int)(1 * sf));
        }

        // ── EXTRA ABILITY COOLDOWNS (left-bottom stack, unlocked only) ──
        int abW = (int)(168 * sf);
        int abH = (int)(20 * sf);
        int abGap = (int)(6 * sf);
        int abX = margin;
        int abY = gp.screenHeight - margin - abH;

        if (gp.player.overdriveUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Overdrive",
                true,
                gp.player.getOverdriveCooldown(),
                gp.player.getOverdriveCooldownMax(),
                new Color(255, 150, 90));
            abY -= abH + abGap;
        }

        if (gp.player.frostNovaUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Frost Nova",
                true,
                gp.player.getFrostNovaCooldown(),
                gp.player.getFrostNovaCooldownMax(),
                new Color(145, 210, 255));
            abY -= abH + abGap;
        }

        if (gp.player.voidSnareUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Void Snare",
                true,
                gp.player.getVoidSnareCooldown(),
                gp.player.getVoidSnareCooldownMax(),
                new Color(160, 125, 255));
            abY -= abH + abGap;
        }

        if (gp.player.shockwaveUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Shockwave",
                true,
                gp.player.getShockwaveCooldown(),
                gp.player.getShockwaveCooldownMax(),
                new Color(255, 185, 95));
        }
    }

    private void drawAbilityBar(int x, int y, int w, int h, String name, boolean unlocked, int cooldown, int maxCooldown, Color accent) {
        // Dark panel
        g2.setColor(new Color(6, 4, 14, 210));
        g2.fillRoundRect(x, y, w, h, 8, 8);
        // Colored accent line (left edge)
        float abPulse = (float)((Math.sin(animTick * 0.1) + 1.0) * 0.5);
        boolean ready = unlocked && cooldown <= 0;
        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                (int)(ready ? 90 + 50 * abPulse : 50)));
        g2.fillRoundRect(x, y + 3, 3, h - 6, 3, 3);
        // Border
        g2.setColor(new Color(70, 60, 90, 70));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, 8, 8);

        float pct;
        if (!unlocked) {
            pct = 0f;
        } else if (maxCooldown <= 0) {
            pct = 1f;
        } else {
            pct = 1f - (float) cooldown / maxCooldown;
        }
        pct = Math.max(0f, Math.min(1f, pct));

        int barX = x + 6;
        int barY = y + h / 2 + 1;
        int barW = w - 12;
        int barH = Math.max(4, h / 3);
        g2.setColor(new Color(15, 12, 25, 200));
        g2.fillRoundRect(barX, barY, barW, barH, 6, 6);
        int fillW = (int)(barW * pct);
        if (fillW > 0) {
            Color fill = unlocked ? accent : new Color(60, 60, 70);
            g2.setColor(fill);
            g2.fillRoundRect(barX, barY, fillW, barH, 6, 6);
            // Top glow
            if (pct >= 1f) {
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int)(60 + 40 * abPulse)));
                g2.fillRoundRect(barX, barY, fillW, barH / 2, 6, 6);
            }
        }

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
        if (!unlocked) {
            g2.setColor(new Color(100, 95, 90));
            g2.drawString(name + " (locked)", x + 8, y + h - 9);
        } else {
            g2.setColor(pct >= 1f ? new Color(
                Math.min(255, accent.getRed() + 40),
                Math.min(255, accent.getGreen() + 40),
                Math.min(255, accent.getBlue() + 40),
                (int)(200 + 55 * abPulse)) : new Color(180, 175, 165));
            g2.drawString(pct >= 1f ? name + "  READY" : name, x + 8, y + h - 9);
        }
    }

    /** Draws a smooth stat bar with modern glow and highlight. */
    private void drawStatBar(int x, int y, int w, int h, float pct, Color bg, Color fill, Color glow) {
        pct = Math.max(0f, Math.min(1f, pct));
        // Background
        g2.setColor(bg);
        g2.fillRoundRect(x, y, w, h, h, h);
        int fillW = (int)((w - 2) * pct);
        if (fillW > 0) {
            // Main fill
            g2.setColor(fill);
            g2.fillRoundRect(x + 1, y + 1, fillW, h - 2, h - 2, h - 2);
            // Top highlight (simulate light reflection)
            g2.setColor(glow);
            g2.fillRoundRect(x + 1, y + 1, fillW, Math.max(2, (h - 2) / 3), h - 2, h - 2);
            // Bright tip at the fill edge
            if (fillW > 4) {
                int tipW = Math.min(6, fillW / 3);
                g2.setColor(new Color(255, 255, 255, 50));
                g2.fillRoundRect(x + 1 + fillW - tipW, y + 1, tipW, h - 2, h - 2, h - 2);
            }
        }
        // Crisp outline
        g2.setColor(new Color(200, 200, 220, 25));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, w, h, h, h);
    }
    public void drawMessage() {

    g2.setFont(g2.getFont().deriveFont(Font.BOLD, 22F));
    int totalHeight = 0;

    for (int i = 0; i < message.size(); i++) {

        if (message.get(i) != null) {

            int count = messageCounter.get(i) + 1;
            messageCounter.set(i, count);

            // Alpha: full 0-140, fade 140-180
            int alpha = 255;
            if (count > 140) {
                alpha = 255 - (int)((count - 140) * (255.0 / 40));
                if (alpha < 0) alpha = 0;
            }

            // Slide from right: ease-out over first 12 frames, ease-in slide out over last 40
            int slideOffset = 0;
            if (count < 12) {
                float t = count / 12f;
                slideOffset = (int)((1 - t * t) * 200);
            } else if (count > 140) {
                float t = (count - 140) / 40f;
                slideOffset = (int)(t * t * 200);
            }

            String txt = message.get(i);
            int txtW = (int) g2.getFontMetrics().getStringBounds(txt, g2).getWidth();
            BufferedImage icon = messageIcon.get(i);
            int iconSpace = icon != null ? 28 : 0;
            int pillW = txtW + iconSpace + 24;
            int pillH = 34;

            // Position: right side of screen, below minimap area
            int px = gp.screenWidth - pillW - 16 + slideOffset;
            int py = 300 + totalHeight;

            Color baseColor = messageColor.get(i);

            // Pill background
            g2.setColor(new Color(10, 8, 6, (int)(alpha * 0.7f)));
            g2.fillRoundRect(px, py, pillW, pillH, 12, 12);

            // Right accent bar
            g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
            g2.fillRoundRect(px + pillW - 3, py, 3, pillH, 3, 3);

            // Icon
            if (icon != null) {
                java.awt.Composite saved = g2.getComposite();
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha / 255f));
                g2.drawImage(icon, px + 8, py + 3, 24, 24, null);
                g2.setComposite(saved);
            }

            // Text shadow
            g2.setColor(new Color(0, 0, 0, alpha));
            g2.drawString(txt, px + 10 + iconSpace + 1, py + 23);

            // Text
            g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
            g2.drawString(txt, px + 10 + iconSpace, py + 22);

            totalHeight += pillH + 6;

            // Remove after 180 frames
            if (count > 180) {
                message.remove(i);
                messageCounter.remove(i);
                messageColor.remove(i);
                messageIcon.remove(i);
                i--;
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

            // ── TITLE ──
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 72F));
            String text = "Michi's Adventure";
            int tw = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            int tx = (gp.screenWidth - tw) / 2;
            int ty = (int)(gp.screenHeight * 0.18);
            // Drop shadow
            g2.setColor(new Color(0, 0, 0, 140));
            g2.drawString(text, tx + 3, ty + 3);
            // Gold gradient
            java.awt.GradientPaint titleGrad = new java.awt.GradientPaint(
                tx, ty - 40, new Color(255, 230, 120),
                tx, ty + 10, new Color(200, 150, 40));
            g2.setPaint(titleGrad);
            g2.drawString(text, tx, ty);

            // Subtitle
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 18F));
            g2.setColor(new Color(180, 170, 150, 200));
            String sub = "A Pixel RPG Adventure";
            int sw = (int) g2.getFontMetrics().getStringBounds(sub, g2).getWidth();
            g2.drawString(sub, (gp.screenWidth - sw) / 2, ty + 30);

            // ── CHARACTER SPRITE ──
            int spriteSize = gp.tileSize * 2;
            int sx = gp.screenWidth / 2 - spriteSize / 2;
            int sy = ty + 50;
            // Subtle glow behind sprite
            g2.setColor(new Color(255, 200, 60, 30));
            g2.fillOval(sx - 10, sy - 5, spriteSize + 20, spriteSize + 10);
            g2.drawImage(gp.player.down1, sx, sy, spriteSize, spriteSize, null);

            // ── MENU BUTTONS ──
            String[] menuItems = {"NEW GAME", "LOAD GAME", "QUIT"};
            int btnW = (int)(gp.screenWidth * 0.25);
            int btnH = 50;
            int btnX = (gp.screenWidth - btnW) / 2;
            int startY = sy + spriteSize + 50;

            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 28F));

            for (int i = 0; i < menuItems.length; i++) {
                int by = startY + i * (btnH + 12);
                boolean sel = (commandNum == i);

                if (sel) {
                    // Glow
                    g2.setColor(new Color(255, 200, 60, 20));
                    g2.fillRoundRect(btnX - 6, by - 6, btnW + 12, btnH + 12, 20, 20);
                    // Filled background
                    java.awt.GradientPaint btnGrad = new java.awt.GradientPaint(
                        btnX, by, new Color(60, 45, 20, 200),
                        btnX, by + btnH, new Color(40, 30, 12, 220));
                    g2.setPaint(btnGrad);
                    g2.fillRoundRect(btnX, by, btnW, btnH, 14, 14);
                    // Gold border
                    g2.setColor(new Color(220, 180, 60));
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(btnX, by, btnW, btnH, 14, 14);
                    // Left accent
                    g2.setColor(new Color(255, 210, 70));
                    g2.fillRoundRect(btnX, by + 8, 3, btnH - 16, 2, 2);
                } else {
                    g2.setColor(new Color(15, 12, 8, 160));
                    g2.fillRoundRect(btnX, by, btnW, btnH, 14, 14);
                    g2.setColor(new Color(80, 70, 50, 80));
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(btnX, by, btnW, btnH, 14, 14);
                }

                // Text
                text = menuItems[i];
                int ttw = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
                int ttx = btnX + (btnW - ttw) / 2;
                g2.setColor(sel ? new Color(255, 230, 150) : new Color(160, 150, 130));
                g2.drawString(text, ttx, by + btnH / 2 + 10);
            }

            // ── INFO HINT (bottom left) ──
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16F));
            g2.setColor(new Color(140, 130, 110, 180));
            g2.drawString("[I] Info & Update Log", 20, gp.screenHeight - 20);

            // ── VERSION (bottom right) ──
            String ver = "v0.2 Alpha";
            int vw = (int) g2.getFontMetrics().getStringBounds(ver, g2).getWidth();
            g2.drawString(ver, gp.screenWidth - vw - 20, gp.screenHeight - 20);
        }
        else if ( titleScreenState == 1) {

            // ── CLASS SELECTION SCREEN ──
            int panelW = 500, panelH = 420;
            int px = (gp.screenWidth - panelW) / 2;
            int py = (gp.screenHeight - panelH) / 2;

            // Dark panel
            g2.setColor(new Color(15, 12, 20, 230));
            g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
            g2.setColor(new Color(180, 150, 80, 100));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

            // Title
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 34F));
            g2.setColor(new Color(255, 220, 100));
            String text = "Choose Your Class";
            int tw = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            g2.drawString(text, px + (panelW - tw) / 2, py + 48);

            // Divider
            g2.setColor(new Color(120, 100, 60, 80));
            g2.drawLine(px + 30, py + 62, px + panelW - 30, py + 62);

            // Class options
            String[] classes = {"Fighter", "Ronin", "Magician"};
            String[] descs = {"High HP, Strong defense", "Fast attacks, High crit", "Powerful magic, High mana"};
            String[] icons = {"\u2694", "\u2620", "\u2733"}; // swords, skull, asterisk
            Color[] classColors = {
                new Color(220, 80, 60),
                new Color(80, 180, 220),
                new Color(160, 80, 220)
            };

            int optY = py + 85;
            int optW = panelW - 60;
            int optH = 60;
            int optX = px + 30;

            for (int i = 0; i < 3; i++) {
                int oy = optY + i * (optH + 14);
                boolean sel = (commandNum == i);

                if (sel) {
                    g2.setColor(new Color(classColors[i].getRed(), classColors[i].getGreen(), classColors[i].getBlue(), 25));
                    g2.fillRoundRect(optX - 4, oy - 4, optW + 8, optH + 8, 14, 14);
                    g2.setColor(new Color(classColors[i].getRed(), classColors[i].getGreen(), classColors[i].getBlue(), 60));
                    g2.fillRoundRect(optX, oy, optW, optH, 12, 12);
                    g2.setColor(classColors[i]);
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(optX, oy, optW, optH, 12, 12);
                    // Left accent
                    g2.fillRoundRect(optX, oy + 8, 3, optH - 16, 2, 2);
                } else {
                    g2.setColor(new Color(30, 25, 40, 140));
                    g2.fillRoundRect(optX, oy, optW, optH, 12, 12);
                    g2.setColor(new Color(60, 55, 70, 60));
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(optX, oy, optW, optH, 12, 12);
                }

                // Icon
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 22F));
                g2.setColor(sel ? classColors[i] : new Color(120, 110, 100));
                g2.drawString(icons[i], optX + 18, oy + 28);

                // Class name
                g2.setFont(g2.getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN, 24F));
                g2.setColor(sel ? Color.WHITE : new Color(170, 160, 145));
                g2.drawString(classes[i], optX + 50, oy + 28);

                // Description
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14F));
                g2.setColor(sel ? new Color(200, 190, 170) : new Color(120, 115, 105));
                g2.drawString(descs[i], optX + 50, oy + 48);
            }

            // Back option
            int backY = optY + 3 * (optH + 14) + 10;
            boolean backSel = (commandNum == 3);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 20F));
            text = "\u2190 Back";
            tw = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            g2.setColor(backSel ? new Color(255, 220, 100) : new Color(120, 115, 105));
            g2.drawString(text, px + (panelW - tw) / 2, backY);

            // Hint
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13F));
            g2.setColor(new Color(100, 95, 85));
            String hint = "[W/S] Navigate    [Enter] Select";
            int hw = (int) g2.getFontMetrics().getStringBounds(hint, g2).getWidth();
            g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 15);
        }
        else if ( titleScreenState == 2 ) {

            // ── UPDATE LOG / INFO SCREEN ──
            int panelW = 560, panelH = 480;
            int px = (gp.screenWidth - panelW) / 2;
            int py = (gp.screenHeight - panelH) / 2;

            // Dark panel
            g2.setColor(new Color(15, 12, 22, 235));
            g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
            g2.setColor(new Color(100, 140, 180, 80));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

            // Title
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 30F));
            g2.setColor(new Color(120, 180, 255));
            String text = "Update Log  \u2022  v0.2 Alpha";
            int tw = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            g2.drawString(text, px + (panelW - tw) / 2, py + 42);

            // Divider
            java.awt.GradientPaint divGrad = new java.awt.GradientPaint(
                px + 40, py + 55, new Color(100, 140, 180, 0),
                px + panelW / 2, py + 55, new Color(100, 140, 180, 120));
            g2.setPaint(divGrad);
            g2.drawLine(px + 40, py + 55, px + panelW / 2, py + 55);
            divGrad = new java.awt.GradientPaint(
                px + panelW / 2, py + 55, new Color(100, 140, 180, 120),
                px + panelW - 40, py + 55, new Color(100, 140, 180, 0));
            g2.setPaint(divGrad);
            g2.drawLine(px + panelW / 2, py + 55, px + panelW - 40, py + 55);

            // Update entries
            String[][] entries = {
                {"\u2726", "New map system with TMX loading"},
                {"\u2694", "Combat: Dodge roll, 3-hit combos"},
                {"\u2620", "New enemy: Skeleton Archer"},
                {"\u2605", "Level-up stat selection screen"},
                {"\u2302", "Breakable pots with random loot"},
                {"\u2611", "Quest tracking system"},
                {"\u2726", "Minimap with entity markers"},
                {"\u2694", "Teleport ability with particles"},
                {"\u2605", "Floating damage numbers"},
                {"\u2302", "Screen shake & hit flash FX"}
            };

            Color[] entryColors = {
                new Color(255, 200, 80),
                new Color(220, 90, 70),
                new Color(180, 80, 200),
                new Color(255, 220, 100),
                new Color(100, 200, 120),
                new Color(80, 180, 230),
                new Color(255, 200, 80),
                new Color(220, 90, 70),
                new Color(255, 220, 100),
                new Color(100, 200, 120)
            };

            int entryY = py + 78;
            int entryX = px + 40;

            for (int i = 0; i < entries.length; i++) {
                int ey = entryY + i * 34;
                if (ey > py + panelH - 70) break; // don't overflow

                // Bullet/icon
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16F));
                g2.setColor(entryColors[i % entryColors.length]);
                g2.drawString(entries[i][0], entryX, ey);

                // Entry text
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 17F));
                g2.setColor(new Color(210, 205, 195));
                g2.drawString(entries[i][1], entryX + 26, ey);
            }

            // Controls info section
            int ctrlY = py + panelH - 80;
            g2.setColor(new Color(60, 55, 70, 60));
            g2.fillRoundRect(px + 25, ctrlY, panelW - 50, 40, 10, 10);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13F));
            g2.setColor(new Color(160, 155, 140));
            g2.drawString("WASD Move | Enter Attack | Space Blink | Shift Roll | Z/X/C/V Skills", px + 40, ctrlY + 25);

            // Back button
            boolean backSel = (commandNum == 0);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 20F));
            text = "\u2190 Back";
            tw = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
            g2.setColor(backSel ? new Color(120, 180, 255) : new Color(100, 95, 85));
            g2.drawString(text, px + (panelW - tw) / 2, py + panelH - 18);
        }
    }
    public void drawPauseScreen() {

        // Fade in overlay
        if (pauseAlpha < 1f) pauseAlpha += 0.06f;
        if (pauseAlpha > 1f) pauseAlpha = 1f;

        // ── DARK BLUR-LIKE OVERLAY ──
        g2.setColor(new Color(8, 8, 15, (int)(160 * pauseAlpha)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // ── SUBTLE BORDER FRAME ──
        int frameInset = gp.tileSize * 3;
        g2.setColor(new Color(180, 140, 60, (int)(40 * pauseAlpha)));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(frameInset, gp.tileSize * 2, gp.screenWidth - frameInset * 2,
                gp.screenHeight - gp.tileSize * 4, 20, 20);

        // ── "PAUSED" TITLE with breathing effect ──
        float breathe = (float)((Math.sin(animTick * 0.04) + 1.0) * 0.5);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 72F));
        String text = "PAUSED";
        int x = getXforCenteredText(text);
        int y = gp.screenHeight / 2 - gp.tileSize;

        // shadow
        g2.setColor(new Color(0, 0, 0, (int)(180 * pauseAlpha)));
        g2.drawString(text, x + 3, y + 3);
        // main text with pulsing alpha
        int textAlpha = (int)((180 + 75 * breathe) * pauseAlpha);
        g2.setColor(new Color(220, 210, 190, Math.min(255, textAlpha)));
        g2.drawString(text, x, y);

        // ── DECORATIVE LINES around title ──
        int lineW = gp.tileSize * 4;
        int lineY = y + 14;
        g2.setColor(new Color(180, 140, 60, (int)(80 * pauseAlpha)));
        g2.setStroke(new BasicStroke(2f));
        // left line
        g2.drawLine(x - lineW - 20, lineY, x - 20, lineY);
        // right line
        int textW = (int) g2.getFontMetrics().getStringBounds(text, g2).getWidth();
        g2.drawLine(x + textW + 20, lineY, x + textW + lineW + 20, lineY);

        // ── QUICK STATS ──
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 20F));
        int statsY = y + gp.tileSize + 20;
        String[] quickStats = {
            "Level " + gp.player.level,
            "HP " + gp.player.life + "/" + gp.player.maxLife,
            "Mana " + gp.player.mana + "/" + gp.player.maxMana
        };
        int totalW = 0;
        int gap = gp.tileSize;
        for (String s : quickStats) {
            totalW += (int) g2.getFontMetrics().getStringBounds(s, g2).getWidth();
        }
        totalW += gap * (quickStats.length - 1);
        int sx = gp.screenWidth / 2 - totalW / 2;
        Color[] statColors = { LVL_BADGE, HP_BAR_FILL, MP_BAR_FILL };
        for (int i = 0; i < quickStats.length; i++) {
            g2.setColor(new Color(statColors[i].getRed(), statColors[i].getGreen(),
                    statColors[i].getBlue(), (int)(180 * pauseAlpha)));
            g2.drawString(quickStats[i], sx, statsY);
            sx += (int) g2.getFontMetrics().getStringBounds(quickStats[i], g2).getWidth() + gap;
        }

        // ── HINT ──
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16F));
        g2.setColor(new Color(150, 145, 130, (int)(120 * pauseAlpha)));
        String hint = "Press P to resume";
        int hx = getXforCenteredText(hint);
        g2.drawString(hint, hx, gp.screenHeight - gp.tileSize * 2);
    }
    public void drawDialogueScreen() {

        if (npc == null) return;

        // ── DIALOGUE WINDOW ──
        int x = gp.tileSize * 2;
        int y = gp.tileSize / 2;
        int width = gp.screenWidth - (gp.tileSize * 4);
        int height = gp.tileSize * 5;

        drawSubWindow(x, y, width, height);

        // ── NPC NAME TAG ──
        if (npc != null && npc.name != null && !npc.name.isEmpty()) {
            int nameTagW = (int)(g2.getFontMetrics(g2.getFont().deriveFont(Font.BOLD, 20F))
                    .getStringBounds(npc.name, g2).getWidth()) + 30;
            int nameTagH = 30;
            int nameTagX = x + 16;
            int nameTagY = y - nameTagH + 4;
            // name tag background
            g2.setColor(new Color(25, 20, 12, 230));
            g2.fillRoundRect(nameTagX, nameTagY, nameTagW, nameTagH, 10, 10);
            g2.setColor(OPT_BORDER);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(nameTagX, nameTagY, nameTagW, nameTagH, 10, 10);
            // name text
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 20F));
            g2.setColor(DIALOGUE_NAME);
            g2.drawString(npc.name, nameTagX + 14, nameTagY + 21);
        }

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 28F));
        x += gp.tileSize;
        y += gp.tileSize;

        if ( npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex] != null ) {

            currentDialogue = npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex];

            char characters[] = npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex].toCharArray();

            if ( charIndex < characters.length ) {

                String s = String.valueOf(characters[charIndex]);
                combinedText = combinedText + s;
                currentDialogue = combinedText;
                charIndex++;
            }

            if ( gp.keyH.enterPressed ) {

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
                npc = null; // prevent dialogue from replaying in later cutscene phases
                return;
            }
        }

        // ── DRAW TEXT with shadow ──
        g2.setColor(Color.white);
        for ( String line : currentDialogue.split("\n")) {
            // text shadow
            g2.setColor(new Color(0, 0, 0, 100));
            g2.drawString(line, x + 2, y + 2);
            // main text
            g2.setColor(new Color(230, 225, 215));
            g2.drawString(line, x, y);
            y += 40;
        }

        // ── BLINKING CONTINUE INDICATOR ──
        if (charIndex >= (npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex] != null ?
                npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex].length() : 0)) {
            float blink = (float)((Math.sin(animTick * 0.1) + 1.0) * 0.5);
            int alpha = (int)(80 + 175 * blink);
            g2.setColor(new Color(220, 210, 190, alpha));
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 18F));
            String cont = "\u25BC ENTER";
            int contW = (int) g2.getFontMetrics().getStringBounds(cont, g2).getWidth();
            int contX = gp.tileSize * 2 + width - gp.tileSize - contW;
            int contY = gp.tileSize / 2 + height - 16;
            g2.drawString(cont, contX, contY);
        }
    }
    public void drawGameOverScreen() {

        // Fade-in animation
        if (gameOverAlpha < 1f) gameOverAlpha += 0.03f;
        if (gameOverAlpha > 1f) gameOverAlpha = 1f;

        // ── DARK OVERLAY with red tint ──
        g2.setColor(new Color(30, 0, 0, (int)(180 * gameOverAlpha)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // ── VIGNETTE EFFECT (darker edges) ──
        int vigAlpha = (int)(100 * gameOverAlpha);
        for (int i = 0; i < 4; i++) {
            int a = Math.max(0, vigAlpha - i * 20);
            g2.setColor(new Color(0, 0, 0, a));
            g2.fillRect(0, 0, gp.screenWidth, gp.tileSize / 2 * (4 - i)); // top
            g2.fillRect(0, gp.screenHeight - gp.tileSize / 2 * (4 - i), gp.screenWidth, gp.tileSize / 2 * (4 - i)); // bottom
        }

        int x;
        int y;
        String text;

        // ── "GAME OVER" TITLE with shake + pulse ──
        float titlePulse = (float)((Math.sin(animTick * 0.05) + 1.0) * 0.5);
        int shakeX = (int)(Math.sin(animTick * 0.3) * 2 * (1f - gameOverAlpha)); // shake fades out

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 96f));
        text = "Game Over";
        x = getXforCenteredText(text) + shakeX;
        y = gp.tileSize * 4;

        // deep shadow
        g2.setColor(new Color(80, 0, 0, (int)(200 * gameOverAlpha)));
        g2.drawString(text, x + 4, y + 4);
        // red glow
        g2.setColor(new Color(200, 40, 40, (int)((100 + 60 * titlePulse) * gameOverAlpha)));
        g2.drawString(text, x + 2, y + 2);
        // main text
        int rr = (int)(200 + 55 * titlePulse);
        g2.setColor(new Color(Math.min(255, rr), 50, 50, (int)(255 * gameOverAlpha)));
        g2.drawString(text, x, y);

        // ── SUBTITLE ──
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 22f));
        g2.setColor(new Color(180, 150, 140, (int)(180 * gameOverAlpha)));
        String sub = "The darkness claims another soul...";
        int subX = getXforCenteredText(sub);
        g2.drawString(sub, subX, y + 50);

        // ── BUTTONS ──
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 42f));
        String[] opts = {"Retry", "Quit"};
        int buttonW = gp.tileSize * 5;
        int buttonH = gp.tileSize;
        int buttonX = gp.screenWidth / 2 - buttonW / 2;
        y += gp.tileSize * 4;

        for (int i = 0; i < opts.length; i++) {
            text = opts[i];
            int btnY = y + i * (buttonH + 16);
            int rectY = btnY - buttonH + 18;
            boolean sel = (commandNum == i);

            if (sel) {
                // highlighted button
                g2.setColor(new Color(150, 30, 30, (int)(80 * gameOverAlpha)));
                g2.fillRoundRect(buttonX, rectY, buttonW, buttonH, 20, 20);
                g2.setColor(new Color(220, 60, 60, (int)(180 * gameOverAlpha)));
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawRoundRect(buttonX, rectY, buttonW, buttonH, 20, 20);
                g2.setColor(new Color(255, 200, 180, (int)(255 * gameOverAlpha)));
            } else {
                // dim button
                g2.setColor(new Color(40, 15, 15, (int)(100 * gameOverAlpha)));
                g2.fillRoundRect(buttonX, rectY, buttonW, buttonH, 20, 20);
                g2.setColor(new Color(120, 80, 80, (int)(120 * gameOverAlpha)));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(buttonX, rectY, buttonW, buttonH, 20, 20);
                g2.setColor(new Color(160, 130, 130, (int)(200 * gameOverAlpha)));
            }

            int txtX = getXforCenteredText(text);
            g2.drawString(text, txtX, btnY);
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

        float pulse = (float)((Math.sin(animTick * 0.05) + 1.0) * 0.5);
        float leafSway = (float)(Math.sin(animTick * 0.03) * 3);

        // ── FRAME ──
        final int frameX = gp.tileSize + gp.tileSize / 2;
        final int frameY = 12;
        final int frameWidth = gp.tileSize * 6;
        final int frameHeight = gp.screenHeight - 24;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight);

        final int pad = 16;
        final int leftX = frameX + pad;
        final int rightX = frameX + frameWidth - pad;
        final int contentW = rightX - leftX;

        // Vine accents
        g2.setColor(new Color(60, 130, 60, (int)(40 + 20 * pulse)));
        g2.fillRoundRect(leftX + (int)leafSway, frameY + 5, contentW, 2, 4, 4);
        g2.fillRoundRect(leftX - (int)leafSway, frameY + frameHeight - 7, contentW, 2, 4, 4);

        int curY = frameY + 26;

        // ── TITLE ──
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 24F));
        String charTitle = "Character";
        int ctW = (int) g2.getFontMetrics().getStringBounds(charTitle, g2).getWidth();
        int ctX = frameX + frameWidth / 2 - ctW / 2;
        g2.setColor(new Color(0, 0, 0, 100));
        g2.drawString(charTitle, ctX + 1, curY + 1);
        g2.setColor(new Color(200, 180, 110, (int)(210 + 45 * pulse)));
        g2.drawString(charTitle, ctX, curY);
        curY += 14;

        // ── PORTRAIT + INFO ──
        int portraitSize = 52;
        int portraitX = leftX;
        int portraitY = curY;
        int gPad = (int)(2 + 2 * pulse);
        g2.setColor(new Color(120, 180, 80, (int)(30 + 25 * pulse)));
        g2.fillRoundRect(portraitX - gPad - 1, portraitY - gPad - 1, portraitSize + gPad * 2 + 2, portraitSize + gPad * 2 + 2, 10, 10);
        g2.setColor(new Color(15, 12, 8, 220));
        g2.fillRoundRect(portraitX - 1, portraitY - 1, portraitSize + 2, portraitSize + 2, 6, 6);
        g2.setColor(new Color(100, 160, 70, (int)(80 + 30 * pulse)));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(portraitX - 1, portraitY - 1, portraitSize + 2, portraitSize + 2, 6, 6);
        g2.drawImage(gp.player.down1, portraitX, portraitY, portraitSize, portraitSize, null);

        int infoX = portraitX + portraitSize + 12;
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 17F));
        g2.setColor(LVL_BADGE);
        g2.drawString("Lv. " + gp.player.level, infoX, portraitY + 18);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12F));
        g2.setColor(new Color(160, 180, 140));
        g2.drawString("Adventurer", infoX, portraitY + 33);
        // SP badge
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10F));
        String spStr = "SP:" + gp.player.skillPoints;
        int spTxtW = g2.getFontMetrics().stringWidth(spStr);
        g2.setColor(new Color(100, 60, 180, (int)(50 + 30 * pulse)));
        g2.fillRoundRect(infoX, portraitY + 38, spTxtW + 8, 14, 6, 6);
        g2.setColor(new Color(200, 170, 255));
        g2.drawString(spStr, infoX + 4, portraitY + 49);

        curY = portraitY + portraitSize + 10;

        // ── HEARTS + HP BAR ──
        int heartSz = 16;
        int heartGap = 2;
        for (int i = 0; i < gp.player.maxLife; i++)
            g2.drawImage(Hearts_Empty, leftX + i * (heartSz + heartGap), curY, heartSz, heartSz, null);
        for (int i = 0; i < gp.player.life; i++)
            g2.drawImage(Hearts_Full, leftX + i * (heartSz + heartGap), curY, heartSz, heartSz, null);
        curY += heartSz + 3;
        drawStatBar(leftX, curY, contentW, 7, (float) gp.player.life / Math.max(1, gp.player.maxLife), HP_BAR_BG, HP_BAR_FILL, HP_BAR_GLOW);
        curY += 12;

        // ── CRYSTALS + MP BAR ──
        int crystalSz = heartSz - 2;
        for (int i = 0; i < gp.player.maxMana; i++)
            g2.drawImage(Crystal_Empty, leftX + i * (heartSz + heartGap), curY, crystalSz, crystalSz, null);
        for (int i = 0; i < gp.player.mana; i++)
            g2.drawImage(Crystal_Full, leftX + i * (heartSz + heartGap), curY, crystalSz, crystalSz, null);
        curY += crystalSz + 3;
        drawStatBar(leftX, curY, contentW, 6, (float) gp.player.mana / Math.max(1, gp.player.maxMana), MP_BAR_BG, MP_BAR_FILL, MP_BAR_GLOW);
        curY += 24;

        // ── COMBAT ──
        final int rowH = 24;
        final int sectionGap = 10;
        curY = drawSectionHeader(g2, "COMBAT", leftX, rightX, curY, pulse);
        String[] cLabels = {"Strength", "Dexterity", "Attack", "Defense", "Speed"};
        String[] cValues = {String.valueOf(gp.player.strenght), String.valueOf(gp.player.dexterity),
            String.valueOf(gp.player.attack), String.valueOf(gp.player.defense), String.valueOf(gp.player.speed)};
        Color[] cColors = {new Color(210,170,90), new Color(90,190,140), new Color(230,120,60), new Color(80,150,210), new Color(180,220,130)};
        for (int i = 0; i < cLabels.length; i++) {
            int ry = curY + i * rowH;
            if (i % 2 == 0) { g2.setColor(new Color(60,90,40,18)); g2.fillRoundRect(leftX-3, ry-15, contentW+6, rowH-2, 5, 5); }
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 15F));
            g2.setColor(cColors[i]); g2.drawString(cLabels[i], leftX, ry);
            g2.setColor(new Color(235,230,215)); g2.drawString(cValues[i], getXforAlignToRightText(cValues[i], rightX), ry);
        }
        curY += cLabels.length * rowH + sectionGap;

        // ── PROGRESSION ──
        curY = drawSectionHeader(g2, "PROGRESSION", leftX, rightX, curY, pulse);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14F));
        g2.setColor(XP_BAR_FILL); g2.drawString("Exp", leftX, curY);
        String expStr = gp.player.exp + " / " + gp.player.nextLevelExp;
        g2.setColor(new Color(235,230,215)); g2.drawString(expStr, getXforAlignToRightText(expStr, rightX), curY);
        drawStatBar(leftX, curY + 5, contentW, 6, gp.player.nextLevelExp > 0 ? (float) gp.player.exp / gp.player.nextLevelExp : 0, XP_BAR_BG, XP_BAR_FILL, XP_BAR_GLOW);
        curY += 22 + sectionGap;

        // ── ITEMS (2-column) ──
        curY = drawSectionHeader(g2, "ITEMS", leftX, rightX, curY, pulse);
        String[] iLabels = {"Coins", "Keys", "Gems", "Artefacts"};
        String[] iValues = {String.valueOf(gp.player.coin), String.valueOf(gp.player.hasKey), String.valueOf(gp.player.hasGem), String.valueOf(gp.player.hasArtefact)};
        Color[] iColors = {COIN_GOLD, new Color(200,180,80), new Color(80,220,200), new Color(220,120,220)};
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14F));
        int colW = contentW / 2;
        for (int i = 0; i < iLabels.length; i++) {
            int col = i % 2, row = i / 2;
            int ix = leftX + col * colW, iy = curY + row * 22;
            g2.setColor(iColors[i]); g2.drawString(iLabels[i], ix, iy);
            g2.setColor(new Color(235,230,215)); g2.drawString(iValues[i], getXforAlignToRightText(iValues[i], ix + colW - 4), iy);
        }
        curY += ((iLabels.length + 1) / 2) * 22 + sectionGap;

        // ── ABILITIES ──
        curY = drawSectionHeader(g2, "ABILITIES", leftX, rightX, curY, pulse);
        String[] aNames = {"Dash", "Shockwave", "Void Snare", "Frost Nova", "Overdrive"};
        boolean[] aUnlocked = {gp.player.dashUnlocked, gp.player.shockwaveUnlocked, gp.player.voidSnareUnlocked, gp.player.frostNovaUnlocked, gp.player.overdriveUnlocked};
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11F));
        int abX = leftX;
        for (int i = 0; i < aNames.length; i++) {
            int bw = g2.getFontMetrics().stringWidth(aNames[i]) + 10;
            if (abX + bw > rightX) { abX = leftX; curY += 18; }
            g2.setColor(aUnlocked[i] ? new Color(40,120,60,(int)(80+40*pulse)) : new Color(40,40,40,100));
            g2.fillRoundRect(abX, curY - 11, bw, 16, 5, 5);
            g2.setColor(aUnlocked[i] ? new Color(100,220,120) : new Color(100,100,100,120));
            g2.drawString(aNames[i], abX + 5, curY);
            abX += bw + 4;
        }
        curY += 20 + sectionGap;

        // ── EQUIPMENT ──
        g2.setColor(new Color(80,120,50,(int)(50+20*pulse)));
        g2.fillRect(leftX, curY - 8, contentW, 1);
        curY = drawSectionHeader(g2, "EQUIPMENT", leftX, rightX, curY, pulse);

        // Calculate icon size to fill remaining space
        int remainingH = (frameY + frameHeight - 14) - (curY + 14);
        int iconSz = Math.min(72, Math.max(44, remainingH));
        int halfW = contentW / 2;
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13F));
        g2.setColor(new Color(180,200,140)); g2.drawString("Weapon", leftX, curY);
        g2.drawImage(gp.player.currentWeapon.down1, leftX + (halfW - iconSz) / 2, curY + 4, iconSz, iconSz, null);
        g2.setColor(new Color(180,200,140)); g2.drawString("Shield", leftX + halfW, curY);
        g2.drawImage(gp.player.currentShield.down1, leftX + halfW + (halfW - iconSz) / 2, curY + 4, iconSz, iconSz, null);
    }

    /** Draws a section header label with a separator line, returns the Y for the first content row. */
    private int drawSectionHeader(Graphics2D g2, String label, int leftX, int rightX, int y, float pulse) {
        y += 4; // extra top margin before section title
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12F));
        g2.setColor(new Color(160, 140, 100, (int)(140 + 40 * pulse)));
        int labelW = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, leftX, y);
        g2.setColor(new Color(120, 100, 60, 50));
        g2.fillRect(leftX + labelW + 6, y - 4, rightX - leftX - labelW - 6, 1);
        return y + 18;
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

        // DRAW TOOLTIP / DESCRIPTION
        if (itemIndex < gp.player.inventory.size()) {
            Entity item = gp.player.inventory.get(itemIndex);
            if (item != null) {
                drawSubWindow(dFrameX, dFrameY, dFrameWidth, dFrameHeight);
                // Item icon and name
                int iconX = dFrameX + 20;
                int iconY = dFrameY + 20;
                g2.drawImage(item.down1, iconX, iconY, gp.tileSize, gp.tileSize, null);
                g2.setFont(g2.getFont().deriveFont(28F));
                g2.setColor(Color.white);
                g2.drawString(item.name, iconX + gp.tileSize + 10, iconY + gp.tileSize / 2 + 5);

                // Stat comparison for equipment
                int statY = iconY + gp.tileSize / 2 + 24;
                g2.setFont(g2.getFont().deriveFont(18F));
                if (item.type == gp.player.type_sword && item.attackValue != 0) {
                    int diff = item.attackValue - (gp.player.currentWeapon != null ? gp.player.currentWeapon.attackValue : 0);
                    drawStatComparison(iconX + gp.tileSize + 10, statY, "ATK " + item.attackValue, diff, item == gp.player.currentWeapon);
                } else if (item.type == gp.player.type_shield && item.defenseValue != 0) {
                    int diff = item.defenseValue - (gp.player.currentShield != null ? gp.player.currentShield.defenseValue : 0);
                    drawStatComparison(iconX + gp.tileSize + 10, statY, "DEF " + item.defenseValue, diff, item == gp.player.currentShield);
                }

                // Description text
                int textX = dFrameX + 30;
                int textY = iconY + gp.tileSize + 20;
                g2.setFont(g2.getFont().deriveFont(22F));
                g2.setColor(new Color(200, 200, 200));
                if (!item.description.isEmpty()) {
                    for (String line : item.description.split("\n")) {
                        g2.drawString(line, textX, textY);
                        textY += 26;
                    }
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
                    gp.playSE(SFX.MENU_SELECT);
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
                drawMedievalSlider(rightCol, ctrlY, gp.audio.getMusicVolume(), 5);
            }
            else if (i == 3) { // SE volume bar
                drawMedievalSlider(rightCol, ctrlY, gp.audio.getSEVolume(), 5);
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
                    gp.playSE(SFX.MENU_SELECT);
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
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 19F));
        String[] actions = {
            "Move",
            "Attack / Confirm",
            "Shoot",
            "Dodge Roll",
            "Blink",
            "Shockwave",
            "Void Snare",
            "Frost Nova",
            "Overdrive",
            "Inventory",
            "Skill Tree",
            "Quest Log",
            "Minimap",
            "Pause",
            "Options",
            "Debug Tools"
        };
        String[] keys    = {
            "W A S D",
            "ENTER",
            "F",
            "SHIFT + Move",
            "SPACE",
            "Z",
            "X",
            "C",
            "V",
            "E",
            "K",
            "Q",
            "M",
            "P",
            "ESC",
            "T / H / R / Y"
        };
        int textX = frameX + pad;
        int keyX  = frameX + fw - pad;
        int textY = frameY + 92;
        int rowH  = 34;

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

    public void drawLevelUpScreen() {
        int w = 420, h = 340;
        int x = (gp.screenWidth - w) / 2;
        int y = (gp.screenHeight - h) / 2;

        // Dim background with slight vignette feel
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // Main panel with gradient background
        java.awt.GradientPaint bgGrad = new java.awt.GradientPaint(
            x, y, new Color(20, 15, 35, 240),
            x, y + h, new Color(10, 8, 18, 250));
        g2.setPaint(bgGrad);
        g2.fillRoundRect(x, y, w, h, 16, 16);

        // Golden border with glow
        g2.setColor(new Color(255, 200, 60, 40));
        g2.setStroke(new java.awt.BasicStroke(6f));
        g2.drawRoundRect(x - 1, y - 1, w + 2, h + 2, 18, 18);
        g2.setColor(new Color(200, 170, 80));
        g2.setStroke(new java.awt.BasicStroke(2f));
        g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 14, 14);

        // Decorative top accent line
        java.awt.GradientPaint accentGrad = new java.awt.GradientPaint(
            x + 40, y + 8, new Color(255, 200, 60, 0),
            x + w / 2, y + 8, new Color(255, 200, 60, 200));
        g2.setPaint(accentGrad);
        g2.setStroke(new java.awt.BasicStroke(1.5f));
        g2.drawLine(x + 40, y + 8, x + w / 2, y + 8);
        accentGrad = new java.awt.GradientPaint(
            x + w / 2, y + 8, new Color(255, 200, 60, 200),
            x + w - 40, y + 8, new Color(255, 200, 60, 0));
        g2.setPaint(accentGrad);
        g2.drawLine(x + w / 2, y + 8, x + w - 40, y + 8);

        // Title with shadow
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 34f));
        String title = "LEVEL UP";
        int tw = (int) g2.getFontMetrics().getStringBounds(title, g2).getWidth();
        int tx = x + (w - tw) / 2;
        // Shadow
        g2.setColor(new Color(0, 0, 0, 120));
        g2.drawString(title, tx + 2, y + 48);
        // Gold gradient text
        java.awt.GradientPaint titleGrad = new java.awt.GradientPaint(
            tx, y + 20, new Color(255, 230, 120),
            tx, y + 50, new Color(220, 170, 50));
        g2.setPaint(titleGrad);
        g2.drawString(title, tx, y + 46);

        // Level badge
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 16f));
        String lvl = "Lv. " + gp.player.level;
        int lw = (int) g2.getFontMetrics().getStringBounds(lvl, g2).getWidth();
        int badgeX = x + (w - lw - 20) / 2;
        g2.setColor(new Color(255, 200, 60, 25));
        g2.fillRoundRect(badgeX, y + 56, lw + 20, 22, 11, 11);
        g2.setColor(new Color(255, 220, 100, 100));
        g2.setStroke(new java.awt.BasicStroke(1f));
        g2.drawRoundRect(badgeX, y + 56, lw + 20, 22, 11, 11);
        g2.setColor(new Color(255, 220, 130));
        g2.drawString(lvl, badgeX + 10, y + 73);

        // Divider line
        g2.setColor(new Color(120, 100, 60, 80));
        g2.drawLine(x + 30, y + 88, x + w - 30, y + 88);

        // Subtitle
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
        g2.setColor(new Color(180, 170, 150));
        String sub = "Choose a stat to upgrade";
        int sw2 = (int) g2.getFontMetrics().getStringBounds(sub, g2).getWidth();
        g2.drawString(sub, x + (w - sw2) / 2, y + 106);

        // Stat options
        String[] options = gp.player.levelUpOptions;
        if (options == null) return;

        // Stat icons (Unicode symbols)
        String[] icons = {"\u2764", "\u2694", "\u2756"}; // heart, swords, diamond
        Color[] statColors = {
            new Color(230, 80, 80),   // red for HP
            new Color(80, 180, 230),  // blue for ATK/SPD
            new Color(80, 200, 120)   // green for DEF/Mana
        };

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 22f));

        for (int i = 0; i < 3; i++) {
            int oy = y + 118 + i * 58;
            boolean selected = (gp.player.levelUpChoice == i);
            int optW = w - 50;
            int optX = x + 25;
            int optH = 46;

            if (selected) {
                // Glow behind selected option
                g2.setColor(new Color(255, 200, 60, 15));
                g2.fillRoundRect(optX - 4, oy - 4, optW + 8, optH + 8, 14, 14);

                // Selected background gradient
                java.awt.GradientPaint optGrad = new java.awt.GradientPaint(
                    optX, oy, new Color(255, 200, 60, 40),
                    optX + optW, oy, new Color(255, 200, 60, 15));
                g2.setPaint(optGrad);
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);

                // Gold border
                g2.setColor(new Color(255, 210, 80, 180));
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);

                // Left accent bar
                g2.setColor(new Color(255, 210, 80));
                g2.fillRoundRect(optX, oy + 6, 3, optH - 12, 2, 2);
            } else {
                // Unselected: subtle dark background
                g2.setColor(new Color(40, 35, 55, 100));
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);
                g2.setColor(new Color(80, 70, 90, 60));
                g2.setStroke(new java.awt.BasicStroke(1f));
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);
            }

            // Icon circle
            int iconR = 14;
            int iconCX = optX + 24;
            int iconCY = oy + optH / 2;
            Color sc = statColors[i % statColors.length];
            g2.setColor(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), selected ? 60 : 30));
            g2.fillOval(iconCX - iconR, iconCY - iconR, iconR * 2, iconR * 2);
            g2.setColor(selected ? sc : new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), 140));
            g2.setStroke(new java.awt.BasicStroke(1.5f));
            g2.drawOval(iconCX - iconR, iconCY - iconR, iconR * 2, iconR * 2);

            // Icon text
            g2.setFont(g2.getFont().deriveFont(14f));
            g2.setColor(selected ? sc.brighter() : sc);
            int iw = (int) g2.getFontMetrics().getStringBounds(icons[i % icons.length], g2).getWidth();
            g2.drawString(icons[i % icons.length], iconCX - iw / 2, iconCY + 5);

            // Option text
            g2.setFont(g2.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN, 20f));
            g2.setColor(selected ? Color.WHITE : new Color(160, 155, 145));
            g2.drawString(options[i], optX + 50, oy + 30);

            // Small arrow for selected
            if (selected) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
                g2.setColor(new Color(255, 210, 80, 180));
                g2.drawString("\u25B6", optX + optW - 22, oy + 28);
            }
        }

        // Bottom hint with key icons
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
        g2.setColor(new Color(120, 115, 105));
        String hint = "[W/S] Navigate    [Enter] Confirm";
        int hw = (int) g2.getFontMetrics().getStringBounds(hint, g2).getWidth();
        g2.drawString(hint, x + (w - hw) / 2, y + h - 16);
    }

    public void drawLevelUpBanner() {
        if (gp.player.levelUpBannerTimer <= 0 || gp.player.levelUpBannerText == null || gp.player.levelUpBannerText.isEmpty()) {
            return;
        }

        int t = gp.player.levelUpBannerTimer;
        float in = Math.min(1f, (180 - t) / 24f);
        float out = Math.min(1f, t / 24f);
        float alphaScale = Math.min(in, out);
        if (t > 156) alphaScale = in;
        if (t < 24) alphaScale = out;

        float pulse = (float)((Math.sin(animTick * 0.18f) + 1.0) * 0.5);
        int y = gp.screenHeight / 2 - gp.tileSize * 2;
        int panelW = 420;
        int panelH = 56;
        int x = gp.screenWidth / 2 - panelW / 2;

        g2.setColor(new Color(20, 16, 10, (int)(170 * alphaScale)));
        g2.fillRoundRect(x, y, panelW, panelH, 16, 16);

        g2.setColor(new Color(255, 210, 90, (int)((90 + 80 * pulse) * alphaScale)));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x, y, panelW, panelH, 16, 16);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 28f));
        String txt = gp.player.levelUpBannerText;
        int tw = (int)g2.getFontMetrics().getStringBounds(txt, g2).getWidth();
        int tx = gp.screenWidth / 2 - tw / 2;

        g2.setColor(new Color(0, 0, 0, (int)(160 * alphaScale)));
        g2.drawString(txt, tx + 2, y + 37);
        g2.setColor(new Color(255, 230, 130, (int)(255 * alphaScale)));
        g2.drawString(txt, tx, y + 35);
    }

    public void drawSkillTreeScreen() {
        int w = 860;
        int h = 540;
        int x = (gp.screenWidth - w) / 2;
        int y = (gp.screenHeight - h) / 2;

        g2.setColor(new Color(5, 6, 10, 180));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        java.awt.GradientPaint bg = new java.awt.GradientPaint(
            x, y, new Color(18, 14, 10, 242),
            x, y + h, new Color(10, 9, 15, 248));
        g2.setPaint(bg);
        g2.fillRoundRect(x, y, w, h, 18, 18);

        g2.setColor(new Color(220, 175, 80, 120));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 16, 16);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 36f));
        String title = "SKILL TREE";
        int tw = (int) g2.getFontMetrics().getStringBounds(title, g2).getWidth();
        g2.setColor(new Color(255, 220, 120));
        g2.drawString(title, x + (w - tw) / 2, y + 52);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 18f));
        g2.setColor(new Color(190, 210, 255));
        g2.drawString("Skill Points: " + gp.player.skillPoints, x + 26, y + 54);

        SkillTree.SkillNode[] nodes = gp.player.skillTree.getNodes();
        int selected = gp.player.skillTree.selectedIndex;
        SkillTree.SkillNode selectedNode = nodes[selected];
        boolean selectedNodeRevealed = gp.player.skillTree.isRevealed(selected);

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 15f));
        g2.setColor(new Color(210, 195, 165));
        String selectedLabel = selectedNodeRevealed
            ? "Selected: " + selectedNode.name
            : "Selected: Unknown";
        g2.drawString(selectedLabel, x + 26, y + 78);
        int revealMaxCol = gp.player.skillTree.getRevealMaxCol();

        int gridX = x + 170;
        int gridY = y + 150;
        int colSpace = 165;
        int rowSpace = 95;
        int nodeR = 34;

        // Branch labels on the left side
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        String[] branchNames = {"WARRIOR", "ROGUE", "ARCANE"};
        Color[] branchColors = {
            new Color(230, 90, 80),
            new Color(200, 180, 80),
            new Color(100, 160, 255),
        };
        for (int r = 0; r < branchNames.length; r++) {
            g2.setColor(branchColors[r]);
            int ly = gridY + r * rowSpace;
            g2.drawString(branchNames[r], x + 10, ly + 5);
        }

        // Draw links first
        g2.setStroke(new BasicStroke(3f));
        for (SkillTree.SkillNode n : nodes) {
            if (n.col > revealMaxCol) continue;
            if (n.requires == null) continue;
            int pIdx = gp.player.skillTree.findIndexById(n.requires);
            if (pIdx < 0) continue;
            SkillTree.SkillNode p = nodes[pIdx];
            if (p.col > revealMaxCol) continue;

            int x1 = gridX + p.col * colSpace;
            int y1 = gridY + p.row * rowSpace;
            int x2 = gridX + n.col * colSpace;
            int y2 = gridY + n.row * rowSpace;

            if (p.unlocked) g2.setColor(new Color(120, 200, 255, 170));
            else g2.setColor(new Color(90, 80, 70, 130));

            g2.drawLine(x1, y1, x2, y2);
        }

        float pulse = (float)((Math.sin(animTick * 0.16f) + 1.0) * 0.5);

        // Draw nodes
        for (int i = 0; i < nodes.length; i++) {
            SkillTree.SkillNode n = nodes[i];
            int nx = gridX + n.col * colSpace;
            int ny = gridY + n.row * rowSpace;
            boolean revealed = gp.player.skillTree.isRevealed(i);

            boolean canUnlock = gp.player.skillTree.canUnlock(gp.player, i);
            boolean isSelected = (i == selected);

            if (!revealed) {
                g2.setColor(new Color(28, 24, 24, 170));
                g2.fillOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

                if (isSelected) {
                    int glow = (int)(8 + pulse * 6);
                    g2.setColor(new Color(255, 210, 90, 70));
                    g2.fillOval(nx - nodeR - glow, ny - nodeR - glow, (nodeR + glow) * 2, (nodeR + glow) * 2);
                }

                g2.setColor(new Color(80, 70, 65, 120));
                g2.setStroke(new BasicStroke(isSelected ? 3f : 2f));
                g2.drawOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 20f));
                g2.setColor(new Color(120, 110, 100, 180));
                g2.drawString("?", nx - 6, ny + 7);

                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
                g2.setColor(new Color(120, 112, 100, 140));
                g2.drawString("Unknown", nx - 30, ny + nodeR + 20);
                continue;
            }

            if (n.unlocked) {
                g2.setColor(new Color(70, 170, 120, 190));
            } else if (canUnlock) {
                g2.setColor(new Color(140, 120, 70, 180));
            } else {
                g2.setColor(new Color(45, 40, 38, 170));
            }
            g2.fillOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

            if (isSelected) {
                int glow = (int)(8 + pulse * 6);
                g2.setColor(new Color(255, 210, 90, 70));
                g2.fillOval(nx - nodeR - glow, ny - nodeR - glow, (nodeR + glow) * 2, (nodeR + glow) * 2);
            }

            if (n.unlocked) g2.setColor(new Color(120, 240, 170));
            else if (canUnlock) g2.setColor(new Color(250, 210, 110));
            else g2.setColor(new Color(110, 105, 95));
            g2.setStroke(new BasicStroke(isSelected ? 3f : 2f));
            g2.drawOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            String cost = "C" + n.cost;
            int cw = (int)g2.getFontMetrics().getStringBounds(cost, g2).getWidth();
            g2.setColor(new Color(230, 225, 210));
            g2.drawString(cost, nx - cw / 2, ny + 4);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
            g2.setColor(n.unlocked ? new Color(180, 235, 200) : new Color(195, 185, 170));
            int nw = (int)g2.getFontMetrics().getStringBounds(n.name, g2).getWidth();
            g2.drawString(n.name, nx - nw / 2, ny + nodeR + 20);
        }

        SkillTree.SkillNode sel = nodes[selected];
        int infoX = x + 40;
        int infoY = y + h - 140;
        int infoW = w - 80;
        int infoH = 100;

        g2.setColor(new Color(25, 22, 18, 210));
        g2.fillRoundRect(infoX, infoY, infoW, infoH, 12, 12);
        g2.setColor(new Color(140, 120, 85, 120));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(infoX, infoY, infoW, infoH, 12, 12);

        boolean selectedRevealed = gp.player.skillTree.isRevealed(selected);
        if (selectedRevealed) {
            // Branch label
            String[] branches = {"Warrior", "Rogue", "Arcane"};
            Color[] bColors = {new Color(230, 100, 90), new Color(220, 200, 90), new Color(110, 170, 255)};
            int branch = Math.min(sel.row, 2);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            g2.setColor(bColors[branch]);
            g2.drawString(branches[branch] + " Path", infoX + 16, infoY + 18);

            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 22f));
            g2.setColor(new Color(245, 220, 130));
            g2.drawString(sel.name, infoX + 16, infoY + 42);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16f));
            g2.setColor(new Color(210, 205, 190));
            g2.drawString(sel.description, infoX + 16, infoY + 66);

            boolean canUnlockSel = gp.player.skillTree.canUnlock(gp.player, selected);
            String status = sel.unlocked ? "Unlocked" : (canUnlockSel ? "Press ENTER to unlock (" + sel.cost + " pts)" : "Locked (need points/prerequisite)");
            g2.setColor(sel.unlocked ? new Color(130, 220, 150) : (canUnlockSel ? new Color(255, 210, 110) : new Color(150, 145, 130)));
            g2.drawString(status, infoX + 16, infoY + 84);
        } else {
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 22f));
            g2.setColor(new Color(170, 160, 145));
            g2.drawString("Unknown Skill", infoX + 16, infoY + 30);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16f));
            g2.setColor(new Color(145, 138, 125));
            g2.drawString(getHiddenSkillTeaser(sel), infoX + 16, infoY + 56);
            g2.drawString("Advance further to reveal the exact skill.", infoX + 16, infoY + 78);
        }

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 14f));
        g2.setColor(new Color(150, 145, 130));
        String hint = "WASD/Arrows Move  |  Enter Unlock  |  K or Esc Close";
        int hw = (int)g2.getFontMetrics().getStringBounds(hint, g2).getWidth();
        g2.drawString(hint, x + (w - hw) / 2, y + h - 12);
    }

    private String getHiddenSkillTeaser(SkillTree.SkillNode node) {
        return switch (node.id) {
            case "SHOCKWAVE" -> "A close-range burst is waiting in this path.";
            case "VOID_SNARE" -> "A force that drags nearby enemies inward.";
            case "FROST_NOVA" -> "A cold eruption that can freeze enemies.";
            case "OVERDRIVE" -> "A brief surge that boosts speed and damage.";
            case "IRON_WILL" -> "A defensive trait that hardens you in battle.";
            case "WINDSTEP" -> "A mobility art linked to rapid evasive movement.";
            case "PHASE_TUNING" -> "A refinement that improves blink flow.";
            case "QUICK_RECOVERY" -> "Mastery of evasion: faster recovery and movement.";
            case "ARCANE_MASTERY" -> "Deep mana reserves drawn from arcane study.";
            default -> "A hidden skill tied to this branch's next power.";
        };
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

    /** Draw a stat value with colored delta indicator for inventory tooltips. */
    private void drawStatComparison(int x, int y, String label, int diff, boolean equipped) {
        g2.setColor(new Color(180, 180, 180));
        g2.drawString(label, x, y);
        if (equipped) {
            g2.setColor(new Color(240, 190, 90));
            g2.drawString(" (equipped)", x + g2.getFontMetrics().stringWidth(label), y);
        } else if (diff > 0) {
            g2.setColor(new Color(80, 220, 80));
            g2.drawString(" \u25B2+" + diff, x + g2.getFontMetrics().stringWidth(label), y);
        } else if (diff < 0) {
            g2.setColor(new Color(220, 80, 80));
            g2.drawString(" \u25BC" + diff, x + g2.getFontMetrics().stringWidth(label), y);
        }
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

package ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;

import javax.imageio.ImageIO;

import audio.SFX;
import entity.Entity;
import main.Config;
import main.GamePanel;
import main.SkillTree;
import object.OBJ_Heart;
import object.OBJ_Key;
import object.OBJ_ManaCrystal;
import util.UtilityTool;

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
    ArrayList<Integer> messageDuration = new ArrayList<>();
    public boolean gameFinished = false;
    public String currentDialogue = "";
    public int commandNum = 0;
    public int titleScreenState = 0; // 0 : the first screen, 1: the second screen
    public int slotCol = 0;
    public int slotRow = 0;
    public int subState = 0;
    int counter = 0;
    public Entity npc;
    int charIndex = 0;
    public float transitionAlpha = 0f;
    String combinedText = "";

    // ── MULTIPLAYER MENU STATE ──
    public int mpInputField = 0;          // 0=name, 1=ip, 2=port
    public String mpServerName = "";
    public String mpServerIP = "";
    public String mpServerPort = "7777";
    public int mpServerSelection = 0;     // selected index in server list + menu
    public boolean mpInputMode = false;   // true when in text input screen (titleScreenState 4)
    public boolean mpAddMode = false;     // true=add server, false=direct connect
    public String mpStatusMessage = "";   // connection status text

    // ── ANIMATION STATE ──
    private int animTick = 0;          // global UI animation ticker
    private float smoothLife = -1f;    // for smooth health bar interpolation
    private float smoothMana = -1f;    // for smooth mana bar interpolation
    private float smoothExp  = -1f;    // for smooth XP bar interpolation
    private float gameOverAlpha = 0f;  // fade-in for game over screen
    private float pauseAlpha = 0f;     // fade-in for pause overlay

    // ── ACT TITLE CARD ──
    private String actTitleText = null;
    private int actTitleTimer = 0;
    private static final int ACT_TITLE_FADE_IN  = 60;   // 1 sec
    private static final int ACT_TITLE_HOLD     = 120;  // 2 sec
    private static final int ACT_TITLE_FADE_OUT = 60;   // 1 sec
    private static final int ACT_TITLE_TOTAL    = ACT_TITLE_FADE_IN + ACT_TITLE_HOLD + ACT_TITLE_FADE_OUT;

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

    // ── CACHED HUD FONTS — derived once, not per frame ──
    private Font hudFont_bold15, hudFont_bold8, hudFont_bold13;
    private Font hudFont_plain10, hudFont_bold10, hudFont_bold9, hudFont_bold22;
    // ── CACHED STROKES ──
    private static final BasicStroke STROKE_1  = new BasicStroke(1f);
    private static final BasicStroke STROKE_15 = new BasicStroke(1.5f);
    private static final BasicStroke STROKE_2  = new BasicStroke(2f);
    private static final BasicStroke STROKE_25 = new BasicStroke(2.5f);
    private static final BasicStroke STROKE_3  = new BasicStroke(3f);
    private static final BasicStroke STROKE_6  = new BasicStroke(6f);

    // ── BASIC STROKE CACHE — for dynamic stroke widths (pulsing cursors etc.) ──
    private final HashMap<Float, BasicStroke> strokeCache = new HashMap<>();
    private BasicStroke cachedStroke(float width) {
        return strokeCache.computeIfAbsent(width, BasicStroke::new);
    }

    // ── FONT CACHE — eliminates ~80 Font.deriveFont() per frame ──
    private final HashMap<Long, Font> fontCache = new HashMap<>();
    // ── COLOR CACHE — eliminates ~80 cachedColor() per frame (pulse alpha etc.) ──
    private final HashMap<Integer, Color> colorCache = new HashMap<>();
    /** Cached Color lookup — avoids short-lived Color objects every frame. */
    private Color cachedColor(int r, int g, int b, int a) {
        int key = (r << 24) | (g << 16) | (b << 8) | a;
        Color c = colorCache.get(key);
        if (c == null) { c = new Color(r, g, b, a); colorCache.put(key, c); }
        return c;
    }
    private Color cachedColor(int r, int g, int b) {
        return cachedColor(r, g, b, 255);
    }
    // ── FONTMETRICS CACHE — eliminates ~54 getFontMetrics() per frame ──
    private final HashMap<Font, FontMetrics> fmCache = new HashMap<>();
    private FontMetrics cachedFM() {
        Font f = g2.getFont();
        return fmCache.computeIfAbsent(f, k -> g2.getFontMetrics(k));
    }
    private FontMetrics cachedFM(Font font) {
        return fmCache.computeIfAbsent(font, k -> g2.getFontMetrics(k));
    }
    // ── CACHED STAT-BAR COLORS (static, no alpha variation) ──
    private static final Color STAT_BAR_OUTLINE = new Color(200, 200, 220, 25);
    private static final Color STAT_BAR_TIP     = new Color(255, 255, 255, 50);
    // ── HUD PANEL COLORS (static, no alpha variation) ──
    private static final Color HUD_PANEL_BG     = new Color(6, 4, 14, 210);
    private static final Color COIN_BORDER_CLR  = new Color(180, 140, 20);
    private static final Color PILL_BORDER_CLR  = new Color(70, 60, 90, 70);

    // ── CACHED ALPHA COMPOSITES ──
    private static final AlphaComposite ALPHA_OPAQUE = AlphaComposite.SrcOver;
    private static final AlphaComposite ALPHA_070 = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f);

    // ── GRADIENT PAINT CACHE — eliminates ~10 GradientPaint allocations per frame ──
    private final HashMap<Long, java.awt.GradientPaint> gradientCache = new HashMap<>();
    private java.awt.GradientPaint cachedGradient(int x1, int y1, Color c1, int x2, int y2, Color c2) {
        long key = ((long) x1 * 92821 + y1) * 31 + ((long) x2 * 92821 + y2) * 17
                 + c1.hashCode() * 7L + c2.hashCode();
        return gradientCache.computeIfAbsent(key,
            k -> new java.awt.GradientPaint(x1, y1, c1, x2, y2, c2));
    }


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
            titleBackground = ImageIO.read(getClass().getResourceAsStream(getTitleScreenBackgroundImage()));
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

        initHudFonts();
    }

    /** Cached font lookup — avoids expensive deriveFont() every frame. */
    private Font cachedFont(int style, float size) {
        long key = ((long) style << 32) | Float.floatToIntBits(size);
        Font f = fontCache.get(key);
        if (f == null) {
            f = arial_40.deriveFont(style, size);
            fontCache.put(key, f);
        }
        return f;
    }

    private void initHudFonts() {
        float sf = gp.screenWidth / 1280f;
        hudFont_bold15  = arial_40.deriveFont(Font.BOLD,  15f * sf);
        hudFont_bold8   = arial_40.deriveFont(Font.BOLD,   8f * sf);
        hudFont_bold13  = arial_40.deriveFont(Font.BOLD,  13f * sf);
        hudFont_plain10 = arial_40.deriveFont(Font.PLAIN, 10f * sf);
        hudFont_bold10  = arial_40.deriveFont(Font.BOLD,  10f * sf);
        hudFont_bold9   = arial_40.deriveFont(Font.BOLD,   9f * sf);
        hudFont_bold22  = arial_40.deriveFont(Font.BOLD,  22f);
    }

    public void addMessage(String text, Color color) {
        addMessage(text, color, (BufferedImage) null, 180);
    }

    public void addMessage(String text, Color color, int duration) {
        addMessage(text, color, (BufferedImage) null, duration);
    }

    public void addMessage(String text, Color color, BufferedImage icon) {
        addMessage(text, color, icon, 180);
    }

    public void addMessage(String text, Color color, BufferedImage icon, int duration) {
        message.add(text);
        messageColor.add(color);
        messageCounter.add(0);
        messageIcon.add(icon);
        messageDuration.add(Math.max(50, duration));
    }
    public void draw(Graphics2D g2) {

        this.g2 = g2;
    
        // Enable anti-aliasing for smoother text and shapes
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setFont(arial_40);
        g2.setColor(Color.white);

        // Reset animation state when leaving certain screens
        if (gp.gameState != GamePanel.gameOverState) gameOverAlpha = 0f;
        if (gp.gameState != GamePanel.pauseState) pauseAlpha = 0f;

        //TITLE STATE
        if(gp.gameState == GamePanel.titleState) {
            drawTitleScreen();
        }

        //PLAY STATE
        if(gp.gameState == GamePanel.playState) {
            drawPlayerLife();
            drawMessage();
            drawLevelUpBanner();
            drawInteractionPrompt();
            if (gp.thoughts != null) gp.thoughts.draw(g2);
        }

        // PAUSE STATE
        if(gp.gameState == GamePanel.pauseState) {
            drawPlayerLife();
            drawPauseScreen();
        }

        //DIALOGUE STATE
        if(gp.gameState == GamePanel.dialogueState){
            drawPlayerLife();
            drawDialogueScreen();
        }

        // CHARACHTER STATE
        if ( gp.gameState == GamePanel.characterState ) {
            drawCharacterScreen();
            drawInventory();
        }

        // OPTIONS STATE
        if(gp.gameState == GamePanel.optionsState){
            drawOptionsScreen();
        }

        // GAME OVER STATE
        if(gp.gameState == GamePanel.gameOverState){
            drawGameOverScreen();
        }
        // TRANSITION STATE
        if (gp.gameState == GamePanel.transitionState) {
            drawTransition(g2);
        }

        // LEVEL UP STATE
        if (gp.gameState == GamePanel.levelUpState) {
            drawPlayerLife();
            drawLevelUpScreen();
        }

        // SKILL TREE STATE
        if (gp.gameState == GamePanel.skillTreeState) {
            drawPlayerLife();
            drawSkillTreeScreen();
        }

        if ( gp.gameState == GamePanel.cutsceneState ) {
            drawDialogueScreen();
            if (gp.thoughts != null) gp.thoughts.draw(g2);
        }

        // JOURNAL STATE
        if ( gp.gameState == GamePanel.journalState ) {
            drawJournalScreen();
        }

        // ACT TITLE CARD (overlays on top of any game state)
        if (actTitleTimer > 0) {
            drawActTitle();
        }

        if( gameFinished ) {

            g2.setFont(arial_40);
            g2.setColor(Color.white);

            String text;
            int textLenght;
            int x;
            int y;

            text = "To Be Continued ! ";
            textLenght = (int)cachedFM().getStringBounds(text, g2).getWidth();
            x = gp.screenWidth/2 - textLenght/2;
            y = gp.screenHeight/2 - (gp.tileSize*3);
            g2.drawString(text, x, y);

            g2.setFont(arial_80B);
            g2.setColor(Color.blue);
            text = "Congratulations!";
            textLenght = (int)cachedFM().getStringBounds(text, g2).getWidth();
            x = gp.screenWidth/2 - textLenght/2;
            y = gp.screenHeight/2 + (gp.tileSize*2);
            g2.drawString(text, x, y);

            gp.gameThread = null;
        }
        else {

            // AFISEAZA CATE CHEI ARE PLAYER UL
            if(gp.gameState == GamePanel.characterState) {

                g2.setFont(arial_40);
                g2.setColor(Color.white);
                //g2.drawImage(Key, gp.tileSize/2, gp.tileSize/2, gp.tileSize, gp.tileSize, null);
                //g2.drawString("x "+ gp.player.hasKey, 95, 80);
            }
    } 
}
    /** Trigger an act title card to fade in, hold, and fade out. Called from MapManager after map load. */
    public void showActTitle(String title) {
        if (title == null || title.isBlank()) return;
        actTitleText = title;
        actTitleTimer = ACT_TITLE_TOTAL;
    }

    private void drawActTitle() {
        if (actTitleText == null) return;
        int elapsed = ACT_TITLE_TOTAL - actTitleTimer;
        actTitleTimer--;

        // Compute alpha: fade in → hold → fade out
        float alpha;
        if (elapsed < ACT_TITLE_FADE_IN) {
            alpha = (float) elapsed / ACT_TITLE_FADE_IN;
        } else if (elapsed < ACT_TITLE_FADE_IN + ACT_TITLE_HOLD) {
            alpha = 1f;
        } else {
            alpha = 1f - (float)(elapsed - ACT_TITLE_FADE_IN - ACT_TITLE_HOLD) / ACT_TITLE_FADE_OUT;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        java.awt.Composite saved = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        // Semi-transparent dark banner across center
        int bannerH = 80;
        int bannerY = gp.screenHeight / 2 - bannerH / 2;
        g2.setColor(cachedColor(0, 0, 0, 160));
        g2.fillRect(0, bannerY, gp.screenWidth, bannerH);

        // Title text — large serif font, warm cream
        g2.setFont(cachedFont(Font.BOLD | Font.ITALIC, 36F));
        g2.setColor(cachedColor(240, 220, 170));
        int tw = (int) cachedFM().getStringBounds(actTitleText, g2).getWidth();
        int tx = gp.screenWidth / 2 - tw / 2;
        int ty = gp.screenHeight / 2 + 12;
        // Text shadow
        g2.setColor(cachedColor(0, 0, 0, 180));
        g2.drawString(actTitleText, tx + 2, ty + 2);
        // Main text
        g2.setColor(cachedColor(240, 220, 170));
        g2.drawString(actTitleText, tx, ty);

        g2.setComposite(saved);

        if (actTitleTimer <= 0) actTitleText = null;
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
        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(margin, margin, panelW, panelH, 12, 12);
        // Colored top accent line (HP red tint)
        int accentH = (int)(2 * sf);
        g2.setColor(cachedColor(230, 60, 80, (int)(60 + 30 * pulse)));
        g2.fillRoundRect(margin + 4, margin, panelW - 8, accentH, 4, 4);
        // Thin border with faint glow
        g2.setColor(cachedColor(70, 60, 90, (int)(60 + 20 * pulse)));
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(margin, margin, panelW, panelH, 12, 12);

        // ── LEVEL BADGE (rounded, breathing glow) ──
        int badgeSize = (int)(34 * sf);
        int badgeX = margin + (int)(8 * sf);
        int badgeY = margin + (int)(6 * sf);
        // Glow behind badge
        int glowPad = (int)(3 + 2 * pulse);
        g2.setColor(cachedColor(255, 200, 60, (int)(25 + 20 * pulse)));
        g2.fillRoundRect(badgeX - glowPad, badgeY - glowPad, badgeSize + glowPad * 2, badgeSize + glowPad * 2, 12, 12);
        // Badge fill
        g2.setColor(cachedColor(20, 16, 8, 240));
        g2.fillRoundRect(badgeX, badgeY, badgeSize, badgeSize, 10, 10);
        g2.setColor(cachedColor(255, 200, 60, (int)(100 + 40 * pulse)));
        g2.setStroke(STROKE_15);
        g2.drawRoundRect(badgeX, badgeY, badgeSize, badgeSize, 10, 10);
        // Level number
        g2.setFont(hudFont_bold15);
        String lvlStr = String.valueOf(gp.player.level);
        FontMetrics fmLvl = cachedFM();
        int lvlX = badgeX + badgeSize / 2 - fmLvl.stringWidth(lvlStr) / 2;
        int lvlY = badgeY + badgeSize / 2 + fmLvl.getAscent() / 2 - 2;
        g2.setColor(LVL_BADGE);
        g2.drawString(lvlStr, lvlX, lvlY);
        // "LV" tag
        g2.setFont(hudFont_bold8);
        g2.setColor(cachedColor(200, 170, 70, 180));
        String lvLabel = "LV";
        int lvLabelW = cachedFM().stringWidth(lvLabel);
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
            g2.setColor(cachedColor(255, 40, 40, (int)(40 * fastPulse)));
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
        g2.setFont(hudFont_bold8);
        g2.setColor(cachedColor(120, 230, 120, (int)(170 + 40 * pulse)));
        String xpTxt = "XP " + gp.player.exp + "/" + gp.player.nextLevelExp;
        int xpTxtW = cachedFM().stringWidth(xpTxt);
        g2.drawString(xpTxt, xpBarX + xpBarW / 2 - xpTxtW / 2, xpBarY - (int)(2 * sf));

        // ── RIGHT-SIDE INFO STACK ──
        int pillW = (int)(94 * sf);
        int pillH = (int)(28 * sf);
        int pillGap = (int)(5 * sf);
        int pillRX = gp.screenWidth - margin - pillW;
        int pillRY = margin;
        int pillRound = 10;

        // Coin pill
        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        // Gold accent line on left edge
        g2.setColor(cachedColor(255, 210, 50, (int)(100 + 40 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(PILL_BORDER_CLR);
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        // Coin icon
        int coinIconSz = (int)(15 * sf);
        int coinIconX = pillRX + (int)(10 * sf);
        int coinIconY = pillRY + pillH / 2 - coinIconSz / 2;
        g2.setColor(COIN_GOLD);
        g2.fillOval(coinIconX, coinIconY, coinIconSz, coinIconSz);
        g2.setColor(COIN_BORDER_CLR);
        g2.setStroke(STROKE_1);
        g2.drawOval(coinIconX, coinIconY, coinIconSz, coinIconSz);
        // Coin text
        g2.setFont(hudFont_bold13);
        g2.setColor(COIN_GOLD);
        String coinStr = String.valueOf(gp.player.coin);
        int coinTxtX = pillRX + pillW - (int)(9 * sf) - cachedFM().stringWidth(coinStr);
        g2.drawString(coinStr, coinTxtX, pillRY + pillH / 2 + cachedFM().getAscent() / 2 - 1);

        // Inventory pill
        pillRY += pillH + pillGap;
        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setColor(cachedColor(140, 160, 180, (int)(60 + 20 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(PILL_BORDER_CLR);
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setFont(hudFont_plain10);
        g2.setColor(cachedColor(160, 170, 180));
        g2.drawString("Inv", pillRX + (int)(10 * sf), pillRY + pillH / 2 + 4);
        String invStr = gp.player.inventory.size() + "/" + gp.player.maxInventorySize;
        g2.setColor(cachedColor(210, 220, 230));
        int invTxtX = pillRX + pillW - (int)(9 * sf) - cachedFM().stringWidth(invStr);
        g2.drawString(invStr, invTxtX, pillRY + pillH / 2 + 4);

        // SP pill
        pillRY += pillH + pillGap;
        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setColor(cachedColor(255, 200, 60, (int)(80 + 30 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(PILL_BORDER_CLR);
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setFont(hudFont_bold10);
        g2.setColor(cachedColor(255, 225, 120));
        g2.drawString("SP", pillRX + (int)(10 * sf), pillRY + pillH / 2 + 4);
        String spStr = String.valueOf(gp.player.skillPoints);
        int spTxtX = pillRX + pillW - (int)(9 * sf) - cachedFM().stringWidth(spStr);
        g2.drawString(spStr, spTxtX, pillRY + pillH / 2 + 4);

        // ── TELEPORT COOLDOWN ──
        if (gp.teleportation) {
            int tpX = margin;
            int tpY = margin + panelH + (int)(8 * sf);
            int tpW = (int)(145 * sf);
            int tpH = (int)(24 * sf);
            g2.setColor(HUD_PANEL_BG);
            g2.fillRoundRect(tpX, tpY, tpW, tpH, 8, 8);
            // Cyan accent line
            float tpPct = 1f - (float) gp.keyH.teleportCooldown / gp.player.getTeleportCooldownMax();
            g2.setColor(cachedColor(80, 180, 255, (int)(50 + 40 * (tpPct >= 1f ? fastPulse : 0))));
            g2.fillRoundRect(tpX, tpY + 3, (int)(3 * sf), tpH - 6, 3, 3);
            g2.setColor(PILL_BORDER_CLR);
            g2.setStroke(STROKE_1);
            g2.drawRoundRect(tpX, tpY, tpW, tpH, 8, 8);

            int barX = tpX + (int)(8 * sf);
            int barY = tpY + tpH / 2 + (int)(1 * sf);
            int barW2 = tpW - (int)(16 * sf);
            int barH2 = (int)(5 * sf);
            g2.setColor(cachedColor(15, 25, 45, 210));
            g2.fillRoundRect(barX, barY, barW2, barH2, barH2, barH2);
            int fillW2 = (int)(barW2 * tpPct);
            if (fillW2 > 0) {
                Color tpFill = tpPct >= 1f ? cachedColor(80, 200, 255) : cachedColor(50, 110, 170);
                g2.setColor(tpFill);
                g2.fillRoundRect(barX, barY, fillW2, barH2, barH2, barH2);
                if (tpPct >= 1f) {
                    g2.setColor(cachedColor(160, 230, 255, (int)(60 + 40 * fastPulse)));
                    g2.fillRoundRect(barX, barY, fillW2, barH2 / 2, barH2, barH2);
                }
            }
            g2.setFont(hudFont_bold9);
            g2.setColor(tpPct >= 1f ? cachedColor(130, 220, 255, (int)(200 + 55 * fastPulse)) : cachedColor(90, 110, 140));
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
                cachedColor(255, 150, 90));
            abY -= abH + abGap;
        }

        if (gp.player.frostNovaUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Frost Nova",
                true,
                gp.player.getFrostNovaCooldown(),
                gp.player.getFrostNovaCooldownMax(),
                cachedColor(145, 210, 255));
            abY -= abH + abGap;
        }

        if (gp.player.voidSnareUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Void Snare",
                true,
                gp.player.getVoidSnareCooldown(),
                gp.player.getVoidSnareCooldownMax(),
                cachedColor(160, 125, 255));
            abY -= abH + abGap;
        }

        if (gp.player.shockwaveUnlocked) {
            drawAbilityBar(abX, abY, abW, abH,
                "Shockwave",
                true,
                gp.player.getShockwaveCooldown(),
                gp.player.getShockwaveCooldownMax(),
                cachedColor(255, 185, 95));
        }
    }

    private void drawAbilityBar(int x, int y, int w, int h, String name, boolean unlocked, int cooldown, int maxCooldown, Color accent) {
        // Dark panel
        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(x, y, w, h, 8, 8);
        // Colored accent line (left edge)
        float abPulse = (float)((Math.sin(animTick * 0.1) + 1.0) * 0.5);
        boolean ready = unlocked && cooldown <= 0;
        g2.setColor(cachedColor(accent.getRed(), accent.getGreen(), accent.getBlue(),
                (int)(ready ? 90 + 50 * abPulse : 50)));
        g2.fillRoundRect(x, y + 3, 3, h - 6, 3, 3);
        // Border
        g2.setColor(PILL_BORDER_CLR);
        g2.setStroke(STROKE_1);
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
        g2.setColor(cachedColor(15, 12, 25, 200));
        g2.fillRoundRect(barX, barY, barW, barH, 6, 6);
        int fillW = (int)(barW * pct);
        if (fillW > 0) {
            Color fill = unlocked ? accent : cachedColor(60, 60, 70);
            g2.setColor(fill);
            g2.fillRoundRect(barX, barY, fillW, barH, 6, 6);
            // Top glow
            if (pct >= 1f) {
                g2.setColor(cachedColor(accent.getRed(), accent.getGreen(), accent.getBlue(), (int)(60 + 40 * abPulse)));
                g2.fillRoundRect(barX, barY, fillW, barH / 2, 6, 6);
            }
        }

        g2.setFont(hudFont_bold10);
        if (!unlocked) {
            g2.setColor(cachedColor(100, 95, 90));
            g2.drawString(name + " (locked)", x + 8, y + h - 9);
        } else {
            g2.setColor(pct >= 1f ? cachedColor(
                Math.min(255, accent.getRed() + 40),
                Math.min(255, accent.getGreen() + 40),
                Math.min(255, accent.getBlue() + 40),
                (int)(200 + 55 * abPulse)) : cachedColor(180, 175, 165));
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
                g2.setColor(STAT_BAR_TIP);
                g2.fillRoundRect(x + 1 + fillW - tipW, y + 1, tipW, h - 2, h - 2, h - 2);
            }
        }
        // Crisp outline
        g2.setColor(STAT_BAR_OUTLINE);
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(x, y, w, h, h, h);
    }
    public void drawMessage() {

    g2.setFont(hudFont_bold22);
    int totalHeight = 0;

    for (int i = 0; i < message.size(); i++) {

        if (message.get(i) != null) {

            int count = messageCounter.get(i) + 1;
            messageCounter.set(i, count);

            int dur = messageDuration.get(i);
            int fadeStart = dur - 40;

            // Alpha: full 0-fadeStart, fade fadeStart-dur
            int alpha = 255;
            if (count > fadeStart) {
                alpha = 255 - (int)((count - fadeStart) * (255.0 / 40));
                if (alpha < 0) alpha = 0;
            }

            // Slide from left: ease-out over first 12 frames, ease-in slide out over last 40
            int slideOffset = 0;
            if (count < 12) {
                float t = count / 12f;
                slideOffset = (int)((1 - t * t) * 200);
            } else if (count > fadeStart) {
                float t = (count - fadeStart) / 40f;
                slideOffset = (int)(t * t * 200);
            }

            String txt = message.get(i);
            int txtW = (int) cachedFM().getStringBounds(txt, g2).getWidth();
            BufferedImage icon = messageIcon.get(i);
            int iconSpace = icon != null ? 28 : 0;
            int pillW = txtW + iconSpace + 24;
            int pillH = 34;

            // Position: left side of screen
            int px = 16 - slideOffset;
            int py = 300 + totalHeight;

            Color baseColor = messageColor.get(i);

            // Pill background
            g2.setColor(cachedColor(10, 8, 6, (int)(alpha * 0.7f)));
            g2.fillRoundRect(px, py, pillW, pillH, 12, 12);

            // Left accent bar
            g2.setColor(cachedColor(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
            g2.fillRoundRect(px, py, 3, pillH, 3, 3);

            // Icon
            if (icon != null) {
                java.awt.Composite saved = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha / 255f))));
                g2.drawImage(icon, px + 14, py + 3, 24, 24, null);
                g2.setComposite(saved);
            }

            // Text shadow
            g2.setColor(cachedColor(0, 0, 0, alpha));
            g2.drawString(txt, px + 14 + iconSpace + 1, py + 23);

            // Text
            g2.setColor(cachedColor(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
            g2.drawString(txt, px + 14 + iconSpace, py + 22);

            totalHeight += pillH + 6;

            // Remove after dur frames
            if (count > dur) {
                message.remove(i);
                messageCounter.remove(i);
                messageColor.remove(i);
                messageIcon.remove(i);
                messageDuration.remove(i);
                i--;
            }
        }
    }
}

    // ── INTERACTION PROMPT: floating "ENTER" prompt when near an interactable ──
    private int promptBobCounter = 0;
    private static final Font PROMPT_FONT = new Font("Georgia", Font.BOLD, 14);

    public void drawInteractionPrompt() {
        Entity target = gp.nearbyInteractable;
        if (target == null) { promptBobCounter = 0; return; }

        // Determine prompt text (use OBJ_Door.promptText if available)
        String text = "ENTER";
        if (target instanceof object.OBJ_Door door && door.promptText != null) {
            text = door.promptText;
        }

        promptBobCounter++;
        int bob = (int)(Math.sin(promptBobCounter * 0.08) * 3); // gentle bob

        // World-to-screen conversion for the object
        int screenX = target.worldX - gp.player.worldX + gp.player.screenX;
        int screenY = target.worldY - gp.player.worldY + gp.player.screenY;

        g2.setFont(PROMPT_FONT);
        FontMetrics fm = cachedFM(PROMPT_FONT);
        int textW = fm.stringWidth(text);
        int textH = fm.getHeight();

        // Center above the object
        int px = screenX + gp.tileSize / 2 - (textW + 24) / 2;
        int py = screenY - 14 + bob;

        int pillW = textW + 24;
        int pillH = textH + 8;

        // Background pill
        g2.setColor(cachedColor(10, 8, 6, 200));
        g2.fillRoundRect(px, py, pillW, pillH, 10, 10);

        // Border
        g2.setColor(cachedColor(200, 190, 170, 160));
        g2.drawRoundRect(px, py, pillW, pillH, 10, 10);

        // Text shadow
        g2.setColor(cachedColor(0, 0, 0, 200));
        g2.drawString(text, px + 13, py + textH + 1);

        // Text
        g2.setColor(cachedColor(240, 230, 210, 255));
        g2.drawString(text, px + 12, py + textH);
    }

    public void drawTitleScreen() {

        // DRAW BACKGROUND IMAGE
        if (titleBackground != null) {
            g2.drawImage(titleBackground, 0, 0, null);
        } else {
            // Fallback to black background
            g2.setColor(cachedColor(0, 0, 0));
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }

        if(titleScreenState == 0) {
            animTick++;
            float pulse = (float)(0.5 + Math.sin(animTick * 0.04) * 0.5); // 0.0 .. 1.0

            // ── TOP VIGNETTE — darkens top strip so the title pops ──
            g2.setPaint(cachedGradient(0, 0, cachedColor(6, 2, 18, 162), 0, 200, cachedColor(6, 2, 18, 0)));
            g2.fillRect(0, 0, gp.screenWidth, 200);
            // ── BOTTOM VIGNETTE — darkens lower strip for menu legibility ──
            int vigTop = gp.screenHeight - 380;
            g2.setPaint(cachedGradient(0, vigTop, cachedColor(3, 1, 12, 0), 0, gp.screenHeight, cachedColor(3, 1, 12, 185)));
            g2.fillRect(0, vigTop, gp.screenWidth, 380);

            // ── TITLE — Georgia Bold Italic, cream-to-gold gradient ──
            g2.setFont(cachedFont(Font.BOLD | Font.ITALIC, 72F));
            String text = "Echoes of the Heir";
            FontMetrics titleFM = cachedFM();
            int tw = (int) titleFM.getStringBounds(text, g2).getWidth();
            int tx = (gp.screenWidth - tw) / 2;
            int ty = titleFM.getAscent() + 12;
            // Deep violet drop shadow
            g2.setColor(cachedColor(22, 4, 48, 218));
            g2.drawString(text, tx + 3, ty + 3);
            // Warm cream-to-gold gradient
            g2.setPaint(cachedGradient(tx, ty - titleFM.getAscent(), cachedColor(248, 238, 214), tx, ty + titleFM.getDescent(), cachedColor(205, 172, 104)));
            g2.drawString(text, tx, ty);

            // ── ORNAMENTAL RULE — spans the exact width of the title ──
            int ruleY = ty + titleFM.getDescent() + 6;
            int cxHalf = gp.screenWidth / 2;
            g2.setFont(cachedFont(Font.PLAIN, 17F));
            int symAlpha = (int)(115 + pulse * 110);
            g2.setColor(cachedColor(215, 168, 60, symAlpha));
            String sym = "\u2726"; // ✦  four-pointed star
            int symW = (int) cachedFM().getStringBounds(sym, g2).getWidth();
            int symX = cxHalf - symW / 2;
            g2.drawString(sym, symX, ruleY + 7);
            g2.setStroke(STROKE_1);
            // left arm — fades in from title-left edge toward center
            g2.setPaint(cachedGradient(tx, ruleY, cachedColor(182, 143, 66, 0), symX - 6, ruleY, cachedColor(182, 143, 66, 168)));
            g2.drawLine(tx, ruleY, symX - 6, ruleY);
            // right arm — fades out from center to title-right edge
            g2.setPaint(cachedGradient(symX + symW + 6, ruleY, cachedColor(182, 143, 66, 168), tx + tw, ruleY, cachedColor(182, 143, 66, 0)));
            g2.drawLine(symX + symW + 6, ruleY, tx + tw, ruleY);

            // ── SUBTITLE — soft violet-lavender, strong shadow, readable over bright canvas ──
            g2.setFont(cachedFont(Font.ITALIC, 19F));
            String sub = "The Canvas Realm Awaits";
            int sw = (int) cachedFM().getStringBounds(sub, g2).getWidth();
            int subX = (gp.screenWidth - sw) / 2;
            int subY = ruleY + 26;
            g2.setColor(cachedColor(10, 2, 28, 210));
            g2.drawString(sub, subX + 2, subY + 2);
            g2.setColor(cachedColor(195, 162, 232, 240));
            g2.drawString(sub, subX, subY);

            // ── CHARACTER SPRITE — centred over the royal crest ──
            int spriteSize = (int)(gp.tileSize * 2.5f);
            int sx = gp.screenWidth / 2 - spriteSize / 2;
            int sy = (int)(gp.screenHeight * 0.335f);
            // Warm canvas-glow halo below sprite
            int haloA = (int)(14 + pulse * 20);
            g2.setColor(cachedColor(235, 205, 128, haloA));
            g2.fillOval(sx - 24, sy + spriteSize - 20, spriteSize + 48, 30);
            g2.drawImage(gp.player.down1, sx, sy, spriteSize, spriteSize, null);

            // ── MENU — anchored below the heraldic crest (~77% screen height) ──
            String[] menuItems = {"NEW GAME", "LOAD GAME", "MULTIPLAYER", "QUIT"};
            int menuStartY = (int)(gp.screenHeight * 0.77f);
            g2.setFont(cachedFont(Font.BOLD, 27F));

            for (int i = 0; i < menuItems.length; i++) {
                int iy = menuStartY + i * 40;
                boolean sel = (commandNum == i);
                text = menuItems[i];
                tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
                tx = (gp.screenWidth - tw) / 2;

                if (sel) {
                    // Deep violet drop shadow
                    g2.setColor(cachedColor(75, 8, 32, 215));
                    g2.drawString(text, tx + 2, iy + 2);
                    // Cream-to-gold gradient text
                    g2.setPaint(cachedGradient(tx, iy - 22, cachedColor(255, 246, 210), tx, iy + 5, cachedColor(228, 192, 102)));
                    g2.drawString(text, tx, iy);
                    // Magenta/pink animated underline
                    int ulA = (int)(85 + pulse * 108);
                    g2.setColor(cachedColor(232, 52, 118, ulA));
                    g2.setStroke(STROKE_2);
                    g2.drawLine(tx, iy + 6, tx + tw, iy + 6);
                    // Pulsing left-cursor — filled circle
                    int curA = (int)(152 + pulse * 103);
                    int circR = 6;
                    g2.setColor(cachedColor(232, 52, 118, curA));
                    g2.fillOval(tx - 26, iy - circR - 3, circR * 2, circR * 2);
                } else {
                    g2.setColor(cachedColor(163, 148, 118, 195));
                    g2.drawString(text, tx, iy);
                }
            }

            // ── BOTTOM HINTS ──
            g2.setFont(cachedFont(Font.PLAIN, 14F));
            g2.setColor(cachedColor(108, 98, 78, 160));
            g2.drawString("[I] Info & Update Log", 22, gp.screenHeight - 22);
            String ver = Config.getVersionString();
            int vw = (int) cachedFM().getStringBounds(ver, g2).getWidth();
            g2.drawString(ver, gp.screenWidth - vw - 22, gp.screenHeight - 22);
        }
        else if ( titleScreenState == 1) {

            // ── CLASS SELECTION SCREEN ──
            int panelW = 500, panelH = 420;
            int px = (gp.screenWidth - panelW) / 2;
            int py = (gp.screenHeight - panelH) / 2;

            // Dark panel
            g2.setColor(cachedColor(15, 12, 20, 230));
            g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
            g2.setColor(cachedColor(180, 150, 80, 100));
            g2.setStroke(STROKE_2);
            g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

            // Title
            g2.setFont(cachedFont(Font.BOLD, 34F));
            g2.setColor(cachedColor(255, 220, 100));
            String text = "Choose Your Class";
            int tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
            g2.drawString(text, px + (panelW - tw) / 2, py + 48);

            // Divider
            g2.setColor(cachedColor(120, 100, 60, 80));
            g2.drawLine(px + 30, py + 62, px + panelW - 30, py + 62);

            // Class options
            String[] classes = {"Fighter", "Ronin", "Magician"};
            String[] descs = {"High HP, Strong defense", "Fast attacks, High crit", "Powerful magic, High mana"};
            String[] icons = {"\u2694", "\u2620", "\u2733"}; // swords, skull, asterisk
            Color[] classColors = {
                cachedColor(220, 80, 60),
                cachedColor(80, 180, 220),
                cachedColor(160, 80, 220)
            };

            int optY = py + 85;
            int optW = panelW - 60;
            int optH = 60;
            int optX = px + 30;

            for (int i = 0; i < 3; i++) {
                int oy = optY + i * (optH + 14);
                boolean sel = (commandNum == i);

                if (sel) {
                    g2.setColor(cachedColor(classColors[i].getRed(), classColors[i].getGreen(), classColors[i].getBlue(), 25));
                    g2.fillRoundRect(optX - 4, oy - 4, optW + 8, optH + 8, 14, 14);
                    g2.setColor(cachedColor(classColors[i].getRed(), classColors[i].getGreen(), classColors[i].getBlue(), 60));
                    g2.fillRoundRect(optX, oy, optW, optH, 12, 12);
                    g2.setColor(classColors[i]);
                    g2.setStroke(STROKE_2);
                    g2.drawRoundRect(optX, oy, optW, optH, 12, 12);
                    // Left accent
                    g2.fillRoundRect(optX, oy + 8, 3, optH - 16, 2, 2);
                } else {
                    g2.setColor(cachedColor(30, 25, 40, 140));
                    g2.fillRoundRect(optX, oy, optW, optH, 12, 12);
                    g2.setColor(cachedColor(60, 55, 70, 60));
                    g2.setStroke(STROKE_1);
                    g2.drawRoundRect(optX, oy, optW, optH, 12, 12);
                }

                // Icon
                g2.setFont(cachedFont(Font.PLAIN, 22F));
                g2.setColor(sel ? classColors[i] : cachedColor(120, 110, 100));
                g2.drawString(icons[i], optX + 18, oy + 28);

                // Class name
                g2.setFont(cachedFont(sel ? Font.BOLD : Font.PLAIN, 24F));
                g2.setColor(sel ? Color.WHITE : cachedColor(170, 160, 145));
                g2.drawString(classes[i], optX + 50, oy + 28);

                // Description
                g2.setFont(cachedFont(Font.PLAIN, 14F));
                g2.setColor(sel ? cachedColor(200, 190, 170) : cachedColor(120, 115, 105));
                g2.drawString(descs[i], optX + 50, oy + 48);
            }

            // Back option
            int backY = optY + 3 * (optH + 14) + 10;
            boolean backSel = (commandNum == 3);
            g2.setFont(cachedFont(Font.BOLD, 20F));
            text = "\u2190 Back";
            tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
            g2.setColor(backSel ? cachedColor(255, 220, 100) : cachedColor(120, 115, 105));
            g2.drawString(text, px + (panelW - tw) / 2, backY);

            // Hint
            g2.setFont(cachedFont(Font.PLAIN, 13F));
            g2.setColor(cachedColor(100, 95, 85));
            String hint = "[W/S] Navigate    [Enter] Select";
            int hw = (int) cachedFM().getStringBounds(hint, g2).getWidth();
            g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 15);
        }
        else if ( titleScreenState == 2 ) {

            // ── UPDATE LOG / INFO SCREEN ──
            int panelW = 560, panelH = 480;
            int px = (gp.screenWidth - panelW) / 2;
            int py = (gp.screenHeight - panelH) / 2;

            // Dark panel
            g2.setColor(cachedColor(15, 12, 22, 235));
            g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
            g2.setColor(cachedColor(100, 140, 180, 80));
            g2.setStroke(STROKE_2);
            g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

            // Title
            g2.setFont(cachedFont(Font.BOLD, 30F));
            g2.setColor(cachedColor(120, 180, 255));
            String text = "Update Log  \u2022  " + Config.getVersionString();
            int tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
            g2.drawString(text, px + (panelW - tw) / 2, py + 42);

            // Divider
            g2.setPaint(cachedGradient(px + 40, py + 55, cachedColor(100, 140, 180, 0),
                px + panelW / 2, py + 55, cachedColor(100, 140, 180, 120)));
            g2.drawLine(px + 40, py + 55, px + panelW / 2, py + 55);
            g2.setPaint(cachedGradient(px + panelW / 2, py + 55, cachedColor(100, 140, 180, 120),
                px + panelW - 40, py + 55, cachedColor(100, 140, 180, 0)));
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
                cachedColor(255, 200, 80),
                cachedColor(220, 90, 70),
                cachedColor(180, 80, 200),
                cachedColor(255, 220, 100),
                cachedColor(100, 200, 120),
                cachedColor(80, 180, 230),
                cachedColor(255, 200, 80),
                cachedColor(220, 90, 70),
                cachedColor(255, 220, 100),
                cachedColor(100, 200, 120)
            };

            int entryY = py + 78;
            int entryX = px + 40;

            for (int i = 0; i < entries.length; i++) {
                int ey = entryY + i * 34;
                if (ey > py + panelH - 70) break; // don't overflow

                // Bullet/icon
                g2.setFont(cachedFont(Font.PLAIN, 16F));
                g2.setColor(entryColors[i % entryColors.length]);
                g2.drawString(entries[i][0], entryX, ey);

                // Entry text
                g2.setFont(cachedFont(Font.PLAIN, 17F));
                g2.setColor(cachedColor(210, 205, 195));
                g2.drawString(entries[i][1], entryX + 26, ey);
            }

            // Controls info section
            int ctrlY = py + panelH - 80;
            g2.setColor(cachedColor(60, 55, 70, 60));
            g2.fillRoundRect(px + 25, ctrlY, panelW - 50, 40, 10, 10);
            g2.setFont(cachedFont(Font.BOLD, 13F));
            g2.setColor(cachedColor(160, 155, 140));
            g2.drawString("WASD Move | Enter Attack | Space Blink | Shift Roll | Z/X/C/V Skills", px + 40, ctrlY + 25);

            // Back button
            boolean backSel = (commandNum == 0);
            g2.setFont(cachedFont(Font.BOLD, 20F));
            text = "\u2190 Back";
            tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
            g2.setColor(backSel ? cachedColor(120, 180, 255) : cachedColor(100, 95, 85));
            g2.drawString(text, px + (panelW - tw) / 2, py + panelH - 18);
        }
        else if (titleScreenState == 3) {
            drawMultiplayerBrowser();
        }
        else if (titleScreenState == 4) {
            drawMultiplayerInput();
        }
    }
    // ═══════════════════════════════════════════════════════════════
    // MULTIPLAYER BROWSER (titleScreenState 3)
    // ═══════════════════════════════════════════════════════════════
    private void drawMultiplayerBrowser() {
        int panelW = 560, panelH = 480;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        // Dark panel
        g2.setColor(cachedColor(12, 14, 24, 235));
        g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
        g2.setColor(cachedColor(80, 140, 200, 90));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

        // Title
        g2.setFont(cachedFont(Font.BOLD, 32F));
        g2.setColor(cachedColor(100, 180, 255));
        String text = "\u2630  MULTIPLAYER";
        int tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
        g2.drawString(text, px + (panelW - tw) / 2, py + 44);

        // Divider
        g2.setColor(cachedColor(80, 120, 180, 80));
        g2.drawLine(px + 30, py + 58, px + panelW - 30, py + 58);

        // ── Server List ──
        ArrayList<String[]> servers = gp.serverList.getServers();
        int listStartY = py + 78;
        int entryH = 48;
        int entryW = panelW - 60;
        int entryX = px + 30;
        int maxVisible = 5;
        int scrollOffset = Math.max(0, mpServerSelection - maxVisible + 1);
        if (mpServerSelection >= servers.size()) scrollOffset = 0; // cursor on menu items

        if (servers.isEmpty()) {
            g2.setFont(cachedFont(Font.ITALIC, 16F));
            g2.setColor(cachedColor(120, 115, 105));
            String empty = "No saved servers. Add one below!";
            int ew = (int) cachedFM().getStringBounds(empty, g2).getWidth();
            g2.drawString(empty, px + (panelW - ew) / 2, listStartY + 30);
        } else {
            g2.setFont(cachedFont(Font.BOLD, 12F));
            g2.setColor(cachedColor(100, 95, 85));
            g2.drawString("SAVED SERVERS", entryX, listStartY - 6);

            for (int i = scrollOffset; i < Math.min(servers.size(), scrollOffset + maxVisible); i++) {
                String[] srv = servers.get(i);
                int ey = listStartY + (i - scrollOffset) * (entryH + 6);
                boolean sel = (mpServerSelection == i);

                if (sel) {
                    g2.setColor(cachedColor(60, 100, 160, 50));
                    g2.fillRoundRect(entryX - 4, ey - 4, entryW + 8, entryH + 8, 12, 12);
                    g2.setColor(cachedColor(80, 150, 220, 80));
                    g2.fillRoundRect(entryX, ey, entryW, entryH, 10, 10);
                    g2.setColor(cachedColor(100, 180, 255));
                    g2.setStroke(STROKE_2);
                    g2.drawRoundRect(entryX, ey, entryW, entryH, 10, 10);
                    g2.fillRoundRect(entryX, ey + 8, 3, entryH - 16, 2, 2);
                } else {
                    g2.setColor(cachedColor(25, 28, 40, 160));
                    g2.fillRoundRect(entryX, ey, entryW, entryH, 10, 10);
                    g2.setColor(cachedColor(50, 55, 70, 60));
                    g2.setStroke(STROKE_1);
                    g2.drawRoundRect(entryX, ey, entryW, entryH, 10, 10);
                }

                // Server name
                g2.setFont(cachedFont(sel ? Font.BOLD : Font.PLAIN, 18F));
                g2.setColor(sel ? Color.WHITE : cachedColor(180, 175, 160));
                g2.drawString(srv[0], entryX + 16, ey + 22);

                // IP:port
                g2.setFont(cachedFont(Font.PLAIN, 13F));
                g2.setColor(sel ? cachedColor(160, 200, 240) : cachedColor(110, 105, 95));
                g2.drawString(srv[1] + ":" + srv[2], entryX + 16, ey + 40);

                // Online indicator dot
                g2.setColor(cachedColor(80, 200, 100, sel ? 200 : 100));
                g2.fillOval(entryX + entryW - 20, ey + entryH / 2 - 4, 8, 8);
            }
        }

        // ── Menu Options ──
        int menuStartY = listStartY + Math.min(servers.size(), maxVisible) * (entryH + 6) + 20;
        if (servers.isEmpty()) menuStartY = listStartY + 60;

        String[] options = {"ADD SERVER", "DIRECT CONNECT", "BACK"};
        Color[] optColors = {
            cachedColor(80, 200, 120),
            cachedColor(100, 180, 255),
            cachedColor(180, 160, 120)
        };
        int optH = 38;
        int optW = panelW - 100;
        int optX = px + 50;

        for (int i = 0; i < options.length; i++) {
            int idx = servers.size() + i;
            int oy = menuStartY + i * (optH + 8);
            boolean sel = (mpServerSelection == idx);

            if (sel) {
                g2.setColor(cachedColor(optColors[i].getRed(), optColors[i].getGreen(), optColors[i].getBlue(), 20));
                g2.fillRoundRect(optX - 4, oy - 3, optW + 8, optH + 6, 12, 12);
                g2.setColor(cachedColor(optColors[i].getRed(), optColors[i].getGreen(), optColors[i].getBlue(), 50));
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);
                g2.setColor(optColors[i]);
                g2.setStroke(STROKE_2);
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);
            } else {
                g2.setColor(cachedColor(20, 22, 35, 140));
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);
                g2.setColor(cachedColor(50, 50, 65, 60));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);
            }

            g2.setFont(cachedFont(Font.BOLD, 18F));
            text = options[i];
            tw = (int) cachedFM().getStringBounds(text, g2).getWidth();
            g2.setColor(sel ? optColors[i] : cachedColor(140, 135, 120));
            g2.drawString(text, optX + (optW - tw) / 2, oy + optH / 2 + 6);
        }

        // ── Status message ──
        if (gp.mpClient != null && !gp.mpClient.connectionStatus.isEmpty()) {
            g2.setFont(cachedFont(Font.ITALIC, 14F));
            g2.setColor(cachedColor(255, 200, 80, 200));
            String status = gp.mpClient.connectionStatus;
            int sw = (int) cachedFM().getStringBounds(status, g2).getWidth();
            g2.drawString(status, px + (panelW - sw) / 2, py + panelH - 42);
        }

        // ── Hint bar ──
        g2.setFont(cachedFont(Font.PLAIN, 12F));
        g2.setColor(cachedColor(90, 85, 75));
        String hint = "[W/S] Navigate   [Enter] Select/Connect   [Delete] Remove Server";
        int hw = (int) cachedFM().getStringBounds(hint, g2).getWidth();
        g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 16);
    }

    // ═══════════════════════════════════════════════════════════════
    // MULTIPLAYER INPUT SCREEN (titleScreenState 4)
    // ═══════════════════════════════════════════════════════════════
    private void drawMultiplayerInput() {
        int panelW = 480, panelH = 350;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        // Dark panel
        g2.setColor(cachedColor(12, 14, 24, 240));
        g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
        g2.setColor(cachedColor(80, 160, 220, 80));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

        // Title
        g2.setFont(cachedFont(Font.BOLD, 28F));
        g2.setColor(cachedColor(100, 200, 140));
        String title = mpAddMode ? "ADD SERVER" : "DIRECT CONNECT";
        int ttw = (int) cachedFM().getStringBounds(title, g2).getWidth();
        g2.drawString(title, px + (panelW - ttw) / 2, py + 40);

        // Divider
        g2.setColor(cachedColor(80, 160, 120, 60));
        g2.drawLine(px + 30, py + 52, px + panelW - 30, py + 52);

        // ── Input Fields ──
        String[] labels;
        String[] values;
        if (mpAddMode) {
            labels = new String[]{"Name:", "IP Address:", "Port:"};
            values = new String[]{mpServerName, mpServerIP, mpServerPort};
        } else {
            labels = new String[]{"IP Address:", "Port:"};
            values = new String[]{mpServerIP, mpServerPort};
        }

        int fieldX = px + 40;
        int fieldW = panelW - 80;
        int fieldH = 36;
        int fieldStartY = py + 72;

        for (int i = 0; i < labels.length; i++) {
            int fy = fieldStartY + i * (fieldH + 28);
            boolean active = (mpInputField == i);

            // Label
            g2.setFont(cachedFont(Font.BOLD, 14F));
            g2.setColor(active ? cachedColor(120, 200, 160) : cachedColor(150, 145, 130));
            g2.drawString(labels[i], fieldX, fy - 6);

            // Field background
            if (active) {
                g2.setColor(cachedColor(30, 50, 60, 200));
                g2.fillRoundRect(fieldX, fy, fieldW, fieldH, 8, 8);
                g2.setColor(cachedColor(80, 200, 160));
                g2.setStroke(STROKE_2);
                g2.drawRoundRect(fieldX, fy, fieldW, fieldH, 8, 8);
            } else {
                g2.setColor(cachedColor(20, 24, 35, 180));
                g2.fillRoundRect(fieldX, fy, fieldW, fieldH, 8, 8);
                g2.setColor(cachedColor(60, 60, 75, 80));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(fieldX, fy, fieldW, fieldH, 8, 8);
            }

            // Field text + cursor
            g2.setFont(cachedFont(Font.PLAIN, 18F));
            g2.setColor(Color.WHITE);
            String displayText = values[i];
            if (active) {
                // Blinking cursor
                boolean cursorVisible = (animTick / 20) % 2 == 0;
                displayText = values[i] + (cursorVisible ? "|" : "");
            }
            g2.drawString(displayText, fieldX + 10, fy + fieldH / 2 + 6);
        }

        // ── Action buttons ──
        int btnY = fieldStartY + labels.length * (fieldH + 28) + 10;
        String[] buttons;
        if (mpAddMode) {
            buttons = new String[]{"SAVE & CONNECT", "SAVE", "CANCEL"};
        } else {
            buttons = new String[]{"CONNECT", "CANCEL"};
        }

        int btnH = 36;
        int btnW = (panelW - 80 - (buttons.length - 1) * 12) / buttons.length;

        for (int i = 0; i < buttons.length; i++) {
            int bx = fieldX + i * (btnW + 12);
            int fieldCount = mpAddMode ? 3 : 2;
            boolean sel = (mpInputField == fieldCount + i);

            if (sel) {
                g2.setColor(cachedColor(60, 140, 100, 60));
                g2.fillRoundRect(bx, btnY, btnW, btnH, 10, 10);
                g2.setColor(cachedColor(80, 200, 140));
                g2.setStroke(STROKE_2);
                g2.drawRoundRect(bx, btnY, btnW, btnH, 10, 10);
            } else {
                g2.setColor(cachedColor(25, 30, 40, 140));
                g2.fillRoundRect(bx, btnY, btnW, btnH, 10, 10);
                g2.setColor(cachedColor(60, 60, 70, 80));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(bx, btnY, btnW, btnH, 10, 10);
            }

            g2.setFont(cachedFont(Font.BOLD, 14F));
            String bText = buttons[i];
            int btw = (int) cachedFM().getStringBounds(bText, g2).getWidth();
            g2.setColor(sel ? cachedColor(100, 220, 160) : cachedColor(140, 135, 120));
            g2.drawString(bText, bx + (btnW - btw) / 2, btnY + btnH / 2 + 5);
        }

        // ── Hint ──
        g2.setFont(cachedFont(Font.PLAIN, 12F));
        g2.setColor(cachedColor(90, 85, 75));
        String hint = "[Tab] Next field   [Enter] Select   [Esc] Back";
        int hw = (int) cachedFM().getStringBounds(hint, g2).getWidth();
        g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 16);
    }

    public void drawPauseScreen() {

        // Fade in overlay
        if (pauseAlpha < 1f) pauseAlpha += 0.06f;
        if (pauseAlpha > 1f) pauseAlpha = 1f;

        // ── DARK BLUR-LIKE OVERLAY ──
        g2.setColor(cachedColor(8, 8, 15, (int)(160 * pauseAlpha)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // ── SUBTLE BORDER FRAME ──
        int frameInset = gp.tileSize * 3;
        g2.setColor(cachedColor(180, 140, 60, (int)(40 * pauseAlpha)));
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(frameInset, gp.tileSize * 2, gp.screenWidth - frameInset * 2,
                gp.screenHeight - gp.tileSize * 4, 20, 20);

        // ── "PAUSED" TITLE with breathing effect ──
        float breathe = (float)((Math.sin(animTick * 0.04) + 1.0) * 0.5);
        g2.setFont(cachedFont(Font.BOLD, 72F));
        String text = "PAUSED";
        int x = getXforCenteredText(text);
        int y = gp.screenHeight / 2 - gp.tileSize;

        // shadow
        g2.setColor(cachedColor(0, 0, 0, (int)(180 * pauseAlpha)));
        g2.drawString(text, x + 3, y + 3);
        // main text with pulsing alpha
        int textAlpha = (int)((180 + 75 * breathe) * pauseAlpha);
        g2.setColor(cachedColor(220, 210, 190, Math.min(255, textAlpha)));
        g2.drawString(text, x, y);

        // ── DECORATIVE LINES around title ──
        int lineW = gp.tileSize * 4;
        int lineY = y + 14;
        g2.setColor(cachedColor(180, 140, 60, (int)(80 * pauseAlpha)));
        g2.setStroke(STROKE_2);
        // left line
        g2.drawLine(x - lineW - 20, lineY, x - 20, lineY);
        // right line
        int textW = (int) cachedFM().getStringBounds(text, g2).getWidth();
        g2.drawLine(x + textW + 20, lineY, x + textW + lineW + 20, lineY);

        // ── QUICK STATS ──
        g2.setFont(cachedFont(Font.PLAIN, 20F));
        int statsY = y + gp.tileSize + 20;
        String[] quickStats = {
            "Level " + gp.player.level,
            "HP " + gp.player.life + "/" + gp.player.maxLife,
            "Mana " + gp.player.mana + "/" + gp.player.maxMana
        };
        int totalW = 0;
        int gap = gp.tileSize;
        for (String s : quickStats) {
            totalW += (int) cachedFM().getStringBounds(s, g2).getWidth();
        }
        totalW += gap * (quickStats.length - 1);
        int sx = gp.screenWidth / 2 - totalW / 2;
        Color[] statColors = { LVL_BADGE, HP_BAR_FILL, MP_BAR_FILL };
        for (int i = 0; i < quickStats.length; i++) {
            g2.setColor(cachedColor(statColors[i].getRed(), statColors[i].getGreen(),
                    statColors[i].getBlue(), (int)(180 * pauseAlpha)));
            g2.drawString(quickStats[i], sx, statsY);
            sx += (int) cachedFM().getStringBounds(quickStats[i], g2).getWidth() + gap;
        }

        // ── HINT ──
        g2.setFont(cachedFont(Font.PLAIN, 16F));
        g2.setColor(cachedColor(150, 145, 130, (int)(120 * pauseAlpha)));
        String hint = "Press P to resume";
        int hx = getXforCenteredText(hint);
        g2.drawString(hint, hx, gp.screenHeight - gp.tileSize * 2);
    }

    // ── PORTRAIT CACHE — lazy-load NPC portraits ──
    private final HashMap<String, BufferedImage> portraitCache = new HashMap<>();
    private BufferedImage getPortrait(String path) {
        if (path == null) return null;
        BufferedImage img = portraitCache.get(path);
        if (img != null) return img;
        try {
            img = ImageIO.read(getClass().getResourceAsStream(path));
            if (img != null) {
                img = UtilityTool.scaleImage(img, 96, 96);
                portraitCache.put(path, img);
            }
        } catch (Exception e) {
            System.out.println("UI: Failed to load portrait: " + path);
            portraitCache.put(path, null);
        }
        return portraitCache.get(path);
    }

    /** Word-wrap text to fit within maxWidth pixels using the given font. Respects existing \n as hard breaks. */
    private java.util.List<String> wrapText(String text, Font font, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        FontMetrics fm = cachedFM(font);
        for (String paragraph : text.split("\n")) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            String[] words = paragraph.split(" ");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                if (current.length() == 0) {
                    current.append(word);
                } else {
                    String test = current + " " + word;
                    if (fm.stringWidth(test) > maxWidth) {
                        lines.add(current.toString());
                        current = new StringBuilder(word);
                    } else {
                        current.append(" ").append(word);
                    }
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
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
            int nameTagW = (int)(cachedFM(cachedFont(Font.BOLD, 20F))
                    .getStringBounds(npc.name, g2).getWidth()) + 30;
            int nameTagH = 30;
            int nameTagX = x + 16;
            int nameTagY = y - nameTagH + 4;
            // name tag background
            g2.setColor(cachedColor(25, 20, 12, 230));
            g2.fillRoundRect(nameTagX, nameTagY, nameTagW, nameTagH, 10, 10);
            g2.setColor(OPT_BORDER);
            g2.setStroke(STROKE_15);
            g2.drawRoundRect(nameTagX, nameTagY, nameTagW, nameTagH, 10, 10);
            // name text
            g2.setFont(cachedFont(Font.BOLD, 20F));
            g2.setColor(DIALOGUE_NAME);
            g2.drawString(npc.name, nameTagX + 14, nameTagY + 21);
        }

        g2.setFont(cachedFont(Font.PLAIN, 28F));
        x += gp.tileSize;
        y += gp.tileSize;

        // ── NPC PORTRAIT (if available) ──
        int portraitOffset = 0;
        if (npc.portraitPath != null) {
            BufferedImage portrait = getPortrait(npc.portraitPath);
            if (portrait != null) {
                int portraitX = x - 4;
                int portraitY = y - 8;
                // Dark background behind portrait
                g2.setColor(cachedColor(15, 12, 8, 200));
                g2.fillRoundRect(portraitX - 4, portraitY - 4, 104, 104, 8, 8);
                g2.drawImage(portrait, portraitX, portraitY, null);
                // Thin border
                g2.setColor(cachedColor(120, 100, 60, 180));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(portraitX - 4, portraitY - 4, 104, 104, 8, 8);
                portraitOffset = 112;
            }
        }
        int textX = x + portraitOffset;

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

                if ( gp.gameState == GamePanel.dialogueState || gp.gameState == GamePanel.cutsceneState ) {

                        // Choice confirmation: if choices are showing, apply the selected choice
                        if (npc.dialogueChoices != null && npc.dialogueChoices.length > 0) {
                            // Store result key (e.g. "ending" → gp.endingChosen)
                            if ("ending".equals(npc.choiceResultKey)) {
                                gp.endingChosen = npc.selectedChoice + 1; // 1-based
                            }
                            // Jump to the dialogue set mapped to this choice
                            if (npc.choiceNextSet != null && npc.selectedChoice < npc.choiceNextSet.length) {
                                npc.dialogueSet = npc.choiceNextSet[npc.selectedChoice];
                                npc.dialogueIndex = 0;
                            } else {
                                npc.dialogueIndex++;
                            }
                            npc.dialogueChoices = null; // clear choices after confirming
                            npc.selectedChoice = 0;
                        } else {
                            npc.dialogueIndex++;
                        }
                        gp.keyH.enterPressed = false;               
                }
            }
        }
        else { // IF NO TEXT IS IN THE ARRAY
            npc.dialogueIndex = 0;

            if ( gp.gameState == GamePanel.dialogueState ) {
                gp.gameState = GamePanel.playState;
            }
            if ( gp.gameState == GamePanel.cutsceneState ) {
                gp.csManager.scenePhase++;
                npc = null; // prevent dialogue from replaying in later cutscene phases
                return;
            }
        }

        // ── DRAW TEXT with shadow (auto word-wrapped) ──
        Font dialogueFont = cachedFont(Font.PLAIN, 28F);
        g2.setFont(dialogueFont);
        int textMaxWidth = width - gp.tileSize * 2 - portraitOffset - 16;
        java.util.List<String> wrappedLines = wrapText(currentDialogue, dialogueFont, textMaxWidth);
        for (String line : wrappedLines) {
            // text shadow
            g2.setColor(cachedColor(0, 0, 0, 100));
            g2.drawString(line, textX + 2, y + 2);
            // main text
            g2.setColor(cachedColor(230, 225, 215));
            g2.drawString(line, textX, y);
            y += 40;
        }

        // ── BLINKING CONTINUE INDICATOR ──
        if (charIndex >= (npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex] != null ?
                npc.ensureDialogues()[npc.dialogueSet][npc.dialogueIndex].length() : 0)) {
            float blink = (float)((Math.sin(animTick * 0.1) + 1.0) * 0.5);
            int alpha = (int)(80 + 175 * blink);
            g2.setColor(cachedColor(220, 210, 190, alpha));
            g2.setFont(cachedFont(Font.PLAIN, 18F));
            String cont = "\u25BC ENTER";
            int contW = (int) cachedFM().getStringBounds(cont, g2).getWidth();
            int contX = gp.tileSize * 2 + width - gp.tileSize - contW;
            int contY = gp.tileSize / 2 + height - 16;
            g2.drawString(cont, contX, contY);

            // ── CHOICE OPTIONS (if available on this dialogue line) ──
            if (npc.dialogueChoices != null && npc.dialogueChoices.length > 0) {
                drawDialogueChoices(gp.tileSize * 2, gp.tileSize / 2 + height + 8, width);
            }
        }
    }

    // ── CHOICE DIALOGUE OPTIONS ──
    private void drawDialogueChoices(int boxX, int boxY, int boxWidth) {
        if (npc == null || npc.dialogueChoices == null) return;

        int optionH = 36;
        int totalH = npc.dialogueChoices.length * optionH + 20;

        drawSubWindow(boxX, boxY, boxWidth, totalH);

        g2.setFont(cachedFont(Font.PLAIN, 22F));
        int textX = boxX + gp.tileSize;
        int textY = boxY + optionH;

        for (int i = 0; i < npc.dialogueChoices.length; i++) {
            String option = npc.dialogueChoices[i];
            if (option == null) continue;

            // Highlight selected option
            if (i == npc.selectedChoice) {
                g2.setColor(cachedColor(255, 215, 100));
                g2.drawString("\u25B6 ", textX - 20, textY);
                g2.drawString(option, textX, textY);
            } else {
                g2.setColor(cachedColor(180, 175, 165));
                g2.drawString(option, textX, textY);
            }
            textY += optionH;
        }
    }

    // ── MEMORY JOURNAL SCREEN ──
    private int journalScroll = 0;
    public int journalSelectedIndex = 0;

    public void drawJournalScreen() {

        data.MemoryJournal journal = gp.memoryJournal;
        if (journal == null) return;

        // ── FULL-SCREEN BACKDROP ──
        g2.setColor(cachedColor(15, 10, 5, 230));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // ── TITLE ──
        g2.setFont(cachedFont(Font.BOLD, 32F));
        g2.setColor(cachedColor(255, 215, 100));
        String title = "Memory Journal";
        g2.drawString(title, getXforCenteredText(title), 50);

        // ── FRAGMENT COUNTER ──
        g2.setFont(cachedFont(Font.PLAIN, 18F));
        g2.setColor(cachedColor(180, 175, 165));
        String counter = journal.getCount() + " / " + journal.getTotal() + " Memories";
        g2.drawString(counter, getXforCenteredText(counter), 78);

        java.util.List<data.MemoryJournal.MemoryFragment> allFragments = journal.getAllSorted();
        if (allFragments.isEmpty()) {
            g2.setFont(cachedFont(Font.ITALIC, 22F));
            g2.setColor(cachedColor(140, 135, 120));
            String emptyMsg = "No memories collected yet...";
            g2.drawString(emptyMsg, getXforCenteredText(emptyMsg), gp.screenHeight / 2);
            return;
        }

        // ── LEFT PANEL: Fragment list ──
        int panelX = gp.tileSize;
        int panelY = 100;
        int panelW = gp.screenWidth / 3;
        int panelH = gp.screenHeight - 140;
        drawSubWindow(panelX, panelY, panelW, panelH);

        g2.setFont(cachedFont(Font.PLAIN, 18F));
        int listX = panelX + 20;
        int listY = panelY + 30;
        int lineH = 28;

        int maxVisible = (panelH - 40) / lineH;
        if (journalSelectedIndex < journalScroll) journalScroll = journalSelectedIndex;
        if (journalSelectedIndex >= journalScroll + maxVisible) journalScroll = journalSelectedIndex - maxVisible + 1;

        for (int i = journalScroll; i < allFragments.size() && i < journalScroll + maxVisible; i++) {
            data.MemoryJournal.MemoryFragment f = allFragments.get(i);

            if (i == journalSelectedIndex) {
                g2.setColor(cachedColor(255, 215, 100));
                g2.drawString("\u25B6 ", listX - 16, listY);
            }

            if (f.collected) {
                g2.setColor(i == journalSelectedIndex ? cachedColor(255, 235, 180) : cachedColor(210, 200, 180));
                g2.drawString(f.name, listX, listY);
            } else {
                g2.setColor(cachedColor(80, 75, 65));
                g2.drawString("??? (" + f.source + ")", listX, listY);
            }
            listY += lineH;
        }

        // ── RIGHT PANEL: Selected fragment text ──
        int rightX = panelX + panelW + gp.tileSize / 2;
        int rightY = panelY;
        int rightW = gp.screenWidth - rightX - gp.tileSize;
        int rightH = panelH;
        drawSubWindow(rightX, rightY, rightW, rightH);

        if (journalSelectedIndex >= 0 && journalSelectedIndex < allFragments.size()) {
            data.MemoryJournal.MemoryFragment selected = allFragments.get(journalSelectedIndex);

            if (selected.collected && selected.text != null) {
                g2.setFont(cachedFont(Font.BOLD, 22F));
                g2.setColor(cachedColor(255, 215, 100));
                g2.drawString(selected.name, rightX + 20, rightY + 35);

                g2.setFont(cachedFont(Font.ITALIC, 14F));
                g2.setColor(cachedColor(140, 135, 120));
                g2.drawString("Source: " + selected.source, rightX + 20, rightY + 55);

                g2.setFont(cachedFont(Font.PLAIN, 19F));
                g2.setColor(cachedColor(230, 225, 215));
                int textY = rightY + 85;
                for (String line : selected.text) {
                    if (line == null) continue;
                    g2.drawString(line, rightX + 20, textY);
                    textY += 28;
                }
            } else {
                g2.setFont(cachedFont(Font.ITALIC, 20F));
                g2.setColor(cachedColor(100, 95, 85));
                g2.drawString("This memory has not been found yet.", rightX + 20, rightY + 60);
            }
        }

        // ── CONTROLS HINT ──
        g2.setFont(cachedFont(Font.PLAIN, 14F));
        g2.setColor(cachedColor(120, 115, 105));
        g2.drawString("W/S: Navigate    J/ESC: Close", panelX + 20, gp.screenHeight - 20);
    }

    public void drawGameOverScreen() {

        // Fade-in animation
        if (gameOverAlpha < 1f) gameOverAlpha += 0.02f;
        if (gameOverAlpha > 1f) gameOverAlpha = 1f;

        float a = gameOverAlpha;

        // ── DARK OVERLAY — deep charcoal, no red tint ──
        g2.setColor(cachedColor(12, 10, 14, (int)(210 * a)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // ── VIGNETTE EFFECT (soft, dark edges) ──
        int vigAlpha = (int)(120 * a);
        for (int i = 0; i < 5; i++) {
            int va = Math.max(0, vigAlpha - i * 22);
            g2.setColor(cachedColor(0, 0, 0, va));
            int band = gp.tileSize / 2 * (5 - i);
            g2.fillRect(0, 0, gp.screenWidth, band);
            g2.fillRect(0, gp.screenHeight - band, gp.screenWidth, band);
            g2.fillRect(0, 0, band, gp.screenHeight);
            g2.fillRect(gp.screenWidth - band, 0, band, gp.screenHeight);
        }

        // ── HORIZONTAL DIVIDER LINE — thin ember line across center area ──
        float linePulse = (float)((Math.sin(animTick * 0.04) + 1.0) * 0.5);
        int lineAlpha = (int)((40 + 30 * linePulse) * a);
        int lineY = gp.screenHeight / 2 - gp.tileSize;
        int lineMargin = gp.tileSize * 3;
        g2.setColor(cachedColor(180, 120, 80, lineAlpha));
        g2.setStroke(STROKE_1);
        g2.drawLine(lineMargin, lineY, gp.screenWidth - lineMargin, lineY);

        int x;
        int y;
        String text;

        // ── "YOU DIED" TITLE — elegant, muted gold/bone color ──
        float titlePulse = (float)((Math.sin(animTick * 0.035) + 1.0) * 0.5);

        g2.setFont(cachedFont(Font.BOLD, 84f));
        text = "YOU DIED";
        x = getXforCenteredText(text);
        y = gp.screenHeight / 2 - gp.tileSize * 2;

        // soft shadow
        g2.setColor(cachedColor(20, 15, 10, (int)(180 * a)));
        g2.drawString(text, x + 3, y + 3);
        // warm glow layer
        int glowR = (int)(160 + 40 * titlePulse);
        int glowG = (int)(110 + 25 * titlePulse);
        int glowB = (int)(60 + 15 * titlePulse);
        g2.setColor(cachedColor(glowR, glowG, glowB, (int)(80 * a)));
        g2.drawString(text, x + 1, y + 1);
        // main text — warm bone/parchment
        int mainR = (int)(200 + 30 * titlePulse);
        int mainG = (int)(170 + 20 * titlePulse);
        g2.setColor(cachedColor(Math.min(255, mainR), Math.min(255, mainG), 130, (int)(255 * a)));
        g2.drawString(text, x, y);

        // ── SUBTITLE ──
        g2.setFont(cachedFont(Font.PLAIN, 20f));
        g2.setColor(cachedColor(140, 125, 110, (int)(160 * a)));
        String sub = "The echoes fade into silence...";
        int subX = getXforCenteredText(sub);
        g2.drawString(sub, subX, y + 48);

        // ── LOWER DIVIDER LINE ──
        int line2Y = y + 70;
        g2.setColor(cachedColor(180, 120, 80, (int)(30 * a)));
        g2.drawLine(lineMargin, line2Y, gp.screenWidth - lineMargin, line2Y);

        // ── BUTTONS — refined, minimal ──
        g2.setFont(cachedFont(Font.BOLD, 36f));
        String[] opts = {"Retry", "Quit"};
        int buttonW = gp.tileSize * 5;
        int buttonH = (int)(gp.tileSize * 0.9);
        int buttonX = gp.screenWidth / 2 - buttonW / 2;
        y = line2Y + gp.tileSize;

        for (int i = 0; i < opts.length; i++) {
            text = opts[i];
            int btnY = y + i * (buttonH + 20);
            int rectY = btnY - buttonH + 16;
            boolean sel = (commandNum == i);

            if (sel) {
                // warm highlight
                g2.setColor(cachedColor(80, 55, 30, (int)(90 * a)));
                g2.fillRoundRect(buttonX, rectY, buttonW, buttonH, 14, 14);
                g2.setColor(cachedColor(200, 160, 100, (int)(160 * a)));
                g2.setStroke(STROKE_25);
                g2.drawRoundRect(buttonX, rectY, buttonW, buttonH, 14, 14);
                g2.setColor(cachedColor(245, 225, 190, (int)(255 * a)));
            } else {
                // dim muted
                g2.setColor(cachedColor(25, 22, 18, (int)(100 * a)));
                g2.fillRoundRect(buttonX, rectY, buttonW, buttonH, 14, 14);
                g2.setColor(cachedColor(90, 75, 60, (int)(100 * a)));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(buttonX, rectY, buttonW, buttonH, 14, 14);
                g2.setColor(cachedColor(130, 115, 100, (int)(180 * a)));
            }

            int txtX = getXforCenteredText(text);
            g2.drawString(text, txtX, btnY);
        }
    }
    public void drawOptionsScreen() {

        g2.setColor(Color.white);
        g2.setFont(cachedFont(Font.PLAIN, 32F));

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
        g2.setColor(cachedColor(60, 130, 60, (int)(40 + 20 * pulse)));
        g2.fillRoundRect(leftX + (int)leafSway, frameY + 5, contentW, 2, 4, 4);
        g2.fillRoundRect(leftX - (int)leafSway, frameY + frameHeight - 7, contentW, 2, 4, 4);

        int curY = frameY + 26;

        // ── TITLE ──
        g2.setFont(cachedFont(Font.BOLD, 24F));
        String charTitle = "Character";
        int ctW = (int) cachedFM().getStringBounds(charTitle, g2).getWidth();
        int ctX = frameX + frameWidth / 2 - ctW / 2;
        g2.setColor(cachedColor(0, 0, 0, 100));
        g2.drawString(charTitle, ctX + 1, curY + 1);
        g2.setColor(cachedColor(200, 180, 110, (int)(210 + 45 * pulse)));
        g2.drawString(charTitle, ctX, curY);
        curY += 14;

        // ── PORTRAIT + INFO ──
        int portraitSize = 52;
        int portraitX = leftX;
        int portraitY = curY;
        int gPad = (int)(2 + 2 * pulse);
        g2.setColor(cachedColor(120, 180, 80, (int)(30 + 25 * pulse)));
        g2.fillRoundRect(portraitX - gPad - 1, portraitY - gPad - 1, portraitSize + gPad * 2 + 2, portraitSize + gPad * 2 + 2, 10, 10);
        g2.setColor(cachedColor(15, 12, 8, 220));
        g2.fillRoundRect(portraitX - 1, portraitY - 1, portraitSize + 2, portraitSize + 2, 6, 6);
        g2.setColor(cachedColor(100, 160, 70, (int)(80 + 30 * pulse)));
        g2.setStroke(STROKE_15);
        g2.drawRoundRect(portraitX - 1, portraitY - 1, portraitSize + 2, portraitSize + 2, 6, 6);
        g2.drawImage(gp.player.down1, portraitX, portraitY, portraitSize, portraitSize, null);

        int infoX = portraitX + portraitSize + 12;
        g2.setFont(cachedFont(Font.BOLD, 17F));
        g2.setColor(LVL_BADGE);
        g2.drawString("Lv. " + gp.player.level, infoX, portraitY + 18);
        g2.setFont(cachedFont(Font.PLAIN, 12F));
        g2.setColor(cachedColor(160, 180, 140));
        g2.drawString("Adventurer", infoX, portraitY + 33);
        // SP badge
        g2.setFont(cachedFont(Font.BOLD, 10F));
        String spStr = "SP:" + gp.player.skillPoints;
        int spTxtW = cachedFM().stringWidth(spStr);
        g2.setColor(cachedColor(100, 60, 180, (int)(50 + 30 * pulse)));
        g2.fillRoundRect(infoX, portraitY + 38, spTxtW + 8, 14, 6, 6);
        g2.setColor(cachedColor(200, 170, 255));
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
        Color[] cColors = {cachedColor(210,170,90), cachedColor(90,190,140), cachedColor(230,120,60), cachedColor(80,150,210), cachedColor(180,220,130)};
        for (int i = 0; i < cLabels.length; i++) {
            int ry = curY + i * rowH;
            if (i % 2 == 0) { g2.setColor(cachedColor(60,90,40,18)); g2.fillRoundRect(leftX-3, ry-15, contentW+6, rowH-2, 5, 5); }
            g2.setFont(cachedFont(Font.PLAIN, 15F));
            g2.setColor(cColors[i]); g2.drawString(cLabels[i], leftX, ry);
            g2.setColor(cachedColor(235,230,215)); g2.drawString(cValues[i], getXforAlignToRightText(cValues[i], rightX), ry);
        }
        curY += cLabels.length * rowH + sectionGap;

        // ── PROGRESSION ──
        curY = drawSectionHeader(g2, "PROGRESSION", leftX, rightX, curY, pulse);
        g2.setFont(cachedFont(Font.PLAIN, 14F));
        g2.setColor(XP_BAR_FILL); g2.drawString("Exp", leftX, curY);
        String expStr = gp.player.exp + " / " + gp.player.nextLevelExp;
        g2.setColor(cachedColor(235,230,215)); g2.drawString(expStr, getXforAlignToRightText(expStr, rightX), curY);
        drawStatBar(leftX, curY + 5, contentW, 6, gp.player.nextLevelExp > 0 ? (float) gp.player.exp / gp.player.nextLevelExp : 0, XP_BAR_BG, XP_BAR_FILL, XP_BAR_GLOW);
        curY += 22 + sectionGap;

        // ── ITEMS (2-column) ──
        curY = drawSectionHeader(g2, "ITEMS", leftX, rightX, curY, pulse);
        String[] iLabels = {"Coins", "Keys", "Gems", "Artefacts"};
        String[] iValues = {String.valueOf(gp.player.coin), String.valueOf(gp.player.hasKey), String.valueOf(gp.player.hasGem), String.valueOf(gp.player.hasArtefact)};
        Color[] iColors = {COIN_GOLD, cachedColor(200,180,80), cachedColor(80,220,200), cachedColor(220,120,220)};
        g2.setFont(cachedFont(Font.PLAIN, 14F));
        int colW = contentW / 2;
        for (int i = 0; i < iLabels.length; i++) {
            int col = i % 2, row = i / 2;
            int ix = leftX + col * colW, iy = curY + row * 22;
            g2.setColor(iColors[i]); g2.drawString(iLabels[i], ix, iy);
            g2.setColor(cachedColor(235,230,215)); g2.drawString(iValues[i], getXforAlignToRightText(iValues[i], ix + colW - 4), iy);
        }
        curY += ((iLabels.length + 1) / 2) * 22 + sectionGap;

        // ── ABILITIES ──
        curY = drawSectionHeader(g2, "ABILITIES", leftX, rightX, curY, pulse);
        String[] aNames = {"Dash", "Shockwave", "Void Snare", "Frost Nova", "Overdrive"};
        boolean[] aUnlocked = {gp.player.dashUnlocked, gp.player.shockwaveUnlocked, gp.player.voidSnareUnlocked, gp.player.frostNovaUnlocked, gp.player.overdriveUnlocked};
        g2.setFont(cachedFont(Font.BOLD, 11F));
        int abX = leftX;
        for (int i = 0; i < aNames.length; i++) {
            int bw = cachedFM().stringWidth(aNames[i]) + 10;
            if (abX + bw > rightX) { abX = leftX; curY += 18; }
            g2.setColor(aUnlocked[i] ? cachedColor(40,120,60,(int)(80+40*pulse)) : cachedColor(40,40,40,100));
            g2.fillRoundRect(abX, curY - 11, bw, 16, 5, 5);
            g2.setColor(aUnlocked[i] ? cachedColor(100,220,120) : cachedColor(100,100,100,120));
            g2.drawString(aNames[i], abX + 5, curY);
            abX += bw + 4;
        }
        curY += 20 + sectionGap;

        // ── EQUIPMENT ──
        g2.setColor(cachedColor(80,120,50,(int)(50+20*pulse)));
        g2.fillRect(leftX, curY - 8, contentW, 1);
        curY = drawSectionHeader(g2, "EQUIPMENT", leftX, rightX, curY, pulse);

        // Calculate icon size to fill remaining space
        int remainingH = (frameY + frameHeight - 14) - (curY + 14);
        int iconSz = Math.min(72, Math.max(44, remainingH));
        int halfW = contentW / 2;
        g2.setFont(cachedFont(Font.PLAIN, 13F));
        g2.setColor(cachedColor(180,200,140)); g2.drawString("Weapon", leftX, curY);
        if (gp.player.currentWeapon != null) {
            g2.drawImage(gp.player.currentWeapon.down1, leftX + (halfW - iconSz) / 2, curY + 4, iconSz, iconSz, null);
        }
        g2.setColor(cachedColor(180,200,140)); g2.drawString("Shield", leftX + halfW, curY);
        if (gp.player.currentShield != null) {
            g2.drawImage(gp.player.currentShield.down1, leftX + halfW + (halfW - iconSz) / 2, curY + 4, iconSz, iconSz, null);
        }
    }

    /** Draws a section header label with a separator line, returns the Y for the first content row. */
    private int drawSectionHeader(Graphics2D g2, String label, int leftX, int rightX, int y, float pulse) {
        y += 4; // extra top margin before section title
        g2.setFont(cachedFont(Font.BOLD, 12F));
        g2.setColor(cachedColor(160, 140, 100, (int)(140 + 40 * pulse)));
        int labelW = cachedFM().stringWidth(label);
        g2.drawString(label, leftX, y);
        g2.setColor(cachedColor(120, 100, 60, 50));
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
            g2.setFont(cachedFont(Font.PLAIN, 28F));
            // shadow
            g2.setColor(cachedColor(0,0,0,160));
            int tlen = (int)cachedFM().getStringBounds(invTitle, g2).getWidth();
            g2.drawString(invTitle, frameX + (frameWidth/2) - tlen/2 + 3, invTitleY + 3);
            // pulsing gold
            int r = (int)(218 + 37 * pulse);
            int gcol = (int)(165 + 20 * pulse);
            int b = (int)(32 + 8 * pulse);
            g2.setColor(cachedColor(Math.min(255,r), Math.min(255,gcol), Math.min(255,b)));
            g2.drawString(invTitle, frameX + (frameWidth/2) - tlen/2, invTitleY);

            // Occupied size moved under the title (left side)
            String invText = "Occupied: " + gp.player.inventory.size() + " / " + gp.player.maxInventorySize;
            g2.setFont(cachedFont(Font.PLAIN, 16F));
            g2.setColor(cachedColor(200,200,200,220));
            g2.drawString(invText, frameX + 18, invTitleY + 26);

            // header strip inside the window for visual grouping
            Color headerBg = cachedColor(30,30,30,120);
            g2.setColor(headerBg);
            g2.fillRoundRect(frameX + 8, frameY + 10, frameWidth - 16, gp.tileSize - 6, 12, 12);
        final int slotXstart = frameX + 30;
        final int slotYstart = frameY + 30;
        int slotSize = gp.tileSize + 3;
        int maxCol = 5;
        int maxRow = 4;

        // DRAW EMPTY SLOTS
        g2.setColor(cachedColor(50, 50, 50, 150));
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
                g2.setColor(cachedColor(240, 190, 90));
                g2.fillRoundRect(slotX, slotY, gp.tileSize, gp.tileSize, 10, 10);
            }

            g2.drawImage(gp.player.inventory.get(i).down1, slotX, slotY, null);

            // STACKABLE ITEM AMOUNT
            if (gp.player.inventory.get(i).amount > 1) {
                g2.setFont(cachedFont(Font.PLAIN, 32f));
                String s = "" + gp.player.inventory.get(i).amount;
                int amountX = getXforAlignToRightText(s, slotX + 70);
                int amountY = slotY + gp.tileSize;

                // SHADOW
                g2.setColor(cachedColor(60, 60, 60));
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
        g2.setColor(cachedColor(255, 255, 255, 40));
        g2.fillRoundRect(cursorX, cursorY, cursorWidth, cursorHeight, 10, 10);
        // pulsing border for selected slot (quantized to reduce allocations)
        float rawStroke = 2f + 2f * (float)((Math.sin(counter * 0.12) + 1.0) * 0.5f);
        int strokeKey = Math.round(rawStroke * 2); // quantize to 0.5 steps
        g2.setColor(Color.white);
        g2.setStroke(cachedStroke(strokeKey * 0.5f));
        g2.drawRoundRect(cursorX, cursorY, cursorWidth, cursorHeight, 10, 10);

        // HINT STRIP (between inventory frame and description window)
        int itemIndex = getItemIndexOnSlot();
        String actionHint = "";
        if (itemIndex < gp.player.inventory.size()) {
            Entity itemForHint = gp.player.inventory.get(itemIndex);
            if (itemForHint != null) {
                if (itemForHint.type == Entity.TYPE_CONSUMABLE) actionHint = "Use (ENTER)";
                else if (itemForHint.type == Entity.TYPE_SWORD || itemForHint.type == Entity.TYPE_SHIELD || itemForHint.type == Entity.TYPE_BOOK) actionHint = "Equip (ENTER)";
            }
        }

        int hintAreaY = frameY + frameHeight; // directly below inventory frame
        int hintAreaH = gp.tileSize - 8;
        // background strip for hints
        g2.setColor(cachedColor(20,20,20,140));
        g2.fillRoundRect(frameX + 8, hintAreaY + 6, frameWidth - 16, hintAreaH, 12, 12);
        // hints text
        g2.setFont(cachedFont(Font.PLAIN, 18F));
        g2.setColor(cachedColor(200,200,200));
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
                g2.setFont(cachedFont(Font.PLAIN, 28F));
                g2.setColor(Color.white);
                g2.drawString(item.name, iconX + gp.tileSize + 10, iconY + gp.tileSize / 2 + 5);

                // Stat comparison for equipment
                int statY = iconY + gp.tileSize / 2 + 24;
                g2.setFont(cachedFont(Font.PLAIN, 18F));
                if (item.type == Entity.TYPE_SWORD && item.attackValue != 0) {
                    int diff = item.attackValue - (gp.player.currentWeapon != null ? gp.player.currentWeapon.attackValue : 0);
                    drawStatComparison(iconX + gp.tileSize + 10, statY, "ATK " + item.attackValue, diff, item == gp.player.currentWeapon);
                } else if (item.type == Entity.TYPE_SHIELD && item.defenseValue != 0) {
                    int diff = item.defenseValue - (gp.player.currentShield != null ? gp.player.currentShield.defenseValue : 0);
                    drawStatComparison(iconX + gp.tileSize + 10, statY, "DEF " + item.defenseValue, diff, item == gp.player.currentShield);
                }

                // Description text
                int textX = dFrameX + 30;
                int textY = iconY + gp.tileSize + 20;
                g2.setFont(cachedFont(Font.PLAIN, 22F));
                g2.setColor(cachedColor(200, 200, 200));
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
    private static final BasicStroke OPT_STROKE_BORDER = STROKE_3;
    private static final BasicStroke OPT_STROKE_THIN   = STROKE_1;
    private static final BasicStroke OPT_STROKE_SEL    = STROKE_2;

    public void options_top( int frameX, int frameY ) {

        int fw = gp.tileSize * 8;
        int pad = 20;                   // inner padding
        int lineH = 52;                 // row height for menu items
        int rightCol = frameX + fw - pad - 155; // right column for controls/sliders

        // ── TITLE ──
        g2.setFont(cachedFont(Font.BOLD, 36F));
        String title = "Settings";
        int titleW = (int) cachedFM().getStringBounds(title, g2).getWidth();
        int titleX = frameX + fw / 2 - titleW / 2;
        int titleY = frameY + 48;
        // shadow
        g2.setColor(cachedColor(0, 0, 0, 150));
        g2.drawString(title, titleX + 2, titleY + 2);
        // gold text
        g2.setColor(OPT_GOLD);
        g2.drawString(title, titleX, titleY);
        // decorative line under title
        int lineYDeco = titleY + 10;
        g2.setColor(OPT_SEPARATOR);
        g2.fillRect(frameX + pad + 20, lineYDeco, fw - pad * 2 - 40, 2);

        // ── MENU ITEMS ──
        g2.setFont(cachedFont(Font.PLAIN, 26F));
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
                int bw = (int) cachedFM().getStringBounds(labels[i], g2).getWidth();
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
                    gp.config.saveConfig();
                }
            }
            else if (i == 1) { // V-Sync toggle
                drawMedievalToggle(rightCol + 100, ctrlY, gp.vSyncOn);
                if (selected && gp.keyH.enterPressed) {
                    gp.setVSync(!gp.vSyncOn);
                    gp.keyH.enterPressed = false;
                    gp.config.saveConfig();
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
                if (selected && gp.keyH.enterPressed) { gp.gameState = GamePanel.playState; commandNum = 0; gp.config.saveConfig(); }
            }
        }

        // ── SERVER STATUS ──
        int statusY = startY + totalItems * lineH + 10;
        boolean online = gp.saveLoad.isServerOnline();
        g2.setFont(cachedFont(Font.PLAIN, 18F));
        String statusText = "Server: " + (online ? "Online" : "Offline");
        int sw = (int) cachedFM().getStringBounds(statusText, g2).getWidth();
        int dotSize = 8;
        int totalStatusW = dotSize + 6 + sw;
        int cx = frameX + fw / 2 - totalStatusW / 2;
        Color dotColor = online ? cachedColor(60, 200, 60) : cachedColor(200, 60, 60);
        g2.setColor(dotColor);
        g2.fillOval(cx, statusY - dotSize + 2, dotSize, dotSize);
        g2.setColor(OPT_TEXT_DIM);
        g2.drawString(statusText, cx + dotSize + 6, statusY);
        g2.setFont(cachedFont(Font.PLAIN, 26F));

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
        g2.setFont(cachedFont(Font.PLAIN, 20F));
        g2.drawString("\u25B6", x, y);  // ▶ unicode arrow
        g2.setFont(cachedFont(Font.PLAIN, 26F));  // restore
    }
    public void options_fullScreenNotification ( int frameX, int frameY ) {

        int fw = gp.tileSize * 8;
        g2.setFont(cachedFont(Font.BOLD, 30F));
        g2.setColor(OPT_GOLD);
        String noteTitle = "Notice";
        int ntw = (int) cachedFM().getStringBounds(noteTitle, g2).getWidth();
        g2.drawString(noteTitle, frameX + fw / 2 - ntw / 2, frameY + 50);

        g2.setFont(cachedFont(Font.PLAIN, 24F));
        g2.setColor(OPT_TEXT);
        int textX = frameX + 30;
        int textY = frameY + gp.tileSize * 3;

        currentDialogue = "The change will take effect\nafter restarting the game.";

        for ( String line: currentDialogue.split("\n")) {
            g2.drawString(line, textX, textY);
            textY += 36;
        }

        // BACK button centered
        g2.setFont(cachedFont(Font.PLAIN, 26F));
        String back = "Back";
        int bw = (int) cachedFM().getStringBounds(back, g2).getWidth();
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
        g2.setFont(cachedFont(Font.BOLD, 34F));
        g2.setColor(OPT_GOLD);
        String ctrlTitle = "Controls";
        int ctw = (int) cachedFM().getStringBounds(ctrlTitle, g2).getWidth();
        g2.drawString(ctrlTitle, frameX + fw / 2 - ctw / 2, frameY + 48);
        // decorative line
        g2.setColor(OPT_SEPARATOR);
        g2.fillRect(frameX + pad, frameY + 58, fw - pad * 2, 2);

        // Key bindings table
        g2.setFont(cachedFont(Font.PLAIN, 19F));
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
                g2.setColor(cachedColor(40, 35, 25, 60));
                g2.fillRoundRect(frameX + 12, ry - 26, fw - 24, rowH - 4, 8, 8);
            }
            // action label
            g2.setColor(OPT_TEXT);
            g2.drawString(actions[i], textX + 5, ry);
            // key label right-aligned in gold
            g2.setColor(OPT_GOLD_DIM);
            int kw = (int) cachedFM().getStringBounds(keys[i], g2).getWidth();
            g2.drawString(keys[i], keyX - kw - 5, ry);
        }

        // BACK button centered
        g2.setFont(cachedFont(Font.PLAIN, 26F));
        String back = "Back";
        int bw = (int) cachedFM().getStringBounds(back, g2).getWidth();
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
        g2.setFont(cachedFont(Font.BOLD, 30F));
        g2.setColor(cachedColor(200, 80, 60));
        String warn = "Quit Game?";
        int ww = (int) cachedFM().getStringBounds(warn, g2).getWidth();
        g2.drawString(warn, frameX + fw / 2 - ww / 2, frameY + 50);

        g2.setFont(cachedFont(Font.PLAIN, 24F));
        g2.setColor(OPT_TEXT);
        int textX = frameX + 30;
        int textY = frameY + gp.tileSize * 3;

        currentDialogue = "Quit the game and return\nto the title screen?";

        for ( String line: currentDialogue.split("\n")) {
            g2.drawString(line, textX, textY);
            textY += 36;
        }

        // YES / NO buttons
        g2.setFont(cachedFont(Font.PLAIN, 26F));
        String[] opts = { "Yes", "No" };
        int btnY = frameY + gp.tileSize * 6;
        for (int i = 0; i < 2; i++) {
            int bw = (int) cachedFM().getStringBounds(opts[i], g2).getWidth();
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
            gp.gameState = GamePanel.titleState;
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
                gp.gameState = GamePanel.playState; // Return to gameplay
            }
        }

        // Draw the black rectangle with the current alpha
        g2.setColor(cachedColor(0, 0, 0, (int)(transitionAlpha * 255)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
    }

    public void drawLevelUpScreen() {
        int w = 420, h = 340;
        int x = (gp.screenWidth - w) / 2;
        int y = (gp.screenHeight - h) / 2;

        // Dim background with slight vignette feel
        g2.setColor(cachedColor(0, 0, 0, 170));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // Main panel with gradient background
        g2.setPaint(cachedGradient(x, y, cachedColor(20, 15, 35, 240),
            x, y + h, cachedColor(10, 8, 18, 250)));
        g2.fillRoundRect(x, y, w, h, 16, 16);

        // Golden border with glow
        g2.setColor(cachedColor(255, 200, 60, 40));
        g2.setStroke(STROKE_6);
        g2.drawRoundRect(x - 1, y - 1, w + 2, h + 2, 18, 18);
        g2.setColor(cachedColor(200, 170, 80));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 14, 14);

        // Decorative top accent line
        g2.setPaint(cachedGradient(x + 40, y + 8, cachedColor(255, 200, 60, 0),
            x + w / 2, y + 8, cachedColor(255, 200, 60, 200)));
        g2.setStroke(STROKE_15);
        g2.drawLine(x + 40, y + 8, x + w / 2, y + 8);
        g2.setPaint(cachedGradient(x + w / 2, y + 8, cachedColor(255, 200, 60, 200),
            x + w - 40, y + 8, cachedColor(255, 200, 60, 0)));
        g2.drawLine(x + w / 2, y + 8, x + w - 40, y + 8);

        // Title with shadow
        g2.setFont(cachedFont(Font.BOLD, 34f));
        String title = "LEVEL UP";
        int tw = (int) cachedFM().getStringBounds(title, g2).getWidth();
        int tx = x + (w - tw) / 2;
        // Shadow
        g2.setColor(cachedColor(0, 0, 0, 120));
        g2.drawString(title, tx + 2, y + 48);
        // Gold gradient text
        g2.setPaint(cachedGradient(tx, y + 20, cachedColor(255, 230, 120),
            tx, y + 50, cachedColor(220, 170, 50)));
        g2.drawString(title, tx, y + 46);

        // Level badge
        g2.setFont(cachedFont(Font.BOLD, 16f));
        String lvl = "Lv. " + gp.player.level;
        int lw = (int) cachedFM().getStringBounds(lvl, g2).getWidth();
        int badgeX = x + (w - lw - 20) / 2;
        g2.setColor(cachedColor(255, 200, 60, 25));
        g2.fillRoundRect(badgeX, y + 56, lw + 20, 22, 11, 11);
        g2.setColor(cachedColor(255, 220, 100, 100));
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(badgeX, y + 56, lw + 20, 22, 11, 11);
        g2.setColor(cachedColor(255, 220, 130));
        g2.drawString(lvl, badgeX + 10, y + 73);

        // Divider line
        g2.setColor(cachedColor(120, 100, 60, 80));
        g2.drawLine(x + 30, y + 88, x + w - 30, y + 88);

        // Subtitle
        g2.setFont(cachedFont(Font.PLAIN, 14f));
        g2.setColor(cachedColor(180, 170, 150));
        String sub = "Choose a stat to upgrade";
        int sw2 = (int) cachedFM().getStringBounds(sub, g2).getWidth();
        g2.drawString(sub, x + (w - sw2) / 2, y + 106);

        // Stat options
        String[] options = gp.player.levelUpOptions;
        if (options == null) return;

        // Stat icons (Unicode symbols)
        String[] icons = {"\u2764", "\u2694", "\u2756"}; // heart, swords, diamond
        Color[] statColors = {
            cachedColor(230, 80, 80),   // red for HP
            cachedColor(80, 180, 230),  // blue for ATK/SPD
            cachedColor(80, 200, 120)   // green for DEF/Mana
        };

        g2.setFont(cachedFont(Font.PLAIN, 22f));

        for (int i = 0; i < 3; i++) {
            int oy = y + 118 + i * 58;
            boolean selected = (gp.player.levelUpChoice == i);
            int optW = w - 50;
            int optX = x + 25;
            int optH = 46;

            if (selected) {
                // Glow behind selected option
                g2.setColor(cachedColor(255, 200, 60, 15));
                g2.fillRoundRect(optX - 4, oy - 4, optW + 8, optH + 8, 14, 14);

                // Selected background gradient
                g2.setPaint(cachedGradient(optX, oy, cachedColor(255, 200, 60, 40),
                    optX + optW, oy, cachedColor(255, 200, 60, 15)));
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);

                // Gold border
                g2.setColor(cachedColor(255, 210, 80, 180));
                g2.setStroke(STROKE_2);
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);

                // Left accent bar
                g2.setColor(cachedColor(255, 210, 80));
                g2.fillRoundRect(optX, oy + 6, 3, optH - 12, 2, 2);
            } else {
                // Unselected: subtle dark background
                g2.setColor(cachedColor(40, 35, 55, 100));
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);
                g2.setColor(cachedColor(80, 70, 90, 60));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);
            }

            // Icon circle
            int iconR = 14;
            int iconCX = optX + 24;
            int iconCY = oy + optH / 2;
            Color sc = statColors[i % statColors.length];
            g2.setColor(cachedColor(sc.getRed(), sc.getGreen(), sc.getBlue(), selected ? 60 : 30));
            g2.fillOval(iconCX - iconR, iconCY - iconR, iconR * 2, iconR * 2);
            g2.setColor(selected ? sc : cachedColor(sc.getRed(), sc.getGreen(), sc.getBlue(), 140));
            g2.setStroke(STROKE_15);
            g2.drawOval(iconCX - iconR, iconCY - iconR, iconR * 2, iconR * 2);

            // Icon text
            g2.setFont(cachedFont(Font.PLAIN, 14f));
            g2.setColor(selected ? sc.brighter() : sc);
            int iw = (int) cachedFM().getStringBounds(icons[i % icons.length], g2).getWidth();
            g2.drawString(icons[i % icons.length], iconCX - iw / 2, iconCY + 5);

            // Option text
            g2.setFont(cachedFont(selected ? Font.BOLD : Font.PLAIN, 20f));
            g2.setColor(selected ? Color.WHITE : cachedColor(160, 155, 145));
            g2.drawString(options[i], optX + 50, oy + 30);

            // Small arrow for selected
            if (selected) {
                g2.setFont(cachedFont(Font.PLAIN, 12f));
                g2.setColor(cachedColor(255, 210, 80, 180));
                g2.drawString("\u25B6", optX + optW - 22, oy + 28);
            }
        }

        // Bottom hint with key icons
        g2.setFont(cachedFont(Font.PLAIN, 13f));
        g2.setColor(cachedColor(120, 115, 105));
        String hint = "[W/S] Navigate    [Enter] Confirm";
        int hw = (int) cachedFM().getStringBounds(hint, g2).getWidth();
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

        g2.setColor(cachedColor(20, 16, 10, (int)(170 * alphaScale)));
        g2.fillRoundRect(x, y, panelW, panelH, 16, 16);

        g2.setColor(cachedColor(255, 210, 90, (int)((90 + 80 * pulse) * alphaScale)));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(x, y, panelW, panelH, 16, 16);

        g2.setFont(cachedFont(Font.BOLD, 28f));
        String txt = gp.player.levelUpBannerText;
        int tw = (int)cachedFM().getStringBounds(txt, g2).getWidth();
        int tx = gp.screenWidth / 2 - tw / 2;

        g2.setColor(cachedColor(0, 0, 0, (int)(160 * alphaScale)));
        g2.drawString(txt, tx + 2, y + 37);
        g2.setColor(cachedColor(255, 230, 130, (int)(255 * alphaScale)));
        g2.drawString(txt, tx, y + 35);
    }

    public void drawSkillTreeScreen() {
        int w = 860;
        int h = 540;
        int x = (gp.screenWidth - w) / 2;
        int y = (gp.screenHeight - h) / 2;

        g2.setColor(cachedColor(5, 6, 10, 180));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        g2.setPaint(cachedGradient(x, y, cachedColor(18, 14, 10, 242),
            x, y + h, cachedColor(10, 9, 15, 248)));
        g2.fillRoundRect(x, y, w, h, 18, 18);

        g2.setColor(cachedColor(220, 175, 80, 120));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 16, 16);

        g2.setFont(cachedFont(Font.BOLD, 36f));
        String title = "SKILL TREE";
        int tw = (int) cachedFM().getStringBounds(title, g2).getWidth();
        g2.setColor(cachedColor(255, 220, 120));
        g2.drawString(title, x + (w - tw) / 2, y + 52);

        g2.setFont(cachedFont(Font.BOLD, 18f));
        g2.setColor(cachedColor(190, 210, 255));
        g2.drawString("Skill Points: " + gp.player.skillPoints, x + 26, y + 54);

        SkillTree.SkillNode[] nodes = gp.player.skillTree.getNodes();
        int selected = gp.player.skillTree.selectedIndex;
        SkillTree.SkillNode selectedNode = nodes[selected];
        boolean selectedNodeRevealed = gp.player.skillTree.isRevealed(selected);

        g2.setFont(cachedFont(Font.PLAIN, 15f));
        g2.setColor(cachedColor(210, 195, 165));
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
        g2.setFont(cachedFont(Font.BOLD, 13f));
        String[] branchNames = {"WARRIOR", "ROGUE", "ARCANE"};
        Color[] branchColors = {
            cachedColor(230, 90, 80),
            cachedColor(200, 180, 80),
            cachedColor(100, 160, 255),
        };
        for (int r = 0; r < branchNames.length; r++) {
            g2.setColor(branchColors[r]);
            int ly = gridY + r * rowSpace;
            g2.drawString(branchNames[r], x + 10, ly + 5);
        }

        // Draw links first
        g2.setStroke(STROKE_3);
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

            if (p.unlocked) g2.setColor(cachedColor(120, 200, 255, 170));
            else g2.setColor(cachedColor(90, 80, 70, 130));

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
                g2.setColor(cachedColor(28, 24, 24, 170));
                g2.fillOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

                if (isSelected) {
                    int glow = (int)(8 + pulse * 6);
                    g2.setColor(cachedColor(255, 210, 90, 70));
                    g2.fillOval(nx - nodeR - glow, ny - nodeR - glow, (nodeR + glow) * 2, (nodeR + glow) * 2);
                }

                g2.setColor(cachedColor(80, 70, 65, 120));
                g2.setStroke(isSelected ? STROKE_3 : STROKE_2);
                g2.drawOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

                g2.setFont(cachedFont(Font.BOLD, 20f));
                g2.setColor(cachedColor(120, 110, 100, 180));
                g2.drawString("?", nx - 6, ny + 7);

                g2.setFont(cachedFont(Font.PLAIN, 14f));
                g2.setColor(cachedColor(120, 112, 100, 140));
                g2.drawString("Unknown", nx - 30, ny + nodeR + 20);
                continue;
            }

            if (n.unlocked) {
                g2.setColor(cachedColor(70, 170, 120, 190));
            } else if (canUnlock) {
                g2.setColor(cachedColor(140, 120, 70, 180));
            } else {
                g2.setColor(cachedColor(45, 40, 38, 170));
            }
            g2.fillOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

            if (isSelected) {
                int glow = (int)(8 + pulse * 6);
                g2.setColor(cachedColor(255, 210, 90, 70));
                g2.fillOval(nx - nodeR - glow, ny - nodeR - glow, (nodeR + glow) * 2, (nodeR + glow) * 2);
            }

            if (n.unlocked) g2.setColor(cachedColor(120, 240, 170));
            else if (canUnlock) g2.setColor(cachedColor(250, 210, 110));
            else g2.setColor(cachedColor(110, 105, 95));
            g2.setStroke(isSelected ? STROKE_3 : STROKE_2);
            g2.drawOval(nx - nodeR, ny - nodeR, nodeR * 2, nodeR * 2);

            g2.setFont(cachedFont(Font.BOLD, 12f));
            String cost = "C" + n.cost;
            int cw = (int)cachedFM().getStringBounds(cost, g2).getWidth();
            g2.setColor(cachedColor(230, 225, 210));
            g2.drawString(cost, nx - cw / 2, ny + 4);

            g2.setFont(cachedFont(Font.PLAIN, 14f));
            g2.setColor(n.unlocked ? cachedColor(180, 235, 200) : cachedColor(195, 185, 170));
            int nw = (int)cachedFM().getStringBounds(n.name, g2).getWidth();
            g2.drawString(n.name, nx - nw / 2, ny + nodeR + 20);
        }

        SkillTree.SkillNode sel = nodes[selected];
        int infoX = x + 40;
        int infoY = y + h - 140;
        int infoW = w - 80;
        int infoH = 100;

        g2.setColor(cachedColor(25, 22, 18, 210));
        g2.fillRoundRect(infoX, infoY, infoW, infoH, 12, 12);
        g2.setColor(cachedColor(140, 120, 85, 120));
        g2.setStroke(STROKE_15);
        g2.drawRoundRect(infoX, infoY, infoW, infoH, 12, 12);

        boolean selectedRevealed = gp.player.skillTree.isRevealed(selected);
        if (selectedRevealed) {
            // Branch label
            String[] branches = {"Warrior", "Rogue", "Arcane"};
            Color[] bColors = {cachedColor(230, 100, 90), cachedColor(220, 200, 90), cachedColor(110, 170, 255)};
            int branch = Math.min(sel.row, 2);
            g2.setFont(cachedFont(Font.BOLD, 12f));
            g2.setColor(bColors[branch]);
            g2.drawString(branches[branch] + " Path", infoX + 16, infoY + 18);

            g2.setFont(cachedFont(Font.BOLD, 22f));
            g2.setColor(cachedColor(245, 220, 130));
            g2.drawString(sel.name, infoX + 16, infoY + 42);

            g2.setFont(cachedFont(Font.PLAIN, 16f));
            g2.setColor(cachedColor(210, 205, 190));
            g2.drawString(sel.description, infoX + 16, infoY + 66);

            boolean canUnlockSel = gp.player.skillTree.canUnlock(gp.player, selected);
            String status = sel.unlocked ? "Unlocked" : (canUnlockSel ? "Press ENTER to unlock (" + sel.cost + " pts)" : "Locked (need points/prerequisite)");
            g2.setColor(sel.unlocked ? cachedColor(130, 220, 150) : (canUnlockSel ? cachedColor(255, 210, 110) : cachedColor(150, 145, 130)));
            g2.drawString(status, infoX + 16, infoY + 84);
        } else {
            g2.setFont(cachedFont(Font.BOLD, 22f));
            g2.setColor(cachedColor(170, 160, 145));
            g2.drawString("Unknown Skill", infoX + 16, infoY + 30);

            g2.setFont(cachedFont(Font.PLAIN, 16f));
            g2.setColor(cachedColor(145, 138, 125));
            g2.drawString(getHiddenSkillTeaser(sel), infoX + 16, infoY + 56);
            g2.drawString("Advance further to reveal the exact skill.", infoX + 16, infoY + 78);
        }

        g2.setFont(cachedFont(Font.PLAIN, 14f));
        g2.setColor(cachedColor(150, 145, 130));
        String hint = "WASD/Arrows Move  |  Enter Unlock  |  K or Esc Close";
        int hw = (int)cachedFM().getStringBounds(hint, g2).getWidth();
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
        g2.setColor(cachedColor(180, 180, 180));
        g2.drawString(label, x, y);
        if (equipped) {
            g2.setColor(cachedColor(240, 190, 90));
            g2.drawString(" (equipped)", x + cachedFM().stringWidth(label), y);
        } else if (diff > 0) {
            g2.setColor(cachedColor(80, 220, 80));
            g2.drawString(" \u25B2+" + diff, x + cachedFM().stringWidth(label), y);
        } else if (diff < 0) {
            g2.setColor(cachedColor(220, 80, 80));
            g2.drawString(" \u25BC" + diff, x + cachedFM().stringWidth(label), y);
        }
    }

    public int getXforCenteredText(String text) {

        int length = (int)cachedFM().getStringBounds(text, g2).getWidth();
        int x = gp.screenWidth/2 - length/2;
        return x;
    }
    public int getXforAlignToRightText(String text, int tailX) {

        int length = (int)cachedFM().getStringBounds(text, g2).getWidth();
        int x = tailX - length;
        return x;

    }

    public String getTitleScreenBackgroundImage() {
        if((int)(Math.random() * 100) % 2 == 0)
            return "/res/background_royal.png";
        else
            return "/res/background.png";
    }
}
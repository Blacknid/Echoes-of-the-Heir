package ui;

import java.util.ArrayList;
import java.util.HashMap;

import audio.SFX;
import entity.Entity;
import gfx.Color;
import gfx.Font;
import gfx.FontMetrics;
import gfx.GdxRenderer;
import gfx.Gradient;
import gfx.Sprite;
import gfx.Stroke;
import main.Config;
import main.GamePanel;
import main.SkillTree;
import object.OBJ_Heart;
import object.OBJ_Key;
import object.OBJ_ManaCrystal;
import util.ResourceCache;
import util.UtilityTool;

public class UI {

    GamePanel gp;
    GdxRenderer g2;
    Font arial_40, arial_80B;
    Sprite Hearts_Full, Hearts_Empty, Key, Crystal_Full, Crystal_Empty;
    public Sprite Compas;
    public Sprite titleBackground;
    private Sprite titleBackgroundRaw;

    // ── Nine-slice UI panel (palette-swappable) ──────────────────────────────
    // One authored 96x96 texture (/res/ui/UI.png) reused for every window via drawPanel(). The
    // PNG is painted with fixed MARKER colors (one per material role); each drawPanel() call
    // remaps those markers to the real per-window colors via a cached palette-swap bake.
    private Sprite uiPanelRaw; // loaded UI.png, or null → drawPanel falls back to the old vector look
    private Sprite buttonPanelRaw; // loaded Button.png, or null → drawButton falls back to a vector button
    // Recolored Button.png textures keyed by theme (same palette-swap scheme as uiPanelCache).
    private final HashMap<Long, Sprite> buttonPanelCache = new HashMap<>();
    // Marker colors as painted in UI.png. Must match the PNG pixel-for-pixel (no anti-aliasing).
    private static final Color UI_MARK_MAIN   = hex("#D4A877"); // main body
    private static final Color UI_MARK_SHADOW = hex("#C79D70"); // shadow
    private static final Color UI_MARK_HL      = hex("#DFB17D"); // highlight
    private static final Color UI_MARK_HL2     = hex("#CCB980"); // 2nd highlight
    private static final Color[] UI_MARKERS = { UI_MARK_MAIN, UI_MARK_SHADOW, UI_MARK_HL, UI_MARK_HL2 };
    // Cache of recolored textures keyed by the 4 target colors (bake once per unique theme).
    private final HashMap<Long, Sprite> uiPanelCache = new HashMap<>();

    // A window's 4-role color theme for the nine-slice panel. Each role is a plain hex string —
    // to recolor a window, just change its hex codes below. To add a new window, add a THEME_* and
    // pass it to drawPanel(...) at that window's draw. Accepts "#RRGGBB" or "RRGGBB".
    public record PanelTheme(Color main, Color shadow, Color highlight, Color highlight2) {
        static PanelTheme of(String main, String shadow, String highlight, String highlight2) {
            return new PanelTheme(hex(main), hex(shadow), hex(highlight), hex(highlight2));
        }
    }

    /** Parse a "#RRGGBB" (or "RRGGBB") hex color. */
    private static Color hex(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        return new Color(Integer.parseInt(h, 16));
    }

    // Per-window themes — main / shadow / highlight / 2nd-highlight, as hex codes.
    private static final PanelTheme THEME_DEFAULT   = PanelTheme.of("#0F0A08", "#644B1E", "#B48C3C", "#DAAF3E");
    private static final PanelTheme THEME_DIALOGUE  = PanelTheme.of("#0F0A08", "#644B1E", "#B48C3C", "#DAAF3E");
    private static final PanelTheme THEME_JOURNAL   = PanelTheme.of("#120E18", "#46375A", "#9678BE", "#C8AFEB");
    private static final PanelTheme THEME_OPTIONS   = PanelTheme.of("#000000", "#46465A", "#9696AF", "#D2D2E6");
    private static final PanelTheme THEME_CHARACTER = PanelTheme.of("#030202", "#5A3C23", "#AF8246", "#E1B45A");
    private static final PanelTheme THEME_INVENTORY = PanelTheme.of("#0C0C0E", "#3C463C", "#789678", "#B4D7B4");
    private static final PanelTheme THEME_LEVELUP   = PanelTheme.of("#140F23", "#5A4628", "#C8AA50", "#FFC83C");
    private static final PanelTheme THEME_SKILLTREE = PanelTheme.of("#000000", "#503C1E", "#C8A046", "#FFC850");
    private static final PanelTheme THEME_PAUSE     = PanelTheme.of("#0A0810", "#5A4623", "#B48C3C", "#DCAF46");
    private static final PanelTheme THEME_HUD       = PanelTheme.of("#06040E", "#282234", "#463C5A", "#6E5F82");
    public boolean messageOn = false;
    ArrayList<String> message = new ArrayList<>();
    ArrayList<Integer> messageCounter = new ArrayList<>();
    ArrayList<Color> messageColor = new ArrayList<>();
    ArrayList<Sprite> messageIcon = new ArrayList<>();
    ArrayList<Integer> messageDuration = new ArrayList<>();
    public boolean gameFinished = false;
    public String currentDialogue = "";
    public int commandNum = 0;

    /** Whether a save file exists — decides if the title screen shows a "CONTINUE" entry. */
    public boolean titleHasSave() {
        return platform.GameStorage.exists("save.dat");
    }

    /** Title screen main-menu labels, "CONTINUE" prepended only when a save file exists. */
    public String[] titleMenuItems() {
        return titleHasSave()
            ? new String[]{"CONTINUE", "NEW GAME", "MULTIPLAYER", "QUIT"}
            : new String[]{"NEW GAME", "MULTIPLAYER", "QUIT"};
    }
    public int titleScreenState = 0; // 0 : the first screen, 1: the second screen

    // ── Declarative menus (built lazily, cached). Each owns its item list + actions so a button is
    //    declared in ONE place; navigation flows through the Menu instead of hand-mapped indices. ──
    private Menu titleMenu;      // title main menu (state 0) — count changes with save presence
    private boolean titleMenuHadSave; // last titleHasSave() used to build titleMenu; rebuild on change
    private Menu classMenu;      // class select (state 1)
    private Menu gameOverMenu;   // game over (Retry / Quit)
    private Menu optionsMenu;    // options/settings top screen (subState 0)

    /**
     * Title main-menu items + actions, in ONE list. Rebuilt only when save-presence changes (which
     * adds/removes the CONTINUE entry). KeyHandler navigates via this Menu instead of index math.
     */
    public Menu titleMenu() {
        boolean hasSave = titleHasSave();
        if (titleMenu == null || hasSave != titleMenuHadSave) {
            titleMenu = Menu.of(null, THEME_PAUSE).onNavigate(() -> gp.playSE(audio.SFX.MENU_SELECT));
            if (hasSave) {
                titleMenu.button("CONTINUE", () -> { gp.saveLoad.load(); gp.keyH.startGame(); });
            }
            titleMenu.button("NEW GAME",    () -> { titleScreenState = 1; commandNum = 0; });
            titleMenu.button("MULTIPLAYER", () -> {
                titleScreenState = 3;
                mpServerSelection = 0;
                commandNum = 0;
                gp.playSE(audio.SFX.MENU_SELECT);
            });
            titleMenu.button("QUIT", () -> System.exit(0));
            titleMenuHadSave = hasSave;
        }
        return titleMenu;
    }

    /**
     * Class-select items + actions (Fighter / Ronin / Magician / Back), in ONE list. Uses the full
     * windowed {@link Menu#draw} panel look. setPlayerStats MUST run after startNewGame() so class
     * stats land on top of the defaults (see the original inline comment).
     */
    public Menu classMenu() {
        if (classMenu == null) {
            classMenu = Menu.of("Choose Your Class", THEME_DEFAULT)
                .onNavigate(() -> gp.playSE(audio.SFX.MENU_SELECT))
                .button("Fighter",  () -> { gp.keyH.startNewGame(); gp.player.setPlayerStats(4, 2, 1, 4, 3); })
                .button("Ronin",    () -> { gp.keyH.startNewGame(); gp.player.setPlayerStats(2, 3, 3, 5, 2); })
                .button("Magician", () -> { gp.keyH.startNewGame(); gp.player.setPlayerStats(3, 1, 2, 5, 5); })
                .item(MenuItem.button("← Back", () -> { titleScreenState = 0; commandNum = 0; gp.playSE(audio.SFX.MENU_SELECT); }).separator().centered());
        }
        return classMenu;
    }

    public int slotCol = 0;
    public int slotRow = 0;
    public int subState = 0;
    public int controlScroll = 0;
    int counter = 0;
    public Entity npc;
    int charIndex = 0;
    public float transitionAlpha = 0f;
    // OPTIMIZATION: typewriter text accumulator — replaces String += String concatenation
    // (which allocated a fresh String every character). StringBuilder reuses internal char[].
    private final StringBuilder dialogueBuilder = new StringBuilder(256);
    String combinedText = "";

    // OPTIMIZATION: word-wrap cache for the active dialogue line.
    // wrapText() is expensive (FontMetrics.stringWidth per word per frame). The wrapped result
    // is invariant for a given (text, width, font) triple — recompute only when one of those
    // changes. Saves ~1-3ms per frame on long dialogue lines on weak hardware.
    private final java.util.ArrayList<String> dialogueWrapCache = new java.util.ArrayList<>();
    private String dialogueWrapKeyText = null;
    private int    dialogueWrapKeyWidth = -1;
    private Font   dialogueWrapKeyFont  = null;

    public int mpInputField = 0;          // 0=name, 1=ip, 2=port
    public String mpServerName = "";
    public String mpServerIP = "";
    public String mpServerPort = "7777";
    public int mpServerSelection = 0;     // selected index in server list + menu
    public boolean mpInputMode = false;   // true when in text input screen (titleScreenState 4)
    public boolean mpAddMode = false;     // true=add server, false=direct connect
    public String mpStatusMessage = "";   // connection status text

    public String playerUsername = "";    // username set on title screen, shown above player head
    public boolean usernameFieldFocused = false; // true when the username field is being typed into

    private int animTick = 0;          // global UI animation ticker
    private float smoothLife = -1f;    // for smooth health bar interpolation
    private float smoothMana = -1f;    // for smooth mana bar interpolation
    private float smoothExp  = -1f;    // for smooth XP bar interpolation
    private float gameOverAlpha = 0f;  // fade-in for game over screen
    private float pauseAlpha = 0f;     // fade-in for pause overlay

    private String actTitleText = null;
    private int actTitleTimer = 0;
    private static final int ACT_TITLE_FADE_IN  = 60;   // 1 sec
    private static final int ACT_TITLE_HOLD     = 120;  // 2 sec
    private static final int ACT_TITLE_FADE_OUT = 60;   // 1 sec
    private static final int ACT_TITLE_TOTAL    = ACT_TITLE_FADE_IN + ACT_TITLE_HOLD + ACT_TITLE_FADE_OUT;

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
    private static final String[] SKILL_TREE_BRANCH_NAMES = {"Warrior", "Rogue", "Arcane", "Survival"};
    private static final Color[] SKILL_TREE_BRANCH_COLORS = {
        new Color(230, 90, 80),
        new Color(220, 195, 70),
        new Color(90, 155, 255),
        new Color(90, 200, 130)
    };

    private Font hudFont_bold15, hudFont_bold8, hudFont_bold13;
    private Font hudFont_plain10, hudFont_bold10, hudFont_bold9, hudFont_bold22;
    /** Prompt font ("ENTER" floating above interactables) — initialized in initHudFonts(). */
    private Font hudFont_prompt;
    private static final Stroke STROKE_1  = new Stroke(1f);
    private static final Stroke STROKE_15 = new Stroke(1.5f);
    private static final Stroke STROKE_2  = new Stroke(2f);
    private static final Stroke STROKE_25 = new Stroke(2.5f);
    private static final Stroke STROKE_3  = new Stroke(3f);
    private static final Stroke STROKE_6  = new Stroke(6f);
    private static final Stroke STROKE_R12 = new Stroke(1.2f, Stroke.CAP_ROUND, Stroke.JOIN_ROUND);
    private static final Stroke STROKE_R18 = new Stroke(1.8f, Stroke.CAP_ROUND, Stroke.JOIN_ROUND);
    private static final Stroke STROKE_R28 = new Stroke(2.8f, Stroke.CAP_ROUND, Stroke.JOIN_ROUND);

    private final HashMap<Float, Stroke> strokeCache = new HashMap<>();
    private Stroke cachedStroke(float width) {
        return strokeCache.computeIfAbsent(width, Stroke::new);
    }

    private final HashMap<Long, Font> fontCache = new HashMap<>();
    private final HashMap<Integer, Color> colorCache = new HashMap<>();
    /** Cached Color lookup â€” avoids short-lived Color objects every frame. */
    private Color cachedColor(int r, int g, int b, int a) {
        int key = (r << 24) | (g << 16) | (b << 8) | a;
        Color c = colorCache.get(key);
        if (c == null) { c = new Color(r, g, b, a); colorCache.put(key, c); }
        return c;
    }
    private Color cachedColor(int r, int g, int b) {
        return cachedColor(r, g, b, 255);
    }
    private final HashMap<Font, FontMetrics> fmCache = new HashMap<>();
    private FontMetrics cachedFM() {
        Font f = g2.getFont();
        // Safety cap: if the cache somehow grows large (e.g. Font objects created outside
        // the cachedFont() path), clear it so it stays bounded. Normal cap is ~20 entries.
        if (fmCache.size() > 100) fmCache.clear();
        return fmCache.computeIfAbsent(f, k -> g2.getFontMetrics(k));
    }
    private FontMetrics cachedFM(Font font) {
        if (fmCache.size() > 100) fmCache.clear();
        return fmCache.computeIfAbsent(font, k -> g2.getFontMetrics(k));
    }
    private static final Color STAT_BAR_OUTLINE = new Color(200, 200, 220, 25);
    private static final Color STAT_BAR_TIP     = new Color(255, 255, 255, 50);
    private static final Color HUD_PANEL_BG     = new Color(6, 4, 14, 210);
    private static final Color COIN_BORDER_CLR  = new Color(180, 140, 20);
    private static final Color PILL_BORDER_CLR  = new Color(70, 60, 90, 70);

    private final HashMap<Long, Gradient> gradientCache = new HashMap<>();
    private Gradient cachedGradient(int x1, int y1, Color c1, int x2, int y2, Color c2) {
        long key = ((long) x1 * 92821 + y1) * 31 + ((long) x2 * 92821 + y2) * 17
                 + c1.hashCode() * 7L + c2.hashCode();
        return gradientCache.computeIfAbsent(key,
            k -> new Gradient(x1, y1, c1, x2, y2, c2));
    }

    // Replaces Math.sin(animTick * k) calls in every draw method.
    // All pulse animations do: sinLUT[(animTick * SPEED) & 511]  → range -1..1
    // The table is built once at class-load time; lookup is a single array access + int mask.
    private static final int SIN_LUT_SIZE = 512;
    private static final float[] sinLUT = new float[SIN_LUT_SIZE];
    static {
        for (int i = 0; i < SIN_LUT_SIZE; i++) {
            sinLUT[i] = (float) Math.sin(i * (2.0 * Math.PI / SIN_LUT_SIZE));
        }
    }
    /** Fast sin lookup. {@code freq} controls speed: 1=slowest, higher=faster. Result is -1..1. */
    private static float fastSin(int tick, int freq) {
        return sinLUT[(tick * freq) & (SIN_LUT_SIZE - 1)];
    }
    /** Convenience: returns (sin+1)/2 → range 0..1 (pulse form). */
    private static float fastPulse(int tick, int freq) {
        return (fastSin(tick, freq) + 1.0f) * 0.5f;
    }

    private int[] skillTreeNodeX = new int[0];
    private int[] skillTreeNodeY = new int[0];
    private int[] skillTreeReqIndex = new int[0];
    private boolean[] skillTreeRevealed = new boolean[0];
    private boolean[] skillTreeCanUnlock = new boolean[0];

    // atan2/cos/sin per connector is expensive. The graph layout is static (node positions
    // never change) so we compute all endpoint/arrow geometry once and reuse it every frame.
    private int[]   stConnLx1 = new int[0], stConnLy1 = new int[0];
    private int[]   stConnLx2 = new int[0], stConnLy2 = new int[0];
    private int[][] stConnArrowX = new int[0][];
    private int[][] stConnArrowY = new int[0][];
    private boolean[] stConnHasArrow = new boolean[0];
    private boolean stConnGeomValid = false;
    private int     stConnGeomScrollPx = Integer.MIN_VALUE;

    private void ensureSkillTreeConnectorCache(int size) {
        if (stConnLx1.length < size) {
            stConnLx1 = new int[size]; stConnLy1 = new int[size];
            stConnLx2 = new int[size]; stConnLy2 = new int[size];
            stConnArrowX = new int[size][3]; stConnArrowY = new int[size][3];
            stConnHasArrow = new boolean[size];
        }
    }

    /** Call after any skill unlock to force the connector arrow cache to rebuild next frame. */
    public void invalidateSkillTreeConnectorCache() {
        stConnGeomValid = false;
    }

    private void ensureSkillTreeCacheCapacity(int size) {
        if (skillTreeNodeX.length >= size) {
            return;
        }
        skillTreeNodeX = new int[size];
        skillTreeNodeY = new int[size];
        skillTreeReqIndex = new int[size];
        skillTreeRevealed = new boolean[size];
        skillTreeCanUnlock = new boolean[size];
    }


    public UI(GamePanel gp) {
        this.gp = gp;

        Font pixelBase;
        try {
            pixelBase = Font.createFont(Font.TRUETYPE_FONT,
                    ResourceCache.openClasspathStream("/res/fonts/Pixeloid Sans.ttf"),
                    "Pixeloid Sans");
        } catch (Exception e) {
            System.out.println("[UI] Pixeloid Sans font not found, falling back to Segoe UI");
            pixelBase = new Font("Segoe UI", Font.PLAIN, 12);
        }
        arial_40 = pixelBase.deriveFont(Font.PLAIN, 40);
        arial_80B = pixelBase.deriveFont(Font.BOLD, 80);

        Entity heart = new OBJ_Heart(gp);
        Hearts_Full = heart.image;
        Hearts_Empty = heart.image1;

        Entity crystal = new OBJ_ManaCrystal(gp);
        Crystal_Full = crystal.image2;
        Crystal_Empty = crystal.image1;
        

        Entity key = new OBJ_Key(gp);
        Key = key.down1;

        try {
            titleBackgroundRaw = ResourceCache.loadImageIfPresent(getTitleScreenBackgroundImage());
            if (titleBackgroundRaw != null) {
                titleBackground = UtilityTool.scaleImage(titleBackgroundRaw, gp.screenWidth, gp.screenHeight);
                System.out.println("Title background loaded successfully!");
            } else {
                System.out.println("Title background file found but could not be loaded");
            }

            // Nine-slice UI panel texture (optional; drawPanel falls back to vector panels if absent).
            uiPanelRaw = ResourceCache.loadImageIfPresent("/res/ui/UI.png");
            if (uiPanelRaw != null) {
                System.out.println("UI panel texture loaded (" + uiPanelRaw.nativeWidth()
                        + "x" + uiPanelRaw.nativeHeight() + ")");
            }

            // Nine-slice button texture (optional; drawButton falls back to a vector button if absent).
            buttonPanelRaw = ResourceCache.loadImageIfPresent("/res/ui/Button.png");
            if (buttonPanelRaw != null) {
                System.out.println("UI button texture loaded (" + buttonPanelRaw.nativeWidth()
                        + "x" + buttonPanelRaw.nativeHeight() + ")");
            }
        } catch(Exception e) {
            System.out.println("Title background not found at /res/background.png, using default black background");
            e.printStackTrace();
        }

        initHudFonts();
    }

    /** Cached font lookup â€” avoids expensive deriveFont() every frame. */
    private Font cachedFont(int style, float size) {
        // Round to integer size — keeps pixel fonts crisp (avoids sub-pixel rounding blur).
        float roundedSize = (float) Math.round(size);
        long key = ((long) style << 32) | Float.floatToIntBits(roundedSize);
        Font f = fontCache.get(key);
        if (f == null) {
            f = arial_40.deriveFont(style, roundedSize);
            fontCache.put(key, f);
        }
        return f;
    }

    private void initHudFonts() {
        // All sizes are integers — avoids sub-pixel rounding that blurs pixel fonts.
        // At 1280-wide (sf=1.0) the sizes match the design values exactly.
        float sf = gp.uiSf();
        hudFont_bold15  = arial_40.deriveFont(Font.BOLD,  (float) Math.round(15f * sf));
        hudFont_bold8   = arial_40.deriveFont(Font.BOLD,  (float) Math.round( 8f * sf));
        hudFont_bold13  = arial_40.deriveFont(Font.BOLD,  (float) Math.round(13f * sf));
        hudFont_plain10 = arial_40.deriveFont(Font.PLAIN, (float) Math.round(10f * sf));
        hudFont_bold10  = arial_40.deriveFont(Font.BOLD,  (float) Math.round(10f * sf));
        hudFont_bold9   = arial_40.deriveFont(Font.BOLD,  (float) Math.round( 9f * sf));
        hudFont_bold22  = arial_40.deriveFont(Font.BOLD,  (float) Math.round(22f * sf));
        hudFont_prompt  = arial_40.deriveFont(Font.BOLD,  (float) Math.round(14f * sf));
    }

    /** Rescales HUD fonts and title background when the logical resolution changes. */
    public void onResolutionChanged() {
        initHudFonts();
        fmCache.clear();
        if (titleBackgroundRaw != null) {
            titleBackground = UtilityTool.scaleImage(titleBackgroundRaw, gp.screenWidth, gp.screenHeight);
        }
    }

    public void addMessage(String text, Color color) {
        addMessage(text, color, (Sprite) null, 180);
    }

    public void addMessage(String text, Color color, int duration) {
        addMessage(text, color, (Sprite) null, duration);
    }

    public void addMessage(String text, Color color, Sprite icon) {
        addMessage(text, color, icon, 180);
    }

    public void addMessage(String text, Color color, Sprite icon, int duration) {
        message.add(text);
        messageColor.add(color);
        messageCounter.add(0);
        messageIcon.add(icon);
        messageDuration.add(Math.max(50, duration));
    }
    public void draw(GdxRenderer g2) {

        this.g2 = g2;

        // Pixel-sharp rendering: GPU textures use nearest-neighbor filtering (set on the
        // textures themselves), so the old AWT rendering hints are no longer needed.

        g2.setFont(arial_40);
        g2.setColor(Color.white);

        if (gp.gameState != GamePanel.gameOverState) gameOverAlpha = 0f;
        if (gp.gameState != GamePanel.pauseState) pauseAlpha = 0f;

        if(gp.gameState == GamePanel.titleState) {
            drawTitleScreen();
        }

        if(gp.gameState == GamePanel.playState) {
            drawPlayerLife();
            drawMessage();
            drawLevelUpBanner();
            drawInteractionPrompt();
            if (gp.thoughts != null) gp.thoughts.draw(g2);
        }

        if(gp.gameState == GamePanel.pauseState) {
            drawPlayerLife();
            drawPauseScreen();
        }

        //DIALOGUE STATE
        if(gp.gameState == GamePanel.dialogueState){
            drawPlayerLife();
            drawDialogueScreen();
        }

        if ( gp.gameState == GamePanel.characterState ) {
            drawCharacterScreen();
            drawInventory();
        }

        if(gp.gameState == GamePanel.optionsState){
            drawOptionsScreen();
        }

        if(gp.gameState == GamePanel.gameOverState){
            drawGameOverScreen();
        }
        if (gp.gameState == GamePanel.transitionState) {
            drawTransition(g2);
        }

        if (gp.gameState == GamePanel.levelUpState) {
            drawPlayerLife();
            drawLevelUpScreen();
        }

        if (gp.gameState == GamePanel.skillTreeState) {
            drawPlayerLife();
            drawSkillTreeScreen();
        }

        if ( gp.gameState == GamePanel.cutsceneState ) {
            drawDialogueScreen();
            if (gp.thoughts != null) gp.thoughts.draw(g2);
        }

        if ( gp.gameState == GamePanel.journalState ) {
            drawJournalScreen();
        }

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
            textLenght = cachedFM().stringWidth(text);
            x = gp.screenWidth/2 - textLenght/2;
            y = gp.screenHeight/2 - (gp.tileSize*3);
            g2.drawString(text, x, y);

            g2.setFont(arial_80B);
            g2.setColor(Color.blue);
            text = "Congratulations!";
            textLenght = cachedFM().stringWidth(text);
            x = gp.screenWidth/2 - textLenght/2;
            y = gp.screenHeight/2 + (gp.tileSize*2);
            g2.drawString(text, x, y);

            gp.gameThread = null;
        }
        else {

            if(gp.gameState == GamePanel.characterState) {

                g2.setFont(arial_40);
                g2.setColor(Color.white);
                //g2.drawImage(Key, gp.tileSize/2, gp.tileSize/2, gp.tileSize, gp.tileSize);
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

        // Compute alpha: fade in â†’ hold â†’ fade out
        float alpha;
        if (elapsed < ACT_TITLE_FADE_IN) {
            alpha = (float) elapsed / ACT_TITLE_FADE_IN;
        } else if (elapsed < ACT_TITLE_FADE_IN + ACT_TITLE_HOLD) {
            alpha = 1f;
        } else {
            alpha = 1f - (float)(elapsed - ACT_TITLE_FADE_IN - ACT_TITLE_HOLD) / ACT_TITLE_FADE_OUT;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        g2.setAlpha(alpha);

        int bannerH = 80;
        int bannerY = gp.screenHeight / 2 - bannerH / 2;
        g2.setColor(cachedColor(0, 0, 0, 160));
        g2.fillRect(0, bannerY, gp.screenWidth, bannerH);

        g2.setFont(cachedFont(Font.BOLD | Font.ITALIC, 36F));
        g2.setColor(cachedColor(240, 220, 170));
        int tw = cachedFM().stringWidth(actTitleText);
        int tx = gp.screenWidth / 2 - tw / 2;
        int ty = gp.screenHeight / 2 + 12;
        g2.setColor(cachedColor(0, 0, 0, 180));
        g2.drawString(actTitleText, tx + 2, ty + 2);
        g2.setColor(cachedColor(240, 220, 170));
        g2.drawString(actTitleText, tx, ty);

        g2.setAlpha(1f);

        if (actTitleTimer <= 0) actTitleText = null;
    }

    /** Called once per game-logic update (60 Hz). Advances animation state independently of render FPS.
     *  Must be called from GamePanel.update() so animations run at a fixed rate on weak hardware. */
    public void updateAnimations() {
        animTick++;
    }

    public void drawPlayerLife() {


        float sf = gp.screenWidth / 1280f;
        int margin = (int)(12 * sf);
        int barH = (int)(12 * sf);

        float targetLife = (float) gp.player.life / Math.max(1, gp.player.maxLife);
        float targetMana = (float) gp.player.mana / Math.max(1, gp.player.maxMana);
        float targetExp  = (gp.player.nextLevelExp > 0) ? (float) gp.player.exp / gp.player.nextLevelExp : 0;
        if (smoothLife < 0) smoothLife = targetLife;
        if (smoothMana < 0) smoothMana = targetMana;
        if (smoothExp  < 0) smoothExp  = targetExp;
        smoothLife += (targetLife - smoothLife) * 0.08f;
        smoothMana += (targetMana - smoothMana) * 0.08f;
        smoothExp  += (targetExp  - smoothExp)  * 0.08f;

        float pulse = fastPulse(animTick, 1);       // 0..1 slow breathe (~1 Hz at 60 UPS)
        float hpFastPulse = fastPulse(animTick, 3); // faster pulse for HP glow

        int panelW = (int)(275 * sf);
        int panelH = (int)(85 * sf);
        drawPanel(margin, margin, panelW, panelH, THEME_HUD);
        int accentH = (int)(2 * sf);
        g2.setColor(cachedColor(230, 60, 80, (int)(60 + 30 * pulse)));
        g2.fillRoundRect(margin + 4, margin, panelW - 8, accentH, 4, 4);

        int badgeSize = (int)(34 * sf);
        int badgeX = margin + (int)(8 * sf);
        int badgeY = margin + (int)(6 * sf);
        int glowPad = (int)(3 + 2 * pulse);
        g2.setColor(cachedColor(255, 200, 60, (int)(25 + 20 * pulse)));
        g2.fillRoundRect(badgeX - glowPad, badgeY - glowPad, badgeSize + glowPad * 2, badgeSize + glowPad * 2, 12, 12);
        g2.setColor(cachedColor(20, 16, 8, 240));
        g2.fillRoundRect(badgeX, badgeY, badgeSize, badgeSize, 10, 10);
        g2.setColor(cachedColor(255, 200, 60, (int)(100 + 40 * pulse)));
        g2.setStroke(STROKE_15);
        g2.drawRoundRect(badgeX, badgeY, badgeSize, badgeSize, 10, 10);
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

        int barsX = badgeX + badgeSize + (int)(12 * sf);
        int barsY = margin + (int)(9 * sf);
        int fullBarW = panelW - (badgeSize + (int)(34 * sf));
        int rowH = (int)(19 * sf);

        int smallIcon = (int)(14 * sf);
        g2.drawImage(Hearts_Full, barsX, barsY, smallIcon, smallIcon);
        int barStartX = barsX + smallIcon + (int)(4 * sf);
        int barContentW = fullBarW - smallIcon - (int)(4 * sf);
        drawStatBar(barStartX, barsY + (int)(1 * sf), barContentW, barH, smoothLife, HP_BAR_BG, HP_BAR_FILL, HP_BAR_GLOW);
        if (smoothLife < 0.3f) {
            g2.setColor(cachedColor(255, 40, 40, (int)(40 * hpFastPulse)));
            g2.fillRoundRect(barStartX, barsY + (int)(1 * sf), barContentW, barH, barH, barH);
        }

        int mpY = barsY + rowH;
        g2.drawImage(Crystal_Full, barsX, mpY, smallIcon, smallIcon);
        int mpBarH = (int)(barH * 0.85f);
        drawStatBar(barStartX, mpY + (int)(1 * sf), barContentW, mpBarH, smoothMana, MP_BAR_BG, MP_BAR_FILL, MP_BAR_GLOW);

        int xpPad = (int)(10 * sf);
        int xpBarX = margin + xpPad;
        int xpBarW = panelW - xpPad * 2;
        int xpBarH = (int)(8 * sf);
        int xpBarY = margin + panelH - xpBarH - (int)(7 * sf);
        drawStatBar(xpBarX, xpBarY, xpBarW, xpBarH, smoothExp, XP_BAR_BG, XP_BAR_FILL, XP_BAR_GLOW);
        g2.setFont(hudFont_bold8);
        g2.setColor(cachedColor(120, 230, 120, (int)(170 + 40 * pulse)));
        String xpTxt = "XP " + gp.player.exp + "/" + gp.player.nextLevelExp;
        int xpTxtW = cachedFM().stringWidth(xpTxt);
        g2.drawString(xpTxt, xpBarX + xpBarW / 2 - xpTxtW / 2, xpBarY - (int)(2 * sf));

        int pillW = (int)(94 * sf);
        int pillH = (int)(28 * sf);
        int pillGap = (int)(5 * sf);
        int pillRX = gp.screenWidth - margin - pillW;
        int pillRY = margin;
        int pillRound = 10;

        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        g2.setColor(cachedColor(255, 210, 50, (int)(100 + 40 * pulse)));
        g2.fillRoundRect(pillRX, pillRY + 4, (int)(3 * sf), pillH - 8, 3, 3);
        g2.setColor(PILL_BORDER_CLR);
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(pillRX, pillRY, pillW, pillH, pillRound, pillRound);
        int coinIconSz = (int)(15 * sf);
        int coinIconX = pillRX + (int)(10 * sf);
        int coinIconY = pillRY + pillH / 2 - coinIconSz / 2;
        g2.setColor(COIN_GOLD);
        g2.fillOval(coinIconX, coinIconY, coinIconSz, coinIconSz);
        g2.setColor(COIN_BORDER_CLR);
        g2.setStroke(STROKE_1);
        g2.drawOval(coinIconX, coinIconY, coinIconSz, coinIconSz);
        g2.setFont(hudFont_bold13);
        g2.setColor(COIN_GOLD);
        String coinStr = String.valueOf(gp.player.coin);
        int coinTxtX = pillRX + pillW - (int)(9 * sf) - cachedFM().stringWidth(coinStr);
        g2.drawString(coinStr, coinTxtX, pillRY + pillH / 2 + cachedFM().getAscent() / 2 - 1);

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

        if (gp.teleportation) {
            int tpX = margin;
            int tpY = margin + panelH + (int)(8 * sf);
            int tpW = (int)(145 * sf);
            int tpH = (int)(24 * sf);
            g2.setColor(HUD_PANEL_BG);
            g2.fillRoundRect(tpX, tpY, tpW, tpH, 8, 8);
            float tpPct = 1f - (float) gp.keyH.teleportCooldown / gp.player.getTeleportCooldownMax();
            g2.setColor(cachedColor(80, 180, 255, (int)(50 + 40 * (tpPct >= 1f ? fastPulse(animTick, 2) : 0))));
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
                    g2.setColor(cachedColor(160, 230, 255, (int)(60 + 40 * fastPulse(animTick, 2))));
                    g2.fillRoundRect(barX, barY, fillW2, barH2 / 2, barH2, barH2);
                }
            }
            g2.setFont(hudFont_bold9);
            g2.setColor(tpPct >= 1f ? cachedColor(130, 220, 255, (int)(200 + 55 * fastPulse(animTick, 2))) : cachedColor(90, 110, 140));
            g2.drawString(tpPct >= 1f ? "BLINK  READY" : "BLINK", tpX + (int)(9 * sf), tpY + tpH / 2 - (int)(1 * sf));
        }

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
        g2.setColor(HUD_PANEL_BG);
        g2.fillRoundRect(x, y, w, h, 8, 8);
        float abPulse = fastPulse(animTick, 2);
        boolean ready = unlocked && cooldown <= 0;
        g2.setColor(cachedColor(accent.getRed(), accent.getGreen(), accent.getBlue(),
                (int)(ready ? 90 + 50 * abPulse : 50)));
        g2.fillRoundRect(x, y + 3, 3, h - 6, 3, 3);
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
        g2.setColor(bg);
        g2.fillRoundRect(x, y, w, h, h, h);
        int fillW = (int)((w - 2) * pct);
        if (fillW > 0) {
            g2.setColor(fill);
            g2.fillRoundRect(x + 1, y + 1, fillW, h - 2, h - 2, h - 2);
            // Top highlight (simulate light reflection)
            g2.setColor(glow);
            g2.fillRoundRect(x + 1, y + 1, fillW, Math.max(2, (h - 2) / 3), h - 2, h - 2);
            if (fillW > 4) {
                int tipW = Math.min(6, fillW / 3);
                g2.setColor(STAT_BAR_TIP);
                g2.fillRoundRect(x + 1 + fillW - tipW, y + 1, tipW, h - 2, h - 2, h - 2);
            }
        }
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
            int txtW = cachedFM().stringWidth(txt);
            Sprite icon = messageIcon.get(i);
            int iconSpace = icon != null ? 28 : 0;
            int pillW = txtW + iconSpace + 24;
            int pillH = 34; // fixed: renders into tempScreen which is always 768px tall

            // Position: left side, roughly 39% down the screen (sf-safe reference point)
            int px = 16 - slideOffset;
            int py = (int)(gp.screenHeight * 0.39f) + totalHeight;

            Color baseColor = messageColor.get(i);

            // Pill background
            g2.setColor(cachedColor(10, 8, 6, (int)(alpha * 0.7f)));
            g2.fillRoundRect(px, py, pillW, pillH, 12, 12);

            g2.setColor(cachedColor(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
            g2.fillRoundRect(px, py, 3, pillH, 3, 3);

            if (icon != null) {
                g2.setAlpha(Math.max(0f, Math.min(1f, alpha / 255f)));
                g2.drawImage(icon, px + 14, py + 3, 24, 24);
                g2.setAlpha(1f);
            }

            g2.setColor(cachedColor(0, 0, 0, alpha));
            g2.drawString(txt, px + 14 + iconSpace + 1, py + 23);

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

    /** Clear all pending HUD notification messages (e.g. on New Game start). */
    public void clearMessages() {
        message.clear();
        messageCounter.clear();
        messageColor.clear();
        messageIcon.clear();
        messageDuration.clear();
    }

    private int promptBobCounter = 0;

    public void drawInteractionPrompt() {
        Entity target = gp.nearbyInteractable;
        if (target == null) { promptBobCounter = 0; return; }

        String text = "ENTER";
        if (target instanceof object.OBJ_Door door && door.promptText != null) {
            text = door.promptText;
        }

        promptBobCounter++;
        int bob = (int)(fastSin(promptBobCounter, 1) * 3); // gentle bob

        int screenX = target.worldX - gp.player.worldX + gp.player.screenX;
        int screenY = target.worldY - gp.player.worldY + gp.player.screenY;

        g2.setFont(hudFont_prompt);
        FontMetrics fm = cachedFM(hudFont_prompt);
        int textW = fm.stringWidth(text);
        int textH = fm.getHeight();

        int entityW = (target.solidArea.width  > 0) ? target.solidArea.width  : gp.tileSize;
        int entityH = (target.solidArea.height > 0) ? target.solidArea.height : gp.tileSize;

        int px = screenX + target.solidArea.x + entityW / 2 - (textW + 24) / 2;
        int py = screenY + target.solidArea.y + entityH / 2 - (textH + 8) / 2 + bob;

        int pillW = textW + 24;
        int pillH = textH + 8;

        g2.setColor(cachedColor(10, 8, 6, 200));
        g2.fillRoundRect(px, py, pillW, pillH, 10, 10);

        g2.setColor(cachedColor(200, 190, 170, 160));
        g2.drawRoundRect(px, py, pillW, pillH, 10, 10);

        g2.setColor(cachedColor(0, 0, 0, 200));
        g2.drawString(text, px + 13, py + textH + 1);

        g2.setColor(cachedColor(240, 230, 210, 255));
        g2.drawString(text, px + 12, py + textH);
    }

    public void drawTitleScreen() {

        if (titleBackground != null) {
            g2.drawImage(titleBackground, 0, 0);
        } else {
            g2.setColor(cachedColor(0, 0, 0));
            g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
        }

        if(titleScreenState == 0) {
            float pulse = fastPulse(animTick, 1); // 0.0 .. 1.0

            int vigTop = gp.screenHeight - 380;
            g2.setColor(cachedColor(3, 1, 12, 0)); // TODO(gfx): gradient
            g2.fillRect(0, vigTop, gp.screenWidth, 380);

            g2.setFont(cachedFont(Font.BOLD, 72F));
            String text = "Echoes of the Heir";
            FontMetrics titleFM = cachedFM();
            int tw = titleFM.stringWidth(text);
            int tx = (gp.screenWidth - tw) / 2;
            int ty = titleFM.getAscent() + 12;
            g2.setColor(cachedColor(22, 4, 48, 218));
            g2.drawString(text, tx + 3, ty + 3);
            g2.setColor(cachedColor(248, 238, 214)); // TODO(gfx): gradient
            g2.drawString(text, tx, ty);

            int ruleY = ty + titleFM.getDescent() + 6;
            int cxHalf = gp.screenWidth / 2;
            g2.setFont(cachedFont(Font.PLAIN, 17F));
            int symAlpha = (int)(115 + pulse * 110);
            g2.setColor(cachedColor(215, 168, 60, symAlpha));
            String sym = "\u2726"; // âœ¦  four-pointed star
            int symW = cachedFM().stringWidth(sym);
            int symX = cxHalf - symW / 2;
            g2.drawString(sym, symX, ruleY + 7);
            g2.setStroke(STROKE_1);
            g2.setColor(cachedColor(182, 143, 66, 0)); // TODO(gfx): gradient
            g2.drawLine(tx, ruleY, symX - 6, ruleY);
            g2.setColor(cachedColor(182, 143, 66, 168)); // TODO(gfx): gradient
            g2.drawLine(symX + symW + 6, ruleY, tx + tw, ruleY);

            g2.setFont(cachedFont(Font.PLAIN, 19F));
            String sub = "The Canvas Realm Awaits";
            int sw = cachedFM().stringWidth(sub);
            int subX = (gp.screenWidth - sw) / 2;
            int subY = ruleY + 26;
            g2.setColor(cachedColor(10, 2, 28, 210));
            g2.drawString(sub, subX + 2, subY + 2);
            g2.setColor(cachedColor(195, 162, 232, 240));
            g2.drawString(sub, subX, subY);

            int spriteSize = (int)(gp.tileSize * 2.5f);
            int sx = gp.screenWidth / 2 - spriteSize / 2;
            int sy = (int)(gp.screenHeight * 0.335f);
            int haloA = (int)(14 + pulse * 20);
            g2.setColor(cachedColor(235, 205, 128, haloA));
            g2.fillOval(sx - 24, sy + spriteSize - 20, spriteSize + 48, 30);
            g2.drawImage(gp.player.down1, sx, sy, spriteSize, spriteSize);

            java.util.List<MenuItem> menuItems = titleMenu().items();
            int menuStartY = (int)(gp.screenHeight * 0.77f);
            g2.setFont(cachedFont(Font.BOLD, 27F));

            // Arrow nudge: 0→1→2→1→0 pixel slide, period ~1.2s at 60ups
            int arrowNudge = (int)(Math.sin(animTick * 0.09) * 2.0);
            int arrowAlpha = (int)(210 + pulse * 45);

            // Record a full-width hitbox per row so mouse hover/click (MouseHandler) matches the rows.
            titleMenu().beginRects();

            for (int i = 0; i < menuItems.size(); i++) {
                int iy = menuStartY + i * 40;
                titleMenu().recordRect(0, iy - 24, gp.screenWidth, 40);
                boolean sel = (commandNum == i);
                text = menuItems.get(i).label;
                tw = cachedFM().stringWidth(text);
                tx = (gp.screenWidth - tw) / 2;

                if (sel) {
                    g2.setColor(cachedColor(255, 255, 255, 255));
                    g2.drawString(text, tx, iy);

                    // Pixel-perfect right-pointing arrow (tip points RIGHT toward text).
                    // Drawn as 7 rows; widths: 1,2,3,4,3,2,1 blocks.
                    // Base (left edge) is fixed; tip = base + maxWidth on the centre row.
                    // p=3: each logical pixel is a 3x3 square for a clean chunky look.
                    int p = 3;
                    int rows = 7;
                    FontMetrics fm = cachedFM();
                    int textMidY = iy - fm.getAscent() / 2;
                    int arrowTopY = textMidY - (rows * p) / 2;
                    // tipX is the rightmost edge of the arrow (the point), sits just left of text
                    int tipX = tx - 14 + arrowNudge;
                    g2.setColor(cachedColor(255, 255, 255, arrowAlpha));
                    for (int r = 0; r < rows; r++) {
                        int w = Math.min(r, rows - 1 - r) + 1; // 1,2,3,4,3,2,1
                        int rowY = arrowTopY + r * p;
                        // All rows anchored to tipX on the right; extend leftward by w blocks
                        int rowX = tipX - w * p;
                        g2.fillRect(rowX, rowY, w * p, p);
                    }
                } else {
                    g2.setColor(cachedColor(180, 175, 165, 160));
                    g2.drawString(text, tx, iy);
                }
            }
            // Username input box — top-right corner of title screen
            {
                float pulse2 = fastPulse(animTick, 2);
                int boxW = 220, boxH = 34;
                int boxX = gp.screenWidth - boxW - 22;
                int boxY = 18;
                int boxRound = 8;

                // Background panel
                g2.setColor(cachedColor(6, 4, 14, 200));
                g2.fillRoundRect(boxX, boxY, boxW, boxH, boxRound, boxRound);

                // Border — highlighted when focused
                if (usernameFieldFocused) {
                    int bordA = (int)(160 + pulse2 * 80);
                    g2.setColor(cachedColor(232, 52, 118, bordA));
                } else {
                    g2.setColor(cachedColor(90, 80, 110, 160));
                }
                g2.setStroke(STROKE_15);
                g2.drawRoundRect(boxX, boxY, boxW, boxH, boxRound, boxRound);

                // Label
                g2.setFont(cachedFont(Font.PLAIN, 11F));
                g2.setColor(cachedColor(160, 145, 200, 180));
                g2.drawString("USERNAME", boxX + 8, boxY - 3);

                // Text content + cursor
                g2.setFont(cachedFont(Font.BOLD, 13F));
                String display = playerUsername.isEmpty() && !usernameFieldFocused ? "Click to set name" : playerUsername;
                Color textCol = playerUsername.isEmpty()
                        ? cachedColor(120, 110, 140, 140)
                        : cachedColor(230, 220, 255);
                g2.setColor(textCol);
                int textX = boxX + 10;
                int textY = boxY + boxH / 2 + cachedFM().getAscent() / 2 - 2;
                g2.drawString(display, textX, textY);

                // Blinking cursor when focused
                if (usernameFieldFocused && (animTick / 20) % 2 == 0) {
                    int cursorX = textX + cachedFM().stringWidth(playerUsername);
                    g2.setColor(cachedColor(232, 52, 118, 220));
                    g2.setStroke(STROKE_15);
                    g2.drawLine(cursorX + 2, boxY + 6, cursorX + 2, boxY + boxH - 6);
                }

                // Hint below box
                g2.setFont(cachedFont(Font.PLAIN, 11F));
                g2.setColor(cachedColor(100, 92, 120, 140));
                String hint = usernameFieldFocused ? "Press ENTER or ESC to confirm" : "Press U to edit";
                g2.drawString(hint, boxX, boxY + boxH + 13);
            }

            g2.setFont(cachedFont(Font.PLAIN, 14F));
            g2.setColor(cachedColor(108, 98, 78, 160));
            g2.drawString("[I] Info & Update Log", 22, gp.screenHeight - 22);
            String ver = Config.getVersionString();
            int vw = cachedFM().stringWidth(ver);
            g2.drawString(ver, gp.screenWidth - vw - 22, gp.screenHeight - 22);
        }
        else if ( titleScreenState == 1) {

            int panelW = 500, panelH = 420;
            int px = (gp.screenWidth - panelW) / 2;
            int py = (gp.screenHeight - panelH) / 2;

            g2.setColor(cachedColor(15, 12, 20, 230));
            g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
            g2.setColor(cachedColor(180, 150, 80, 100));
            g2.setStroke(STROKE_2);
            g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

            g2.setFont(cachedFont(Font.BOLD, 34F));
            g2.setColor(cachedColor(255, 220, 100));
            String text = "Choose Your Class";
            int tw = cachedFM().stringWidth(text);
            g2.drawString(text, px + (panelW - tw) / 2, py + 48);

            g2.setColor(cachedColor(120, 100, 60, 80));
            g2.drawLine(px + 30, py + 62, px + panelW - 30, py + 62);

            // Class options — labels come from classMenu() (declared once); the icon/desc/color
            // presentation stays here, keyed by row index. Item 3 is the "Back" row.
            java.util.List<MenuItem> classItems = classMenu().items();
            String[] classes = {classItems.get(0).label, classItems.get(1).label, classItems.get(2).label};
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

            // Record button rects so the mouse hover/click hit-test (MouseHandler) matches exactly
            // what we draw here — the Menu owns hit-testing for this custom-styled screen too.
            classMenu().beginRects();

            for (int i = 0; i < 3; i++) {
                int oy = optY + i * (optH + 14);
                boolean sel = (commandNum == i);
                classMenu().recordRect(optX, oy, optW, optH);

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

                g2.setFont(cachedFont(Font.PLAIN, 22F));
                g2.setColor(sel ? classColors[i] : cachedColor(120, 110, 100));
                g2.drawString(icons[i], optX + 18, oy + 28);

                g2.setFont(cachedFont(sel ? Font.BOLD : Font.PLAIN, 24F));
                g2.setColor(sel ? Color.WHITE : cachedColor(170, 160, 145));
                g2.drawString(classes[i], optX + 50, oy + 28);

                g2.setFont(cachedFont(Font.PLAIN, 14F));
                g2.setColor(sel ? cachedColor(200, 190, 170) : cachedColor(120, 115, 105));
                g2.drawString(descs[i], optX + 50, oy + 48);
            }

            int backY = optY + 3 * (optH + 14) + 10;
            boolean backSel = (commandNum == 3);
            g2.setFont(cachedFont(Font.BOLD, 20F));
            text = "\u2190 Back";
            tw = cachedFM().stringWidth(text);
            g2.setColor(backSel ? cachedColor(255, 220, 100) : cachedColor(120, 115, 105));
            g2.drawString(text, px + (panelW - tw) / 2, backY);
            // Back hitbox spans the panel width, centered on the text baseline (matches its click area).
            classMenu().recordRect(optX, backY - 20, optW, 30);

            g2.setFont(cachedFont(Font.PLAIN, 13F));
            g2.setColor(cachedColor(100, 95, 85));
            String hint = "[W/S] Navigate    [Enter] Select";
            int hw = cachedFM().stringWidth(hint);
            g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 15);
        }
        else if ( titleScreenState == 2 ) {

            int panelW = 560, panelH = 480;
            int px = (gp.screenWidth - panelW) / 2;
            int py = (gp.screenHeight - panelH) / 2;

            g2.setColor(cachedColor(15, 12, 22, 235));
            g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
            g2.setColor(cachedColor(100, 140, 180, 80));
            g2.setStroke(STROKE_2);
            g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

            g2.setFont(cachedFont(Font.BOLD, 30F));
            g2.setColor(cachedColor(120, 180, 255));
            String text = "Update Log  \u2022  " + Config.getVersionString();
            int tw = cachedFM().stringWidth(text);
            g2.drawString(text, px + (panelW - tw) / 2, py + 42);

            g2.setColor(cachedColor(100, 140, 180, 0)); // TODO(gfx): gradient
            g2.drawLine(px + 40, py + 55, px + panelW / 2, py + 55);
            g2.setColor(cachedColor(100, 140, 180, 120)); // TODO(gfx): gradient
            g2.drawLine(px + panelW / 2, py + 55, px + panelW - 40, py + 55);

            String[][] entries = {
                {"\u2726", "New map system with TMX loading"},
                {"\u2694", "Combat: Dodge roll, 3-hit combos"},
                {"\u2620", "New enemy: Skeleton Archer"},
                {"\u2605", "Level-up stat selection screen"},
                {"\u2302", "Breakable pots with random loot"},
                {"\u2611", "Quest tracking system"},
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

                g2.setFont(cachedFont(Font.PLAIN, 16F));
                g2.setColor(entryColors[i % entryColors.length]);
                g2.drawString(entries[i][0], entryX, ey);

                g2.setFont(cachedFont(Font.PLAIN, 17F));
                g2.setColor(cachedColor(210, 205, 195));
                g2.drawString(entries[i][1], entryX + 26, ey);
            }

            int ctrlY = py + panelH - 80;
            g2.setColor(cachedColor(60, 55, 70, 60));
            g2.fillRoundRect(px + 25, ctrlY, panelW - 50, 40, 10, 10);
            g2.setFont(cachedFont(Font.BOLD, 13F));
            g2.setColor(cachedColor(160, 155, 140));
            g2.drawString("WASD Move | Enter Attack | Space Blink | Shift Roll | Z/X/C/V Skills", px + 40, ctrlY + 25);

            boolean backSel = (commandNum == 0);
            g2.setFont(cachedFont(Font.BOLD, 20F));
            text = "\u2190 Back";
            tw = cachedFM().stringWidth(text);
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
    // MULTIPLAYER BROWSER (titleScreenState 3)
    private void drawMultiplayerBrowser() {
        int panelW = 560, panelH = 480;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        g2.setColor(cachedColor(12, 14, 24, 235));
        g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
        g2.setColor(cachedColor(80, 140, 200, 90));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

        g2.setFont(cachedFont(Font.BOLD, 32F));
        g2.setColor(cachedColor(100, 180, 255));
        String text = "\u2630  MULTIPLAYER";
        int tw = cachedFM().stringWidth(text);
        g2.drawString(text, px + (panelW - tw) / 2, py + 44);

        g2.setColor(cachedColor(80, 120, 180, 80));
        g2.drawLine(px + 30, py + 58, px + panelW - 30, py + 58);

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
            int ew = cachedFM().stringWidth(empty);
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

                g2.setColor(cachedColor(80, 200, 100, sel ? 200 : 100));
                g2.fillOval(entryX + entryW - 20, ey + entryH / 2 - 4, 8, 8);
            }
        }

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
            tw = cachedFM().stringWidth(text);
            g2.setColor(sel ? optColors[i] : cachedColor(140, 135, 120));
            g2.drawString(text, optX + (optW - tw) / 2, oy + optH / 2 + 6);
        }

        if (gp.mpClient != null && !gp.mpClient.connectionStatus.isEmpty()) {
            g2.setFont(cachedFont(Font.ITALIC, 14F));
            g2.setColor(cachedColor(255, 200, 80, 200));
            String status = gp.mpClient.connectionStatus;
            int sw = cachedFM().stringWidth(status);
            g2.drawString(status, px + (panelW - sw) / 2, py + panelH - 42);
        }

        g2.setFont(cachedFont(Font.PLAIN, 12F));
        g2.setColor(cachedColor(90, 85, 75));
        String hint = "[W/S] Navigate   [Enter] Select/Connect   [Delete] Remove Server";
        int hw = cachedFM().stringWidth(hint);
        g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 16);
    }

    // MULTIPLAYER INPUT SCREEN (titleScreenState 4)
    private void drawMultiplayerInput() {
        int panelW = 480, panelH = 350;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        g2.setColor(cachedColor(12, 14, 24, 240));
        g2.fillRoundRect(px, py, panelW, panelH, 16, 16);
        g2.setColor(cachedColor(80, 160, 220, 80));
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(px + 2, py + 2, panelW - 4, panelH - 4, 14, 14);

        g2.setFont(cachedFont(Font.BOLD, 28F));
        g2.setColor(cachedColor(100, 200, 140));
        String title = mpAddMode ? "ADD SERVER" : "DIRECT CONNECT";
        int ttw = cachedFM().stringWidth(title);
        g2.drawString(title, px + (panelW - ttw) / 2, py + 40);

        g2.setColor(cachedColor(80, 160, 120, 60));
        g2.drawLine(px + 30, py + 52, px + panelW - 30, py + 52);

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

            g2.setFont(cachedFont(Font.BOLD, 14F));
            g2.setColor(active ? cachedColor(120, 200, 160) : cachedColor(150, 145, 130));
            g2.drawString(labels[i], fieldX, fy - 6);

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

            g2.setFont(cachedFont(Font.PLAIN, 18F));
            g2.setColor(Color.WHITE);
            String displayText = values[i];
            if (active) {
                boolean cursorVisible = (animTick / 20) % 2 == 0;
                displayText = values[i] + (cursorVisible ? "|" : "");
            }
            g2.drawString(displayText, fieldX + 10, fy + fieldH / 2 + 6);
        }

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
            int btw = cachedFM().stringWidth(bText);
            g2.setColor(sel ? cachedColor(100, 220, 160) : cachedColor(140, 135, 120));
            g2.drawString(bText, bx + (btnW - btw) / 2, btnY + btnH / 2 + 5);
        }

        // Connection status (shown after a failed connect attempt)
        if (gp.mpClient != null && !gp.mpClient.connectionStatus.isEmpty()) {
            g2.setFont(cachedFont(Font.ITALIC, 12F));
            String cs = gp.mpClient.connectionStatus;
            boolean isError = cs.contains("taken") || cs.contains("banned") || cs.contains("failed")
                    || cs.contains("timed") || cs.contains("full");
            g2.setColor(isError ? cachedColor(255, 110, 100, 220) : cachedColor(120, 210, 140, 220));
            int csw = cachedFM().stringWidth(cs);
            // Wrap to panel width if too long
            if (csw > panelW - 60) cs = cs.substring(0, Math.min(cs.length(), 60)) + "…";
            csw = cachedFM().stringWidth(cs);
            g2.drawString(cs, px + (panelW - csw) / 2, py + panelH - 36);
        }

        g2.setFont(cachedFont(Font.PLAIN, 12F));
        g2.setColor(cachedColor(90, 85, 75));
        String hint = "[Tab] Next field   [Enter] Select   [Esc] Back";
        int hw = cachedFM().stringWidth(hint);
        g2.drawString(hint, px + (panelW - hw) / 2, py + panelH - 16);
    }

    public void drawPauseScreen() {

        if (pauseAlpha < 1f) pauseAlpha += 0.06f;
        if (pauseAlpha > 1f) pauseAlpha = 1f;

        g2.setColor(cachedColor(8, 8, 15, (int)(160 * pauseAlpha)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // Framed nine-slice panel, faded in with pauseAlpha (setAlpha is multiplied by drawImage).
        int frameInset = gp.tileSize * 3;
        float prevAlpha = g2.getAlpha();
        g2.setAlpha(prevAlpha * pauseAlpha);
        drawPanel(frameInset, gp.tileSize * 2, gp.screenWidth - frameInset * 2,
                gp.screenHeight - gp.tileSize * 4, THEME_PAUSE);
        g2.setAlpha(prevAlpha);

        float breathe = fastPulse(animTick, 1);
        g2.setFont(cachedFont(Font.BOLD, 72F));
        String text = "PAUSED";
        int x = getXforCenteredText(text);
        int y = gp.screenHeight / 2 - gp.tileSize;

        g2.setColor(cachedColor(0, 0, 0, (int)(180 * pauseAlpha)));
        g2.drawString(text, x + 3, y + 3);
        int textAlpha = (int)((180 + 75 * breathe) * pauseAlpha);
        g2.setColor(cachedColor(220, 210, 190, Math.min(255, textAlpha)));
        g2.drawString(text, x, y);

        int lineW = gp.tileSize * 4;
        int lineY = y + 14;
        g2.setColor(cachedColor(180, 140, 60, (int)(80 * pauseAlpha)));
        g2.setStroke(STROKE_2);
        g2.drawLine(x - lineW - 20, lineY, x - 20, lineY);
        int textW = cachedFM().stringWidth(text);
        g2.drawLine(x + textW + 20, lineY, x + textW + lineW + 20, lineY);

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
            totalW += cachedFM().stringWidth(s);
        }
        totalW += gap * (quickStats.length - 1);
        int sx = gp.screenWidth / 2 - totalW / 2;
        Color[] statColors = { LVL_BADGE, HP_BAR_FILL, MP_BAR_FILL };
        for (int i = 0; i < quickStats.length; i++) {
            g2.setColor(cachedColor(statColors[i].getRed(), statColors[i].getGreen(),
                    statColors[i].getBlue(), (int)(180 * pauseAlpha)));
            g2.drawString(quickStats[i], sx, statsY);
            sx += cachedFM().stringWidth(quickStats[i]) + gap;
        }

        g2.setFont(cachedFont(Font.PLAIN, 16F));
        g2.setColor(cachedColor(150, 145, 130, (int)(120 * pauseAlpha)));
        String hint = "Press P to resume";
        int hx = getXforCenteredText(hint);
        g2.drawString(hint, hx, gp.screenHeight - gp.tileSize * 2);
    }

    private final HashMap<String, Sprite> portraitCache = new HashMap<>();
    private Sprite getPortrait(String path) {
        if (path == null) return null;
        if (portraitCache.containsKey(path)) {
            return portraitCache.get(path);
        }
        Sprite img = ResourceCache.loadScaledImageIfPresent(path, 96, 96);
        portraitCache.put(path, img);
        return img;
    }

    /**
     * Word-wrap text to fit within maxWidth pixels using the given font. Respects existing \n as hard breaks.
     *
     * OPTIMIZATION: scans the input string in a single pass — no split("\n") or split(" ")
     * allocations. Reuses a single StringBuilder for the current line and computes word width
     * once per word using fm.stringWidth on a substring view. This makes wrapping ~3x cheaper
     * for typical dialogue lines on weak hardware.
     */
    private final StringBuilder wrapBuilder = new StringBuilder(256);
    private java.util.List<String> wrapText(String text, Font font, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) { lines.add(""); return lines; }
        FontMetrics fm = cachedFM(font);
        int n = text.length();
        int i = 0;
        wrapBuilder.setLength(0);
        while (i < n) {
            // Hard break on '\n'
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(wrapBuilder.toString());
                wrapBuilder.setLength(0);
                i++;
                continue;
            }
            // Locate end of current word (no spaces, no newlines)
            int wordStart = i;
            while (i < n) {
                char wc = text.charAt(i);
                if (wc == ' ' || wc == '\n') break;
                i++;
            }
            String word = text.substring(wordStart, i);

            if (wrapBuilder.length() == 0) {
                // First word on the line — always fits (even if technically too wide)
                wrapBuilder.append(word);
            } else {
                // Check whether "<current> <word>" fits without building a new String
                int candidateWidth = fm.stringWidth(wrapBuilder.toString()) + fm.charWidth(' ') + fm.stringWidth(word);
                if (candidateWidth > maxWidth) {
                    lines.add(wrapBuilder.toString());
                    wrapBuilder.setLength(0);
                    wrapBuilder.append(word);
                } else {
                    wrapBuilder.append(' ').append(word);
                }
            }
            // Skip a single trailing space (collapse consecutive spaces to one)
            if (i < n && text.charAt(i) == ' ') i++;
        }
        if (wrapBuilder.length() > 0) lines.add(wrapBuilder.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /**
     * Returns the cached wrapped lines for {@code text}, recomputing only if any of
     * (text, font, maxWidth) has changed since the last call. The returned list is owned by
     * the cache — do not mutate.
     */
    private java.util.List<String> wrapTextCached(String text, Font font, int maxWidth) {
        if (maxWidth == dialogueWrapKeyWidth
                && font == dialogueWrapKeyFont
                && text != null && text.equals(dialogueWrapKeyText)) {
            return dialogueWrapCache;
        }
        // Recompute and copy into the persistent cache list
        java.util.List<String> fresh = wrapText(text, font, maxWidth);
        dialogueWrapCache.clear();
        dialogueWrapCache.addAll(fresh);
        dialogueWrapKeyText  = text;
        dialogueWrapKeyWidth = maxWidth;
        dialogueWrapKeyFont  = font;
        return dialogueWrapCache;
    }

    /**
     * Called from {@link main.GamePanel#update()} at 60 UPS.
     * Handles all dialogue state mutations: typewriter tick, Enter input, gameState transitions.
     * Keeping mutations here (not in the draw path) means dialogue speed is always 60 chars/sec
     * regardless of FPS, and the EDT is never starved by rapid wrapText() calls.
     */
    public void updateDialogueState() {
        if (npc == null) return;

        String[][] dlgSets = npc.ensureDialogues();
        String fullLine = (npc.dialogueSet < dlgSets.length
                           && npc.dialogueIndex < dlgSets[npc.dialogueSet].length)
                           ? dlgSets[npc.dialogueSet][npc.dialogueIndex]
                           : null;
        int fullLineLen = (fullLine != null) ? fullLine.length() : 0;

        if (fullLine != null) {
            // Typewriter: one char per update tick (60 UPS = ~60 chars/sec, FPS-independent).
            if (charIndex < fullLineLen) {
                if (charIndex == 0) gp.startDialogueTyping();
                dialogueBuilder.append(fullLine.charAt(charIndex));
                charIndex++;
                if (charIndex >= fullLineLen) gp.stopDialogueTyping();
            }
            // When typing is complete, reuse the original String object so that
            // wrapTextCached()'s reference-equality fast-path fires on every draw frame.
            currentDialogue = (charIndex < fullLineLen) ? dialogueBuilder.toString() : fullLine;

            if (gp.keyH.enterPressed) {
                if (charIndex < fullLineLen) {
                    // Typewriter still running — complete the line instantly, don't advance yet
                    charIndex = fullLineLen;
                    currentDialogue = fullLine;
                    dialogueBuilder.setLength(0);
                    gp.keyH.enterPressed = false;
                    gp.stopDialogueTyping();
                } else {
                    // Line already complete — advance to next
                    charIndex = 0;
                    combinedText = "";
                    dialogueBuilder.setLength(0);
                    gp.stopDialogueTyping();

                    if (gp.gameState == GamePanel.dialogueState || gp.gameState == GamePanel.cutsceneState) {
                        // Choice confirmation: if choices are showing, apply the selected choice
                        if (npc.dialogueChoices != null && npc.dialogueChoices.length > 0) {
                            if ("ending".equals(npc.choiceResultKey)) {
                                gp.endingChosen = npc.selectedChoice + 1;
                            }
                            if (npc.choiceNextSet != null && npc.selectedChoice < npc.choiceNextSet.length) {
                                npc.dialogueSet = npc.choiceNextSet[npc.selectedChoice];
                                npc.dialogueIndex = 0;
                            } else {
                                npc.dialogueIndex++;
                            }
                            npc.dialogueChoices = null;
                            npc.selectedChoice = 0;
                        } else {
                            npc.dialogueIndex++;
                        }
                        gp.keyH.enterPressed = false;
                    }
                }
            }
        } else {
            // End of dialogue
            // If the active state marks this NPC as met, record it so intro won't show again
            if (npc instanceof entity.NPC_Generic) {
                entity.NPC_Generic gNpc = (entity.NPC_Generic) npc;
                if (gNpc.activeState != null && gNpc.activeState.marksNpcMet
                        && gNpc.objectId != null && !gNpc.objectId.isBlank()) {
                    gp.metNPCs.add(gNpc.objectId);
                }
            }
            npc.dialogueIndex = 0;
            dialogueBuilder.setLength(0);
            currentDialogue = "";
            gp.stopDialogueTyping();

            if (gp.gameState == GamePanel.dialogueState) {
                gp.gameState = GamePanel.playState;
            }
            if (gp.gameState == GamePanel.cutsceneState) {
                gp.csManager.scenePhase++;
                npc = null;
            }
        }
    }

    public void drawDialogueScreen() {

        if (npc == null) return;

        int x = gp.tileSize * 2;
        int y = gp.tileSize / 2;
        int width = gp.screenWidth - (gp.tileSize * 4);
        int height = gp.tileSize * 5;

        drawSubWindow(x, y, width, height, THEME_DIALOGUE);

        if (npc != null && npc.name != null && !npc.name.isEmpty()) {
            int nameTagW = cachedFM(cachedFont(Font.BOLD, 20F))
                    .stringWidth(npc.name) + 30;
            int nameTagH = 30;
            int nameTagX = x + 16;
            int nameTagY = y - nameTagH + 4;
            g2.setColor(cachedColor(25, 20, 12, 230));
            g2.fillRoundRect(nameTagX, nameTagY, nameTagW, nameTagH, 10, 10);
            g2.setColor(OPT_BORDER);
            g2.setStroke(STROKE_15);
            g2.drawRoundRect(nameTagX, nameTagY, nameTagW, nameTagH, 10, 10);
            g2.setFont(cachedFont(Font.BOLD, 20F));
            g2.setColor(DIALOGUE_NAME);
            g2.drawString(npc.name, nameTagX + 14, nameTagY + 21);
        }

        g2.setFont(cachedFont(Font.PLAIN, 28F));
        x += gp.tileSize;
        y += gp.tileSize;

        int portraitOffset = 0;
        if (npc.portraitPath != null) {
            Sprite portrait = getPortrait(npc.portraitPath);
            if (portrait != null) {
                int portraitX = x - 4;
                int portraitY = y - 8;
                g2.setColor(cachedColor(15, 12, 8, 200));
                g2.fillRoundRect(portraitX - 4, portraitY - 4, 104, 104, 8, 8);
                g2.drawImage(portrait, portraitX, portraitY);
                g2.setColor(cachedColor(120, 100, 60, 180));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(portraitX - 4, portraitY - 4, 104, 104, 8, 8);
                portraitOffset = 112;
            }
        }
        int textX = x + portraitOffset;

        // All state mutations (typewriter tick, Enter handling, gameState changes) happen in
        // updateDialogueState() at 60 UPS. drawDialogueScreen() is read-only — it only draws.
        String[][] dlgSets = npc.ensureDialogues();
        String fullLine    = (npc.dialogueSet < dlgSets.length
                              && npc.dialogueIndex < dlgSets[npc.dialogueSet].length)
                              ? dlgSets[npc.dialogueSet][npc.dialogueIndex]
                              : null;
        int fullLineLen    = (fullLine != null) ? fullLine.length() : 0;

        // End-of-dialogue transitions are already handled in updateDialogueState;
        // if fullLine is null here the state change is scheduled — skip drawing.
        if (fullLine == null) return;

        Font dialogueFont = cachedFont(Font.PLAIN, 28F);
        g2.setFont(dialogueFont);
        int textMaxWidth = width - gp.tileSize * 2 - portraitOffset - 16;
        java.util.List<String> wrappedLines = wrapTextCached(currentDialogue, dialogueFont, textMaxWidth);
        for (int li = 0, ln = wrappedLines.size(); li < ln; li++) {
            String line = wrappedLines.get(li);
            g2.setColor(cachedColor(0, 0, 0, 100));
            g2.drawString(line, textX + 2, y + 2);
            g2.setColor(cachedColor(230, 225, 215));
            g2.drawString(line, textX, y);
            y += 40;
        }

        if (charIndex >= fullLineLen) {
            float blink = fastPulse(animTick, 2);
            int alpha = (int)(80 + 175 * blink);
            g2.setColor(cachedColor(220, 210, 190, alpha));
            g2.setFont(cachedFont(Font.PLAIN, 18F));
            String cont = "\u25BC ENTER";
            int contW = cachedFM().stringWidth(cont);
            int contX = gp.tileSize * 2 + width - gp.tileSize - contW;
            int contY = gp.tileSize / 2 + height - 16;
            g2.drawString(cont, contX, contY);

            if (npc.dialogueChoices != null && npc.dialogueChoices.length > 0) {
                drawDialogueChoices(gp.tileSize * 2, gp.tileSize / 2 + height + 8, width);
            }
        }
    }

    private void drawDialogueChoices(int boxX, int boxY, int boxWidth) {
        if (npc == null || npc.dialogueChoices == null) return;

        int optionH = 36;
        int totalH = npc.dialogueChoices.length * optionH + 20;

        drawSubWindow(boxX, boxY, boxWidth, totalH, THEME_DIALOGUE);

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

    public int journalScroll = 0;
    public int journalSelectedIndex = 0;

    public void drawJournalScreen() {

        data.MemoryJournal journal = gp.memoryJournal;
        if (journal == null) return;

        g2.setColor(cachedColor(15, 10, 5, 230));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        g2.setFont(cachedFont(Font.BOLD, 32F));
        g2.setColor(cachedColor(255, 215, 100));
        String title = "Memory Journal";
        g2.drawString(title, getXforCenteredText(title), 50);

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

        int panelX = gp.tileSize;
        int panelY = 100;
        int panelW = gp.screenWidth / 3;
        int panelH = gp.screenHeight - 140;
        drawSubWindow(panelX, panelY, panelW, panelH, THEME_JOURNAL);

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

        int rightX = panelX + panelW + gp.tileSize / 2;
        int rightY = panelY;
        int rightW = gp.screenWidth - rightX - gp.tileSize;
        int rightH = panelH;
        drawSubWindow(rightX, rightY, rightW, rightH, THEME_JOURNAL);

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

        g2.setFont(cachedFont(Font.PLAIN, 14F));
        g2.setColor(cachedColor(120, 115, 105));
        g2.drawString("W/S: Navigate    J/ESC: Close", panelX + 20, gp.screenHeight - 20);
    }

    /**
     * The Game Over menu (Retry / Quit). Actions live here — the single source of truth — so
     * KeyHandler just calls {@code gameOverMenu().activate()} instead of an index switch, and the
     * draw code reads the labels from {@code items()}. Built once, then reused.
     */
    public Menu gameOverMenu() {
        if (gameOverMenu == null) {
            gameOverMenu = Menu.of(null, THEME_PAUSE)
                .onNavigate(() -> gp.playSE(audio.SFX.MENU_SELECT))
                .button("Retry", () -> {
                    // resetGame(false) repositions the player at retrySpawnCol/Row (see MapManager).
                    gp.resetGame(false);
                    gp.gameState = gp.playState;
                    gp.playMusic(audio.SFX.MAIN_THEME);
                })
                .button("Quit", () -> {
                    gp.ui.titleScreenState = 0;
                    gp.ui.commandNum = 0;
                    gp.stopMusic();
                    gp.resetGame(true);
                    gp.gameState = GamePanel.titleState;
                });
        }
        return gameOverMenu;
    }

    public void drawGameOverScreen() {

        if (gameOverAlpha < 1f) gameOverAlpha += 0.02f;
        if (gameOverAlpha > 1f) gameOverAlpha = 1f;

        float a = gameOverAlpha;

        g2.setColor(cachedColor(12, 10, 14, (int)(210 * a)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

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

        float linePulse = fastPulse(animTick, 1);
        int lineAlpha = (int)((40 + 30 * linePulse) * a);
        int lineY = gp.screenHeight / 2 - gp.tileSize;
        int lineMargin = gp.tileSize * 3;
        g2.setColor(cachedColor(180, 120, 80, lineAlpha));
        g2.setStroke(STROKE_1);
        g2.drawLine(lineMargin, lineY, gp.screenWidth - lineMargin, lineY);

        int x;
        int y;
        String text;

        float titlePulse = fastPulse(animTick, 1);

        g2.setFont(cachedFont(Font.BOLD, 84f));
        text = "YOU DIED";
        x = getXforCenteredText(text);
        y = gp.screenHeight / 2 - gp.tileSize * 2;

        g2.setColor(cachedColor(20, 15, 10, (int)(180 * a)));
        g2.drawString(text, x + 3, y + 3);
        int glowR = (int)(160 + 40 * titlePulse);
        int glowG = (int)(110 + 25 * titlePulse);
        int glowB = (int)(60 + 15 * titlePulse);
        g2.setColor(cachedColor(glowR, glowG, glowB, (int)(80 * a)));
        g2.drawString(text, x + 1, y + 1);
        int mainR = (int)(200 + 30 * titlePulse);
        int mainG = (int)(170 + 20 * titlePulse);
        g2.setColor(cachedColor(Math.min(255, mainR), Math.min(255, mainG), 130, (int)(255 * a)));
        g2.drawString(text, x, y);

        g2.setFont(cachedFont(Font.PLAIN, 20f));
        g2.setColor(cachedColor(140, 125, 110, (int)(160 * a)));
        String sub = "The echoes fade into silence...";
        int subX = getXforCenteredText(sub);
        g2.drawString(sub, subX, y + 48);

        int line2Y = y + 70;
        g2.setColor(cachedColor(180, 120, 80, (int)(30 * a)));
        g2.drawLine(lineMargin, line2Y, gp.screenWidth - lineMargin, line2Y);

        g2.setFont(cachedFont(Font.BOLD, 36f));
        java.util.List<MenuItem> opts = gameOverMenu().items();
        int buttonW = gp.tileSize * 5;
        int buttonH = (int)(gp.tileSize * 0.9);
        int buttonX = gp.screenWidth / 2 - buttonW / 2;
        y = line2Y + gp.tileSize;

        gameOverMenu().beginRects();

        for (int i = 0; i < opts.size(); i++) {
            text = opts.get(i).label;
            int btnY = y + i * (buttonH + 20);
            int rectY = btnY - buttonH + 16;
            gameOverMenu().recordRect(buttonX, rectY, buttonW, buttonH);
            boolean sel = (commandNum == i);

            if (sel) {
                g2.setColor(cachedColor(80, 55, 30, (int)(90 * a)));
                g2.fillRoundRect(buttonX, rectY, buttonW, buttonH, 14, 14);
                g2.setColor(cachedColor(200, 160, 100, (int)(160 * a)));
                g2.setStroke(STROKE_25);
                g2.drawRoundRect(buttonX, rectY, buttonW, buttonH, 14, 14);
                g2.setColor(cachedColor(245, 225, 190, (int)(255 * a)));
            } else {
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

        // SUB WINDOW — centered at any resolution
        int frameWidth  = Math.min(520, (int)(gp.screenWidth * 0.42f));
        int frameHeight = Math.min(660, (int)(gp.screenHeight * 0.92f));
        int frameX = (gp.screenWidth  - frameWidth)  / 2;
        int frameY = (gp.screenHeight - frameHeight) / 2;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight, THEME_OPTIONS);

        switch (subState) {
            case 0: options_top ( frameX, frameY ); break;
            case 1: options_fullScreenNotification ( frameX, frameY ); break;
            case 2: options_control ( frameX, frameY ); break;
            case 3: options_endGameConfirmation ( frameX, frameY ); break;
        }

        gp.keyH.enterPressed = false;
    }
    public void drawCharacterScreen() {

        float pulse = fastPulse(animTick, 1);
        float leafSway = fastSin(animTick, 1) * 3f;

        final int frameWidth  = Math.min(384, (int)(gp.screenWidth * 0.30f));
        final int frameHeight = gp.screenHeight - 24;
        final int frameX = (int)(gp.screenWidth * 0.02f);
        final int frameY = 12;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight, THEME_CHARACTER);

        final int pad = 16;
        final int leftX = frameX + pad;
        final int rightX = frameX + frameWidth - pad;
        final int contentW = rightX - leftX;

        g2.setColor(cachedColor(60, 130, 60, (int)(40 + 20 * pulse)));
        g2.fillRoundRect(leftX + (int)leafSway, frameY + 5, contentW, 2, 4, 4);
        g2.fillRoundRect(leftX - (int)leafSway, frameY + frameHeight - 7, contentW, 2, 4, 4);

        int curY = frameY + 26;

        g2.setFont(cachedFont(Font.BOLD, 24F));
        String charTitle = "Character";
        int ctW = cachedFM().stringWidth(charTitle);
        int ctX = frameX + frameWidth / 2 - ctW / 2;
        g2.setColor(cachedColor(0, 0, 0, 100));
        g2.drawString(charTitle, ctX + 1, curY + 1);
        g2.setColor(cachedColor(200, 180, 110, (int)(210 + 45 * pulse)));
        g2.drawString(charTitle, ctX, curY);
        curY += 14;

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
        g2.drawImage(gp.player.down1, portraitX, portraitY, portraitSize, portraitSize);

        int infoX = portraitX + portraitSize + 12;
        g2.setFont(cachedFont(Font.BOLD, 17F));
        g2.setColor(LVL_BADGE);
        g2.drawString("Lv. " + gp.player.level, infoX, portraitY + 18);
        g2.setFont(cachedFont(Font.PLAIN, 12F));
        g2.setColor(cachedColor(160, 180, 140));
        g2.drawString("Adventurer", infoX, portraitY + 33);
        g2.setFont(cachedFont(Font.BOLD, 10F));
        String spStr = "SP:" + gp.player.skillPoints;
        int spTxtW = cachedFM().stringWidth(spStr);
        g2.setColor(cachedColor(100, 60, 180, (int)(50 + 30 * pulse)));
        g2.fillRoundRect(infoX, portraitY + 38, spTxtW + 8, 14, 6, 6);
        g2.setColor(cachedColor(200, 170, 255));
        g2.drawString(spStr, infoX + 4, portraitY + 49);

        curY = portraitY + portraitSize + 10;

        int heartSz = 16;
        int heartGap = 2;
        for (int i = 0; i < gp.player.maxLife; i++)
            g2.drawImage(Hearts_Empty, leftX + i * (heartSz + heartGap), curY, heartSz, heartSz);
        for (int i = 0; i < gp.player.life; i++)
            g2.drawImage(Hearts_Full, leftX + i * (heartSz + heartGap), curY, heartSz, heartSz);
        curY += heartSz + 3;
        drawStatBar(leftX, curY, contentW, 7, (float) gp.player.life / Math.max(1, gp.player.maxLife), HP_BAR_BG, HP_BAR_FILL, HP_BAR_GLOW);
        curY += 12;

        int crystalSz = heartSz - 2;
        for (int i = 0; i < gp.player.maxMana; i++)
            g2.drawImage(Crystal_Empty, leftX + i * (heartSz + heartGap), curY, crystalSz, crystalSz);
        for (int i = 0; i < gp.player.mana; i++)
            g2.drawImage(Crystal_Full, leftX + i * (heartSz + heartGap), curY, crystalSz, crystalSz);
        curY += crystalSz + 3;
        drawStatBar(leftX, curY, contentW, 6, (float) gp.player.mana / Math.max(1, gp.player.maxMana), MP_BAR_BG, MP_BAR_FILL, MP_BAR_GLOW);
        curY += 24;

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

        curY = drawSectionHeader(g2, "PROGRESSION", leftX, rightX, curY, pulse);
        g2.setFont(cachedFont(Font.PLAIN, 14F));
        g2.setColor(XP_BAR_FILL); g2.drawString("Exp", leftX, curY);
        String expStr = gp.player.exp + " / " + gp.player.nextLevelExp;
        g2.setColor(cachedColor(235,230,215)); g2.drawString(expStr, getXforAlignToRightText(expStr, rightX), curY);
        drawStatBar(leftX, curY + 5, contentW, 6, gp.player.nextLevelExp > 0 ? (float) gp.player.exp / gp.player.nextLevelExp : 0, XP_BAR_BG, XP_BAR_FILL, XP_BAR_GLOW);
        curY += 22 + sectionGap;

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

        g2.setColor(cachedColor(80,120,50,(int)(50+20*pulse)));
        g2.fillRect(leftX, curY - 8, contentW, 1);
        curY = drawSectionHeader(g2, "EQUIPMENT", leftX, rightX, curY, pulse);

        int remainingH = (frameY + frameHeight - 14) - (curY + 14);
        int iconSz = Math.min(72, Math.max(44, remainingH));
        int halfW = contentW / 2;
        g2.setFont(cachedFont(Font.PLAIN, 13F));
        g2.setColor(cachedColor(180,200,140)); g2.drawString("Weapon", leftX, curY);
        if (gp.player.currentWeapon != null) {
            g2.drawImage(gp.player.currentWeapon.down1, leftX + (halfW - iconSz) / 2, curY + 4, iconSz, iconSz);
        }
        g2.setColor(cachedColor(180,200,140)); g2.drawString("Shield", leftX + halfW, curY);
        if (gp.player.currentShield != null) {
            g2.drawImage(gp.player.currentShield.down1, leftX + halfW + (halfW - iconSz) / 2, curY + 4, iconSz, iconSz);
        }
    }

    /** Draws a section header label with a separator line, returns the Y for the first content row. */
    private int drawSectionHeader(GdxRenderer g2, String label, int leftX, int rightX, int y, float pulse) {
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

        int frameWidth  = Math.min(384, (int)(gp.screenWidth * 0.30f));
        int frameHeight = gp.tileSize * 5;
        int frameX = gp.screenWidth - frameWidth - 16;  // right-aligned: works at any tileSize/resolution
        int frameY = gp.tileSize;
        drawSubWindow(frameX, frameY, frameWidth, frameHeight, THEME_INVENTORY);

        g2.setColor(Color.white);
            counter++;
            float pulse = fastPulse(counter, 1); // 0..1

            String invTitle = "Inventory";
            int invTitleY = frameY - gp.tileSize/2;
            g2.setFont(cachedFont(Font.PLAIN, 28F));
            g2.setColor(cachedColor(0,0,0,160));
            int tlen = cachedFM().stringWidth(invTitle);
            g2.drawString(invTitle, frameX + (frameWidth/2) - tlen/2 + 3, invTitleY + 3);
            int r = (int)(218 + 37 * pulse);
            int gcol = (int)(165 + 20 * pulse);
            int b = (int)(32 + 8 * pulse);
            g2.setColor(cachedColor(Math.min(255,r), Math.min(255,gcol), Math.min(255,b)));
            g2.drawString(invTitle, frameX + (frameWidth/2) - tlen/2, invTitleY);

            String invText = "Occupied: " + gp.player.inventory.size() + " / " + gp.player.maxInventorySize;
            g2.setFont(cachedFont(Font.PLAIN, 16F));
            g2.setColor(cachedColor(200,200,200,220));
            g2.drawString(invText, frameX + 18, invTitleY + 26);

            Color headerBg = cachedColor(30,30,30,120);
            g2.setColor(headerBg);
            g2.fillRoundRect(frameX + 8, frameY + 10, frameWidth - 16, gp.tileSize - 6, 12, 12);
        final int slotXstart = frameX + 30;
        final int slotYstart = frameY + 30;
        int slotSize = gp.tileSize + 3;
        int maxCol = 5;
        int maxRow = 4;

        g2.setColor(cachedColor(50, 50, 50, 150));
        for (int row = 0; row < maxRow; row++) {
            for (int col = 0; col < maxCol; col++) {
                int x = slotXstart + col * slotSize;
                int y = slotYstart + row * slotSize;
                g2.fillRoundRect(x, y, gp.tileSize, gp.tileSize, 10, 10);
            }
        }

        for (int i = 0; i < gp.player.inventory.size(); i++) {
            int row = i / maxCol;
            int col = i % maxCol;
            int slotX = slotXstart + col * slotSize;
            int slotY = slotYstart + row * slotSize;

            if (gp.player.inventory.get(i) == gp.player.currentShield ||
                    gp.player.inventory.get(i) == gp.player.currentWeapon) {
                g2.setColor(cachedColor(240, 190, 90));
                g2.fillRoundRect(slotX, slotY, gp.tileSize, gp.tileSize, 10, 10);
            }

            g2.drawImage(gp.player.inventory.get(i).down1, slotX, slotY);

            if (gp.player.inventory.get(i).amount > 1) {
                g2.setFont(cachedFont(Font.PLAIN, 32f));
                String s = "" + gp.player.inventory.get(i).amount;
                int amountX = getXforAlignToRightText(s, slotX + 70);
                int amountY = slotY + gp.tileSize;

                g2.setColor(cachedColor(60, 60, 60));
                g2.drawString(s, amountX, amountY);
                g2.setColor(Color.white);
                g2.drawString(s, amountX - 3, amountY - 3);
            }
        }

        int cursorX = slotXstart + (slotSize * slotCol);
        int cursorY = slotYstart + (slotSize * slotRow);
        int cursorWidth = gp.tileSize;
        int cursorHeight = gp.tileSize;
        g2.setColor(cachedColor(255, 255, 255, 40));
        g2.fillRoundRect(cursorX, cursorY, cursorWidth, cursorHeight, 10, 10);
        float rawStroke = 2f + 2f * fastPulse(counter, 2);
        int strokeKey = Math.round(rawStroke * 2); // quantize to 0.5 steps
        g2.setColor(Color.white);
        g2.setStroke(cachedStroke(strokeKey * 0.5f));
        g2.drawRoundRect(cursorX, cursorY, cursorWidth, cursorHeight, 10, 10);

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
        g2.setColor(cachedColor(20,20,20,140));
        g2.fillRoundRect(frameX + 8, hintAreaY + 6, frameWidth - 16, hintAreaH, 12, 12);
        g2.setFont(cachedFont(Font.PLAIN, 18F));
        g2.setColor(cachedColor(200,200,200));
        int hintTextY = hintAreaY + 6 + hintAreaH/2 + 6;
        g2.drawString("Drop (BACKSPACE)", frameX + 20, hintTextY);
        if (!actionHint.isEmpty()) {
            int tailX = frameX + frameWidth - 20;
            int ax = getXforAlignToRightText(actionHint, tailX);
            g2.drawString(actionHint, ax, hintTextY);
        }

        int dFrameX = frameX;
        int dFrameY = frameY + frameHeight + hintAreaH + 12; // shift down to make room for hint strip
        int dFrameWidth = frameWidth;
        int dFrameHeight = gp.tileSize * 3;

        if (itemIndex < gp.player.inventory.size()) {
            Entity item = gp.player.inventory.get(itemIndex);
            if (item != null) {
                drawSubWindow(dFrameX, dFrameY, dFrameWidth, dFrameHeight, THEME_INVENTORY);
                int iconX = dFrameX + 20;
                int iconY = dFrameY + 20;
                g2.drawImage(item.down1, iconX, iconY, gp.tileSize, gp.tileSize);
                g2.setFont(cachedFont(Font.PLAIN, 28F));
                g2.setColor(Color.white);
                g2.drawString(item.name, iconX + gp.tileSize + 10, iconY + gp.tileSize / 2 + 5);

                int statY = iconY + gp.tileSize / 2 + 24;
                g2.setFont(cachedFont(Font.PLAIN, 18F));
                if (item.type == Entity.TYPE_SWORD && item.attackValue != 0) {
                    int diff = item.attackValue - (gp.player.currentWeapon != null ? gp.player.currentWeapon.attackValue : 0);
                    drawStatComparison(iconX + gp.tileSize + 10, statY, "ATK " + item.attackValue, diff, item == gp.player.currentWeapon);
                } else if (item.type == Entity.TYPE_SHIELD && item.defenseValue != 0) {
                    int diff = item.defenseValue - (gp.player.currentShield != null ? gp.player.currentShield.defenseValue : 0);
                    drawStatComparison(iconX + gp.tileSize + 10, statY, "DEF " + item.defenseValue, diff, item == gp.player.currentShield);
                }

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
    private static final Stroke OPT_STROKE_BORDER = STROKE_3;
    private static final Stroke OPT_STROKE_THIN   = STROKE_1;
    private static final Stroke OPT_STROKE_SEL    = STROKE_2;

    private static final String[] GRAPHICS_QUALITY_NAMES = { "Low", "Medium", "High" };

    /**
     * The Settings menu (subState 0) as one declarative list — toggles, a selector and sliders with
     * their get/set lambdas. This replaces the old split where labels + Enter-actions lived in
     * options_top() and the ◀▶ handling lived in KeyHandler.adjustOptionsVolume(). Add a setting by
     * adding one row here.
     */
    public Menu optionsMenu() {
        if (optionsMenu == null) {
            optionsMenu = Menu.of("Settings", THEME_OPTIONS)
                .onNavigate(() -> gp.playSE(audio.SFX.MENU_SELECT))
                .toggle("Full Screen", () -> gp.fullScreenOn, () -> {
                    gp.applyFullScreenSetting(!gp.fullScreenOn);
                    gp.playSE(audio.SFX.MENU_SELECT);
                    gp.config.saveConfig();
                })
                .toggle("V-Sync", () -> gp.vSyncOn, () -> {
                    gp.setVSync(!gp.vSyncOn);
                    gp.config.saveConfig();
                })
                .toggle("Perf Mode", () -> gp.config.fpsTarget == 30, () -> {
                    gp.applyFpsTarget(gp.config.fpsTarget == 30 ? 60 : 30);
                    gp.playSE(audio.SFX.MENU_SELECT);
                    gp.config.saveConfig();
                })
                .selector("Graphics",
                    () -> GRAPHICS_QUALITY_NAMES[gp.config.graphicsQuality],
                    () -> cycleGraphicsQuality(-1),
                    () -> cycleGraphicsQuality(+1))
                .slider("Music", () -> gp.audio.getMusicVolume(), 5,
                    () -> { gp.audio.setMusicVolume(gp.audio.getMusicVolume() - 1); gp.playSE(audio.SFX.MENU_SELECT); },
                    () -> { gp.audio.setMusicVolume(gp.audio.getMusicVolume() + 1); gp.playSE(audio.SFX.MENU_SELECT); })
                .slider("Sound FX", () -> gp.audio.getSEVolume(), 5,
                    () -> { gp.audio.setSEVolume(gp.audio.getSEVolume() - 1); gp.playSE(audio.SFX.MENU_SELECT); },
                    () -> { gp.audio.setSEVolume(gp.audio.getSEVolume() + 1); gp.playSE(audio.SFX.MENU_SELECT); })
                .button("Controls", () -> { subState = 2; commandNum = 0; })
                .button("End Game", () -> { subState = 3; commandNum = 0; })
                .button("Save Game", () -> {
                    gp.saveLoad.save();
                    addMessage("Game saved.", Color.WHITE);
                    gp.playSE(audio.SFX.MENU_SELECT);
                })
                .item(MenuItem.button("Back", () -> {
                    gp.gameState = GamePanel.playState; commandNum = 0; gp.config.saveConfig();
                }).separator().centered());
        }
        return optionsMenu;
    }

    private void cycleGraphicsQuality(int change) {
        int q = (gp.config.graphicsQuality + change) % 3;
        if (q < 0) q += 3;
        gp.config.graphicsQuality = q;
        if (gp.eManager.lightning != null) gp.eManager.lightning.clearShadowCaches();
        gp.playSE(audio.SFX.MENU_SELECT);
        gp.config.saveConfig();
    }

    public void options_top( int frameX, int frameY ) {

        int fw = Math.min(520, (int)(gp.screenWidth * 0.42f));
        int fh = Math.min(660, (int)(gp.screenHeight * 0.92f));

        // Declarative Settings menu \u2014 one list of rows (see optionsMenu()). Draw the rows into the
        // frame the caller already drew (drawItems, not draw, so we don't double-draw the panel).
        // Reserve space at the bottom for the server-status line.
        Menu menu = optionsMenu();
        menu.setSelected(commandNum);
        int rowsH = fh - 40; // leave room for the status footer
        menu.itemHeight(46).gap(6).padding(20).titleSize(36).itemSize(26)
            .drawItems(this, g2, frameX, frameY, fw, rowsH);

        int statusY = frameY + fh - 14;
        boolean online = gp.saveLoad.isServerOnline();
        g2.setFont(cachedFont(Font.PLAIN, 18F));
        String statusText = "Server: " + (online ? "Online" : "Offline");
        int sw = cachedFM().stringWidth(statusText);
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
    void drawMedievalToggle(int x, int y, boolean on) {
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
    void drawMedievalSlider(int x, int y, int value, int max) {
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
        g2.drawString("\u25B6", x, y);  // â–¶ unicode arrow
        g2.setFont(cachedFont(Font.PLAIN, 26F));  // restore
    }
    public void options_fullScreenNotification ( int frameX, int frameY ) {

        int fw = Math.min(520, (int)(gp.screenWidth * 0.42f));
        g2.setFont(cachedFont(Font.BOLD, 30F));
        g2.setColor(OPT_GOLD);
        String noteTitle = "Notice";
        int ntw = cachedFM().stringWidth(noteTitle);
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
        int bw = cachedFM().stringWidth(back);
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

        int fw  = Math.min(520, (int)(gp.screenWidth  * 0.42f));
        int fh  = Math.min(660, (int)(gp.screenHeight * 0.92f));
        int pad = 30;

        g2.setFont(cachedFont(Font.BOLD, 34F));
        g2.setColor(OPT_GOLD);
        String ctrlTitle = "Controls";
        int ctw = cachedFM().stringWidth(ctrlTitle);
        g2.drawString(ctrlTitle, frameX + fw / 2 - ctw / 2, frameY + 48);
        g2.setColor(OPT_SEPARATOR);
        g2.fillRect(frameX + pad, frameY + 58, fw - pad * 2, 2);

        // Key bindings table
        g2.setFont(cachedFont(Font.PLAIN, 19F));
        String[] actions = {
            "Move", "Attack / Confirm", "Shoot", "Dodge Roll", "Blink",
            "Shockwave", "Void Snare", "Frost Nova", "Overdrive",
            "Inventory", "Skill Tree", "Quest Log", "Pause", "Options", "Debug Tools"
        };
        String[] keys = {
            "W A S D", "ENTER", "F", "SHIFT + Move", "SPACE",
            "Z", "X", "C", "V", "E", "K", "Q", "P", "ESC", "Ctrl+D / F9"
        };

        int textX   = frameX + pad;
        int keyX    = frameX + fw - pad;
        int rowH    = 34;
        // reserve space at the bottom for the Back button (36px button + 16px margin each side)
        int backAreaH = 68;
        int listTop   = frameY + 70;
        int listH     = fh - (listTop - frameY) - backAreaH;
        int maxVis    = listH / rowH;

        // clamp scroll
        int maxScroll = Math.max(0, actions.length - maxVis);
        controlScroll = Math.max(0, Math.min(controlScroll, maxScroll));

        // clip to list area so rows don't bleed into the Back button
        g2.setClip(frameX, listTop, fw, listH);

        for (int i = controlScroll; i < actions.length && i < controlScroll + maxVis; i++) {
            int ry = listTop + (i - controlScroll) * rowH + rowH - 6;
            if (i % 2 == 0) {
                g2.setColor(cachedColor(40, 35, 25, 60));
                g2.fillRoundRect(frameX + 12, ry - 26, fw - 24, rowH - 4, 8, 8);
            }
            g2.setColor(OPT_TEXT);
            g2.drawString(actions[i], textX + 5, ry);
            g2.setColor(OPT_GOLD_DIM);
            int kw = cachedFM().stringWidth(keys[i]);
            g2.drawString(keys[i], keyX - kw - 5, ry);
        }

        g2.clearClip();

        // scroll hint if there are more rows above or below
        if (maxScroll > 0) {
            g2.setFont(cachedFont(Font.PLAIN, 13F));
            g2.setColor(cachedColor(120, 115, 105));
            String hint = (controlScroll > 0 ? "▲ " : "  ") + "Scroll" + (controlScroll < maxScroll ? " ▼" : "");
            int hw = cachedFM().stringWidth(hint);
            g2.drawString(hint, frameX + fw / 2 - hw / 2, frameY + fh - backAreaH + 2);
        }

        // separator above Back button
        g2.setColor(OPT_SEPARATOR);
        g2.fillRect(frameX + pad, frameY + fh - backAreaH + 6, fw - pad * 2, 1);

        // BACK button — always at fixed position inside the reserved area
        g2.setFont(cachedFont(Font.PLAIN, 26F));
        String back = "Back";
        int bw   = cachedFM().stringWidth(back);
        int backX = frameX + fw / 2 - bw / 2;
        int backY = frameY + fh - backAreaH + 46;
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
        if (sel && gp.keyH.enterPressed) {
            subState = 0;
            commandNum = 4;
        }
    }
    public void options_endGameConfirmation( int frameX, int frameY  ) {

        int fw = Math.min(520, (int)(gp.screenWidth * 0.42f));

        // Warning title
        g2.setFont(cachedFont(Font.BOLD, 30F));
        g2.setColor(cachedColor(200, 80, 60));
        String warn = "Quit Game?";
        int ww = cachedFM().stringWidth(warn);
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

        // SAVE & QUIT / QUIT / NO buttons
        g2.setFont(cachedFont(Font.PLAIN, 26F));
        String[] opts = { "Save & Quit", "Quit", "No" };
        int btnY = frameY + gp.tileSize * 5 + 20;
        for (int i = 0; i < opts.length; i++) {
            int bw = cachedFM().stringWidth(opts[i]);
            int bx = frameX + fw / 2 - bw / 2;
            int by = btnY + i * 48;
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

        if (gp.keyH.enterPressed) {
            if (commandNum == 0) {
                // Save & Quit: persist game then return to title
                gp.saveLoad.save();
                addMessage("Game saved.", Color.WHITE);
                subState = 0;
                titleScreenState = 0;
                gp.stopMusic();
                gp.resetGame(true);
                gp.gameState = GamePanel.titleState;
            } else if (commandNum == 1) {
                // Quit without saving
                subState = 0;
                commandNum = 0;
                titleScreenState = 0;
                gp.stopMusic();
                gp.resetGame(true);
                gp.gameState = GamePanel.titleState;
            } else if (commandNum == 2) {
                // Cancel — back to options menu
                subState = 0;
                commandNum = 7; // End Game row
            }
        }
    }
    private int transitionHoldCounter = 0;
    private static final int TRANSITION_HOLD_FRAMES = 10; // frames to stay fully black after map loads

    public void drawTransition(GdxRenderer g2) {
        // Phase 0: Fade to black
        if (subState == 0) {
            transitionAlpha += 0.033f; // ~30 frames
            if (transitionAlpha >= 1.0f) {
                transitionAlpha = 1.0f;
                gp.changeMap();
                transitionHoldCounter = 0;
                subState = 1;
            }
        }
        // Phase 1: Hold fully black — wait for player + camera to settle
        else if (subState == 1) {
            transitionAlpha = 1.0f;
            transitionHoldCounter++;
            if (transitionHoldCounter >= TRANSITION_HOLD_FRAMES) {
                subState = 2;
            }
        }
        // Phase 2: Fade from black — slow cinematic reveal
        else if (subState == 2) {
            transitionAlpha -= 0.02f; // ~50 frames (~0.83s)
            if (transitionAlpha <= 0f) {
                transitionAlpha = 0f;
                subState = 0;
                gp.gameState = GamePanel.playState;
            }
        }

        g2.setColor(cachedColor(0, 0, 0, (int)(transitionAlpha * 255)));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);
    }

    public void drawLevelUpScreen() {
        int w = 420, h = 340;
        int x = (gp.screenWidth - w) / 2;
        int y = (gp.screenHeight - h) / 2;

        g2.setColor(cachedColor(0, 0, 0, 255));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // Level-up panel.
        drawPanel(x, y, w, h, THEME_LEVELUP);

        g2.setColor(cachedColor(255, 200, 60, 0)); // TODO(gfx): gradient
        g2.setStroke(STROKE_15);
        g2.drawLine(x + 40, y + 8, x + w / 2, y + 8);
        g2.setColor(cachedColor(255, 200, 60, 200)); // TODO(gfx): gradient
        g2.drawLine(x + w / 2, y + 8, x + w - 40, y + 8);

        g2.setFont(cachedFont(Font.BOLD, 34f));
        String title = "LEVEL UP";
        int tw = cachedFM().stringWidth(title);
        int tx = x + (w - tw) / 2;
        g2.setColor(cachedColor(0, 0, 0, 120));
        g2.drawString(title, tx + 2, y + 48);
        g2.setColor(cachedColor(255, 230, 120)); // TODO(gfx): gradient
        g2.drawString(title, tx, y + 46);

        g2.setFont(cachedFont(Font.BOLD, 16f));
        String lvl = "Lv. " + gp.player.level;
        int lw = cachedFM().stringWidth(lvl);
        int badgeX = x + (w - lw - 20) / 2;
        g2.setColor(cachedColor(255, 200, 60, 25));
        g2.fillRoundRect(badgeX, y + 56, lw + 20, 22, 11, 11);
        g2.setColor(cachedColor(255, 220, 100, 100));
        g2.setStroke(STROKE_1);
        g2.drawRoundRect(badgeX, y + 56, lw + 20, 22, 11, 11);
        g2.setColor(cachedColor(255, 220, 130));
        g2.drawString(lvl, badgeX + 10, y + 73);

        g2.setColor(cachedColor(120, 100, 60, 80));
        g2.drawLine(x + 30, y + 88, x + w - 30, y + 88);

        g2.setFont(cachedFont(Font.PLAIN, 14f));
        g2.setColor(cachedColor(180, 170, 150));
        String sub = "Choose a stat to upgrade";
        int sw2 = cachedFM().stringWidth(sub);
        g2.drawString(sub, x + (w - sw2) / 2, y + 106);

        String[] options = gp.player.levelUpOptions;
        if (options == null) return;

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
                g2.setColor(cachedColor(255, 200, 60, 15));
                g2.fillRoundRect(optX - 4, oy - 4, optW + 8, optH + 8, 14, 14);

                g2.setColor(cachedColor(255, 200, 60, 40)); // TODO(gfx): gradient
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);

                g2.setColor(cachedColor(255, 210, 80, 180));
                g2.setStroke(STROKE_2);
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);

                g2.setColor(cachedColor(255, 210, 80));
                g2.fillRoundRect(optX, oy + 6, 3, optH - 12, 2, 2);
            } else {
                g2.setColor(cachedColor(40, 35, 55, 100));
                g2.fillRoundRect(optX, oy, optW, optH, 10, 10);
                g2.setColor(cachedColor(80, 70, 90, 60));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(optX, oy, optW, optH, 10, 10);
            }

            int iconR = 14;
            int iconCX = optX + 24;
            int iconCY = oy + optH / 2;
            Color sc = statColors[i % statColors.length];
            g2.setColor(cachedColor(sc.getRed(), sc.getGreen(), sc.getBlue(), selected ? 60 : 30));
            g2.fillOval(iconCX - iconR, iconCY - iconR, iconR * 2, iconR * 2);
            g2.setColor(selected ? sc : cachedColor(sc.getRed(), sc.getGreen(), sc.getBlue(), 140));
            g2.setStroke(STROKE_15);
            g2.drawOval(iconCX - iconR, iconCY - iconR, iconR * 2, iconR * 2);

            g2.setFont(cachedFont(Font.PLAIN, 14f));
            g2.setColor(selected ? sc.brighter() : sc);
            int iw = cachedFM().stringWidth(icons[i % icons.length]);
            g2.drawString(icons[i % icons.length], iconCX - iw / 2, iconCY + 5);

            g2.setFont(cachedFont(selected ? Font.BOLD : Font.PLAIN, 20f));
            g2.setColor(selected ? Color.WHITE : cachedColor(160, 155, 145));
            g2.drawString(options[i], optX + 50, oy + 30);

            if (selected) {
                g2.setFont(cachedFont(Font.PLAIN, 12f));
                g2.setColor(cachedColor(255, 210, 80, 180));
                g2.drawString("\u25B6", optX + optW - 22, oy + 28);
            }
        }

        g2.setFont(cachedFont(Font.PLAIN, 13f));
        g2.setColor(cachedColor(120, 115, 105));
        String hint = "[W/S] Navigate    [Enter] Confirm";
        int hw = cachedFM().stringWidth(hint);
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

        float pulse = fastPulse(animTick, 3);
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
        int tw = cachedFM().stringWidth(txt);
        int tx = gp.screenWidth / 2 - tw / 2;

        g2.setColor(cachedColor(0, 0, 0, (int)(160 * alphaScale)));
        g2.drawString(txt, tx + 2, y + 37);
        g2.setColor(cachedColor(255, 230, 130, (int)(255 * alphaScale)));
        g2.drawString(txt, tx, y + 35);
    }

    public void drawSkillTreeScreen() {
        // node.col = depth tier (0-5) -> Y (scrolls top-to-bottom, W/S)
        // node.row = branch    (0-3)  -> X (4 fixed columns,        A/D)
        final int NUM_BRANCHES = SKILL_TREE_BRANCH_NAMES.length;
        final int NUM_TIERS    = 6;
        final int NODE_R       = 26;
        final int TIER_STEP    = 110;
        final int HEADER_H     = 56;
        final int INFO_H       = 108;

        final int PW = 700, PH = 640;
        final int PX = (gp.screenWidth - PW) / 2;
        final int PY = (gp.screenHeight - PH) / 2;
        final int GRAPH_X  = PX + 10;
        final int GRAPH_Y  = PY + HEADER_H + 50;
        final int GRAPH_W  = PW - 20;
        final int GRAPH_H  = PH - (GRAPH_Y - PY) - INFO_H - 14;
        final int COL_STEP = GRAPH_W / NUM_BRANCHES;
        final int TREE_H   = NUM_TIERS * TIER_STEP;
        final int MAX_SCR  = Math.max(0, TREE_H - GRAPH_H);

        SkillTree.SkillNode[] nodes = gp.player.skillTree.getNodes();
        if (nodes.length == 0) return;
        ensureSkillTreeCacheCapacity(nodes.length);
        int selected = Math.max(0, Math.min(gp.player.skillTree.selectedIndex, nodes.length - 1));

        // smooth scroll: fixed-point *10 stored in scrollOffset
        SkillTree.SkillNode selNode = nodes[selected];
        int targetScroll = Math.max(0, Math.min(
            selNode.col * TIER_STEP + TIER_STEP / 2 - GRAPH_H / 2, MAX_SCR));
        int sf = gp.player.skillTree.scrollOffset;
        int tf = targetScroll * 10;
        if (sf < tf) sf = Math.min(sf + Math.max(1, (tf - sf) / 5), tf);
        else if (sf > tf) sf = Math.max(sf - Math.max(1, (sf - tf) / 5), tf);
        gp.player.skillTree.scrollOffset = sf;
        int scrollPx = sf / 10;

        // virtual Y for each node (before scroll)
        int revealMaxCol = gp.player.skillTree.getRevealMaxCol();
        for (int i = 0; i < nodes.length; i++) {
            SkillTree.SkillNode node = nodes[i];
            skillTreeNodeX[i] = GRAPH_X + node.row * COL_STEP + COL_STEP / 2;
            skillTreeNodeY[i] = node.col * TIER_STEP + TIER_STEP / 2;
            int reqIndex = node.requires != null ? gp.player.skillTree.findIndexById(node.requires) : -1;
            skillTreeReqIndex[i] = reqIndex;
            boolean revealed = node.col <= revealMaxCol;
            skillTreeRevealed[i] = revealed;
            skillTreeCanUnlock[i] = revealed && !node.unlocked && gp.player.skillPoints >= node.cost
                && (node.requires == null || (reqIndex >= 0 && nodes[reqIndex].unlocked));
        }

        // full-screen dim
        g2.setColor(cachedColor(3, 4, 8, 215));
        g2.fillRect(0, 0, gp.screenWidth, gp.screenHeight);

        // panel background.
        drawPanel(PX, PY, PW, PH, THEME_SKILLTREE);

        g2.setFont(cachedFont(Font.BOLD, 26f));
        String title = "SKILL TREE";
        int titleW = cachedFM().stringWidth(title);
        g2.setColor(cachedColor(255, 220, 100));
        g2.drawString(title, PX + (PW - titleW) / 2, PY + 36);

        g2.setFont(cachedFont(Font.BOLD, 14f));
        String pts = "SP: " + gp.player.skillPoints;
        int ptsTW = cachedFM().stringWidth(pts) + 20;
        int ptsBX = PX + PW - ptsTW - 12, ptsBY = PY + 12;
        g2.setColor(cachedColor(28, 38, 58, 200));
        g2.fillRoundRect(ptsBX, ptsBY, ptsTW, 24, 8, 8);
        g2.setColor(cachedColor(100, 160, 255, 160));
        g2.setStroke(STROKE_15);
        g2.drawRoundRect(ptsBX, ptsBY, ptsTW, 24, 8, 8);
        g2.setColor(cachedColor(180, 210, 255));
        g2.drawString(pts, ptsBX + 10, ptsBY + 17);

        // branch headers
        int headerY = PY + 42;
        for (int b = 0; b < NUM_BRANCHES; b++) {
            Color bc = SKILL_TREE_BRANCH_COLORS[b];
            int hx = GRAPH_X + b * COL_STEP;
            g2.setColor(cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 28));
            g2.fillRoundRect(hx + 4, headerY + 2, COL_STEP - 8, HEADER_H - 4, 8, 8);
            g2.setColor(cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 75));
            g2.setStroke(STROKE_1);
            g2.drawRoundRect(hx + 4, headerY + 2, COL_STEP - 8, HEADER_H - 4, 8, 8);
            g2.setFont(cachedFont(Font.BOLD, 13f));
            g2.setColor(cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 230));
            String bn = SKILL_TREE_BRANCH_NAMES[b].toUpperCase();
            int bnW = cachedFM().stringWidth(bn);
            g2.drawString(bn, hx + (COL_STEP - bnW) / 2, headerY + HEADER_H / 2 + 6);
        }
        g2.setColor(cachedColor(200, 160, 70, 50));
        g2.setStroke(STROKE_1);
        g2.drawLine(PX + 12, GRAPH_Y - 4, PX + PW - 12, GRAPH_Y - 4);

        // clip viewport
        g2.setClip(GRAPH_X, GRAPH_Y, GRAPH_W, GRAPH_H);

        float pulse = fastPulse(animTick, 2);

        // vertical column stripe tints
        for (int b = 0; b < NUM_BRANCHES; b++) {
            Color bc = SKILL_TREE_BRANCH_COLORS[b];
            g2.setColor(cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 10));
            g2.fillRect(GRAPH_X + b * COL_STEP, GRAPH_Y, COL_STEP, GRAPH_H);
        }

        // connector lines — geometry is precomputed when scrollPx changes (static layout)
        ensureSkillTreeConnectorCache(nodes.length);
        if (!stConnGeomValid || stConnGeomScrollPx != scrollPx) {
            for (int i = 0; i < nodes.length; i++) {
                stConnHasArrow[i] = false;
                if (nodes[i].requires == null) continue;
                int pi = skillTreeReqIndex[i];
                if (pi < 0) continue;
                int sx1 = skillTreeNodeX[pi], sy1 = GRAPH_Y + skillTreeNodeY[pi] - scrollPx;
                int sx2 = skillTreeNodeX[i],  sy2 = GRAPH_Y + skillTreeNodeY[i]  - scrollPx;
                double ang = Math.atan2(sy2 - sy1, sx2 - sx1);
                stConnLx1[i] = sx1 + (int)(Math.cos(ang) * (NODE_R + 3));
                stConnLy1[i] = sy1 + (int)(Math.sin(ang) * (NODE_R + 3));
                stConnLx2[i] = sx2 - (int)(Math.cos(ang) * (NODE_R + 3));
                stConnLy2[i] = sy2 - (int)(Math.sin(ang) * (NODE_R + 3));
                boolean pUnlocked2 = nodes[pi].unlocked;
                boolean cRevealed2 = skillTreeRevealed[i];
                if (pUnlocked2 && cRevealed2) {
                    double perp = ang + Math.PI / 2;
                    int ax = stConnLx2[i] - (int)(Math.cos(ang) * 7);
                    int ay = stConnLy2[i] - (int)(Math.sin(ang) * 7);
                    stConnArrowX[i][0] = stConnLx2[i]; stConnArrowX[i][1] = ax+(int)(Math.cos(perp)*4); stConnArrowX[i][2] = ax-(int)(Math.cos(perp)*4);
                    stConnArrowY[i][0] = stConnLy2[i]; stConnArrowY[i][1] = ay+(int)(Math.sin(perp)*4); stConnArrowY[i][2] = ay-(int)(Math.sin(perp)*4);
                    stConnHasArrow[i] = true;
                }
            }
            stConnGeomValid = true;
            stConnGeomScrollPx = scrollPx;
        }
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i].requires == null) continue;
            int pi = skillTreeReqIndex[i];
            if (pi < 0) continue;
            boolean pUnlocked = nodes[pi].unlocked;
            boolean cUnlocked = nodes[i].unlocked;
            boolean cRevealed = skillTreeRevealed[i];
            Color lc;
            float thick;
            if (pUnlocked && cUnlocked) {
                Color bc = SKILL_TREE_BRANCH_COLORS[Math.min(nodes[i].row, SKILL_TREE_BRANCH_COLORS.length - 1)];
                lc = cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 200);
                thick = 2.8f;
            } else if (pUnlocked && cRevealed) {
                lc = cachedColor(190, 170, 100, 130); thick = 1.8f;
            } else {
                lc = cachedColor(70, 65, 55, 80); thick = 1.2f;
            }
            g2.setColor(lc);
            g2.setStroke(thick > 2f ? STROKE_R28 : (thick > 1.5f ? STROKE_R18 : STROKE_R12));
            g2.drawLine(stConnLx1[i], stConnLy1[i], stConnLx2[i], stConnLy2[i]);
            if (stConnHasArrow[i]) {
                g2.fill(new gfx.geom.IntPolygon(stConnArrowX[i], stConnArrowY[i], 3));
            }
        }

        // node bubbles
        for (int i = 0; i < nodes.length; i++) {
            SkillTree.SkillNode n = nodes[i];
            boolean revealed   = skillTreeRevealed[i];
            boolean canUnlock  = skillTreeCanUnlock[i];
            boolean isSel      = (i == selected);
            Color bc = SKILL_TREE_BRANCH_COLORS[Math.min(n.row, SKILL_TREE_BRANCH_COLORS.length - 1)];
            int cx = skillTreeNodeX[i];
            int cy = GRAPH_Y + skillTreeNodeY[i] - scrollPx;
            if (cy + NODE_R + 20 < GRAPH_Y || cy - NODE_R - 20 > GRAPH_Y + GRAPH_H) continue;

            if (isSel) {
                int gA = (int)(80 + pulse * 100);
                int gR = NODE_R + 8 + (int)(pulse * 5);
                g2.setColor(cachedColor(255, 220, 100, gA));
                g2.fillOval(cx - gR, cy - gR, gR * 2, gR * 2);
                g2.setColor(cachedColor(255, 210, 80, gA / 2));
                g2.fillOval(cx - NODE_R - 4, cy - NODE_R - 4, (NODE_R + 4) * 2, (NODE_R + 4) * 2);
            }

            Color inner, outer;
            if (!revealed) {
                inner = cachedColor(28, 25, 22, 220); outer = cachedColor(70, 65, 55, 180);
            } else if (n.unlocked) {
                inner = cachedColor(bc.getRed()/3+10, bc.getGreen()/3+10, bc.getBlue()/3+10, 230);
                outer = cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 200);
            } else if (canUnlock) {
                inner = cachedColor(38, 34, 20, 220); outer = cachedColor(190, 170, 80, 180);
            } else {
                inner = cachedColor(22, 20, 28, 220); outer = cachedColor(55, 50, 68, 160);
            }
            g2.setColor(inner);
            g2.fillOval(cx - NODE_R, cy - NODE_R, NODE_R * 2, NODE_R * 2);
            g2.setColor(cachedColor(255, 255, 255, revealed ? 28 : 12));
            g2.fillOval(cx - NODE_R + 5, cy - NODE_R + 4, NODE_R - 4, NODE_R - 4);
            g2.setColor(outer);
            g2.setStroke(isSel ? STROKE_25 : (n.unlocked ? STROKE_2 : STROKE_15));
            g2.drawOval(cx - NODE_R, cy - NODE_R, NODE_R * 2, NODE_R * 2);
            if (n.unlocked) {
                int gr = NODE_R - 5;
                g2.setColor(cachedColor(bc.getRed(), bc.getGreen(), bc.getBlue(), 60));
                g2.drawOval(cx - gr, cy - gr, gr * 2, gr * 2);
            }

            // icon inside bubble
            g2.setFont(cachedFont(Font.BOLD, 15f));
            if (!revealed) {
                g2.setColor(cachedColor(110, 100, 85));
                String s = "?"; int sw = cachedFM().stringWidth(s);
                g2.drawString(s, cx - sw/2, cy + 6);
            } else if (n.unlocked) {
                g2.setColor(cachedColor(180, 255, 200));
                String s = "\u2714"; int sw = cachedFM().stringWidth(s);
                g2.drawString(s, cx - sw/2, cy + 6);
            } else if (canUnlock) {
                g2.setColor(cachedColor(255, 215, 100));
                String s = "\u25CF"; int sw = cachedFM().stringWidth(s);
                g2.drawString(s, cx - sw/2, cy + 6);
            } else {
                g2.setColor(cachedColor(120, 115, 105));
                String s = "\u25CB"; int sw = cachedFM().stringWidth(s);
                g2.drawString(s, cx - sw/2, cy + 6);
            }

            // name label below bubble
            if (revealed) {
                g2.setFont(cachedFont(Font.BOLD, 10f));
                int bcR = bc.getRed(), bcG = bc.getGreen(), bcB = bc.getBlue();
                Color nc = n.unlocked
                    ? cachedColor(Math.min(bcR+40,255), Math.min(bcG+40,255), Math.min(bcB+40,255), 230)
                    : (isSel ? cachedColor(255, 230, 160) : cachedColor(175, 168, 155));
                String lbl = n.name;
                int lblW = cachedFM().stringWidth(lbl);
                int maxW = COL_STEP - 10;
                if (lblW > maxW) {
                    while (lblW > maxW - 8 && lbl.length() > 3) {
                        lbl = lbl.substring(0, lbl.length() - 1);
                        lblW = cachedFM().stringWidth(lbl + "\u2026");
                    }
                    lbl += "\u2026";
                    lblW = cachedFM().stringWidth(lbl);
                }
                g2.setColor(cachedColor(0, 0, 0, 120));
                g2.drawString(lbl, cx - lblW/2 + 1, cy + NODE_R + 14 + 1);
                g2.setColor(nc);
                g2.drawString(lbl, cx - lblW/2, cy + NODE_R + 14);
            }

            // cost badge above bubble
            if (revealed && !n.unlocked) {
                g2.setFont(cachedFont(Font.BOLD, 10f));
                String cs = n.cost + "sp";
                int csW = cachedFM().stringWidth(cs) + 8;
                int csbX = cx - csW/2, csbY = cy - NODE_R - 18;
                Color badgeC = canUnlock ? cachedColor(255, 200, 60) : cachedColor(100, 95, 80);
                g2.setColor(cachedColor(10, 10, 18, 200));
                g2.fillRoundRect(csbX, csbY, csW, 14, 4, 4);
                g2.setColor(badgeC);
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(csbX, csbY, csW, 14, 4, 4);
                g2.drawString(cs, csbX + 4, csbY + 11);
            }
        }

        // restore clip
        g2.clearClip();

        // top edge fade
        g2.setColor(cachedColor(8, 7, 16, 210)); // TODO(gfx): gradient
        g2.fillRect(GRAPH_X, GRAPH_Y, GRAPH_W, 38);

        // bottom edge fade
        g2.setColor(cachedColor(8, 7, 16, 0)); // TODO(gfx): gradient
        g2.fillRect(GRAPH_X, GRAPH_Y + GRAPH_H - 38, GRAPH_W, 38);

        // scroll indicator
        if (MAX_SCR > 0) {
            int barX = PX + PW - 7, barY = GRAPH_Y + 4, barH = GRAPH_H - 8;
            int tH   = Math.max(20, barH * GRAPH_H / TREE_H);
            int tY   = barY + (int)((float) scrollPx / MAX_SCR * (barH - tH));
            g2.setColor(cachedColor(60, 55, 45, 110));
            g2.fillRoundRect(barX, barY, 4, barH, 2, 2);
            g2.setColor(cachedColor(200, 170, 80, 180));
            g2.fillRoundRect(barX, tY, 4, tH, 2, 2);
        }

        // info panel
        int infoX = PX + 12, infoY = PY + PH - INFO_H - 8, infoW = PW - 24;
        g2.setColor(cachedColor(12, 10, 20, 235));
        g2.fillRoundRect(infoX, infoY, infoW, INFO_H, 12, 12);
        g2.setColor(cachedColor(130, 110, 70, 130));
        g2.setStroke(STROKE_15);
        g2.drawRoundRect(infoX, infoY, infoW, INFO_H, 12, 12);

        SkillTree.SkillNode sel = nodes[selected];
        boolean selRev = skillTreeRevealed[selected];
        int bIdx = Math.max(0, Math.min(sel.row, SKILL_TREE_BRANCH_COLORS.length - 1));
        Color bcSel = SKILL_TREE_BRANCH_COLORS[bIdx];

        if (selRev) {
            g2.setFont(cachedFont(Font.BOLD, 10f));
            g2.setColor(cachedColor(bcSel.getRed(), bcSel.getGreen(), bcSel.getBlue(), 210));
            g2.drawString(SKILL_TREE_BRANCH_NAMES[bIdx].toUpperCase() + "  \u2014  Tier " + (sel.col + 1),
                infoX + 14, infoY + 16);
            g2.setFont(cachedFont(Font.BOLD, 18f));
            g2.setColor(cachedColor(245, 220, 120));
            g2.drawString(sel.name, infoX + 14, infoY + 36);
            g2.setFont(cachedFont(Font.PLAIN, 13f));
            g2.setColor(cachedColor(200, 195, 180));
            g2.drawString(sel.description, infoX + 14, infoY + 55);
            if (sel.requires != null) {
                g2.setFont(cachedFont(Font.PLAIN, 11f));
                g2.setColor(cachedColor(140, 135, 120));
                g2.drawString("Requires: " + sel.requires.replace('_', ' '), infoX + 14, infoY + 71);
            }
            boolean canUnlockSel = skillTreeCanUnlock[selected];
            String status = sel.unlocked
                ? "\u2714  Unlocked"
                : (canUnlockSel ? "[Enter] Unlock  \u2013  " + sel.cost + " SP"
                                : "Locked  (need prereq or " + sel.cost + " SP)");
            g2.setFont(cachedFont(Font.BOLD, 13f));
            g2.setColor(sel.unlocked ? cachedColor(120, 230, 150)
                : (canUnlockSel ? cachedColor(255, 215, 80) : cachedColor(145, 138, 125)));
            g2.drawString(status, infoX + 14, infoY + INFO_H - 16);
        } else {
            g2.setFont(cachedFont(Font.BOLD, 16f));
            g2.setColor(cachedColor(160, 150, 130));
            g2.drawString("Unknown Skill", infoX + 14, infoY + 28);
            g2.setFont(cachedFont(Font.PLAIN, 13f));
            g2.setColor(cachedColor(130, 122, 110));
            g2.drawString(getHiddenSkillTeaser(sel), infoX + 14, infoY + 50);
            g2.drawString("Unlock earlier skills to reveal this.", infoX + 14, infoY + 68);
        }

        // hint bar
        g2.setFont(cachedFont(Font.PLAIN, 12f));
        g2.setColor(cachedColor(140, 135, 118));
        String hint = "[W/S] Scroll    [A/D] Branch    [Enter] Unlock    [K/Esc] Close";
        int hw = cachedFM().stringWidth(hint);
        g2.drawString(hint, PX + (PW - hw) / 2, PY + PH - 5);
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

    /** Draw a window background with a specific per-window theme. */
    public void drawSubWindow(int x, int y, int width, int height, PanelTheme theme) {
        drawPanel(x, y, width, height, theme);
    }

    /** Draw a window background with the default gold-on-dark theme. */
    public void drawSubWindow(int x, int y, int width, int height) {
        drawPanel(x, y, width, height, THEME_DEFAULT);
    }

    /** Nine-slice panel with the 4 role colors given explicitly. */
    public void drawPanel(int x, int y, int width, int height,
                          Color main, Color shadow, Color highlight, Color highlight2) {
        drawPanel(x, y, width, height, new PanelTheme(main, shadow, highlight, highlight2));
    }

    /**
     * Draw a UI window background using the nine-slice UI.png, recolored to {@code theme}: the PNG's
     * marker colors (main/shadow/highlight/highlight2) are remapped to the theme's colors. The
     * recolored texture is baked once per unique color-set and cached. If UI.png isn't present,
     * falls back to the previous vector look so the game still renders.
     */
    public void drawPanel(int x, int y, int width, int height, PanelTheme theme) {
        if (uiPanelRaw == null) { drawSubWindowVector(x, y, width, height); return; }
        long key = paletteKey(theme.main(), theme.shadow(), theme.highlight(), theme.highlight2());
        Sprite themed = uiPanelCache.get(key);
        if (themed == null) {
            themed = GdxRenderer.bakePaletteSwap(uiPanelRaw, UI_MARKERS,
                    new Color[]{ theme.main(), theme.shadow(), theme.highlight(), theme.highlight2() });
            uiPanelCache.put(key, themed);
        }
        g2.drawNineSlice(themed, x, y, width, height);
    }

    /** Pack the 4 theme colors' 24-bit RGBs into a stable cache key. */
    private static long paletteKey(Color a, Color b, Color c, Color d) {
        long ka = a.getRGB() & 0xFFFFFFL, kb = b.getRGB() & 0xFFFFFFL;
        long kc = c.getRGB() & 0xFFFFFFL, kd = d.getRGB() & 0xFFFFFFL;
        // Mix the four 24-bit values; collisions are harmless (just a rare redundant bake).
        return (ka * 1000003L + kb) * 1000003L + kc ^ (kd << 1);
    }

    /**
     * Draw a menu button background sized to (x,y,w,h), recolored to {@code theme}. Uses the
     * nine-slice Button.png (palette-swapped + cached per theme) when present; otherwise falls back
     * to a themed rounded-rect (the same look the old hand-rolled menus used) so buttons still
     * render before art exists. When {@code selected} an accent overlay/border is drawn on top.
     * Used by {@link Menu} — the declarative menu layer.
     */
    public void drawButton(int x, int y, int w, int h, PanelTheme theme, boolean selected) {
        if (buttonPanelRaw != null) {
            long key = paletteKey(theme.main(), theme.shadow(), theme.highlight(), theme.highlight2());
            Sprite themed = buttonPanelCache.get(key);
            if (themed == null) {
                themed = GdxRenderer.bakePaletteSwap(buttonPanelRaw, UI_MARKERS,
                        new Color[]{ theme.main(), theme.shadow(), theme.highlight(), theme.highlight2() });
                buttonPanelCache.put(key, themed);
            }
            g2.drawNineSlice(themed, x, y, w, h);
            if (selected) {
                g2.setColor(OPT_SEL_BG);
                g2.fillRoundRect(x + 2, y + 2, w - 4, h - 4, 10, 10);
                g2.setColor(OPT_SEL_BORDER);
                g2.setStroke(OPT_STROKE_SEL);
                g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 10, 10);
            }
        } else {
            // Vector fallback — mirrors the rounded-rect buttons in options_top / game-over.
            if (selected) {
                g2.setColor(OPT_SEL_BG);
                g2.fillRoundRect(x, y, w, h, 12, 12);
                g2.setColor(OPT_SEL_BORDER);
                g2.setStroke(OPT_STROKE_SEL);
                g2.drawRoundRect(x, y, w, h, 12, 12);
            } else {
                g2.setColor(cachedColor(25, 22, 18, 120));
                g2.fillRoundRect(x, y, w, h, 12, 12);
                g2.setColor(cachedColor(90, 75, 60, 90));
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(x, y, w, h, 12, 12);
            }
        }
    }

    // ── Package-private accessors used by Menu (keeps UI's caches/animation internal) ─────────
    /** Cached font lookup for the declarative {@link Menu} layer. */
    Font cachedFontFor(int style, float size) { return cachedFont(style, size); }
    /** Cached color lookup for the declarative {@link Menu} layer. */
    Color cachedColorFor(int r, int g, int b, int a) { return cachedColor(r, g, b, a); }
    /** Shared slow UI pulse (0..1) so Menu animations stay in sync with the rest of the UI. */
    float uiPulse() { return fastPulse(animTick, 1); }

    /** The original vector panel look; used as a fallback when UI.png is missing. */
    private void drawSubWindowVector(int x, int y, int width, int height) {
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

        int length = cachedFM().stringWidth(text);
        int x = gp.screenWidth/2 - length/2;
        return x;
    }
    public int getXforAlignToRightText(String text, int tailX) {

        int length = cachedFM().stringWidth(text);
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

package main;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;

import entity.Entity;

public class MouseHandler implements InputProcessor {

    private final GamePanel gp;

    // Game-space cursor position (updated every move/drag)
    public int gameX = 0;
    public int gameY = 0;

    // Left button state — consumed by Player.update() each frame
    public boolean leftPressed  = false;
    public boolean rightPressed = false;

    // One-shot click flags — set on press, cleared after being read
    public boolean leftClicked  = false;
    public boolean rightClicked = false;

    public MouseHandler(GamePanel gp) {
        this.gp = gp;
    }

    // ── libGDX InputProcessor: position, clicks, scroll ──────────────────────
    // Touch/mouse coords arrive in window pixels with the SAME top-left origin the game uses
    // (y-down camera), so panelToGame needs no Y flip.

    private void updatePos(int screenX, int screenY) {
        int[] p = gp.panelToGame(screenX, screenY);
        gameX = p[0];
        gameY = p[1];
    }

    @Override public boolean mouseMoved(int screenX, int screenY) {
        updatePos(screenX, screenY); handleHover(); return false;
    }

    @Override public boolean touchDragged(int screenX, int screenY, int pointer) {
        updatePos(screenX, screenY); handleHover(); if (leftPressed) tryApplyVolumeSlider(); return false;
    }

    @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        updatePos(screenX, screenY);
        if (button == Input.Buttons.LEFT)  { leftPressed = true;  leftClicked = true; }
        if (button == Input.Buttons.RIGHT) { rightPressed = true; rightClicked = true; }
        handleClick();
        return false;
    }

    @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.LEFT)  leftPressed  = false;
        if (button == Input.Buttons.RIGHT) rightPressed = false;
        return false;
    }

    @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        return touchUp(screenX, screenY, pointer, button);
    }

    // scrolled(amountX, amountY): positive amountY = wheel down/away (matches old getWheelRotation>0).
    @Override public boolean scrolled(float amountX, float amountY) {
        int dir = amountY > 0 ? 1 : -1;
        if (gp.windPainter != null && gp.windPainter.isActive()
                && gp.gameState == GamePanel.playState) {
            gp.windPainter.scrollRadius(-dir); // wheel up = bigger brush
            return false;
        }
        if (gp.gameState == GamePanel.journalState) {
            scrollJournal(dir);
        } else if (gp.gameState == GamePanel.skillTreeState) {
            scrollSkillTree(dir);
        } else if (gp.gameState == GamePanel.optionsState && gp.ui.subState == 2) {
            gp.ui.controlScroll += dir;  // clamped by options_control each frame
        }
        return false;
    }

    // Key events are handled by KeyHandler; MouseHandler ignores them.
    @Override public boolean keyDown(int keycode)  { return false; }
    @Override public boolean keyUp(int keycode)    { return false; }
    @Override public boolean keyTyped(char c)      { return false; }

    private void scrollJournal(int dir) {
        if (gp.memoryJournal == null) return;
        int max = Math.max(0, gp.memoryJournal.getAllSorted().size() - 1);
        gp.ui.journalSelectedIndex = Math.max(0, Math.min(max, gp.ui.journalSelectedIndex + dir));
    }

    private void scrollSkillTree(int dir) {
        SkillTree st = gp.player.skillTree;
        if (st == null || st.getNodes().length == 0) return;
        int max = st.getNodes().length - 1;
        st.selectedIndex = Math.max(0, Math.min(max, st.selectedIndex + dir));
    }

    // ── hover: moving the mouse over an option highlights it ─────────────────

    private void handleHover() {
        switch (gp.gameState) {
            case GamePanel.titleState     -> hoverTitle();
            case GamePanel.levelUpState   -> hoverLevelUp();
            case GamePanel.gameOverState  -> hoverGameOver();
            case GamePanel.characterState -> hoverInventory();
            case GamePanel.dialogueState  -> hoverDialogue();
            case GamePanel.optionsState   -> hoverOptions();
            case GamePanel.journalState   -> hoverJournal();
            case GamePanel.skillTreeState -> hoverSkillTree();
        }
    }

    // ── click: clicking an option that is already hovered confirms it ─────────

    private void handleClick() {
        // Consume leftClicked for every non-play state so returning to play
        // doesn't leave a stale flag that triggers an attack.
        if (gp.gameState != GamePanel.playState) leftClicked = false;
        switch (gp.gameState) {
            case GamePanel.titleState     -> clickTitle();
            case GamePanel.pauseState     -> clickPause();
            case GamePanel.characterState -> clickInventory();
            case GamePanel.levelUpState   -> clickLevelUp();
            case GamePanel.dialogueState  -> clickDialogue();
            case GamePanel.gameOverState  -> clickGameOver();
            case GamePanel.optionsState   -> clickOptions();
            case GamePanel.journalState   -> {} // hover selects; no confirm action
            case GamePanel.skillTreeState -> clickSkillTree();
            case GamePanel.playState      -> clickWorld();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TITLE SCREEN
    // Items: [CONTINUE,] NEW GAME, MULTIPLAYER, QUIT — CONTINUE only if a save exists
    // menuStartY = screenHeight*0.77, each item 40px apart
    // ════════════════════════════════════════════════════════════════════════

    private int titleItemUnderMouse() {
        if (gp.ui.titleScreenState != 0) return -1;
        int menuStartY = (int)(gp.screenHeight * 0.77f);
        int itemH = 40;
        int menuCount = gp.ui.titleMenuItems().length;
        for (int i = 0; i < menuCount; i++) {
            int top = menuStartY + i * itemH - 24;
            if (gameY >= top && gameY < top + itemH) return i;
        }
        return -1;
    }

    // Class selection screen (titleScreenState == 1)
    // Panel: 500×420 centred; 3 class rows at optY + i*(60+14), h=60; Back below
    private int classItemUnderMouse() {
        if (gp.ui.titleScreenState != 1) return -1;
        int panelW = 500, panelH = 420;
        int px  = (gp.screenWidth  - panelW) / 2;
        int py  = (gp.screenHeight - panelH) / 2;
        int optX = px + 30, optW = panelW - 60, optH = 60;
        int optY = py + 85;
        for (int i = 0; i < 3; i++) {
            int oy = optY + i * (optH + 14);
            if (gameX >= optX && gameX < optX + optW && gameY >= oy && gameY < oy + optH) return i;
        }
        // Back button
        int backY = optY + 3 * (optH + 14) + 10;
        if (gameY >= backY - 20 && gameY < backY + 10) return 3;
        return -1;
    }

    private void hoverTitle() {
        if (gp.ui.titleScreenState == 1) {
            int i = classItemUnderMouse();
            if (i >= 0) gp.ui.commandNum = i;
            return;
        }
        int i = titleItemUnderMouse();
        if (i >= 0) gp.ui.commandNum = i;
    }

    private void clickTitle() {
        if (gp.ui.titleScreenState == 1) {
            int i = classItemUnderMouse();
            if (i < 0) return;
            gp.ui.commandNum = i;
            gp.playSE(audio.SFX.MENU_SELECT);
            switch (i) {
                case 0 -> { gp.keyH.startNewGame(); gp.player.setPlayerStats(4, 2, 1, 4, 3); }
                case 1 -> { gp.keyH.startNewGame(); gp.player.setPlayerStats(2, 3, 3, 5, 2); }
                case 2 -> { gp.keyH.startNewGame(); gp.player.setPlayerStats(3, 1, 2, 5, 5); }
                case 3 -> { gp.ui.titleScreenState = 0; gp.ui.commandNum = 0; }
            }
            return;
        }
        int i = titleItemUnderMouse();
        if (i < 0) return;
        gp.ui.commandNum = i;
        executeTitleAction(i);
    }

    private void executeTitleAction(int i) {
        gp.playSE(audio.SFX.MENU_SELECT);
        boolean hasSave = gp.ui.titleHasSave();
        // With a save: 0=CONTINUE 1=NEW GAME 2=MULTIPLAYER 3=QUIT
        // Without:     0=NEW GAME  1=MULTIPLAYER            2=QUIT
        if (hasSave && i == 0) { gp.saveLoad.load(); startGame(); return; }
        int newGameIdx = hasSave ? 1 : 0;
        int multiplayerIdx = hasSave ? 2 : 1;
        int quitIdx = hasSave ? 3 : 2;
        if (i == newGameIdx) { gp.ui.titleScreenState = 1; }
        else if (i == multiplayerIdx) {
            gp.ui.titleScreenState = 3; gp.ui.mpServerSelection = 0; gp.ui.commandNum = 0;
        }
        // Gdx.app.exit() over System.exit(0): lets libGDX run its own shutdown/dispose()
        // sequence and maps to Activity.finish() on Android instead of killing the process.
        else if (i == quitIdx) { Gdx.app.exit(); }
    }

    private void startGame() {
        gp.gameState = GamePanel.playState;
        String path = gp.mapManager.mapRegistry.getOrDefault(
            gp.mapManager.currentMapId, "/res/maps/Canvas_Village.tmx");
        gp.mapObjectLoader.loadMapProperties(path);
    }

    // ════════════════════════════════════════════════════════════════════════
    // PAUSE SCREEN — click anywhere to resume
    // ════════════════════════════════════════════════════════════════════════

    private void clickPause() {
        gp.gameState = GamePanel.playState;
    }

    // ════════════════════════════════════════════════════════════════════════
    // INVENTORY (characterState)
    // frameX = screenWidth - min(384,screenWidth*0.30) - 16
    // slotXstart = frameX+30, slotYstart = tileSize+30, slotSize = tileSize+3
    // 5 cols × 4 rows
    // ════════════════════════════════════════════════════════════════════════

    private int[] inventorySlotUnderMouse() {
        int frameWidth = Math.min(384, (int)(gp.screenWidth * 0.30f));
        int frameX     = gp.screenWidth - frameWidth - 16;
        int slotXstart = frameX + 30;
        int slotYstart = gp.tileSize + 30;
        int slotSize   = gp.tileSize + 3;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 5; col++) {
                int sx = slotXstart + col * slotSize;
                int sy = slotYstart + row * slotSize;
                if (gameX >= sx && gameX < sx + gp.tileSize &&
                    gameY >= sy && gameY < sy + gp.tileSize) {
                    return new int[]{col, row};
                }
            }
        }
        return null;
    }

    private void hoverInventory() {
        int[] slot = inventorySlotUnderMouse();
        if (slot != null) { gp.ui.slotCol = slot[0]; gp.ui.slotRow = slot[1]; }
    }

    private void clickInventory() {
        int[] slot = inventorySlotUnderMouse();
        if (slot == null) return;
        gp.ui.slotCol = slot[0];
        gp.ui.slotRow = slot[1];
        gp.player.selectItem();
        leftClicked = false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // LEVEL-UP CHOICES
    // panel: w=420 h=340 centred; options at y+118 + i*58, h=46, x+25, w-50
    // ════════════════════════════════════════════════════════════════════════

    private int levelUpOptionUnderMouse() {
        if (gp.player.levelUpOptions == null) return -1;
        int w = 420, h = 340;
        int px = (gp.screenWidth  - w) / 2;
        int py = (gp.screenHeight - h) / 2;
        int optX = px + 25, optW = w - 50, optH = 46;
        for (int i = 0; i < 3; i++) {
            int oy = py + 118 + i * 58;
            if (gameX >= optX && gameX < optX + optW && gameY >= oy && gameY < oy + optH) return i;
        }
        return -1;
    }

    private void hoverLevelUp() {
        int i = levelUpOptionUnderMouse();
        if (i >= 0) gp.player.levelUpChoice = i;
    }

    private void clickLevelUp() {
        int i = levelUpOptionUnderMouse();
        if (i < 0) return;
        gp.player.levelUpChoice = i;
        gp.player.applyLevelUpChoice();
        gp.gameState = GamePanel.playState;
    }

    // ════════════════════════════════════════════════════════════════════════
    // DIALOGUE CHOICES
    // boxX=tileSize*2, boxY=tileSize/2 + tileSize*5 + 8, optionH=36
    // ════════════════════════════════════════════════════════════════════════

    private int dialogueChoiceUnderMouse() {
        if (gp.ui.npc == null || gp.ui.npc.dialogueChoices == null) return -1;
        int boxX    = gp.tileSize * 2;
        int boxW    = gp.screenWidth - (gp.tileSize * 4);
        int boxY    = gp.tileSize / 2 + gp.tileSize * 5 + 8;
        int optionH = 36;
        for (int i = 0; i < gp.ui.npc.dialogueChoices.length; i++) {
            int iy = boxY + i * optionH;
            if (gameX >= boxX && gameX < boxX + boxW && gameY >= iy && gameY < iy + optionH) return i;
        }
        return -1;
    }

    private void hoverDialogue() {
        int i = dialogueChoiceUnderMouse();
        if (i >= 0) gp.ui.npc.selectedChoice = i;
    }

    private void clickDialogue() {
        int i = dialogueChoiceUnderMouse();
        if (i < 0) {
            // Click outside choices — advance dialogue like Enter
            gp.keyH.enterPressed = true;
            return;
        }
        gp.ui.npc.selectedChoice = i;
        gp.keyH.enterPressed = true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // GAME OVER
    // Two buttons (Retry / Quit) centred; buttonH=tileSize*0.9, gap=20
    // line2Y = (screenHeight/2 - tileSize*2) + 70  (matches drawGameOverScreen)
    // ════════════════════════════════════════════════════════════════════════

    private int gameOverButtonUnderMouse() {
        int y0      = gp.screenHeight / 2 - gp.tileSize * 2;  // "YOU DIED" baseline
        int line2Y  = y0 + 70;
        int buttonW = gp.tileSize * 5;
        int buttonH = (int)(gp.tileSize * 0.9f);
        int buttonX = gp.screenWidth / 2 - buttonW / 2;
        int startY  = line2Y + gp.tileSize;
        for (int i = 0; i < 2; i++) {
            int btnY  = startY + i * (buttonH + 20);
            int rectY = btnY - buttonH + 16;
            if (gameX >= buttonX && gameX < buttonX + buttonW &&
                gameY >= rectY   && gameY < rectY + buttonH) return i;
        }
        return -1;
    }

    private void hoverGameOver() {
        int i = gameOverButtonUnderMouse();
        if (i >= 0) gp.ui.commandNum = i;
    }

    private void clickGameOver() {
        int i = gameOverButtonUnderMouse();
        if (i < 0) return;
        gp.ui.commandNum = i;
        gp.playSE(audio.SFX.MENU_SELECT);
        if (i == 0) {
            // Retry — resetGame(false) already repositions the player correctly (at
            // retrySpawnCol/Row, the tile they last entered the map at, falling back to the
            // map's default spawn only if that isn't set). An extra setDefaultPositions() call
            // here used to unconditionally overwrite that with the map's default/new-game
            // spawn point afterward, so retry always dropped the player at the same fixed spot
            // instead of where the TMX/door-entry logic actually placed them.
            gp.resetGame(false);
            gp.gameState = GamePanel.playState;
            gp.playMusic(audio.SFX.MAIN_THEME);
        } else {
            // Quit to title
            gp.ui.titleScreenState = 0;
            gp.ui.commandNum = 0;
            gp.stopMusic();
            gp.resetGame(true);
            gp.gameState = GamePanel.titleState;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPTIONS SCREEN
    // Frame: min(520, screenWidth*0.42) × min(660, screenHeight*0.92), centred
    // options_top: items 0-9, startY = frameY+48+42, lineH=46
    // ════════════════════════════════════════════════════════════════════════

    private int optionsFrameY() {
        return (gp.screenHeight - Math.min(660, (int)(gp.screenHeight * 0.92f))) / 2;
    }

    private int optionsItemUnderMouse() {
        if (gp.ui.subState != 0) return -1;
        int frameY  = optionsFrameY();
        int startY  = frameY + 48 + 42;  // titleY + 42 (title baseline = frameY+48)
        int lineH   = 46;
        for (int i = 0; i < 10; i++) {
            int itemY = startY + i * lineH;
            int top   = itemY - lineH + 16;  // matches selection bar barY
            if (gameY >= top && gameY < top + lineH - 4) return i;
        }
        return -1;
    }

    // Slider geometry (matches drawMedievalSlider called from options_top):
    //   rightCol = frameX + fw - pad - 155  (pad=20)
    //   ctrlY    = itemY - 17
    //   barW=150, barH=18, max=5
    // Returns 0..5 if click is inside the slider for row rowIndex, else -1.
    private int sliderValueUnderMouse(int rowIndex) {
        if (gp.ui.subState != 0) return -1;
        int fw      = Math.min(520, (int)(gp.screenWidth * 0.42f));
        int frameX  = (gp.screenWidth - fw) / 2;
        int frameY  = optionsFrameY();
        int pad     = 20;
        int rightCol = frameX + fw - pad - 155;
        int lineH   = 46;
        int startY  = frameY + 48 + 42;
        int itemY   = startY + rowIndex * lineH;
        int ctrlY   = itemY - 17;
        int barW    = 150, barH = 18;
        int cy      = ctrlY + 2;
        if (gameX < rightCol || gameX >= rightCol + barW) return -1;
        if (gameY < cy       || gameY >= cy + barH)       return -1;
        int val = Math.round((gameX - rightCol) / (float) barW * 5);
        return Math.max(0, Math.min(5, val));
    }

    // Apply a slider click/drag for music (row 4) or SFX (row 5).
    private void tryApplyVolumeSlider() {
        if (gp.ui.subState != 0) return;
        int music = sliderValueUnderMouse(4);
        if (music >= 0) {
            gp.ui.commandNum = 4;
            gp.audio.setMusicVolume(music);
            return;
        }
        int se = sliderValueUnderMouse(5);
        if (se >= 0) {
            gp.ui.commandNum = 5;
            gp.audio.setSEVolume(se);
        }
    }

    // End-game confirmation (subState 3): "Save & Quit" / "Quit" / "No"
    // btnY = frameY + tileSize*5 + 20, each button 48px apart
    private int endGameButtonUnderMouse() {
        int frameY = optionsFrameY();
        int btnY   = frameY + gp.tileSize * 5 + 20;
        for (int i = 0; i < 3; i++) {
            int by  = btnY + i * 48;
            int top = by - 28;
            if (gameY >= top && gameY < top + 36) return i;
        }
        return -1;
    }

    // Controls Back button (subState 2)
    // backY = frameY + fh - backAreaH + 46, where backAreaH=68
    private boolean controlsBackUnderMouse() {
        int frameY = optionsFrameY();
        int fh     = Math.min(660, (int)(gp.screenHeight * 0.92f));
        int backY  = frameY + fh - 68 + 46;
        return gameY >= backY - 28 && gameY < backY + 8;
    }

    private void hoverOptions() {
        switch (gp.ui.subState) {
            case 0 -> { int i = optionsItemUnderMouse(); if (i >= 0) gp.ui.commandNum = i; }
            case 3 -> { int i = endGameButtonUnderMouse(); if (i >= 0) gp.ui.commandNum = i; }
            case 2 -> { if (controlsBackUnderMouse()) gp.ui.commandNum = 0; }
        }
    }

    private void clickOptions() {
        switch (gp.ui.subState) {
            case 1 -> gp.keyH.enterPressed = true;  // fullscreen notice: click = dismiss
            case 3 -> {
                int i = endGameButtonUnderMouse();
                if (i >= 0) { gp.ui.commandNum = i; gp.keyH.enterPressed = true; }
            }
            case 2 -> {
                if (controlsBackUnderMouse()) { gp.ui.commandNum = 0; gp.keyH.enterPressed = true; }
            }
            default -> {
                // Try slider click first; if not on a slider, treat as item select
                int prevMusic = gp.audio.getMusicVolume();
                int prevSE    = gp.audio.getSEVolume();
                tryApplyVolumeSlider();
                if (gp.audio.getMusicVolume() != prevMusic || gp.audio.getSEVolume() != prevSE) return;
                int i = optionsItemUnderMouse();
                if (i >= 0) { gp.ui.commandNum = i; gp.keyH.enterPressed = true; }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // JOURNAL SCREEN
    // Left panel: x=tileSize, y=100, w=screenWidth/3, h=screenHeight-140
    // List: listX=panelX+20, listY=panelY+30, lineH=28
    // ════════════════════════════════════════════════════════════════════════

    private void hoverJournal() {
        if (gp.memoryJournal == null) return;
        int panelX  = gp.tileSize;
        int panelY  = 100;
        int panelW  = gp.screenWidth / 3;
        int panelH  = gp.screenHeight - 140;
        int listY   = panelY + 30;
        int lineH   = 28;
        int maxVis  = (panelH - 40) / lineH;
        // only react if inside the left panel
        if (gameX < panelX || gameX >= panelX + panelW) return;
        if (gameY < listY  || gameY >= listY + maxVis * lineH) return;
        int row = (gameY - listY) / lineH;
        int idx = gp.ui.journalScroll + row;
        int max = Math.max(0, gp.memoryJournal.getAllSorted().size() - 1);
        gp.ui.journalSelectedIndex = Math.max(0, Math.min(max, idx));
    }

    // ════════════════════════════════════════════════════════════════════════
    // SKILL TREE SCREEN
    // Panel: 700×640 centred. HEADER_H=56, graph starts at PY+HEADER_H+50.
    // COL_STEP=(700-20)/4=170, TIER_STEP=110. Node radius=26.
    // selectedIndex drives scroll automatically via UI draw loop.
    // ════════════════════════════════════════════════════════════════════════

    private static final int ST_PW        = 700;
    private static final int ST_PH        = 640;
    private static final int ST_HEADER_H  = 56;
    private static final int ST_NODE_R    = 26;
    private static final int ST_TIER_STEP = 110;
    private static final int ST_COL_STEP  = (ST_PW - 20) / 4;  // 170

    private int skillTreeNodeUnderMouse() {
        SkillTree st = gp.player.skillTree;
        if (st == null) return -1;
        SkillTree.SkillNode[] nodes = st.getNodes();
        if (nodes.length == 0) return -1;

        int PX      = (gp.screenWidth  - ST_PW) / 2;
        int PY      = (gp.screenHeight - ST_PH) / 2;
        int GRAPH_X = PX + 10;
        int GRAPH_Y = PY + ST_HEADER_H + 50;
        int scrollPx = st.scrollOffset / 10;

        for (int i = 0; i < nodes.length; i++) {
            SkillTree.SkillNode node = nodes[i];
            int cx = GRAPH_X + node.row * ST_COL_STEP + ST_COL_STEP / 2;
            int cy = GRAPH_Y + node.col * ST_TIER_STEP + ST_TIER_STEP / 2 - scrollPx;
            int dx = gameX - cx;
            int dy = gameY - cy;
            if (dx * dx + dy * dy <= ST_NODE_R * ST_NODE_R) return i;
        }
        return -1;
    }

    private void hoverSkillTree() {
        int i = skillTreeNodeUnderMouse();
        if (i >= 0) gp.player.skillTree.selectedIndex = i;
    }

    private void clickSkillTree() {
        int i = skillTreeNodeUnderMouse();
        if (i < 0) return;
        gp.player.skillTree.selectedIndex = i;
        gp.keyH.enterPressed = true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // WORLD — NPC click-to-interact
    // Click anywhere in playState while an NPC is within its interactRange.
    // No sprite hit-test needed — range proximity is the only requirement.
    // ════════════════════════════════════════════════════════════════════════

    private void clickWorld() {
        // While the wind painter is active, the mouse paints — ignore world interactions.
        if (gp.windPainter != null && gp.windPainter.isActive()) { leftClicked = false; return; }
        // Don't steal click if it's meant for combat (handled by Player.update via leftClicked)
        // We only handle NPC interaction here; combat consumes leftClicked separately.
        int playerCX = gp.player.getCenterX();
        int playerCY = gp.player.getCenterY();
        for (int i = 0; i < gp.npc.length; i++) {
            Entity n = gp.npc[i];
            if (n == null || n.interactRange <= 0) continue;
            int dx     = n.getCenterX() - playerCX;
            int dy     = n.getCenterY() - playerCY;
            int distSq = dx * dx + dy * dy;
            if (distSq > (long)n.interactRange * n.interactRange) continue;
            // Face the player toward the NPC before speaking
            if (Math.abs(dx) >= Math.abs(dy))
                gp.player.direction = dx >= 0 ? Entity.DIR_RIGHT : Entity.DIR_LEFT;
            else
                gp.player.direction = dy >= 0 ? Entity.DIR_DOWN : Entity.DIR_UP;
            gp.npc[i].speak();
            leftClicked = false;  // consume so Player.update doesn't also fire an attack
            return;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMBAT — left-click attacks in the direction of the cursor
    // ════════════════════════════════════════════════════════════════════════

    /** Continuous aim angle (radians, atan2 convention: 0=right, +PI/2=down) from the player
     *  center to the cursor. Used for free-aim attack cone/slice orientation. */
    public double getAttackAngleFromMouse() {
        int pcx = gp.player.screenX + gp.tileSize / 2;
        int pcy = gp.player.screenY + gp.tileSize / 2;
        double dx = gameX - pcx;
        double dy = gameY - pcy;
        if (dx == 0 && dy == 0) return angleForDirection(gp.player.direction);
        return Math.atan2(dy, dx);
    }

    /** Cardinal direction → atan2-convention angle, for keyboard-only attacks (no mouse aim). */
    public static double angleForDirection(int direction) {
        return switch (direction) {
            case Entity.DIR_RIGHT -> 0.0;
            case Entity.DIR_DOWN  -> Math.PI / 2.0;
            case Entity.DIR_LEFT  -> Math.PI;
            case Entity.DIR_UP    -> -Math.PI / 2.0;
            default -> Math.PI / 2.0;
        };
    }
}

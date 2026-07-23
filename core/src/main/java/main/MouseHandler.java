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

    // Left button state, consumed by Player.update() each frame
    public boolean leftPressed  = false;
    public boolean rightPressed = false;

    // One-shot click flags, set on press, cleared after being read
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
        if (button == Input.Buttons.LEFT)  { leftPressed = true;  leftClicked = true; gp.actions.setPhysical("mouse:left_click", true); }
        if (button == Input.Buttons.RIGHT) { rightPressed = true; rightClicked = true; gp.actions.setPhysical("mouse:right_click", true); }
        handleClick();
        return false;
    }

    @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.LEFT)  { leftPressed  = false; gp.actions.setPhysical("mouse:left_click", false); }
        if (button == Input.Buttons.RIGHT) { rightPressed = false; gp.actions.setPhysical("mouse:right_click", false); }
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
            case GamePanel.shopState      -> hoverShop();
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
            case GamePanel.shopState      -> clickShop();
            case GamePanel.playState      -> clickWorld();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TITLE SCREEN
    // Items: [CONTINUE,] NEW GAME, MULTIPLAYER, QUIT, CONTINUE only if a save exists
    // menuStartY = screenHeight*0.77, each item 40px apart
    // ════════════════════════════════════════════════════════════════════════

    private int titleItemUnderMouse() {
        if (gp.ui.titleScreenState != 0) return -1;
        // The title draw loop records each row's rect into titleMenu(); just query it.
        return gp.ui.titleMenu().itemAt(gameX, gameY);
    }

    // Class selection screen (titleScreenState == 1). The draw code records its button rects into
    // classMenu() each frame, so hit-testing is just a query, no duplicated, drift-prone geometry.
    private int classItemUnderMouse() {
        if (gp.ui.titleScreenState != 1) return -1;
        return gp.ui.classMenu().itemAt(gameX, gameY);
    }

    private void hoverTitle() {
        if (gp.ui.titleScreenState == 1) {
            int i = classItemUnderMouse();
            if (i >= 0) gp.ui.commandNum = i;
            return;
        }
        int i = titleItemUnderMouse();
        if (i >= 0) {
            if (i != gp.ui.commandNum) gp.ui.continueStatusMessage = ""; // dismiss a stale error line
            gp.ui.commandNum = i;
        }
    }

    private void clickTitle() {
        // Sub-screens that were keyboard-only (and therefore completely dead on Android touch):
        // server browser, add/direct-connect form, friends list.
        if (gp.ui.titleScreenState == 3) { clickMultiplayerBrowser(); return; }
        if (gp.ui.titleScreenState == 4) { clickMultiplayerInput();   return; }
        if (gp.ui.titleScreenState == 5) { clickFriendsList();        return; }
        if (gp.ui.titleScreenState == 0 && gp.ui.usernameBoxContains(gameX, gameY)) {
            focusUsernameField();
            return;
        }
        // Clicking anywhere else while the username field is focused should drop focus,
        // otherwise usernameFieldFocused can stay stuck true and silently block all
        // direction-key menu navigation for the rest of the session (see isDirKey guard
        // in KeyHandler.keyDown).
        if (gp.ui.usernameFieldFocused) {
            gp.ui.usernameFieldFocused = false;
            gp.player.name = gp.ui.playerUsername;
        }
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

    /** Focus the username box for typing; on Android this also pops the native soft-keyboard dialog. */
    private void focusUsernameField() {
        gp.ui.usernameFieldFocused = true;
        gp.playSE(audio.SFX.MENU_SELECT);
        if (Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
            Gdx.input.getTextInput(new com.badlogic.gdx.Input.TextInputListener() {
                @Override public void input(String text) {
                    String trimmed = text.length() > 20 ? text.substring(0, 20) : text;
                    gp.ui.playerUsername = trimmed;
                    gp.player.name = trimmed;
                    gp.ui.usernameFieldFocused = false;
                }
                @Override public void canceled() {
                    gp.ui.usernameFieldFocused = false;
                }
            }, "Enter Username", gp.ui.playerUsername, "");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MULTIPLAYER BROWSER (titleScreenState 3) — tap a server row to select it,
    // tap it again to connect; tap ADD SERVER / DIRECT CONNECT / BACK directly.
    // Geometry mirrors UI.drawMultiplayerBrowser() exactly.
    // ════════════════════════════════════════════════════════════════════════

    private void clickMultiplayerBrowser() {
        int panelW = 560, panelH = 480;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        java.util.ArrayList<String[]> servers = gp.serverList.getServers();
        int listStartY = py + 78;
        int entryH = 48, entryW = panelW - 60, entryX = px + 30;
        int maxVisible = 5;
        int scrollOffset = Math.max(0, gp.ui.mpServerSelection - maxVisible + 1);
        if (gp.ui.mpServerSelection >= servers.size()) scrollOffset = 0;

        for (int i = scrollOffset; i < Math.min(servers.size(), scrollOffset + maxVisible); i++) {
            int ey = listStartY + (i - scrollOffset) * (entryH + 6);
            if (gameX >= entryX && gameX <= entryX + entryW && gameY >= ey && gameY <= ey + entryH) {
                if (gp.ui.mpServerSelection == i) {
                    // Second tap on the selected row connects (mirrors keyboard Enter).
                    gp.keyH.activateMultiplayerBrowserItem(i);
                } else {
                    gp.ui.mpServerSelection = i;
                    gp.playSE(audio.SFX.MENU_SELECT);
                }
                return;
            }
        }

        int menuStartY = listStartY + Math.min(servers.size(), maxVisible) * (entryH + 6) + 20;
        if (servers.isEmpty()) menuStartY = listStartY + 60;
        int optH = 38, optW = panelW - 100, optX = px + 50;
        for (int i = 0; i < 3; i++) {
            int oy = menuStartY + i * (optH + 8);
            if (gameX >= optX && gameX <= optX + optW && gameY >= oy && gameY <= oy + optH) {
                gp.ui.mpServerSelection = servers.size() + i;
                gp.keyH.activateMultiplayerBrowserItem(gp.ui.mpServerSelection);
                return;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MULTIPLAYER ADD/DIRECT-CONNECT FORM (titleScreenState 4) — tap a field to
    // focus it (Android: pops the soft-keyboard dialog), tap a button to fire it.
    // Geometry mirrors UI.drawMultiplayerInput() exactly.
    // ════════════════════════════════════════════════════════════════════════

    private void clickMultiplayerInput() {
        int panelW = 480, panelH = 350;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        int fieldCount = gp.ui.mpAddMode ? 3 : 2;
        int fieldX = px + 40, fieldW = panelW - 80, fieldH = 36;
        int fieldStartY = py + 72;

        for (int i = 0; i < fieldCount; i++) {
            int fy = fieldStartY + i * (fieldH + 28);
            if (gameX >= fieldX && gameX <= fieldX + fieldW && gameY >= fy && gameY <= fy + fieldH) {
                gp.ui.mpInputField = i;
                gp.playSE(audio.SFX.MENU_SELECT);
                if (Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
                    openMpFieldDialog(i);
                }
                return;
            }
        }

        int btnY = fieldStartY + fieldCount * (fieldH + 28) + 10;
        int btnCount = gp.ui.mpAddMode ? 3 : 2;
        int btnH = 36;
        int btnW = (panelW - 80 - (btnCount - 1) * 12) / btnCount;
        for (int i = 0; i < btnCount; i++) {
            int bx = fieldX + i * (btnW + 12);
            if (gameX >= bx && gameX <= bx + btnW && gameY >= btnY && gameY <= btnY + btnH) {
                gp.ui.mpInputField = fieldCount + i;
                gp.keyH.handleMultiplayerInputButton(i);
                return;
            }
        }
    }

    /** Native text dialog for one field of the multiplayer form (Android soft keyboard). */
    private void openMpFieldDialog(int field) {
        final boolean add = gp.ui.mpAddMode;
        String title;
        String current;
        if (add) {
            title   = switch (field) { case 0 -> "Server name"; case 1 -> "Server IP address"; default -> "Server port"; };
            current = switch (field) { case 0 -> gp.ui.mpServerName; case 1 -> gp.ui.mpServerIP; default -> gp.ui.mpServerPort; };
        } else {
            title   = field == 0 ? "Server IP address" : "Server port";
            current = field == 0 ? gp.ui.mpServerIP : gp.ui.mpServerPort;
        }
        final boolean isPort = (add && field == 2) || (!add && field == 1);
        Gdx.input.getTextInput(new com.badlogic.gdx.Input.TextInputListener() {
            @Override public void input(String text) {
                String v = text.trim();
                if (isPort) {
                    v = v.replaceAll("[^0-9]", "");
                    if (v.length() > 5) v = v.substring(0, 5);
                } else {
                    // Same charset/length rule as the keyboard path (typeCharToField).
                    v = v.replaceAll("[^\\x20-\\x7e]", "");
                    if (v.length() > 40) v = v.substring(0, 40);
                }
                if (add) {
                    switch (field) {
                        case 0 -> gp.ui.mpServerName = v;
                        case 1 -> gp.ui.mpServerIP = v;
                        default -> gp.ui.mpServerPort = v;
                    }
                } else {
                    if (field == 0) gp.ui.mpServerIP = v; else gp.ui.mpServerPort = v;
                }
            }
            @Override public void canceled() { /* keep previous value */ }
        }, title, current, isPort ? "7777" : "");
    }

    // ════════════════════════════════════════════════════════════════════════
    // FRIENDS LIST (titleScreenState 5) — tap a request row twice to accept it,
    // tap ADD FRIEND / CLAIM USERNAME / BACK directly (Android: text dialog).
    // Geometry mirrors UI.drawFriendsList() exactly.
    // ════════════════════════════════════════════════════════════════════════

    private void clickFriendsList() {
        // While a text-entry or NFC wait is active, taps shouldn't re-trigger menu rows.
        if (gp.ui.friendsAddMode || gp.ui.friendsClaimMode || gp.ui.friendsNfcWaiting) return;

        int panelW = 560, panelH = 520;
        int px = (gp.screenWidth - panelW) / 2;
        int py = (gp.screenHeight - panelH) / 2;

        int friendCount = gp.friendsListManager.getFriends().size();
        int syncingCount = gp.friendsListManager.getPendingLocalAdds().size();
        int syncingEnd = friendCount + syncingCount;
        int requestCount = gp.friendsListManager.getPendingRequests().size();
        int rowCount = syncingEnd + requestCount;
        boolean hasUsername = gp.friendsListManager.getClaimedUsername() != null;

        int listStartY = py + 94;
        int entryH = 44, entryW = panelW - 60, entryX = px + 30;
        int maxVisible = 5;
        int scrollOffset = Math.max(0, Math.min(gp.ui.friendsSelection, rowCount - 1) - maxVisible + 1);
        if (gp.ui.friendsSelection >= rowCount) scrollOffset = Math.max(0, rowCount - maxVisible);

        for (int i = scrollOffset; i < Math.min(rowCount, scrollOffset + maxVisible); i++) {
            int ey = listStartY + (i - scrollOffset) * (entryH + 6);
            if (gameX >= entryX && gameX <= entryX + entryW && gameY >= ey && gameY <= ey + entryH) {
                if (gp.ui.friendsSelection == i && i >= syncingEnd && i < rowCount) {
                    // Second tap on a selected pending request accepts it (Enter equivalent).
                    String username = gp.friendsListManager.getPendingRequests().get(i - syncingEnd);
                    new Thread(() -> gp.friendsListManager.respondFriendRequest(username, true),
                            "Friends-Accept").start();
                }
                gp.ui.friendsSelection = i;
                gp.playSE(audio.SFX.MENU_SELECT);
                return;
            }
        }

        int menuStartY = listStartY + Math.min(rowCount, maxVisible) * (entryH + 6) + 20;
        if (rowCount == 0) menuStartY = listStartY + 60;
        int menuCount = hasUsername ? 2 : 3;
        int optH = 36, optW = panelW - 100, optX = px + 50;
        for (int i = 0; i < menuCount; i++) {
            int oy = menuStartY + i * (optH + 8);
            if (gameX >= optX && gameX <= optX + optW && gameY >= oy && gameY <= oy + optH) {
                gp.ui.friendsSelection = rowCount + i;
                gp.playSE(audio.SFX.MENU_SELECT);
                if (i == 0) {
                    gp.keyH.startAddFriend();
                    maybeOpenFriendsDialog(false);
                } else if (i == 1 && !hasUsername) {
                    gp.ui.friendsClaimMode = true;
                    String claimed = gp.friendsListManager.getClaimedUsername();
                    gp.ui.friendsNewName = claimed != null ? claimed : "";
                    maybeOpenFriendsDialog(true);
                } else {
                    platform.NfcFriend.stopReading();
                    gp.ui.titleScreenState = 0;
                    gp.ui.commandNum = 0;
                }
                return;
            }
        }
    }

    /** On Android, replace the type-in-place username entry with the native soft-keyboard dialog. */
    private void maybeOpenFriendsDialog(boolean claiming) {
        if (Gdx.app.getType() != com.badlogic.gdx.Application.ApplicationType.Android) return;
        if (claiming ? !gp.ui.friendsClaimMode : !gp.ui.friendsAddMode) return;
        Gdx.input.getTextInput(new com.badlogic.gdx.Input.TextInputListener() {
            @Override public void input(String text) {
                String typed = text.trim();
                gp.ui.friendsNewName = "";
                gp.ui.friendsAddMode = false;
                gp.ui.friendsClaimMode = false;
                if (!typed.isBlank()) {
                    new Thread(() -> gp.keyH.runFriendAction(claiming, typed), "Friends-Action").start();
                }
            }
            @Override public void canceled() {
                gp.ui.friendsNewName = "";
                gp.ui.friendsAddMode = false;
                gp.ui.friendsClaimMode = false;
            }
        }, claiming ? "Claim a username" : "Add friend by username", gp.ui.friendsNewName, "");
    }

    /**
     * Fires whatever action the clicked item itself declares (see UI.titleMenu()), instead of
 * re-deriving the item's meaning from its index, the menu's item count/order varies (CONTINUE
     * only if a save exists, JOIN GAME only if NFC is supported), so a fixed index-to-action
     * mapping here would silently fire the wrong action whenever the layout doesn't match what was
     * hardcoded. Mirrors KeyHandler's ENTER handling for the same menu.
     */
    private void executeTitleAction(int i) {
        ui.Menu menu = gp.ui.titleMenu();
        menu.setSelected(i);
        ui.MenuItem selItem = menu.selectedItem();
        String label = selItem != null ? selItem.label : "";
        if (!gp.ui.blockIfNoUsername(label)) menu.activate();
    }

    private void startGame() {
        gp.gameState = GamePanel.playState;
        String path = gp.mapManager.mapRegistry.getOrDefault(
            gp.mapManager.currentMapId, "/res/maps/Canvas_Village.tmx");
        gp.mapObjectLoader.loadMapProperties(path);
    }

    // ════════════════════════════════════════════════════════════════════════
    // PAUSE SCREEN, click a button to activate it; click outside every button
    // (the overlay background) resumes, preserving the old "click anywhere to
    // dismiss" feel for touch/mouse without a near-miss on QUIT TO TITLE (etc.)
    // silently resuming instead.
    // ════════════════════════════════════════════════════════════════════════

    private void clickPause() {
        ui.Menu menu = gp.ui.pauseMenu();
        int i = menu.itemAt(gameX, gameY);
        if (i >= 0) {
            gp.ui.pauseSelection = i;
            menu.setSelected(i);
            menu.activate();
        } else {
            gp.gameState = GamePanel.playState;
        }
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
        if (slot != null) {
            gp.ui.slotCol = slot[0]; gp.ui.slotRow = slot[1];
            gp.ui.selectionVisible = true;
        } else {
            gp.ui.selectionVisible = false;
        }
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
            // Click outside choices, advance dialogue like Enter
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
        // The game-over draw loop records its button rects into gameOverMenu(); just query them.
        return gp.ui.gameOverMenu().itemAt(gameX, gameY);
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
            // Retry, resetGame(false) already repositions the player correctly (at
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
        // Ask the Menu what its last drawn frame put under the cursor, always matches the visuals.
        return gp.ui.optionsMenu().itemAt(gameX, gameY);
    }

    // Returns 0..max if the cursor is inside the slider bar for menu row rowIndex, else -1.
    // Delegates to the Menu, which knows exactly where it last drew the bar (no duplicated geometry).
    private int sliderValueUnderMouse(int rowIndex) {
        if (gp.ui.subState != 0) return -1;
        return gp.ui.optionsMenu().sliderValueAt(rowIndex, gameX, gameY);
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

    private void hoverShop() {
        if (gp.ui.isShopPromptActive()) return; // single button, click is enough
        int i = gp.ui.shopGridItemUnderMouse(gameX, gameY);
        if (i >= 0) {
            gp.ui.setShopSelectedRow(i);
            gp.ui.selectionVisible = true;
        } else {
            gp.ui.selectionVisible = false;
        }
    }

    private void clickShop() {
        if (gp.ui.isShopPromptActive()) {
            // Click the button to enter; clicking anywhere else means "not interested", leave,
            // same as any non-confirm keypress (see KeyHandler.handleShopState).
            if (gp.ui.shopPromptButtonUnderMouse(gameX, gameY)) {
                gp.ui.confirmShopPrompt();
            } else {
                gp.ui.closeShop();
                gp.playSE(audio.SFX.MENU_SELECT);
            }
            return;
        }
        int tab = gp.ui.shopTabUnderMouse(gameX, gameY);
        if (tab >= 0) {
            if ((tab == 1) != gp.ui.shopSellTab) gp.ui.switchShopTab();
            return;
        }
        int i = gp.ui.shopGridItemUnderMouse(gameX, gameY);
        if (i < 0) return;
        gp.ui.setShopSelectedRow(i);
        gp.ui.activateShopSelection();
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
                // Try slider click first; if not on a slider, treat as item select. subState 0 is the
                // declarative Menu (UI.optionsMenu()), same as KeyHandler.handleOptionsState, clicking a
                // row must call menu.activate() directly; the enterPressed latch is only ever consumed by
                // the non-declarative sub-screens (subStates 1-3), so setting it here was a silent no-op
                // for every button/toggle/selector row (Full Screen, V-Sync, Controls, Back, etc).
                int prevMusic = gp.audio.getMusicVolume();
                int prevSE    = gp.audio.getSEVolume();
                tryApplyVolumeSlider();
                if (gp.audio.getMusicVolume() != prevMusic || gp.audio.getSEVolume() != prevSE) return;
                int i = optionsItemUnderMouse();
                if (i >= 0) {
                    gp.ui.commandNum = i;
                    ui.Menu menu = gp.ui.optionsMenu();
                    menu.setSelected(i);
                    // SELECTOR rows (e.g. Graphics quality) don't respond to activate(), they need
                    // pressLeft()/pressRight() depending on which arrow was clicked, same as A/D on
                    // keyboard (KeyHandler.handleOptionsState). Everything else (buttons/toggles) is a
                    // plain activate().
                    int arrow = menu.selectorArrowAt(i, gameX, gameY);
                    if (arrow < 0) menu.pressLeft();
                    else if (arrow > 0) menu.pressRight();
                    else menu.activate();
                }
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
        // drawJournalScreen() draws each row's text with listY as the BASELINE (drawString's y is
        // the baseline, not the top), so row i's glyphs sit ABOVE listY + i*lineH, not below it.
        // The old hit-test treated that same value as the row's top edge, which put the clickable
        // band a row-height too low, hovering a fragment visually selected the one below/above it.
        // Center each row's hit band on its baseline instead, using half the font's ascent as the
        // offset above the baseline (matches the 18F font drawJournalScreen uses for this list).
        int baselineY = panelY + 30;
        int lineH   = 28;
        int aboveBaseline = 13; // ~ascent/2 for the 18pt list font, keeps the band centered on the glyphs
        int maxVis  = (panelH - 40) / lineH;
        int listTop = baselineY - aboveBaseline;
        // only react if inside the left panel
        if (gameX < panelX || gameX >= panelX + panelW) return;
        if (gameY < listTop || gameY >= listTop + maxVis * lineH) return;
        int row = (gameY - listTop) / lineH;
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
    // WORLD, NPC click-to-interact
    // Click anywhere in playState while an NPC is within its interactRange.
    // No sprite hit-test needed, range proximity is the only requirement.
    // ════════════════════════════════════════════════════════════════════════

    private void clickWorld() {
        // While the wind painter is active, the mouse paints, ignore world interactions.
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
    // COMBAT, left-click attacks in the direction of the cursor
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

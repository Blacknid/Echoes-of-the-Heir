package main;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;

import audio.SFX;
import entity.Entity;
import main.input.InputBindings;

public class KeyHandler implements InputProcessor {

    GamePanel gp;

    public boolean upPressed, downPressed, leftPressed, rightPressed, shotKeyPressed;

    public boolean enterPressed, dashPressed;

    public boolean shockwavePressed, voidSnarePressed, frostNovaPressed, overdrivePressed;

    public boolean showDebugText = false;

    public int teleportCooldown = 0;
    public static final int TELEPORT_COOLDOWN_MAX = 90; // ~1.5 seconds

    private int projectileCooldown = 0;

    // Menu navigation key-repeat (bypasses slow OS repeat delay). Sourced from
    // gp.actions.isDown(MENU_*) every poll (see pollMenuDirections()), so keyboard AND
    // controller d-pad/stick both drive this identically — no separate gamepad menu-nav
    // code needed anywhere in this file.
    private boolean menuUp, menuDown, menuLeft, menuRight;
    private int menuRepeatCounter;
    private static final int MENU_INITIAL_DELAY = 10; // frames before first repeat (~167ms)
    private static final int MENU_REPEAT_RATE = 4;    // frames between repeats (~67ms)

    public KeyHandler(GamePanel gp) {
        this.gp = gp;
    }

    /** Maps a key code to the constant-name spelling used in keybindings.json/InputBindings
     *  (e.g. ESCAPE, SHIFT_LEFT, UP) — NOT Input.Keys.toString(), whose human-readable display
     *  strings ("Escape", "L-Shift", "Up") don't match the JSON's "key:ESCAPE" style tokens and
     *  silently fail every lookup for every non-single-letter key. Built once via reflection over
     *  Input.Keys' public static final int fields, which is exactly what Input.Keys.valueOf(name)
     *  (used nowhere here, but the inverse operation) expects as input. */
    private static final java.util.Map<Integer, String> KEY_CODE_TO_NAME = buildKeyCodeToNameMap();

    private static java.util.Map<Integer, String> buildKeyCodeToNameMap() {
        java.util.Map<Integer, String> map = new java.util.HashMap<>();
        for (java.lang.reflect.Field f : Input.Keys.class.getFields()) {
            if (f.getType() != int.class) continue;
            int mods = f.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isFinal(mods)) continue;
            try {
                int code = f.getInt(null);
                map.putIfAbsent(code, f.getName());
            } catch (IllegalAccessException ignored) {}
        }
        return map;
    }

    private static String keyToken(int code) {
        String name = KEY_CODE_TO_NAME.get(code);
        return "key:" + (name != null ? name : Input.Keys.toString(code));
    }

    public boolean keyDown(int code) {
        if (code == Input.Keys.F) {
            System.out.println("[DBG] keyDown F, token=" + keyToken(code) + " tokensForSHOOT=" + InputBindings.tokensFor(InputBindings.SHOOT)
                + " gameState=" + gp.gameState + " playState=" + GamePanel.playState
                + " debugMenuOpen=" + gp.debugMenuOpen);
        }
        gp.actions.setPhysical(keyToken(code), true);

        // Fire once immediately on first press in menu states, then arm repeat counter. Shared
        // with onControllerButton/onControllerAxisDirection so keyboard, d-pad, and stick all
        // get the exact same initial-fire + repeat-delay behavior.
        if (consumeFreshDirPress()) return true;

        // F11 — fullscreen toggle, works in every game state (standard PC game convention)
        if (code == Input.Keys.F11) {
            gp.applyFullScreenSetting(!gp.fullScreenOn);
            return true;
        }

        dispatchToState(code);
        return true;
    }

    /**
     * Per-state dispatch shared by keyDown (real key events) and onControllerButton (gamepad
     * face/shoulder/stick-click button events, which never go through libGDX's keyDown callback
     * at all — ControllerListener is a separate event source). Every handleXState method below
     * reads action state via gp.actions.consumePressed(...), not this code param, EXCEPT for a
     * handful of genuinely keyboard-only affordances (BACKSPACE to delete text, TAB to cycle a
     * text field, debug keys) — those intentionally no-op for a controller-sourced call, since
     * code is CONTROLLER_CALL (-1) and matches no real Input.Keys constant.
     */
    private void dispatchToState(int code) {
        if (gp.gameState == GamePanel.titleState) {
            handleTitleState(code);
        }
        else if (gp.gameState == GamePanel.playState) {
            handlePlayState(code);
        }
        else if (gp.gameState == GamePanel.pauseState) {
            handlePauseState(code);
        }
        else if (gp.gameState == GamePanel.dialogueState || gp.gameState == GamePanel.cutsceneState) {
            // Allow skipping the awakening cutscene with Escape or Enter
            if (gp.gameState == GamePanel.cutsceneState
                    && gp.csManager.sceneNum == gp.csManager.awakening
                    && (code == Input.Keys.ESCAPE || code == Input.Keys.ENTER)) {
                gp.csManager.skipAwakening();
                return;
            }
            handleDialogueState(code);
        }
        // JOURNAL STATE
        else if (gp.gameState == GamePanel.journalState) {
            handleJournalState(code);
        }
        // CHARACTER STATE
        else if (gp.gameState == GamePanel.characterState) {
            handleCharacterState(code);
        }
        // OPTIONS STATE
        else if (gp.gameState == GamePanel.optionsState) {
            handleOptionsState(code);
        }
        // GAME OVER STATE
        else if (gp.gameState == GamePanel.gameOverState) {
            handleGameOverState(code);
        }
        // LEVEL UP STATE
        else if (gp.gameState == GamePanel.levelUpState) {
            handleLevelUpState(code);
        }
        // SKILL TREE STATE
        else if (gp.gameState == GamePanel.skillTreeState) {
            handleSkillTreeState(code);
        }
    }

    /** Sentinel passed as the "key code" for controller-sourced dispatch — matches no real
     *  Input.Keys constant, so every `code == Input.Keys.X` check in the handleXState methods
     *  correctly falls through as a no-op for genuinely keyboard-only affordances. */
    private static final int CONTROLLER_CALL = -1;

    /**
     * Entry point for GamepadInputAdapter: a controller button maps to one or more actions (see
     * InputBindings), and gp.actions already has the physical edge recorded via setPhysical()
     * before this is called — but nothing would otherwise re-run the per-state dispatch tree,
     * since that tree normally only runs inside keyDown() and gamepad buttons never fire libGDX's
     * keyDown callback (ControllerListener is a separate event source). Without this, one-shot
     * menu actions like confirm/cancel on a face button would be recorded in gp.actions but never
     * actually consumed anywhere — which is exactly why, before this method existed, pressing
     * Cross/X on the title screen silently did nothing.
     */
    public void onControllerButton() {
        if (consumeFreshDirPress()) return;
        dispatchToState(CONTROLLER_CALL);
    }

    /**
     * Entry point for GamepadInputAdapter.axisMoved(): call this the moment a stick axis crosses
     * the deadzone (the "down" transition), so the initial-fire + MENU_INITIAL_DELAY-arm behavior
     * matches keyboard/d-pad exactly. Without this, stick-driven menu nav skipped the initial
     * delay entirely (menuRepeatCounter defaults to 0, not MENU_INITIAL_DELAY), so update()'s
     * poll would repeat at MENU_REPEAT_RATE (4 frames, ~67ms) starting from the very first tick —
     * nearly 2.5x faster than the intended hold-to-repeat delay, easily reading as "one light
     * tap on the stick jumps 2-3 options" since a brief press could span several 67ms ticks.
     */
    public void onControllerAxisDirection() {
        consumeFreshDirPress();
    }

    /** Shared by onControllerButton/onControllerAxisDirection: consumes a fresh MENU_* edge (if
     *  any), fires it immediately, and arms the repeat counter at the initial (slow) delay —
     *  returns true if it handled a direction press, so callers can skip further dispatch. */
    private boolean consumeFreshDirPress() {
        pollMenuDirections();
        boolean freshDirPress = gp.actions.consumePressed(InputBindings.MENU_UP)
                              | gp.actions.consumePressed(InputBindings.MENU_DOWN)
                              | gp.actions.consumePressed(InputBindings.MENU_LEFT)
                              | gp.actions.consumePressed(InputBindings.MENU_RIGHT);
        if (freshDirPress && isInMenuState() && !gp.ui.usernameFieldFocused) {
            fireMenuNavigation();
            menuRepeatCounter = MENU_INITIAL_DELAY;
            return true;
        }
        return false;
    }

    public boolean keyUp(int code) {
        gp.actions.setPhysical(keyToken(code), false);
        pollMenuDirections();
        switch (code) {
            case Input.Keys.W, Input.Keys.UP, Input.Keys.S, Input.Keys.DOWN,
                 Input.Keys.A, Input.Keys.LEFT, Input.Keys.D, Input.Keys.RIGHT -> {
                // Movement mirrors actions.isDown() so releasing one of two bound keys (e.g. W
                // while UP is still held) doesn't clear the field out from under the other key.
                upPressed    = gp.actions.isDown(InputBindings.MOVE_UP);
                downPressed  = gp.actions.isDown(InputBindings.MOVE_DOWN);
                leftPressed  = gp.actions.isDown(InputBindings.MOVE_LEFT);
                rightPressed = gp.actions.isDown(InputBindings.MOVE_RIGHT);
            }
            case Input.Keys.F -> shotKeyPressed = false;
            case Input.Keys.SPACE, Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT -> dashPressed = false;
            case Input.Keys.Z -> shockwavePressed = false;
            case Input.Keys.X -> voidSnarePressed = false;
            case Input.Keys.C -> frostNovaPressed = false;
            case Input.Keys.V -> overdrivePressed = false;
            case Input.Keys.ENTER -> enterPressed = false;
        }
        return true;
    }

    /** Refreshes the four repeat-nav booleans from gp.actions — the single place both keyboard
     *  (via keyDown/keyUp above) and gamepad (via GamepadInputAdapter, which also just calls
     *  gp.actions.setPhysical) end up driving the same menu-navigation repeat system. */
    private void pollMenuDirections() {
        menuUp    = gp.actions.isDown(InputBindings.MENU_UP);
        menuDown  = gp.actions.isDown(InputBindings.MENU_DOWN);
        menuLeft  = gp.actions.isDown(InputBindings.MENU_LEFT);
        menuRight = gp.actions.isDown(InputBindings.MENU_RIGHT);
    }

    @Override
    public boolean keyTyped(char c) {
        if (gp.gameState == GamePanel.titleState && gp.ui.usernameFieldFocused
                && gp.ui.titleScreenState == 0) {
            if (c != ' ' && c != '\b' && c != '\n' && c != '\r' && c != '\t') {
                if (gp.ui.playerUsername.length() < 20) {
                    gp.ui.playerUsername += c;
                    // Keep player.name in sync so it's available immediately in singleplayer
                    gp.player.name = gp.ui.playerUsername;
                }
            }
            return true;
        }
        if (gp.gameState == GamePanel.titleState && gp.ui.titleScreenState == 4) {
            int fieldCount = gp.ui.mpAddMode ? 3 : 2;
            if (gp.ui.mpInputField < fieldCount && c != ' '
                    && c != '\b' && c != '\n' && c != '\r' && c != '\t') {
                typeCharToField(c);
            }
        }
        if (gp.gameState == GamePanel.titleState && gp.ui.titleScreenState == 5
                && gp.ui.friendsAddMode) {
            if (c != '\b' && c != '\n' && c != '\r' && c != '\t'
                    && gp.ui.friendsNewName.length() < 20) {
                gp.ui.friendsNewName += c;
            }
        }
        return true;
    }

    private void handleTitleState(int code) {
        if (gp.ui.titleScreenState == 0) {
            // Username field intercepts most keys when focused
            if (gp.ui.usernameFieldFocused) {
                if (code == Input.Keys.BACKSPACE) {
                    if (!gp.ui.playerUsername.isEmpty()) {
                        gp.ui.playerUsername = gp.ui.playerUsername.substring(0, gp.ui.playerUsername.length() - 1);
                        gp.player.name = gp.ui.playerUsername;
                    }
                } else if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM) || code == Input.Keys.ESCAPE) {
                    gp.ui.usernameFieldFocused = false;
                    gp.player.name = gp.ui.playerUsername;
                    gp.playSE(SFX.MENU_SELECT);
                }
                return; // don't forward to menu navigation while typing
            }
            if (code == Input.Keys.U) {
                gp.ui.usernameFieldFocused = true;
                gp.playSE(SFX.MENU_SELECT);
                return;
            }
            // Navigation + actions are declared on the Menu (UI.titleMenu()); commandNum mirrors
            // the Menu selection so the custom title-screen draw still highlights the right row.
            ui.Menu menu = gp.ui.titleMenu();
            menu.setSelected(gp.ui.commandNum);
            if (gp.actions.consumePressed(InputBindings.MENU_UP))   { menu.moveUp();   gp.ui.commandNum = menu.getSelected(); }
            if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) { menu.moveDown(); gp.ui.commandNum = menu.getSelected(); }
            if (code == Input.Keys.I) {
                gp.ui.titleScreenState = 2;
                gp.ui.commandNum = 0;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
                ui.MenuItem selItem = menu.selectedItem();
                String label = selItem != null ? selItem.label : "";
                if (!gp.ui.blockIfNoUsername(label)) menu.activate();
            }
        } else if (gp.ui.titleScreenState == 1) {
            // Class select — items + actions declared on UI.classMenu(); commandNum mirrors selection.
            ui.Menu menu = gp.ui.classMenu();
            menu.setSelected(gp.ui.commandNum);
            if (gp.actions.consumePressed(InputBindings.MENU_UP))   { menu.moveUp();   gp.ui.commandNum = menu.getSelected(); }
            if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) { menu.moveDown(); gp.ui.commandNum = menu.getSelected(); }
            if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) { menu.activate(); }
        } else if (gp.ui.titleScreenState == 2 && gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            if (gp.ui.commandNum == 0) {
                gp.ui.titleScreenState = 0;
                gp.ui.commandNum = 0;
                gp.playSE(SFX.MENU_SELECT);
            }
        }
        else if (gp.ui.titleScreenState == 3) {
            handleMultiplayerBrowser(code);
        }
        else if (gp.ui.titleScreenState == 4) {
            handleMultiplayerInput(code);
        }
        else if (gp.ui.titleScreenState == 5) {
            handleFriendsListState(code);
        }
        else if (gp.ui.titleScreenState == 6) {
            handleJoinGameState(code);
        }
    }

    private void handleJoinGameState(int code) {
        if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            platform.NfcFriend.stopReading();
            // joinHost() sets guesting=true synchronously, before the async BLE connect result is
            // known — backing out here must undo that too, or bleSession.isActive() stays true
            // forever (GamePanel keeps ticking/drawing a session the player already left).
            gp.bleSession.leaveHost();
            gp.ui.joinGameNfcWaiting = false;
            gp.ui.titleScreenState = 0;
            gp.ui.commandNum = 0;
            gp.playSE(SFX.MENU_SELECT);
        }
    }

    private void handlePauseState(int code) {
        ui.UI ui = gp.ui;
        ui.Menu menu = ui.pauseMenu();
        menu.setSelected(ui.pauseSelection);
        if (gp.actions.consumePressed(InputBindings.MENU_UP))   { menu.moveUp();   ui.pauseSelection = menu.getSelected(); }
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) { menu.moveDown(); ui.pauseSelection = menu.getSelected(); }
        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) { menu.activate(); }
        // P/Escape always resume immediately, regardless of menu selection — preserves the
        // original one-key-toggle muscle memory rather than requiring RESUME to be highlighted.
        if (gp.actions.consumePressed(InputBindings.PAUSE) || gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            gp.gameState = GamePanel.playState;
        }
    }

    private void handleFriendsListState(int code) {
        ui.UI ui = gp.ui;
        int friendCount = gp.friendsListManager.getFriends().size();
        int syncingCount = gp.friendsListManager.getPendingLocalAdds().size();
        int syncingEnd = friendCount + syncingCount; // rows [friendCount, syncingEnd) are unselectable "syncing" entries
        int requestCount = gp.friendsListManager.getPendingRequests().size();
        int rowCount = syncingEnd + requestCount;
        boolean hasUsername = gp.friendsListManager.getClaimedUsername() != null;
        int menuCount = hasUsername ? 2 : 3; // ADD FRIEND [, CLAIM USERNAME], BACK
        int totalItems = rowCount + menuCount;

        if (ui.friendsAddMode || ui.friendsClaimMode) {
            if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
                ui.friendsAddMode = false;
                ui.friendsClaimMode = false;
                ui.friendsNewName = "";
                gp.playSE(SFX.MENU_SELECT);
            } else if (code == Input.Keys.BACKSPACE) {
                if (!ui.friendsNewName.isEmpty()) {
                    ui.friendsNewName = ui.friendsNewName.substring(0, ui.friendsNewName.length() - 1);
                }
            } else if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
                String typed = ui.friendsNewName.trim();
                boolean claiming = ui.friendsClaimMode;
                ui.friendsNewName = "";
                ui.friendsAddMode = false;
                ui.friendsClaimMode = false;
                gp.playSE(SFX.MENU_SELECT);
                if (!typed.isBlank()) {
                    new Thread(() -> runFriendAction(claiming, typed), "Friends-Action").start();
                }
            }
            return;
        }

        if (ui.friendsNfcWaiting) {
            if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
                platform.NfcFriend.stopReading();
                ui.friendsNfcWaiting = false;
                gp.playSE(SFX.MENU_SELECT);
            }
            return;
        }

        if (gp.actions.consumePressed(InputBindings.MENU_UP)) {
            ui.friendsSelection = (ui.friendsSelection - 1 + totalItems) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
            ui.friendsSelection = (ui.friendsSelection + 1) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            platform.NfcFriend.stopReading();
            ui.titleScreenState = 0;
            ui.commandNum = 0;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == Input.Keys.FORWARD_DEL) {
            if (ui.friendsSelection < friendCount) {
                int index = ui.friendsSelection;
                new Thread(() -> gp.friendsListManager.removeFriend(index), "Friends-Remove").start();
                gp.playSE(SFX.MENU_SELECT);
            } else if (ui.friendsSelection < syncingEnd) {
                // no-op: a queued-but-not-yet-sent NFC add isn't a real server request yet
            } else if (ui.friendsSelection < rowCount) {
                String username = gp.friendsListManager.getPendingRequests().get(ui.friendsSelection - syncingEnd);
                new Thread(() -> gp.friendsListManager.respondFriendRequest(username, false), "Friends-Decline").start();
                gp.playSE(SFX.MENU_SELECT);
            }
            if (rowCount > 0 && ui.friendsSelection >= rowCount - 1) {
                ui.friendsSelection = Math.max(0, rowCount - 2);
            }
        }
        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            if (ui.friendsSelection < friendCount) {
                // no-op: selecting an existing friend entry does nothing yet
            } else if (ui.friendsSelection < syncingEnd) {
                // no-op: nothing to accept/decline until the queued add reaches the server
            } else if (ui.friendsSelection < rowCount) {
                String username = gp.friendsListManager.getPendingRequests().get(ui.friendsSelection - syncingEnd);
                new Thread(() -> {
                    gp.friendsListManager.respondFriendRequest(username, true);
                }, "Friends-Accept").start();
                gp.playSE(SFX.MENU_SELECT);
            } else {
                int menuIdx = ui.friendsSelection - rowCount;
                if (menuIdx == 0) {
                    startAddFriend();
                    gp.playSE(SFX.MENU_SELECT);
                } else if (menuIdx == 1 && !hasUsername) {
                    ui.friendsClaimMode = true;
                    // Pre-fill with the name the server says this license already owns, if any.
                    // Usually null here (the option is hidden once a username is known), but the
                    // screen can be opened before the GET_MY_USERNAME fetch has landed.
                    String claimed = gp.friendsListManager.getClaimedUsername();
                    ui.friendsNewName = claimed != null ? claimed : "";
                    gp.playSE(SFX.MENU_SELECT);
                } else {
                    platform.NfcFriend.stopReading();
                    ui.titleScreenState = 0;
                    ui.commandNum = 0;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
        }
    }

    /** Runs a claim-username or send-friend-request call off the render thread, then surfaces the result. */
    private void runFriendAction(boolean claiming, String typed) {
        ui.UI ui = gp.ui;
        String status = claiming
                ? gp.friendsListManager.claimUsername(typed)
                : gp.friendsListManager.sendFriendRequest(typed);
        ui.friendsStatusMessage = claiming ? "Claim: " + status : "Request: " + status;
        if (!claiming && "SENT".equals(status)) {
            gp.friendsListManager.refresh();
        }
    }

    /**
     * ADD FRIEND on platforms with NFC (Android): starts reader mode instead of the typed-username
     * box, per platform.NfcFriend's class doc — the phone being tapped can have the game fully
     * closed, but this device must stay on this screen actively reading. Desktop (no NFC hardware)
     * falls back to the existing typed flow.
     */
    private void startAddFriend() {
        ui.UI ui = gp.ui;
        if (!platform.NfcFriend.isSupported()) {
            ui.friendsAddMode = true;
            ui.friendsNewName = "";
            return;
        }
        ui.friendsNfcWaiting = true;
        ui.friendsStatusMessage = "";
        platform.NfcFriend.startReading(payload -> {
            ui.friendsNfcWaiting = false;
            platform.NfcFriendPayload.Decoded decoded =
                    payload != null ? platform.NfcFriendPayload.decode(payload) : null;
            if (decoded == null) {
                ui.friendsStatusMessage = "Tap failed — try again";
                return;
            }
            // Shows up immediately (marked "syncing") and is queued to disk — the actual
            // SEND_FRIEND_REQUEST reaches the server whenever it's next reachable, not
            // necessarily right now. See FriendsListManager's offline-NFC-adds class doc.
            gp.friendsListManager.addFriendByNfc(decoded.friendId(), decoded.username());
            ui.friendsStatusMessage = "Added " + decoded.username() + " — syncing…";
        });
    }

    public void startGame() {
        gp.gameState = GamePanel.playState;
        String path = gp.mapManager.mapRegistry.getOrDefault(gp.mapManager.currentMapId, "/res/maps/Canvas_Village.tmx");
        gp.mapObjectLoader.loadMapProperties(path);
    }

    public void startNewGame() {
        // Wipe all leftover world / player / map state from any previous run. Without this,
        // choosing NEW GAME after an End-Game-to-title (or after a LOAD, or after a multiplayer
        // session) keeps the old inventory, opened chests, story flags, etc.
        gp.resetSession();
        // Enter cutscene state — the Awakening scene handles the rest
        gp.gameState = GamePanel.cutsceneState;
        gp.csManager.sceneNum = gp.csManager.awakening;
        gp.csManager.scenePhase = 0;
    }

    private void handleMultiplayerBrowser(int code) {
        int serverCount = gp.serverList.getServers().size();
        int totalItems = serverCount + 3;

        if (gp.actions.consumePressed(InputBindings.MENU_UP)) {
            gp.ui.mpServerSelection = (gp.ui.mpServerSelection - 1 + totalItems) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
            gp.ui.mpServerSelection = (gp.ui.mpServerSelection + 1) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            gp.ui.titleScreenState = 0;
            gp.ui.commandNum = gp.ui.titleHasSave() ? 2 : 1; // highlight Multiplayer on return
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == Input.Keys.FORWARD_DEL) {
            if (gp.ui.mpServerSelection < serverCount) {
                gp.serverList.removeServer(gp.ui.mpServerSelection);
                if (gp.ui.mpServerSelection >= gp.serverList.getServers().size()) {
                    gp.ui.mpServerSelection = Math.max(0, gp.serverList.getServers().size() - 1);
                }
                gp.playSE(SFX.MENU_SELECT);
            }
        }
        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            if (gp.ui.mpServerSelection < serverCount) {
                String[] srv = gp.serverList.getServers().get(gp.ui.mpServerSelection);
                connectToServer(srv[1], srv[2]);
            } else {
                int menuIdx = gp.ui.mpServerSelection - serverCount;
                if (menuIdx == 0) {
                    gp.ui.titleScreenState = 4;
                    gp.ui.mpAddMode = true;
                    gp.ui.mpInputField = 0;
                    gp.ui.mpServerName = "";
                    gp.ui.mpServerIP = "";
                    gp.ui.mpServerPort = "7777";
                    gp.playSE(SFX.MENU_SELECT);
                } else if (menuIdx == 1) {
                    gp.ui.titleScreenState = 4;
                    gp.ui.mpAddMode = false;
                    gp.ui.mpInputField = 0; // start at IP field (field 0 in direct mode)
                    gp.ui.mpServerIP = "";
                    gp.ui.mpServerPort = "7777";
                    gp.playSE(SFX.MENU_SELECT);
                } else if (menuIdx == 2) {
                    gp.ui.titleScreenState = 0;
                    gp.ui.commandNum = gp.ui.titleHasSave() ? 2 : 1;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
        }
    }

    private void handleMultiplayerInput(int code) {
        int fieldCount = gp.ui.mpAddMode ? 3 : 2;
        int buttonCount = gp.ui.mpAddMode ? 3 : 2;
        int totalItems = fieldCount + buttonCount;
        boolean inTextField = gp.ui.mpInputField < fieldCount;

        if (code == Input.Keys.TAB) {
            gp.ui.mpInputField = (gp.ui.mpInputField + 1) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            gp.ui.titleScreenState = 3;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (!inTextField) {
            if (gp.actions.consumePressed(InputBindings.MENU_UP)) {
                gp.ui.mpInputField--;
                if (gp.ui.mpInputField < fieldCount) gp.ui.mpInputField = totalItems - 1;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
                gp.ui.mpInputField++;
                if (gp.ui.mpInputField >= totalItems) gp.ui.mpInputField = fieldCount;
                gp.playSE(SFX.MENU_SELECT);
            }
        } else {
            if (code == Input.Keys.UP) {
                int newField = gp.ui.mpInputField - 1;
                if (!gp.ui.mpAddMode && newField == 0) newField = fieldCount - 1;
                if (newField >= 0 && newField < fieldCount) {
                    gp.ui.mpInputField = newField;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
            if (code == Input.Keys.DOWN) {
                int newField = gp.ui.mpInputField + 1;
                if (newField < fieldCount) {
                    gp.ui.mpInputField = newField;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
        }

        if (code == Input.Keys.BACKSPACE && inTextField) {
            deleteCharFromField();
        }

        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            if (inTextField) {
                gp.ui.mpInputField++;
                if (gp.ui.mpInputField >= fieldCount) {
                }
                gp.playSE(SFX.MENU_SELECT);
            } else {
                int btnIdx = gp.ui.mpInputField - fieldCount;
                handleMultiplayerInputButton(btnIdx);
            }
        }
    }

    private void handleMultiplayerInputButton(int btnIdx) {
        if (gp.ui.mpAddMode) {
            if (btnIdx == 0) {
                if (!gp.ui.mpServerIP.isEmpty()) {
                    String name = gp.ui.mpServerName.isEmpty() ? gp.ui.mpServerIP : gp.ui.mpServerName;
                    gp.serverList.addServer(name, gp.ui.mpServerIP, gp.ui.mpServerPort);
                    connectToServer(gp.ui.mpServerIP, gp.ui.mpServerPort);
                }
            } else if (btnIdx == 1) {
                if (!gp.ui.mpServerIP.isEmpty()) {
                    String name = gp.ui.mpServerName.isEmpty() ? gp.ui.mpServerIP : gp.ui.mpServerName;
                    gp.serverList.addServer(name, gp.ui.mpServerIP, gp.ui.mpServerPort);
                    gp.ui.titleScreenState = 3;
                    gp.playSE(SFX.MENU_SELECT);
                }
            } else {
                gp.ui.titleScreenState = 3;
                gp.playSE(SFX.MENU_SELECT);
            }
        } else {
            if (btnIdx == 0) {
                if (!gp.ui.mpServerIP.isEmpty()) {
                    connectToServer(gp.ui.mpServerIP, gp.ui.mpServerPort);
                }
            } else {
                gp.ui.titleScreenState = 3;
                gp.playSE(SFX.MENU_SELECT);
            }
        }
    }

    private void connectToServer(String ip, String portStr) {
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            gp.mpClient.connectionStatus = "Invalid port number!";
            return;
        }
        // Start from a clean slate. Singleplayer and multiplayer share one GamePanel, so without
        // this the session inherits whatever the previous singleplayer run left in memory — its
        // inventory, level, story flags and world — instead of a fresh character. resetSession()
        // also tears down any session still live, so it must run BEFORE we connect the new one.
        gp.resetSession();

        // Start connection attempt — use username from title screen, fall back to "Player"
        String username = (gp.ui.playerUsername != null && !gp.ui.playerUsername.isEmpty())
                ? gp.ui.playerUsername : "Player";
        gp.mpClient.connect(ip.trim(), port, username, "Fighter");
        // Start game in multiplayer mode
        gp.multiplayerMode = true;
        // Watcher thread:
        //   1. wait up to 6 s for the TCP handshake to succeed
        //   2. then wait up to 60 s for the streamed world to fully load
        // Only after the world is ready do we enter playState — entering
        // earlier would render an empty map until chunks arrived.
        new Thread(() -> {
            long start = System.currentTimeMillis();
            // Phase 1: handshake
            while (System.currentTimeMillis() - start < 6000) {
                if (gp.mpClient.isConnected()) break;
                if (!gp.mpClient.isConnecting()) {
                    gp.multiplayerMode = false;
                    return;
                }
                try { Thread.sleep(100); } catch (InterruptedException ex) { break; }
            }
            if (!gp.mpClient.isConnected()) {
                gp.mpClient.connectionStatus = "Connection timed out.";
                gp.mpClient.disconnect();
                gp.multiplayerMode = false;
                return;
            }
            // Phase 2: world streaming
            long worldDeadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < worldDeadline) {
                if (!gp.mpClient.isConnected()) {
                    gp.multiplayerMode = false;
                    return;
                }
                gp.mpClient.connectionStatus = gp.mpClient.mapStreamer.progressString();
                if (gp.mpClient.isWorldReady()) {
                    gp.gameState = gp.playState;
                    gp.playMusic(SFX.MAIN_THEME);
                    return;
                }
                try { Thread.sleep(150); } catch (InterruptedException ex) { break; }
            }
            gp.mpClient.connectionStatus = "World load timed out.";
            gp.mpClient.disconnect();
            gp.multiplayerMode = false;
        }, "MP-Watcher").start();
    }

    private void deleteCharFromField() {
        int field = gp.ui.mpInputField;
        if (gp.ui.mpAddMode) {
            switch (field) {
                case 0 -> { if (!gp.ui.mpServerName.isEmpty()) gp.ui.mpServerName = gp.ui.mpServerName.substring(0, gp.ui.mpServerName.length() - 1); }
                case 1 -> { if (!gp.ui.mpServerIP.isEmpty()) gp.ui.mpServerIP = gp.ui.mpServerIP.substring(0, gp.ui.mpServerIP.length() - 1); }
                case 2 -> { if (!gp.ui.mpServerPort.isEmpty()) gp.ui.mpServerPort = gp.ui.mpServerPort.substring(0, gp.ui.mpServerPort.length() - 1); }
            }
        } else {
            switch (field) {
                case 0 -> { if (!gp.ui.mpServerIP.isEmpty()) gp.ui.mpServerIP = gp.ui.mpServerIP.substring(0, gp.ui.mpServerIP.length() - 1); }
                case 1 -> { if (!gp.ui.mpServerPort.isEmpty()) gp.ui.mpServerPort = gp.ui.mpServerPort.substring(0, gp.ui.mpServerPort.length() - 1); }
            }
        }
    }

    private void typeCharToField(char c) {
        if (c < 32 || c > 126) return;

        int field = gp.ui.mpInputField;
        int maxLen = 40;

        if (gp.ui.mpAddMode) {
            switch (field) {
                case 0 -> { if (gp.ui.mpServerName.length() < maxLen) gp.ui.mpServerName += c; }
                case 1 -> { if (gp.ui.mpServerIP.length() < maxLen) gp.ui.mpServerIP += c; }
                case 2 -> { if (gp.ui.mpServerPort.length() < 5 && Character.isDigit(c)) gp.ui.mpServerPort += c; }
            }
        } else {
            switch (field) {
                case 0 -> { if (gp.ui.mpServerIP.length() < maxLen) gp.ui.mpServerIP += c; }
                case 1 -> { if (gp.ui.mpServerPort.length() < 5 && Character.isDigit(c)) gp.ui.mpServerPort += c; }
            }
        }
    }

    private void handlePlayState(int code) {
        // Ctrl+D — enable / disable debug mode entirely
        if (ctrlDown() && code == Input.Keys.D) {
            gp.debugModeEnabled = !gp.debugModeEnabled;
            if (!gp.debugModeEnabled && gp.debugMenuOpen) gp.toggleDebugMenu();
            return;
        }

        // F9 — open / close debug panel (only when debug mode is on)
        if (code == Input.Keys.F9) {
            if (gp.debugModeEnabled && (gp.debugMenuOpen || !isOverlayOpen())) gp.toggleDebugMenu();
            return;
        }

        // Ctrl+W — toggle the Wind Painter (debug mode only)
        if (ctrlDown() && code == Input.Keys.W) {
            if (gp.debugModeEnabled && gp.windPainter != null) gp.windPainter.toggle();
            return;
        }
        // Ctrl+S — save the wind map while the painter is active
        if (ctrlDown() && code == Input.Keys.S) {
            if (gp.windPainter != null && gp.windPainter.handleSave()) return;
        }
        // While the painter is active, route its editing keys (and swallow them so they
        // don't also drive the player / game actions).
        if (gp.windPainter != null && gp.windPainter.isActive()) {
            if (gp.windPainter.handleKey(code)) return;
        }

        if (gp.debugMenuOpen) {
            if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) gp.activateDebugMenuSelection();
            // Consuming OPTIONS here (not just checking the raw key) drains its pending-press
            // flag — otherwise it stays pending while gameState never left playState (the debug
            // menu is an overlay flag, not a separate state), and the next pollGameplayActions()
            // tick would immediately open the options screen too.
            if (gp.actions.consumePressed(InputBindings.OPTIONS)) gp.toggleDebugMenu();
            return;
        }

        if (gp.debugModeEnabled) {
            if (code == Input.Keys.T) { showDebugText = !showDebugText; }
            if (code == Input.Keys.F5) { gp.toggleDebugSepia(); }
            if (code == Input.Keys.F7) { gp.collectDebugFragments(); }
            if (code == Input.Keys.F8) { gp.teleportToAwakeningDebug(); }
            if (code == Input.Keys.F6) { gp.triggerDebugFlashback(); }
            if (code == Input.Keys.H) { gp.HitBoxes = !gp.HitBoxes; System.out.println("[DBG] H pressed, HitBoxes=" + gp.HitBoxes); }
            if (code == Input.Keys.R) { gp.reloadCurrentMapDebug(); }
            if (code == Input.Keys.Y) { gp.drawPath = !gp.drawPath; }
        } else if (code == Input.Keys.H) {
            System.out.println("[DBG] H pressed but debugModeEnabled=false (Ctrl+D to enable debug mode first)");
        }

        if (gp.questManager != null && gp.questManager.isLogOpen()) {
            if (code == Input.Keys.UP)   gp.questManager.scrollLog(-1);
            if (code == Input.Keys.DOWN) gp.questManager.scrollLog(1);
        }

        // Gameplay actions (movement, dash, attack, skills, screen toggles) are polled every
        // frame from update() instead of here, so gamepad-only presses (which don't go through
        // KeyHandler.keyDown) aren't missed. Still called once here too so keyboard input feels
        // immediate on the exact frame it's pressed, not delayed to the next update() tick;
        // consumePressed() is safe to call from both places since it self-clears after the
        // first read each frame.
        pollGameplayActions();
    }

    /** Reads gp.actions for every gameplay action and applies the result — movement booleans,
     *  one-shot flags, screen-toggle transitions. Called once per key press (from
     *  handlePlayState, for immediate keyboard response) and once per game-loop tick (from
     *  update(), to also catch gamepad-only edges that never go through keyDown). Guarded the
     *  same way handlePlayState is: skipped while the debug menu or wind painter is capturing
     *  input. */
    private void pollGameplayActions() {
        if (gp.gameState != GamePanel.playState) return;
        if (gp.debugMenuOpen) return;
        if (gp.windPainter != null && gp.windPainter.isActive()) return;

        if (gp.actions.consumePressed(InputBindings.MINIMAP) && gp.minimap != null && !isOverlayOpen()) {
            gp.minimap.toggleWorldMap();
            if (gp.minimap.isWorldMapOpen()) {
                // Prevent movement carry-over when entering map mode
                upPressed = false;
                downPressed = false;
                leftPressed = false;
                rightPressed = false;
            }
            return;
        }

        // Close map first if it's open
        if (gp.minimap != null && gp.minimap.isWorldMapOpen()) {
            if (gp.actions.consumePressed(InputBindings.OPTIONS) || gp.actions.consumePressed(InputBindings.MINIMAP)) {
                gp.minimap.toggleWorldMap();
            }
            return;
        }

        upPressed    = gp.actions.isDown(InputBindings.MOVE_UP);
        downPressed  = gp.actions.isDown(InputBindings.MOVE_DOWN);
        leftPressed  = gp.actions.isDown(InputBindings.MOVE_LEFT);
        rightPressed = gp.actions.isDown(InputBindings.MOVE_RIGHT);

        // Each of these opens a screen that ALSO reacts to MENU_CANCEL (same key/button, ESCAPE
        // or Circle, as some of these triggers) to close itself. clearAllPending() right here —
        // not just relying on GamePanel's deferred per-tick gameState-change detector — prevents
        // a race: if the player's very next keypress is a direction key, keyDown's
        // consumeFreshDirPress() early-returns before ever calling handleXState, so a stale
        // MENU_CANCEL flag from THIS press could sit unconsumed until some later keypress
        // finally reaches handleXState and reads it as "cancel", instantly closing the screen
        // that press had nothing to do with.
        if (gp.actions.consumePressed(InputBindings.PAUSE))            { gp.gameState = GamePanel.pauseState; gp.actions.clearAllPending(); }
        if (gp.actions.consumePressed(InputBindings.OPTIONS))          { gp.gameState = GamePanel.optionsState; gp.actions.clearAllPending(); }
        if (gp.actions.consumePressed(InputBindings.CHARACTER_SCREEN) && !isOverlayOpen()) { gp.gameState = GamePanel.characterState; gp.actions.clearAllPending(); }
        if (gp.actions.consumePressed(InputBindings.SKILL_TREE) && !isOverlayOpen())       { gp.gameState = GamePanel.skillTreeState; gp.actions.clearAllPending(); }
        if (gp.actions.consumePressed(InputBindings.JOURNAL) && !isOverlayOpen())          { gp.gameState = GamePanel.journalState; gp.actions.clearAllPending(); }
        // enterPressed drives melee swing (Player.java) AND dialogue/interact (EventHandler.java)
        // — ATTACK (ENTER/left-click/Square) and INTERACT (ENTER/Cross) both feed it so keyboard's
        // historical "ENTER does both" behavior is preserved, while a controller gets separate
        // buttons for "swing sword" vs "talk to NPC / confirm". Both consumePressed() calls must
        // run every poll (not short-circuit via ||) so a controller press on either button is
        // never left unconsumed/stale.
        boolean attackPressed = gp.actions.consumePressed(InputBindings.ATTACK);
        boolean interactPressed = gp.actions.consumePressed(InputBindings.INTERACT);
        if (attackPressed || interactPressed) { enterPressed = true; }
        // shotKeyPressed is read (but never cleared) by Player.fireProjectileIfRequested(), so
        // it must be true for exactly one tick per press, same as TouchControlsOverlay's
        // documented true-then-false-next-sample contract — reset every poll, then re-set only
        // on a fresh edge, instead of leaving it permanently true after the first press. SHOOT
        // (F/R2) is a separate action from ATTACK so one controller press can't fire both the
        // sword swing and the arrow at once.
        shotKeyPressed = gp.actions.consumePressed(InputBindings.SHOOT);
        if (shotKeyPressed) System.out.println("[DBG] pollGameplayActions: SHOOT consumed, shotKeyPressed=true");

        // Dash: fires once per press regardless of which bound key/button triggered it —
        // consumePressed already gives one-shot-on-rising-edge semantics, replacing the old
        // dashKeyHeld latch (Player dashes in the facing direction, so a direction key need
        // not be held; dash from standstill lunges the way you're facing).
        if (gp.actions.consumePressed(InputBindings.DASH)) {
            dashPressed = true;
        }

        if (gp.actions.consumePressed(InputBindings.QUEST_LOG) && gp.questManager != null) {
            if (gp.questManager.isLogOpen()) {
                gp.questManager.toggleLog(); // always allow closing
            } else if (!isOverlayOpen()) {
                gp.questManager.toggleLog();
            }
        }

        if (gp.actions.consumePressed(InputBindings.TELEPORT) && gp.teleportation) { handleTeleport(); }
        if (gp.actions.consumePressed(InputBindings.SKILL_SHOCKWAVE))  { shockwavePressed = true; }
        if (gp.actions.consumePressed(InputBindings.SKILL_VOID_SNARE)) { voidSnarePressed = true; }
        if (gp.actions.consumePressed(InputBindings.SKILL_FROST_NOVA)) { frostNovaPressed = true; }
        if (gp.actions.consumePressed(InputBindings.SKILL_OVERDRIVE))  { overdrivePressed = true; }
    }

    private boolean isOverlayOpen() {
         return gp.debugMenuOpen ||
             (gp.questManager != null && gp.questManager.isLogOpen()) ||
               (gp.minimap != null && gp.minimap.isWorldMapOpen());
    }

    private void handleDialogueState(int code) {
        Entity npc = gp.ui.npc;
        // If choices are showing and typewriter is done, navigate choices with W/S
        if (npc != null && npc.dialogueChoices != null && npc.dialogueChoices.length > 0) {
            if (gp.actions.consumePressed(InputBindings.MENU_UP)) {
                npc.selectedChoice--;
                if (npc.selectedChoice < 0) npc.selectedChoice = npc.dialogueChoices.length - 1;
            }
            if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
                npc.selectedChoice++;
                if (npc.selectedChoice >= npc.dialogueChoices.length) npc.selectedChoice = 0;
            }
        }
        // Consuming INTERACT here (not just checking the raw key) drains its pending-press flag —
        // otherwise it stays pending after dialogue ends and gameState returns to playState, and
        // the next pollGameplayActions() tick would fire a spurious extra interact/attack.
        if (gp.actions.consumePressed(InputBindings.INTERACT) || gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            enterPressed = true;
        }
    }

    private void handleJournalState(int code) {
        // Consuming JOURNAL/MENU_CANCEL here (not just checking the raw key) drains their
        // pending-press flags — otherwise the close keypress leaves one pending, and the next
        // playState poll in pollGameplayActions() would immediately re-open the screen it was
        // just closed from.
        if (gp.actions.consumePressed(InputBindings.JOURNAL) || gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            gp.gameState = GamePanel.playState;
            return;
        }
        if (gp.actions.consumePressed(InputBindings.MENU_UP)) {
            gp.ui.journalSelectedIndex--;
            if (gp.ui.journalSelectedIndex < 0) gp.ui.journalSelectedIndex = 0;
        }
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
            if (gp.memoryJournal != null) {
                int max = gp.memoryJournal.getAllSorted().size() - 1;
                gp.ui.journalSelectedIndex++;
                if (gp.ui.journalSelectedIndex > max) gp.ui.journalSelectedIndex = max;
            }
        }
    }

    private void handleTeleport() {
        if ( teleportCooldown == 0) {
            // Spawn departure particles at origin
            gp.player.spawnTeleportParticles(true);

            switch (gp.player.direction) {
                case Entity.DIR_UP    -> gp.player.worldY -= gp.tileSize * 3;
                case Entity.DIR_DOWN  -> gp.player.worldY += gp.tileSize * 3;
                case Entity.DIR_LEFT  -> gp.player.worldX -= gp.tileSize * 3;
                case Entity.DIR_RIGHT -> gp.player.worldX += gp.tileSize * 3;
            }

            // Spawn arrival particles at destination
            gp.player.spawnTeleportParticles(false);

            // Brief invincibility after teleport
            gp.player.invincible = true;
            gp.player.invincibleCounter = 20;

            gp.screenShake.shakeLight();
            gp.playSE(SFX.MENU_SELECT);

            teleportCooldown = gp.player.getTeleportCooldownMax();
        }
    }

    private void handleSkillTreeState(int code) {
        // Consuming SKILL_TREE/MENU_CANCEL here (not just checking the raw key) drains their
        // pending-press flags — otherwise the close keypress leaves one pending, and the next
        // playState poll in pollGameplayActions() would immediately re-open the screen it was
        // just closed from.
        if (gp.actions.consumePressed(InputBindings.MENU_CANCEL) || gp.actions.consumePressed(InputBindings.SKILL_TREE)) {
            gp.gameState = GamePanel.playState;
            gp.playSE(SFX.MENU_SELECT);
            return;
        }

        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            if (!gp.player.skillTree.unlockSelected(gp.player)) {
                gp.playSE(SFX.PLAYER_HIT);
            } else {
                gp.ui.invalidateSkillTreeConnectorCache();
            }
        }
    }

    private void handleCharacterState(int code) {
        // Consuming CHARACTER_SCREEN/MENU_CANCEL here (not just checking the raw key) drains
        // their pending-press flags — otherwise the close keypress leaves one pending, and the
        // next playState poll in pollGameplayActions() would immediately re-open the screen it
        // was just closed from.
        if (gp.actions.consumePressed(InputBindings.CHARACTER_SCREEN)) gp.gameState = GamePanel.playState;

        if (gp.actions.consumePressed(InputBindings.MENU_UP)    && gp.ui.slotRow > 0) { gp.ui.slotRow--; gp.playSE(SFX.MENU_CURSOR); }
        if (gp.actions.consumePressed(InputBindings.MENU_LEFT)  && gp.ui.slotCol > 0) { gp.ui.slotCol--; gp.playSE(SFX.MENU_CURSOR); }
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)  && gp.ui.slotRow < 3) { gp.ui.slotRow++; gp.playSE(SFX.MENU_CURSOR); }
        if (gp.actions.consumePressed(InputBindings.MENU_RIGHT) && gp.ui.slotCol < 4) { gp.ui.slotCol++; gp.playSE(SFX.MENU_CURSOR); }
        if (gp.actions.consumePressed(InputBindings.MENU_CANCEL)) gp.gameState = GamePanel.playState;

        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) gp.player.selectItem();

        // Drop item with BACKSPACE, but only if it's not currently equipped
        if (code == Input.Keys.BACKSPACE && ( gp.ui.getItemIndexOnSlot() != gp.player.getCurrentWeaponSlot() &&
        gp.ui.getItemIndexOnSlot() != gp.player.getCurrentShieldSlot() )) { gp.player.dropItem(); }
    }

    private void handleOptionsState(int code) {
        // Consuming OPTIONS/MENU_CANCEL here (not just checking the raw key) drains their
        // pending-press flags — otherwise the close keypress leaves one pending, and the next
        // playState poll in pollGameplayActions() would immediately re-open the options screen
        // it was just closed from.
        if (gp.actions.consumePressed(InputBindings.OPTIONS) || gp.actions.consumePressed(InputBindings.MENU_CANCEL)) {
            gp.gameState = GamePanel.playState;
        }

        // subState 0 (the top Settings screen) is a declarative Menu (UI.optionsMenu()): Enter
        // activates, A/D adjust selector/sliders. The sub-screens (Controls list, End-Game confirm)
        // still use the enterPressed latch consumed inside their draw methods.
        if (gp.ui.subState == 0) {
            ui.Menu menu = gp.ui.optionsMenu();
            menu.setSelected(gp.ui.commandNum);
            if (gp.actions.consumePressed(InputBindings.MENU_UP))    { menu.moveUp();   gp.ui.commandNum = menu.getSelected(); }
            if (gp.actions.consumePressed(InputBindings.MENU_DOWN))  { menu.moveDown(); gp.ui.commandNum = menu.getSelected(); }
            if (gp.actions.consumePressed(InputBindings.MENU_LEFT))  menu.pressLeft();
            if (gp.actions.consumePressed(InputBindings.MENU_RIGHT)) menu.pressRight();
            if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) menu.activate();
            return;
        }

        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) enterPressed = true;
        int maxCommandNum = switch (gp.ui.subState) {
            case 3 -> 2;
            default -> 0;
        };
        if (gp.actions.consumePressed(InputBindings.MENU_UP))   gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCommandNum + 1) % (maxCommandNum + 1);
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCommandNum + 1);
    }

    private void handleGameOverState(int code) {
        // Clear movement flags to prevent interference
        upPressed = false;
        downPressed = false;
        leftPressed = false;
        rightPressed = false;

        // Menu navigation: MENU_UP/MENU_DOWN toggle between the two options.
        if (gp.actions.consumePressed(InputBindings.MENU_UP) || gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
            gp.ui.commandNum = 1 - gp.ui.commandNum;
            gp.playSE(SFX.MENU_SELECT);
        }
        // Execute selected option with ENTER/controller confirm — actions are declared on the
        // Menu (UI.gameOverMenu()). Consuming MENU_CONFIRM here (not just checking the raw key)
        // drains its pending-press flag — otherwise it stays pending across a Retry (which
        // returns to playState), and the next pollGameplayActions() tick would fire a spurious
        // extra interact/attack.
        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            gp.ui.gameOverMenu().setSelected(gp.ui.commandNum);
            gp.ui.gameOverMenu().activate();
        }
    }

    private void handleLevelUpState(int code) {
        if (gp.actions.consumePressed(InputBindings.MENU_UP)) {
            gp.player.levelUpChoice--;
            if (gp.player.levelUpChoice < 0) gp.player.levelUpChoice = 2;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.actions.consumePressed(InputBindings.MENU_DOWN)) {
            gp.player.levelUpChoice++;
            if (gp.player.levelUpChoice > 2) gp.player.levelUpChoice = 0;
            gp.playSE(SFX.MENU_SELECT);
        }
        // Consuming MENU_CONFIRM here (not just checking the raw key) drains its pending-press
        // flag — otherwise it stays pending after returning to playState, and the next
        // pollGameplayActions() tick would fire a spurious extra interact/attack.
        if (gp.actions.consumePressed(InputBindings.MENU_CONFIRM)) {
            gp.player.applyLevelUpChoice();
            gp.gameState = GamePanel.playState;
        }
    }

    // ============================
    // UPDATE METHOD
    // ============================
    public void update() {
        // Reduce cooldowns every game tick
        if (projectileCooldown > 0) projectileCooldown--;
        if (teleportCooldown > 0) teleportCooldown--;

        // Catches gamepad-only edges (button/axis presses never go through keyDown), and
        // keeps movement booleans continuously in sync with held actions every tick.
        pollGameplayActions();

        // Menu navigation key repeat — refresh from gp.actions so a held gamepad d-pad/stick
        // (fed via GamepadInputAdapter -> gp.actions.setPhysical, same as keyboard) drives this
        // identically to a held keyboard key.
        pollMenuDirections();
        if (isInMenuState() && !gp.ui.usernameFieldFocused && (menuUp || menuDown || menuLeft || menuRight)) {
            if (menuRepeatCounter > 0) {
                menuRepeatCounter--;
            } else {
                fireMenuNavigation();
                menuRepeatCounter = MENU_REPEAT_RATE;
            }
        }
    }

    private boolean isInMenuState() {
        int s = gp.gameState;
        if (s == GamePanel.titleState) {
            return gp.ui.titleScreenState != 4;
        }
        return s == GamePanel.characterState || s == GamePanel.levelUpState ||
               s == GamePanel.skillTreeState || s == GamePanel.optionsState ||
               s == GamePanel.journalState   || s == GamePanel.gameOverState ||
               s == GamePanel.pauseState     ||
               gp.debugMenuOpen;
    }

    private void fireMenuNavigation() {
        if (gp.debugMenuOpen) {
            if (menuUp) gp.moveDebugMenuSelection(-1);
            if (menuDown) gp.moveDebugMenuSelection(+1);
            if (menuLeft) gp.adjustDebugMenuValue(-1);
            if (menuRight) gp.adjustDebugMenuValue(+1);
            return;
        }

        int state = gp.gameState;

        if (state == GamePanel.titleState) {
            if (gp.ui.titleScreenState == 0) {
                repeatMenu(gp.ui.titleMenu());
            }
            else if (gp.ui.titleScreenState == 1) {
                repeatMenu(gp.ui.classMenu());
            }
            else if (gp.ui.titleScreenState == 3) {
                int serverCount = gp.serverList.getServers().size();
                int totalItems = serverCount + 3;
                if (menuUp)   { gp.ui.mpServerSelection = (gp.ui.mpServerSelection - 1 + totalItems) % totalItems; gp.playSE(SFX.MENU_SELECT); }
                if (menuDown) { gp.ui.mpServerSelection = (gp.ui.mpServerSelection + 1) % totalItems; gp.playSE(SFX.MENU_SELECT); }
            }
        }
        else if (state == GamePanel.characterState) {
            if (menuUp    && gp.ui.slotRow > 0) { gp.ui.slotRow--; gp.playSE(SFX.MENU_CURSOR); }
            if (menuDown  && gp.ui.slotRow < 3) { gp.ui.slotRow++; gp.playSE(SFX.MENU_CURSOR); }
            if (menuLeft  && gp.ui.slotCol > 0) { gp.ui.slotCol--; gp.playSE(SFX.MENU_CURSOR); }
            if (menuRight && gp.ui.slotCol < 4) { gp.ui.slotCol++; gp.playSE(SFX.MENU_CURSOR); }
        }
        else if (state == GamePanel.levelUpState) {
            if (menuUp) {
                gp.player.levelUpChoice--;
                if (gp.player.levelUpChoice < 0) gp.player.levelUpChoice = 2;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (menuDown) {
                gp.player.levelUpChoice++;
                if (gp.player.levelUpChoice > 2) gp.player.levelUpChoice = 0;
                gp.playSE(SFX.MENU_SELECT);
            }
        }
        else if (state == GamePanel.skillTreeState) {
            if (menuUp)    { gp.player.skillTree.moveSelection(-1, 0); gp.playSE(SFX.MENU_CURSOR); }
            if (menuDown)  { gp.player.skillTree.moveSelection(+1, 0); gp.playSE(SFX.MENU_CURSOR); }
            if (menuLeft)  { gp.player.skillTree.moveSelection(0, -1); gp.playSE(SFX.MENU_CURSOR); }
            if (menuRight) { gp.player.skillTree.moveSelection(0, +1); gp.playSE(SFX.MENU_CURSOR); }
        }
        else if (state == GamePanel.optionsState) {
            if (gp.ui.subState == 0) {
                ui.Menu menu = gp.ui.optionsMenu();
                repeatMenu(menu);              // up/down, mirrors commandNum
                if (menuLeft)  menu.pressLeft();
                if (menuRight) menu.pressRight();
            } else {
                int maxCmd = switch (gp.ui.subState) { case 3 -> 2; default -> 0; };
                if (menuUp)   gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCmd + 1) % (maxCmd + 1);
                if (menuDown) gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCmd + 1);
            }
        }
        else if (state == GamePanel.journalState) {
            if (menuUp) {
                gp.ui.journalSelectedIndex--;
                if (gp.ui.journalSelectedIndex < 0) gp.ui.journalSelectedIndex = 0;
            }
            if (menuDown && gp.memoryJournal != null) {
                int max = gp.memoryJournal.getAllSorted().size() - 1;
                gp.ui.journalSelectedIndex++;
                if (gp.ui.journalSelectedIndex > max) gp.ui.journalSelectedIndex = max;
            }
        }
        else if (state == GamePanel.gameOverState) {
            repeatMenu(gp.ui.gameOverMenu());
        }
        else if (state == GamePanel.pauseState) {
            // Uses ui.pauseSelection, not commandNum (see handlePauseState) — can't reuse
            // repeatMenu() as-is, same up/down logic against the pause-specific field instead.
            ui.Menu menu = gp.ui.pauseMenu();
            menu.setSelected(gp.ui.pauseSelection);
            if (menuUp)   menu.moveUp();
            if (menuDown) menu.moveDown();
            gp.ui.pauseSelection = menu.getSelected();
        }
    }

    /**
     * Held-key (repeat) navigation for a declarative {@link ui.Menu}: seeds the Menu's selection
     * from commandNum, applies up/down, and mirrors the result back to commandNum so screens that
     * still read commandNum for their highlight stay in sync.
     */
    private void repeatMenu(ui.Menu menu) {
        menu.setSelected(gp.ui.commandNum);
        if (menuUp)   menu.moveUp();
        if (menuDown) menu.moveDown();
        gp.ui.commandNum = menu.getSelected();
    }

    // ── libGDX InputProcessor plumbing ──────────────────────────────────────
    private boolean ctrlDown() {
        return Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
            || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
    }
    @Override public boolean touchDown(int x, int y, int pointer, int button) { return false; }
    @Override public boolean touchUp(int x, int y, int pointer, int button) { return false; }
    @Override public boolean touchCancelled(int x, int y, int pointer, int button) { return false; }
    @Override public boolean touchDragged(int x, int y, int pointer) { return false; }
    @Override public boolean mouseMoved(int x, int y) { return false; }
    @Override public boolean scrolled(float amountX, float amountY) { return false; }
}

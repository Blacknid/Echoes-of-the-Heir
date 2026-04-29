package main;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import audio.SFX;
import entity.Entity;
import util.ResourceCache;

public class KeyHandler implements KeyListener {

    GamePanel gp;

    // Movement
    public boolean upPressed, downPressed, leftPressed, rightPressed, shotKeyPressed;

    // Actions
    public boolean enterPressed, dashPressed;

    // Ability keys
    public boolean shockwavePressed, voidSnarePressed, frostNovaPressed, overdrivePressed;

    // Debug
    public boolean showDebugText = false;

    // Abilities
    public int teleportCooldown = 0;
    public static final int TELEPORT_COOLDOWN_MAX = 90; // ~1.5 seconds

    private int projectileCooldown = 0;

    // Menu navigation key-repeat (bypasses slow OS repeat delay)
    private boolean menuUp, menuDown, menuLeft, menuRight;
    private int menuRepeatCounter;
    private static final int MENU_INITIAL_DELAY = 10; // frames before first repeat (~167ms)
    private static final int MENU_REPEAT_RATE = 4;    // frames between repeats (~67ms)

    public KeyHandler(GamePanel gp) {
        this.gp = gp;
    }

    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        // Track direction keys for menu repeat system
        boolean isDirKey = code == KeyEvent.VK_W || code == KeyEvent.VK_UP
                        || code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN
                        || code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT
                        || code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT;
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP)    menuUp    = true;
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN)  menuDown  = true;
        if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT)  menuLeft  = true;
        if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) menuRight = true;
        // Fire once immediately on first press in menu states, then arm repeat counter
        if (isDirKey && isInMenuState()) {
            fireMenuNavigation();
            menuRepeatCounter = MENU_INITIAL_DELAY;
            return;
        }

        // TITLE STATE
        if (gp.gameState == GamePanel.titleState) {
            handleTitleState(code);
        }
        // PLAY STATE
        else if (gp.gameState == GamePanel.playState) {
            handlePlayState(code);
        }
        // PAUSE STATE
        else if (gp.gameState == GamePanel.pauseState) {
            if (code == KeyEvent.VK_P) gp.gameState = GamePanel.playState;
        }
        // DIALOGUE / CUTSCENE STATE
        else if (gp.gameState == GamePanel.dialogueState || gp.gameState == GamePanel.cutsceneState) {
            // Allow skipping the awakening cutscene with Escape or Enter
            if (gp.gameState == GamePanel.cutsceneState
                    && gp.csManager.sceneNum == gp.csManager.awakening
                    && (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_ENTER)) {
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

    public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W, KeyEvent.VK_UP -> { upPressed = false; menuUp = false; }
            case KeyEvent.VK_S, KeyEvent.VK_DOWN -> { downPressed = false; menuDown = false; }
            case KeyEvent.VK_A, KeyEvent.VK_LEFT -> { leftPressed = false; menuLeft = false; }
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> { rightPressed = false; menuRight = false; }
            case KeyEvent.VK_F -> shotKeyPressed = false;
            case KeyEvent.VK_SPACE, KeyEvent.VK_SHIFT -> dashPressed = false;
            case KeyEvent.VK_Z -> shockwavePressed = false;
            case KeyEvent.VK_X -> voidSnarePressed = false;
            case KeyEvent.VK_C -> frostNovaPressed = false;
            case KeyEvent.VK_V -> overdrivePressed = false;
            case KeyEvent.VK_ENTER -> enterPressed = false;
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Text input for multiplayer server input screen
        if (gp.gameState == GamePanel.titleState && gp.ui.titleScreenState == 4) {
            char c = e.getKeyChar();
            int fieldCount = gp.ui.mpAddMode ? 3 : 2;
            if (gp.ui.mpInputField < fieldCount && c != KeyEvent.CHAR_UNDEFINED
                    && c != '\b' && c != '\n' && c != '\r' && c != '\t') {
                typeCharToField(c);
            }
        }
    }

    // ============================
    // STATE HANDLERS
    // ============================

    private void handleTitleState(int code) {
        if (gp.ui.titleScreenState == 0) {
            if (code == KeyEvent.VK_W) {
                gp.ui.commandNum = (gp.ui.commandNum - 1 + 4) % 4;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (code == KeyEvent.VK_S) {
                gp.ui.commandNum = (gp.ui.commandNum + 1) % 4;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (code == KeyEvent.VK_I) {
                gp.ui.titleScreenState = 2;
                gp.ui.commandNum = 0;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (code == KeyEvent.VK_ENTER) {
                if (gp.ui.commandNum == 0) { gp.ui.titleScreenState = 1; }
                if (gp.ui.commandNum == 1) { gp.saveLoad.load(); startGame(); }
                if (gp.ui.commandNum == 2) {
                    // MULTIPLAYER
                    gp.ui.titleScreenState = 3;
                    gp.ui.mpServerSelection = 0;
                    gp.ui.commandNum = 0;
                    gp.playSE(SFX.MENU_SELECT);
                }
                if (gp.ui.commandNum == 3) { System.exit(0); }
            }
        } else if (gp.ui.titleScreenState == 1) {
            int maxCommand = 3;
            if (code == KeyEvent.VK_W) {
                gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCommand + 1) % (maxCommand + 1);
                gp.playSE(SFX.MENU_SELECT);
            }
            if (code == KeyEvent.VK_S) {
                gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCommand + 1);
                gp.playSE(SFX.MENU_SELECT);
            }
            if (code == KeyEvent.VK_ENTER) {
                switch (gp.ui.commandNum) {
                    case 0 -> { gp.player.setPlayerStats(4, 2, 1, 4, 3); startNewGame(); } 
                    case 1 -> { gp.player.setPlayerStats(2, 1, 3, 5, 2); startNewGame(); }
                    case 2 -> { gp.player.setPlayerStats(3, 1, 2, 5, 5); startNewGame(); }
                    case 3 -> { gp.ui.titleScreenState = 0; gp.ui.commandNum = 0; gp.playSE(SFX.MENU_SELECT); }
                }
            }
        } else if (gp.ui.titleScreenState == 2 && code == KeyEvent.VK_ENTER) {
            if (gp.ui.commandNum == 0) {
                gp.ui.titleScreenState = 0;
                gp.ui.commandNum = 0;
                gp.playSE(SFX.MENU_SELECT);
            }
        }
        // ── MULTIPLAYER BROWSER (titleScreenState 3) ──
        else if (gp.ui.titleScreenState == 3) {
            handleMultiplayerBrowser(code);
        }
        // ── MULTIPLAYER INPUT (titleScreenState 4) ──
        else if (gp.ui.titleScreenState == 4) {
            handleMultiplayerInput(code);
        }
    }

    private void startGame() {
        gp.gameState = GamePanel.playState;
        // Apply music and weather from the TMX map's properties
        String path = gp.mapManager.mapRegistry.getOrDefault(gp.mapManager.currentMapId, "/res/maps/harta.tmx");
        gp.mapObjectLoader.loadMapProperties(path);
    }

    private void startNewGame() {
        // Enter cutscene state — the Awakening scene handles the rest
        gp.gameState = GamePanel.cutsceneState;
        gp.csManager.sceneNum = gp.csManager.awakening;
        gp.csManager.scenePhase = 0;
    }

    // ── MULTIPLAYER BROWSER ──
    private void handleMultiplayerBrowser(int code) {
        int serverCount = gp.serverList.getServers().size();
        int totalItems = serverCount + 3; // servers + Add Server + Direct Connect + Back

        if (code == KeyEvent.VK_W) {
            gp.ui.mpServerSelection = (gp.ui.mpServerSelection - 1 + totalItems) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == KeyEvent.VK_S) {
            gp.ui.mpServerSelection = (gp.ui.mpServerSelection + 1) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == KeyEvent.VK_ESCAPE) {
            gp.ui.titleScreenState = 0;
            gp.ui.commandNum = 2; // highlight Multiplayer on return
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == KeyEvent.VK_DELETE) {
            // Remove selected server
            if (gp.ui.mpServerSelection < serverCount) {
                gp.serverList.removeServer(gp.ui.mpServerSelection);
                if (gp.ui.mpServerSelection >= gp.serverList.getServers().size()) {
                    gp.ui.mpServerSelection = Math.max(0, gp.serverList.getServers().size() - 1);
                }
                gp.playSE(SFX.MENU_SELECT);
            }
        }
        if (code == KeyEvent.VK_ENTER) {
            if (gp.ui.mpServerSelection < serverCount) {
                // Connect to selected server
                String[] srv = gp.serverList.getServers().get(gp.ui.mpServerSelection);
                connectToServer(srv[1], srv[2]);
            } else {
                int menuIdx = gp.ui.mpServerSelection - serverCount;
                if (menuIdx == 0) {
                    // Add Server
                    gp.ui.titleScreenState = 4;
                    gp.ui.mpAddMode = true;
                    gp.ui.mpInputField = 0;
                    gp.ui.mpServerName = "";
                    gp.ui.mpServerIP = "";
                    gp.ui.mpServerPort = "7777";
                    gp.playSE(SFX.MENU_SELECT);
                } else if (menuIdx == 1) {
                    // Direct Connect
                    gp.ui.titleScreenState = 4;
                    gp.ui.mpAddMode = false;
                    gp.ui.mpInputField = 0; // start at IP field (field 0 in direct mode)
                    gp.ui.mpServerIP = "";
                    gp.ui.mpServerPort = "7777";
                    gp.playSE(SFX.MENU_SELECT);
                } else if (menuIdx == 2) {
                    // Back
                    gp.ui.titleScreenState = 0;
                    gp.ui.commandNum = 2;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
        }
    }

    // ── MULTIPLAYER INPUT SCREEN ──
    private void handleMultiplayerInput(int code) {
        // Fields: add mode = 0(name),1(ip),2(port); direct mode = 0(ip),1(port)
        int fieldCount = gp.ui.mpAddMode ? 3 : 2;
        int buttonCount = gp.ui.mpAddMode ? 3 : 2;
        int totalItems = fieldCount + buttonCount;
        boolean inTextField = gp.ui.mpInputField < fieldCount;

        if (code == KeyEvent.VK_TAB) {
            gp.ui.mpInputField = (gp.ui.mpInputField + 1) % totalItems;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == KeyEvent.VK_ESCAPE) {
            gp.ui.titleScreenState = 3;
            gp.playSE(SFX.MENU_SELECT);
        }
        // Arrow key navigation for buttons only; W/S are used for typing in fields
        if (!inTextField) {
            if (code == KeyEvent.VK_UP || code == KeyEvent.VK_W) {
                gp.ui.mpInputField--;
                if (gp.ui.mpInputField < fieldCount) gp.ui.mpInputField = totalItems - 1;
                gp.playSE(SFX.MENU_SELECT);
            }
            if (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_S) {
                gp.ui.mpInputField++;
                if (gp.ui.mpInputField >= totalItems) gp.ui.mpInputField = fieldCount;
                gp.playSE(SFX.MENU_SELECT);
            }
        } else {
            // Arrow keys navigate between text fields
            if (code == KeyEvent.VK_UP) {
                int newField = gp.ui.mpInputField - 1;
                // For direct connect mode, skip field 0 (name)
                if (!gp.ui.mpAddMode && newField == 0) newField = fieldCount - 1;
                if (newField >= 0 && newField < fieldCount) {
                    gp.ui.mpInputField = newField;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
            if (code == KeyEvent.VK_DOWN) {
                int newField = gp.ui.mpInputField + 1;
                if (newField < fieldCount) {
                    gp.ui.mpInputField = newField;
                    gp.playSE(SFX.MENU_SELECT);
                }
            }
        }

        // Backspace for text fields
        if (code == KeyEvent.VK_BACK_SPACE && inTextField) {
            deleteCharFromField();
        }

        // Enter on buttons or move past text fields
        if (code == KeyEvent.VK_ENTER) {
            if (inTextField) {
                // Move to next field or first button
                gp.ui.mpInputField++;
                if (gp.ui.mpInputField >= fieldCount) {
                    // Stay on first button
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
            // Buttons: Save & Connect, Save, Cancel
            if (btnIdx == 0) {
                // Save & Connect
                if (!gp.ui.mpServerIP.isEmpty()) {
                    String name = gp.ui.mpServerName.isEmpty() ? gp.ui.mpServerIP : gp.ui.mpServerName;
                    gp.serverList.addServer(name, gp.ui.mpServerIP, gp.ui.mpServerPort);
                    connectToServer(gp.ui.mpServerIP, gp.ui.mpServerPort);
                }
            } else if (btnIdx == 1) {
                // Save only
                if (!gp.ui.mpServerIP.isEmpty()) {
                    String name = gp.ui.mpServerName.isEmpty() ? gp.ui.mpServerIP : gp.ui.mpServerName;
                    gp.serverList.addServer(name, gp.ui.mpServerIP, gp.ui.mpServerPort);
                    gp.ui.titleScreenState = 3;
                    gp.playSE(SFX.MENU_SELECT);
                }
            } else {
                // Cancel
                gp.ui.titleScreenState = 3;
                gp.playSE(SFX.MENU_SELECT);
            }
        } else {
            // Buttons: Connect, Cancel
            if (btnIdx == 0) {
                // Connect
                if (!gp.ui.mpServerIP.isEmpty()) {
                    connectToServer(gp.ui.mpServerIP, gp.ui.mpServerPort);
                }
            } else {
                // Cancel
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
        // Start connection attempt
        gp.mpClient.connect(ip.trim(), port, "Player", "Fighter");
        // Start game in multiplayer mode
        gp.multiplayerMode = true;
        // We'll check connection status and transition to play state
        // Start a watcher thread that transitions to play state on connection success
        new Thread(() -> {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 6000) {
                if (gp.mpClient.isConnected()) {
                    gp.gameState = gp.playState;
                    gp.playMusic(SFX.MAIN_THEME);
                    return;
                }
                if (!gp.mpClient.isConnecting()) {
                    // Connection failed
                    gp.multiplayerMode = false;
                    return;
                }
                try { Thread.sleep(100); } catch (InterruptedException ex) { break; }
            }
            // Timeout
            if (!gp.mpClient.isConnected()) {
                gp.mpClient.connectionStatus = "Connection timed out.";
                gp.mpClient.disconnect();
                gp.multiplayerMode = false;
            }
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
        // World map overlay control (open/close)
        if (code == KeyEvent.VK_M && gp.minimap != null && !isOverlayOpen()) {
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
            if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_M) {
                gp.minimap.toggleWorldMap();
            }
            return;
        }

        // Movement
        if (code == KeyEvent.VK_W) {upPressed = true;}
        if (code == KeyEvent.VK_S) {downPressed = true;}
        if (code == KeyEvent.VK_A) {leftPressed = true;}
        if (code == KeyEvent.VK_D) {rightPressed = true;}

        // Game state changes
        if (code == KeyEvent.VK_P) { gp.gameState = GamePanel.pauseState; }
        if (code == KeyEvent.VK_ESCAPE) { gp.gameState = GamePanel.optionsState; }
        // Only allow opening inventory if no other overlay is open
        if (code == KeyEvent.VK_E && !isOverlayOpen()) { gp.gameState = GamePanel.characterState; }
        if (code == KeyEvent.VK_K && !isOverlayOpen()) { gp.gameState = GamePanel.skillTreeState; }
        if (code == KeyEvent.VK_J && !isOverlayOpen()) { gp.gameState = GamePanel.journalState; }
        if (code == KeyEvent.VK_ENTER) { enterPressed = true; }
        if (code == KeyEvent.VK_F) { shotKeyPressed = true; }

        // DEBUGS   

        // Dash
        if ( (code == KeyEvent.VK_SHIFT ) && ( leftPressed || rightPressed 
                        || upPressed || downPressed ) ) { dashPressed = true; }

        // Debug toggle
        if (code == KeyEvent.VK_T) { showDebugText = !showDebugText; }

        // [DEBUG] F5 = toggle persistent sepia overlay (MapShaderManager.sepiaMode)
        if (code == KeyEvent.VK_F5 && gp.mapShader != null) {
            gp.mapShader.sepiaMode = !gp.mapShader.sepiaMode;
        }

        // [DEBUG] F7 = collect all registered test fragments (visible in journal via J)
        if (code == KeyEvent.VK_F7 && gp.memoryJournal != null) {
            gp.memoryJournal.collect("frag_prologue");
            gp.memoryJournal.collect("frag_forest");
            gp.memoryJournal.collect("frag_lake");
            gp.gameState = GamePanel.journalState;
        }

        // [DEBUG] F8 = teleport to Awakening Cave
        if (code == KeyEvent.VK_F8) {
            gp.startTransition("awakening_cave", 20, 15);
        }

        // [DEBUG] F6 = trigger a test MemoryFlashback sequence
        if (code == KeyEvent.VK_F6 && gp.memoryFlashback != null) {
            data.MemoryJournal.MemoryFragment testFrag = new data.MemoryJournal.MemoryFragment(
                "test_sepia", "A Lost Moment",
                new String[]{"The rain fell on cobblestones...", "She never looked back.", "Neither did he."},
                0, "debug");
            gp.memoryFlashback.trigger(testFrag);
        }

        // [DEBUG] F9 = open/close debug map switcher
        if (code == KeyEvent.VK_F9) {
            gp.debugMapSwitcherOpen = !gp.debugMapSwitcherOpen;
            if (gp.debugMapSwitcherOpen) gp.refreshDebugMapList();
        }

        // Debug map switcher navigation (W/S/Enter/Escape consumed by switcher when open)
        if (gp.debugMapSwitcherOpen) {
            if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) {
                gp.debugMapSelectedIndex = Math.max(0, gp.debugMapSelectedIndex - 1);
            }
            if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) {
                gp.debugMapSelectedIndex = Math.min(gp.debugMapList.size() - 1, gp.debugMapSelectedIndex + 1);
            }
            if (code == KeyEvent.VK_ENTER && !gp.debugMapList.isEmpty()) {
                String targetId = gp.debugMapList.get(gp.debugMapSelectedIndex);
                gp.debugMapSwitcherOpen = false;
                gp.startTransition(targetId, -1, -1); // -1,-1 = use map default spawn
            }
            if (code == KeyEvent.VK_ESCAPE) {
                gp.debugMapSwitcherOpen = false;
            }
            return; // swallow all keys while switcher is openr
        }

        // Hitboxes toggle
        if (code == KeyEvent.VK_H) { gp.HitBoxes = !gp.HitBoxes; }

        // Reload map
        if (code == KeyEvent.VK_R && gp.mapManager != null) {
            String path = gp.mapManager.mapRegistry.getOrDefault(gp.mapManager.currentMapId, gp.mapManager.currentMapId);
            ResourceCache.invalidateXml(path);
            gp.tileM.loadMapFromTMX(path);
            gp.tileM.loadCollisionLayer(path);
            gp.mapObjectLoader.loadMapProperties(path);
            gp.eHandler.reset();
            gp.aSetter.loadEventsFromTMX();
            gp.cChecker.updateCollisionRectsCache();
            if (gp.minimap != null) {
                gp.minimap.invalidateTerrainCache(gp.mapManager.currentMapId);
                gp.minimap.bakeTerrainImage();
            }
        }

        // Path toggle
        if (code == KeyEvent.VK_Y) { gp.drawPath = !gp.drawPath; } 

        // Quest log toggle - close if open, only open if no other overlay is active
        if (code == KeyEvent.VK_Q && gp.questManager != null) {
            if (gp.questManager.isLogOpen()) {
                gp.questManager.toggleLog(); // always allow closing
            } else if (!isOverlayOpen()) {
                gp.questManager.toggleLog();
            }
        }

        // Quest log scroll
        if (gp.questManager != null && gp.questManager.isLogOpen()) {
            if (code == KeyEvent.VK_UP)   gp.questManager.scrollLog(-1);
            if (code == KeyEvent.VK_DOWN) gp.questManager.scrollLog(1);
        }



        // Abilities
        if ( code == KeyEvent.VK_SPACE && gp.teleportation ) { handleTeleport(); }
        if ( code == KeyEvent.VK_Z ) { shockwavePressed = true; }
        if ( code == KeyEvent.VK_X ) { voidSnarePressed = true; }
        if ( code == KeyEvent.VK_C ) { frostNovaPressed = true; }
        if ( code == KeyEvent.VK_V ) { overdrivePressed = true; }
    }

    /** Returns true if any overlay (quest log, minimap) is currently open */
    private boolean isOverlayOpen() {
        return (gp.questManager != null && gp.questManager.isLogOpen()) ||
               (gp.minimap != null && gp.minimap.isWorldMapOpen());
    }

    private void handleDialogueState(int code) {
        Entity npc = gp.ui.npc;
        // If choices are showing and typewriter is done, navigate choices with W/S
        if (npc != null && npc.dialogueChoices != null && npc.dialogueChoices.length > 0) {
            if (code == KeyEvent.VK_W) {
                npc.selectedChoice--;
                if (npc.selectedChoice < 0) npc.selectedChoice = npc.dialogueChoices.length - 1;
            }
            if (code == KeyEvent.VK_S) {
                npc.selectedChoice++;
                if (npc.selectedChoice >= npc.dialogueChoices.length) npc.selectedChoice = 0;
            }
        }
        if (code == KeyEvent.VK_ENTER) enterPressed = true;
    }

    private void handleJournalState(int code) {
        if (code == KeyEvent.VK_J || code == KeyEvent.VK_ESCAPE) {
            gp.gameState = GamePanel.playState;
            return;
        }
        if (code == KeyEvent.VK_W) {
            gp.ui.journalSelectedIndex--;
            if (gp.ui.journalSelectedIndex < 0) gp.ui.journalSelectedIndex = 0;
        }
        if (code == KeyEvent.VK_S) {
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
        if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_K) {
            gp.gameState = GamePanel.playState;
            gp.playSE(SFX.MENU_SELECT);
            return;
        }

        if (code == KeyEvent.VK_ENTER) {
            if (!gp.player.skillTree.unlockSelected(gp.player)) {
                gp.playSE(SFX.PLAYER_HIT);
            }
        }
    }

    private void handleCharacterState(int code) {
        if (code == KeyEvent.VK_E) gp.gameState = GamePanel.playState;

        if (code == KeyEvent.VK_W && gp.ui.slotRow > 0) { gp.ui.slotRow--; gp.playSE(SFX.MENU_CURSOR); }
        if (code == KeyEvent.VK_A && gp.ui.slotCol > 0) { gp.ui.slotCol--; gp.playSE(SFX.MENU_CURSOR); }
        if (code == KeyEvent.VK_S && gp.ui.slotRow < 3) { gp.ui.slotRow++; gp.playSE(SFX.MENU_CURSOR); }
        if (code == KeyEvent.VK_D && gp.ui.slotCol < 4) { gp.ui.slotCol++; gp.playSE(SFX.MENU_CURSOR); }
        if (code == KeyEvent.VK_ESCAPE) gp.gameState = GamePanel.playState;

        if (code == KeyEvent.VK_ENTER) gp.player.selectItem();

        // Drop item with BACKSPACE, but only if it's not currently equipped

        if (code == KeyEvent.VK_BACK_SPACE && ( gp.ui.getItemIndexOnSlot() != gp.player.getCurrentWeaponSlot() && 
        gp.ui.getItemIndexOnSlot() != gp.player.getCurrentShieldSlot() )) { gp.player.dropItem(); }
    }

    private void handleOptionsState(int code) {
        if (code == KeyEvent.VK_ESCAPE) gp.gameState = GamePanel.playState;
        if (code == KeyEvent.VK_ENTER) enterPressed = true;

        int maxCommandNum = switch (gp.ui.subState) {
            case 0 -> 7;
            case 3 -> 1;
            default -> 0;
        };

        if (code == KeyEvent.VK_W) gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCommandNum + 1) % (maxCommandNum + 1);
        if (code == KeyEvent.VK_S) gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCommandNum + 1);
        if (code == KeyEvent.VK_A) adjustOptionsVolume(-1);
        if (code == KeyEvent.VK_D) adjustOptionsVolume(1);
    }

    private void adjustOptionsVolume(int change) {
        if (gp.ui.subState != 0) return;

        if (gp.ui.commandNum == 2) {
            gp.audio.setMusicVolume(gp.audio.getMusicVolume() + change);
            gp.playSE(SFX.MENU_SELECT);
        }
        if (gp.ui.commandNum == 3) {
            gp.audio.setSEVolume(gp.audio.getSEVolume() + change);
            gp.playSE(SFX.MENU_SELECT);
        }
    }

    private void handleGameOverState(int code) {
        // Clear movement flags to prevent interference
        upPressed = false;
        downPressed = false;
        leftPressed = false;
        rightPressed = false;
        
        // Menu navigation: W/S or UP/DOWN arrows to toggle
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP ||
            code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) {
            gp.ui.commandNum = 1 - gp.ui.commandNum;
            gp.playSE(SFX.MENU_SELECT);
        }
        // Execute selected option with ENTER
        if (code == KeyEvent.VK_ENTER) {
            if (gp.ui.commandNum == 0) {
                // Retry: reset and continue playing
                gp.resetGame(false);
                gp.gameState = gp.playState;
                gp.playMusic(SFX.MAIN_THEME);
                gp.player.setDefaultPositions();
            }
            else if (gp.ui.commandNum == 1) {
                // Quit: go to title screen
                gp.ui.titleScreenState = 0;
                gp.ui.commandNum = 0;
                gp.stopMusic();
                gp.resetGame(true);
                gp.gameState = GamePanel.titleState;
            }
        }
    }

    private void handleLevelUpState(int code) {
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) {
            gp.player.levelUpChoice--;
            if (gp.player.levelUpChoice < 0) gp.player.levelUpChoice = 2;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) {
            gp.player.levelUpChoice++;
            if (gp.player.levelUpChoice > 2) gp.player.levelUpChoice = 0;
            gp.playSE(SFX.MENU_SELECT);
        }
        if (code == KeyEvent.VK_ENTER) {
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

        // Menu navigation key repeat
        if (isInMenuState() && (menuUp || menuDown || menuLeft || menuRight)) {
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
               s == GamePanel.journalState   || s == GamePanel.gameOverState;
    }

    private void fireMenuNavigation() {
        int state = gp.gameState;

        if (state == GamePanel.titleState) {
            if (gp.ui.titleScreenState == 0) {
                if (menuUp)   { gp.ui.commandNum = (gp.ui.commandNum - 1 + 4) % 4; gp.playSE(SFX.MENU_SELECT); }
                if (menuDown) { gp.ui.commandNum = (gp.ui.commandNum + 1) % 4; gp.playSE(SFX.MENU_SELECT); }
            }
            else if (gp.ui.titleScreenState == 1) {
                int maxCommand = 3;
                if (menuUp)   { gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCommand + 1) % (maxCommand + 1); gp.playSE(SFX.MENU_SELECT); }
                if (menuDown) { gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCommand + 1); gp.playSE(SFX.MENU_SELECT); }
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
            int maxCmd = switch (gp.ui.subState) { case 0 -> 7; case 3 -> 1; default -> 0; };
            if (menuUp)   gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCmd + 1) % (maxCmd + 1);
            if (menuDown) gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCmd + 1);
            if (menuLeft)  adjustOptionsVolume(-1);
            if (menuRight) adjustOptionsVolume(1);
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
            if (menuUp || menuDown) {
                gp.ui.commandNum = 1 - gp.ui.commandNum;
                gp.playSE(SFX.MENU_SELECT);
            }
        }
    }
}

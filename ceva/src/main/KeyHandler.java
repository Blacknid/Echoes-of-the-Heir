package main;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

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

    public KeyHandler(GamePanel gp) {
        this.gp = gp;
    }

    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        // TITLE STATE
        if (gp.gameState == gp.titleState) {
            handleTitleState(code);
        }
        // PLAY STATE
        else if (gp.gameState == gp.playState) {
            handlePlayState(code);
        }
        // PAUSE STATE
        else if (gp.gameState == gp.pauseState) {
            if (code == KeyEvent.VK_P) gp.gameState = gp.playState;
        }
        // DIALOGUE / CUTSCENE STATE
        else if (gp.gameState == gp.dialogueState || gp.gameState == gp.cutsceneState) {
            if (code == KeyEvent.VK_ENTER) enterPressed = true;
        }
        // CHARACTER STATE
        else if (gp.gameState == gp.characterState) {
            handleCharacterState(code);
        }
        // OPTIONS STATE
        else if (gp.gameState == gp.optionsState) {
            handleOptionsState(code);
        }
        // GAME OVER STATE
        else if (gp.gameState == gp.gameOverState) {
            handleGameOverState(code);
        }
        // LEVEL UP STATE
        else if (gp.gameState == gp.levelUpState) {
            handleLevelUpState(code);
        }
        // SKILL TREE STATE
        else if (gp.gameState == gp.skillTreeState) {
            handleSkillTreeState(code);
        }
    }

    public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W, KeyEvent.VK_UP -> upPressed = false;
            case KeyEvent.VK_S, KeyEvent.VK_DOWN -> downPressed = false;
            case KeyEvent.VK_A, KeyEvent.VK_LEFT -> leftPressed = false;
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> rightPressed = false;
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
        // Not used, but required by KeyListener interface
    }

    // ============================
    // STATE HANDLERS
    // ============================

    private void handleTitleState(int code) {
        if (gp.ui.titleScreenState == 0) {
            if (code == KeyEvent.VK_W) {
                gp.ui.commandNum = (gp.ui.commandNum - 1 + 3) % 3;
                gp.playSE(3);
            }
            if (code == KeyEvent.VK_S) {
                gp.ui.commandNum = (gp.ui.commandNum + 1) % 3;
                gp.playSE(3);
            }
            if (code == KeyEvent.VK_I) {
                gp.ui.titleScreenState = 2;
                gp.ui.commandNum = 0;
                gp.playSE(3);
            }
            if (code == KeyEvent.VK_ENTER) {
                if (gp.ui.commandNum == 0) { gp.ui.titleScreenState = 1; }
                if (gp.ui.commandNum == 1) { gp.saveLoad.load(); startGame(); }
                if (gp.ui.commandNum == 2) { System.exit(0); }
            }
        } else if (gp.ui.titleScreenState == 1) {
            int maxCommand = 3;
            if (code == KeyEvent.VK_W) {
                gp.ui.commandNum = (gp.ui.commandNum - 1 + maxCommand + 1) % (maxCommand + 1);
                gp.playSE(3);
            }
            if (code == KeyEvent.VK_S) {
                gp.ui.commandNum = (gp.ui.commandNum + 1) % (maxCommand + 1);
                gp.playSE(3);
            }
            if (code == KeyEvent.VK_ENTER) {
                switch (gp.ui.commandNum) {
                    case 0 -> { gp.player.setPlayerStats(4, 2, 1, 4, 3); startGame(); } 
                    case 1 -> { gp.player.setPlayerStats(2, 1, 3, 5, 2); startGame(); }
                    case 2 -> { gp.player.setPlayerStats(3, 1, 2, 5, 5); startGame(); }
                    case 3 -> { gp.ui.titleScreenState = 0; gp.ui.commandNum = 0; gp.playSE(3); }
                }
            }
        } else if (gp.ui.titleScreenState == 2 && code == KeyEvent.VK_ENTER) {
            if (gp.ui.commandNum == 0) {
                gp.ui.titleScreenState = 0;
                gp.ui.commandNum = 0;
                gp.playSE(3);
            }
        }
    }

    private void startGame() {
        gp.gameState = gp.playState;
        gp.playMusic(0);
    }

    private void handlePlayState(int code) {
        // Movement
        if (code == KeyEvent.VK_W) {upPressed = true;}
        if (code == KeyEvent.VK_S) {downPressed = true;}
        if (code == KeyEvent.VK_A) {leftPressed = true;}
        if (code == KeyEvent.VK_D) {rightPressed = true;}

        // Game state changes
        if (code == KeyEvent.VK_P) { gp.gameState = gp.pauseState; }
        if (code == KeyEvent.VK_ESCAPE) { gp.gameState = gp.optionsState; }
        // Only allow opening inventory if no other overlay is open
        if (code == KeyEvent.VK_E && !isOverlayOpen()) { gp.gameState = gp.characterState; }
        if (code == KeyEvent.VK_K && !isOverlayOpen()) { gp.gameState = gp.skillTreeState; }
        if (code == KeyEvent.VK_ENTER) { enterPressed = true; }
        if (code == KeyEvent.VK_F) { shotKeyPressed = true; }

        // DEBUGS   

        // Dash
        if ( (code == KeyEvent.VK_SHIFT ) && ( leftPressed || rightPressed 
                        || upPressed || downPressed ) ) { dashPressed = true; }

        // Debug toggle
        if (code == KeyEvent.VK_T) { showDebugText = !showDebugText; }

        // Hitboxes toggle
        if (code == KeyEvent.VK_H) { gp.HitBoxes = !gp.HitBoxes; }

        // Reload map
        if (code == KeyEvent.VK_R) { gp.tileM.loadMapFromTMX("/res/maps/harta.tmx"); }

        // Path toggle
        if (code == KeyEvent.VK_Y) { gp.drawPath = !gp.drawPath; } 

        // Minimap toggle
        if (code == KeyEvent.VK_M && gp.minimap != null && !isOverlayOpen()) { gp.minimap.toggle(); }

        // Quest log toggle - close if open, only open if no other overlay is active
        if (code == KeyEvent.VK_Q && gp.questManager != null) {
            if (gp.questManager.isLogOpen()) {
                gp.questManager.toggleLog(); // always allow closing
            } else if (!isOverlayOpen()) {
                gp.questManager.toggleLog();
            }
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
        return (gp.questManager != null && gp.questManager.isLogOpen());
    }

    private void handleTeleport() {
        if ( teleportCooldown == 0) {
            // Spawn departure particles at origin
            gp.player.spawnTeleportParticles(true);

            switch (gp.player.direction) {
                case "up" -> gp.player.worldY -= gp.tileSize * 3;
                case "down" -> gp.player.worldY += gp.tileSize * 3;
                case "left" -> gp.player.worldX -= gp.tileSize * 3;
                case "right" -> gp.player.worldX += gp.tileSize * 3;
            }

            // Spawn arrival particles at destination
            gp.player.spawnTeleportParticles(false);

            // Brief invincibility after teleport
            gp.player.invincible = true;
            gp.player.invincibleCounter = 20;

            gp.screenShake.shakeLight();
            gp.playSE(3);

            teleportCooldown = gp.player.getTeleportCooldownMax();
        }
    }

    private void handleSkillTreeState(int code) {
        if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_K) {
            gp.gameState = gp.playState;
            gp.playSE(3);
            return;
        }

        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) {
            gp.player.skillTree.moveSelection(gp.player, 0, -1);
            gp.playSE(7);
        }
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) {
            gp.player.skillTree.moveSelection(gp.player, 0, 1);
            gp.playSE(7);
        }
        if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT) {
            gp.player.skillTree.moveSelection(gp.player, -1, 0);
            gp.playSE(7);
        }
        if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) {
            gp.player.skillTree.moveSelection(gp.player, 1, 0);
            gp.playSE(7);
        }

        if (code == KeyEvent.VK_ENTER) {
            if (!gp.player.skillTree.unlockSelected(gp.player)) {
                gp.playSE(8);
            }
        }
    }

    private void handleCharacterState(int code) {
        if (code == KeyEvent.VK_E) gp.gameState = gp.playState;

        if (code == KeyEvent.VK_W && gp.ui.slotRow > 0) { gp.ui.slotRow--; gp.playSE(7); }
        if (code == KeyEvent.VK_A && gp.ui.slotCol > 0) { gp.ui.slotCol--; gp.playSE(7); }
        if (code == KeyEvent.VK_S && gp.ui.slotRow < 3) { gp.ui.slotRow++; gp.playSE(7); }
        if (code == KeyEvent.VK_D && gp.ui.slotCol < 4) { gp.ui.slotCol++; gp.playSE(7); }

        if (code == KeyEvent.VK_ENTER) gp.player.selectItem();

        // Drop item with BACKSPACE, but only if it's not currently equipped

        if (code == KeyEvent.VK_BACK_SPACE && ( gp.ui.getItemIndexOnSlot() != gp.player.getCurrentWeaponSlot() && 
        gp.ui.getItemIndexOnSlot() != gp.player.getCurrentShieldSlot() )) { gp.player.dropItem(); }
    }

    private void handleOptionsState(int code) {
        if (code == KeyEvent.VK_ESCAPE) gp.gameState = gp.playState;
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
            gp.music.volumeScale = Math.max(0, Math.min(5, gp.music.volumeScale + change));
            gp.music.checkVolume();
            gp.playSE(3);
        }
        if (gp.ui.commandNum == 3) {
            gp.se.volumeScale = Math.max(0, Math.min(5, gp.se.volumeScale + change));
            gp.playSE(3);
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
            gp.playSE(3);
        }
        // Execute selected option with ENTER
        if (code == KeyEvent.VK_ENTER) {
            if (gp.ui.commandNum == 0) {
                // Retry: reset and continue playing
                gp.resetGame(false);
                gp.gameState = gp.playState;
                gp.playMusic(0);
                gp.player.setDefaultPositions();
            }
            else if (gp.ui.commandNum == 1) {
                // Quit: go to title screen
                gp.ui.titleScreenState = 0;
                gp.ui.commandNum = 0;
                gp.stopMusic();
                gp.resetGame(true);
                gp.gameState = gp.titleState;
            }
        }
    }

    private void handleLevelUpState(int code) {
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) {
            gp.player.levelUpChoice--;
            if (gp.player.levelUpChoice < 0) gp.player.levelUpChoice = 2;
            gp.playSE(3);
        }
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) {
            gp.player.levelUpChoice++;
            if (gp.player.levelUpChoice > 2) gp.player.levelUpChoice = 0;
            gp.playSE(3);
        }
        if (code == KeyEvent.VK_ENTER) {
            gp.player.applyLevelUpChoice();
            gp.gameState = gp.playState;
        }
    }

    // ============================
    // UPDATE METHOD
    // ============================
    public void update() {
        // Reduce cooldowns every game tick
        if (projectileCooldown > 0) projectileCooldown--;
        if (teleportCooldown > 0) teleportCooldown--;
    }
}

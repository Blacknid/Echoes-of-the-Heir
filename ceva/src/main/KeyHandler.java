package main;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class KeyHandler implements KeyListener {

    GamePanel gp;

    // Movement
    public boolean upPressed, downPressed, leftPressed, rightPressed, shotKeyPressed;

    // Actions
    public boolean enterPressed, dashPressed;

    // Debug
    public boolean showDebugText = false;

    // Abilities
    private int teleportCooldown = 0;
    private final int TELEPORT_COOLDOWN_MAX = 10;

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
    }

    public void keyReleased(KeyEvent e) {
        int code = e.getKeyCode();

        if (code == KeyEvent.VK_W) upPressed = false;
        if (code == KeyEvent.VK_S) downPressed = false;
        if (code == KeyEvent.VK_A) leftPressed = false;
        if (code == KeyEvent.VK_D) rightPressed = false;
        if (code == KeyEvent.VK_UP) upPressed = false;
        if (code == KeyEvent.VK_DOWN) downPressed = false;
        if (code == KeyEvent.VK_LEFT) leftPressed = false;
        if (code == KeyEvent.VK_RIGHT) rightPressed = false;
        if (code == KeyEvent.VK_F) shotKeyPressed = false;
        if (code == KeyEvent.VK_SPACE) dashPressed = false;
        if (code == KeyEvent.VK_SHIFT) dashPressed = false;
        if (code == KeyEvent.VK_ENTER) enterPressed = false;
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
        if (code == KeyEvent.VK_E) { gp.gameState = gp.characterState; }
        if (code == KeyEvent.VK_ENTER) { enterPressed = true; }
        if (code == KeyEvent.VK_F) { shotKeyPressed = true; }

        // DEBUGS   

        // Dash
        if ( (code == KeyEvent.VK_SHIFT ) && ( leftPressed == true || rightPressed == true 
                        || upPressed == true || downPressed == true ) ) { dashPressed = true; }

        // Debug toggle
        if (code == KeyEvent.VK_T) { showDebugText = !showDebugText; }

        // Hitboxes toggle
        if (code == KeyEvent.VK_H) { gp.HitBoxes = !gp.HitBoxes; }

        // Reload map
        if (code == KeyEvent.VK_R) { gp.tileM.loadMapFromTMX("/res/maps/harta.tmx"); }

        // Path toggle
        if (code == KeyEvent.VK_Y) { PathFinderDubug(); } 



        // Abilities
        if ( code == KeyEvent.VK_SPACE && gp.teleportation == true ) { handleTeleport(); }
    }

    public void PathFinderDubug() {
        if (gp.drawPath == false) {
            gp.drawPath = true;
        } else {
            gp.drawPath = false;
        }
    }

    private void handleTeleport() {
        if ( teleportCooldown == 0) {
            switch (gp.player.direction) {
                case "up" -> gp.player.worldY -= gp.tileSize * 3;
                case "down" -> gp.player.worldY += gp.tileSize * 3;
                case "left" -> gp.player.worldX -= gp.tileSize * 3;
                case "right" -> gp.player.worldX += gp.tileSize * 3;
            }
            teleportCooldown = TELEPORT_COOLDOWN_MAX;
        }
        if (teleportCooldown > 0) teleportCooldown--;
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
        
        // Menu navigation: W/S or UP/DOWN arrows to move
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) {
            // Move up: 1 -> 0
            if (gp.ui.commandNum == 1) {
                gp.ui.commandNum = 0;
                gp.playSE(3);
            }
            else if (gp.ui.commandNum == 0) {
                gp.ui.commandNum = 1;
                gp.playSE(3);
            }
        }
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) {
            // Move down: 0 -> 1
            if (gp.ui.commandNum == 0) {
                gp.ui.commandNum = 1;
                gp.playSE(3);
            }
            else if (gp.ui.commandNum == 1) {
                gp.ui.commandNum = 0;
                gp.playSE(3);
            }
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

    // ============================
    // UPDATE METHOD
    // ============================
    public void update() {
        // Reduce cooldowns every game tick
        if (projectileCooldown > 0) projectileCooldown--;
        if (teleportCooldown > 0) teleportCooldown--;
    }
}

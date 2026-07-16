package ui;

import entity.Entity;
import main.GamePanel;

/**
 * Generic "camera pans from the player to a boss and back" intro, reusable for any boss: just call
 * start(boss) (e.g. from an EventHandler zone trigger) and it handles panning, holding on the boss,
 * panning back, and returning control, no per-boss code needed.
 *
 * Uses GamePanel's real camera-offset (gp.cameraLocked/cameraWorldX/Y, read via gp.getCamWorldX/Y())
 * instead of moving the player: the player stays exactly where they triggered the cutscene, fully
 * visible, while the camera itself pans away to frame the boss and back. See GamePanel.getCamWorldX/Y
 * for how every draw call site reads through this instead of the player's raw position.
 */
public class BossIntroCutscene {

    private final GamePanel gp;

    private static final int PAN_TICKS = 110;   // ~1.8s each way, slow, deliberate pan
    private static final int HOLD_TICKS = 120;  // ~2s spent looking at the boss

    private enum Phase { NONE, PAN_TO_BOSS, HOLD, PAN_TO_PLAYER, DONE }
    private Phase phase = Phase.NONE;
    private int phaseTimer;

    private int homeWorldX, homeWorldY;  // the player's real position, where the camera starts/ends
    private int fromX, fromY, toX, toY;  // current leg's pan endpoints
    private Entity boss;

    public BossIntroCutscene(GamePanel gp) {
        this.gp = gp;
    }

    public boolean isActive() {
        return phase != Phase.NONE;
    }

    /** The boss being introduced, while active, Boss.update() checks this to hold still and idle-face it. */
    public Entity getBoss() {
        return boss;
    }

    /** Begins the cutscene: locks the camera onto the player's current spot and starts panning toward `boss`. */
    public void start(Entity boss) {
        if (isActive() || boss == null) return;

        this.boss = boss;
        homeWorldX = gp.player.worldX;
        homeWorldY = gp.player.worldY;
        gp.gameState = GamePanel.cutsceneState;
        // UI.draw() shows a dialogue box any time gameState == cutsceneState AND ui.npc is non-null
        // ui.npc is never cleared after a normal conversation ends, so without this, the box (and
        // whatever stale NPC/line it last held) would reappear during this cutscene even though no
        // dialogue is happening.
        gp.ui.npc = null;
        // The dialogue-camera zoom/pan (RenderPipeline wraps the ENTIRE world pass, player included,
        // in this transform whenever it's off-neutral) also never resets itself, if a conversation
        // happened recently and hadn't fully eased back to zoom=1/pan=0 yet, that leftover transform
        // would still be applied here and drag the player along with it. Snap it to neutral so only
        // the intentional camera pan (below) affects the view.
        gp.dlgZoom = 1f;
        gp.dlgZoomTarget = 1f;
        gp.dlgPanX = 0f;
        gp.dlgPanTargetX = 0f;
        gp.dlgPanY = 0f;
        gp.dlgPanTargetY = 0f;
        gp.dlgBarsTarget = 1f;
        gp.cameraLocked = true;

        beginLeg(homeWorldX, homeWorldY, boss.getCenterX(), boss.getCenterY());
        phase = Phase.PAN_TO_BOSS;
    }

    private void beginLeg(int fromX, int fromY, int toX, int toY) {
        this.fromX = fromX;
        this.fromY = fromY;
        this.toX = toX;
        this.toY = toY;
        phaseTimer = 0;
    }

    public void update() {
        switch (phase) {
            case PAN_TO_BOSS -> {
                if (tickPan()) {
                    beginLeg(gp.cameraWorldX, gp.cameraWorldY, gp.cameraWorldX, gp.cameraWorldY);
                    phase = Phase.HOLD;
                }
            }
            case HOLD -> {
                phaseTimer++;
                if (phaseTimer >= HOLD_TICKS) {
                    beginLeg(gp.cameraWorldX, gp.cameraWorldY, homeWorldX, homeWorldY);
                    phase = Phase.PAN_TO_PLAYER;
                }
            }
            case PAN_TO_PLAYER -> {
                if (tickPan()) finish();
            }
            default -> { }
        }
    }

    /** Eases the locked camera position from (fromX,fromY) to (toX,toY). Returns true once arrived. */
    private boolean tickPan() {
        phaseTimer++;
        float t = Math.min(1f, phaseTimer / (float) PAN_TICKS);
        float eased = t * t * (3f - 2f * t); // smoothstep: eases in and out, no jarring start/stop
        gp.cameraWorldX = fromX + Math.round((toX - fromX) * eased);
        gp.cameraWorldY = fromY + Math.round((toY - fromY) * eased);
        return t >= 1f;
    }

    private void finish() {
        gp.cameraLocked = false;
        gp.dlgBarsTarget = 0f;
        gp.gameState = GamePanel.playState;
        phase = Phase.NONE;
        if (boss instanceof entity.Boss b) b.markIntroSeen();
        boss = null;
    }
}

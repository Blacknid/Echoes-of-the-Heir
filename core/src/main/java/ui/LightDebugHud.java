package ui;

import gfx.Color;
import gfx.Font;
import gfx.GdxRenderer;
import gfx.shader.LightDebug;
import main.GamePanel;

/**
 * On-screen diagnostic overlay for the "lights dance when I move" hunt. Drawn last, over the HUD.
 * White lines = live values; RED lines = a suspicious condition fired this frame (latched ~1.5s so
 * one-frame events are catchable by eye). See {@link LightDebug} for the F-key kill-switches.
 *
 * <p>Every condition below is a mechanism that has caused (or could cause) the light pool to shift
 * relative to the world:
 * <ul>
 *   <li><b>MASK-TRANSFORM</b> — the full-screen mask was blitted while the batch carried a non-zero
 *       translate (camera shake / dialogue pan). The blit neutralizes it; if the value is non-zero
 *       AND dancing coincides, the neutralize is being bypassed somewhere.</li>
 *   <li><b>LIGHT≠PLAYER</b> — the shader light's screen position differs from where the player
 *       sprite is drawn: the pool is genuinely elsewhere (coordinate/frame-lag bug).</li>
 *   <li><b>DLG-CAM</b> — the dialogue camera zoom/pan is not fully neutral during normal play
 *       (float residue keeps the world scaled ~1.0001 while the mask isn't).</li>
 *   <li><b>SHAKE</b> — camera shake active (world translated; mask must not be).</li>
 *   <li><b>CLAMP</b> — camera at a map edge (player not screen-centred; a different code path).</li>
 * </ul>
 */
public final class LightDebugHud {

    private LightDebugHud() {}

    private static final Font  FONT   = new Font("Arial", Font.BOLD, 12);
    private static final Color BG     = new Color(0, 0, 0, 150);
    private static final Color INFO   = new Color(220, 230, 240);
    private static final Color OK     = new Color(120, 230, 120);
    private static final Color WARNC  = new Color(255, 90, 70);
    private static final Color TOGGLE = new Color(255, 215, 120);

    private static int lastWorldX = Integer.MIN_VALUE;
    private static int lastScreenX;

    public static void draw(GdxRenderer g2, GamePanel gp) {
        if (!LightDebug.hud) { LightDebug.tickWarnings(); return; }

        // ── Gather + evaluate suspects ──
        int wx = gp.player.worldX,  wy = gp.player.worldY;
        int sx = gp.player.screenX, sy = gp.player.screenY;
        int dWorldX = 0, dOffX = 0;
        if (lastWorldX != Integer.MIN_VALUE) {
            dWorldX = wx - lastWorldX;
            int dScreenX = sx - lastScreenX;
            dOffX = dScreenX - dWorldX; // how much the world-draw offset changed this frame
        }
        lastWorldX = wx; lastScreenX = sx;

        // Light 0 (player light) must sit exactly on the player's draw position + half tile.
        int expLX = sx + gp.tileSize / 2, expLY = sy + gp.tileSize / 2;
        boolean lightOnPlayer = LightDebug.lightCount == 0
                || (Math.abs(LightDebug.light0X - expLX) < 0.5f && Math.abs(LightDebug.light0Y - expLY) < 0.5f);
        if (!lightOnPlayer) {
            LightDebug.warn("LIGHT!=PLAYER", "light0=(" + (int) LightDebug.light0X + "," + (int) LightDebug.light0Y
                    + ") player+half=(" + expLX + "," + expLY + ")");
        }
        if (LightDebug.maskBlitTx != 0f || LightDebug.maskBlitTy != 0f) {
            LightDebug.warn("MASK-TRANSFORM", "world translate at mask blit = ("
                    + LightDebug.maskBlitTx + "," + LightDebug.maskBlitTy + ") — shake/pan active");
        }
        boolean dlgResidue = gp.dlgZoom != 1f || gp.dlgPanX != 0f || gp.dlgPanY != 0f;
        if (dlgResidue) {
            LightDebug.warn("DLG-CAM", String.format("zoom=%.5f pan=(%.3f,%.3f) — world scaled, mask NOT",
                    gp.dlgZoom, gp.dlgPanX, gp.dlgPanY));
        }
        int shX = gp.screenShake.getOffsetX(), shY = gp.screenShake.getOffsetY();
        if (shX != 0 || shY != 0) LightDebug.warn("SHAKE", "offset=(" + shX + "," + shY + ")");
        int centerX = gp.screenWidth / 2 - gp.tileSize / 2;
        boolean clamped = Math.abs(sx - centerX) > 2 && dWorldX != 0;

        // ── Draw ──
        int x = 10, y = 130, lh = 15;
        java.util.List<String> warns = LightDebug.tickWarnings();
        int lines = 8 + warns.size();
        g2.setColor(BG);
        g2.fillRect(x - 6, y - 14, 470, lines * lh + 22);
        g2.setFont(FONT);

        g2.setColor(TOGGLE);
        g2.drawString("[F5]hud  [F6]freezeAnim:" + on(LightDebug.freezeTime)
                + "  [F7]noDetail:" + on(LightDebug.noDetail) + "  [F8]noPost:" + on(LightDebug.noBloom)
                + "  [F9]noShadow:" + on(LightDebug.noShadows) + "  [F10]noRim:" + on(LightDebug.noRim), x, y);
        y += lh;

        g2.setColor(INFO);
        g2.drawString("tier=" + LightDebug.tier + "  lights=" + LightDebug.lightCount
                + "  premult=" + LightDebug.maskPremultiplied
                + "  maskInCapture=" + LightDebug.maskBlitDuringCapture, x, y); y += lh;
        g2.drawString("player world=(" + wx + "," + wy + ")  screen=(" + sx + "," + sy + ")  dWorldX="
                + dWorldX + "  dOffsetX=" + dOffX, x, y); y += lh;
        g2.drawString("light0=(" + (int) LightDebug.light0X + "," + (int) LightDebug.light0Y
                + ")  expected=(" + expLX + "," + expLY + ")", x, y); y += lh;
        g2.setColor(lightOnPlayer ? OK : WARNC);
        g2.drawString(lightOnPlayer ? "light locked to player: YES" : "LIGHT NOT ON PLAYER", x, y); y += lh;
        g2.setColor(INFO);
        g2.drawString("dlgZoom=" + gp.dlgZoom + "  dlgPan=(" + gp.dlgPanX + "," + gp.dlgPanY
                + ")  shake=(" + shX + "," + shY + ")", x, y); y += lh;
        g2.drawString("pixelScale=" + gp.pixelScale + "  logical=" + gp.screenWidth + "x" + gp.screenHeight
                + "  device=" + gp.deviceWidth + "x" + gp.deviceHeight
                + (gp.pixelScale != 1 ? "  (SCALED)" : ""), x, y); y += lh;
        g2.drawString("camClamped=" + clamped + "  FPS=" + gp.currentFPS, x, y); y += lh;

        g2.setColor(WARNC);
        for (String w : warns) { g2.drawString("! " + w, x, y); y += lh; }
    }

    private static String on(boolean b) { return b ? "ON" : "off"; }
}

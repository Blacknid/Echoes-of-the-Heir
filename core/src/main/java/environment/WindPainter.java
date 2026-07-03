package environment;

import com.badlogic.gdx.Input;

import gfx.Color;
import gfx.Font;
import gfx.GdxRenderer;
import gfx.Stroke;

import main.GamePanel;

/**
 * In-game wind-map editor.
 *
 * <p>Authoring workflow (this is the "paint over the live map" tool you asked for — no separate
 * app, the actual map renders behind your strokes):
 * <ol>
 *   <li>Enter the game (play state) and enable debug mode (<b>Ctrl+D</b>).</li>
 *   <li>Toggle the wind painter with <b>Ctrl+W</b>. The map dims slightly and a wind overlay appears:
 *       coloured tiles = strength, little arrows = direction.</li>
 *   <li><b>Left-drag</b> to paint wind under a soft round brush. The wind direction is the direction
 *       you drag the mouse (a quick flick sets the heading), so you literally draw the airflow.</li>
 *   <li><b>Right-drag</b> to erase.</li>
 *   <li><b>Mouse wheel</b> changes the brush radius. <b>[ ]</b> change brush strength.</li>
 *   <li><b>R</b> toggles "rain channel": strokes then paint the per-tile extra-wind-while-raining
 *       value instead of the base strength, so you decide which tiles get windier in rain.</li>
 *   <li><b>F</b> sets a fixed heading from the arrow keys instead of using drag direction
 *       (useful for a uniform prevailing wind). Press <b>F</b> again to return to drag-direction mode.</li>
 *   <li><b>Ctrl+S</b> saves to <code>core/assets/res/maps/&lt;Map&gt;.windmap</code>.</li>
 *   <li><b>Delete</b> clears the whole map's wind.</li>
 * </ol>
 *
 * <p>The painter writes into {@link WindField}; the same field is what the player physics samples,
 * so you feel your edits immediately (try it while walking into/away from your strokes).
 */
public class WindPainter {

    private final GamePanel gp;

    private boolean active = false;

    // Wind strength is quantised into discrete "levels" for the right-click eraser.
    // MAX_WIND_LEVEL levels span the full 0..1 strength range, so one level = 1/MAX_WIND_LEVEL.
    private static final int   MAX_WIND_LEVEL = 10;
    private static final float LEVEL_STEP     = 1f / MAX_WIND_LEVEL; // strength per level

    // Brush settings
    private float brushRadiusTiles = 3.0f;
    private float brushStrength    = 0.15f; // added per painted frame at brush centre (left-click)
    // Right-click lowers the wind by this many levels per pass (heavier/softer via [ and ]).
    // Capped to MAX_WIND_LEVEL so a single pass can never subtract more than the maximum wind.
    private int   rightClickLevels = 1;
    private boolean paintRainChannel = false;
    private boolean fixedHeading      = false;   // true = use headingRadians; false = use drag direction
    private float headingRadians      = 0f;       // used when fixedHeading

    // Drag tracking (world coords) for deriving stroke direction
    private boolean wasLeftDown = false;
    private boolean wasRightDown = false; // edge-detect right-click so it fires once per press
    private int lastWorldX, lastWorldY;
    private float lastStrokeAngle = 0f;

    // Save feedback
    private int saveFlashTimer = 0;

    public WindPainter(GamePanel gp) {
        this.gp = gp;
    }

    public boolean isActive() { return active; }

    public void toggle() {
        active = !active;
        if (active) {
            gp.ui.addMessage("Wind Painter ON — L-drag paint, R-drag lower level, wheel size, Ctrl+S save",
                new Color(140, 210, 255), 240);
        } else {
            gp.ui.addMessage("Wind Painter OFF", new Color(140, 210, 255), 120);
        }
    }

    /** Adjust brush radius from the mouse wheel (called by MouseHandler). */
    public void scrollRadius(int dir) {
        brushRadiusTiles = clamp(brushRadiusTiles + dir * 0.5f, 0.5f, 20f);
    }

    /**
     * Handle a painter key. Returns true if the key was consumed by the painter.
     * Called from KeyHandler before normal play-state handling.
     */
    public boolean handleKey(int code) {
        if (!active) return false;
        switch (code) {
            case Input.Keys.LEFT_BRACKET  -> {
                rightClickLevels = clampInt(rightClickLevels - 1, 1, MAX_WIND_LEVEL);
                gp.ui.addMessage("Right-click: -" + rightClickLevels + " level" + (rightClickLevels > 1 ? "s" : ""),
                    new Color(255, 180, 120), 120);
                return true;
            }
            case Input.Keys.RIGHT_BRACKET -> {
                rightClickLevels = clampInt(rightClickLevels + 1, 1, MAX_WIND_LEVEL);
                gp.ui.addMessage("Right-click: -" + rightClickLevels + " level" + (rightClickLevels > 1 ? "s" : ""),
                    new Color(255, 180, 120), 120);
                return true;
            }
            case Input.Keys.R -> {
                paintRainChannel = !paintRainChannel;
                gp.ui.addMessage(paintRainChannel ? "Painting RAIN-wind channel" : "Painting BASE-wind channel",
                    new Color(140, 210, 255), 150);
                return true;
            }
            case Input.Keys.F -> {
                fixedHeading = !fixedHeading;
                gp.ui.addMessage(fixedHeading ? "Fixed heading (arrows set direction)" : "Drag direction sets wind",
                    new Color(140, 210, 255), 150);
                return true;
            }
            case Input.Keys.UP    -> { if (fixedHeading) { headingRadians = (float)(-Math.PI / 2); return true; } }
            case Input.Keys.DOWN  -> { if (fixedHeading) { headingRadians = (float)( Math.PI / 2); return true; } }
            case Input.Keys.LEFT  -> { if (fixedHeading) { headingRadians = (float) Math.PI;       return true; } }
            case Input.Keys.RIGHT -> { if (fixedHeading) { headingRadians = 0f;                    return true; } }
            case Input.Keys.FORWARD_DEL -> {
                gp.windField.clearAll();
                gp.ui.addMessage("Wind cleared for this map", new Color(255, 180, 120), 150);
                return true;
            }
        }
        return false;
    }

    /** Ctrl+S while painter is active → save. Returns true if handled. */
    public boolean handleSave() {
        if (!active) return false;
        boolean ok = gp.windField.save();
        saveFlashTimer = 60;
        gp.ui.addMessage(ok ? "Wind map saved" : "Wind map save FAILED (see console)",
            ok ? new Color(140, 255, 160) : new Color(255, 120, 120), 180);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Per-frame painting (driven by mouse state)
    // ──────────────────────────────────────────────────────────────────────────

    public void update() {
        if (saveFlashTimer > 0) saveFlashTimer--;
        if (!active) { wasLeftDown = false; wasRightDown = false; return; }
        if (gp.gameState != GamePanel.playState) return;

        // Screen → world: world = gameCursor + (playerWorld - playerScreen)
        int worldX = gp.mouseH.gameX + (gp.player.worldX - gp.player.screenX);
        int worldY = gp.mouseH.gameY + (gp.player.worldY - gp.player.screenY);
        int col = worldX / gp.tileSize;
        int row = worldY / gp.tileSize;

        boolean left  = gp.mouseH.leftPressed;
        boolean right = gp.mouseH.rightPressed;

        if (left) {
            // Derive stroke heading from drag motion, unless a fixed heading is set.
            float dir;
            if (fixedHeading) {
                dir = headingRadians;
            } else if (wasLeftDown) {
                int ddx = worldX - lastWorldX;
                int ddy = worldY - lastWorldY;
                if (ddx * ddx + ddy * ddy >= 4) {        // moved at least ~2px → update heading
                    lastStrokeAngle = (float) Math.atan2(ddy, ddx);
                }
                dir = lastStrokeAngle;
            } else {
                dir = lastStrokeAngle; // first frame of a stroke: reuse last heading
            }
            gp.windField.paint(col, row, brushRadiusTiles, brushStrength, dir, paintRainChannel);
        } else if (right && !wasRightDown) {
            // Right-click LOWERS the wind by rightClickLevels discrete levels (direction ignored).
            // Fires ONCE per press (rising edge) — holding the button does not keep decreasing.
            // The decrease magnitude is capped at the maximum wind level (1.0) so a single press
            // can never subtract more than the full range.
            float decrease = Math.min(1f, rightClickLevels * LEVEL_STEP);
            gp.windField.paint(col, row, brushRadiusTiles, -decrease, 0f, paintRainChannel);
        }

        wasLeftDown = left;
        wasRightDown = right;
        lastWorldX = worldX;
        lastWorldY = worldY;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Overlay rendering
    // ──────────────────────────────────────────────────────────────────────────

    public void draw(GdxRenderer g2) {
        if (!active || gp.windField == null) return;

        int pwx = gp.player.worldX, pwy = gp.player.worldY;
        int psx = gp.player.screenX, psy = gp.player.screenY;
        int ts  = gp.tileSize;

        float savedTX = g2.getTranslateX(), savedTY = g2.getTranslateY();
        Stroke oldStroke = g2.getStroke();
        
        g2.translate(-pwx + psx, -pwy + psy);

        // Only draw cells around the visible screen for performance.
        int startCol = Math.max(0, (pwx - gp.screenWidth) / ts);
        int endCol   = Math.min(gp.windField.getCols() - 1, (pwx + gp.screenWidth) / ts);
        int startRow = Math.max(0, (pwy - gp.screenHeight) / ts);
        int endRow   = Math.min(gp.windField.getRows() - 1, (pwy + gp.screenHeight) / ts);

        g2.setStroke(new Stroke(2f));
        for (int row = startRow; row <= endRow; row++) {
            for (int col = startCol; col <= endCol; col++) {
                float base = gp.windField.rawStrengthAt(col, row);
                float rain = gp.windField.rawRainBonusAt(col, row);
                float shown = paintRainChannel ? rain : base;
                if (shown <= 0.001f && rain <= 0.001f) continue;

                int tx = col * ts, ty = row * ts;
                // Tile fill: hue = strength of the channel we're editing.
                float s = clamp(shown, 0f, 1f);
                g2.setColor(new Color(0.1f + 0.9f * s, 0.55f - 0.35f * s, 1f - 0.7f * s, 0.30f));
                g2.fillRect(tx, ty, ts, ts);

                // Direction arrow (only where there is base wind).
                if (base > 0.02f) {
                    float ang = gp.windField.rawAngleAt(col, row);
                    int cx = tx + ts / 2, cy = ty + ts / 2;
                    int len = (int) (ts * 0.40f * (0.4f + base));
                    int ex = cx + (int) (Math.cos(ang) * len);
                    int ey = cy + (int) (Math.sin(ang) * len);
                    g2.setColor(new Color(255, 255, 255, 200));
                    g2.drawLine(cx, cy, ex, ey);
                    // arrowhead
                    double a1 = ang + Math.PI * 0.85, a2 = ang - Math.PI * 0.85;
                    int hl = Math.max(3, len / 2);
                    g2.drawLine(ex, ey, ex + (int)(Math.cos(a1) * hl), ey + (int)(Math.sin(a1) * hl));
                    g2.drawLine(ex, ey, ex + (int)(Math.cos(a2) * hl), ey + (int)(Math.sin(a2) * hl));
                }
            }
        }

        // Brush cursor (world space).
        int worldX = gp.mouseH.gameX + (pwx - psx);
        int worldY = gp.mouseH.gameY + (pwy - psy);
        int br = (int) (brushRadiusTiles * ts);
        g2.setColor(paintRainChannel ? new Color(120, 180, 255, 230) : new Color(255, 230, 120, 230));
        g2.setStroke(new Stroke(2f));
        g2.drawOval(worldX - br, worldY - br, br * 2, br * 2);

        g2.setTranslate(savedTX, savedTY);
        g2.setStroke(oldStroke);
        g2.setAlpha(1f);

        // HUD readout (screen space).
        drawHud(g2);
    }

    private void drawHud(GdxRenderer g2) {
        int x = 16, y = gp.screenHeight - 132;
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect(x - 8, y - 18, 360, 124, 12, 12);
        g2.setColor(new Color(160, 220, 255));
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
        g2.drawString("WIND PAINTER  —  map: " + gp.windField.getCurrentMapId(), x, y);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        y += 18;
        g2.drawString(String.format("Channel: %s   Brush: r=%.1f  paint=%.2f   R-click: -%d/%d lvl",
            paintRainChannel ? "RAIN-extra" : "BASE", brushRadiusTiles, brushStrength,
            rightClickLevels, MAX_WIND_LEVEL), x, y);
        y += 16;
        g2.drawString(fixedHeading
            ? String.format("Heading: FIXED %d° (arrows)", (int) Math.toDegrees(headingRadians))
            : "Heading: from drag direction", x, y);
        y += 16;
        g2.drawString(String.format("Global gust: %.0f%%  (auto-varies, dips to 70%% on turns)",
            gp.windField.getGustStrength() * 100f), x, y);
        y += 16;
        g2.setColor(new Color(180, 180, 180));
        g2.drawString("L-paint  R:-1 level  wheel:size  [ ]:R-click levels  R:rain  F:fixedDir  Ctrl+S:save  Del:clear", x, y);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

package environment;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import data.MemoryJournal.MemoryFragment;
import main.GamePanel;

/**
 * Handles the visual flashback effect when a Memory Fragment is claimed.
 * State machine: INACTIVE → FADE_TO_WHITE → SEPIA_TEXT → FADE_BACK → DONE
 *
 * Called from GamePanel's draw loop as a top-layer overlay.
 */
public class MemoryFlashback {

    // ── States ──
    public static final int INACTIVE     = 0;
    public static final int FADE_TO_WHITE = 1;
    public static final int SEPIA_TEXT    = 2;
    public static final int FADE_BACK    = 3;
    public static final int DONE         = 4;

    private int state = INACTIVE;
    private GamePanel gp;

    // Timing (in game ticks @ 60 UPS)
    private float alpha = 0f;
    private int timer = 0;
    private int charIndex = 0;       // typewriter position
    private int textTimer = 0;       // ticks since text phase started

    // Content
    private MemoryFragment fragment;
    private String displayTitle = "";
    private String[] displayLines;

    // Visual constants
    private static final Color SEPIA_OVERLAY = new Color(112, 66, 20, 100);
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color TEXT_COLOR = new Color(240, 230, 210);
    private static final Color TEXT_SHADOW = new Color(30, 20, 10);
    private static final Color TITLE_COLOR = new Color(255, 215, 100);
    private static final Font TITLE_FONT = new Font("Serif", Font.BOLD, 28);
    private static final Font TEXT_FONT = new Font("Serif", Font.PLAIN, 20);

    private static final int FADE_IN_TICKS = 40;    // ~0.67s white fade in
    private static final int TEXT_HOLD_TICKS = 180;  // ~3s text display (typewriter + hold)
    private static final int FADE_OUT_TICKS = 40;    // ~0.67s fade back
    private static final int TYPEWRITER_SPEED = 2;   // characters per tick

    public MemoryFlashback(GamePanel gp) {
        this.gp = gp;
    }

    /** Start the flashback sequence for a collected fragment. */
    public void trigger(MemoryFragment fragment) {
        if (fragment == null) return;
        this.fragment = fragment;
        this.displayTitle = fragment.name != null ? fragment.name : "";
        this.displayLines = fragment.text != null ? fragment.text : new String[]{"..."};
        this.alpha = 0f;
        this.timer = 0;
        this.charIndex = 0;
        this.textTimer = 0;
        this.state = FADE_TO_WHITE;
    }

    /** Whether the flashback is currently playing (blocks normal game updates). */
    public boolean isActive() {
        return state != INACTIVE && state != DONE;
    }

    /** Call once per game tick (60 UPS). */
    public void update() {
        if (state == INACTIVE || state == DONE) return;

        timer++;

        switch (state) {
            case FADE_TO_WHITE:
                alpha = Math.min(1f, (float) timer / FADE_IN_TICKS);
                if (timer >= FADE_IN_TICKS) {
                    state = SEPIA_TEXT;
                    timer = 0;
                    alpha = 1f;
                }
                break;

            case SEPIA_TEXT:
                textTimer++;
                // Advance typewriter
                int totalChars = getTotalTextChars();
                charIndex = Math.min(totalChars, textTimer * TYPEWRITER_SPEED);
                if (timer >= TEXT_HOLD_TICKS) {
                    state = FADE_BACK;
                    timer = 0;
                }
                break;

            case FADE_BACK:
                alpha = 1f - Math.min(1f, (float) timer / FADE_OUT_TICKS);
                if (timer >= FADE_OUT_TICKS) {
                    state = DONE;
                    alpha = 0f;
                }
                break;
        }
    }

    /** Draw the flashback overlay. Call after all other rendering. */
    public void draw(Graphics2D g2) {
        if (state == INACTIVE || state == DONE) return;

        int w = gp.screenWidth;
        int h = gp.screenHeight;

        switch (state) {
            case FADE_TO_WHITE:
                // White wash over everything
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(WHITE);
                g2.fillRect(0, 0, w, h);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                break;

            case SEPIA_TEXT:
                // Full white background + sepia tint
                g2.setColor(WHITE);
                g2.fillRect(0, 0, w, h);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
                g2.setColor(SEPIA_OVERLAY);
                g2.fillRect(0, 0, w, h);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

                // Draw title
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(TITLE_FONT);
                FontMetrics fmTitle = g2.getFontMetrics();
                int titleW = fmTitle.stringWidth(displayTitle);
                int titleX = (w - titleW) / 2;
                int titleY = h / 3;
                // Shadow
                g2.setColor(TEXT_SHADOW);
                g2.drawString(displayTitle, titleX + 2, titleY + 2);
                // Main
                g2.setColor(TITLE_COLOR);
                g2.drawString(displayTitle, titleX, titleY);

                // Draw text lines with typewriter effect
                g2.setFont(TEXT_FONT);
                FontMetrics fm = g2.getFontMetrics();
                int lineH = fm.getHeight() + 4;
                int textStartY = titleY + 50;
                int charsLeft = charIndex;

                for (int i = 0; i < displayLines.length && charsLeft > 0; i++) {
                    String line = displayLines[i];
                    if (line == null) continue;
                    String visible = line.substring(0, Math.min(charsLeft, line.length()));
                    charsLeft -= line.length();

                    int lineW = fm.stringWidth(visible);
                    int lineX = (w - lineW) / 2;
                    int lineY = textStartY + i * lineH;

                    g2.setColor(TEXT_SHADOW);
                    g2.drawString(visible, lineX + 1, lineY + 1);
                    g2.setColor(TEXT_COLOR);
                    g2.drawString(visible, lineX, lineY);
                }
                break;

            case FADE_BACK:
                // Fade out the sepia screen
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(WHITE);
                g2.fillRect(0, 0, w, h);
                if (alpha > 0.3f) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.6f));
                    g2.setColor(SEPIA_OVERLAY);
                    g2.fillRect(0, 0, w, h);
                }
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                break;
        }
    }

    /** Mark the flashback as finished (cleanup). Call after DONE state detected. */
    public void finish() {
        state = INACTIVE;
        fragment = null;
    }

    public int getState() { return state; }

    private int getTotalTextChars() {
        int total = 0;
        if (displayLines != null) {
            for (String line : displayLines) {
                if (line != null) total += line.length();
            }
        }
        return total;
    }
}

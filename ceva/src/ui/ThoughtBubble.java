package ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayDeque;

import main.GamePanel;

/**
 * Non-blocking inner monologue / thought system.
 *
 * Unlike NPC dialogue, thoughts:
 *   - Do NOT pause the game
 *   - Do NOT require Enter to dismiss
 *   - Type out letter-by-letter, linger, then fade away
 *   - Appear as soft italic text near the bottom-center of the screen
 *   - Queue up if multiple are triggered — one plays after the other
 *
 * USAGE FROM CODE:
 *   gp.thoughts.show("I can't remember anything...");
 *   gp.thoughts.show("This place... it's drawn.", 100);  // custom linger time
 *
 * USAGE FROM TILED (MapObjectLoader):
 *   Create an object with type = "ThoughtTrigger"
 *   Properties:
 *     message  (String)  "I can't remember anything..."
 *     oneShot  (bool)    true
 *     delay    (int)     0          — frames to wait before starting (optional)
 *     linger   (int)     120        — frames to hold after typing finishes (optional)
 */
public class ThoughtBubble {

    private final GamePanel gp;

    // ── Queued thoughts ─────────────────────────────────────────────────
    private final ArrayDeque<Thought> queue = new ArrayDeque<>();

    // ── Active thought state ────────────────────────────────────────────
    private Thought active;
    private int   charIndex;        // how many chars are visible
    private int   typeCounter;      // frame counter for typewriter tick
    private int   lingerCounter;    // frames remaining after fully typed
    private int   delayCounter;     // frames to wait before starting
    private float fadeAlpha = 1f;   // for fade-out

    // ── Tuning ──────────────────────────────────────────────────────────
    private static final int   TYPE_SPEED      = 3;    // frames per character
    private static final int   DEFAULT_LINGER  = 120;  // ~2 seconds at 60 UPS
    private static final int   FADE_DURATION   = 40;   // frames to fade out
    private static final float Y_POSITION      = 0.82f; // fraction of screen height
    private static final Color TEXT_COLOR       = new Color(220, 215, 200);
    private static final Color SHADOW_COLOR     = new Color(0, 0, 0, 140);
    private static final Color BG_COLOR         = new Color(10, 8, 14, 100);

    // ── Inner class ─────────────────────────────────────────────────────
    private static class Thought {
        final String text;
        final int linger;
        final int delay;

        Thought(String text, int linger, int delay) {
            this.text   = text;
            this.linger = linger;
            this.delay  = delay;
        }
    }

    public ThoughtBubble(GamePanel gp) {
        this.gp = gp;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Queue an inner thought with default linger and no delay. */
    public void show(String text) {
        show(text, DEFAULT_LINGER, 0);
    }

    /** Queue an inner thought with custom linger (frames) and no delay. */
    public void show(String text, int linger) {
        show(text, linger, 0);
    }

    /** Queue an inner thought with custom linger and delay (frames before it starts). */
    public void show(String text, int linger, int delay) {
        if (text == null || text.isEmpty()) return;
        queue.add(new Thought(text, Math.max(30, linger), Math.max(0, delay)));
    }

    /** True if a thought is currently visible or queued. */
    public boolean isActive() {
        return active != null || !queue.isEmpty();
    }

    /** Clear everything — useful when changing maps or skipping cutscenes. */
    public void clear() {
        queue.clear();
        active = null;
    }

    // ── Update (call from GamePanel.update, runs every tick) ────────────

    public void update() {
        // If nothing active, try to pull from queue
        if (active == null) {
            if (queue.isEmpty()) return;
            active        = queue.poll();
            charIndex     = 0;
            typeCounter   = 0;
            lingerCounter = active.linger;
            delayCounter  = active.delay;
            fadeAlpha     = 1f;
            return; // start next frame
        }

        // Wait for delay
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // Typewriter phase
        if (charIndex < active.text.length()) {
            typeCounter++;
            if (typeCounter >= TYPE_SPEED) {
                typeCounter = 0;
                charIndex++;
                // Skip ahead through spaces so pacing feels natural
                while (charIndex < active.text.length() && active.text.charAt(charIndex) == ' ') {
                    charIndex++;
                }
                // Pause slightly on '.' ',' '!' '?' '—' for natural rhythm
                // Applied ONCE per character reveal (inside the advancement block)
                if (charIndex > 0 && charIndex < active.text.length()) {
                    char prev = active.text.charAt(charIndex - 1);
                    if (prev == '.' || prev == '!' || prev == '?') {
                        typeCounter -= 4; // extra pause (will take 4 more frames)
                    } else if (prev == ',' || prev == '\u2014') {
                        typeCounter -= 2;
                    }
                }
            }
            return;
        }

        // Linger phase
        if (lingerCounter > 0) {
            lingerCounter--;
            // Start fading during the last FADE_DURATION frames
            if (lingerCounter < FADE_DURATION) {
                fadeAlpha = (float) lingerCounter / FADE_DURATION;
            }
            return;
        }

        // Done
        active = null;
    }

    // ── Draw (call from UI.draw, during playState / cutsceneState) ──────

    public void draw(Graphics2D g2) {
        if (active == null || delayCounter > 0) return;
        if (fadeAlpha <= 0f) return;

        String visible = active.text.substring(0, Math.min(charIndex, active.text.length()));
        if (visible.isEmpty()) return;

        // Font: italic serif for inner monologue feel
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font thoughtFont = new Font("Serif", Font.ITALIC, 28);
        g2.setFont(thoughtFont);
        FontMetrics fm = g2.getFontMetrics();

        int textW = fm.stringWidth(visible);
        int textH = fm.getHeight();
        int screenW = gp.screenWidth;
        int tx = (screenW - textW) / 2;
        int ty = (int)(gp.screenHeight * Y_POSITION);

        Composite saved = g2.getComposite();
        float a = Math.max(0f, Math.min(1f, fadeAlpha));
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));

        // Subtle dark pill behind text
        int padX = 20, padY = 10;
        g2.setColor(BG_COLOR);
        g2.fillRoundRect(tx - padX, ty - textH - padY + 4, textW + padX * 2, textH + padY * 2, 18, 18);

        // Text shadow
        g2.setColor(SHADOW_COLOR);
        g2.drawString(visible, tx + 1, ty + 1);

        // Text
        g2.setColor(TEXT_COLOR);
        g2.drawString(visible, tx, ty);

        // Typing cursor (blinking underscore while still typing)
        if (charIndex < active.text.length()) {
            if ((typeCounter / 8) % 2 == 0) {
                int cursorX = tx + textW + 2;
                g2.drawString("_", cursorX, ty);
            }
        }

        g2.setComposite(saved);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }
}

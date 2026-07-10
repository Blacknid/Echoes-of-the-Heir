package environment;

import java.util.Random;

import main.GamePanel;

/**
 * Periodic "something's out there" moments for tense spaces (dungeons, etc.) — a subtle distant
 * rumble on a randomized interval, with no scripted trigger tiles needed. Reuses
 * {@link ui.ScreenShake#shake} as-is: it already lets a stronger active shake win over a weaker
 * one (see ScreenShake.shake), so a tension-beat rumble never fights combat shake.
 */
public class TensionBeats {

    public static final int MIN_GAP_TICKS = 900;   // ~15s
    public static final int MAX_GAP_TICKS = 2400;  // ~40s

    // Weaker and shorter than ScreenShake.shakeLight() (3f/8 frames) so it reads as a distant
    // tremor, not a hit — and so it never outcompetes real combat feedback.
    private static final float RUMBLE_INTENSITY = 1.5f;
    private static final int RUMBLE_DURATION_TICKS = 14;

    public boolean enabled = false;

    private final GamePanel gp;
    private final Random random = new Random();
    private int ticksUntilNextBeat;

    public TensionBeats(GamePanel gp) {
        this.gp = gp;
        rollNextGap();
    }

    /** Called on map change so a beat armed on the previous map doesn't fire on the new one. */
    public void reset() {
        rollNextGap();
    }

    public void update() {
        if (!enabled) return;

        ticksUntilNextBeat--;
        if (ticksUntilNextBeat <= 0) {
            gp.screenShake.shake(RUMBLE_INTENSITY, RUMBLE_DURATION_TICKS);
            rollNextGap();
        }
    }

    private void rollNextGap() {
        ticksUntilNextBeat = MIN_GAP_TICKS + random.nextInt(MAX_GAP_TICKS - MIN_GAP_TICKS);
    }
}

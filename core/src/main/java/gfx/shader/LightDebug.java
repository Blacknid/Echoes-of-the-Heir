package gfx.shader;

/**
 * Live diagnostics + kill-switches for hunting the "lights dance / move with me when I walk" bug.
 *
 * <p>The bug has worn several disguises (self-shadowing, mask-blit transform, FBO flip); this class
 * lets the PLAYER isolate the remaining one interactively: an on-screen HUD ({@link ui.LightDebugHud})
 * shows every per-frame value that could shift the light pool relative to the world, latches a RED
 * warning whenever a suspicious condition fires, and F-keys switch individual lighting features off
 * so whichever toggle stops the dancing names the culprit.
 *
 * <p>Everything here is static and written by the render path each frame:
 * <ul>
 * <li>{@code F5}, HUD on/off</li>
 * <li>{@code F6}, freeze shader time (kills shimmer/breathe/flicker ANIMATION; if dancing stops,
 *       the "movement" was the organic light detail animating, not a coordinate bug)</li>
 * <li>{@code F7}, disable the noise/ripple detail entirely (static falloff circle)</li>
 * <li>{@code F8}, disable bloom (half-res glow can "swim" while scrolling)</li>
 * <li>{@code F9}, disable shadows (ray-march churn)</li>
 * <li>{@code F10}, disable rim lighting</li>
 * </ul>
 */
public final class LightDebug {

    private LightDebug() {}

    // ── Kill-switch toggles (polled from F-keys in MichiGame.render) ──
    // HUD defaults OFF so the lighting-diagnostic overlay isn't shown at startup; press F5 to
    // bring it up when hunting the "dancing lights" bug.
    public static boolean hud        = false;
    public static boolean freezeTime = false;
    public static boolean noDetail   = false;
    public static boolean noBloom    = false;
    public static boolean noShadows  = false;
    public static boolean noRim      = false;

    // ── Live values written by the render path each frame, read by the HUD ──
    /** Batch transform translation at the moment the light mask was blitted (MUST be 0,0
 * anything else shifts the full-screen mask off the world by that amount). */
    public static float maskBlitTx, maskBlitTy;
    /** Whether the mask blit ran inside the HIGH-tier scene capture (flip-orientation branch). */
    public static boolean maskBlitDuringCapture;
    /** Whether the premultiplied composite branch was used (GLSL mask) vs legacy straight-alpha. */
    public static boolean maskPremultiplied;
    /** Lights uploaded to the shader this frame; light 0 is the player's. */
    public static int lightCount;
    public static float light0X, light0Y;
    /** Which light path ran: "HIGH", "MED-cheap", "baked", "off". */
    public static String tier = "off";

    // ── Latched warnings: a transient event (one shake frame) stays on screen ~1.5s ──
    private static final int LATCH_FRAMES = 90;
    private static final int MAX_WARN = 8;
    private static final String[] warnMsg = new String[MAX_WARN];
    private static final int[] warnTtl = new int[MAX_WARN];

    /** Raise (or refresh) a latched warning line. Same key replaces its previous message. */
    public static void warn(String key, String msg) {
        int free = -1;
        for (int i = 0; i < MAX_WARN; i++) {
            if (warnMsg[i] != null && warnMsg[i].startsWith(key)) { warnMsg[i] = key + ": " + msg; warnTtl[i] = LATCH_FRAMES; return; }
            if (warnMsg[i] == null && free < 0) free = i;
        }
        if (free >= 0) { warnMsg[free] = key + ": " + msg; warnTtl[free] = LATCH_FRAMES; }
    }

    /** Tick TTLs once per frame; expired warnings vanish. Returns the live warning lines. */
    public static java.util.List<String> tickWarnings() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(MAX_WARN);
        for (int i = 0; i < MAX_WARN; i++) {
            if (warnMsg[i] == null) continue;
            if (--warnTtl[i] <= 0) { warnMsg[i] = null; continue; }
            out.add(warnMsg[i]);
        }
        return out;
    }
}

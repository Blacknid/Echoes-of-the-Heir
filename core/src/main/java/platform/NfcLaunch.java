package platform;

/**
 * Signals "this app instance was just brought to front by an NFC tap on a hosting phone's cold-
 * launch tag" (see androidlauncher.nfc.Ndef4Service's AAR) from the Android launcher into shared
 * game code, so the title screen can skip straight to the JOIN GAME auto-join flow instead of
 * requiring the player to navigate there manually — see ui.UI's title-screen NFC-launch handling.
 *
 * <p>A simple consume-once flag rather than a queue: only the most recent tap matters (if two taps
 * land before the game reads the first, the second's intent already superseded it), and
 * AndroidLauncher.onCreate/onNewIntent both funnel into {@link #markLaunchedViaNfc}.
 */
public final class NfcLaunch {
    private NfcLaunch() {}

    private static volatile boolean pending = false;

    public static void markLaunchedViaNfc() { pending = true; }

    /** Returns true (and clears the flag) at most once per tap — call from the title-screen tick. */
    public static boolean consumeLaunchedViaNfc() {
        if (!pending) return false;
        pending = false;
        return true;
    }
}

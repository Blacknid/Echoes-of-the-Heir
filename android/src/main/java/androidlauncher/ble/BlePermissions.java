package androidlauncher.ble;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Runtime (dangerous) Bluetooth permission check/request for API 31+ (BLUETOOTH_ADVERTISE/
 * BLUETOOTH_CONNECT/BLUETOOTH_SCAN), see AndroidManifest.xml's comment on why
 * ACCESS_FINE_LOCATION isn't needed (neverForLocation). Below API 31 these permissions are
 * normal (granted at install), so {@link #hasAll} is trivially true there, this app's minSdk is
 * 26, meaning no legacy pre-31 request path is needed either way.
 *
 * <h2>Why the request is callback-driven rather than fire-and-forget</h2>
 * These are runtime permissions, so a denial (or a dialog the player swiped away) is a normal,
 * recoverable state that must be re-askable at the moment BLE is actually needed. An earlier
 * version asked exactly once ever, on first boot, latching a "already asked" flag in
 * SharedPreferences <em>before</em> the dialog even resolved, and every later call was a no-op.
 * If the player dismissed that one dialog, {@link #hasAll} stayed false permanently, so
 * BleHostServiceImpl/BleGuestServiceImpl's isSupported() gates reported "BLE unsupported" forever
 * and INVITE PLAYER / JOIN GAME silently did nothing on a device with perfectly working
 * Bluetooth. {@link #ensureGranted} instead requests on demand and resumes the caller's action
 * once the player responds, so a tap is never consumed and lost by a missing permission.
 */
public final class BlePermissions {
    private BlePermissions() {}

    private static final int REQUEST_CODE = 4271;

    /**
     * Action to resume once the player answers the permission dialog, see {@link #ensureGranted}.
     * Static because the request/response round-trip goes through the Activity and can outlive a
     * configuration change; cleared as soon as it fires so a later unrelated grant can't re-run it.
     */
    private static Runnable pendingOnGranted;
    private static Runnable pendingOnDenied;

    static String[] required() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
            };
        }
        return new String[0];
    }

    public static boolean hasAll(Activity activity) {
        for (String p : required()) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    static void requestAll(Activity activity) {
        String[] perms = required();
        if (perms.length > 0) {
            activity.requestPermissions(perms, REQUEST_CODE);
        }
    }

    /**
     * Runs {@code onGranted} immediately if the Bluetooth permissions are already held, otherwise
     * shows the OS dialog and runs it when (and only when) the player grants them, running
     * {@code onDenied} if they refuse. This is what lets INVITE PLAYER / JOIN GAME survive a
     * first-ever tap on a device that hasn't granted Bluetooth yet: the action is deferred, not
     * dropped. {@code onDenied} may be null if the caller has nothing to report.
     *
     * <p>Must be called from the UI thread (both call sites come from libGDX's render thread via
     * the game's own menu handling, which is why the resume runnables are posted rather than run
     * inline, see {@link #onRequestPermissionsResult}).
     */
    public static void ensureGranted(Activity activity, Runnable onGranted, Runnable onDenied) {
        if (hasAll(activity)) {
            onGranted.run();
            return;
        }
        pendingOnGranted = onGranted;
        pendingOnDenied = onDenied;
        activity.runOnUiThread(() -> requestAll(activity));
    }

    /**
     * Hook for AndroidLauncher#onRequestPermissionsResult: resolves whatever action was waiting on
     * {@link #ensureGranted}. The resumed runnable is posted to libGDX's thread because it lands
     * back in game state (BleMultiplayerSession/UI) that the render thread owns, matching the
     * threading discipline BleHostServiceImpl/BleGuestServiceImpl apply to their own callbacks.
     */
    public static void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return;
        Runnable granted = pendingOnGranted;
        Runnable denied = pendingOnDenied;
        pendingOnGranted = null;
        pendingOnDenied = null;

        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        Runnable toRun = allGranted ? granted : denied;
        if (toRun != null) com.badlogic.gdx.Gdx.app.postRunnable(toRun);
    }

    /**
     * Pre-warms the Bluetooth permission dialog on app start so it's typically out of the way
     * before the player's first host/join tap. Purely an optimization: unlike the old
     * "ask once ever" latch this replaced, nothing depends on it succeeding, a denial here is
     * fully recovered by {@link #ensureGranted} re-asking at the actual point of use.
     */
    public static void prewarm(Activity activity) {
        if (hasAll(activity)) return;
        requestAll(activity);
    }
}

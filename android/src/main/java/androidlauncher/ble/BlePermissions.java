package androidlauncher.ble;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Runtime (dangerous) Bluetooth permission check/request for API 31+ (BLUETOOTH_ADVERTISE/
 * BLUETOOTH_CONNECT/BLUETOOTH_SCAN) — see AndroidManifest.xml's comment on why
 * ACCESS_FINE_LOCATION isn't needed (neverForLocation). Below API 31 these permissions are
 * normal (granted at install), so {@link #hasAll} is trivially true there — this app's minSdk is
 * 26, meaning no legacy pre-31 request path is needed either way.
 */
public final class BlePermissions {
    private BlePermissions() {}

    private static final int REQUEST_CODE = 4271;
    private static final String PREFS_NAME = "ble_permissions";
    private static final String KEY_ASKED_ON_BOOT = "asked_on_boot";

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

    static boolean hasAll(Activity activity) {
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
     * Requests Bluetooth permissions once, the very first time the app is ever opened — so by the
     * time a player actually taps INVITE PLAYER or JOIN GAME, the OS dialog is long out of the way
     * and doesn't interrupt the tap moment itself. Safe to call every boot: the "already asked"
     * flag (persisted in SharedPreferences, separate from Android's own permission grant state so
     * this fires exactly once regardless of whether the player allowed or denied it) makes every
     * call after the first a no-op.
     */
    public static void requestOnFirstBootIfNeeded(Activity activity) {
        if (hasAll(activity)) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED_ON_BOOT, false)) return;
        prefs.edit().putBoolean(KEY_ASKED_ON_BOOT, true).apply();
        requestAll(activity);
    }
}

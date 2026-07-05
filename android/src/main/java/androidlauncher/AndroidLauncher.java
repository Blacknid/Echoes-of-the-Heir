package androidlauncher;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import androidlauncher.coop.BleBossCoop;
import androidlauncher.nfc.NfcPairingManager;
import main.MichiGame;
import platform.Nfc;

/**
 * Android entry point (gdx-backend-android). Configures the Android-specific application
 * settings (immersive mode, wakelock, disabled sensors) then hands off to the shared
 * {@link MichiGame}, which owns the game loop/rendering on every backend and runs its own
 * platform-specific setup (license priming, touch overlay, etc.) once {@code Gdx} is live —
 * {@code Gdx.app}/{@code Gdx.files} do not exist yet at this point in the Android lifecycle,
 * so platform setup that depends on them cannot run here.
 *
 * <p>NFC pairing and Bluetooth boss co-op are the two exceptions registered directly from here
 * rather than from {@code MichiGame.create()}: both need the live {@code Activity} instance
 * (reader-mode NFC and BLE runtime-permission requests), which only this class has — {@code core}
 * never depends on {@code android.*} classes.
 */
public class AndroidLauncher extends AndroidApplication {

    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 4201;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useWakelock = true; // top-down action game — screen shouldn't sleep mid-play

        Nfc.set(new NfcPairingManager(this));
        requestBluetoothPermissionsIfNeeded();
        platform.Bt.set(new BleBossCoop(this));

        initialize(new MichiGame(), config);
    }

    /** Bluetooth boss co-op (Phase 3) needs these at runtime on API 31+; below that, the manifest
     *  declarations alone are sufficient and this is a no-op. Declining simply means the "Invite
     *  Friends" flow reports Bluetooth as unavailable — the rest of the game is unaffected. */
    private void requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return; // API 31+

        String[] needed = {
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
        };
        boolean allGranted = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, needed, BLUETOOTH_PERMISSION_REQUEST_CODE);
        }
    }
}

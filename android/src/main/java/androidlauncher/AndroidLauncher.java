package androidlauncher;

import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import androidlauncher.ble.BleGuestServiceImpl;
import androidlauncher.ble.BleHostServiceImpl;
import androidlauncher.ble.BlePermissions;
import androidlauncher.nfc.Ndef4Service;
import androidlauncher.nfc.NfcFriendServiceImpl;
import main.MichiGame;
import platform.BleMultiplayer;
import platform.NfcFriend;
import platform.NfcLaunch;

/**
 * Android entry point (gdx-backend-android). Configures the Android-specific application
 * settings (immersive mode, wakelock, disabled sensors) then hands off to the shared
 * {@link MichiGame}, which owns the game loop/rendering on every backend and runs its own
 * platform-specific setup (license priming, touch overlay, etc.) once {@code Gdx} is live
 * {@code Gdx.app}/{@code Gdx.files} do not exist yet at this point in the Android lifecycle,
 * so platform setup that depends on them cannot run here.
 */
public class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useWakelock = true; // top-down action game, screen shouldn't sleep mid-play

        // Registered before MichiGame.create() runs so platform.NfcFriend is ready the first time
        // the friends screen needs it (readers/emulated payload, see NfcFriendServiceImpl).
        NfcFriend.set(new NfcFriendServiceImpl(this));

        // Local BLE multiplayer (pause menu's INVITE PLAYER), see main.BleMultiplayerSession.
        BleMultiplayer.setHost(new BleHostServiceImpl(this));
        BleMultiplayer.setGuest(new BleGuestServiceImpl(this));

        // Ask for Bluetooth permissions once, the very first time the app is ever opened, so the
        // OS dialog is long out of the way before the player's first actual host/join tap (see
        // BlePermissions#requestOnFirstBootIfNeeded's class doc).
        BlePermissions.requestOnFirstBootIfNeeded(this);

        checkNfcLaunch(getIntent());

        initialize(new MichiGame(), config);
    }

    /**
     * singleTask launchMode routes a repeat tap (app already running) here instead of a fresh
 * onCreate, still needs to mark the flag so the title screen's tick (see ui.UI) picks up the
     * "auto-join" trigger even if the game was, say, sitting idle on the title screen already.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkNfcLaunch(intent);
    }

    /**
     * A cold-launch-via-tap (see androidlauncher.nfc.Ndef4Service's AAR) delivers one of the NFC
 * tag-dispatch actions with EXTRA_TAG populated, see platform.NfcLaunch's class doc for why
     * only the action check (not a specific payload) matters here: the tag itself carries nothing
     * but the AAR, the real handoff happens over NfcFriend's reader-mode channel afterward.
     */
    private void checkNfcLaunch(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean isNfcAction = NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action);
        if (isNfcAction || intent.hasExtra(NfcAdapter.EXTRA_TAG)) {
            NfcLaunch.markLaunchedViaNfc();
        }
    }

    /**
     * D2760000850101 (the standard NFC Forum NDEF Tag Application AID Ndef4Service uses) is not
 * exclusive to this app, Android ships a built-in "Embedded tag" system service
     * (com.android.nfc.ndef_nfcee) registered for the same AID on real devices, confirmed via
     * `adb shell dumpsys nfc` on both Galaxy S25 Ultra and A37 test hardware. With two services
     * eligible for one AID, Android's documented same-AID conflict resolution shows the user a
 * disambiguation dialog on every tap, naming Ndef4Service's own description ("Michi's
 * Adventure game invite") as one of the choices, instead of silently routing to us. Foreground
     * preference (CardEmulation#setPreferredService, cleared in onPause per its own contract)
     * overrides that conflict resolution while this Activity is frontmost, so INVITE PLAYER /
     * JOIN GAME taps between two phones that both have the app open never hit the chooser. This
     * cannot help the genuinely-cold-launch case (app fully closed, no foreground Activity to call
 * this from), that tap still goes through normal OS dispatch and may show the system chooser
     * once; it's an inherent platform limit of sharing the standard NDEF AID, not a bug here.
     */
    @Override
    protected void onResume() {
        super.onResume();
        CardEmulation ce = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(this));
        if (ce != null) {
            ce.setPreferredService(this, new ComponentName(this, Ndef4Service.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CardEmulation ce = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(this));
        if (ce != null) {
            ce.unsetPreferredService(this);
        }
    }
}

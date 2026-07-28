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
import androidlauncher.itch.AndroidItchAuth;
import androidlauncher.nfc.Ndef4Service;
import androidlauncher.nfc.NfcFriendServiceImpl;
import main.MichiGame;
import platform.BleMultiplayer;
import platform.ItchAuthProvider;
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

        // itch.io sign-in for first-run license activation. Registered (not invoked) here:
        // LicenseActivation only calls it when this install has no license yet, so a returning
        // player never sees a browser. Before this existed no provider was registered on
        // Android at all, so a gated ACTIVATE could never succeed on a phone and the
        // owner-secret file was the only way to get a license here.
        ItchAuthProvider.set(new AndroidItchAuth(this));

        // Pre-warm the Bluetooth permission dialog so it's usually out of the way before the
        // player's first host/join tap. Only an optimization now: INVITE PLAYER / JOIN GAME each
        // re-ask on demand and resume themselves if it's still ungranted, so a denial here is
        // recoverable (see BlePermissions#ensureGranted, and its class doc for the "asked once
        // ever, then permanently disabled" bug this replaced).
        BlePermissions.prewarm(this);

        checkNfcLaunch(getIntent());

        // The itch redirect normally arrives at the running Activity via onNewIntent, but if the OS
        // destroyed the game while the browser was frontmost (low memory), the redirect cold-starts
        // it and lands here instead. AndroidItchAuth's handoff is static precisely so the token
        // survives that recreation; without this call the sign-in would hang to its full timeout.
        AndroidItchAuth.handleRedirect(getIntent());

        initialize(new MichiGame(), config);
    }

    /**
     * singleTask launchMode routes a repeat tap (app already running) here instead of a fresh
 * onCreate, still needs to mark the flag so the title screen's tick (see ui.UI) picks up the
     * "auto-join" trigger even if the game was, say, sitting idle on the title screen already.
     *
     * <p>Also where the itch.io OAuth redirect (michi://itch-auth) comes back in, see
     * {@link AndroidItchAuth} for why that is a custom scheme rather than the desktop build's
     * loopback listener.
     */
    /**
     * Routes the Bluetooth permission dialog's outcome back to whatever host/join action was
     * waiting on it (see BlePermissions#ensureGranted). Without this the deferred INVITE PLAYER /
     * JOIN GAME action would never resume and the tap would be silently lost.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        BlePermissions.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkNfcLaunch(intent);
        AndroidItchAuth.handleRedirect(intent);
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
        // getDefaultAdapter() is null on devices with no NFC hardware, and CardEmulation.getInstance
        // throws on a null adapter — without these guards the app crashed in onResume on every
        // NFC-less device before the game even drew a frame. NFC features just stay off there.
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            CardEmulation ce = adapter != null ? CardEmulation.getInstance(adapter) : null;
            if (ce != null) {
                ce.setPreferredService(this, new ComponentName(this, Ndef4Service.class));
            }
        } catch (RuntimeException e) {
            // Some OEM builds throw UnsupportedOperationException from CardEmulation; not fatal.
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
            CardEmulation ce = adapter != null ? CardEmulation.getInstance(adapter) : null;
            if (ce != null) {
                ce.unsetPreferredService(this);
            }
        } catch (RuntimeException e) {
            // See onResume — never let NFC plumbing take the whole app down.
        }
    }
}

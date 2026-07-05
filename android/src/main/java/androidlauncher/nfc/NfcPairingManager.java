package androidlauncher.nfc;

import java.nio.charset.StandardCharsets;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import com.badlogic.gdx.Gdx;

import platform.NfcPairing;

/**
 * Android implementation of {@link platform.NfcPairing}. Registered from {@link
 * androidlauncher.AndroidLauncher} once the activity exists (NFC reader-mode APIs are
 * Activity-scoped, unlike the rest of the game's platform code which only needs {@code Gdx}).
 *
 * <p>A "session" is symmetric: starting one both (a) arms {@link NfcAdapter#enableReaderMode} so
 * WE can read a peer's tag, and (b) pushes our own payload into {@link MichiHostApduService} so
 * THEIR reader can read US. Whichever phone's reader activates first on the tap gets the other's
 * payload; the peer's own reader (if also mid-session) reads ours right back — no lead/follow
 * negotiation needed, which matters since two players tapping phones together can't coordinate
 * who taps "first".
 */
public final class NfcPairingManager implements NfcPairing {

    private static final String TAG = "MichiNFC";

    // Must match android/res/xml/apduservice.xml's <aid-filter>.
    private static final byte[] SELECT_AID_APDU = {
        (byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, (byte) 0x07,
        (byte) 0xF0, (byte) 0x39, (byte) 0x31, (byte) 0x41, (byte) 0x41, (byte) 0x41, (byte) 0x00,
        (byte) 0x00
    };

    private final Activity activity;
    private volatile Listener listener;
    private volatile boolean sessionActive = false;

    public NfcPairingManager(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean isAvailable() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        return adapter != null && adapter.isEnabled();
    }

    @Override
    public void startSession(String ownUsername, String ownBluetoothToken, Listener listener) {
        if (sessionActive) return;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("NFC is not available or is turned off.");
            return;
        }

        this.listener = listener;
        sessionActive = true;
        MichiHostApduService.setOutgoingPayload(ownUsername, ownBluetoothToken);

        int flags = NfcAdapter.FLAG_READER_NFC_A
                | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
        adapter.enableReaderMode(activity, this::onTagDiscovered, flags, null);
        Log.d(TAG, "NFC session started (reader mode + HCE payload armed).");
    }

    @Override
    public void stopSession() {
        if (!sessionActive) return;
        sessionActive = false;
        listener = null;
        MichiHostApduService.clearOutgoingPayload();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter != null) adapter.disableReaderMode(activity);
        Log.d(TAG, "NFC session stopped.");
    }

    // Runs on a binder thread from the NFC stack, NOT the render thread — every field/callback
    // touch here must be handed back to the render thread via Gdx.app.postRunnable, since UI.java
    // (the eventual Listener) assumes single-threaded access like the rest of the game loop.
    private void onTagDiscovered(Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            postError("That device can't be tapped for pairing.");
            return;
        }
        try {
            iso.connect();
            iso.setTimeout(2500);
            byte[] response = iso.transceive(SELECT_AID_APDU);
            iso.close();

            if (response == null || response.length < 2) {
                postError("Tap didn't come through — try again.");
                return;
            }
            // Last two bytes are the status word (0x9000 = OK); everything before is our payload.
            byte sw1 = response[response.length - 2];
            byte sw2 = response[response.length - 1];
            if (sw1 != (byte) 0x90 || sw2 != (byte) 0x00) {
                postError("The other phone didn't respond correctly — try again.");
                return;
            }
            String payload = new String(response, 0, response.length - 2, StandardCharsets.UTF_8);
            int delim = payload.indexOf(MichiHostApduService.FIELD_DELIMITER);
            if (delim < 0) {
                postError("Received an unreadable tap — try again.");
                return;
            }
            String peerUsername = payload.substring(0, delim);
            String peerToken = payload.substring(delim + 1);
            if (peerUsername.isEmpty()) {
                postError("Received an unreadable tap — try again.");
                return;
            }

            Listener l = listener;
            Gdx.app.postRunnable(() -> { if (l != null) l.onPeerRead(peerUsername, peerToken); });
        } catch (Exception e) {
            Log.e(TAG, "NFC tap failed", e);
            postError("Tap failed: " + e.getMessage());
        }
    }

    private void postError(String message) {
        Listener l = listener;
        Gdx.app.postRunnable(() -> { if (l != null) l.onError(message); });
    }
}

package androidlauncher.nfc;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

import platform.NfcFriendPayload;
import platform.NfcFriendService;

/**
 * Android implementation of {@link NfcFriendService}: reads other devices' {@link FriendHceService}
 * tags via {@link NfcAdapter#enableReaderMode}, and keeps this device's own emulated payload
 * current so it can be read the same way (including while backgrounded, per HCE AID routing).
 *
 * <p>Reader mode only runs while the activity is in the foreground — that's an Android platform
 * limit, not a choice made here (see platform.NfcFriendService's class doc). Registered once from
 * {@link androidlauncher.AndroidLauncher#onCreate}.
 */
public class NfcFriendServiceImpl implements NfcFriendService {

    private static final byte[] SELECT_APDU = buildSelectApdu(FriendHceService.AID);

    private final Activity activity;
    private final NfcAdapter adapter;

    public NfcFriendServiceImpl(Activity activity) {
        this.activity = activity;
        this.adapter = NfcAdapter.getDefaultAdapter(activity);
    }

    @Override
    public boolean isSupported() {
        return adapter != null && adapter.isEnabled();
    }

    @Override
    public void startReading(Consumer<String> onResult) {
        if (adapter == null) {
            onResult.accept(null);
            return;
        }
        NfcAdapter.ReaderCallback callback = tag -> {
            String payload = readPayload(tag);
            // Gdx.app.postRunnable (not runOnUiThread — Android's UI thread and libGDX's GL/render
            // thread are different threads) so onResult's downstream game-state mutation (e.g.
            // BleMultiplayerSession.joinHost's map load, gameState transition) runs on the same
            // thread the render loop reads that state from. See BleGuestServiceImpl.connect's
            // class-doc-adjacent comment for the concrete crash/corruption this avoids.
            com.badlogic.gdx.Gdx.app.postRunnable(() -> onResult.accept(payload));
        };
        // When two phones running THIS app tap, the reader phone is also HCE-listening (its own
        // FriendHceService is always AID-routed). On-device logs (Galaxy S23, 2026-07-10) showed
        // the platform resolving that poll+listen collision by tearing our reader mode back to
        // flags:0 the instant the tag was discovered, handing the RF event to the default NDEF
        // tag dispatcher — which then failed ("rw_t4t_sm_detect_ndef: NLEN...", "Check NDEF Failed
        // status=3") because our HCE peer is a custom-APDU service, not a Type-4 NDEF tag. Our own
        // readPayload()/IsoDep.transceive never got to run, so the invite was never read and JOIN
        // GAME reported "couldn't connect" without ever starting the BLE scan.
        //
        // FLAG_READER_SKIP_NDEF_CHECK alone wasn't enough: the extra fixes below keep our reader
        // session owning the RF link long enough to do the custom SELECT:
        //  • NFC_B in addition to NFC_A — some Samsung HCE peers present as ISO-DEP over NFC-B; a
        //    reader polling only NFC-A can miss them and fall through to default dispatch.
        //  • NO_PLATFORM_SOUNDS — suppresses the system tag chime whose handling races our reader.
        //  • EXTRA_READER_PRESENCE_CHECK_DELAY (large) — the default aggressive presence check can
        //    declare the tag "gone" and drop the session mid-exchange between two hand-held phones;
        //    a long delay keeps the reader engaged through the transceive round trip.
        int flags = NfcAdapter.FLAG_READER_NFC_A
                | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
        android.os.Bundle extras = new android.os.Bundle();
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000);
        adapter.enableReaderMode(activity, callback, flags, extras);
    }

    @Override
    public void stopReading() {
        if (adapter != null) adapter.disableReaderMode(activity);
    }

    @Override
    public void setEmulatedPayload(String friendId, String username) {
        setEmulatedPayloadRaw(NfcFriendPayload.encode(friendId, username));
    }

    @Override
    public void setEmulatedPayloadRaw(String payload) {
        FriendHceService.currentPayload = payload != null ? FriendHceService.encode(payload) : null;
    }

    /** Runs on the NFC reader thread (not the UI thread) — returns null on any I/O failure. */
    private static String readPayload(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return null;
        try {
            isoDep.connect();
            isoDep.setTimeout(2000);
            byte[] response = isoDep.transceive(SELECT_APDU);
            if (response == null || response.length < 2) return null;
            byte[] sw = Arrays.copyOfRange(response, response.length - 2, response.length);
            if (sw[0] != (byte) 0x90 || sw[1] != 0x00) return null;
            byte[] body = Arrays.copyOf(response, response.length - 2);
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            try {
                isoDep.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] buildSelectApdu(String aidHex) {
        byte[] aid = hexToBytes(aidHex);
        byte[] apdu = new byte[5 + aid.length];
        apdu[0] = 0x00; // CLA
        apdu[1] = (byte) 0xA4; // INS: SELECT
        apdu[2] = 0x04; // P1: select by AID
        apdu[3] = 0x00; // P2
        apdu[4] = (byte) aid.length; // Lc
        System.arraycopy(aid, 0, apdu, 5, aid.length);
        return apdu;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}

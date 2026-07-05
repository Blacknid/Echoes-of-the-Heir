package androidlauncher.nfc;

import java.nio.charset.StandardCharsets;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

/**
 * Host-card-emulation service: while an NFC add-friend session is active (see
 * {@link NfcPairingManager}), this phone answers a peer's reader-mode tap by emulating an NFC tag
 * that "contains" our own username + Bluetooth pairing token. This is what makes the tap
 * symmetric — both phones can be readers and both can be tags, so either can tap first.
 *
 * <p>The Android system binds/unbinds this service around each tap automatically; the actual
 * payload it should answer with is pushed in from {@link NfcPairingManager} via the static
 * {@link #setOutgoingPayload} before a session starts (there is no other channel into an HCE
 * service — it isn't constructed by our own code, only by the NFC subsystem).
 */
public class MichiHostApduService extends HostApduService {

    private static final String TAG = "MichiHCE";

    /** Separates username from the BT token in the payload. Usernames are alnum/_/- only
     *  (see server.py's username_is_valid), so this control byte can never collide with content. */
    static final char FIELD_DELIMITER = '';

    // SELECT AID APDU header, per ISO/IEC 7816-4. Must match the AID declared in
    // android/res/xml/apduservice.xml (F0393141414100).
    private static final byte[] SELECT_AID_HEADER = {
        (byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00
    };
    private static final byte[] SW_OK          = { (byte) 0x90, (byte) 0x00 };
    private static final byte[] SW_UNKNOWN_CMD = { (byte) 0x6F, (byte) 0x00 };

    /** username + FIELD_DELIMITER + bluetoothToken, refreshed by NfcPairingManager per session. */
    private static volatile byte[] outgoingPayload = new byte[0];

    static void setOutgoingPayload(String username, String bluetoothToken) {
        String combined = (username == null ? "" : username) + FIELD_DELIMITER
                + (bluetoothToken == null ? "" : bluetoothToken);
        outgoingPayload = combined.getBytes(StandardCharsets.UTF_8);
    }

    static void clearOutgoingPayload() {
        outgoingPayload = new byte[0];
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (startsWith(commandApdu, SELECT_AID_HEADER)) {
            // SELECT succeeded — respond to the AID selection, then hand back our payload on the
            // very next command the reader sends (a reader always follows SELECT with a READ).
            Log.d(TAG, "AID selected, replying with " + outgoingPayload.length + "-byte payload");
            return concat(outgoingPayload, SW_OK);
        }
        return SW_UNKNOWN_CMD;
    }

    @Override
    public void onDeactivated(int reason) {
        Log.d(TAG, "Deactivated: " + reason);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

package androidlauncher.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Emulates this device as an NFC tag carrying this player's add-friend identity (friend_id +
 * username, never the license key — see platform.NfcFriendPayload). Android wakes this service
 * via AID routing whenever another phone's NFC reader mode SELECTs {@link #AID}, even if the game
 * itself is fully closed or backgrounded — the same mechanism Google Wallet relies on, so a
 * player's tag "just works" without them having the app open (see AndroidManifest's
 * apduservice.xml host-apdu-service registration).
 *
 * <p>Uses a minimal two-command custom APDU exchange rather than full NFC Forum Type-4 Tag NDEF
 * emulation (SELECT + multiple READ BINARYs against an emulated CC/NDEF file): SELECT AID returns
 * the payload directly in one round trip, which is all a same-app phone-to-phone tap needs and
 * keeps this service's logic auditable in a few lines.
 */
public class FriendHceService extends HostApduService {

    /** Custom AID for this game's friend-tap protocol; must match apduservice.xml's aid-group. */
    public static final String AID = "F0464D49434849" ; // "F0" RID prefix + "FMICHI" ASCII

    private static final byte[] SELECT_APDU_HEADER = {(byte) 0x00, (byte) 0xA4, 0x04, 0x00};
    private static final byte[] SW_OK = {(byte) 0x90, 0x00};
    private static final byte[] SW_NOT_READY = {0x6A, (byte) 0x82}; // file/data not found

    /**
     * Payload set by {@link androidlauncher.nfc.NfcFriendServiceImpl#setEmulatedPayload}; static
     * because Android instantiates HCE services fresh per binding and there is exactly one
     * identity per device install.
     */
    static volatile byte[] currentPayload = null;

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null || commandApdu.length < SELECT_APDU_HEADER.length) return SW_NOT_READY;
        boolean isSelect = Arrays.equals(
                Arrays.copyOf(commandApdu, SELECT_APDU_HEADER.length), SELECT_APDU_HEADER);
        if (!isSelect) return SW_NOT_READY;

        byte[] payload = currentPayload;
        if (payload == null) return SW_NOT_READY;

        byte[] response = new byte[payload.length + SW_OK.length];
        System.arraycopy(payload, 0, response, 0, payload.length);
        System.arraycopy(SW_OK, 0, response, payload.length, SW_OK.length);
        return response;
    }

    @Override
    public void onDeactivated(int reason) {
        // No per-session state to clean up — currentPayload is long-lived (set once the player's
        // friend_id/username are known) and reused across taps.
    }

    static byte[] encode(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}

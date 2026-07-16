package androidlauncher.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Emulates a standard NFC Forum Type-4 Tag (the real NDEF tag application, distinct from
 * FriendHceService's custom-AID single-APDU exchange), this is what's actually required for
 * Android's tag-dispatch system to cold-launch this app on tap, via an Android Application Record
 * (AAR) inside the served NDEF message. Always active (registered once in AndroidManifest.xml,
 * like FriendHceService) rather than toggled with hosting state: the served message is a static
 * AAR-only NDEF record that only ever means "tap to open Michi's Adventure", it deliberately
 * carries no live host MAC/session token/map id, since a cold-launched app has no code running
 * yet to consume a rich payload anyway. The real handoff (host MAC/token/map id) always happens
 * afterward over FriendHceService's custom-AID channel, once the app is actually running and back
 * in NFC reader mode, see androidlauncher.AndroidLauncher's NFC-launch auto-continuation.
 *
 * <p>Why two separate HCE services: a real NDEF Type-4 Tag requires implementing the standard
 * SELECT NDEF-app-AID -&gt; SELECT CC file -&gt; READ BINARY -&gt; SELECT NDEF file -&gt; READ BINARY
 * round-trip sequence (this class), which Android's tag dispatcher specifically recognizes and
 * parses for an AAR to auto-launch a closed app. That parsing does NOT happen for a custom AID
 * hence FriendHceService's simpler one-shot exchange works for an already-open app (foreground
 * reader mode, no dispatch/AAR involved) but can't cold-launch anything.
 */
public class Ndef4Service extends HostApduService {

    // Standard NFC Forum-assigned AID for the NDEF Tag Application (ISO/IEC 7816-4).
    private static final byte[] NDEF_APP_AID = hex("D2760000850101");
    // Fixed file identifiers used by every Type-4 Tag implementation (NFC Forum Type 4 Tag spec).
    private static final byte[] CC_FILE_ID   = hex("E103");
    private static final byte[] NDEF_FILE_ID = hex("E104");

    private static final byte[] SW_OK             = {(byte) 0x90, 0x00};
    private static final byte[] SW_FILE_NOT_FOUND = {0x6A, (byte) 0x82};
    private static final byte[] SW_WRONG_PARAMS   = {0x6A, (byte) 0x86};

    private static final byte[] SELECT_HEADER = {0x00, (byte) 0xA4};
    private static final byte[] READ_BINARY_HEADER = {0x00, (byte) 0xB0};

    /** Built once, the served message never changes (see class doc: AAR-only, no live data). */
    private static final byte[] NDEF_MESSAGE = buildAarOnlyMessage("com.michi.adventure");

    private enum Selected { NONE, CC, NDEF }
    private Selected selected = Selected.NONE;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return SW_WRONG_PARAMS;

        if (matches(apdu, SELECT_HEADER)) {
            return handleSelect(apdu);
        }
        if (matches(apdu, READ_BINARY_HEADER)) {
            return handleReadBinary(apdu);
        }
        return SW_WRONG_PARAMS;
    }

    private byte[] handleSelect(byte[] apdu) {
        byte p1 = apdu[2];
        if (p1 == 0x04) { // SELECT by AID (application/DF)
            byte[] aid = extractData(apdu);
            if (Arrays.equals(aid, NDEF_APP_AID)) {
                selected = Selected.NONE; // app selected, but no file yet
                return SW_OK;
            }
            return SW_FILE_NOT_FOUND;
        }
        if (p1 == 0x00) { // SELECT by file ID (EF)
            byte[] fileId = extractData(apdu);
            if (Arrays.equals(fileId, CC_FILE_ID))   { selected = Selected.CC;   return SW_OK; }
            if (Arrays.equals(fileId, NDEF_FILE_ID)) { selected = Selected.NDEF; return SW_OK; }
            return SW_FILE_NOT_FOUND;
        }
        return SW_WRONG_PARAMS;
    }

    private byte[] handleReadBinary(byte[] apdu) {
        if (apdu.length < 5) return SW_WRONG_PARAMS;
        int offset = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
        int le = apdu[4] & 0xFF;
        if (le == 0) le = 256;

        byte[] file = switch (selected) {
            case CC -> ccFile();
            case NDEF -> ndefFileWithLength();
            case NONE -> null;
        };
        if (file == null) return SW_FILE_NOT_FOUND;
        if (offset >= file.length) return SW_WRONG_PARAMS;

        int end = Math.min(file.length, offset + le);
        byte[] body = Arrays.copyOfRange(file, offset, end);
        byte[] response = new byte[body.length + SW_OK.length];
        System.arraycopy(body, 0, response, 0, body.length);
        System.arraycopy(SW_OK, 0, response, body.length, SW_OK.length);
        return response;
    }

    @Override
    public void onDeactivated(int reason) {
        selected = Selected.NONE;
    }

    /** Fixed Capability Container: one NDEF file (E104), max read/write sizes, mapping version 2.0. */
    private static byte[] ccFile() {
        byte[] ndef = ndefFileWithLength();
        int ndefLen = ndef.length;
        return new byte[]{
                0x00, 0x0F,                         // CCLEN = 15 bytes
                0x20,                                // Mapping version 2.0
                0x00, (byte) 0x3B,                   // MLe (max R-APDU data size, 59)
                0x00, (byte) 0x34,                   // MLc (max C-APDU data size, 52)
                0x04,                                // NDEF File Control TLV tag
                0x06,                                // TLV length
                NDEF_FILE_ID[0], NDEF_FILE_ID[1],
                (byte) ((ndefLen - 2 >> 8) & 0xFF), (byte) ((ndefLen - 2) & 0xFF), // max NDEF file size
                0x00,                                // read access: free
                (byte) 0xFF,                         // write access: none (read-only tag)
        };
    }

    /** NDEF file = 2-byte big-endian NLEN prefix + the NDEF message itself. */
    private static byte[] ndefFileWithLength() {
        byte[] msg = NDEF_MESSAGE;
        byte[] out = new byte[2 + msg.length];
        out[0] = (byte) ((msg.length >> 8) & 0xFF);
        out[1] = (byte) (msg.length & 0xFF);
        System.arraycopy(msg, 0, out, 2, msg.length);
        return out;
    }

    /** Single-record NDEF message: just the Android Application Record, see class doc. */
    private static byte[] buildAarOnlyMessage(String packageName) {
        return ndefRecord((byte) 0x04 /* TNF_EXTERNAL_TYPE */,
                "android.com:pkg".getBytes(StandardCharsets.US_ASCII),
                packageName.getBytes(StandardCharsets.UTF_8), true, true);
    }

    private static byte[] ndefRecord(byte tnf, byte[] type, byte[] payload, boolean isFirst, boolean isLast) {
        byte header = tnf;
        header |= (byte) 0x10; // SR (short record, payload length fits one byte)
        if (isFirst) header |= (byte) 0x80; // MB (message begin)
        if (isLast)  header |= (byte) 0x40; // ME (message end)

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(type.length);
        out.write(payload.length);
        out.writeBytes(type);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static boolean matches(byte[] apdu, byte[] header) {
        if (apdu.length < header.length) return false;
        for (int i = 0; i < header.length; i++) if (apdu[i] != header[i]) return false;
        return true;
    }

    /** Lc-prefixed data field of a SELECT APDU: apdu[4] = Lc, data follows. */
    private static byte[] extractData(byte[] apdu) {
        if (apdu.length < 5) return new byte[0];
        int lc = apdu[4] & 0xFF;
        if (apdu.length < 5 + lc) return new byte[0];
        return Arrays.copyOfRange(apdu, 5, 5 + lc);
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }
}

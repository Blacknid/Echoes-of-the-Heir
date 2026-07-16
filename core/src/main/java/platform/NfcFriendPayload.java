package platform;

/**
 * Wire format for the NDEF payload exchanged on an add-friend NFC tap. Deliberately NOT JSON to
 * keep the emulated NDEF record tiny (single-frame APDU), a versioned pipe-delimited record.
 * Never includes the license key; friend_id is a separate opaque per-account token issued by the
 * friends server (see SERVERS/save_server/server.py GET_MY_FRIEND_ID).
 */
public final class NfcFriendPayload {
    private NfcFriendPayload() {}

    private static final String PREFIX = "MICHIFRIEND1|";

    /** Builds the payload string embedded in this device's emulated NDEF record. */
    public static String encode(String friendId, String username) {
        String safeFriendId = friendId == null ? "" : friendId.replace("|", "");
        String safeUsername = username == null ? "" : username.replace("|", "");
        return PREFIX + safeFriendId + "|" + safeUsername;
    }

    public record Decoded(String friendId, String username) {}

    /** Parses a payload read from another device's tag, or null if malformed/wrong version. */
    public static Decoded decode(String payload) {
        if (payload == null || !payload.startsWith(PREFIX)) return null;
        String body = payload.substring(PREFIX.length());
        int sep = body.indexOf('|');
        if (sep < 0) return null;
        String friendId = body.substring(0, sep);
        String username = body.substring(sep + 1);
        if (friendId.isEmpty() || username.isEmpty()) return null;
        return new Decoded(friendId, username);
    }
}

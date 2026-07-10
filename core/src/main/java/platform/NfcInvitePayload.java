package platform;

/**
 * Wire format for the NDEF payload exchanged on a BLE-session invite tap (pause menu's INVITE
 * PLAYER). Reuses the exact same tap channel as {@link NfcFriendPayload} (see
 * NfcFriendService#setEmulatedPayloadRaw) but with a distinct prefix so a reader can tell the two
 * apart. Deliberately compact (pipe-delimited, not JSON) to stay well under the ~200-byte safe
 * single-APDU budget the HCE exchange is built around (see FriendHceService's class doc).
 *
 * <p>Carries a random per-session token (proves this guest was actually tapped rather than just
 * happening to be first through the door — see BleGuestService's scan-based join), the active map
 * id (so the guest loads the identical map file it already has locally — see
 * main.BleMultiplayerSession's class doc for why no map data is transmitted), and the host's
 * display name for the guest's join UI. Deliberately does NOT carry a Bluetooth address: Android
 * has returned a fixed placeholder from {@code BluetoothAdapter.getAddress()} to every
 * non-privileged app since API 23, so a "real" address was never actually obtainable to embed here
 * — the guest instead finds the host via a filtered BLE scan on the fixed service UUID.
 */
public final class NfcInvitePayload {
    private NfcInvitePayload() {}

    private static final String PREFIX = "MICHIINVITE2|";

    public static String encode(String sessionToken, String mapId, String hostName) {
        String token = safe(sessionToken);
        String map = safe(mapId);
        String name = safe(hostName);
        return PREFIX + token + "|" + map + "|" + name;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("|", "");
    }

    public record Decoded(String sessionToken, String mapId, String hostName) {}

    /** Parses a payload read from another device's tag, or null if malformed/wrong version. */
    public static Decoded decode(String payload) {
        if (payload == null || !payload.startsWith(PREFIX)) return null;
        String[] parts = payload.substring(PREFIX.length()).split("\\|", -1);
        if (parts.length != 3) return null;
        if (parts[0].isEmpty() || parts[1].isEmpty()) return null;
        return new Decoded(parts[0], parts[1], parts[2]);
    }
}

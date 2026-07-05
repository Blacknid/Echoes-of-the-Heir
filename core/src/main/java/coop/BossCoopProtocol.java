package coop;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Wire format for LAN (desktop, {@link BossCoopHostServer}/{@link BossCoopClient}) and Bluetooth
 * (mobile, {@code androidlauncher.coop.*}) boss-co-op transports alike — both platforms share this
 * exact message shape so {@link LoadoutSerializer}/{@link PlayerLoadout}/{@link RewardingHelpers}
 * work unmodified regardless of which socket/GATT plumbing carried the bytes. Deliberately
 * lightweight compared to the dedicated multiplayer server's RSA/AES-GCM protocol — per design,
 * this never exchanges license keys (a friend's session shouldn't need to see or verify your
 * license), so trust instead comes from:
 *
 * <ol>
 *   <li>the friendship check already done against the save server before an invite is even shown,</li>
 *   <li>a random per-session token minted by the host at invite-time and handed to the specific
 *       invited friend out-of-band — over the mDNS-discovered invite on desktop, or over the NFC
 *       tap on mobile (a SEPARATE, freshly-minted token from the one used for friend-pairing, per
 *       the "must re-tap every session" design) — which the joiner must present on connect. A
 *       stranger who wasn't invited/tapped never sees this token.</li>
 * </ol>
 *
 * <p>Messages are newline-terminated (LAN) or length-framed (Bluetooth, see the mobile transport's
 * own chunking) JSON-ish strings, hand-built/parsed the same way {@code main.MultiplayerClient}
 * does for its own (encrypted) payloads — no JSON library dependency, consistent with the rest of
 * this codebase's networking code.
 */
public final class BossCoopProtocol {
    private BossCoopProtocol() {}

    public static final int DEFAULT_PORT = 58326; // arbitrary, unregistered range (LAN transport only)
    public static final int MAX_PLAYERS = 4;

    // Client -> host
    public static final String CMD_JOIN = "JOIN";       // {"cmd":"JOIN","token":"...","username":"...","level":N}
    public static final String CMD_LEAVE = "LEAVE";     // {"cmd":"LEAVE"}

    // Host -> client
    public static final String CMD_WAITING = "WAITING";   // {"cmd":"WAITING","players":["Alice","Bob"]}
    public static final String CMD_START = "START";       // {"cmd":"START","playerCount":N,"loadout":"<json>"}
    public static final String CMD_BOSS_DEFEATED = "BOSS_DEFEATED"; // {"cmd":"BOSS_DEFEATED","bossId":1,"rewardExp":N}
    public static final String CMD_SESSION_ENDED = "SESSION_ENDED"; // {"cmd":"SESSION_ENDED","reason":"..."}
    public static final String CMD_REJECTED = "REJECTED"; // {"cmd":"REJECTED","reason":"..."}

    public static String randomToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── Tiny JSON helpers, mirroring main.MultiplayerClient's manual scanner style ──

    public static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int i = json.indexOf(search);
        if (i < 0) return null;
        int start = i + search.length();
        int end = json.indexOf('"', start);
        while (end > start && json.charAt(end - 1) == '\\') end = json.indexOf('"', end + 1);
        return end < 0 ? null : json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public static int extractInt(String json, String key, int fallback) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return fallback;
        int start = i + search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static java.util.List<String> extractStringArray(String json, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String search = "\"" + key + "\":[";
        int i = json.indexOf(search);
        if (i < 0) return out;
        int p = i + search.length();
        while (p < json.length() && json.charAt(p) != ']') {
            if (json.charAt(p) == '"') {
                int start = ++p;
                while (p < json.length() && json.charAt(p) != '"') p++;
                out.add(json.substring(start, p));
            }
            p++;
        }
        return out;
    }

    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                default   -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Finds the matching closing brace for the '{' at {@code start}; returns the substring including both braces. */
    public static String extractJsonObject(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return s.substring(start);
    }
}

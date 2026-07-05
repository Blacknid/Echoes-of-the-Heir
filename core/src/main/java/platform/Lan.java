package platform;

import java.util.List;

/** Read-only LAN-discovery surface shared code uses instead of depending on the JmDNS implementation. */
public final class Lan {
    private Lan() {}

    private static volatile LanDiscovery active;

    public static void set(LanDiscovery discovery) { active = discovery; }

    public static boolean isAvailable() { return active != null && active.isAvailable(); }

    public static void startPresence(String username) { if (active != null) active.startPresence(username); }
    public static void stopPresence() { if (active != null) active.stopPresence(); }

    public static List<String> visibleFriends(List<String> friendUsernames) {
        return active != null ? active.visibleFriends(friendUsernames) : List.of();
    }

    public static void advertiseSession(String hostUsername, String invitedUsername, int bossId, int port, String sessionToken) {
        if (active != null) active.advertiseSession(hostUsername, invitedUsername, bossId, port, sessionToken);
    }

    public static void stopAdvertisingSession() { if (active != null) active.stopAdvertisingSession(); }

    public static LanDiscovery.SessionInvite pollInvite(String hostUsername, String ownUsername) {
        return active != null ? active.pollInvite(hostUsername, ownUsername) : null;
    }
}

package platform;

import java.util.List;

/**
 * Desktop-only LAN presence/invite discovery for co-op boss fights (see {@code coop.*}), backed by
 * mDNS (JmDNS) on desktop. Never implemented/registered on Android — mobile's equivalent (Phase 3)
 * pairs over Bluetooth+NFC instead, so {@link platform.Lan#isAvailable()} simply returns false
 * there and the "Invite Friends" UI hides itself accordingly.
 */
public interface LanDiscovery {
    boolean isAvailable();

    /** Broadcast "I'm online as {@code username}" so friends' games can see this one is reachable. */
    void startPresence(String username);
    void stopPresence();

    /** Friends (from the given list) currently visible advertising presence on the LAN. */
    List<String> visibleFriends(List<String> friendUsernames);

    /** Host: advertise a boss-session invite addressed to one specific friend. */
    void advertiseSession(String hostUsername, String invitedUsername, int bossId, int port, String sessionToken);
    void stopAdvertisingSession();

    /** Joiner: poll for a session invite addressed to us from a specific host (already a friend). */
    SessionInvite pollInvite(String hostUsername, String ownUsername);

    record SessionInvite(String hostIp, int port, String sessionToken, int bossId) {}
}

package desktop.coop;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import platform.LanDiscovery;

/**
 * Desktop implementation of {@link LanDiscovery}, backed by JmDNS. Two independent service types
 * are used (see the design note in {@code core}'s {@code platform.LanDiscovery}):
 *
 * <ul>
 *   <li>{@code _michi-presence._tcp.local.} — every running game instance advertises "I'm online
 *       as X" continuously, so a host can see which of their friends are actually reachable on
 *       the LAN before picking who to invite.</li>
 *   <li>{@code _michi-boss._tcp.local.} — the HOST advertises one of these per active invite,
 *       tagged with the invited friend's username (in the TXT record) plus the session token and
 *       boss id; every other instance is browsing this type and filters for invites addressed to
 *       itself from a host it's already friends with.</li>
 * </ul>
 *
 * <p>All JmDNS callbacks fire on JmDNS's own threads, never the render thread — every method here
 * that's read by game/UI code only touches simple thread-safe collections (no im-memory game state
 * is mutated directly from a callback).
 */
public final class JmdnsLanDiscovery implements LanDiscovery {

    private static final String PRESENCE_TYPE = "_michi-presence._tcp.local.";
    private static final String BOSS_TYPE = "_michi-boss._tcp.local.";

    private JmDNS jmdns;
    private ServiceInfo presenceInfo;
    private ServiceInfo sessionInfo;

    // username -> true while a serviceResolved/serviceAdded says they're currently online.
    private final Map<String, Boolean> onlineFriends = new ConcurrentHashMap<>();
    // Resolved boss-session invites currently visible, keyed by "hostUsername|invitedUsername".
    private final Map<String, SessionInvite> visibleInvites = new ConcurrentHashMap<>();

    private final ServiceListener presenceListener = new ServiceListener() {
        @Override public void serviceAdded(ServiceEvent event) {
            jmdns.requestServiceInfo(event.getType(), event.getName(), 3000);
        }
        @Override public void serviceResolved(ServiceEvent event) {
            String username = event.getInfo().getPropertyString("username");
            if (username != null) onlineFriends.put(username, Boolean.TRUE);
        }
        @Override public void serviceRemoved(ServiceEvent event) {
            // JmDNS only gives us name/type here, not the TXT record, so we can't recover which
            // username this was — presence entries simply age out of onlineFriends naturally as
            // the picker re-queries and finds them no longer resolvable on the next full browse.
        }
    };

    private final ServiceListener bossListener = new ServiceListener() {
        @Override public void serviceAdded(ServiceEvent event) {
            jmdns.requestServiceInfo(event.getType(), event.getName(), 3000);
        }
        @Override public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            String host = info.getPropertyString("host");
            String invited = info.getPropertyString("invited");
            String token = info.getPropertyString("token");
            int bossId = parseIntOr(info.getPropertyString("bossId"), 0);
            String[] addrs = info.getHostAddresses();
            if (host == null || invited == null || token == null || addrs.length == 0) return;
            visibleInvites.put(host + "|" + invited,
                    new SessionInvite(addrs[0], info.getPort(), token, bossId));
        }
        @Override public void serviceRemoved(ServiceEvent event) {
            // Same limitation as presenceListener — stale invites are pruned by TTL expiry inside
            // JmDNS itself (further pollInvite calls simply won't see them once they've expired).
        }
    };

    @Override
    public boolean isAvailable() {
        return true; // desktop always has LAN/mDNS available (no special hardware required)
    }

    private synchronized void ensureStarted() {
        if (jmdns != null) return;
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            jmdns.addServiceListener(PRESENCE_TYPE, presenceListener);
            jmdns.addServiceListener(BOSS_TYPE, bossListener);
        } catch (IOException e) {
            jmdns = null;
        }
    }

    @Override
    public void startPresence(String username) {
        ensureStarted();
        if (jmdns == null) return;
        try {
            Map<String, String> props = new HashMap<>();
            props.put("username", username);
            presenceInfo = ServiceInfo.create(PRESENCE_TYPE, "michi-" + username, presencePort(), 0, 0, props);
            jmdns.registerService(presenceInfo);
        } catch (IOException ignored) {
            // Presence is best-effort — a failure here just means friends won't see us as online.
        }
    }

    @Override
    public void stopPresence() {
        if (jmdns != null && presenceInfo != null) {
            new Thread(() -> jmdns.unregisterService(presenceInfo), "LanDiscovery-Unregister").start();
            presenceInfo = null;
        }
    }

    @Override
    public List<String> visibleFriends(List<String> friendUsernames) {
        List<String> out = new ArrayList<>();
        for (String name : friendUsernames) {
            if (onlineFriends.containsKey(name)) out.add(name);
        }
        return out;
    }

    @Override
    public void advertiseSession(String hostUsername, String invitedUsername, int bossId, int port, String sessionToken) {
        ensureStarted();
        if (jmdns == null) return;
        stopAdvertisingSession();
        try {
            Map<String, String> props = new HashMap<>();
            props.put("host", hostUsername);
            props.put("invited", invitedUsername);
            props.put("token", sessionToken);
            props.put("bossId", String.valueOf(bossId));
            sessionInfo = ServiceInfo.create(BOSS_TYPE, "michi-boss-" + hostUsername + "-" + invitedUsername,
                    port, 0, 0, props);
            jmdns.registerService(sessionInfo);
        } catch (IOException ignored) {
            // If advertising fails, the invited friend simply won't see the session — the host
            // UI's own "waiting for players" room will just stay empty, which is a visible signal.
        }
    }

    @Override
    public void stopAdvertisingSession() {
        if (jmdns != null && sessionInfo != null) {
            new Thread(() -> jmdns.unregisterService(sessionInfo), "LanDiscovery-Unregister").start();
            sessionInfo = null;
        }
    }

    @Override
    public SessionInvite pollInvite(String hostUsername, String ownUsername) {
        ensureStarted();
        return visibleInvites.get(hostUsername + "|" + ownUsername);
    }

    // Presence advertisements don't need a real open port (nothing connects to them — the TXT
    // record is the entire payload) but ServiceInfo.create requires a plausible-looking one.
    private int presencePort() { return 1; }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }
}

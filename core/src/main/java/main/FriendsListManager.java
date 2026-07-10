package main;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import data.CloudSaveService;
import platform.GameStorage;

/**
 * Client-side view of the server-side friends system (SERVERS/save_server/server.py). Friends,
 * usernames and pending requests all live in the server's SQLite database keyed by license_key —
 * this class only caches the last-fetched lists in memory for drawing while a network call is
 * in flight or the server is briefly unreachable.
 *
 * <h2>Offline NFC adds</h2>
 * An NFC add-friend tap (see platform.NfcFriendPayload) already carries the sender's username in
 * the tag itself, so unlike the typed add-friend flow, adding by NFC does NOT require a live
 * server round-trip to know who was tapped. {@link #addFriendByNfc} therefore shows the tapped
 * username in the friends list immediately (marked "syncing" — see {@link #getPendingLocalAdds()})
 * and persists it to a small local queue file ({@link #PENDING_ADDS_FILE}) so it survives an app
 * restart before the actual SEND_FRIEND_REQUEST reaches the server. {@link #retryPendingAdds()}
 * drains that queue whenever the server is reachable — the constructor registers it on the
 * shared CloudSaveService's heartbeat (see CloudSaveService#setOnReconnect) so a queued add syncs
 * within one heartbeat tick of connectivity returning, even if the player never reopens Friends.
 */
public class FriendsListManager {

    private static final String PENDING_ADDS_FILE = "pending_friend_adds.txt";

    private final CloudSaveService cloudSaveService;

    private final ArrayList<String> friends = new ArrayList<>();
    private final ArrayList<String> pendingRequests = new ArrayList<>();
    /** Usernames added by NFC tap but not yet confirmed sent to the server. Persisted to disk. */
    private final LinkedHashSet<String> pendingLocalAdds = new LinkedHashSet<>(loadPendingAdds());
    private volatile boolean retryInFlight = false;

    private volatile String claimedUsername;
    private volatile boolean usernameChecked = false;

    /** Opaque per-account token embedded in this player's outgoing NFC add-friend tag; see server's friend_id. */
    private volatile String myFriendId;

    public FriendsListManager(CloudSaveService cloudSaveService) {
        this.cloudSaveService = cloudSaveService;
        cloudSaveService.setOnReconnect(this::retryPendingAdds);
    }

    public ArrayList<String> getFriends() {
        return friends;
    }

    public ArrayList<String> getPendingRequests() {
        return pendingRequests;
    }

    /** Usernames added by NFC tap that haven't reached the server yet — see class doc. */
    public List<String> getPendingLocalAdds() {
        synchronized (pendingLocalAdds) { return new ArrayList<>(pendingLocalAdds); }
    }

    /** Username this player has claimed on the friends server, or null if none/not yet checked. */
    public String getClaimedUsername() {
        return claimedUsername;
    }

    public boolean isUsernameChecked() {
        return usernameChecked;
    }

    /** Refreshes both friends and pending-request lists from the server. Safe to call from any thread. */
    public void refresh() {
        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null) return;

        CloudSaveService.FriendResult friendsResult = cloudSaveService.listFriends(licenseKey);
        if (friendsResult.ok()) {
            synchronized (friends) {
                friends.clear();
                friends.addAll(friendsResult.names());
            }
        }

        CloudSaveService.FriendResult requestsResult = cloudSaveService.listFriendRequests(licenseKey);
        if (requestsResult.ok()) {
            synchronized (pendingRequests) {
                pendingRequests.clear();
                pendingRequests.addAll(requestsResult.names());
            }
        }
    }

    /**
     * Attempts to claim {@code username} for this license on the friends server.
     * @return server status: "CLAIMED", "TAKEN", "INVALID", "NO_SERVER", "NO_LICENSE", or "ERROR".
     */
    public String claimUsername(String username) {
        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null) return "NO_LICENSE";
        CloudSaveService.FriendResult result = cloudSaveService.claimUsername(licenseKey, username);
        usernameChecked = true;
        if ("CLAIMED".equals(result.status())) {
            claimedUsername = username.trim();
        }
        return result.status();
    }

    /**
     * @return server status: "SENT", "ALREADY_FRIENDS", "ALREADY_PENDING", "NOT_FOUND", "SELF",
     * "INVALID", "NO_SERVER", "NO_LICENSE", or "ERROR".
     */
    public String sendFriendRequest(String username) {
        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null || username == null || username.isBlank()) return "NO_LICENSE";
        return cloudSaveService.sendFriendRequest(licenseKey, username.trim()).status();
    }

    /**
     * This player's own friend_id, fetched (and cached) from the server on first use. Embedded in
     * the NDEF payload this device's NFC HCE service emits so another player's tap can resolve it
     * back to a username server-side — see platform.NfcFriendService.
     * @return the friend_id, or null if unavailable (no license/server, or no username claimed yet).
     */
    public String getMyFriendId() {
        if (myFriendId != null) return myFriendId;
        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null) return null;
        CloudSaveService.FriendIdResult result = cloudSaveService.getMyFriendId(licenseKey);
        if (result.ok()) myFriendId = result.value();
        return myFriendId;
    }

    /**
     * Adds a friend from a decoded NFC tap. The tag already carries the sender's username (see
     * platform.NfcFriendPayload), so this never blocks on a server round-trip: the username shows
     * up in the friends list immediately as "syncing" ({@link #getPendingLocalAdds()}), the tap is
     * queued to disk, and an immediate send is attempted if the server looks reachable right now.
     * friend_id isn't used to resolve identity — it's not needed since the payload already names
     * the account — but is kept in the caller for future anti-spoof verification if ever needed.
     * @return "QUEUED" (always, since the add is accepted locally regardless of connectivity)
     */
    public String addFriendByNfc(String friendId, String username) {
        if (username == null || username.isBlank()) return "INVALID";
        String trimmed = username.trim();
        synchronized (pendingLocalAdds) { pendingLocalAdds.add(trimmed); }
        savePendingAdds();
        retryPendingAdds();
        return "QUEUED";
    }

    /**
     * Attempts to send every queued NFC-add username to the server. Safe to call often (e.g. once
     * per title-screen tick) — no-ops immediately if the queue is empty, a license isn't ready, or
     * a retry is already running. Successful sends are removed from the queue and folded into
     * {@link #refresh()}'s result; failures stay queued for the next call.
     */
    public void retryPendingAdds() {
        if (retryInFlight) return;
        List<String> snapshot;
        synchronized (pendingLocalAdds) { snapshot = new ArrayList<>(pendingLocalAdds); }
        if (snapshot.isEmpty()) return;
        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null) return;

        retryInFlight = true;
        new Thread(() -> {
            try {
                boolean anySent = false;
                for (String username : snapshot) {
                    String status = cloudSaveService.sendFriendRequest(licenseKey, username).status();
                    // Any of these mean the server now knows about the request (or already did) —
                    // the local placeholder has served its purpose and can be dropped either way.
                    boolean resolved = "SENT".equals(status) || "ALREADY_FRIENDS".equals(status)
                            || "ALREADY_PENDING".equals(status) || "SELF".equals(status)
                            || "NOT_FOUND".equals(status) || "INVALID".equals(status);
                    if (resolved) {
                        synchronized (pendingLocalAdds) { pendingLocalAdds.remove(username); }
                        anySent = true;
                    }
                    // NO_SERVER/ERROR: leave queued, try again next call.
                }
                if (anySent) {
                    savePendingAdds();
                    refresh();
                }
            } finally {
                retryInFlight = false;
            }
        }, "Friends-Nfc-Retry").start();
    }

    private void savePendingAdds() {
        List<String> snapshot;
        synchronized (pendingLocalAdds) { snapshot = new ArrayList<>(pendingLocalAdds); }
        try (java.io.BufferedWriter w = GameStorage.bufferedWriter(PENDING_ADDS_FILE)) {
            for (String username : snapshot) {
                w.write(username);
                w.newLine();
            }
        } catch (Exception e) {
            System.out.println("[Friends] Failed to persist pending NFC adds: " + e.getMessage());
        }
    }

    private static Set<String> loadPendingAdds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!GameStorage.exists(PENDING_ADDS_FILE)) return out;
        try (java.io.BufferedReader r = GameStorage.bufferedReader(PENDING_ADDS_FILE)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) out.add(line.trim());
            }
        } catch (Exception e) {
            System.out.println("[Friends] Failed to load pending NFC adds: " + e.getMessage());
        }
        return out;
    }

    /** Accepts or declines a pending incoming request, then refreshes the local caches. */
    public String respondFriendRequest(String username, boolean accept) {
        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null) return "NO_LICENSE";
        String status = cloudSaveService.respondFriendRequest(licenseKey, username, accept).status();
        if ("ACCEPTED".equals(status) || "DECLINED".equals(status)) refresh();
        return status;
    }

    /** Removes an accepted friend by name, then refreshes the local cache. */
    public void removeFriend(int index) {
        List<String> snapshot;
        synchronized (friends) { snapshot = new ArrayList<>(friends); }
        if (index < 0 || index >= snapshot.size()) return;
        String username = snapshot.get(index);

        String licenseKey = Main.LICENSE_KEY;
        if (licenseKey == null) return;
        cloudSaveService.removeFriend(licenseKey, username);
        synchronized (friends) { friends.remove(username); }
    }
}

package data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import platform.GameStorage;

/**
 * Offline queue for friend requests captured via an NFC tap (see {@link platform.NfcPairing}).
 * A tap only ever reads a username locally — it can't reach the save server itself if the phone
 * has no connectivity at that moment — so the username is queued here and flushed the next time
 * {@link #flush} is called with a reachable {@link SaveLoad}. One username per line, de-duplicated
 * on both add and flush so a repeat tap (or a retry after a partial failure) can't double-queue.
 */
public final class PendingNfcFriendRequests {

    private static final String FILE_NAME = "pending_nfc_friend_requests.txt";

    private PendingNfcFriendRequests() {}

    public static synchronized void add(String username) {
        if (username == null || username.isBlank()) return;
        LinkedHashSet<String> pending = readAll();
        pending.add(username.trim());
        writeAll(pending);
    }

    /** Attempt to send every queued request; anything that still fails (offline/server error) stays queued. */
    public static synchronized List<CloudSaveService.FriendResult> flush(SaveLoad saveLoad) {
        LinkedHashSet<String> pending = readAll();
        List<CloudSaveService.FriendResult> results = new ArrayList<>();
        if (pending.isEmpty()) return results;

        LinkedHashSet<String> stillPending = new LinkedHashSet<>();
        for (String username : pending) {
            CloudSaveService.FriendResult result = saveLoad.sendFriendRequest(username);
            results.add(result);
            // "No server reachable" is the only retryable failure — every other failure (already
            // friends, not found, self, invalid) is a permanent outcome for that username.
            if (!result.ok() && result.message() != null && result.message().contains("No server reachable")) {
                stillPending.add(username);
            }
        }
        writeAll(stillPending);
        return results;
    }

    public static synchronized boolean hasPending() {
        return !readAll().isEmpty();
    }

    private static LinkedHashSet<String> readAll() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!GameStorage.exists(FILE_NAME)) return out;
        try (var reader = GameStorage.bufferedReader(FILE_NAME)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (IOException ignored) {
            // Corrupt/unreadable queue file — treat as empty rather than blocking the game.
        }
        return out;
    }

    private static void writeAll(LinkedHashSet<String> usernames) {
        if (usernames.isEmpty()) { GameStorage.delete(FILE_NAME); return; }
        try (var writer = GameStorage.bufferedWriter(FILE_NAME)) {
            for (String username : usernames) {
                writer.write(username);
                writer.newLine();
            }
        } catch (IOException ignored) {
            // Best-effort persistence — worst case the queue is retried again next flush.
        }
    }
}

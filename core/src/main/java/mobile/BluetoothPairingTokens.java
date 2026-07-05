package mobile;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import platform.GameStorage;

/**
 * Local store for the Bluetooth pairing tokens exchanged over NFC (see {@link platform.NfcPairing}).
 *
 * <p>A token is a random per-device secret, unrelated to the phone's actual Bluetooth MAC/identity.
 * It exists purely so Phase 3 (Bluetooth boss co-op) can refuse a game-level connection from any
 * peer who hasn't proven — by having read it here — that an NFC tap actually happened between the
 * two specific devices, regardless of what the OS Bluetooth stack itself allows. The token rotates
 * every time a new NFC session starts, so a stale/leaked token from a past tap stops being useful
 * for a future connection attempt.
 */
public final class BluetoothPairingTokens {

    private static final String OWN_TOKEN_FILE = "bt_pairing_token.txt";
    private static final String PEER_TOKENS_FILE = "bt_pairing_peers.txt";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BluetoothPairingTokens() {}

    /** Fresh random token for this NFC session; overwrites whatever was cached from a previous tap. */
    public static synchronized String rotateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (var writer = GameStorage.bufferedWriter(OWN_TOKEN_FILE)) {
            writer.write(token);
        } catch (IOException ignored) {
            // Worst case the token isn't persisted across a restart mid-session; still usable in-memory.
        }
        return token;
    }

    /** The current session's own token, minting one if none exists yet. */
    public static synchronized String currentToken() {
        if (GameStorage.exists(OWN_TOKEN_FILE)) {
            try (var reader = GameStorage.bufferedReader(OWN_TOKEN_FILE)) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) return line.trim();
            } catch (IOException ignored) {
                // Fall through to minting a new one below.
            }
        }
        return rotateToken();
    }

    /** Remember the token a specific friend handed us over NFC, keyed by their username. */
    public static synchronized void rememberPeer(String username, String token) {
        if (username == null || username.isBlank() || token == null || token.isBlank()) return;
        Map<String, String> peers = readPeers();
        peers.put(username.trim(), token.trim());
        writePeers(peers);
    }

    /** The last NFC-exchanged token for this friend, or null if they've never been tapped. */
    public static synchronized String tokenForPeer(String username) {
        return readPeers().get(username);
    }

    private static Map<String, String> readPeers() {
        Map<String, String> out = new HashMap<>();
        if (!GameStorage.exists(PEER_TOKENS_FILE)) return out;
        try (var reader = GameStorage.bufferedReader(PEER_TOKENS_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException ignored) {
            // Corrupt/unreadable — treat as no known peers.
        }
        return out;
    }

    private static void writePeers(Map<String, String> peers) {
        try (var writer = GameStorage.bufferedWriter(PEER_TOKENS_FILE)) {
            for (Map.Entry<String, String> e : peers.entrySet()) {
                writer.write(e.getKey());
                writer.write('\t');
                writer.write(e.getValue());
                writer.newLine();
            }
        } catch (IOException ignored) {
            // Best-effort — Phase 3 will simply see no cached token for this peer and require a re-tap.
        }
    }
}

package data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import platform.GameStorage;

/**
 * Cloud-save client
 *
 * Crypto
 *  - RSA-OAEP-SHA256 for the licensed handshake
 *  - AES-256-GCM (AEAD) for every framed message after the handshake
 *  - HKDF-SHA256 for delivery- and local-key derivation
 *  - Per-direction sequence counters bound into AES-GCM AAD (anti-replay)
 *
 * Server discovery
 *  Reads {@code save_servers.txt} from the game's working directory. One
 *  endpoint per line (host or host:port, # for comments). The client tries
 *  each entry in order and uses the first that responds to {@code PING}.
 *  If the file is missing or empty, falls back to {@link #FALLBACK_HOSTS}.
 *
 * Offline cache
 *  When all save servers are unreachable, the game state is encrypted with a
 *  key derived from {@code (license_key, machine_fingerprint)} and written
 *  to {@code local_save.dat}. No plaintext key file is left on disk. The
 *  cached save is uploaded automatically once a server returns and removed
 *  afterwards.
 */
public class CloudSaveService {

    /** Used only if {@code save_servers.txt} is missing or empty. */
    private static final String[] FALLBACK_HOSTS = { "192.168.137.14", "192.168.137.126", "192.168.1.9" };
    private static final int    DEFAULT_PORT       = 5005;
    private static final int    CONNECT_TIMEOUT_MS = 3000;
    private static final int    SOCKET_TIMEOUT_MS  = 8000;

    /** Allow legacy plaintext-key offline saves to be migrated/decrypted. */
    private static final String LEGACY_AES_KEY_FILE = "local_aes.key";
    private static final String LOCAL_SAVE_FILE     = "local_save.dat";
    private static final String SAVE_SERVERS_FILE   = "save_servers.txt";

    private static final String RSA_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuEwRNiBMB0MkhCI7+3Xs"
          + "fsZZWZMvk4WbgjZk7CBMUwyXSnY6vscwMcWIvlVj6BItyfNJP1PFUaaJgfoOFXl1"
          + "vTn1jKiCBdLC14NhLDeW2E7N1XWD3s4YhXWqc91soETgb2TRpYIqfkfiKwdsKwiH"
          + "XemA4XLgrZWHihbTWrPzb1TANd2a2tQWMDqq7QGMka07Yb2L5CBdaZFaX0iDUvcH"
          + "aN2dOguvkmYWrotrd1GVQtvF/bYIWR19QCKvfQZV++vxEUFhcZdjgXXSewN194VS"
          + "QzrMKiwVogXLUTVGBFt1mmF/fXUjzM8oY4SEKBCoiybHPbwbIJLZ43L54PjmlCRf"
          + "1wIDAQAB";

    private static final String GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String PROTOCOL_TAG = "v2";
    private static final byte[] LICENSE_PEPPER  = "michi-license-pepper-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIVERY_INFO   = "michi-delivery-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIVERY_AAD    = "MichiCloudSession".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LOCAL_INFO      = "michi-local-v2".getBytes(StandardCharsets.UTF_8);

    private static final byte DIR_C2S = 0x02;
    private static final byte DIR_S2C = 0x01;

    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private final AtomicBoolean serverOnline = new AtomicBoolean(false);
    private volatile boolean heartbeatRunning = false;

    private volatile List<Endpoint> serverPool = loadServerPool();
    /** First host that responded to PING. Refreshed by heartbeat. */
    private volatile Endpoint activeEndpoint;

    private volatile boolean pendingUpload = false;
    private volatile String cachedLicenseKey;

    /**
     * Fired from the heartbeat thread whenever a PING succeeds (i.e. potentially "just came back
     * online" — checked every heartbeat, not just on the offline-to-online edge, so a late
     * registration or a listener that itself no-ops when it has nothing to do is still safe).
     * Used by FriendsListManager to retry NFC-queued friend adds without needing its own poll
     * loop; keeps this class itself friends-agnostic.
     */
    private volatile Runnable onReconnect;

    public void setOnReconnect(Runnable onReconnect) { this.onReconnect = onReconnect; }

    public void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;

        Thread heartbeat = new Thread(() -> {
            while (heartbeatRunning) {
                boolean alive = pingPool();
                serverOnline.set(alive);
                if (alive && pendingUpload) {
                    syncPendingLocalSave();
                }
                if (alive && onReconnect != null) {
                    onReconnect.run();
                }
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }, "CloudSave-Heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public void stopHeartbeat() { heartbeatRunning = false; }

    public boolean isServerOnline() { return serverOnline.get(); }

    /** Reload the configured save server list from {@code save_servers.txt}. */
    public void reloadServerPool() {
        this.serverPool = loadServerPool();
    }

    public SaveResult save(GameState state, String licenseKey, boolean offlineMode) {
        state.timestamp = System.currentTimeMillis();
        cachedLicenseKey = licenseKey;

        // Local re-verify: detect on-disk tampering / fp drift before each
        // network attempt. If verifyCurrent() fails, skip cloud and fall
        // through to the offline cache. (No throw — game must keep running.)
        if (licenseKey != null && !platform.License.verifyCurrent()) {
            System.out.println("[CloudSave] License re-verify failed — falling back to offline cache.");
            licenseKey = null;
        }

        if (licenseKey != null && (serverOnline.get() || pingPool())) {
            SaveResult result = uploadToServer(state, licenseKey);
            if (result.ok()) {
                pendingUpload = false;
                deletePendingLocalFiles();
                return result;
            }
        }

        SaveResult local = saveLocalEncrypted(state, licenseKey);
        if (local.ok()) {
            pendingUpload = true;
        }
        return local;
    }

    public DownloadResult download(String licenseKey) {
        cachedLicenseKey = licenseKey;

        if (licenseKey != null && !platform.License.verifyCurrent()) {
            System.out.println("[CloudSave] License re-verify failed — refusing remote download.");
            licenseKey = null;
        }

        if (licenseKey != null && (serverOnline.get() || pingPool())) {
            try {
                String json = downloadFromServer(licenseKey);
                if (json != null) {
                    pendingUpload = false;
                    deletePendingLocalFiles();
                    return DownloadResult.ok(json);
                }
                // null = NO_SAVE on server, fall through to local
            } catch (ConnectException e) {
                serverOnline.set(false);
            } catch (Exception ignored) {
                // fall through to local
            }
        }

        String localJson = loadLocalEncrypted(licenseKey);
        if (localJson != null) return DownloadResult.ok(localJson);
        return DownloadResult.fail("No save found on server or locally.");
    }

    /** One save-server endpoint. */
    public static final class Endpoint {
        public final String host;
        public final int port;
        public Endpoint(String host, int port) { this.host = host; this.port = port; }
        @Override public String toString() { return host + ":" + port; }
    }

    /**
     * Load endpoints from {@code save_servers.txt}, one per line.
     * Format: {@code host} or {@code host:port}, # comments allowed.
     * Falls back to {@link #FALLBACK_HOSTS} if the file is absent or empty.
     */
    private List<Endpoint> loadServerPool() {
        List<Endpoint> list = new ArrayList<>();
        if (GameStorage.exists(SAVE_SERVERS_FILE)) {
            try (BufferedReader br = GameStorage.bufferedReader(SAVE_SERVERS_FILE)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int colon = line.lastIndexOf(':');
                    String host;
                    int port;
                    if (colon > 0 && colon < line.length() - 1) {
                        host = line.substring(0, colon).trim();
                        try {
                            port = Integer.parseInt(line.substring(colon + 1).trim());
                        } catch (NumberFormatException nfe) {
                            host = line;
                            port = DEFAULT_PORT;
                        }
                    } else {
                        host = line;
                        port = DEFAULT_PORT;
                    }
                    if (!host.isEmpty()) list.add(new Endpoint(host, port));
                }
            } catch (IOException e) {
                System.out.println("[CloudSave] Could not read " + SAVE_SERVERS_FILE + ": " + e.getMessage());
            }
        }
        if (list.isEmpty()) {
            for (String host : FALLBACK_HOSTS) list.add(new Endpoint(host, DEFAULT_PORT));
        }
        return list;
    }

    /** Try every endpoint in order, set {@link #activeEndpoint} to the first that PONGs. */
    private boolean pingPool() {
        for (Endpoint ep : serverPool) {
            if (pingOne(ep)) {
                activeEndpoint = ep;
                return true;
            }
        }
        return false;
    }

    private boolean pingOne(Endpoint ep) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(3000);
            BufferedWriter w = writer(socket);
            BufferedReader r = reader(socket);
            w.write("PING");
            w.newLine();
            w.flush();
            String resp = r.readLine();
            return "PONG".equals(resp);
        } catch (Exception e) {
            return false;
        }
    }

    /** Encapsulates an authenticated AEAD-framed session. */
    private static final class Session implements AutoCloseable {
        final Socket socket;
        final BufferedWriter w;
        final BufferedReader r;
        final byte[] key;
        long sendSeq = 0;
        long recvSeq = 0;

        Session(Socket s, BufferedWriter w, BufferedReader r, byte[] key) {
            this.socket = s; this.w = w; this.r = r; this.key = key;
        }

        void sendJson(String json) throws IOException, GeneralSecurityException {
            byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
            byte[] nonce = new byte[12];
            SECURE_RANDOM.nextBytes(nonce);
            byte[] seq = longBE(sendSeq);
            byte[] aad = concat(new byte[]{ DIR_C2S }, seq);
            byte[] ct = aesGcmEncrypt(plaintext, key, nonce, aad);
            byte[] frame = concat(seq, nonce, ct);
            w.write("DATA " + Base64.getEncoder().encodeToString(frame));
            w.newLine();
            w.flush();
            sendSeq++;
        }

        String recvJson() throws IOException, GeneralSecurityException {
            String line = r.readLine();
            if (line == null) throw new IOException("server closed connection");
            if (!line.startsWith("DATA "))
                throw new IOException("expected DATA frame, got: " + truncate(line, 32));
            byte[] frame = Base64.getDecoder().decode(line.substring(5));
            if (frame.length < 8 + 12 + 16) throw new IOException("frame too short");
            long seq = readLongBE(frame, 0);
            if (seq != recvSeq)
                throw new IOException("server seq mismatch (got " + seq + ", expected " + recvSeq + ")");
            byte[] nonce = Arrays.copyOfRange(frame, 8, 20);
            byte[] ct = Arrays.copyOfRange(frame, 20, frame.length);
            byte[] aad = concat(new byte[]{ DIR_S2C }, Arrays.copyOfRange(frame, 0, 8));
            byte[] plaintext = aesGcmDecrypt(ct, key, nonce, aad);
            recvSeq++;
            return new String(plaintext, StandardCharsets.UTF_8);
        }

        @Override public void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Open an authenticated session with the active server.
     * @return null if all servers failed.
     */
    private Session openSession(String licenseKey) {
        // Build try-list with active first
        List<Endpoint> tryOrder = new ArrayList<>();
        if (activeEndpoint != null) tryOrder.add(activeEndpoint);
        for (Endpoint ep : serverPool) {
            if (ep != activeEndpoint) tryOrder.add(ep);
        }

        for (Endpoint ep : tryOrder) {
            try {
                Session s = handshakeWith(ep, licenseKey);
                if (s != null) {
                    activeEndpoint = ep;
                    serverOnline.set(true);
                    return s;
                }
            } catch (ConnectException e) {
                // try next
            } catch (Exception e) {
                // try next
            }
        }
        serverOnline.set(false);
        return null;
    }

    private Session handshakeWith(Endpoint ep, String licenseKey) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            BufferedWriter w = writer(socket);
            BufferedReader r = reader(socket);

            // Step 1: HELLO
            byte[] clientNonce = new byte[16];
            SECURE_RANDOM.nextBytes(clientNonce);
            w.write("HELLO " + PROTOCOL_TAG + " " + Base64.getEncoder().encodeToString(clientNonce));
            w.newLine();
            w.flush();

            // Step 2: server nonce
            String okLine = r.readLine();
            if (okLine == null || !okLine.startsWith("OK ")) {
                if ("AUTH_FAIL".equals(okLine) || "RATE_LIMIT".equals(okLine)
                        || "BUSY".equals(okLine)) {
                    socket.close();
                    return null;
                }
                socket.close();
                return null;
            }
            byte[] serverNonce = Base64.getDecoder().decode(okLine.substring(3));
            if (serverNonce.length != 16) { socket.close(); return null; }

            // Step 3: LOGIN (RSA-OAEP) — the server already knows our license via activation_id
            // (issued once by platform.LicenseActivation's online ACTIVATE), so the handshake
            // JSON carries no license/fp/signature at all, just the shared nonces:
            // "LOGIN <enc_b64> <activation_id> <enc_blob_b64>"
            String activationId = platform.License.getActivationId();
            String encBlob = platform.License.getEncBlob();
            String handshakeJson = "{"
                    + "\"ts\":"             + (System.currentTimeMillis() / 1000L) + ","
                    + "\"client_nonce\":\"" + toHex(clientNonce)              + "\","
                    + "\"server_nonce\":\"" + toHex(serverNonce)              + "\""
                    + "}";

            byte[] enc = rsaOaepEncrypt(handshakeJson.getBytes(StandardCharsets.UTF_8));
            w.write("LOGIN " + Base64.getEncoder().encodeToString(enc)
                    + " " + (activationId != null ? activationId : "")
                    + " " + (encBlob != null ? encBlob : ""));
            w.newLine();
            w.flush();

            // Step 4: AUTH_OK + encrypted session key
            String authLine = r.readLine();
            if (authLine == null || !authLine.startsWith("AUTH_OK ")) {
                socket.close();
                return null;
            }
            byte[] encSession = Base64.getDecoder().decode(authLine.substring(8));

            byte[] deliveryKey = hkdf(
                    concat(licenseKey.getBytes(StandardCharsets.UTF_8), LICENSE_PEPPER),
                    serverNonce, DELIVERY_INFO, 32);

            byte[] nonceForDelivery = Arrays.copyOfRange(clientNonce, 0, 12);
            byte[] sessionKey = aesGcmDecrypt(encSession, deliveryKey, nonceForDelivery, DELIVERY_AAD);
            if (sessionKey.length != 32) { socket.close(); return null; }

            return new Session(socket, w, r, sessionKey);
        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private SaveResult uploadToServer(GameState state, String licenseKey) {
        try {
            byte[] jsonBytes = serializeToJsonBytes(state);
            return uploadRawJsonToServer(jsonBytes, licenseKey);
        } catch (ReflectiveOperationException e) {
            return SaveResult.fail("Cloud save failed during serialization.");
        }
    }

    private SaveResult uploadRawJsonToServer(byte[] jsonBytes, String licenseKey) {
        try (Session sess = openSession(licenseKey)) {
            if (sess == null) return SaveResult.fail("Cloud save failed: no server reachable.");

            String req = "{\"cmd\":\"UPLOAD\",\"data\":\""
                    + Base64.getEncoder().encodeToString(jsonBytes) + "\"}";
            sess.sendJson(req);

            String response = sess.recvJson();
            String status = extractJsonString(response, "status");
            if ("SAVED".equals(status)) return SaveResult.ok("Cloud save uploaded.");
            if ("SYNC".equals(status))  return SaveResult.ok("Cloud save synced (server had newer version).");
            if ("ERROR".equals(status)) {
                String msg = extractJsonString(response, "msg");
                return SaveResult.fail("Cloud save server error: " + (msg != null ? msg : "unknown"));
            }
            return SaveResult.fail("Cloud save: unexpected response.");
        } catch (Exception e) {
            return SaveResult.fail("Cloud save failed: " + e.getMessage());
        }
    }

    /**
     * @return JSON string, or {@code null} if server explicitly returned NO_SAVE.
     * @throws Exception on connection / decryption failure.
     */
    private String downloadFromServer(String licenseKey) throws Exception {
        try (Session sess = openSession(licenseKey)) {
            if (sess == null) throw new ConnectException("no server reachable");

            sess.sendJson("{\"cmd\":\"DOWNLOAD\"}");
            String response = sess.recvJson();
            String status = extractJsonString(response, "status");

            if ("DOWNLOADED".equals(status)) {
                String data = extractJsonString(response, "data");
                if (data == null) return null;
                byte[] bytes = Base64.getDecoder().decode(data);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if ("NO_SAVE".equals(status)) return null;
            throw new IOException("unexpected status: " + status);
        }
    }

    // ── Friends (username claim + friend requests) ──────────────────────────
    // Reuses the same encrypted session/framing as save UPLOAD/DOWNLOAD; the server already
    // implements these commands (see SERVERS/save_server/server.py) keyed by license_key.

    public record FriendResult(boolean ok, String status, List<String> names) {
        static FriendResult of(String status, List<String> names) {
            return new FriendResult(true, status, names);
        }
        static FriendResult fail(String status) { return new FriendResult(false, status, null); }
    }

    public FriendResult claimUsername(String licenseKey, String username) {
        return simpleCommand(licenseKey,
                "{\"cmd\":\"CLAIM_USERNAME\",\"username\":" + quote(username) + "}");
    }

    public FriendResult checkUsername(String licenseKey, String username) {
        return simpleCommand(licenseKey,
                "{\"cmd\":\"CHECK_USERNAME\",\"username\":" + quote(username) + "}");
    }

    public FriendResult sendFriendRequest(String licenseKey, String username) {
        return simpleCommand(licenseKey,
                "{\"cmd\":\"SEND_FRIEND_REQUEST\",\"username\":" + quote(username) + "}");
    }

    public FriendResult respondFriendRequest(String licenseKey, String username, boolean accept) {
        return simpleCommand(licenseKey,
                "{\"cmd\":\"RESPOND_FRIEND_REQUEST\",\"username\":" + quote(username)
                        + ",\"accept\":" + accept + "}");
    }

    public FriendResult removeFriend(String licenseKey, String username) {
        return simpleCommand(licenseKey,
                "{\"cmd\":\"REMOVE_FRIEND\",\"username\":" + quote(username) + "}");
    }

    /** Opaque per-account token (NOT the license key) embedded in this player's NFC add-friend tag. */
    public record FriendIdResult(boolean ok, String status, String value) {
        static FriendIdResult of(String status, String value) { return new FriendIdResult(true, status, value); }
        static FriendIdResult fail(String status) { return new FriendIdResult(false, status, null); }
    }

    public FriendIdResult getMyFriendId(String licenseKey) {
        if (licenseKey == null) return FriendIdResult.fail("NO_LICENSE");
        try (Session sess = openSession(licenseKey)) {
            if (sess == null) return FriendIdResult.fail("NO_SERVER");
            sess.sendJson("{\"cmd\":\"GET_MY_FRIEND_ID\"}");
            String response = sess.recvJson();
            String status = extractJsonString(response, "status");
            if (!"OK".equals(status)) return FriendIdResult.fail(status != null ? status : "ERROR");
            return FriendIdResult.of("OK", extractJsonString(response, "friend_id"));
        } catch (Exception e) {
            return FriendIdResult.fail("ERROR");
        }
    }

    public FriendResult listFriendRequests(String licenseKey) {
        return listCommand(licenseKey, "{\"cmd\":\"LIST_FRIEND_REQUESTS\"}", "requests");
    }

    public FriendResult listFriends(String licenseKey) {
        return listCommand(licenseKey, "{\"cmd\":\"LIST_FRIENDS\"}", "friends");
    }

    /** Sends a command whose response is just {"status": "..."}. */
    private FriendResult simpleCommand(String licenseKey, String requestJson) {
        if (licenseKey == null) return FriendResult.fail("NO_LICENSE");
        try (Session sess = openSession(licenseKey)) {
            if (sess == null) return FriendResult.fail("NO_SERVER");
            sess.sendJson(requestJson);
            String response = sess.recvJson();
            String status = extractJsonString(response, "status");
            return FriendResult.of(status != null ? status : "ERROR", null);
        } catch (Exception e) {
            return FriendResult.fail("ERROR");
        }
    }

    /** Sends a command whose response is {"status":"OK","<listKey>":[...]}. */
    private FriendResult listCommand(String licenseKey, String requestJson, String listKey) {
        if (licenseKey == null) return FriendResult.fail("NO_LICENSE");
        try (Session sess = openSession(licenseKey)) {
            if (sess == null) return FriendResult.fail("NO_SERVER");
            sess.sendJson(requestJson);
            String response = sess.recvJson();
            String status = extractJsonString(response, "status");
            if (!"OK".equals(status)) return FriendResult.fail(status != null ? status : "ERROR");
            return FriendResult.of("OK", extractJsonStringArray(response, listKey));
        } catch (Exception e) {
            return FriendResult.fail("ERROR");
        }
    }

    /** Parses a top-level JSON string array field, e.g. {"friends":["Alice","Bob"]}. */
    private static List<String> extractJsonStringArray(String json, String key) {
        List<String> out = new ArrayList<>();
        String search = "\"" + key + "\":[";
        int i = json.indexOf(search);
        if (i < 0) return out;
        int start = i + search.length();
        int end = json.indexOf(']', start);
        if (end < 0) return out;
        String body = json.substring(start, end);
        boolean inString = false, escape = false;
        StringBuilder cur = new StringBuilder();
        for (int p = 0; p < body.length(); p++) {
            char c = body.charAt(p);
            if (inString) {
                if (escape) {
                    switch (c) {
                        case 'n' -> cur.append('\n');
                        case 'r' -> cur.append('\r');
                        case 't' -> cur.append('\t');
                        case '"' -> cur.append('"');
                        case '\\' -> cur.append('\\');
                        default -> cur.append(c);
                    }
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inString = true;
            }
        }
        return out;
    }

    /**
     * Local-cache key = HKDF-SHA256(license_or_anon, salt=machine_fingerprint,
     *                               info="michi-local-v2", length=32).
     * No key is ever written to disk.
     */
    private byte[] deriveLocalKey(String licenseKey) throws GeneralSecurityException {
        byte[] secret = (licenseKey != null && !licenseKey.isEmpty())
                ? (licenseKey + ":local").getBytes(StandardCharsets.UTF_8)
                : "<no-license>".getBytes(StandardCharsets.UTF_8);
        byte[] salt = machineFingerprint();
        return hkdf(secret, salt, LOCAL_INFO, 32);
    }

    /** Best-effort, non-secret machine fingerprint (MAC + hostname + user). */
    private byte[] machineFingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String user = System.getProperty("user.name", "");
            md.update(user.getBytes(StandardCharsets.UTF_8));
            try {
                String host = java.net.InetAddress.getLocalHost().getHostName();
                md.update((byte) 0);
                md.update(host.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            try {
                java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
                while (ifs != null && ifs.hasMoreElements()) {
                    NetworkInterface ni = ifs.nextElement();
                    if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        md.update((byte) 0);
                        md.update(mac);
                    }
                }
            } catch (Exception ignored) {}
            return md.digest();
        } catch (GeneralSecurityException e) {
            // SHA-256 should always be available
            return new byte[32];
        }
    }

    private SaveResult saveLocalEncrypted(GameState state, String licenseKey) {
        try {
            byte[] jsonBytes = serializeToJsonBytes(state);
            byte[] key = deriveLocalKey(licenseKey);
            byte[] nonce = new byte[12];
            SECURE_RANDOM.nextBytes(nonce);
            byte[] aad = "michi-local".getBytes(StandardCharsets.UTF_8);
            byte[] ct = aesGcmEncrypt(jsonBytes, key, nonce, aad);

            // File layout: magic(4) | version(1) | nonce(12) | ciphertext+tag
            try (OutputStream fos = GameStorage.outputStream(LOCAL_SAVE_FILE)) {
                fos.write(new byte[]{ 'M', 'I', 'C', 'H' });
                fos.write(2); // version 2
                fos.write(nonce);
                fos.write(ct);
            }
            // Wipe legacy plaintext key file if it lingered
            GameStorage.delete(LEGACY_AES_KEY_FILE);
            return SaveResult.ok("Saved locally (server offline).");
        } catch (Exception e) {
            return SaveResult.fail("Local save failed: " + e.getMessage());
        }
    }

    private String loadLocalEncrypted(String licenseKey) {
        if (!GameStorage.exists(LOCAL_SAVE_FILE)) return null;
        byte[] data;
        try (InputStream fis = GameStorage.inputStream(LOCAL_SAVE_FILE)) {
            data = fis.readAllBytes();
        } catch (IOException e) {
            return null;
        }

        // v2: magic+version+nonce(12)+ciphertext
        if (data.length > 4 + 1 + 12 + 16
                && data[0] == 'M' && data[1] == 'I' && data[2] == 'C' && data[3] == 'H'
                && data[4] == 2) {
            try {
                byte[] key = deriveLocalKey(licenseKey);
                byte[] nonce = Arrays.copyOfRange(data, 5, 17);
                byte[] ct    = Arrays.copyOfRange(data, 17, data.length);
                byte[] aad   = "michi-local".getBytes(StandardCharsets.UTF_8);
                byte[] plain = aesGcmDecrypt(ct, key, nonce, aad);
                return new String(plain, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        // Legacy v1: AES-CBC with plaintext key in local_aes.key
        if (!GameStorage.exists(LEGACY_AES_KEY_FILE)) return null;
        try (InputStream fis = GameStorage.inputStream(LEGACY_AES_KEY_FILE)) {
            byte[] aesKey = fis.readAllBytes();
            if (aesKey.length != 32) return null;
            byte[] iv = Arrays.copyOfRange(data, 0, 16);
            byte[] enc = Arrays.copyOfRange(data, 16, data.length);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new javax.crypto.spec.IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(enc);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void syncPendingLocalSave() {
        if (cachedLicenseKey == null) return;
        try {
            String json = loadLocalEncrypted(cachedLicenseKey);
            if (json == null) { pendingUpload = false; return; }
            SaveResult result = uploadRawJsonToServer(
                    json.getBytes(StandardCharsets.UTF_8), cachedLicenseKey);
            if (result.ok()) {
                pendingUpload = false;
                deletePendingLocalFiles();
                System.out.println("[CloudSave] Pending local save synced to server.");
            }
        } catch (Exception e) {
            System.out.println("[CloudSave] Failed to sync pending save: " + e.getMessage());
        }
    }

    private void deletePendingLocalFiles() {
        try { GameStorage.delete(LOCAL_SAVE_FILE); } catch (Exception ignored) {}
        try { GameStorage.delete(LEGACY_AES_KEY_FILE); } catch (Exception ignored) {}
    }

    private static PublicKey loadRSAPublicKey() throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(RSA_PUBLIC_KEY_B64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] rsaOaepEncrypt(byte[] plain) throws GeneralSecurityException {
        OAEPParameterSpec spec = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.ENCRYPT_MODE, loadRSAPublicKey(), spec);
        return c.doFinal(plain);
    }

    static byte[] aesGcmEncrypt(byte[] plain, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(GCM);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(plain);
    }

    static byte[] aesGcmDecrypt(byte[] ct, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(GCM);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(ct);
    }

    /** RFC 5869 HKDF-SHA256, extract+expand. */
    static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        if (salt == null) salt = new byte[hmac.getMacLength()];
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(secret);

        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            hmac.reset();
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(t);
            if (info != null) hmac.update(info);
            hmac.update(counter);
            t = hmac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
            counter++;
        }
        return out;
    }

    private static BufferedWriter writer(Socket s) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private static byte[] longBE(long v) {
        return new byte[]{
                (byte)(v >>> 56), (byte)(v >>> 48), (byte)(v >>> 40), (byte)(v >>> 32),
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8),  (byte) v
        };
    }
    private static long readLongBE(byte[] b, int off) {
        return  ((long)(b[off]   & 0xff) << 56)
              | ((long)(b[off+1] & 0xff) << 48)
              | ((long)(b[off+2] & 0xff) << 40)
              | ((long)(b[off+3] & 0xff) << 32)
              | ((long)(b[off+4] & 0xff) << 24)
              | ((long)(b[off+5] & 0xff) << 16)
              | ((long)(b[off+6] & 0xff) << 8)
              | ((long)(b[off+7] & 0xff));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // Tiny JSON helpers — only used to read {"status":"..","data":".."}
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int i = json.indexOf(search);
        if (i < 0) return null;
        int start = i + search.length();
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int p = start; p < json.length(); p++) {
            char c = json.charAt(p);
            if (escape) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    byte[] serializeToJsonBytes(Object value) throws ReflectiveOperationException {
        String json = trySerializeWithGson(value);
        if (json == null) json = trySerializeWithJackson(value);
        if (json == null) json = serializeFallback(value, new IdentityHashMap<>());
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String trySerializeWithGson(Object value) throws ReflectiveOperationException {
        try {
            Class<?> cls = Class.forName("com.google.gson.Gson");
            Object gson = cls.getDeclaredConstructor().newInstance();
            return (String) cls.getMethod("toJson", Object.class).invoke(gson, value);
        } catch (ClassNotFoundException e) { return null; }
    }

    private String trySerializeWithJackson(Object value) throws ReflectiveOperationException {
        try {
            Class<?> cls = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = cls.getDeclaredConstructor().newInstance();
            return (String) cls.getMethod("writeValueAsString", Object.class).invoke(mapper, value);
        } catch (ClassNotFoundException e) { return null; }
    }

    private String serializeFallback(Object value, IdentityHashMap<Object, Boolean> visited)
            throws IllegalAccessException {

        if (value == null) return "null";
        if (value instanceof String s) return quote(s);
        if (value instanceof Character c) return quote(String.valueOf(c));
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);

        if (visited.containsKey(value))
            throw new IllegalStateException("Circular reference in cloud-save data.");
        Class<?> cls = value.getClass();

        if (cls.isArray()) {
            visited.put(value, Boolean.TRUE);
            int len = Array.getLength(value);
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < len; i++) {
                if (i > 0) b.append(',');
                b.append(serializeFallback(Array.get(value, i), visited));
            }
            visited.remove(value);
            return b.append(']').toString();
        }

        if (value instanceof Iterable<?> it) {
            visited.put(value, Boolean.TRUE);
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) b.append(',');
                b.append(serializeFallback(o, visited));
                first = false;
            }
            visited.remove(value);
            return b.append(']').toString();
        }

        if (value instanceof Map<?, ?> map) {
            visited.put(value, Boolean.TRUE);
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String k)) continue;
                if (!first) b.append(',');
                b.append(quote(k)).append(':').append(serializeFallback(e.getValue(), visited));
                first = false;
            }
            visited.remove(value);
            return b.append('}').toString();
        }

        visited.put(value, Boolean.TRUE);
        ArrayList<Field> fields = new ArrayList<>(Arrays.asList(cls.getFields()));
        fields.sort(Comparator.comparing(Field::getName));
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Field f : fields) {
            if (!first) b.append(',');
            b.append(quote(f.getName())).append(':').append(serializeFallback(f.get(value), visited));
            first = false;
        }
        visited.remove(value);
        return b.append('}').toString();
    }

    private String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    public record SaveResult(boolean ok, String message) {
        public static SaveResult ok(String msg) { return new SaveResult(true, msg); }
        public static SaveResult fail(String msg) { return new SaveResult(false, msg); }
    }

    public record DownloadResult(boolean ok, String json, String message) {
        public static DownloadResult ok(String json) { return new DownloadResult(true, json, "Downloaded."); }
        public static DownloadResult fail(String msg) { return new DownloadResult(false, null, msg); }
    }
}

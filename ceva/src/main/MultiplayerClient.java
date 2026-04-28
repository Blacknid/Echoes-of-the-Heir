package main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Multiplayer-server client v2.
 *
 * <p>Uses the same security model as the cloud-save service:
 *  <ul>
 *    <li>RSA-OAEP-SHA256 license-bound handshake</li>
 *    <li>AES-256-GCM authenticated encryption per frame</li>
 *    <li>HKDF-SHA256 delivery + session keys</li>
 *    <li>Per-direction sequence counters bound into AAD (anti-replay)</li>
 *    <li>Timestamp + nonce check against the server's replay cache</li>
 *  </ul>
 *
 * <p>The IP/port to connect to is supplied by the in-game UI
 * (Direct Connect or saved-server list); this class never hard-codes a host.
 */
public class MultiplayerClient {

    // ── Embedded RSA public key (must match the MP server's private key) ─
    private static final String RSA_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuEwRNiBMB0MkhCI7+3Xs"
          + "fsZZWZMvk4WbgjZk7CBMUwyXSnY6vscwMcWIvlVj6BItyfNJP1PFUaaJgfoOFXl1"
          + "vTn1jKiCBdLC14NhLDeW2E7N1XWD3s4YhXWqc91soETgb2TRpYIqfkfiKwdsKwiH"
          + "XemA4XLgrZWHihbTWrPzb1TANd2a2tQWMDqq7QGMka07Yb2L5CBdaZFaX0iDUvcH"
          + "aN2dOguvkmYWrotrd1GVQtvF/bYIWR19QCKvfQZV++vxEUFhcZdjgXXSewN194VS"
          + "QzrMKiwVogXLUTVGBFt1mmF/fXUjzM8oY4SEKBCoiybHPbwbIJLZ43L54PjmlCRf"
          + "1wIDAQAB";

    private static final String PROTOCOL_TAG    = "v2";
    private static final byte[] LICENSE_PEPPER  = "michi-license-pepper-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIVERY_INFO   = "michi-delivery-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIVERY_AAD    = "MichiMpSession".getBytes(StandardCharsets.UTF_8);

    private static final byte DIR_S2C = 0x01;
    private static final byte DIR_C2S = 0x02;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final GamePanel gp;
    private Socket socket;
    private BufferedWriter out;
    private BufferedReader in;
    private byte[] sessionKey;

    private final AtomicLong sendSeq = new AtomicLong(0);
    private final AtomicLong recvSeq = new AtomicLong(0);

    private Thread receiveThread;
    private final AtomicBoolean connected  = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    public final ConcurrentHashMap<Integer, RemotePlayerState> remotePlayers = new ConcurrentHashMap<>();
    public int localId = -1;
    public String serverMessage = "";
    public String connectionStatus = "";

    private int sendCounter = 0;
    private static final int SEND_INTERVAL = 3;

    public MultiplayerClient(GamePanel gp) {
        this.gp = gp;
    }

    public boolean isConnected()  { return connected.get(); }
    public boolean isConnecting() { return connecting.get(); }

    /** Async connect.  Sends license-authenticated handshake before joining. */
    public void connect(String ip, int port, String playerName, String playerClass) {
        if (connected.get() || connecting.get()) return;
        connecting.set(true);
        connectionStatus = "Connecting to " + ip + ":" + port + "...";

        new Thread(() -> {
            try {
                String license = Main.LICENSE_KEY;
                if (license == null || license.isBlank()) {
                    connecting.set(false);
                    connectionStatus = "No license key. Multiplayer requires a valid license.";
                    System.out.println("[MP Client] Missing license — aborting connect.");
                    return;
                }

                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(ip, port), 5000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(15000);

                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream(),  StandardCharsets.UTF_8));

                if (!performHandshake(license, playerName, playerClass)) {
                    connecting.set(false);
                    connectionStatus = "Handshake failed (auth rejected or bad protocol).";
                    closeQuietly();
                    return;
                }

                connected.set(true);
                connecting.set(false);
                connectionStatus = "Connected!";

                receiveThread = new Thread(this::receiveLoop, "MP-Receive");
                receiveThread.setDaemon(true);
                receiveThread.start();

                Thread keepalive = new Thread(this::keepaliveLoop, "MP-Keepalive");
                keepalive.setDaemon(true);
                keepalive.start();

            } catch (Exception e) {
                connecting.set(false);
                connected.set(false);
                connectionStatus = "Failed: " + e.getMessage();
                System.out.println("[MP Client] Connection failed: " + e.getMessage());
                closeQuietly();
            }
        }, "MP-Connect").start();
    }

    public void disconnect() {
        connected.set(false);
        connecting.set(false);
        remotePlayers.clear();
        localId = -1;
        connectionStatus = "";
        sessionKey = null;
        sendSeq.set(0);
        recvSeq.set(0);
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException ignored) {}
        socket = null;
    }

    /** Called every game tick. */
    public void update() {
        if (!connected.get()) return;
        sendCounter++;
        if (sendCounter >= SEND_INTERVAL) {
            sendCounter = 0;
            sendPlayerState();
        }
    }

    private void sendPlayerState() {
        try {
            var p = gp.player;
            String msg = "{\"type\":\"move\","
                    + "\"x\":"        + p.worldX  + ","
                    + "\"y\":"        + p.worldY  + ","
                    + "\"dir\":"      + p.direction + ","
                    + "\"sprite\":"   + p.spriteNum + ","
                    + "\"attacking\":" + p.attacking + ","
                    + "\"life\":"     + p.life      + ","
                    + "\"maxLife\":"  + p.maxLife
                    + "}";
            sendEncrypted(msg);
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending state: " + e.getMessage());
            disconnect();
        }
    }

    public void sendChat(String message) {
        if (!connected.get()) return;
        try {
            sendEncrypted("{\"type\":\"chat\",\"msg\":\"" + jsonEscape(message) + "\"}");
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending chat: " + e.getMessage());
        }
    }

    // =====================================================================
    //  HANDSHAKE
    // =====================================================================

    private boolean performHandshake(String license, String name, String cls) {
        try {
            // Step 1: HELLO
            byte[] clientNonce = new byte[16];
            SECURE_RANDOM.nextBytes(clientNonce);
            sendLine("HELLO " + PROTOCOL_TAG + " " + Base64.getEncoder().encodeToString(clientNonce));

            // Step 2: server nonce
            String okLine = readLine();
            if (okLine == null || !okLine.startsWith("OK ")) {
                if ("RATE_LIMIT".equals(okLine)) connectionStatus = "Rate-limited by server.";
                else if ("SERVER_FULL".equals(okLine)) connectionStatus = "Server is full.";
                return false;
            }
            byte[] serverNonce = Base64.getDecoder().decode(okLine.substring(3));
            if (serverNonce.length != 16) return false;

            // Step 3: AUTH (RSA-OAEP) — bundle name+class so server can build PlayerState atomically
            String handshakeJson = "{"
                    + "\"license\":\""      + jsonEscape(license)            + "\","
                    + "\"ts\":"             + (System.currentTimeMillis() / 1000L) + ","
                    + "\"client_nonce\":\"" + toHex(clientNonce)              + "\","
                    + "\"server_nonce\":\"" + toHex(serverNonce)              + "\","
                    + "\"name\":\""         + jsonEscape(name)                + "\","
                    + "\"class\":\""        + jsonEscape(cls)                 + "\""
                    + "}";
            byte[] enc = rsaOaepEncrypt(handshakeJson.getBytes(StandardCharsets.UTF_8));
            sendLine("AUTH " + Base64.getEncoder().encodeToString(enc));

            // Step 4: AUTH_OK + encrypted session key
            String authLine = readLine();
            if (authLine == null || !authLine.startsWith("AUTH_OK ")) return false;
            byte[] encSession = Base64.getDecoder().decode(authLine.substring(8));

            byte[] deliveryKey = hkdf(
                    concat(license.getBytes(StandardCharsets.UTF_8), LICENSE_PEPPER),
                    serverNonce, DELIVERY_INFO, 32);
            byte[] nonceForDelivery = Arrays.copyOfRange(clientNonce, 0, 12);
            byte[] sk = aesGcmDecrypt(encSession, deliveryKey, nonceForDelivery, DELIVERY_AAD);
            if (sk.length != 32) return false;
            this.sessionKey = sk;

            return true;
        } catch (Exception e) {
            System.out.println("[MP Client] Handshake failed: " + e.getMessage());
            return false;
        }
    }

    // =====================================================================
    //  ENCRYPTED FRAMING
    // =====================================================================

    private synchronized void sendEncrypted(String json) throws IOException, GeneralSecurityException {
        if (sessionKey == null || out == null) return;
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[12];
        SECURE_RANDOM.nextBytes(nonce);
        long seq = sendSeq.getAndIncrement();
        byte[] seqBytes = longBE(seq);
        byte[] aad = new byte[]{ DIR_C2S, seqBytes[0], seqBytes[1], seqBytes[2], seqBytes[3],
                                 seqBytes[4], seqBytes[5], seqBytes[6], seqBytes[7] };
        byte[] ct = aesGcmEncrypt(plaintext, sessionKey, nonce, aad);
        byte[] frame = concat(seqBytes, nonce, ct);
        synchronized (out) {
            out.write("DATA " + Base64.getEncoder().encodeToString(frame));
            out.newLine();
            out.flush();
        }
    }

    /** @return decrypted JSON, or null on EOF. */
    private String recvEncrypted() throws IOException, GeneralSecurityException {
        String line = readLine();
        if (line == null) return null;
        if (!line.startsWith("DATA ")) {
            // Server may send plaintext error frames before/after handshake
            if (line.equals("AUTH_FAIL") || line.equals("RATE_LIMIT")
                    || line.equals("SERVER_FULL") || line.equals("BUSY")) {
                throw new IOException("server error: " + line);
            }
            throw new IOException("expected DATA frame, got: " + truncate(line, 32));
        }
        byte[] frame = Base64.getDecoder().decode(line.substring(5));
        if (frame.length < 8 + 12 + 16) throw new IOException("frame too short");
        long seq = readLongBE(frame, 0);
        long expected = recvSeq.getAndIncrement();
        if (seq != expected)
            throw new IOException("server seq mismatch (got " + seq + ", expected " + expected + ")");
        byte[] nonce = Arrays.copyOfRange(frame, 8, 20);
        byte[] ct    = Arrays.copyOfRange(frame, 20, frame.length);
        byte[] aad   = new byte[]{ DIR_S2C, frame[0], frame[1], frame[2], frame[3],
                                   frame[4], frame[5], frame[6], frame[7] };
        byte[] plaintext = aesGcmDecrypt(ct, sessionKey, nonce, aad);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private void sendLine(String text) throws IOException {
        synchronized (out) {
            out.write(text);
            out.newLine();
            out.flush();
        }
    }

    private String readLine() throws IOException {
        return in.readLine();
    }

    // =====================================================================
    //  RECEIVE / KEEPALIVE LOOPS
    // =====================================================================

    private void receiveLoop() {
        try {
            while (connected.get()) {
                String json;
                try {
                    json = recvEncrypted();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (json == null) break;
                handleMessage(json.trim());
            }
        } catch (Exception e) {
            if (connected.get()) {
                System.out.println("[MP Client] Receive error: " + e.getMessage());
            }
        } finally {
            connected.set(false);
            connectionStatus = "Disconnected";
        }
    }

    private void keepaliveLoop() {
        while (connected.get()) {
            try {
                Thread.sleep(3000);
                if (connected.get()) {
                    sendEncrypted("{\"type\":\"ping\"}");
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    // =====================================================================
    //  APPLICATION MESSAGES (parsed plaintext JSON)
    // =====================================================================

    private void handleMessage(String json) {
        String type = extractString(json, "type");
        if (type == null) return;

        switch (type) {
            case "welcome" -> {
                localId = extractInt(json, "id", -1);
                connectionStatus = "Connected (ID: " + localId + ")";
                System.out.println("[MP Client] Welcome! Local ID = " + localId);
                parsePlayerList(json);
            }
            case "player_join" -> {
                int id = extractInt(json, "id", -1);
                String name = extractString(json, "name");
                String cls  = extractString(json, "class");
                if (id >= 0 && id != localId) {
                    RemotePlayerState rp = new RemotePlayerState();
                    rp.name        = name != null ? name : "Player";
                    rp.playerClass = cls  != null ? cls  : "Fighter";
                    remotePlayers.put(id, rp);
                    gp.ui.addMessage(rp.name + " joined!", new java.awt.Color(100, 220, 100));
                }
            }
            case "player_leave" -> {
                int id = extractInt(json, "id", -1);
                RemotePlayerState removed = remotePlayers.remove(id);
                if (removed != null) {
                    gp.ui.addMessage(removed.name + " left.", new java.awt.Color(220, 100, 100));
                }
            }
            case "player_update" -> {
                int id = extractInt(json, "id", -1);
                if (id >= 0 && id != localId) {
                    RemotePlayerState rp = remotePlayers.get(id);
                    if (rp != null) {
                        rp.worldX     = extractInt(json, "x",       rp.worldX);
                        rp.worldY     = extractInt(json, "y",       rp.worldY);
                        rp.direction  = extractInt(json, "dir",     rp.direction);
                        rp.spriteNum  = extractInt(json, "sprite",  rp.spriteNum);
                        rp.attacking  = extractBool(json, "attacking");
                        rp.life       = extractInt(json, "life",    rp.life);
                        rp.maxLife    = extractInt(json, "maxLife", rp.maxLife);
                    }
                }
            }
            case "server_full" -> {
                connectionStatus = "Server is full!";
                disconnect();
            }
            case "chat" -> {
                String from = extractString(json, "from");
                String msg  = extractString(json, "msg");
                if (from != null && msg != null) {
                    gp.ui.addMessage(from + ": " + msg, new java.awt.Color(200, 200, 255));
                }
            }
            case "chat_throttled" -> { /* silently dropped — UI optional */ }
            case "pong" -> { /* keepalive */ }
            case "kick" -> {
                String reason = extractString(json, "reason");
                connectionStatus = "Kicked: " + (reason != null ? reason : "No reason");
                disconnect();
            }
            default -> { /* ignore unknown */ }
        }
    }

    private void parsePlayerList(String json) {
        int idx = json.indexOf("\"players\"");
        if (idx < 0) return;
        int arrStart = json.indexOf('[', idx);
        int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return;

        String arr = json.substring(arrStart + 1, arrEnd);
        String[] entries = arr.split("\\},\\s*\\{");
        for (String entry : entries) {
            entry = entry.replace("{", "").replace("}", "").trim();
            if (entry.isEmpty()) continue;
            String wrapped = "{" + entry + "}";
            int id = extractInt(wrapped, "id", -1);
            if (id >= 0 && id != localId) {
                RemotePlayerState rp = new RemotePlayerState();
                rp.name        = extractString(wrapped, "name");
                if (rp.name == null) rp.name = "Player";
                rp.playerClass = extractString(wrapped, "class");
                if (rp.playerClass == null) rp.playerClass = "Fighter";
                rp.worldX    = extractInt(wrapped, "x", 0);
                rp.worldY    = extractInt(wrapped, "y", 0);
                rp.direction = extractInt(wrapped, "dir", 0);
                remotePlayers.put(id, rp);
            }
        }
    }

    // =====================================================================
    //  CRYPTO PRIMITIVES
    // =====================================================================

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

    private static byte[] aesGcmEncrypt(byte[] plain, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(plain);
    }

    private static byte[] aesGcmDecrypt(byte[] ct, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(ct);
    }

    private static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        if (salt == null) salt = new byte[hmac.getMacLength()];
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(secret);

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

    // =====================================================================
    //  SMALL UTILITIES
    // =====================================================================

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

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }

    // ── Simple JSON helpers for inbound application messages ──

    private static String extractString(String json, String key) {
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

    private static int extractInt(String json, String key, int fallback) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return fallback;
        int start = i + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean extractBool(String json, String key) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return false;
        int start = i + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        return json.startsWith("true", start);
    }

    /** Holds the state of a remote player for rendering. */
    public static class RemotePlayerState {
        public String name = "Player";
        public String playerClass = "Fighter";
        public int worldX, worldY;
        public int direction = 0;
        public int spriteNum = 1;
        public boolean attacking = false;
        public int life = 6, maxLife = 6;
    }
}

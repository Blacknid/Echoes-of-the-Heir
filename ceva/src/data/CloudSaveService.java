package data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cloud-save client with RSA/AES hybrid encryption, heartbeat monitoring,
 * and online/offline mode support.
 *
 * Protocol:
 *  1. Client RSA-encrypts license key → sends as Base64 line
 *  2. Server validates, generates AES-256 session key
 *  3. Server sends "AUTH_OK" + Base64(AES key encrypted with delivery key)
 *  4. All subsequent data: Base64( IV + AES-CBC( JSON bytes ) )
 */
public class CloudSaveService {

    // ── Network ──────────────────────────────────────────────────────────
    private static final String SERVER_HOST = "192.168.1.10";
    private static final int    SERVER_PORT = 5005;
    private static final int    CONNECT_TIMEOUT_MS = 3000;
    private static final int    SOCKET_TIMEOUT_MS  = 8000;

    // ── RSA public key (DER, Base64) ─────────────────────────────────────
    private static final String RSA_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuEwRNiBMB0MkhCI7+3Xs"
          + "fsZZWZMvk4WbgjZk7CBMUwyXSnY6vscwMcWIvlVj6BItyfNJP1PFUaaJgfoOFXl1"
          + "vTn1jKiCBdLC14NhLDeW2E7N1XWD3s4YhXWqc91soETgb2TRpYIqfkfiKwdsKwiH"
          + "XemA4XLgrZWHihbTWrPzb1TANd2a2tQWMDqq7QGMka07Yb2L5CBdaZFaX0iDUvcH"
          + "aN2dOguvkmYWrotrd1GVQtvF/bYIWR19QCKvfQZV++vxEUFhcZdjgXXSewN194VS"
          + "QzrMKiwVogXLUTVGBFt1mmF/fXUjzM8oY4SEKBCoiybHPbwbIJLZ43L54PjmlCRf"
          + "1wIDAQAB";

    // ── Crypto constants ─────────────────────────────────────────────────
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String LOCAL_SAVE_FILE = "local_save.dat";

    // ── Heartbeat ────────────────────────────────────────────────────────
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private final AtomicBoolean serverOnline = new AtomicBoolean(false);
    private volatile boolean heartbeatRunning = false;

    // ── Session state (set after successful handshake) ───────────────────
    private byte[] sessionKey;

    // =====================================================================
    //  PUBLIC API
    // =====================================================================

    /** Start the background heartbeat thread. Call once at game startup. */
    public void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;

        Thread heartbeat = new Thread(() -> {
            while (heartbeatRunning) {
                boolean alive = ping();
                serverOnline.set(alive);
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }, "CloudSave-Heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public void stopHeartbeat() { heartbeatRunning = false; }

    public boolean isServerOnline() { return serverOnline.get(); }

    /**
     * Save game state.  Behaviour depends on {@code offlineMode}:
     * <ul>
     *   <li><b>offlineMode = false</b> (Online): stream directly to server from RAM.
     *       No local files are written.</li>
     *   <li><b>offlineMode = true</b> (Offline debug): write encrypted local_save.dat
     *       and attempt background sync to server.</li>
     * </ul>
     */
    public SaveResult save(GameState state, String licenseKey, boolean offlineMode) {

        state.timestamp = System.currentTimeMillis();

        if (offlineMode) {
            SaveResult local = saveLocalEncrypted(state, licenseKey);
            // Background sync attempt
            if (serverOnline.get()) {
                Thread sync = new Thread(() -> uploadToServer(state, licenseKey),
                        "CloudSave-Sync");
                sync.setDaemon(true);
                sync.start();
            }
            return local;
        }

        // Online mode — zero disk
        if (!serverOnline.get()) {
            return SaveResult.fail("Cloud save failed: Server offline (heartbeat).");
        }
        return uploadToServer(state, licenseKey);
    }

    /**
     * Download the latest save from the server.
     * Returns null GameState inside SaveResult on failure.
     */
    public DownloadResult download(String licenseKey) {

        if (!serverOnline.get()) {
            return DownloadResult.fail("Download failed: Server offline (heartbeat).");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            BufferedWriter writer = writer(socket);
            BufferedReader reader = reader(socket);

            if (!handshake(licenseKey, writer, reader)) {
                return DownloadResult.fail("Download failed: authorization rejected.");
            }

            writer.write("DOWNLOAD");
            writer.newLine();
            writer.flush();

            String line = reader.readLine();
            if ("NO_SAVE".equals(line)) {
                return DownloadResult.fail("No save found on server.");
            }

            byte[] decrypted = aesDecrypt(Base64.getDecoder().decode(line), sessionKey);
            String json = new String(decrypted, StandardCharsets.UTF_8);
            return DownloadResult.ok(json);

        } catch (ConnectException e) {
            return DownloadResult.fail("Download failed: Connection refused.");
        } catch (Exception e) {
            return DownloadResult.fail("Download failed: " + e.getMessage());
        }
    }

    // =====================================================================
    //  UPLOAD / HANDSHAKE
    // =====================================================================

    private SaveResult uploadToServer(GameState state, String licenseKey) {
        try {
            byte[] jsonBytes = serializeToJsonBytes(state);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);

                BufferedWriter writer = writer(socket);
                BufferedReader reader = reader(socket);

                if (!handshake(licenseKey, writer, reader)) {
                    return SaveResult.fail("Cloud save authorization failed.");
                }

                writer.write("UPLOAD");
                writer.newLine();
                writer.flush();

                byte[] encrypted = aesEncrypt(jsonBytes, sessionKey);
                String payload = Base64.getEncoder().encodeToString(encrypted);
                writer.write(payload);
                writer.newLine();
                writer.flush();

                String ack = reader.readLine();
                if (ack != null && ack.startsWith("SYNC")) {
                    // Server has a newer save — it sent the newer version back
                    return SaveResult.ok("Cloud save synced (server had newer version).");
                }
                if ("SAVED".equals(ack)) {
                    return SaveResult.ok("Cloud save uploaded.");
                }
                return SaveResult.fail("Cloud save: unexpected server response: " + ack);
            }

        } catch (ConnectException e) {
            serverOnline.set(false);
            return SaveResult.fail("Cloud save failed: Connection refused.");
        } catch (SocketTimeoutException e) {
            return SaveResult.fail("Cloud save failed: Server timeout.");
        } catch (IOException e) {
            return SaveResult.fail("Cloud save failed: " + e.getMessage());
        } catch (GeneralSecurityException e) {
            return SaveResult.fail("Cloud save failed during encryption.");
        } catch (ReflectiveOperationException e) {
            return SaveResult.fail("Cloud save failed during serialization.");
        }
    }

    /**
     * RSA/AES handshake:
     *  1. RSA-encrypt license → Base64 line
     *  2. Wait for AUTH_OK
     *  3. Receive AES-256 session key (encrypted with SHA-256(license))
     */
    private boolean handshake(String licenseKey, BufferedWriter writer, BufferedReader reader)
            throws IOException, GeneralSecurityException {

        // Step 1 — RSA-encrypt license key
        PublicKey pubKey = loadRSAPublicKey();
        Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
        rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encLicense = rsaCipher.doFinal(licenseKey.getBytes(StandardCharsets.UTF_8));

        writer.write(Base64.getEncoder().encodeToString(encLicense));
        writer.newLine();
        writer.flush();

        // Step 2 — Wait for AUTH result
        String authLine = reader.readLine();
        if (!"AUTH_OK".equals(authLine)) return false;

        // Step 3 — Receive AES session key (encrypted with SHA-256(license))
        String keyLine = reader.readLine();
        if (keyLine == null) return false;

        byte[] deliveryKey = MessageDigest.getInstance("SHA-256")
                .digest(licenseKey.getBytes(StandardCharsets.UTF_8));
        byte[] decryptedKey = aesDecrypt(Base64.getDecoder().decode(keyLine), deliveryKey);

        if (decryptedKey.length != 32) return false;
        sessionKey = decryptedKey;
        return true;
    }

    // =====================================================================
    //  OFFLINE / LOCAL ENCRYPTED SAVE
    // =====================================================================

    private SaveResult saveLocalEncrypted(GameState state, String licenseKey) {
        try {
            byte[] jsonBytes = serializeToJsonBytes(state);
            byte[] aesKey = MessageDigest.getInstance("SHA-256")
                    .digest(licenseKey.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = aesEncrypt(jsonBytes, aesKey);

            try (FileOutputStream fos = new FileOutputStream(LOCAL_SAVE_FILE)) {
                fos.write(encrypted);
            }
            return SaveResult.ok("Saved to local_save.dat (offline mode).");

        } catch (Exception e) {
            return SaveResult.fail("Local save failed: " + e.getMessage());
        }
    }

    /** Load a previously saved offline file and return its JSON string. */
    public String loadLocalEncrypted(String licenseKey) {
        try {
            byte[] data;
            try (FileInputStream fis = new FileInputStream(LOCAL_SAVE_FILE)) {
                data = fis.readAllBytes();
            }
            byte[] aesKey = MessageDigest.getInstance("SHA-256")
                    .digest(licenseKey.getBytes(StandardCharsets.UTF_8));
            byte[] decrypted = aesDecrypt(data, aesKey);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    //  HEARTBEAT
    // =====================================================================

    private boolean ping() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), CONNECT_TIMEOUT_MS);
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

    // =====================================================================
    //  CRYPTO HELPERS
    // =====================================================================

    private static PublicKey loadRSAPublicKey() throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(RSA_PUBLIC_KEY_B64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    static byte[] aesEncrypt(byte[] plain, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[16];
        SECURE_RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(enc, 0, out, iv.length, enc.length);
        return out;
    }

    static byte[] aesDecrypt(byte[] data, byte[] key) throws GeneralSecurityException {
        byte[] iv = Arrays.copyOfRange(data, 0, 16);
        byte[] enc = Arrays.copyOfRange(data, 16, data.length);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(enc);
    }

    // =====================================================================
    //  JSON SERIALIZATION (zero-dependency fallback)
    // =====================================================================

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

        // Generic object — public fields
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

    // =====================================================================
    //  SOCKET HELPERS
    // =====================================================================

    private static BufferedWriter writer(Socket s) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    // =====================================================================
    //  RESULT TYPES
    // =====================================================================

    public record SaveResult(boolean ok, String message) {
        public static SaveResult ok(String msg) { return new SaveResult(true, msg); }
        public static SaveResult fail(String msg) { return new SaveResult(false, msg); }
    }

    public record DownloadResult(boolean ok, String json, String message) {
        public static DownloadResult ok(String json) { return new DownloadResult(true, json, "Downloaded."); }
        public static DownloadResult fail(String msg) { return new DownloadResult(false, null, msg); }
    }
}
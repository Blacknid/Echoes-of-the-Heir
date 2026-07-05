package platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Online license activation/login, shared by every backend (desktop, Android — no more
 * per-platform machine-fingerprint code, since licensing lives entirely on the save server now).
 *
 * <p>First run: {@link #ensureActivated()} calls the save server's ACTIVATE handshake, which
 * issues a brand-new license_key and hands back:
 * <ul>
 *   <li>the plaintext license_key itself (AEAD-wrapped in transit, told to the client exactly
 *       once — it's needed in memory as the account identifier for every save/friend/MP call)</li>
 *   <li>an opaque {@code activation_id} plus an AES-GCM-encrypted blob of the license_key,
 *       encrypted with a random key the server keeps to itself alongside the license_key in its
 *       {@code licenses} table</li>
 * </ul>
 * Only {@code activation_id} and the encrypted blob are ever persisted locally, in
 * {@value #ACTIVATION_FILE} via {@link GameStorage} — the plaintext license_key is never written
 * to disk.
 *
 * <p>Every later run: {@link #ensureActivated()} sends {@code activation_id} + the encrypted blob
 * back over LOGIN; the server decrypts the blob (it alone holds the per-account key) and this
 * class caches the confirmed license_key in memory only, for that process's lifetime.
 *
 * <p>Registers itself with {@link License} on success, same as the old per-platform managers did.
 */
public final class LicenseActivation implements LicenseCheck {

    public static final LicenseActivation INSTANCE = new LicenseActivation();

    private static final String ACTIVATION_FILE = "activation.dat";
    private static final int DEFAULT_PORT = 5005;
    private static final String[] FALLBACK_HOSTS = { "192.168.137.14", "192.168.137.126" };
    private static final String SAVE_SERVERS_FILE = "save_servers.txt";

    private static final String PROTOCOL_TAG = "v2";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 8000;
    private static final byte[] ISSUANCE_INFO = "michi-issuance-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ISSUED_LICENSE_AAD = "MichiIssuedLicense".getBytes(StandardCharsets.UTF_8);

    // Server's transport RSA public key — same keypair CloudSaveService/MultiplayerClient use
    // to encrypt the ACTIVATE/LOGIN envelope. Not a "license signing" key: it never authenticates
    // anything by itself, it just keeps the handshake payload private in transit.
    private static final String RSA_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuEwRNiBMB0MkhCI7+3Xs"
          + "fsZZWZMvk4WbgjZk7CBMUwyXSnY6vscwMcWIvlVj6BItyfNJP1PFUaaJgfoOFXl1"
          + "vTn1jKiCBdLC14NhLDeW2E7N1XWD3s4YhXWqc91soETgb2TRpYIqfkfiKwdsKwiH"
          + "XemA4XLgrZWHihbTWrPzb1TANd2a2tQWMDqq7QGMka07Yb2L5CBdaZFaX0iDUvcH"
          + "aN2dOguvkmYWrotrd1GVQtvF/bYIWR19QCKvfQZV++vxEUFhcZdjgXXSewN194VS"
          + "QzrMKiwVogXLUTVGBFt1mmF/fXUjzM8oY4SEKBCoiybHPbwbIJLZ43L54PjmlCRf"
          + "1wIDAQAB";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private volatile String cachedKey;
    private volatile String activationId;
    private volatile String encBlobB64;

    private LicenseActivation() {}

    @Override public boolean verifyCurrent() { return cachedKey != null; }
    @Override public String getActivationId() { return activationId; }
    @Override public String getEncBlob() { return encBlobB64; }
    @Override public String getCachedKey() { return cachedKey; }
    @Override public boolean isTampered() { return false; }

    /**
     * Ensures this install has a license: logs in with a previously persisted activation if one
     * exists, otherwise activates fresh online and persists the result. Safe to call on every
     * boot — it only ever does one network round trip.
     *
     * @return the plaintext license_key on success, or null if no save server was reachable this
     *         run (offline first boot, or a transient outage on a later boot — caller should keep
     *         running license-less and retry next launch).
     */
    public static String ensureActivated() {
        LicenseActivation m = INSTANCE;
        Properties existing = readLocal();
        String existingId = existing == null ? "" : existing.getProperty("activation_id", "").trim();
        String existingBlob = existing == null ? "" : existing.getProperty("enc_blob", "").trim();
        boolean isLogin = !existingId.isEmpty() && !existingBlob.isEmpty();

        for (Endpoint ep : serverPool()) {
            try {
                Result r = m.handshake(ep, isLogin, existingId, existingBlob);
                if (r == null) continue;
                if (!isLogin) writeLocal(r.activationId, r.encBlobB64);
                m.activationId = r.activationId;
                m.encBlobB64 = r.encBlobB64;
                m.cachedKey = r.licenseKey;
                License.set(m);
                System.out.println(isLogin ? "[License] Logged in." : "[License] Activated online — license issued.");
                return r.licenseKey;
            } catch (Exception ignored) {
                // try next endpoint
            }
        }
        System.out.println("[License] No save server reachable — cannot "
                + (isLogin ? "log in" : "activate") + " yet.");
        return null;
    }

    private record Result(String licenseKey, String activationId, String encBlobB64) {}

    private Result handshake(Endpoint ep, boolean isLogin, String activationId, String encBlobB64)
            throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);

            byte[] clientNonce = new byte[16];
            SECURE_RANDOM.nextBytes(clientNonce);
            writeLine(w, "HELLO " + PROTOCOL_TAG + " " + Base64.getEncoder().encodeToString(clientNonce));

            String okLine = r.readLine();
            if (okLine == null || !okLine.startsWith("OK ")) return null;
            byte[] serverNonce = Base64.getDecoder().decode(okLine.substring(3));
            if (serverNonce.length != 16) return null;

            String handshakeJson = "{"
                    + "\"ts\":" + (System.currentTimeMillis() / 1000L) + ","
                    + "\"client_nonce\":\"" + toHex(clientNonce) + "\","
                    + "\"server_nonce\":\"" + toHex(serverNonce) + "\""
                    + "}";
            byte[] enc = rsaOaepEncrypt(handshakeJson.getBytes(StandardCharsets.UTF_8));
            String encB64 = Base64.getEncoder().encodeToString(enc);

            if (isLogin) {
                writeLine(w, "LOGIN " + encB64 + " " + activationId + " " + encBlobB64);
            } else {
                writeLine(w, "ACTIVATE " + encB64);
            }

            String authLine = r.readLine();
            if (authLine == null || !authLine.startsWith("AUTH_OK ")) return null;
            String[] parts = authLine.split(" ");

            if (isLogin) {
                // "AUTH_OK <enc_session_b64>" — LOGIN doesn't repeat the license_key; we already
                // know it (it's how we got here) so just confirm success.
                if (parts.length != 2) return null;
                return new Result(cachedKey, activationId, encBlobB64);
            }

            // "AUTH_OK <enc_session_b64> <activation_id> <enc_blob_b64> <enc_license_key_b64>"
            if (parts.length != 5) return null;
            String newActivationId = parts[2];
            String newEncBlobB64 = parts[3];
            // issuance_key is derived purely from the nonces (both sides already share those at
            // this point) — NOT from license_key, since the client doesn't know it yet; that's
            // the value being delivered right now. Must match the server's hkdf() call exactly.
            byte[] issuanceKey = hkdf(concat(clientNonce, serverNonce), serverNonce, ISSUANCE_INFO, 32);
            byte[] encLicenseKey = Base64.getDecoder().decode(parts[4]);
            byte[] licenseKeyBytes = aesGcmDecrypt(encLicenseKey, issuanceKey,
                    java.util.Arrays.copyOfRange(clientNonce, 0, 12), ISSUED_LICENSE_AAD);
            String licenseKey = new String(licenseKeyBytes, StandardCharsets.UTF_8);
            return new Result(licenseKey, newActivationId, newEncBlobB64);
        }
    }

    private static Properties readLocal() {
        if (!GameStorage.exists(ACTIVATION_FILE)) return null;
        try (var in = GameStorage.inputStream(ACTIVATION_FILE)) {
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeLocal(String activationId, String encBlobB64) {
        try (OutputStream out = GameStorage.outputStream(ACTIVATION_FILE)) {
            Properties p = new Properties();
            p.setProperty("activation_id", activationId);
            p.setProperty("enc_blob", encBlobB64);
            p.store(out, "Michi's Adventure — online license activation (no plaintext license key)");
        } catch (IOException e) {
            System.out.println("[License] Failed to persist activation: " + e.getMessage());
        }
    }

    private record Endpoint(String host, int port) {}

    private static List<Endpoint> serverPool() {
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
            } catch (IOException ignored) {}
        }
        if (list.isEmpty()) {
            for (String host : FALLBACK_HOSTS) list.add(new Endpoint(host, DEFAULT_PORT));
        }
        return list;
    }

    private static void writeLine(Writer w, String line) throws IOException {
        w.write(line);
        w.write("\n");
        w.flush();
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

    private static byte[] aesGcmDecrypt(byte[] ct, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(ct);
    }

    /** RFC 5869 HKDF-SHA256, extract+expand. */
    private static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(secret);

        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
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

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}

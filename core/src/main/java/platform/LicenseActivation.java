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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    /**
     * Owner-only escape hatch, read from disk next to the game — NEVER hardcoded, never shipped.
     * itch never issues an OAuth access_token to the developer's own account (only to accounts
     * that bought the game through the storefront), so the normal browser flow can never
     * activate the owner's install. If this file exists, its contents (trimmed) are sent as the
     * itch_token instead, and a server with a matching MICHI_ITCH_OWNER_SECRET accepts it without
     * ever calling itch. Gitignored via the blanket *.dat rule; back it up like any other secret.
     */
    private static final String OWNER_SECRET_FILE = "owner_secret.dat";
    private static final int DEFAULT_PORT = 5005;
    // Public hosts first: the 192.168.* entries are LAN-only and unreachable from a player's machine,
    // so a shipped build that falls back to them can never activate. Kept after the public hosts as a
    // dev convenience only. Production endpoints normally come from save_servers.txt.
    private static final String[] FALLBACK_HOSTS = { "142.93.103.51", "128.127.115.96", "192.168.137.14", "192.168.137.126", "192.168.1.9", "192.168.1.212" };
    private static final String SAVE_SERVERS_FILE = "save_servers.txt";

    private static final String PROTOCOL_TAG = "v2";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 8000;
    private static final byte[] ISSUANCE_INFO = "michi-issuance-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ISSUED_LICENSE_AAD = "MichiIssuedLicense".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ITCH_TOKEN_INFO = "michi-itchtoken-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ITCH_TOKEN_AAD = "MichiItchToken".getBytes(StandardCharsets.UTF_8);

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
    /** Set once the server has answered AUTH_OK this run (via ACTIVATE or LOGIN). */
    private volatile boolean confirmed;
    /** Player-facing reason the last activation attempt failed; null when it succeeded. */
    private volatile String lastError;

    /**
     * Trips when {@link #ensureActivated()} has finished — succeeded OR failed. It does NOT mean
     * "licensed"; ask {@link #verifyCurrent()} for that. It only means the answer is in.
     *
     * <p>Activation runs on a background thread (MichiGame.create() starts it, so the window doesn't
     * freeze on a multi-second network round trip), but the cloud-save load is gated on
     * {@code License.verifyCurrent()}. Anything that loads a save therefore has to WAIT for this
     * instead of reading a half-initialized answer: pressing CONTINUE before activation landed used
     * to see verifyCurrent()==false, silently skip the cloud download, and load a stale local save
     * instead — a pure race, so the same button did different things depending on click speed.
     */
    private static final CountDownLatch SETTLED = new CountDownLatch(1);

    private LicenseActivation() {}

    /**
     * Block until activation has settled (succeeded or failed), or {@code timeoutMs} elapses.
     * Returns true if it settled in time. Never call from the render thread without a timeout.
     */
    public static boolean awaitSettled(long timeoutMs) {
        try {
            return SETTLED.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True once {@link #ensureActivated()} has run to completion this launch, licensed or not. */
    public static boolean hasSettled() { return SETTLED.getCount() == 0; }

    /**
     * True once the save server has confirmed this install's license this run.
     *
     * <p>Deliberately does NOT require {@link #cachedKey}: only ACTIVATE (first ever run) discloses
     * the plaintext license_key. On every later run we authenticate with LOGIN, and the server —
     * which alone holds enc_key — decrypts enc_blob, verifies it, and answers AUTH_OK without
     * echoing the key back. A successful LOGIN is therefore itself the proof of license. Gating on
     * cachedKey != null here silently marked every returning player unlicensed and disabled their
     * cloud saves.
     */
    @Override public boolean verifyCurrent() { return confirmed; }
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
        try {
            return activate();
        } finally {
            // In a finally so that NO exit path — early return, thrown exception — can leave a
            // caller blocked in awaitSettled() forever waiting for an answer that already came.
            SETTLED.countDown();
        }
    }

    private static String activate() {
        LicenseActivation m = INSTANCE;
        Properties existing = readLocal();
        String existingId = existing == null ? "" : existing.getProperty("activation_id", "").trim();
        String existingBlob = existing == null ? "" : existing.getProperty("enc_blob", "").trim();
        boolean isLogin = !existingId.isEmpty() && !existingBlob.isEmpty();

        m.lastError = null;

        // Proof of purchase is needed ONLY when this install has no license yet. A returning
        // player logs in with the credentials they already hold and never sees a browser —
        // the license is ours, not itch's, and it keeps working offline forever.
        String itchToken = null;
        if (!isLogin) {
            String ownerSecret = readOwnerSecret();
            if (ownerSecret != null) {
                itchToken = "ownerkey:" + ownerSecret;
                System.out.println("[License] Using owner secret bypass — skipping itch.io OAuth.");
            } else {
                itchToken = ItchAuthProvider.tokenOrNull();
                if (itchToken == null) {
                    System.out.println("[License] No itch.io proof of purchase obtained — the server "
                            + "will refuse activation if its purchase gate is on.");
                }
            }
        }

        for (Endpoint ep : serverPool()) {
            try {
                Result r = m.handshake(ep, isLogin, existingId, existingBlob, itchToken);
                if (r == null) {
                    // A definitive verdict (not owned / itch down) is the server's final answer.
                    // Trying the next endpoint would just re-ask the same question — and, worse,
                    // burn the one-shot itch token against a server that already rejected it.
                    if (m.lastError != null) return null;
                    continue;  // this endpoint is simply unreachable — try the next one
                }
                if (!isLogin) writeLocal(r.activationId, r.encBlobB64);
                m.activationId = r.activationId;
                m.encBlobB64 = r.encBlobB64;
                // Set on both paths: ACTIVATE issues the key, LOGIN re-delivers it (CloudSaveService
                // derives its session delivery_key from it, so it's needed every run). Stays null
                // only against an older server that doesn't send it — hence `confirmed`, not
                // cachedKey, is what marks this install licensed.
                m.cachedKey = r.licenseKey;
                m.confirmed = true;
                License.set(m);
                System.out.println(isLogin ? "[License] Logged in." : "[License] Activated online — license issued.");
                return r.licenseKey;
            } catch (java.io.IOException netFailure) {
                // Endpoint unreachable or the connection dropped — worth trying the next one.
            } catch (Exception bug) {
                // A crypto/parse failure is OUR bug, not an outage, and it will fail identically
                // against every endpoint. Swallowing it silently is what let the oversized-RSA
                // -envelope bug masquerade as "couldn't reach the license server" for every buyer.
                System.out.println("[License] Handshake failed against " + ep.host + ": " + bug);
            }
        }
        if (m.lastError == null) {
            m.lastError = "Couldn't reach the license server. Check your internet connection.";
        }
        System.out.println("[License] No save server reachable — cannot "
                + (isLogin ? "log in" : "activate") + " yet.");
        return null;
    }

    /** Why the last {@link #ensureActivated()} failed, phrased for the player. Null if it worked. */
    public static String lastError() { return INSTANCE.lastError; }

    private record Result(String licenseKey, String activationId, String encBlobB64) {}

    private Result handshake(Endpoint ep, boolean isLogin, String activationId, String encBlobB64,
                             String itchToken) throws Exception {
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
                // The itch token travels in its own AES-GCM box, NOT inside the RSA envelope:
                // it is a live credential for the player's itch account, so it must never cross
                // the wire in the clear — but RSA-2048 with OAEP-SHA256 can only carry 190 bytes
                // of plaintext, and the nonces alone already spend 117 of them. A real itch token
                // pushed the envelope past the limit, rsaOaepEncrypt() threw, and the buyer's
                // ACTIVATE was never even sent. The box is keyed off the two nonces, which both
                // sides already share here, so it costs no extra round trip and has no size cap.
                String itchField = "";
                if (itchToken != null && !itchToken.isBlank()) {
                    byte[] itchKey = hkdf(concat(clientNonce, serverNonce), serverNonce,
                            ITCH_TOKEN_INFO, 32);
                    byte[] box = aesGcmEncrypt(itchToken.getBytes(StandardCharsets.UTF_8), itchKey,
                            java.util.Arrays.copyOfRange(clientNonce, 0, 12), ITCH_TOKEN_AAD);
                    itchField = " " + Base64.getEncoder().encodeToString(box);
                }
                writeLine(w, "ACTIVATE " + encB64 + itchField);
            }

            String authLine = r.readLine();
            if (authLine == null || !authLine.startsWith("AUTH_OK ")) {
                // Distinguish "you don't own the game" from "our licence server is having a
                // bad day" — otherwise a server-side outage reads to the player as an accusation
                // of piracy, and they have no idea whether to buy again or just wait.
                if ("ITCH_NOT_OWNED".equals(authLine)) {
                    lastError = "itch.io says this account hasn't purchased Michi's Adventure. "
                            + "Make sure you signed in with the account you bought it on.";
                } else if ("ITCH_UNAVAILABLE".equals(authLine)) {
                    lastError = "Couldn't reach itch.io to verify your purchase. "
                            + "This is on our side — please try again in a few minutes.";
                } else if ("AUTH_FAIL".equals(authLine)) {
                    lastError = "The server rejected this activation.";
                }
                if (lastError != null) System.out.println("[License] " + lastError);
                return null;
            }
            String[] parts = authLine.split(" ");

            // issuance_key is derived purely from the nonces (both sides already share those at
            // this point) — NOT from license_key, since on ACTIVATE the client doesn't know it yet;
            // that's the value being delivered. Must match the server's hkdf() call exactly.
            byte[] issuanceKey = hkdf(concat(clientNonce, serverNonce), serverNonce, ISSUANCE_INFO, 32);

            if (isLogin) {
                // "AUTH_OK <enc_session_b64> <enc_license_key_b64>" — the key is re-delivered every
                // run because we never persist it, yet CloudSaveService derives its session
                // delivery_key from it. A 2-token reply means an older server that doesn't send it:
                // stay licensed (the handshake still proved it), just without cloud access.
                if (parts.length < 2) return null;
                if (parts.length < 3) return new Result(null, activationId, encBlobB64);
                String relicensed = decryptIssuedKey(parts[2], issuanceKey, clientNonce);
                return new Result(relicensed, activationId, encBlobB64);
            }

            // "AUTH_OK <enc_session_b64> <activation_id> <enc_blob_b64> <enc_license_key_b64>"
            if (parts.length != 5) return null;
            String newActivationId = parts[2];
            String newEncBlobB64 = parts[3];
            String licenseKey = decryptIssuedKey(parts[4], issuanceKey, clientNonce);
            return new Result(licenseKey, newActivationId, newEncBlobB64);
        }
    }

    /** Unwrap the AEAD-wrapped license_key the server delivers on ACTIVATE and LOGIN. */
    private static String decryptIssuedKey(String encB64, byte[] issuanceKey, byte[] clientNonce)
            throws GeneralSecurityException {
        byte[] ct = Base64.getDecoder().decode(encB64);
        byte[] plain = aesGcmDecrypt(ct, issuanceKey,
                java.util.Arrays.copyOfRange(clientNonce, 0, 12), ISSUED_LICENSE_AAD);
        return new String(plain, StandardCharsets.UTF_8);
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

    /** The owner's secret from {@link #OWNER_SECRET_FILE}, trimmed, or null if absent/empty. */
    private static String readOwnerSecret() {
        if (!GameStorage.exists(OWNER_SECRET_FILE)) return null;
        try (BufferedReader br = GameStorage.bufferedReader(OWNER_SECRET_FILE)) {
            String secret = br.readLine();
            secret = secret == null ? "" : secret.trim();
            return secret.isEmpty() ? null : secret;
        } catch (IOException e) {
            System.out.println("[License] Failed to read " + OWNER_SECRET_FILE + ": " + e.getMessage());
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

    private static byte[] aesGcmEncrypt(byte[] plain, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
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

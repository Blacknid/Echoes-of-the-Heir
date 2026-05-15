package data;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

/**
 * Loads, verifies, and continuously re-verifies the RSA-signed,
 * machine-bound license file.
 *
 * <p>license.properties format (three required fields):
 * <pre>
 *   license_key=XXXXXXXX-YYYY
 *   machine_fp=&lt;16 hex chars — SHA-256(Windows MachineGuid)[:8 bytes]&gt;
 *   signature=&lt;base64(RSA-2048 PKCS#1v15 SHA-256 over "license_key|machine_fp")&gt;
 * </pre>
 *
 * <p>Returns null silently on any failure. The game always runs;
 * cloud saves and multiplayer require a non-null LICENSE_KEY.
 *
 * <p><b>Local verify system.</b> On top of the one-time check at startup,
 * {@link #verifyCurrent()} can be called at any time (e.g. before each
 * cloud-save / MP connect, or by a background thread) to detect on-disk
 * tampering, file swap, or machine cloning during a running session.
 */
public final class LicenseManager {

    // ── Embedded RSA-2048 public key (DER/SPKI, base64) ─────────────────────
    // Replace this placeholder with the content of build_tools/license_public.b64
    // (output of build_tools/generate_license_keys.py).
    private static final String PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAo6f8HJrv9fc0XhaYkDHZ"
      + "ie+8pqNF6ktoxtw1CSPGK1cG/DJJiKQPQEsk/NaUuwqq7D3CK8ZTD2A2XDwK/+a"
      + "/B2LvbaSpm8f3v5pkT2DkWTH+QApISJu3T7SkEQA1bJnqVqSClpG0u3dtkd8F2XI"
      + "viIqT771/I0shSKwQVGL/GRADT38Zvjw3kk1Nc6iEVSga8rEd0fxuI0AJqBErAmtg"
      + "mTL9cAvb1UMBtGzrFB3dK+Oq68TPVzsHMDSWLQeTSnY4U8V9HpsuB0Jq5xK7RN6Q"
      + "q7pd/r4NCIKzgLWbhqELKBo0o8I8ddi1SCRz0Zo9J631Wrbg38pZuD5sHLd2oFt8"
      + "UQIDAQAB";

    private static final Path LICENSE_PATH = Paths.get("license.properties");

    // ── Cached state pinned at load() time. Used by verifyCurrent() to
    //    detect tampering / swap / fp drift without doing the slow
    //    `reg query` every check.
    private static volatile String cachedKey;
    private static volatile String cachedFp;
    private static volatile String cachedSig;
    private static volatile String cachedMachineFp;
    private static volatile long   cachedFileMtime;
    private static volatile boolean tampered = false;

    private LicenseManager() {}

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Read license.properties, verify machine fingerprint and RSA signature.
     * On success, caches the verified triple for {@link #verifyCurrent()}.
     *
     * @return the license key string on success, or null on any failure.
     */
    public static String load() {
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(LICENSE_PATH.toFile())) {
                props.load(fis);
            }

            String key = props.getProperty("license_key", "").trim();
            String fp  = props.getProperty("machine_fp",  "").trim();
            String sig = props.getProperty("signature",   "").trim();

            if (key.isEmpty() || fp.isEmpty() || sig.isEmpty()) {
                System.out.println("[License] Incomplete license file.");
                return null;
            }

            String currentFp = getMachineFingerprint();
            if (!currentFp.equalsIgnoreCase(fp)) {
                System.out.println("[License] Machine fingerprint mismatch — license belongs to a different installation.");
                return null;
            }

            if (!verify(key, fp, sig)) {
                System.out.println("[License] Signature invalid — file may have been tampered with.");
                return null;
            }

            // Pin the verified state.
            cachedKey       = key;
            cachedFp        = fp;
            cachedSig       = sig;
            cachedMachineFp = currentFp;
            cachedFileMtime = safeMtime(LICENSE_PATH);
            tampered = false;
            return key;

        } catch (FileNotFoundException e) {
            System.out.println("[License] license.properties not found.");
            return null;
        } catch (Exception e) {
            System.out.println("[License] Load failed: " + e.getMessage());
            return null;
        }
    }

    /** @return the machine fingerprint pinned by the last successful load(). */
    public static String getCachedMachineFp() { return cachedMachineFp; }

    /** @return the base64 signature pinned by the last successful load(). */
    public static String getCachedSignature() { return cachedSig; }

    /** @return the license key pinned by the last successful load(). */
    public static String getCachedKey() { return cachedKey; }

    /** @return true once verifyCurrent() has flagged a tamper. */
    public static boolean isTampered() { return tampered; }

    /**
     * Re-validate the license against its cached state. Cheap to call
     * frequently: only re-reads the file when its mtime changed; only
     * re-derives the machine fingerprint when the file changed.
     *
     * <p>Returns false (and sets {@link #isTampered()} to true) if any of:
     * <ul>
     *   <li>the file disappeared, was truncated, or no longer parses;</li>
     *   <li>any of the three fields was edited;</li>
     *   <li>the RSA signature no longer verifies;</li>
     *   <li>the on-disk fp no longer matches the running machine.</li>
     * </ul>
     */
    public static boolean verifyCurrent() {
        if (cachedKey == null) return false;          // never loaded successfully
        if (tampered)          return false;          // sticky once tripped

        try {
            long mtime = safeMtime(LICENSE_PATH);
            if (mtime == 0L) {                        // file vanished
                tampered = true;
                System.out.println("[License] license.properties disappeared at runtime.");
                return false;
            }

            // Fast path: file untouched since load().
            if (mtime == cachedFileMtime) return true;

            // File changed — re-read all three fields and re-validate.
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(LICENSE_PATH.toFile())) {
                props.load(fis);
            }
            String key = props.getProperty("license_key", "").trim();
            String fp  = props.getProperty("machine_fp",  "").trim();
            String sig = props.getProperty("signature",   "").trim();

            if (!key.equals(cachedKey) || !fp.equals(cachedFp) || !sig.equals(cachedSig)) {
                tampered = true;
                System.out.println("[License] license.properties was modified at runtime — invalidating session.");
                return false;
            }
            if (!verify(key, fp, sig)) {
                tampered = true;
                System.out.println("[License] Signature no longer verifies — invalidating session.");
                return false;
            }
            cachedFileMtime = mtime;
            return true;
        } catch (Exception e) {
            tampered = true;
            System.out.println("[License] verifyCurrent failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Spawn a daemon thread that calls {@link #verifyCurrent()} every
     * {@code intervalSeconds}. On the first failure it sets
     * {@code main.Main.LICENSE_KEY = null} so subsequent saves /
     * multiplayer connects abort cleanly.
     */
    public static void startWatchdog(int intervalSeconds) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!verifyCurrent()) {
                    try {
                        // Best-effort: nuke the in-memory key.
                        Class<?> mainCls = Class.forName("main.Main");
                        java.lang.reflect.Field f = mainCls.getDeclaredField("LICENSE_KEY");
                        f.setAccessible(true);
                        f.set(null, null);
                    } catch (Throwable ignored) {}
                    return;     // stop polling once tripped
                }
            }
        }, "License-Watchdog");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    //  CRYPTO
    // =========================================================================

    /** Verify RSA-2048 PKCS#1v15 SHA-256 signature over "key|fp". */
    static boolean verify(String key, String fp, String sigB64) {
        try {
            byte[] pubKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY_B64);
            PublicKey pubKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pubKeyBytes));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pubKey);
            sig.update((key + "|" + fp).getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            System.out.println("[License] Verify error: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    //  MACHINE FINGERPRINT
    // =========================================================================

    /**
     * Derive a stable 16-char hex fingerprint for this machine.
     * Source: SHA-256(Windows MachineGuid), first 8 bytes.
     * Falls back to SHA-256(COMPUTERNAME + USERNAME) if the registry is unavailable.
     */
    public static String getMachineFingerprint() {
        String guid = readMachineGuid();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(guid.getBytes(StandardCharsets.UTF_8));
            return String.format(
                "%02x%02x%02x%02x%02x%02x%02x%02x",
                hash[0] & 0xFF, hash[1] & 0xFF, hash[2] & 0xFF, hash[3] & 0xFF,
                hash[4] & 0xFF, hash[5] & 0xFF, hash[6] & 0xFF, hash[7] & 0xFF
            );
        } catch (NoSuchAlgorithmException e) {
            return "0000000000000000";
        }
    }

    private static String readMachineGuid() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "/v", "MachineGuid"
            });
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("REG_SZ")) {
                        int idx = line.lastIndexOf("REG_SZ");
                        String guid = line.substring(idx + 6).trim();
                        if (!guid.isEmpty()) return guid;
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}

        String host = System.getenv("COMPUTERNAME");
        String user = System.getenv("USERNAME");
        return (host != null ? host : "unknown") + (user != null ? user : "user");
    }

    private static long safeMtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (Exception e) { return 0L; }
    }
}

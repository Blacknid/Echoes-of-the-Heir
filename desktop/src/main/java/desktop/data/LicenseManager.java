package desktop.data;

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

import platform.License;
import platform.LicenseCheck;

/**
 * Loads, verifies, and continuously re-verifies the RSA-signed, machine-bound license file.
 *
 * license.properties format (three required fields):
 *   license_key=XXXXXXXX-YYYY
 *   machine_fp=&lt;16 hex chars — SHA-256(Windows MachineGuid)[:8 bytes]&gt;
 *   signature=&lt;base64(RSA-2048 PKCS#1v15 SHA-256 over "license_key|machine_fp")&gt;
 *
 * Returns null silently on any failure. The game always runs; cloud saves and multiplayer
 * require a non-null LICENSE_KEY. {@link #verifyCurrent()} can be called at any time to detect
 * on-disk tampering, file swap, or machine cloning during a running session.
 */
public final class LicenseManager implements LicenseCheck {

    public static final LicenseManager INSTANCE = new LicenseManager();

    // Replace this placeholder with the content of build_tools/license_public.b64
    // (output of build_tools/generate_license_keys.py).
    private static final String PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA+fKgLpa8kluHVEryc7//" +
      "SsrLfy+Q5Q7YXRFApBwTBJ4ur81ScoTy8LZjvGE6L+OB1YD/dAHLs2/jyH/3e2CJ" +
      "30mdjpwq8S4l4t1iIQj9t3aJj6eEedRf6Rbjsovx6099tgGidyPg4yrCXBZ7CrrL" +
      "+MNEw9FnAnFulUrI/hv2zfakBJmaMh4CfL5uZBRXKUb1RRbbPYExqrPd9DNKDDnI" +
      "f4m2+Y640+dfB7ZMJpbrieRwh1sJzQsQGom3cAx2Wv5RtUQSAJVWmoBFDWbhephK" +
      "WPg816PKHaOeGs5zlaRA4s6DJ+Oq6rnMa471fDaSZetIceXttxxRhPyDwoYDOB6g" +
      "0wIDAQAB";

    /**
     * Resolved against the directory containing the running JAR (or the
     * working directory in a dev classpath run), so the game finds its
     * license regardless of where it was launched from.
     */
    private static final Path LICENSE_PATH = resolveLicensePath();

    private static Path resolveLicensePath() {
        try {
            java.net.URL u = LicenseManager.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            Path codeLoc = Paths.get(u.toURI());
            Path base = Files.isDirectory(codeLoc) ? codeLoc : codeLoc.getParent();
            if (base != null) return base.resolve("license.properties");
        } catch (Exception ignored) {}
        return Paths.get("license.properties");
    }

    // Cached state pinned at load() time, used by verifyCurrent() to avoid a `reg query` every check.
    private volatile String cachedKey;
    private volatile String cachedFp;
    private volatile String cachedSig;
    private volatile String cachedMachineFp;
    private volatile long   cachedFileMtime;
    private volatile boolean tampered = false;

    private LicenseManager() {}

    /**
     * Read license.properties, verify machine fingerprint and RSA signature.
     * On success, caches the verified triple and registers this instance with {@link License}.
     *
     * @return the license key string on success, or null on any failure.
     */
    public static String load() {
        LicenseManager m = INSTANCE;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(LICENSE_PATH.toFile())) {
                props.load(fis);
            }

            String key = props.getProperty("license_key", "").trim();
            String fp  = props.getProperty("machine_fp",  "").trim();
            // Strip all whitespace (installers sometimes embed \r\n inside the base64 value,
            // which Properties.load would otherwise silently truncate).
            String sig = props.getProperty("signature",   "").replaceAll("\\s", "");

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
            m.cachedKey       = key;
            m.cachedFp        = fp;
            m.cachedSig       = sig;
            m.cachedMachineFp = currentFp;
            m.cachedFileMtime = safeMtime(LICENSE_PATH);
            m.tampered = false;
            License.set(m);
            return key;

        } catch (FileNotFoundException e) {
            System.out.println("[License] license.properties not found.");
            return null;
        } catch (Exception e) {
            System.out.println("[License] Load failed: " + e.getMessage());
            return null;
        }
    }

    @Override public String getCachedMachineFp() { return cachedMachineFp; }
    @Override public String getCachedSignature() { return cachedSig; }
    @Override public String getCachedKey() { return cachedKey; }
    @Override public boolean isTampered() { return tampered; }

    /**
     * Re-validate the license against its cached state. Cheap to call frequently: only
     * re-reads the file when its mtime changed; only re-derives the machine fingerprint
     * when the file changed.
     *
     * Returns false (and sets isTampered() true) if the file vanished/was truncated, any
     * cached field was edited, the signature no longer verifies, or the on-disk fp no longer
     * matches this machine.
     */
    @Override
    public boolean verifyCurrent() {
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
     * Spawn a daemon thread that calls verifyCurrent() every intervalSeconds. On the first
     * failure it calls main.Main.invalidateLicense() so subsequent saves/MP connects abort.
     */
    public static void startWatchdog(int intervalSeconds) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!INSTANCE.verifyCurrent()) {
                    try { main.Main.invalidateLicense(); }
                    catch (Throwable ignored) {}
                    return;     // stop polling once tripped
                }
            }
        }, "License-Watchdog");
        t.setDaemon(true);
        t.start();
    }

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

package data;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

/**
 * DEV-ONLY build-time utility.
 * Generates a signed, machine-bound license.properties for local development
 * and testing (mirrors what the Inno Setup installer does at production install time).
 *
 * NOT needed for production — the installer (setup_init.iss) handles generation.
 * EXCLUDE this class from the production JAR build.
 *
 * Usage:
 *   java data.LicenseGenerator [outputPath [salt [privateKeyB64File]]]
 *
 *   outputPath        — target file (default: license.properties)
 *   salt              — license_salt from server_config.json (default: MichiCloudSalt2026)
 *   privateKeyB64File — path to a PKCS#8 DER base64 file for signing
 *                       (obtain from build_tools/generate_license_keys.py output;
 *                        if omitted, writes an UNSIGNED file — cloud saves will fail)
 *
 * Each invocation produces a different random license key.
 */
public class LicenseGenerator {

    private static final String DEFAULT_SALT = "MichiCloudSalt2026";
    private static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public static void main(String[] args) throws Exception {

        String outputPath      = args.length > 0 ? args[0] : "license.properties";
        String salt            = args.length > 1 ? args[1] : DEFAULT_SALT;
        String privKeyB64File  = args.length > 2 ? args[2] : null;

        String prefix     = generatePrefix();
        String suffix     = computeSuffix(prefix, salt);
        String licenseKey = prefix + "-" + suffix;
        String machineFp  = LicenseManager.getMachineFingerprint();

        Properties props = new Properties();
        props.setProperty("license_key", licenseKey);
        props.setProperty("machine_fp",  machineFp);

        if (privKeyB64File != null) {
            String b64 = new String(Files.readAllBytes(Paths.get(privKeyB64File)),
                                    StandardCharsets.UTF_8).trim();
            byte[] keyBytes = Base64.getDecoder().decode(b64);
            PrivateKey privKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privKey);
            sig.update((licenseKey + "|" + machineFp).getBytes(StandardCharsets.UTF_8));
            props.setProperty("signature", Base64.getEncoder().encodeToString(sig.sign()));
            System.out.println("Signed with private key from: " + privKeyB64File);
        } else {
            props.setProperty("signature", "UNSIGNED_DEV_PLACEHOLDER");
            System.out.println("WARNING: No private key supplied — signature is a placeholder.");
            System.out.println("         LicenseManager.load() will return null (cloud saves disabled).");
            System.out.println("         Pass path to PKCS#8 b64 key as third arg to sign properly.");
        }

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            props.store(fos, "Dev-generated license — do not distribute");
        }

        System.out.println("License generated: " + licenseKey);
        System.out.println("Machine FP:        " + machineFp);
        System.out.println("Written to:        " + outputPath);
    }

    private static String generatePrefix() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CHARSET.charAt(rng.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }

    static String computeSuffix(String prefix, String salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest((prefix + salt).getBytes(StandardCharsets.UTF_8));
        return String.format("%02x%02x", hash[0] & 0xFF, hash[1] & 0xFF).toUpperCase();
    }
}

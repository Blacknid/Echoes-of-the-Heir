package data;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Build-time utility. Generates a mathematically valid license key
 * (XXXXXXXX-YYYY) and writes it to license.properties for injection
 * into the game binary before packaging.
 *
 * Usage: java data.LicenseGenerator [outputPath]
 */
public class LicenseGenerator {

    private static final String SALT = "MichiCloudSalt2026";
    private static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public static void main(String[] args) throws Exception {

        String outputPath = args.length > 0 ? args[0] : "license.properties";

        String prefix = generatePrefix();
        String suffix = computeSuffix(prefix);
        String licenseKey = prefix + "-" + suffix;

        Properties props = new Properties();
        props.setProperty("license_key", licenseKey);

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            props.store(fos, "Auto-generated license key — do not edit");
        }

        System.out.println("License generated: " + licenseKey);
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

    static String computeSuffix(String prefix) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest((prefix + SALT).getBytes(StandardCharsets.UTF_8));
        return String.format("%02x%02x", hash[0] & 0xFF, hash[1] & 0xFF).toUpperCase();
    }
}

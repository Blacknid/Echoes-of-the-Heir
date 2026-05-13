package update;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.swing.JOptionPane;

/**
 * Mandatory update check.
 *
 *  - Reads the current version from {@code /res/build.properties} INSIDE
 *    the JAR (the same file Config reads — single source of truth, kept
 *    in sync by compile.cmd's auto-increment). Defaults to "0.0.0".
 *  - Reads {@code update_servers.txt} for a host pool.
 *  - Asks the patch server: "CHECK <version>". If it answers UPTODATE the
 *    method returns and the game continues. If the answer is UPDATE the
 *    patch is downloaded, the RSA signature is verified against
 *    {@link #PATCH_PUBLIC_KEY_B64}, and the {@link Updater} is spawned in
 *    a separate JVM. The current process then calls {@link System#exit(int)}
 *    so the running JAR is unlocked and can be replaced.
 *  - If the patch server is unreachable, startup continues normally.
 *  - The license / save-server config is never touched here.
 */
public final class UpdateClient {

    /** Replace with the contents of {@code patch_public_key.b64}. */
    public static final String PATCH_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0000000000000000000000000000000000000000000000000PASTE_REAL_KEY_HERE";

    private static final List<String> FALLBACK_HOSTS = List.of(
            "192.168.137.14:5006"
    );

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS    = 60000;
    private static final int MAX_PATCH_BYTES    = 256 * 1024 * 1024;

    private UpdateClient() {}

    /** Returns true if the game may proceed; false if the caller should exit
     *  (e.g. the user dismissed a mandatory update). The Updater is spawned
     *  internally before returning false. */
    public static boolean checkAndApply() {
        String currentVersion = readCurrentVersion();
        List<String> servers = readServerList();

        for (String hp : servers) {
            HostPort target = HostPort.parse(hp, 5006);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS);

                writeLine(s, "CHECK " + currentVersion);
                String reply = readLine(s);
                if (reply == null) continue;

                if (reply.equals("UPTODATE")) {
                    System.out.println("[Update] Up to date (v" + currentVersion + ").");
                    return true;
                }
                if (reply.startsWith("UPDATE ")) {
                    return handleUpdate(reply, currentVersion, target);
                }
                if (reply.startsWith("ERROR")) {
                    System.out.println("[Update] Server error: " + reply);
                    continue;
                }
            } catch (IOException ex) {
                System.out.println("[Update] " + target + " unreachable: " + ex.getMessage());
            }
        }
        // No patch server reachable — let the game start (offline-friendly).
        System.out.println("[Update] No patch server reachable. Continuing.");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    private static boolean handleUpdate(String reply, String currentVersion, HostPort target) {
        // Format: UPDATE <to_version> <size_bytes> <sha256_hex> <sig_b64>
        String[] parts = reply.split(" ");
        if (parts.length < 5) {
            System.out.println("[Update] Malformed UPDATE reply: " + reply);
            return true; // ignore malformed reply, allow start
        }
        String toVersion = parts[1];
        long expectedSize;
        try { expectedSize = Long.parseLong(parts[2]); }
        catch (NumberFormatException nf) { return true; }
        String expectedSha = parts[3];
        String sigB64 = parts[4];

        if (expectedSize <= 0 || expectedSize > MAX_PATCH_BYTES) {
            System.out.println("[Update] Implausible patch size: " + expectedSize);
            return true;
        }

        int answer = JOptionPane.showConfirmDialog(null,
                "A required update is available:\n\n"
                        + "    " + currentVersion + "  →  " + toVersion + "\n"
                        + "    " + (expectedSize / 1024) + " KB\n\n"
                        + "Download and install now?\n"
                        + "(The game cannot start until the update is applied.)",
                "Michi's Adventure — Update required",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            JOptionPane.showMessageDialog(null,
                    "Update declined. The game will close.",
                    "Michi's Adventure",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }

        // Download, verify, hand off to Updater
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            writeLine(s, "FETCH " + currentVersion);

            DataInputStream in = new DataInputStream(s.getInputStream());
            long size = in.readLong();
            if (size != expectedSize) {
                throw new IOException("size mismatch: " + size + " vs " + expectedSize);
            }
            byte[] patchBytes = in.readNBytes((int) size);
            if (patchBytes.length != size) {
                throw new IOException("short read: " + patchBytes.length);
            }

            // Hash + signature verify
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(patchBytes);
            if (!bytesToHex(sha).equalsIgnoreCase(expectedSha)) {
                throw new IOException("patch SHA-256 mismatch");
            }
            byte[] payload = signaturePayload(sha, currentVersion, toVersion);
            byte[] signature = Base64.getDecoder().decode(sigB64);
            if (!verifySignature(payload, signature)) {
                throw new IOException("patch signature INVALID");
            }
            System.out.println("[Update] Patch verified (" + patchBytes.length + " bytes).");

            // Persist patch + spawn Updater
            Path patchFile = Files.createTempFile("michi_patch_", ".zip");
            Files.write(patchFile, patchBytes);

            spawnUpdater(patchFile, toVersion);
            JOptionPane.showMessageDialog(null,
                    "The updater is running.\nThe game will reopen automatically.",
                    "Michi's Adventure — Updating",
                    JOptionPane.INFORMATION_MESSAGE);
            return false; // tell main() to exit so the JAR can be replaced

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null,
                    "Update failed: " + ex.getMessage()
                            + "\n\nThe game will close. Try again later.",
                    "Michi's Adventure — Update failed",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private static void spawnUpdater(Path patchFile, String toVersion) throws IOException {
        Path gameJar = locateRunningJar();
        if (gameJar == null) {
            throw new IOException("could not locate the running JAR — aborting update");
        }

        String javaExe = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + (isWindows() ? "java.exe" : "java");

        // The Updater class lives inside the same JAR. It runs as a separate
        // JVM so it can rewrite the JAR after this process exits.
        long pid = ProcessHandle.current().pid();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-cp");
        cmd.add(gameJar.toString());
        cmd.add("update.Updater");
        cmd.add(gameJar.toString());
        cmd.add(patchFile.toString());
        cmd.add(toVersion);
        cmd.add(String.valueOf(pid));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(System.getProperty("java.io.tmpdir"),
                "michi_updater.log"));
        pb.start();
    }

    private static Path locateRunningJar() {
        try {
            String url = UpdateClient.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            // On Windows the URL path looks like /C:/foo/game.jar — strip leading /
            if (isWindows() && url.startsWith("/")) url = url.substring(1);
            Path p = Paths.get(url);
            if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".jar")) {
                return p;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ─────────────────────────────────────────────────────────────────────
    /** Reads version from /res/build.properties inside the running JAR.
     *  Falls back to 0.0.0 if not present. The build.properties file is
     *  auto-incremented by compile.cmd on every build. */
    static String readCurrentVersion() {
        try (InputStream is = UpdateClient.class.getResourceAsStream("/res/build.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                String v = props.getProperty("version", "").trim();
                String b = props.getProperty("build", "").trim();
                if (!v.isEmpty()) {
                    return b.isEmpty() ? v : v + "." + b;
                }
            }
        } catch (IOException ignored) {}
        return "0.0.0";
    }

    private static List<String> readServerList() {
        List<String> out = new ArrayList<>();
        Path p = Paths.get("update_servers.txt");
        if (Files.isRegularFile(p)) {
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    out.add(t);
                }
            } catch (IOException ignored) {}
        }
        if (out.isEmpty()) out.addAll(FALLBACK_HOSTS);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    private static byte[] signaturePayload(byte[] sha, String fromVersion, String toVersion) {
        byte[] from = fromVersion.getBytes(StandardCharsets.US_ASCII);
        byte[] to   = toVersion.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[sha.length + 1 + from.length + 1 + to.length];
        int p = 0;
        System.arraycopy(sha, 0, out, p, sha.length); p += sha.length;
        out[p++] = '|';
        System.arraycopy(from, 0, out, p, from.length); p += from.length;
        out[p++] = '|';
        System.arraycopy(to, 0, out, p, to.length);
        return out;
    }

    private static boolean verifySignature(byte[] payload, byte[] signature) {
        try {
            byte[] der = Base64.getDecoder().decode(PATCH_PUBLIC_KEY_B64);
            PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pub);
            sig.update(payload);
            return sig.verify(signature);
        } catch (Exception ex) {
            System.out.println("[Update] verifySignature error: " + ex);
            return false;
        }
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    private static void writeLine(Socket s, String line) throws IOException {
        OutputStream os = s.getOutputStream();
        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String readLine(Socket s) throws IOException {
        InputStream is = s.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        return br.readLine();
    }

    // ─────────────────────────────────────────────────────────────────────
    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String h, int p) { this.host = h; this.port = p; }
        static HostPort parse(String entry, int defaultPort) {
            int i = entry.lastIndexOf(':');
            if (i <= 0) return new HostPort(entry, defaultPort);
            try {
                return new HostPort(entry.substring(0, i),
                        Integer.parseInt(entry.substring(i + 1)));
            } catch (NumberFormatException nf) {
                return new HostPort(entry, defaultPort);
            }
        }
        @Override public String toString() { return host + ":" + port; }
    }
}

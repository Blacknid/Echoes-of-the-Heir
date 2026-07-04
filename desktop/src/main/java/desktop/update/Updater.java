package desktop.update;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * External-process patch applier. Spawned by {@link UpdateClient} as:
 * <pre>
 *   java -cp game.jar desktop.update.Updater  game.jar  patch.zip  newVersion  parentPid
 * </pre>
 * Waits for the parent process to exit, rebuilds the JAR (overlaying patch's {@code add/}
 * and {@code replace/} entries, skipping {@code delete}), replaces it atomically, relaunches.
 * Never touches files outside the JAR (license.properties, save_servers.txt, etc).
 */
public final class Updater {

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: Updater <gameJar> <patchZip> <toVersion> <parentPid>");
            System.exit(2);
        }
        Path gameJar = Paths.get(args[0]);
        Path patchZip = Paths.get(args[1]);
        String toVersion = args[2];
        long parentPid;
        try { parentPid = Long.parseLong(args[3]); }
        catch (NumberFormatException nf) { parentPid = -1; }

        try {
            waitForParent(parentPid);
            applyPatch(gameJar, patchZip, toVersion);
            relaunch(gameJar);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.err.println("[Updater] FAILED: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void waitForParent(long pid) throws InterruptedException {
        if (pid <= 0) { Thread.sleep(2000); return; }
        ProcessHandle.of(pid).ifPresent(ph -> {
            try { ph.onExit().get(); } catch (Exception ignored) {}
        });
        Thread.sleep(750); // give the OS time to release JAR file handles
    }

    private static void applyPatch(Path gameJar, Path patchZip, String toVersion)
            throws IOException {
        if (!Files.isRegularFile(gameJar)) {
            throw new IOException("game JAR not found: " + gameJar);
        }
        if (!Files.isRegularFile(patchZip)) {
            throw new IOException("patch ZIP not found: " + patchZip);
        }

        Set<String> deleteSet = new HashSet<>();
        java.util.Map<String, byte[]> overlay = new java.util.HashMap<>();

        try (ZipFile patch = new ZipFile(patchZip.toFile())) {
            ZipEntry mEntry = patch.getEntry("manifest.json");
            if (mEntry == null) throw new IOException("patch missing manifest.json");
            byte[] manifestBytes;
            try (java.io.InputStream is = patch.getInputStream(mEntry)) {
                manifestBytes = is.readAllBytes();
            }

            String json = new String(manifestBytes, StandardCharsets.UTF_8);
            int i = json.indexOf("\"delete\"");
            if (i >= 0) {
                int lb = json.indexOf('[', i);
                int rb = json.indexOf(']', lb);
                if (lb >= 0 && rb > lb) {
                    String inner = json.substring(lb + 1, rb);
                    for (String s : inner.split(",")) {
                        String t = s.trim().replaceAll("^\"|\"$", "");
                        if (!t.isEmpty()) deleteSet.add(t);
                    }
                }
            }

            // Walk every entry; everything under add/ or replace/ becomes the overlay
            java.util.Enumeration<? extends ZipEntry> enu = patch.entries();
            while (enu.hasMoreElements()) {
                ZipEntry e = enu.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String stripped;
                if (name.startsWith("add/"))         stripped = name.substring(4);
                else if (name.startsWith("replace/")) stripped = name.substring(8);
                else                                  continue;
                try (java.io.InputStream is = patch.getInputStream(e)) {
                    overlay.put(stripped, is.readAllBytes());
                }
            }
        }

        Path tmpJar = gameJar.resolveSibling(gameJar.getFileName() + ".updating");
        Files.deleteIfExists(tmpJar);

        Set<String> writtenNames = new HashSet<>();
        try (java.io.InputStream raw = Files.newInputStream(gameJar);
             ZipInputStream zin = new ZipInputStream(raw);
             java.io.OutputStream rawOut = Files.newOutputStream(tmpJar);
             ZipOutputStream zout = new ZipOutputStream(rawOut)) {

            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (deleteSet.contains(name)) continue;
                if (overlay.containsKey(name)) {
                    zout.putNextEntry(new ZipEntry(name));
                    zout.write(overlay.get(name));
                    zout.closeEntry();
                    writtenNames.add(name);
                } else {
                    zout.putNextEntry(new ZipEntry(name));
                    zin.transferTo(zout);
                    zout.closeEntry();
                    writtenNames.add(name);
                }
            }

            for (java.util.Map.Entry<String, byte[]> en : overlay.entrySet()) {
                if (writtenNames.contains(en.getKey())) continue;
                zout.putNextEntry(new ZipEntry(en.getKey()));
                zout.write(en.getValue());
                zout.closeEntry();
            }
        }

        Path backup = gameJar.resolveSibling(gameJar.getFileName() + ".bak");
        try {
            Files.move(gameJar, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmpJar, gameJar, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(backup);
        } catch (IOException ex) {
            if (Files.exists(backup) && !Files.exists(gameJar)) {
                Files.move(backup, gameJar, StandardCopyOption.REPLACE_EXISTING);
            }
            throw ex;
        }

        try { Files.deleteIfExists(patchZip); } catch (IOException ignored) {}

        System.out.println("[Updater] Applied patch — now at v" + toVersion);
    }

    private static void relaunch(Path gameJar) throws IOException {
        String javaExe = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + (isWindows() ? "java.exe" : "java");
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-jar", gameJar.toString());
        pb.directory(gameJar.toAbsolutePath().getParent().toFile());
        pb.inheritIO();
        pb.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

package platform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * User-writable persistent storage (config, save games, server lists) for a simple relative
 * filename. Desktop resolves these against a fixed base directory (the folder containing the
 * running JAR — see {@link #baseDir()}), NOT the process working directory: the auto-updater
 * relaunches the game with a different working directory than the one the player originally
 * launched from, and a working-directory-relative lookup silently "loses" activation.dat,
 * owner_secret.dat and every save file in that case, even though they're sitting right there.
 * Android has no writable working directory, {@code Gdx.files.local(...)} resolves to the
 * app's private internal storage instead, which needs no runtime permission.
 */
public final class GameStorage {

    private GameStorage() {}

    private static boolean isAndroid() {
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android;
    }

    private static volatile File baseDir;

    /**
     * Directory the running JAR lives in, resolved once and cached. Falls back to the working
     * directory ({@code user.dir}) when the code source can't be determined (e.g. running from
     * an IDE with loose .class files rather than a packaged JAR) so dev runs keep working.
     */
    private static File baseDir() {
        File dir = baseDir;
        if (dir != null) return dir;
        synchronized (GameStorage.class) {
            if (baseDir == null) {
                File resolved = null;
                try {
                    java.net.URI loc = GameStorage.class.getProtectionDomain().getCodeSource()
                            .getLocation().toURI();
                    File f = new File(loc).getAbsoluteFile();
                    // A JAR's code source points at the .jar file itself; loose .class files
                    // (IDE run) point at a classes/ directory — either way take its parent
                    // folder as the stable base, except the classes/ dir case, which we keep
                    // as-is so dev runs still find assets/config next to the module.
                    resolved = f.isFile() ? f.getParentFile() : f;
                } catch (Exception ignored) {
                    // Fall through to user.dir below.
                }
                baseDir = resolved != null ? resolved : new File(System.getProperty("user.dir"));
            }
            return baseDir;
        }
    }

    private static File resolve(String relativeName) {
        return new File(baseDir(), relativeName);
    }

    public static boolean exists(String relativeName) {
        return isAndroid() ? Gdx.files.local(relativeName).exists() : resolve(relativeName).exists();
    }

    public static boolean delete(String relativeName) {
        if (isAndroid()) {
            FileHandle fh = Gdx.files.local(relativeName);
            return !fh.exists() || fh.delete();
        }
        File f = resolve(relativeName);
        return !f.exists() || f.delete();
    }

    public static Reader reader(String relativeName) throws IOException {
        if (isAndroid()) {
            return new java.io.InputStreamReader(inputStream(relativeName), StandardCharsets.UTF_8);
        }
        return new FileReader(resolve(relativeName));
    }

    public static BufferedReader bufferedReader(String relativeName) throws IOException {
        return new BufferedReader(reader(relativeName));
    }

    public static Writer writer(String relativeName) throws IOException {
        if (isAndroid()) {
            return new java.io.OutputStreamWriter(outputStream(relativeName), StandardCharsets.UTF_8);
        }
        return new FileWriter(resolve(relativeName));
    }

    public static BufferedWriter bufferedWriter(String relativeName) throws IOException {
        return new BufferedWriter(writer(relativeName));
    }

    public static InputStream inputStream(String relativeName) throws IOException {
        if (isAndroid()) {
            FileHandle fh = Gdx.files.local(relativeName);
            if (!fh.exists()) throw new java.io.FileNotFoundException(relativeName);
            return fh.read();
        }
        return new FileInputStream(resolve(relativeName));
    }

    public static OutputStream outputStream(String relativeName) throws IOException {
        if (isAndroid()) {
            return Gdx.files.local(relativeName).write(false);
        }
        return new FileOutputStream(resolve(relativeName));
    }

    /**
     * Write {@code data} to {@code relativeName} atomically: the bytes go to a temp file first
     * and only replace the target once fully flushed. A crash or full disk mid-write therefore
     * leaves the previous file intact instead of a truncated, undecryptable one.
     */
    public static void writeAtomic(String relativeName, byte[] data) throws IOException {
        String tmpName = relativeName + ".tmp";
        try (OutputStream os = outputStream(tmpName)) {
            os.write(data);
            os.flush();
        }
        if (isAndroid()) {
            FileHandle tmp = Gdx.files.local(tmpName);
            FileHandle dst = Gdx.files.local(relativeName);
            if (dst.exists()) dst.delete();
            tmp.moveTo(dst);
            return;
        }
        java.nio.file.Files.move(
                resolve(tmpName).toPath(), resolve(relativeName).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}

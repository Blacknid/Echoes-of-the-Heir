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
 * filename. Desktop resolves these against the process WORKING DIRECTORY (plain
 * {@code java.io.File}), exactly as the game always did before, preserving save-file
 * compatibility byte-for-byte. Android has no writable working directory,
 * {@code Gdx.files.local(...)} resolves to the app's private internal storage instead, which
 * needs no runtime permission.
 *
 * <p><b>Why the working directory and not the JAR's folder:</b> an earlier attempt resolved
 * against the folder containing the running JAR, to survive the auto-updater relaunch. That was
 * wrong twice over. First, the updater ALREADY relaunches with the working directory set to the
 * JAR's folder (see {@code desktop.update.Updater#relaunch}, {@code pb.directory(...)}), so the
 * working directory is correct across updates on its own. Second, the game's code does not live
 * next to its data files: in a dev run it's inside {@code core/build/libs/core.jar} and in a
 * packaged build it may sit in a {@code lib/} subfolder — so the JAR's folder is NOT where
 * activation.dat / owner_secret.dat / save.dat live. Keying off it made the game silently write
 * saves into {@code build/libs} and never find the owner secret at the project root, which broke
 * both licensing (owner-secret login fell through to a failed ACTIVATE) and Continue.
 */
public final class GameStorage {

    private GameStorage() {}

    private static boolean isAndroid() {
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android;
    }

    private static File resolve(String relativeName) {
        // Working-directory-relative, matching the game's historical behaviour. The launcher
        // (dev: Gradle run.workingDir = project root; production: Updater sets the CWD to the
        // JAR's folder) guarantees the working directory is where the data files sit.
        return new File(relativeName);
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

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
 * filename. Desktop resolves these against the process working directory exactly as the game
 * always has (plain {@code java.io.File}), preserving save-file compatibility byte-for-byte.
 * Android has no writable working directory, {@code Gdx.files.local(...)} resolves to the
 * app's private internal storage instead, which needs no runtime permission.
 */
public final class GameStorage {

    private GameStorage() {}

    private static boolean isAndroid() {
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.Android;
    }

    public static boolean exists(String relativeName) {
        return isAndroid() ? Gdx.files.local(relativeName).exists() : new File(relativeName).exists();
    }

    public static boolean delete(String relativeName) {
        if (isAndroid()) {
            FileHandle fh = Gdx.files.local(relativeName);
            return !fh.exists() || fh.delete();
        }
        File f = new File(relativeName);
        return !f.exists() || f.delete();
    }

    public static Reader reader(String relativeName) throws IOException {
        if (isAndroid()) {
            return new java.io.InputStreamReader(inputStream(relativeName), StandardCharsets.UTF_8);
        }
        return new FileReader(relativeName);
    }

    public static BufferedReader bufferedReader(String relativeName) throws IOException {
        return new BufferedReader(reader(relativeName));
    }

    public static Writer writer(String relativeName) throws IOException {
        if (isAndroid()) {
            return new java.io.OutputStreamWriter(outputStream(relativeName), StandardCharsets.UTF_8);
        }
        return new FileWriter(relativeName);
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
        return new FileInputStream(relativeName);
    }

    public static OutputStream outputStream(String relativeName) throws IOException {
        if (isAndroid()) {
            return Gdx.files.local(relativeName).write(false);
        }
        return new FileOutputStream(relativeName);
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
                new File(tmpName).toPath(), new File(relativeName).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}

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
}

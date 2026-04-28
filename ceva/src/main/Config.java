package main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Config {

    GamePanel gp;

    // Version info loaded from res/build.properties
    public static String gameVersion = "2.0";
    public static int buildNumber = 0;

    static {
        loadBuildProperties();
    }

    private static void loadBuildProperties() {
        try (InputStream is = Config.class.getResourceAsStream("/res/build.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                gameVersion = props.getProperty("version", "2.0");
                try { buildNumber = Integer.parseInt(props.getProperty("build", "0")); }
                catch (NumberFormatException ignored) {}
                System.out.println("[Config] Version " + gameVersion + " build " + buildNumber);
            }
        } catch (Exception e) {
            System.out.println("[Config] Could not load build.properties");
        }
    }

    public static String getVersionString() {
        return "v" + gameVersion + "." + buildNumber;
    }

    // Rendering scale configuration
    // `originalTileSize` is the native pixel size used when authoring spritesheets (default 32).
    // `scale` is an integer multiplier applied at runtime (1,2,3...).
    public static int originalTileSize = 32;
    public static double scale = 2;
    public static int tileSize = (int)(originalTileSize * scale);

    /** Set the scale multiplier and update derived values. */
    public static void setScale(double s) {
        if (s <= 0) return;
        scale = s;
        tileSize = (int)(originalTileSize * scale);
    }

    /** Set the original (native) tile size in pixels and update derived values. */
    public static void setOriginalTileSize(int o) {
        if (o <= 0) return;
        originalTileSize = o;
        tileSize = (int)(originalTileSize * scale);
    }

    public Config ( GamePanel gp ) {
        this.gp = gp;
    }

    public void saveConfig () {

        try (BufferedWriter bw = new BufferedWriter(new FileWriter("config.txt"))) {

            bw.write("fullscreen=" + (gp.fullScreenOn ? "On" : "Off")); bw.newLine();
            bw.write("musicVolume=" + gp.audio.getMusicVolume());        bw.newLine();
            bw.write("seVolume=" + gp.audio.getSEVolume());              bw.newLine();
            bw.write("vsync=" + (gp.vSyncOn ? "On" : "Off"));           bw.newLine();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void loadConfig () {

        try (BufferedReader br = new BufferedReader(new FileReader("config.txt"))) {

            Map<String, String> map = new HashMap<>();
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }

            gp.fullScreenOn = "On".equals(map.getOrDefault("fullscreen", "Off"));
            int musicVol = Integer.parseInt(map.getOrDefault("musicVolume", "5"));
            int seVol    = Integer.parseInt(map.getOrDefault("seVolume", "5"));
            gp.audio.applyConfig(musicVol, seVol);
            gp.vSyncOn = "On".equals(map.getOrDefault("vsync", "Off"));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

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

    // UI reference resolution — every pixel offset in the HUD is authored at 1280x768.
    // uiSf() / uiSfH() in GamePanel produce the scale factor when the internal resolution changes.
    public static final int UI_BASE_W = 1280;
    public static final int UI_BASE_H = 720;

    // Rendering scale configuration
    // `originalTileSize` is the native pixel size used when authoring spritesheets (default 32).
    // `scale` controls zoom level: 2.0 = 64px tiles (20x12 view), 2.5 = 80px tiles (16x9 view)
    public static int originalTileSize = 32;
    public static double scale = 2.0;
    public static int tileSize = (int)(originalTileSize * scale);
    /** When true: stretch the fixed 1280×720 back-buffer to fill the window.
     *  When false (default): dynamic viewport — bigger window = more world tiles visible, 1:1 pixels. */
    public static boolean stretchToFill = false;

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

    // Last known windowed position — persisted so the window reopens where the player left it.
    public int windowX = -1;
    public int windowY = -1;

    /** FPS cap: 30 = performance mode, 60 = normal, 0 = follow vSync/uncapped setting. */
    public int fpsTarget = 60;

    /** Graphics quality: 0 = Low (square lights + shadows), 1 = Medium (circle lights, no shadows), 2 = High (circle lights + shadows). */
    public static final int GRAPHICS_LOW    = 0;
    public static final int GRAPHICS_MEDIUM = 1;
    public static final int GRAPHICS_HIGH   = 2;
    public int graphicsQuality = GRAPHICS_HIGH;

    public void saveConfig () {

        try (BufferedWriter bw = new BufferedWriter(new FileWriter("config.txt"))) {

            bw.write("fullscreen=" + (gp.fullScreenOn ? "On" : "Off")); bw.newLine();
            bw.write("musicVolume=" + gp.audio.getMusicVolume());        bw.newLine();
            bw.write("seVolume=" + gp.audio.getSEVolume());              bw.newLine();
            bw.write("vsync=" + (gp.vSyncOn ? "On" : "Off"));           bw.newLine();
            bw.write("fpsTarget=" + fpsTarget);                           bw.newLine();
            bw.write("graphicsQuality=" + graphicsQuality);                bw.newLine();
            bw.write("stretchToFill=" + (Config.stretchToFill ? "On" : "Off")); bw.newLine();
            // Save window position. The window is owned by libGDX (backend-specific); to keep
            // core backend-agnostic (Android-portable), we persist the last saved coords rather
            // than querying the live window here.
            int wx = windowX, wy = windowY;
            if (wx >= 0 && wy >= 0) {
                bw.write("windowX=" + wx); bw.newLine();
                bw.write("windowY=" + wy); bw.newLine();
            }

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
            try { fpsTarget = Integer.parseInt(map.getOrDefault("fpsTarget", "60")); } catch (Exception ignored) {}
            try { graphicsQuality = Math.max(GRAPHICS_LOW, Math.min(GRAPHICS_HIGH, Integer.parseInt(map.getOrDefault("graphicsQuality", "2")))); } catch (Exception ignored) {}
            Config.stretchToFill = "On".equals(map.getOrDefault("stretchToFill", "Off"));
            try { windowX = Integer.parseInt(map.getOrDefault("windowX", "-1")); } catch (Exception ignored) {}
            try { windowY = Integer.parseInt(map.getOrDefault("windowY", "-1")); } catch (Exception ignored) {}

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

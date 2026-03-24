package main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Config {

    GamePanel gp;

    public Config ( GamePanel gp ) {
        this.gp = gp;
    }

    public void saveConfig () {

        try (BufferedWriter bw = new BufferedWriter(new FileWriter("config.txt"))) {

            bw.write("fullscreen=" + (gp.fullScreenOn ? "On" : "Off")); bw.newLine();
            bw.write("musicVolume=" + gp.music.volumeScale);             bw.newLine();
            bw.write("seVolume=" + gp.se.volumeScale);                   bw.newLine();
            bw.write("vsync=" + (gp.vSyncOn ? "On" : "Off"));            bw.newLine();

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

            gp.fullScreenOn      = "On".equals(map.getOrDefault("fullscreen", "Off"));
            gp.music.volumeScale = Integer.parseInt(map.getOrDefault("musicVolume", "5"));
            gp.se.volumeScale    = Integer.parseInt(map.getOrDefault("seVolume", "5"));
            gp.vSyncOn           = "On".equals(map.getOrDefault("vsync", "Off"));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

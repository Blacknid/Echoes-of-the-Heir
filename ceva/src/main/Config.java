package main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Config {

    GamePanel gp;

    public Config ( GamePanel gp ) {
        this.gp = gp;
    }

    public void saveConfig () {

        try (BufferedWriter bw = new BufferedWriter(new FileWriter("config.txt"))) {

            // FULL SCREEN
            bw.write(gp.fullScreenOn ? "On" : "Off");
            bw.newLine();

            // MUSIC VOLUME
            bw.write(String.valueOf(gp.music.volumeScale));
            bw.newLine();

            // SE VOLUME
            bw.write(String.valueOf(gp.se.volumeScale));
            bw.newLine();

            // V-SYNC
            bw.write(gp.vSyncOn ? "On" : "Off");
            bw.newLine();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void loadConfig () {

        try (BufferedReader br = new BufferedReader(new FileReader("config.txt"))) {

            String s = br.readLine();

            // FULL SCREEN
            gp.fullScreenOn = "On".equals(s);

            // MUSIC VOLUME
            s = br.readLine();
            gp.music.volumeScale = Integer.parseInt(s);

            // SE VOLUME
            s = br.readLine();
            gp.se.volumeScale = Integer.parseInt(s);

            // V-SYNC
            s = br.readLine();
            gp.vSyncOn = "On".equals(s);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

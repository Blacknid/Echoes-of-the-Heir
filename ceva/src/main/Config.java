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

        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter("config.txt"));

            // FULL SCREEN
            if ( gp.fullScreenOn == true ) {
                bw.write("On");
            }
            if ( gp.fullScreenOn == false ) {
                bw.write("Off");
            }
            bw.newLine();

            // MUSIC VOLUME
            bw.write(String.valueOf(gp.music.volumeScale));
            bw.newLine();

            // SE VOLUME
            bw.write(String.valueOf(gp.se.volumeScale));
            bw.newLine();

            // V-SYNC
            if ( gp.vSyncOn == true ) {
                bw.write("On");
            }
            if ( gp.vSyncOn == false ) {
                bw.write("Off");
            }
            bw.newLine();

            bw.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void loadConfig () {

        try {
            BufferedReader br = new BufferedReader(new FileReader("config.txt"));

            String s = br.readLine();

            // FULL SCREEN
            if ( s.equals("On")) {
                gp.fullScreenOn = true;
            }
            if ( s.equals("Off")) {
                gp.fullScreenOn = false;
            }

            // MUSIC VOLUME
            s = br.readLine();
            gp.music.volumeScale = Integer.parseInt(s);

            // SE VOLUME
            s = br.readLine();
            gp.se.volumeScale = Integer.parseInt(s);

            // V-SYNC
            s = br.readLine();
            if ( "On".equals(s)) {
                gp.vSyncOn = true;
            }
            if ( "Off".equals(s)) {
                gp.vSyncOn = false;
            }

            br.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

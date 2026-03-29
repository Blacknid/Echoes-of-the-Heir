package audio;

import java.net.URL;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

public class Sound {

    Clip clip;
    URL soundURL[] = new URL[30];
    FloatControl fc;
    int volumeScale = 3;
    float volume;

    // Concurrent SFX: up to POOL clips per sound index for overlapping playback
    private static final int POOL = 3;
    private Clip[][] clipPool = new Clip[30][POOL];
    private int[] clipRobin = new int[30]; // round-robin index per slot

    public Sound() {

        soundURL[0] = getClass().getResource("/res/sound/Main_Theme.wav"); // unOfficially "main theme"
        soundURL[1] = getClass().getResource("/res/sound/Door.wav");
        soundURL[2] = getClass().getResource("/res/sound/Equip.wav"); 
        soundURL[3] = getClass().getResource("/res/sound/Options.wav");
        soundURL[4] = getClass().getResource("/res/sound/GameOver.wav");
        soundURL[5] = getClass().getResource("/res/sound/GotGem.wav");
        soundURL[6] = getClass().getResource("/res/sound/Victory.wav");
        soundURL[7] = getClass().getResource("/res/sound/Selections.wav");
        soundURL[8] = getClass().getResource("/res/sound/Michiduta Receive Hit.wav");
        soundURL[9] = getClass().getResource("/res/sound/Monster Hit.wav");
        soundURL[10] = getClass().getResource("/res/sound/Weapon Swing.wav");
        soundURL[11] = getClass().getResource("/res/sound/Level Up.wav");
        soundURL[12] = getClass().getResource("/res/sound/Arrow.wav");
        soundURL[13] = getClass().getResource("/res/sound/piano_soundtrack/Music Box 1.wav");
    }

    public void setFile(int i) {
        try {
            // Round-robin through the pool for this sound index
            int slot = clipRobin[i];
            clipRobin[i] = (slot + 1) % POOL;

            // Reuse cached clip if available
            if (clipPool[i][slot] != null && clipPool[i][slot].isOpen()) {
                clip = clipPool[i][slot];
                clip.stop();
                clip.setFramePosition(0);
            } else {
                // Close previous clip in this slot to prevent resource leak
                if (clipPool[i][slot] != null) {
                    clipPool[i][slot].close();
                }
                AudioInputStream ais = AudioSystem.getAudioInputStream(soundURL[i]);
                clip = AudioSystem.getClip();
                clip.open(ais);
                ais.close();
                clipPool[i][slot] = clip;
            }
            fc = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            checkVolume();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    public void play() {
        if (clip != null) {
            clip.setFramePosition(0);
            clip.start();
        }
    }
    public void loop() {
        if (clip != null) {
            clip.loop(Clip.LOOP_CONTINUOUSLY);
        }
    }
    public void stop() {
        if (clip != null) {
            clip.stop();
        }
    }
    public void checkVolume() {
        switch (volumeScale) {
            case 0: volume = -80f; break;
            case 1: volume = -20f; break;
            case 2: volume = -12f; break;
            case 3: volume = -5f; break;
            case 4: volume = 1f; break;
            case 5: volume = 6f; break;
        }
        if (fc != null) {
            fc.setValue(volume);
        }
    }
}

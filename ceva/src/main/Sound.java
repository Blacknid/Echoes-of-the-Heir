package main;

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

    // OPTIMIZATION: Cache clips to avoid re-opening the same audio file repeatedly
    private Clip[] clipCache = new Clip[30];

    public Sound() {

        soundURL[0] = getClass().getResource("/res/sound/Michiduta Theme.wav");
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
    }

    public void setFile(int i) {
        try {
            // Reuse cached clip if available
            if (clipCache[i] != null && clipCache[i].isOpen()) {
                clip = clipCache[i];
                clip.stop();
                clip.setFramePosition(0);
            } else {
                // Close previous clip in this slot to prevent resource leak
                if (clipCache[i] != null) {
                    clipCache[i].close();
                }
                AudioInputStream ais = AudioSystem.getAudioInputStream(soundURL[i]);
                clip = AudioSystem.getClip();
                clip.open(ais);
                ais.close(); // Close the stream after opening the clip
                clipCache[i] = clip;
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

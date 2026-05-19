package audio;

import java.io.IOException;
import java.net.URL;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

public class Sound {

    Clip clip;
    URL soundURL[] = new URL[30];
    FloatControl fc;
    int volumeScale = 3;
    float volume;

    // Concurrent SFX: up to POOL clips per sound index for overlapping playback
    private static final int POOL = 3;
    private final Clip[][] clipPool = new Clip[30][POOL];
    private final int[] clipRobin = new int[30]; // round-robin index per slot

    public Sound() {

        soundURL[0] = getClass().getResource("/res/sound/Soundtracks/Main_Theme.wav"); // Officially the Main Theme for Echoes of the Heir, but also used for the title screen and some boss fights
        soundURL[1] = getClass().getResource("/res/sound/Soundtracks/Awakening Cave.mp3"); // The Awakening Cave theme, also used for the tutorial area and some later zones
        soundURL[2] = getClass().getResource("/res/sound/Soundtracks/Canvas Village.mp3"); // The main village theme, also used for the world map
        
        soundURL[3] = getClass().getResource("/res/sound/Door.wav");
        soundURL[4] = getClass().getResource("/res/sound/Equip.wav"); 
        soundURL[5] = getClass().getResource("/res/sound/Options.wav");
        soundURL[6] = getClass().getResource("/res/sound/GameOver.wav");
        soundURL[7] = getClass().getResource("/res/sound/GotGem.wav");
        soundURL[8] = getClass().getResource("/res/sound/Victory.wav");
        soundURL[9] = getClass().getResource("/res/sound/Selections.wav");
        soundURL[10] = getClass().getResource("/res/sound/Michiduta Receive Hit.wav");
        soundURL[11] = getClass().getResource("/res/sound/Monster Hit.wav");
        soundURL[12] = getClass().getResource("/res/sound/Weapon Swing.wav");
        soundURL[13] = getClass().getResource("/res/sound/Level Up.wav");
        soundURL[14] = getClass().getResource("/res/sound/Arrow.wav");
        soundURL[15] = getClass().getResource("/res/sound/piano_soundtrack/Music Box 1.wav");
    }

    public void setFile(int i) {
        try {
            if (soundURL[i] == null) {
                System.out.println("Sound: missing audio resource for slot " + i);
                return;
            }

            // Round-robin through the pool for this sound index
            int slot = clipRobin[i];
            clipRobin[i] = (slot + 1) % POOL;

            if (clipPool[i][slot] != null && clipPool[i][slot].isOpen()) {
                clip = clipPool[i][slot];
                clip.stop();
                clip.setFramePosition(0);
            } else {
                // Close previous clip in this slot to prevent resource leak
                if (clipPool[i][slot] != null) {
                    clipPool[i][slot].close();
                }
                clip = AudioSystem.getClip();
                try (AudioInputStream ais = AudioSystem.getAudioInputStream(soundURL[i])) {
                    // MP3 files must be transcoded to PCM before a Clip can open them
                    clip.open(ais);
                }
                clipPool[i][slot] = clip;
            }
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                fc = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            } else {
                fc = null;
            }
            checkVolume();
        } catch (IOException | LineUnavailableException | UnsupportedAudioFileException e) {
            System.out.println("Sound: failed to load slot " + i + ": " + e.getMessage());
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
        volume = switch (volumeScale) {
            case 0 -> -80f;
            case 1 -> -20f;
            case 2 -> -12f;
            case 3 -> -5f;
            case 4 -> 1f;
            case 5 -> 6f;
            default -> volume;
        };
        if (fc != null) {
            fc.setValue(volume);
        }
    }
}

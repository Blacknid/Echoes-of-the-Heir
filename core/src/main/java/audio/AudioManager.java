package audio;

/**
 * Centralized audio manager. Wraps the two Sound instances (music + SE)
 * and provides a clean API with named sound constants from SFX.
 *
 * Usage:
 *   gp.audio.playSE(SFX.MONSTER_HIT);
 *   gp.audio.playMusic(SFX.MUSIC_THEME);
 *   gp.audio.stopMusic();
 */
public class AudioManager {

    private final Sound music = new Sound();
    private final Sound se = new Sound();

    public void playMusic(int index) {
        music.stop();
        music.setFile(index);
        music.play();
        music.loop();
    }

    public void stopMusic() {
        music.stop();
    }

    public void playSE(int index) {
        se.setFile(index);
        se.play();
    }

    public int getMusicVolume() { return music.volumeScale; }
    public int getSEVolume()    { return se.volumeScale; }

    public void setMusicVolume(int scale) {
        music.volumeScale = Math.max(0, Math.min(5, scale));
        music.checkVolume();
    }

    public void setSEVolume(int scale) {
        se.volumeScale = Math.max(0, Math.min(5, scale));
    }

    /** Apply volume from loaded config. */
    public void applyConfig(int musicVol, int seVol) {
        music.volumeScale = musicVol;
        se.volumeScale = seVol;
    }

    /** Convenience for Config save. */
    public Sound getMusicSound() { return music; }
    public Sound getSESound()    { return se; }
}

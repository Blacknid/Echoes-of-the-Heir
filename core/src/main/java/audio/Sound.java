package audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;

/**
 * Audio playback backed by libGDX (cross-platform incl. Android), replacing the desktop-only
 * {@code javax.sound.sampled} pipeline. Each instance plays one "current" clip selected by index
 * via {@link #setFile(int)}; {@link AudioManager} uses one Sound for music and one for SE.
 *
 * <p>libGDX distinguishes streaming {@link Music} (long, loopable tracks) from {@code Sound}
 * (short SFX). Music slots (soundtracks/long loops) are streamed via Music; everything else plays
 * as a fire-and-forget {@code Sound}. The public API ({@code setFile/play/loop/stop/checkVolume},
 * {@code volumeScale}) is preserved so callers are unchanged. {@code .mp3}/{@code .wav}/{@code .ogg}
 * are all supported natively.
 */
public class Sound {

    // Slots that are long, loopable tracks → streamed as Music. (Soundtracks + music box +
    // dialogue typing loop, which also needs clean loop()/stop() semantics.)
    private static final java.util.Set<Integer> MUSIC_SLOTS = java.util.Set.of(0, 1, 2, 15, 16);

    // Slots that get a small random pitch wobble each time they play, so a rapidly-repeated SFX
    // (e.g. attack swings) doesn't sound like the exact same sample looping. Add more slot numbers
    // here to give any other SFX the same treatment, no other code needs to change.
    private static final java.util.Set<Integer> PITCH_VARIED_SLOTS = java.util.Set.of(SFX.WEAPON_SWING);
    private static final float PITCH_MIN = 0.90f;
    private static final float PITCH_MAX = 1.10f;
    // Remembers the last pitch used per slot so back-to-back plays never repeat the same value
    // "random but not repetitive", not just random.
    private final java.util.Map<Integer, Float> lastPitch = new java.util.HashMap<>();

    private float nextPitch(int slot) {
        if (!PITCH_VARIED_SLOTS.contains(slot)) return 1f;
        float prev = lastPitch.getOrDefault(slot, Float.NaN);
        float pitch;
        do {
            pitch = PITCH_MIN + (float) Math.random() * (PITCH_MAX - PITCH_MIN);
        } while (Math.abs(pitch - prev) < 0.03f); // re-roll if it landed suspiciously close to last time
        lastPitch.put(slot, pitch);
        return pitch;
    }

    private final String[] soundPath = new String[30];
    private final com.badlogic.gdx.audio.Sound[] sfxCache = new com.badlogic.gdx.audio.Sound[30];

    private Music currentMusic;     // active streamed track (if the current slot is a music slot)
    private int   currentSfxSlot = -1; // active SFX slot (for play())
    private boolean loopCurrent = false;

    public int volumeScale = 3;
    float volume; // 0..1 linear gain (kept as a field for API parity)

    public Sound() {
        soundPath[0]  = "res/sound/Soundtracks/Main_Theme.wav";
        soundPath[1]  = "res/sound/Soundtracks/Awakening Cave.mp3";
        soundPath[2]  = "res/sound/Soundtracks/Canvas Village.mp3";
        soundPath[3]  = "res/sound/Door.wav";
        soundPath[4]  = "res/sound/Equip.wav";
        soundPath[5]  = "res/sound/Options.wav";
        soundPath[6]  = "res/sound/GameOver.wav";
        soundPath[7]  = "res/sound/GotGem.wav";
        soundPath[8]  = "res/sound/Victory.wav";
        soundPath[9]  = "res/sound/Selections.wav";
        soundPath[10] = "res/sound/Michi Receive Hit.wav";
        soundPath[11] = "res/sound/Monster Hit.wav";
        soundPath[12] = "res/sound/Weapon Swing.wav";
        soundPath[13] = "res/sound/Level Up.wav";
        soundPath[14] = "res/sound/Arrow.wav";
        soundPath[15] = "res/sound/piano_soundtrack/Music Box 1.wav";
        soundPath[16] = "res/sound/Dialogue.mp3";
        checkVolume();
    }

    private FileHandle handle(int i) {
        String p = (i >= 0 && i < soundPath.length) ? soundPath[i] : null;
        if (p == null) return null;
        FileHandle fh = Gdx.files.internal(p);
        return fh.exists() ? fh : null;
    }

    /** Select the clip for slot i (does not start playback, call play()/loop()). */
    public void setFile(int i) {
        FileHandle fh = handle(i);
        if (fh == null) {
            System.out.println("Sound: missing audio resource for slot " + i);
            currentSfxSlot = -1;
            return;
        }
        if (MUSIC_SLOTS.contains(i)) {
            // Switch streamed track. stop()/dispose() go through the same Android MediaPlayer
            // state machine as stop() above and throw just as readily, so the old track is torn
            // down defensively — a previous track that refuses to stop must not prevent the new
            // one from loading, and must never take the GL thread down with it.
            if (currentMusic != null) {
                try { currentMusic.stop(); } catch (RuntimeException ignored) { }
                try { currentMusic.dispose(); } catch (RuntimeException ignored) { }
                currentMusic = null;
            }
            try {
                currentMusic = Gdx.audio.newMusic(fh);
                currentMusic.setVolume(linearVolume());
            } catch (Exception e) {
                System.out.println("Sound: failed to load music slot " + i + ": " + e.getMessage());
            }
            currentSfxSlot = -1;
        } else {
            // SFX: lazily decode + cache.
            if (sfxCache[i] == null) {
                try { sfxCache[i] = Gdx.audio.newSound(fh); }
                catch (Exception e) { System.out.println("Sound: failed to load SE slot " + i + ": " + e.getMessage()); }
            }
            currentSfxSlot = i;
        }
    }

    public void play() {
        loopCurrent = false;
        if (currentMusic != null) {
            // Same MediaPlayer state-machine hazard as stop() — see its javadoc. Failing to start
            // a track is a missing-music annoyance; throwing here kills the GL thread.
            try {
                currentMusic.setLooping(false);
                currentMusic.setVolume(linearVolume());
                currentMusic.play();
            } catch (RuntimeException e) {
                System.out.println("Sound: music play failed (" + e + ")");
            }
        } else if (currentSfxSlot >= 0 && sfxCache[currentSfxSlot] != null) {
            long id = sfxCache[currentSfxSlot].play(linearVolume());
            float pitch = nextPitch(currentSfxSlot);
            if (pitch != 1f) sfxCache[currentSfxSlot].setPitch(id, pitch);
        }
    }

    public void loop() {
        loopCurrent = true;
        if (currentMusic != null) {
            // See play()/stop(): guarded for the Android MediaPlayer state machine.
            try {
                currentMusic.setLooping(true);
                currentMusic.setVolume(linearVolume());
                currentMusic.play();
            } catch (RuntimeException e) {
                System.out.println("Sound: music loop failed (" + e + ")");
            }
        }
        // (SFX are not looped in this game.)
    }

    /**
     * Stop the streamed track, tolerating a backend that objects to the request.
     *
     * <p>The null check alone is not enough on Android. libGDX's {@code AndroidMusic} wraps
     * {@code android.media.MediaPlayer}, which is a state machine: {@code stop()} is only legal
     * from Started/Paused/Stopped/PlaybackCompleted and throws {@link IllegalStateException}
     * anywhere else. So a track that ended on its own, or was still preparing, has a non-null
     * {@code currentMusic} whose {@code stop()} throws — and this runs on the GL thread (see
     * GamePanel.update's title-music handling), so the exception killed the whole app. It showed
     * up as a crash the moment a multiplayer world finished loading, because that is when the
     * title track gets stopped. Desktop never reproduced it: OpenAL's stop() is a no-op in any
     * state.
     *
     * <p>Stopping is best-effort by nature — the goal is "this track is not playing", and every
     * state that rejects the call is already a state where it isn't.
     */
    public void stop() {
        if (currentMusic == null) return;
        try {
            currentMusic.stop();
        } catch (RuntimeException e) {
            System.out.println("Sound: music stop ignored (" + e + ")");
        }
    }

    /** Map the 0..5 volumeScale to a 0..1 linear gain (mirrors the old dB curve, perceptually). */
    public void checkVolume() {
        volume = switch (volumeScale) {
            case 0 -> 0f;
            case 1 -> 0.15f;
            case 2 -> 0.30f;
            case 3 -> 0.55f;
            case 4 -> 0.80f;
            case 5 -> 1f;
            default -> volume;
        };
        // Guarded like the rest of the streamed-track calls (see stop()): this one runs via
        // linearVolume() on the render path, so an unguarded throw here is the most frequent
        // chance of all to take down the GL thread.
        if (currentMusic != null) {
            try { currentMusic.setVolume(volume); } catch (RuntimeException ignored) { }
        }
    }

    private float linearVolume() { checkVolume(); return volume; }
}

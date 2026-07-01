package gfx;

import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Thin wrapper over libGDX {@link Animation} that keeps the game's {@link Sprite} type at the
 * boundary. Entity/tile animation used to be hand-rolled integer counters (spriteCounter/spriteNum)
 * advanced once per 60-UPS tick and bounced 1..N..1 by hand; this replaces that with a float
 * {@code stateTime} and {@link #getFrame(float)}.
 *
 * <p>Why keep the original {@code Sprite[]} instead of returning libGDX's own frame: {@link Sprite}
 * carries the display V-flip and the logical (pre-scaled) width/height the draw code relies on. We
 * therefore drive libGDX's {@link Animation} only to pick the frame INDEX
 * ({@link Animation#getKeyFrameIndex(float)}) and return our own {@code frames[index]} — the region
 * inside the Animation is only a vehicle for timing/index math and is never drawn directly.
 *
 * <p>Frame duration is settable at runtime ({@link #setFrameDuration(float)}) because some cadences
 * are dynamic — e.g. the walk cycle speeds up with movement speed ({@code 48/speed} ticks) — so the
 * caller pushes the new duration each tick instead of rebuilding the animation.
 */
public class SpriteAnimation {

    private final Sprite[] frames;
    private final Animation<TextureRegion> animation;

    /**
     * @param frames        the game frames to cycle (kept and returned as-is); may be null/empty
     * @param frameDuration seconds per frame ({@link #durationForTicks(int)} converts old tick counts)
     * @param mode          play mode — {@link Animation.PlayMode#LOOP_PINGPONG} reproduces the old
     *                      1..N..1 bounce; {@link Animation.PlayMode#LOOP} for a plain wrap
     */
    public SpriteAnimation(Sprite[] frames, float frameDuration, Animation.PlayMode mode) {
        this.frames = frames;
        if (frames == null || frames.length == 0) {
            this.animation = null;
            return;
        }
        TextureRegion[] regions = new TextureRegion[frames.length];
        for (int i = 0; i < frames.length; i++) {
            regions[i] = frames[i] != null ? frames[i].region() : null;
        }
        this.animation = new Animation<>(Math.max(1e-4f, frameDuration), regions);
        this.animation.setPlayMode(mode);
    }

    /** Convert a legacy per-frame tick interval (at 60 UPS) to seconds. */
    public static float durationForTicks(int ticks) {
        return Math.max(1, ticks) / 60f;
    }

    /** Change the seconds-per-frame at runtime (e.g. walk cadence tied to movement speed). */
    public void setFrameDuration(float seconds) {
        if (animation != null) animation.setFrameDuration(Math.max(1e-4f, seconds));
    }

    public float getFrameDuration() {
        return animation != null ? animation.getFrameDuration() : 0f;
    }

    /** Index of the frame shown at {@code stateTime} (honors the play mode / bounce), or 0. */
    public int getFrameIndex(float stateTime) {
        if (animation == null) return 0;
        return animation.getKeyFrameIndex(stateTime);
    }

    /** The {@link Sprite} shown at {@code stateTime}, or null if this animation has no frames. */
    public Sprite getFrame(float stateTime) {
        if (frames == null || frames.length == 0) return null;
        return frames[getFrameIndex(stateTime)];
    }

    public int frameCount() {
        return frames == null ? 0 : frames.length;
    }

    public boolean isEmpty() {
        return frames == null || frames.length == 0;
    }
}

package gfx;

import com.badlogic.gdx.graphics.g2d.Animation;

/**
 * Per-direction sprite frames plus one playback speed in MILLISECONDS per frame.
 * Groups "what to draw" and "how fast" so speed lives at the load site
 * ({@code new AnimClip(frames, 150)} == 150 ms/frame). Builds one
 * {@link SpriteAnimation} per direction the first time that direction plays.
 */
public class AnimClip {
    private final Sprite[][] frames;          // [direction][frame]
    private final Animation.PlayMode mode;
    private final SpriteAnimation[] anims;    // one per direction, built on demand
    public int frameMs;                       // ms per frame (public: read at hit/death counters)

    public AnimClip(Sprite[][] frames, int frameMs, Animation.PlayMode mode) {
        this.frames = frames;
        this.frameMs = frameMs;
        this.mode = mode;
        this.anims = new SpriteAnimation[frames != null ? frames.length : 0];
    }

    public int frameIndex(int direction, float stateTime) {
        return anim(direction) == null ? 0 : anim(direction).getFrameIndex(stateTime);
    }

    public int frameCount(int direction) {
        return (frames == null || direction < 0 || direction >= frames.length
                || frames[direction] == null) ? 0 : frames[direction].length;
    }

    /** Override ms speed at runtime (walk scales this by movement speed). */
    public void setFrameMs(int ms) {
        this.frameMs = ms;
        for (SpriteAnimation a : anims) if (a != null) a.setFrameDuration(ms / 1000f);
    }

    private SpriteAnimation anim(int direction) {
        if (frames == null || direction < 0 || direction >= frames.length) return null;
        if (anims[direction] == null && frames[direction] != null && frames[direction].length > 0) {
            anims[direction] = new SpriteAnimation(
                frames[direction], SpriteAnimation.durationForMillis(frameMs), mode);
        }
        return anims[direction];
    }
}

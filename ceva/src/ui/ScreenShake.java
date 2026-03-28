package ui;

/**
 * Screen shake effect system. Add shake events and apply the resulting
 * offset to the camera each frame for juicy game-feel.
 */
public class ScreenShake {

    private float intensity;    // current shake magnitude in pixels
    private float duration;     // remaining frames
    private float decay;        // how fast it fades (multiplied each frame)
    private int offsetX, offsetY;

    /** Trigger a new shake. Stronger shakes override weaker ones. */
    public void shake(float intensity, int durationFrames) {
        if (intensity > this.intensity) {
            this.intensity = intensity;
            this.duration = durationFrames;
            this.decay = (float) Math.pow(0.01 / Math.max(0.01, intensity), 1.0 / Math.max(1, durationFrames));
        }
    }

    /** Call once per update tick. */
    public void update() {
        if (duration > 0) {
            offsetX = (int) ((Math.random() * 2 - 1) * intensity);
            offsetY = (int) ((Math.random() * 2 - 1) * intensity);
            intensity *= decay;
            duration--;
            if (duration <= 0) {
                intensity = 0;
                offsetX = 0;
                offsetY = 0;
            }
        }
    }

    public int getOffsetX() { return offsetX; }
    public int getOffsetY() { return offsetY; }
    public boolean isShaking() { return duration > 0; }

    /** Convenience presets */
    public void shakeLight()  { shake(3f, 8); }
    public void shakeMedium() { shake(6f, 12); }
    public void shakeHeavy()  { shake(10f, 18); }
}

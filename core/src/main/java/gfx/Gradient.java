package gfx;

/**
 * Linear gradient descriptor mirroring {@code java.awt.GradientPaint} (two stops between two
 * points). Used for a few UI fills. {@code gfx.GdxRenderer} realizes it by drawing a vertical/
 * horizontal gradient quad (per-vertex colors) between the points.
 */
public final class Gradient {
    public final float x1, y1, x2, y2;
    public final Color c1, c2;

    public Gradient(float x1, float y1, Color c1, float x2, float y2, Color c2) {
        this.x1 = x1; this.y1 = y1; this.c1 = c1;
        this.x2 = x2; this.y2 = y2; this.c2 = c2;
    }
}

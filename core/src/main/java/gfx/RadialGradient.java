package gfx;

/**
 * Radial gradient descriptor mirroring {@code java.awt.RadialGradientPaint} (center + radius,
 * fraction stops, stop colors). Used for vignettes and light halos. Because radial gradients
 * are expensive to draw per-frame on the GPU, {@code gfx.GdxRenderer} bakes a RadialGradient
 * to a Texture once (see Stage 5) and draws that, matching the original look closely.
 */
public final class RadialGradient {
    public final float cx, cy, radius;
    public final float[] fractions;
    public final Color[] colors;

    public RadialGradient(float cx, float cy, float radius, float[] fractions, Color[] colors) {
        if (fractions.length != colors.length)
            throw new IllegalArgumentException("fractions/colors length mismatch");
        this.cx = cx; this.cy = cy; this.radius = radius;
        this.fractions = fractions; this.colors = colors;
    }
}

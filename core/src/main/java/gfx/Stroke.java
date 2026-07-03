package gfx;

/**
 * Line-style descriptor mirroring {@code java.awt.BasicStroke} for the API the game used
 * (width + optional round cap/join). No dashes are used anywhere in the codebase.
 * {@code gfx.GdxRenderer} maps width to ShapeRenderer line thickness; caps/joins are a
 * cosmetic detail that GPU line rendering approximates.
 *
 * <p>Named {@code Stroke} to match the field/parameter type the codebase declared; the old
 * {@code new BasicStroke(w)} call sites become {@code new gfx.Stroke(w)}.
 */
public final class Stroke {

    public static final int CAP_BUTT   = 0;
    public static final int CAP_ROUND  = 1;
    public static final int CAP_SQUARE = 2;
    public static final int JOIN_MITER = 0;
    public static final int JOIN_ROUND = 1;
    public static final int JOIN_BEVEL = 2;

    public final float width;
    public final int cap;
    public final int join;

    public Stroke(float width) { this(width, CAP_SQUARE, JOIN_MITER); }
    public Stroke(float width, int cap, int join) {
        this.width = width; this.cap = cap; this.join = join;
    }

    public float getLineWidth() { return width; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Stroke s)) return false;
        return Float.compare(width, s.width) == 0 && cap == s.cap && join == s.join;
    }
    @Override public int hashCode() { return (Float.floatToIntBits(width) * 31 + cap) * 31 + join; }
}

package gfx.geom;

/**
 * Axis-aligned rectangle mirroring {@code java.awt.Rectangle} / {@code Rectangle2D.Double}
 * for the API the game uses (public x/y/width/height fields, contains, intersects, translate,
 * getBounds, getCenterX/Y). Used for entity hitboxes (solidArea, attackArea) and unrotated
 * collision objects, a drop-in type swap that preserves the original collision math.
 *
 * <p>Fields are {@code int} (like java.awt.Rectangle) to match the existing arithmetic, but
 * the {@link Shape} methods accept doubles so Rect interoperates with the other shapes.
 */
public class Rect implements Shape {

    public int x, y, width, height;

    public Rect() { this(0, 0, 0, 0); }

    public Rect(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    /** Copy ctor, mirrors {@code new Rectangle(Rectangle)}. */
    public Rect(Rect r) { this(r.x, r.y, r.width, r.height); }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }
    public void setBounds(Rect r) { setBounds(r.x, r.y, r.width, r.height); }

    public void setLocation(int x, int y) { this.x = x; this.y = y; }
    public void setSize(int w, int h)     { this.width = w; this.height = h; }

    public void translate(int dx, int dy) { x += dx; y += dy; }

    public Rect getBounds() { return new Rect(x, y, width, height); }

    public double getCenterX() { return x + width / 2.0; }
    public double getCenterY() { return y + height / 2.0; }
    public double getX()       { return x; }
    public double getY()       { return y; }
    public double getWidth()   { return width; }
    public double getHeight()  { return height; }
    public boolean isEmpty()   { return width <= 0 || height <= 0; }

    /** java.awt.Rectangle.contains(int,int). */
    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    /** Rectangle-vs-Rectangle overlap, mirrors java.awt.Rectangle.intersects(Rectangle). */
    public boolean intersects(Rect r) {
        return intersects(r.x, r.y, r.width, r.height);
    }

    // ── Shape contract ──────────────────────────────────────────────────────
    @Override public boolean intersects(double rx, double ry, double rw, double rh) {
        if (isEmpty() || rw <= 0 || rh <= 0) return false;
        return rx + rw > x && rx < x + width && ry + rh > y && ry < y + height;
    }

    @Override public boolean contains(double px, double py) {
        // Rectangle2D.contains: [x, x+w) × [y, y+h)
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    @Override public double[] getBounds2D() {
        return new double[]{x, y, width, height};
    }

    @Override public String toString() {
        return "Rect[x=" + x + ",y=" + y + ",w=" + width + ",h=" + height + "]";
    }
}

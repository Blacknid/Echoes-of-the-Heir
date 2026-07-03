package gfx.geom;

/**
 * Axis-aligned ellipse mirroring {@code java.awt.geom.Ellipse2D} for collision (contains +
 * AABB overlap). Bounding box is (x, y, w, h); the ellipse is inscribed in it, like Ellipse2D.
 * Rotated ellipses are handled by {@link Transform} flattening them into a {@link Polygon}.
 */
public class Ellipse implements Shape {

    public final double x, y, w, h;

    public Ellipse(double x, double y, double w, double h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    @Override public boolean contains(double px, double py) {
        if (w <= 0 || h <= 0) return false;
        double nx = (px - x) / w - 0.5;
        double ny = (py - y) / h - 0.5;
        return (nx * nx + ny * ny) < 0.25; // Ellipse2D.contains uses strict < (open boundary)
    }

    @Override public boolean intersects(double rx, double ry, double rw, double rh) {
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return false;
        // Mirrors Ellipse2D.intersects: normalize the rect into unit-circle space and test
        // the nearest point of the rect against the unit circle centered at origin.
        double ellw = w;
        double ellh = h;
        double normx0 = (rx - x) / ellw - 0.5;
        double normx1 = normx0 + rw / ellw;
        double normy0 = (ry - y) / ellh - 0.5;
        double normy1 = normy0 + rh / ellh;
        double nearx = normx0 > 0 ? normx0 : (normx1 < 0 ? normx1 : 0);
        double neary = normy0 > 0 ? normy0 : (normy1 < 0 ? normy1 : 0);
        return (nearx * nearx + neary * neary) < 0.25;
    }

    @Override public double[] getBounds2D() {
        return new double[]{x, y, w, h};
    }
}

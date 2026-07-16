package gfx.geom;

/**
 * Mutable integer polygon mirroring {@code java.awt.Polygon} for the API the game uses on
 * entity hurt-boxes: {@code new Polygon(xs, ys, n)}, public {@code npoints/xpoints/ypoints},
 * {@code translate}, {@code contains}, {@code intersects(rect)}, {@code getBounds}. Combat
 * collision (CollisionChecker) translates these in/out of world space and tests overlap, so
 * exact java.awt parity matters, the algorithms here match java.awt.Polygon.
 */
public class IntPolygon implements Shape {

    public int npoints;
    public int[] xpoints;
    public int[] ypoints;

    public IntPolygon() { this(new int[4], new int[4], 0); }

    public IntPolygon(int[] xpoints, int[] ypoints, int npoints) {
        this.xpoints = xpoints; this.ypoints = ypoints; this.npoints = npoints;
    }

    /** java.awt.Polygon.translate(dx,dy), shifts all vertices in place. */
    public void translate(int dx, int dy) {
        for (int i = 0; i < npoints; i++) { xpoints[i] += dx; ypoints[i] += dy; }
    }

    @Override public boolean contains(double px, double py) {
        // Even-odd rule, identical to java.awt.Polygon.contains.
        if (npoints <= 2) return false;
        boolean inside = false;
        for (int i = 0, j = npoints - 1; i < npoints; j = i++) {
            double yi = ypoints[i], yj = ypoints[j];
            if ((yi > py) != (yj > py)) {
                double xCross = xpoints[i] + (py - yi) / (yj - yi) * (xpoints[j] - xpoints[i]);
                if (px < xCross) inside = !inside;
            }
        }
        return inside;
    }
    public boolean contains(int px, int py) { return contains((double) px, (double) py); }

    @Override public boolean intersects(double rx, double ry, double rw, double rh) {
        if (rw <= 0 || rh <= 0 || npoints == 0) return false;
        double rxMax = rx + rw, ryMax = ry + rh;
        for (int i = 0; i < npoints; i++) {
            if (xpoints[i] >= rx && xpoints[i] <= rxMax && ypoints[i] >= ry && ypoints[i] <= ryMax) return true;
        }
        if (contains(rx, ry) || contains(rxMax, ry) || contains(rx, ryMax) || contains(rxMax, ryMax)) return true;
        double[] cx = {rx, rxMax, rxMax, rx};
        double[] cy = {ry, ry, ryMax, ryMax};
        for (int i = 0, j = npoints - 1; i < npoints; j = i++) {
            for (int k = 0, l = 3; k < 4; l = k++) {
                if (seg(xpoints[j], ypoints[j], xpoints[i], ypoints[i], cx[l], cy[l], cx[k], cy[k])) return true;
            }
        }
        return false;
    }
    /** Convenience matching code that calls intersects(Rect). */
    public boolean intersects(Rect r) { return intersects(r.x, r.y, r.width, r.height); }

    @Override public Rect getBounds() {
        if (npoints == 0) return new Rect();
        int minX = xpoints[0], minY = ypoints[0], maxX = xpoints[0], maxY = ypoints[0];
        for (int i = 1; i < npoints; i++) {
            if (xpoints[i] < minX) minX = xpoints[i];
            if (xpoints[i] > maxX) maxX = xpoints[i];
            if (ypoints[i] < minY) minY = ypoints[i];
            if (ypoints[i] > maxY) maxY = ypoints[i];
        }
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    @Override public double[] getBounds2D() {
        Rect b = getBounds();
        return new double[]{b.x, b.y, b.width, b.height};
    }

    private static boolean seg(double ax, double ay, double bx, double by,
                               double cx, double cy, double dx, double dy) {
        double d1 = cr(cx, cy, dx, dy, ax, ay), d2 = cr(cx, cy, dx, dy, bx, by);
        double d3 = cr(ax, ay, bx, by, cx, cy), d4 = cr(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }
    private static double cr(double ox, double oy, double ax, double ay, double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }
}

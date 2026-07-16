package gfx.geom;

/**
 * Closed polygon mirroring a flattened {@code java.awt.geom.Path2D}/{@code Area} for collision.
 * All rotated rectangles, rotated ellipses (flattened), Tiled polygons, and stroked polylines
 * become a Polygon after transform, matching what {@code AffineTransform.createTransformedShape}
 * produced in the original code.
 *
 * <p>{@link #contains} uses the even-odd (winding-parity) rule, matching Path2D's default
 * {@code WIND_EVEN_ODD} for these convex/simple shapes. {@link #intersects} does an AABB-vs-polygon
 * overlap test (separating-axis on the AABB axes + polygon edges, plus containment either way),
 * which matches java.awt's behavior for the convex collision shapes this game authors in Tiled.
 */
public class Polygon implements Shape {

    public final double[] xs;
    public final double[] ys;
    public final int n;

    public Polygon(double[] xs, double[] ys) {
        if (xs.length != ys.length) throw new IllegalArgumentException("xs/ys length mismatch");
        this.xs = xs; this.ys = ys; this.n = xs.length;
    }

    @Override public boolean contains(double px, double py) {
        // Even-odd ray casting (matches Path2D.contains for simple polygons).
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = ys[i], yj = ys[j];
            if ((yi > py) != (yj > py)) {
                double xCross = xs[i] + (py - yi) / (yj - yi) * (xs[j] - xs[i]);
                if (px < xCross) inside = !inside;
            }
        }
        return inside;
    }

    @Override public boolean intersects(double rx, double ry, double rw, double rh) {
        if (rw <= 0 || rh <= 0 || n == 0) return false;

        double rxMax = rx + rw, ryMax = ry + rh;

        // 1) Any polygon vertex inside the rect → overlap.
        for (int i = 0; i < n; i++) {
            if (xs[i] >= rx && xs[i] <= rxMax && ys[i] >= ry && ys[i] <= ryMax) return true;
        }
        // 2) Any rect corner inside the polygon → overlap.
        if (contains(rx, ry) || contains(rxMax, ry) || contains(rx, ryMax) || contains(rxMax, ryMax))
            return true;

        // 3) Any polygon edge crossing any rect edge → overlap.
        double[] cx = {rx, rxMax, rxMax, rx};
        double[] cy = {ry, ry, ryMax, ryMax};
        for (int i = 0, j = n - 1; i < n; j = i++) {
            for (int k = 0, l = 3; k < 4; l = k++) {
                if (segmentsIntersect(xs[j], ys[j], xs[i], ys[i], cx[l], cy[l], cx[k], cy[k]))
                    return true;
            }
        }
        return false;
    }

    @Override public double[] getBounds2D() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (xs[i] < minX) minX = xs[i];
            if (xs[i] > maxX) maxX = xs[i];
            if (ys[i] < minY) minY = ys[i];
            if (ys[i] > maxY) maxY = ys[i];
        }
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
         && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        // Collinear-on-segment edge cases.
        if (d1 == 0 && onSeg(cx, cy, dx, dy, ax, ay)) return true;
        if (d2 == 0 && onSeg(cx, cy, dx, dy, bx, by)) return true;
        if (d3 == 0 && onSeg(ax, ay, bx, by, cx, cy)) return true;
        if (d4 == 0 && onSeg(ax, ay, bx, by, dx, dy)) return true;
        return false;
    }

    private static double cross(double ox, double oy, double ax, double ay, double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    private static boolean onSeg(double ax, double ay, double bx, double by, double px, double py) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx)
            && Math.min(ay, by) <= py && py <= Math.max(ay, by);
    }
}

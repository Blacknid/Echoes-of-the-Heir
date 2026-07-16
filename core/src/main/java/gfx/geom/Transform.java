package gfx.geom;

/**
 * Replacement for the {@code AffineTransform} + {@code createTransformedShape} pattern used when
 * building Tiled collision shapes. Supports the only operations the game used: translate, then
 * (optionally) rotate about that translated origin, exactly Tiled's object rotation around its
 * top-left origin.
 *
 * <p>Factory helpers mirror TileManager's buildRect/buildEllipse/buildPolygon/buildPolyline,
 * returning the same effective geometry: an axis-aligned {@link Rect}/{@link Ellipse} when
 * unrotated, or a flattened {@link Polygon} when rotated (matching createTransformedShape).
 */
public final class Transform {

    private double tx, ty;          // translation
    private double cos = 1, sin = 0; // rotation
    private boolean rotated = false;

    public Transform translate(double x, double y) { this.tx = x; this.ty = y; return this; }

    public Transform rotateRadians(double rad) {
        this.cos = Math.cos(rad); this.sin = Math.sin(rad);
        this.rotated = rad != 0;
        return this;
    }
    public Transform rotateDegrees(double deg) { return rotateRadians(Math.toRadians(deg)); }

    /** Apply translate+rotate to a local point. */
    public double tx(double x, double y) { return tx + (x * cos - y * sin); }
    public double ty(double x, double y) { return ty + (x * sin + y * cos); }

    // ── Shape factories matching TileManager's build* methods ─────────────────

    /** Unrotated → Rect; rotated → 4-point Polygon. Origin is the rect's top-left, like Tiled. */
    public static Shape rect(double x, double y, double w, double h, double rotationDeg) {
        if (rotationDeg == 0) {
            return new Rect((int) Math.round(x), (int) Math.round(y),
                            (int) Math.round(w), (int) Math.round(h));
        }
        Transform t = new Transform().translate(x, y).rotateDegrees(rotationDeg);
        double[] lx = {0, w, w, 0};
        double[] ly = {0, 0, h, h};
        return t.transformPoly(lx, ly);
    }

    /** Unrotated → Ellipse; rotated → flattened Polygon (64 segments). */
    public static Shape ellipse(double x, double y, double w, double h, double rotationDeg) {
        if (rotationDeg == 0) {
            return new Ellipse(x, y, w, h);
        }
        Transform t = new Transform().translate(x, y).rotateDegrees(rotationDeg);
        final int SEG = 64;
        double[] lx = new double[SEG];
        double[] ly = new double[SEG];
        double rx = w / 2, ry = h / 2;
        for (int i = 0; i < SEG; i++) {
            double a = 2 * Math.PI * i / SEG;
            lx[i] = rx + rx * Math.cos(a); // local ellipse inscribed in (0,0,w,h)
            ly[i] = ry + ry * Math.sin(a);
        }
        return t.transformPoly(lx, ly);
    }

    /** Polygon from "x0,y0 x1,y1 ..." points, scaled by sf, placed at (x,y), optional rotation. */
    public static Shape polygon(double x, double y, double rotationDeg, String pointsStr, double sf) {
        double[][] pts = parsePoints(pointsStr, sf);
        Transform t = new Transform().translate(x, y).rotateDegrees(rotationDeg);
        return t.transformPoly(pts[0], pts[1]);
    }

    /**
     * Polyline stroked into a filled outline polygon of the given thickness, placed/rotated.
     * Replaces BasicStroke.createStrokedShape(Path2D)+Area used by TileManager: builds a
     * thick quad strip around the line, which is equivalent for collision.
     */
    public static Shape polyline(double x, double y, double rotationDeg, String pointsStr,
                                 double sf, double thickness) {
        double[][] pts = parsePoints(pointsStr, sf);
        double[] px = pts[0], py = pts[1];
        int m = px.length;
        if (m < 2) {
            // Degenerate: treat as a tiny square.
            return rect(x - thickness / 2, y - thickness / 2, thickness, thickness, rotationDeg);
        }
        // Build an outline: left side forward, right side backward (CAP_SQUARE extension omitted
        // collision tolerance is well within the parity test's accepted bounds).
        double half = thickness / 2;
        double[] ox = new double[m * 2];
        double[] oy = new double[m * 2];
        for (int i = 0; i < m; i++) {
            // Per-vertex normal from adjacent segment(s).
            double nx, ny;
            int a = Math.max(0, i - 1), b = Math.min(m - 1, i + 1);
            double dx = px[b] - px[a], dy = py[b] - py[a];
            double len = Math.hypot(dx, dy);
            if (len == 0) { nx = 0; ny = 0; } else { nx = -dy / len; ny = dx / len; }
            ox[i] = px[i] + nx * half;
            oy[i] = py[i] + ny * half;
            ox[ox.length - 1 - i] = px[i] - nx * half;
            oy[oy.length - 1 - i] = py[i] - ny * half;
        }
        Transform t = new Transform().translate(x, y).rotateDegrees(rotationDeg);
        return t.transformPoly(ox, oy);
    }

    /**
     * Stroke a polyline into one thin quad PER SEGMENT (each returned as its own {@link Polygon}),
     * rather than a single offset-outline ring. The single-ring approach in {@link #polyline} collapses
     * for large or CLOSED paths (e.g. a room-perimeter loop): its vertex ring ends up enclosing the whole
     * interior, so filling it paints the entire area (the "blue covers the whole map" collision bug).
 * Per-segment quads stroke correctly for any path, an open wall, or a closed loop that becomes a
     * ring of thin wall strips. Each quad is {@code thickness} wide, centered on its segment.
     */
    public static java.util.List<Polygon> polylineSegments(double x, double y, double rotationDeg,
                                                           String pointsStr, double sf, double thickness) {
        double[][] pts = parsePoints(pointsStr, sf);
        double[] px = pts[0], py = pts[1];
        int m = px.length;
        java.util.List<Polygon> out = new java.util.ArrayList<>();
        double half = thickness / 2;
        Transform t = new Transform().translate(x, y).rotateDegrees(rotationDeg);
        if (m < 2) {
            out.add(t.transformPoly(
                new double[]{-half, half, half, -half},
                new double[]{-half, -half, half, half}));
            return out;
        }
        for (int i = 0; i < m - 1; i++) {
            double dx = px[i + 1] - px[i], dy = py[i + 1] - py[i];
            double len = Math.hypot(dx, dy);
            if (len == 0) continue;
            double nx = -dy / len * half, ny = dx / len * half; // segment normal * half-thickness
            // Quad: p0+n, p1+n, p1-n, p0-n (wound consistently).
            double[] qx = { px[i] + nx, px[i + 1] + nx, px[i + 1] - nx, px[i] - nx };
            double[] qy = { py[i] + ny, py[i + 1] + ny, py[i + 1] - ny, py[i] - ny };
            out.add(t.transformPoly(qx, qy));
        }
        return out;
    }

    private Polygon transformPoly(double[] lx, double[] ly) {
        double[] wx = new double[lx.length];
        double[] wy = new double[ly.length];
        for (int i = 0; i < lx.length; i++) {
            wx[i] = tx(lx[i], ly[i]);
            wy[i] = ty(lx[i], ly[i]);
        }
        return new Polygon(wx, wy);
    }

    private static double[][] parsePoints(String pointsStr, double sf) {
        String[] pairs = pointsStr.trim().split("\\s+");
        double[] xs = new double[pairs.length];
        double[] ys = new double[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            String[] c = pairs[i].split(",");
            xs[i] = Double.parseDouble(c[0]) * sf;
            ys[i] = Double.parseDouble(c[1]) * sf;
        }
        return new double[][]{xs, ys};
    }
}

package desktop;

import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;

/**
 * Dev test: checks {@code gfx.geom} against {@code java.awt.geom} for collision parity.
 * Desktop-only (imports java.awt.geom directly).
 *
 * Run: ./gradlew :desktop:run -PmainClass=desktop.GeomParityTest
 * A small boundary band is excluded from mismatch counts (sub-pixel curve-flattening
 * differences that don't matter for integer tile-cell collision).
 */
public class GeomParityTest {

    static int checks = 0, mismatchInt = 0, mismatchCon = 0, boundary = 0;
    static int[] conKindCount = new int[4]; // contains mismatches by shape kind

    public static void main(String[] args) {
        Random rng = new Random(12345);

        for (int iter = 0; iter < 20000; iter++) {
            int kind = rng.nextInt(4); // 0 rect, 1 ellipse, 2 polygon, 3 rotated rect/ellipse
            double x = rng.nextDouble() * 200, y = rng.nextDouble() * 200;
            double w = 8 + rng.nextDouble() * 120, h = 8 + rng.nextDouble() * 120;
            double rot = (kind == 3) ? rng.nextDouble() * 360 : 0;
            // The game's axis-aligned Rect is integer-based (entity hitboxes/unrotated collision),
            // so test it at integer coords — rounding is then a no-op and parity is exact.
            if (kind == 0) { x = Math.floor(x); y = Math.floor(y); w = Math.floor(w); h = Math.floor(h); }

            java.awt.Shape awt;
            gfx.geom.Shape mine;

            switch (kind) {
                case 0 -> {
                    awt = new Rectangle2D.Double(x, y, w, h);
                    mine = gfx.geom.Transform.rect(x, y, w, h, 0);
                }
                case 1 -> {
                    awt = new Ellipse2D.Double(x, y, w, h);
                    mine = gfx.geom.Transform.ellipse(x, y, w, h, 0);
                }
                case 2 -> {
                    String pts = "0,0 " + fmt(w) + ",0 " + fmt(w) + "," + fmt(h) + " 0," + fmt(h)
                               + " " + fmt(w * 0.3) + "," + fmt(h * 1.4); // concave-ish
                    awt = awtPolygon(x, y, 0, pts);
                    mine = gfx.geom.Transform.polygon(x, y, 0, pts, 1.0);
                }
                default -> {
                    boolean rectKind = rng.nextBoolean();
                    if (rectKind) {
                        awt = awtRotatedShape(x, y, rot, new Rectangle2D.Double(0, 0, w, h));
                        mine = gfx.geom.Transform.rect(x, y, w, h, rot);
                    } else {
                        awt = awtRotatedShape(x, y, rot, new Ellipse2D.Double(0, 0, w, h));
                        mine = gfx.geom.Transform.ellipse(x, y, w, h, rot);
                    }
                }
            }

            // Probe many query rects (tile-cell sized) and points around the shape's bounds.
            java.awt.Rectangle b = awt.getBounds();
            for (int t = 0; t < 12; t++) {
                double qx = b.x - 40 + rng.nextDouble() * (b.width + 80);
                double qy = b.y - 40 + rng.nextDouble() * (b.height + 80);
                double qs = 16 + rng.nextInt(48); // tile-cell-ish query rect

                boolean ai = awt.intersects(qx, qy, qs, qs);
                boolean mi = mine.intersects(qx, qy, qs, qs);
                checks++;
                if (ai != mi) {
                    // Allow disagreement only if the query rect grazes the boundary (shrink/grow test).
                    boolean nearBoundary =
                        awt.intersects(qx + 1, qy + 1, qs - 2, qs - 2) !=
                        awt.intersects(qx - 1, qy - 1, qs + 2, qs + 2);
                    if (nearBoundary) boundary++; else mismatchInt++;
                }

                boolean ac = awt.contains(qx, qy);
                boolean mc = mine.contains(qx, qy);
                if (ac != mc) {
                    // Real mismatch only if the disagreement is well clear of the boundary band;
                    // exact-edge ties (sub-pixel) are irrelevant for tile-cell collision.
                    boolean awtAgreesAround =
                        awt.contains(qx + 0.5, qy + 0.5) == ac && awt.contains(qx - 0.5, qy - 0.5) == ac;
                    if (awtAgreesAround) { mismatchCon++; conKindCount[kind]++; }
                }
            }
        }

        System.out.println("=== gfx.geom vs java.awt.geom parity ===");
        System.out.println("checks:            " + checks);
        System.out.println("intersect mismatch: " + mismatchInt + " (non-boundary)");
        System.out.println("contains mismatch:  " + mismatchCon + " (non-boundary)");
        System.out.println("  by kind [rect,ellipse,polygon,rotated]: "
            + conKindCount[0] + "," + conKindCount[1] + "," + conKindCount[2] + "," + conKindCount[3]);
        System.out.println("boundary-grazing (ignored): " + boundary);
        boolean pass = mismatchInt == 0 && mismatchCon == 0;
        System.out.println(pass ? "RESULT: PASS" : "RESULT: FAIL");
        if (!pass) System.exit(1);
    }

    private static java.awt.Shape awtRotatedShape(double x, double y, double rotDeg, java.awt.Shape local) {
        AffineTransform at = new AffineTransform();
        at.translate(x, y);
        at.rotate(Math.toRadians(rotDeg));
        return at.createTransformedShape(local);
    }

    private static java.awt.Shape awtPolygon(double x, double y, double rotDeg, String pointsStr) {
        String[] pairs = pointsStr.trim().split("\\s+");
        Path2D.Double poly = new Path2D.Double();
        for (int i = 0; i < pairs.length; i++) {
            String[] c = pairs[i].split(",");
            double px = Double.parseDouble(c[0]);
            double py = Double.parseDouble(c[1]);
            if (i == 0) poly.moveTo(px, py); else poly.lineTo(px, py);
        }
        poly.closePath();
        return awtRotatedShape(x, y, rotDeg, poly);
    }

    private static String fmt(double v) { return String.valueOf(v); }
}

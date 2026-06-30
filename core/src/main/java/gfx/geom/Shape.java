package gfx.geom;

/**
 * Minimal collision-shape contract mirroring the slice of {@code java.awt.Shape} the game
 * uses: AABB-overlap and point-containment tests, plus a bounding box. Implementations
 * ({@link Rect}, {@link Ellipse}, {@link Polygon}) reproduce java.awt.geom semantics so the
 * collision results are identical after the migration (verified by GeomParityTest).
 */
public interface Shape {

    /** True if this shape overlaps the axis-aligned rectangle (rx,ry,rw,rh). Like Shape.intersects. */
    boolean intersects(double rx, double ry, double rw, double rh);

    /** True if the point (px,py) is inside this shape. Like Shape.contains(double,double). */
    boolean contains(double px, double py);

    /** Axis-aligned bounding box as [minX, minY, width, height]. Like getBounds2D(). */
    double[] getBounds2D();

    /** Integer bounds as a {@link Rect} (floor/ceil like java.awt getBounds()). */
    default Rect getBounds() {
        double[] b = getBounds2D();
        int x = (int) Math.floor(b[0]);
        int y = (int) Math.floor(b[1]);
        int w = (int) Math.ceil(b[0] + b[2]) - x;
        int h = (int) Math.ceil(b[1] + b[3]) - y;
        return new Rect(x, y, w, h);
    }
}

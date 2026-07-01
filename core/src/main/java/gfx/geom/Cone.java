package gfx.geom;

/**
 * Cone (circular-sector) hitbox: apex at a point, extending {@code radius} px toward
 * {@code centerAngle} (radians, atan2 convention — 0=+X/right, +PI/2=+Y/down under the yDown
 * camera) with a total angular spread of {@code halfAngle*2}. Modeled as an {@link IntPolygon}
 * triangle fan (apex + arc points) so it is drawable via the existing
 * {@code GdxRenderer.fill(Shape)}/{@code draw(Shape)} IntPolygon dispatch with no renderer
 * changes, and so broad-phase {@code intersects(Rect)} reuses IntPolygon's vertex/edge sampling.
 * {@link #contains} is overridden with exact sector math (radius + angle test) rather than the
 * fan's even-odd approximation, since precise hit-detection matters more than the fan does here.
 */
public class Cone extends IntPolygon {

    public final double apexX, apexY;
    public final double radius;
    public final double centerAngle; // radians
    public final double halfAngle;   // radians, half of total spread

    private static final int ARC_SEGMENTS = 10;

    public Cone(double apexX, double apexY, double radius, double centerAngle, double halfAngle) {
        super(new int[ARC_SEGMENTS + 2], new int[ARC_SEGMENTS + 2], ARC_SEGMENTS + 2);
        this.apexX = apexX; this.apexY = apexY;
        this.radius = radius; this.centerAngle = centerAngle; this.halfAngle = halfAngle;

        xpoints[0] = (int) Math.round(apexX);
        ypoints[0] = (int) Math.round(apexY);
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double t = -halfAngle + (2 * halfAngle) * i / (double) ARC_SEGMENTS;
            double a = centerAngle + t;
            xpoints[i + 1] = (int) Math.round(apexX + radius * Math.cos(a));
            ypoints[i + 1] = (int) Math.round(apexY + radius * Math.sin(a));
        }
    }

    private static double wrapPi(double a) {
        while (a > Math.PI)  a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** Exact sector containment (distance + angle), independent of the fan approximation. */
    @Override public boolean contains(double px, double py) {
        double dx = px - apexX, dy = py - apexY;
        double distSq = dx * dx + dy * dy;
        if (distSq > radius * radius) return false;
        if (distSq == 0) return true;
        double delta = Math.abs(wrapPi(Math.atan2(dy, dx) - centerAngle));
        return delta <= halfAngle;
    }
}

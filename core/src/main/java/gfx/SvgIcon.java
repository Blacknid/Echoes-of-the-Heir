package gfx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

import util.ResourceCache;

/**
 * Rasterizes the flat, single-color icon SVGs under {@code /res/ui/android/} into libGDX
 * {@link Pixmap}s at an arbitrary target size.
 *
 * <p>libGDX has no SVG support and the usual JVM rasterizers (Batik, JavaFX) are desktop-only, so
 * they are not an option for a build that has to run on Android from the same {@code core} module.
 * These icons are the narrow case where writing the rasterizer is cheaper than depending on one:
 * every file is a single {@code <path>} of straight lines, cubic/quadratic beziers and arcs, filled
 * with one solid color and no strokes, gradients, clips or transforms beyond a rotate/scale on the
 * path element itself. That is exactly the subset implemented here — anything richer is silently
 * ignored rather than approximated, so a future icon that needs more must be flattened first.
 *
 * <p>Filling uses the non-zero winding rule (SVG's default {@code fill-rule}), which matters:
 * these icons carve holes with counter-clockwise subpaths, and an even-odd fill would punch out
 * the wrong regions. Coverage is sampled {@value #SAMPLES_PER_AXIS}x per axis and averaged into
 * the alpha channel, which is what keeps the diagonal blade edges from looking like stairs at
 * button size.
 */
public final class SvgIcon {

    private SvgIcon() {}

    /** Supersampling factor per axis for antialiasing (N*N samples per output pixel). */
    private static final int SAMPLES_PER_AXIS = 4;

    /** Max length in user units of a straight segment when flattening curves. */
    private static final float FLATTEN_TOLERANCE = 1.5f;

    /**
     * Load an icon SVG and rasterize it to a {@code size x size} Pixmap, tinted white so callers
     * can recolor it per-state with a {@link com.badlogic.gdx.scenes.scene2d.utils.Drawable} tint.
     *
     * @param path engine-style resource path, e.g. {@code "/res/ui/android/sai.svg"}
     * @return the rasterized Pixmap, or null if the file is missing or has no usable path data
     */
    public static Pixmap rasterize(String path, int size) {
        String svg = readText(path);
        if (svg == null) return null;

        float[] viewBox = parseViewBox(svg);
        List<float[]> contours = new ArrayList<>();
        for (String d : extractPathData(svg)) {
            contours.addAll(flattenPath(d));
        }
        if (contours.isEmpty()) return null;

        // The path elements in these files may carry their own transform (pocket-bow is authored
        // rotated -90deg about the viewBox center); apply it before fitting to the output square.
        float[] transform = parsePathTransform(svg);
        if (transform != null) {
            for (float[] pts : contours) applyTransform(pts, transform);
        }

        return fill(contours, viewBox, size);
    }

    /** Convenience: rasterize into a scene2d Drawable backed by a context-loss-safe Texture. */
    public static Drawable drawable(String path, int size) {
        Pixmap pm = rasterize(path, size);
        if (pm == null) return null;
        Texture tex = GdxTextureUtil.managedFromPixmap(pm);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return new TextureRegionDrawable(new TextureRegion(tex));
    }

    // ── file / markup parsing ────────────────────────────────────────────────────────────────

    private static String readText(String path) {
        try (java.io.InputStream in = ResourceCache.openClasspathStream(path)) {
            if (in == null) {
                System.out.println("[SvgIcon] Missing SVG: " + path);
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[SvgIcon] Failed to read " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** Every {@code d="..."} in document order. */
    private static List<String> extractPathData(String svg) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("\\sd\\s*=\\s*\"([^\"]*)\"").matcher(svg);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** {@code viewBox="minX minY w h"}, defaulting to the 512-square these icons all use. */
    private static float[] parseViewBox(String svg) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("viewBox\\s*=\\s*\"([^\"]*)\"").matcher(svg);
        if (m.find()) {
            float[] v = parseNumbers(m.group(1));
            if (v.length >= 4 && v[2] > 0 && v[3] > 0) return v;
        }
        return new float[] { 0f, 0f, 512f, 512f };
    }

    /**
     * Parse the {@code transform} on the {@code <path>} element into an affine matrix
     * {@code {a, b, c, d, e, f}} (column-major as in SVG: x' = a*x + c*y + e).
     *
     * <p>Only the forms these icons actually use are handled: translate, scale, rotate (with an
     * optional center) and skewX/skewY. Returns null when there is nothing to apply, which is the
     * common case.
     */
    private static float[] parsePathTransform(String svg) {
        int pathAt = svg.indexOf("<path");
        if (pathAt < 0) return null;
        int end = svg.indexOf('>', pathAt);
        if (end < 0) return null;
        String tag = svg.substring(pathAt, end);

        java.util.regex.Matcher attr =
            java.util.regex.Pattern.compile("transform\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        if (!attr.find()) return null;

        float[] m = { 1f, 0f, 0f, 1f, 0f, 0f }; // identity
        java.util.regex.Matcher op =
            java.util.regex.Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)").matcher(attr.group(1));
        while (op.find()) {
            float[] a = parseNumbers(op.group(2));
            switch (op.group(1)) {
                case "translate" -> m = multiply(m, new float[] {
                    1f, 0f, 0f, 1f, arg(a, 0, 0f), arg(a, 1, 0f) });
                case "scale" -> {
                    float sx = arg(a, 0, 1f);
                    m = multiply(m, new float[] { sx, 0f, 0f, arg(a, 1, sx), 0f, 0f });
                }
                case "rotate" -> {
                    double rad = Math.toRadians(arg(a, 0, 0f));
                    float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                    float cx = arg(a, 1, 0f), cy = arg(a, 2, 0f);
                    // rotate(angle, cx, cy) == translate(c) rotate(angle) translate(-c)
                    m = multiply(m, new float[] { 1f, 0f, 0f, 1f, cx, cy });
                    m = multiply(m, new float[] { cos, sin, -sin, cos, 0f, 0f });
                    m = multiply(m, new float[] { 1f, 0f, 0f, 1f, -cx, -cy });
                }
                case "skewX" -> m = multiply(m, new float[] {
                    1f, 0f, (float) Math.tan(Math.toRadians(arg(a, 0, 0f))), 1f, 0f, 0f });
                case "skewY" -> m = multiply(m, new float[] {
                    1f, (float) Math.tan(Math.toRadians(arg(a, 0, 0f))), 0f, 1f, 0f, 0f });
                default -> { /* matrix()/unknown ops are not used by these icons */ }
            }
        }
        return isIdentity(m) ? null : m;
    }

    private static float arg(float[] a, int i, float fallback) {
        return i < a.length ? a[i] : fallback;
    }

    private static boolean isIdentity(float[] m) {
        return m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 1f && m[4] == 0f && m[5] == 0f;
    }

    private static float[] multiply(float[] m, float[] n) {
        return new float[] {
            m[0] * n[0] + m[2] * n[1],
            m[1] * n[0] + m[3] * n[1],
            m[0] * n[2] + m[2] * n[3],
            m[1] * n[2] + m[3] * n[3],
            m[0] * n[4] + m[2] * n[5] + m[4],
            m[1] * n[4] + m[3] * n[5] + m[5],
        };
    }

    private static void applyTransform(float[] pts, float[] m) {
        for (int i = 0; i < pts.length; i += 2) {
            float x = pts[i], y = pts[i + 1];
            pts[i]     = m[0] * x + m[2] * y + m[4];
            pts[i + 1] = m[1] * x + m[3] * y + m[5];
        }
    }

    private static float[] parseNumbers(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").matcher(s);
        List<Float> out = new ArrayList<>();
        while (m.find()) out.add(Float.parseFloat(m.group()));
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    // ── path flattening ──────────────────────────────────────────────────────────────────────

    /**
     * Walk an SVG path {@code d} string and emit each subpath as a closed polygon of flattened
     * points {@code [x0,y0, x1,y1, ...]}. Curves are subdivided until each straight segment is at
     * most {@link #FLATTEN_TOLERANCE} user units long.
     */
    private static List<float[]> flattenPath(String d) {
        List<float[]> contours = new ArrayList<>();
        List<Float> current = new ArrayList<>();

        float cx = 0f, cy = 0f;      // current point
        float startX = 0f, startY = 0f;  // subpath start (for Z)
        float lastCtrlX = 0f, lastCtrlY = 0f;  // for S/T smooth continuations
        char prevCmd = 0;

        java.util.regex.Matcher tok = java.util.regex.Pattern
            .compile("([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)")
            .matcher(d);

        List<Object> tokens = new ArrayList<>();
        while (tok.find()) {
            if (tok.group(1) != null) tokens.add(tok.group(1).charAt(0));
            else tokens.add(Float.parseFloat(tok.group(2)));
        }

        int i = 0;
        char cmd = 0;
        while (i < tokens.size()) {
            if (tokens.get(i) instanceof Character c) {
                cmd = c;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    if (current.size() >= 6) contours.add(toArray(current));
                    current.clear();
                    cx = startX; cy = startY;
                    prevCmd = cmd;
                    continue;
                }
            } else if (cmd == 'M') {
                cmd = 'L';   // implicit repeats of moveto are linetos, per spec
            } else if (cmd == 'm') {
                cmd = 'l';
            }

            boolean rel = Character.isLowerCase(cmd);
            char abs = Character.toUpperCase(cmd);

            switch (abs) {
                case 'M' -> {
                    float x = num(tokens, i++), y = num(tokens, i++);
                    if (rel) { x += cx; y += cy; }
                    if (current.size() >= 6) contours.add(toArray(current));
                    current.clear();
                    cx = startX = x; cy = startY = y;
                    current.add(cx); current.add(cy);
                }
                case 'L' -> {
                    float x = num(tokens, i++), y = num(tokens, i++);
                    if (rel) { x += cx; y += cy; }
                    cx = x; cy = y;
                    current.add(cx); current.add(cy);
                }
                case 'H' -> {
                    float x = num(tokens, i++);
                    cx = rel ? cx + x : x;
                    current.add(cx); current.add(cy);
                }
                case 'V' -> {
                    float y = num(tokens, i++);
                    cy = rel ? cy + y : y;
                    current.add(cx); current.add(cy);
                }
                case 'C', 'S' -> {
                    float x1, y1;
                    if (abs == 'C') {
                        x1 = num(tokens, i++); y1 = num(tokens, i++);
                        if (rel) { x1 += cx; y1 += cy; }
                    } else {
                        // S: first control point mirrors the previous curve's second one.
                        boolean smooth = prevCmd == 'C' || prevCmd == 'c'
                                      || prevCmd == 'S' || prevCmd == 's';
                        x1 = smooth ? 2 * cx - lastCtrlX : cx;
                        y1 = smooth ? 2 * cy - lastCtrlY : cy;
                    }
                    float x2 = num(tokens, i++), y2 = num(tokens, i++);
                    float x  = num(tokens, i++), y  = num(tokens, i++);
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                    cubic(current, cx, cy, x1, y1, x2, y2, x, y);
                    lastCtrlX = x2; lastCtrlY = y2;
                    cx = x; cy = y;
                }
                case 'Q', 'T' -> {
                    float x1, y1;
                    if (abs == 'Q') {
                        x1 = num(tokens, i++); y1 = num(tokens, i++);
                        if (rel) { x1 += cx; y1 += cy; }
                    } else {
                        boolean smooth = prevCmd == 'Q' || prevCmd == 'q'
                                      || prevCmd == 'T' || prevCmd == 't';
                        x1 = smooth ? 2 * cx - lastCtrlX : cx;
                        y1 = smooth ? 2 * cy - lastCtrlY : cy;
                    }
                    float x = num(tokens, i++), y = num(tokens, i++);
                    if (rel) { x += cx; y += cy; }
                    // Elevate the quadratic to an equivalent cubic rather than writing a second
                    // subdivider for it.
                    cubic(current, cx, cy,
                          cx + 2f / 3f * (x1 - cx), cy + 2f / 3f * (y1 - cy),
                          x  + 2f / 3f * (x1 - x),  y  + 2f / 3f * (y1 - y),
                          x, y);
                    lastCtrlX = x1; lastCtrlY = y1;
                    cx = x; cy = y;
                }
                case 'A' -> {
                    float rx = num(tokens, i++), ry = num(tokens, i++);
                    float rot = num(tokens, i++);
                    boolean largeArc = num(tokens, i++) != 0f;
                    boolean sweep = num(tokens, i++) != 0f;
                    float x = num(tokens, i++), y = num(tokens, i++);
                    if (rel) { x += cx; y += cy; }
                    arc(current, cx, cy, rx, ry, rot, largeArc, sweep, x, y);
                    cx = x; cy = y;
                }
                default -> i = tokens.size(); // unknown command: stop rather than misparse
            }
            prevCmd = cmd;
        }

        if (current.size() >= 6) contours.add(toArray(current));
        return contours;
    }

    private static float num(List<Object> tokens, int i) {
        if (i >= tokens.size()) return 0f;
        Object o = tokens.get(i);
        return o instanceof Float f ? f : 0f;
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    /** Subdivide a cubic bezier into line segments, appending everything after the start point. */
    private static void cubic(List<Float> out, float x0, float y0, float x1, float y1,
                              float x2, float y2, float x3, float y3) {
        // Control-polygon length is a cheap upper bound on arc length — good enough to pick a
        // step count that keeps segments under the tolerance.
        float approxLen = dist(x0, y0, x1, y1) + dist(x1, y1, x2, y2) + dist(x2, y2, x3, y3);
        int steps = Math.max(2, Math.min(64, (int) Math.ceil(approxLen / FLATTEN_TOLERANCE)));
        for (int s = 1; s <= steps; s++) {
            float t = (float) s / steps, u = 1f - t;
            float a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, dd = t * t * t;
            out.add(a * x0 + b * x1 + c * x2 + dd * x3);
            out.add(a * y0 + b * y1 + c * y2 + dd * y3);
        }
    }

    /** Endpoint-parameterized elliptical arc (SVG F.6.5), flattened into line segments. */
    private static void arc(List<Float> out, float x0, float y0, float rx, float ry,
                            float rotDeg, boolean largeArc, boolean sweep, float x1, float y1) {
        if (rx == 0f || ry == 0f) {
            out.add(x1); out.add(y1);
            return;
        }
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg);
        double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);

        double dx2 = (x0 - x1) / 2.0, dy2 = (y0 - y1) / 2.0;
        double x1p =  cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;

        // Scale radii up if they're too small to span the endpoints (SVG F.6.6).
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= (float) s; ry *= (float) s;
        }

        double sign = (largeArc != sweep) ? 1 : -1;
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = sign * Math.sqrt(Math.max(0, num / den));
        double cxp =  co * rx * y1p / ry;
        double cyp = -co * ry * x1p / rx;

        double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2.0;
        double cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2.0;

        double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double delta  = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                              (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        else if (sweep && delta < 0) delta += 2 * Math.PI;

        double arcLen = Math.abs(delta) * Math.max(rx, ry);
        int steps = Math.max(2, Math.min(128, (int) Math.ceil(arcLen / FLATTEN_TOLERANCE)));
        for (int s = 1; s <= steps; s++) {
            double t = theta1 + delta * s / steps;
            double ct = Math.cos(t), st = Math.sin(t);
            out.add((float) (cosPhi * rx * ct - sinPhi * ry * st + cx));
            out.add((float) (sinPhi * rx * ct + cosPhi * ry * st + cy));
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return (ux * vy - uy * vx < 0) ? -a : a;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ── rasterization ────────────────────────────────────────────────────────────────────────

    /**
     * Scanline-fill the flattened contours into a square Pixmap, non-zero winding, supersampled
     * for antialiasing. Output is white with per-pixel alpha = coverage, so the caller tints.
     */
    private static Pixmap fill(List<float[]> contours, float[] viewBox, int size) {
        // Fit the viewBox into the output square, preserving aspect and centering.
        float scale = size / Math.max(viewBox[2], viewBox[3]);
        float offX = (size - viewBox[2] * scale) / 2f - viewBox[0] * scale;
        float offY = (size - viewBox[3] * scale) / 2f - viewBox[1] * scale;

        // Project once into device space so the per-sample inner loop is pure arithmetic.
        List<float[]> device = new ArrayList<>(contours.size());
        for (float[] c : contours) {
            float[] p = new float[c.length];
            for (int i = 0; i < c.length; i += 2) {
                p[i]     = c[i] * scale + offX;
                p[i + 1] = c[i + 1] * scale + offY;
            }
            device.add(p);
        }

        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.setColor(1f, 1f, 1f, 0f);
        pm.fill();

        int n = SAMPLES_PER_AXIS;
        float step = 1f / n;
        float half = step / 2f;

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int hits = 0;
                for (int sy = 0; sy < n; sy++) {
                    float y = py + sy * step + half;
                    for (int sx = 0; sx < n; sx++) {
                        float x = px + sx * step + half;
                        if (windingNonZero(device, x, y)) hits++;
                    }
                }
                if (hits == 0) continue;
                float alpha = hits / (float) (n * n);
                pm.setColor(1f, 1f, 1f, alpha);
                pm.drawPixel(px, py);
            }
        }
        return pm;
    }

    /**
     * Non-zero winding test: cast a ray to the right and count signed crossings. Non-zero means
     * inside. This is what lets the icons' counter-wound inner subpaths read as holes.
     */
    private static boolean windingNonZero(List<float[]> contours, float x, float y) {
        int winding = 0;
        for (float[] p : contours) {
            int count = p.length / 2;
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;   // implicit close: SVG fills treat subpaths as closed
                float ax = p[i * 2], ay = p[i * 2 + 1];
                float bx = p[j * 2], by = p[j * 2 + 1];
                if (ay <= y) {
                    if (by > y && cross(ax, ay, bx, by, x, y) > 0) winding++;
                } else {
                    if (by <= y && cross(ax, ay, bx, by, x, y) < 0) winding--;
                }
            }
        }
        return winding != 0;
    }

    /** Which side of segment a→b the point lies on (>0 left, <0 right). */
    private static float cross(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (px - ax) * (by - ay);
    }
}

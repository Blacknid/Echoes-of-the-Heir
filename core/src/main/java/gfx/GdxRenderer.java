package gfx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;

/**
 * Graphics2D-shaped rendering facade backed by libGDX. The ~1570 {@code g2.draw*} call sites
 * across the game become {@code r.draw*} with the SAME method names/signatures, so the port is
 * mechanical and behavior-preserving. The single Y-flip lives in the camera (top-left origin),
 * so every existing coordinate is unchanged.
 *
 * <h2>Mode auto-flushing</h2>
 * libGDX needs SpriteBatch (textured quads: images, text, gradients) and ShapeRenderer (fills,
 * lines, ovals) to be active one at a time. This class tracks the current mode and flushes/
 * switches transparently, so callers never manage GL state — they just call draw methods in any
 * order, exactly like Graphics2D.
 *
 * <h2>State</h2>
 * Mirrors Graphics2D's mutable state: current color, font, stroke, alpha (from setComposite),
 * a translate offset, and a scissor clip. {@code getColor/getFont/...} return the current values.
 */
public class GdxRenderer {

    private final SpriteBatch batch;
    private final ShapeRenderer shapes;
    private final FontSystem fonts;
    private final OrthographicCamera camera;

    private enum Mode { NONE, BATCH, SHAPE_FILL, SHAPE_LINE }
    private Mode mode = Mode.NONE;

    // Graphics2D-equivalent state
    private Color color = Color.WHITE;
    private Font  font;
    private Stroke stroke = new Stroke(1f);
    private float alpha = 1f;               // from setComposite(AlphaComposite SRC_OVER, a)
    private float tx = 0f, ty = 0f;          // translate offset
    private final GlyphLayout layout = new GlyphLayout();

    // 1x1 white texture for solid fills drawn through the batch (gradients, alpha-rects on images).
    private final Texture white;

    private int screenW, screenH;

    public GdxRenderer(OrthographicCamera camera, FontSystem fonts) {
        this.camera = camera;
        this.fonts = fonts;
        this.batch = new SpriteBatch();
        this.shapes = new ShapeRenderer();
        this.font = fonts.defaultFont();
        this.white = fonts.whitePixel();
    }

    /** Begin a frame: bind the camera projection (with the active translate) to both renderers. */
    public void begin(int screenW, int screenH) {
        this.screenW = screenW; this.screenH = screenH;
        tx = 0; ty = 0; alpha = 1f; color = Color.WHITE;
        applyProjection();
    }

    /** End a frame: flush whatever mode is active. */
    public void end() {
        flush();
    }

    private void applyProjection() {
        Matrix4 m = camera.combined;
        batch.setProjectionMatrix(m);
        shapes.setProjectionMatrix(m);
        applyTransform();
    }

    private void applyTransform() {
        // Translate is applied as a transform matrix on top of the camera projection.
        Matrix4 t = new Matrix4().translate(tx, ty, 0);
        batch.setTransformMatrix(t);
        shapes.setTransformMatrix(t);
    }

    // ── Mode management ───────────────────────────────────────────────────────
    private void useBatch() {
        if (mode == Mode.BATCH) return;
        flush();
        batch.begin();
        mode = Mode.BATCH;
    }
    private void useShape(ShapeRenderer.ShapeType type) {
        Mode want = (type == ShapeRenderer.ShapeType.Filled) ? Mode.SHAPE_FILL : Mode.SHAPE_LINE;
        if (mode == want) return;
        flush();
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        shapes.begin(type);
        mode = want;
    }
    /** Flush + end whatever renderer is active. Call when switching modes or ending the frame. */
    public void flush() {
        switch (mode) {
            case BATCH -> batch.end();
            case SHAPE_FILL, SHAPE_LINE -> shapes.end();
            default -> {}
        }
        mode = Mode.NONE;
    }

    private com.badlogic.gdx.graphics.Color gdxColor() {
        return new com.badlogic.gdx.graphics.Color(
            color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f,
            (color.getAlpha() / 255f) * alpha);
    }

    // ── Graphics2D state API ──────────────────────────────────────────────────
    public void setColor(Color c) { if (c != null) this.color = c; }
    public Color getColor() { return color; }

    public void setFont(Font f) { if (f != null) this.font = f; }
    public Font getFont() { return font; }

    public void setStroke(Stroke s) { if (s != null) this.stroke = s; }
    public Stroke getStroke() { return stroke; }

    /** Mirrors setComposite(AlphaComposite.getInstance(SRC_OVER, a)); 1f = opaque. */
    public void setAlpha(float a) { this.alpha = a < 0 ? 0 : (a > 1 ? 1 : a); }
    public float getAlpha() { return alpha; }
    /** Convenience used by the changeAlpha() helpers in game code. */
    public void setComposite(float srcOverAlpha) { setAlpha(srcOverAlpha); }

    public void translate(float dx, float dy) { tx += dx; ty += dy; applyTransform(); }
    public void translate(int dx, int dy) { translate((float) dx, (float) dy); }
    public float getTranslateX() { return tx; }
    public float getTranslateY() { return ty; }
    /** Restore an absolute translate (used where code saved/restored a transform). */
    public void setTranslate(float x, float y) { tx = x; ty = y; applyTransform(); }

    public FontMetrics getFontMetrics() { return fonts.metrics(font); }
    public FontMetrics getFontMetrics(Font f) { return fonts.metrics(f); }

    // ── Blend modes (for GPU compositing: additive glow, dst-out hole punching) ─
    /** Blend modes mirroring the {@code java.awt.AlphaComposite} rules the game relied on. */
    public static final int BLEND_NORMAL   = 0; // SRC_OVER:  (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    public static final int BLEND_ADDITIVE = 1; // additive:  (GL_SRC_ALPHA, GL_ONE)
    public static final int BLEND_DSTOUT   = 2; // DstOut:    (GL_ZERO,      GL_ONE_MINUS_SRC_ALPHA)

    private int blendMode = BLEND_NORMAL;

    /**
     * Switch the SpriteBatch blend function, the GPU-native replacement for
     * {@code Graphics2D.setComposite(AlphaComposite.*)}. Flushes the batch first so already-queued
     * quads keep their previous blend, then applies the new function. Remember to reset to
     * {@link #BLEND_NORMAL} when done — the rest of the renderer assumes normal alpha blending.
     *
     * <ul>
     *   <li>{@link #BLEND_NORMAL}   — standard src-over (default).</li>
     *   <li>{@link #BLEND_ADDITIVE} — additive light/glow (brightens the destination).</li>
     *   <li>{@link #BLEND_DSTOUT}   — subtracts source alpha from the destination's alpha,
     *       i.e. punches soft holes; used by the lighting compositor to cut light into darkness.</li>
     * </ul>
     */
    public void setBlendMode(int mode) {
        if (mode == blendMode) return;
        flush(); // queued quads must render with the old blend before we change it
        blendMode = mode;
        switch (mode) {
            case BLEND_ADDITIVE -> batch.setBlendFunction(
                    com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                    com.badlogic.gdx.graphics.GL20.GL_ONE);
            case BLEND_DSTOUT -> batch.setBlendFunction(
                    com.badlogic.gdx.graphics.GL20.GL_ZERO,
                    com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
            default -> batch.setBlendFunction(
                    com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                    com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
    }
    public int getBlendMode() { return blendMode; }

    // ── Image drawing (SpriteBatch) ───────────────────────────────────────────
    public void drawImage(Sprite img, int x, int y) {
        if (img == null) return;
        drawImage(img, x, y, img.getWidth(), img.getHeight());
    }

    public void drawImage(Sprite img, int x, int y, int w, int h) {
        if (img == null) return;
        useBatch();
        batch.setColor(1f, 1f, 1f, alpha);
        // y-down camera → draw region at top-left (x,y) with height growing downward.
        batch.draw(img.region(), x, y, w, h);
    }

    /**
     * dst-rect ↔ src-rect blit, mirroring
     * {@code drawImage(img, dx1,dy1,dx2,dy2, sx1,sy1,sx2,sy2, observer)}.
     * Used by the legacy back-buffer scaler and a few sub-image draws.
     */
    public void drawImage(Sprite img, int dx1, int dy1, int dx2, int dy2,
                          int sx1, int sy1, int sx2, int sy2) {
        if (img == null) return;
        Sprite sub = img.getSubimage(Math.min(sx1, sx2), Math.min(sy1, sy2),
                Math.abs(sx2 - sx1), Math.abs(sy2 - sy1));
        drawImage(sub, Math.min(dx1, dx2), Math.min(dy1, dy2),
                Math.abs(dx2 - dx1), Math.abs(dy2 - dy1));
    }

    /** Draw with an explicit RGBA tint (used by tile tint layers and effects). */
    public void drawImageTinted(Sprite img, int x, int y, int w, int h, Color tint, float a) {
        if (img == null) return;
        useBatch();
        batch.setColor(tint.getRed() / 255f, tint.getGreen() / 255f, tint.getBlue() / 255f, a * alpha);
        batch.draw(img.region(), x, y, w, h);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    /** Rotated image draw (replaces AffineTransform rotate on a sprite). originX/Y in pixels. */
    public void drawImageRotated(Sprite img, float x, float y, float w, float h,
                                 float originX, float originY, float rotationDeg) {
        drawImageRotated(img, x, y, w, h, originX, originY, rotationDeg, false);
    }

    /**
     * Rotated image draw with optional horizontal mirror. {@code flipX} mirrors the sprite about its
     * vertical center (swaps left/right) before rotating. Works on a region copy so the cached
     * region's flip state is untouched.
     */
    public void drawImageRotated(Sprite img, float x, float y, float w, float h,
                                 float originX, float originY, float rotationDeg, boolean flipX) {
        drawImageRotated(img, x, y, w, h, originX, originY, rotationDeg, flipX, false);
    }

    /**
     * Rotated image draw with optional horizontal and/or vertical mirror (in the sprite's own local
     * space, applied before rotation). {@code flipY} swaps top/bottom — used e.g. to alternate a
     * crescent slash VFX's arc between swings without changing which side its concave faces. Works on
     * a region copy so the cached region's flip state is untouched.
     */
    public void drawImageRotated(Sprite img, float x, float y, float w, float h,
                                 float originX, float originY, float rotationDeg,
                                 boolean flipX, boolean flipY) {
        if (img == null) return;
        useBatch();
        batch.setColor(1f, 1f, 1f, alpha);
        com.badlogic.gdx.graphics.g2d.TextureRegion r = img.region();
        if (flipX || flipY) {
            r = new com.badlogic.gdx.graphics.g2d.TextureRegion(r);
            r.flip(flipX, flipY);
        }
        // libGDX rotates CCW; y-down camera makes positive degrees clockwise on screen, matching AWT.
        batch.draw(r, x, y, originX, originY, w, h, 1f, 1f, rotationDeg);
    }

    /**
     * Draw a tile with Tiled flip/rotate flags (H/V/diagonal), replacing the old AffineTransform
     * path. Reproduces the 7 flip/rotate combinations by flipping the region and rotating in 90°
     * steps about the tile's top-left, matching buildFlipTransform's placement.
     */
    public void drawTileFlipped(Sprite img, int x, int y, int w, int h,
                                boolean fH, boolean fV, boolean fD) {
        if (img == null) return;
        useBatch();
        batch.setColor(1f, 1f, 1f, alpha);
        com.badlogic.gdx.graphics.g2d.TextureRegion r0 = img.region();
        // Work on a copy so we don't mutate the cached region's flip state.
        com.badlogic.gdx.graphics.g2d.TextureRegion r =
            new com.badlogic.gdx.graphics.g2d.TextureRegion(r0);
        // Diagonal flip = transpose; expressed as H-flip + 90° rotation in the combinations below.
        float rot = 0f;       // degrees, clockwise on the y-down screen
        boolean flipX = fH, flipY = fV;
        if (fD) {
            // D combined with H/V encodes the rotations (see buildFlipTransform mapping).
            if (fH && !fV)      { rot = 90f;  flipX = false; flipY = false; }   // 90° CW
            else if (!fH && fV) { rot = -90f; flipX = false; flipY = false; }   // 90° CCW
            else if (fH && fV)  { rot = 90f;  flipX = false; flipY = true;  }   // 270° CW
            else                { rot = 90f;  flipX = true;  flipY = false; }   // transpose
        }
        r.flip(flipX, flipY);
        if (rot == 0f) {
            batch.draw(r, x, y, w, h);
        } else {
            // Rotate about the tile center so the rotated tile still covers its cell.
            batch.draw(r, x, y, w / 2f, h / 2f, w, h, 1f, 1f, rot);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    // ── Text (SpriteBatch + BitmapFont) ───────────────────────────────────────
    /** drawString(s, x, y): x,y is the BASELINE origin, like Graphics2D. */
    public void drawString(String s, int x, int y) { drawString(s, (float) x, (float) y); }
    public void drawString(String s, float x, float y) {
        if (s == null || s.isEmpty()) return;
        useBatch();
        fonts.draw(batch, font, s, x, y, color, alpha);
    }

    // ── Shapes (ShapeRenderer) ────────────────────────────────────────────────
    public void fillRect(int x, int y, int w, int h) {
        useShape(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(gdxColor());
        shapes.rect(x, y, w, h);
    }
    public void drawRect(int x, int y, int w, int h) {
        // Outline using filled thin bars so line width (stroke) is honored consistently.
        float lw = Math.max(1f, stroke.width);
        fillRect(x, y, w, (int) lw);
        fillRect(x, y + h - (int) lw, w, (int) lw);
        fillRect(x, y, (int) lw, h);
        fillRect(x + w - (int) lw, y, (int) lw, h);
    }
    public void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH) {
        // Approximate: a filled rect plus the look reads identically at game scale; corner
        // rounding is cosmetic. (A true rounded rect can be added if visually needed.)
        useShape(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(gdxColor());
        int r = Math.max(0, Math.min(Math.min(arcW, arcH) / 2, Math.min(w, h) / 2));
        if (r <= 0) { shapes.rect(x, y, w, h); return; }
        shapes.rect(x + r, y, w - 2 * r, h);
        shapes.rect(x, y + r, w, h - 2 * r);
        shapes.circle(x + r, y + r, r);
        shapes.circle(x + w - r, y + r, r);
        shapes.circle(x + r, y + h - r, r);
        shapes.circle(x + w - r, y + h - r, r);
    }
    public void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH) {
        drawRect(x, y, w, h); // outline approximation
    }
    public void fillOval(int x, int y, int w, int h) {
        useShape(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(gdxColor());
        shapes.ellipse(x, y, w, h);
    }
    public void drawOval(int x, int y, int w, int h) {
        useShape(ShapeRenderer.ShapeType.Line);
        shapes.setColor(gdxColor());
        // ShapeRenderer ellipse outline in Line mode.
        shapes.ellipse(x, y, w, h);
    }
    public void drawLine(int x1, int y1, int x2, int y2) {
        float lw = Math.max(1f, stroke.width);
        if (lw <= 1f) {
            useShape(ShapeRenderer.ShapeType.Line);
            shapes.setColor(gdxColor());
            shapes.line(x1, y1, x2, y2);
        } else {
            useShape(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(gdxColor());
            shapes.rectLine(x1, y1, x2, y2, lw);
        }
    }

    /** Fill a rect with a linear gradient (mirrors GradientPaint + fillRect). */
    public void fillGradient(Gradient g, int x, int y, int w, int h) {
        useShape(ShapeRenderer.ShapeType.Filled);
        com.badlogic.gdx.graphics.Color c1 = g.c1.toGdx(); c1.a *= alpha;
        com.badlogic.gdx.graphics.Color c2 = g.c2.toGdx(); c2.a *= alpha;
        // Determine orientation from the gradient's two points: vertical if dy dominates.
        boolean vertical = Math.abs(g.y2 - g.y1) >= Math.abs(g.x2 - g.x1);
        if (vertical) {
            // c1 at top (y), c2 at bottom (y+h): bottom-left, bottom-right, top-right, top-left
            shapes.rect(x, y, w, h, c2, c2, c1, c1);
        } else {
            shapes.rect(x, y, w, h, c1, c2, c2, c1);
        }
    }

    // ── gfx.geom.Shape fill/draw (collision/debug overlays, polygons) ──────────
    public void fill(gfx.geom.Shape s) {
        if (s instanceof gfx.geom.Rect r) { fillRect(r.x, r.y, r.width, r.height); return; }
        if (s instanceof gfx.geom.Ellipse e) { fillOval((int) e.x, (int) e.y, (int) e.w, (int) e.h); return; }
        if (s instanceof gfx.geom.Polygon p) {
            useShape(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(gdxColor());
            // Triangle-fan fill (convex shapes used here).
            for (int i = 1; i + 1 < p.n; i++) {
                shapes.triangle((float) p.xs[0], (float) p.ys[0],
                                (float) p.xs[i], (float) p.ys[i],
                                (float) p.xs[i + 1], (float) p.ys[i + 1]);
            }
            return;
        }
        if (s instanceof gfx.geom.IntPolygon p) {
            useShape(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(gdxColor());
            for (int i = 1; i + 1 < p.npoints; i++) {
                shapes.triangle(p.xpoints[0], p.ypoints[0],
                                p.xpoints[i], p.ypoints[i],
                                p.xpoints[i + 1], p.ypoints[i + 1]);
            }
        }
    }
    public void draw(gfx.geom.Shape s) {
        if (s instanceof gfx.geom.Rect r) { drawRect(r.x, r.y, r.width, r.height); return; }
        if (s instanceof gfx.geom.Ellipse e) { drawOval((int) e.x, (int) e.y, (int) e.w, (int) e.h); return; }
        if (s instanceof gfx.geom.Polygon p) {
            useShape(ShapeRenderer.ShapeType.Line);
            shapes.setColor(gdxColor());
            for (int i = 0, j = p.n - 1; i < p.n; j = i++) {
                shapes.line((float) p.xs[j], (float) p.ys[j], (float) p.xs[i], (float) p.ys[i]);
            }
            return;
        }
        if (s instanceof gfx.geom.IntPolygon p) {
            useShape(ShapeRenderer.ShapeType.Line);
            shapes.setColor(gdxColor());
            for (int i = 0, j = p.npoints - 1; i < p.npoints; j = i++) {
                shapes.line(p.xpoints[j], p.ypoints[j], p.xpoints[i], p.ypoints[i]);
            }
        }
    }

    // ── Clipping (scissor) ────────────────────────────────────────────────────
    /** Rectangular clip in game coordinates; mirrors setClip(x,y,w,h). null clears. */
    public void setClip(Integer x, Integer y, Integer w, Integer h) {
        flush();
        if (x == null) { ScissorStack.popScissors(); return; }
        // Convert game-space (top-left, +translate) to GL scissor (bottom-left window pixels).
        Rectangle clip = new Rectangle(x + tx, y + ty, w, h);
        Rectangle scissor = new Rectangle();
        ScissorStack.calculateScissors(camera, batch.getTransformMatrix(), clip, scissor);
        ScissorStack.pushScissors(scissor);
    }
    public void clearClip() { flush(); if (ScissorStack.peekScissors() != null) ScissorStack.popScissors(); }

    /**
     * Bake a {@link RadialGradient} into a Sprite (Texture) once, the GPU-native replacement for
     * Java2D's RadialGradientPaint. Used for vignettes and light halos — draw the returned Sprite
     * each frame instead of rasterizing a gradient. Interpolates the stop colors per pixel by radius.
     */
    public static Sprite bakeRadialGradient(RadialGradient g, int w, int h) {
        com.badlogic.gdx.graphics.Pixmap pm =
            new com.badlogic.gdx.graphics.Pixmap(w, h, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        pm.setBlending(com.badlogic.gdx.graphics.Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - g.cx, dy = y - g.cy;
                float t = (float) Math.sqrt(dx * dx + dy * dy) / g.radius;
                if (t > 1f) t = 1f;
                Color c = sampleStops(g.fractions, g.colors, t);
                pm.setColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
                pm.drawPixel(x, y);
            }
        }
        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return new Sprite(tex);
    }

    private static Color sampleStops(float[] fractions, Color[] colors, float t) {
        if (t <= fractions[0]) return colors[0];
        for (int i = 1; i < fractions.length; i++) {
            if (t <= fractions[i]) {
                float span = fractions[i] - fractions[i - 1];
                float f = span <= 0 ? 0 : (t - fractions[i - 1]) / span;
                Color a = colors[i - 1], b = colors[i];
                return new Color(
                    (int) (a.getRed()   + (b.getRed()   - a.getRed())   * f),
                    (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                    (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * f),
                    (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * f));
            }
        }
        return colors[colors.length - 1];
    }

    public void dispose() {
        flush();
        batch.dispose();
        shapes.dispose();
    }

    public SpriteBatch batch() { return batch; }
    public ShapeRenderer shapes() { return shapes; }
    public OrthographicCamera camera() { return camera; }
}

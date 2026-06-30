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
        if (img == null) return;
        useBatch();
        batch.setColor(1f, 1f, 1f, alpha);
        // libGDX rotates CCW; y-down camera makes positive degrees clockwise on screen, matching AWT.
        batch.draw(img.region(), x, y, originX, originY, w, h, 1f, 1f, rotationDeg);
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

    public void dispose() {
        flush();
        batch.dispose();
        shapes.dispose();
    }

    public SpriteBatch batch() { return batch; }
    public ShapeRenderer shapes() { return shapes; }
    public OrthographicCamera camera() { return camera; }
}

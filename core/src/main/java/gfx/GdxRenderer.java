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
    // World zoom applied on top of the translate: scale-about-pivot (pivot in logical px, y-down).
    // Used by the dialogue camera to push in on the player+NPC. 1f = no zoom.
    private float zoom = 1f;
    private float pivotX = 0f, pivotY = 0f;
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
        zoom = 1f; pivotX = 0f; pivotY = 0f;
        // Feed the shader pipeline a monotonically-increasing time so the light flicker/breathing/noise
        // and rim animation advance smoothly. Only if the pipeline already exists (don't force-create it).
        if (shaderPipeline != null) {
            shaderTime += Gdx.graphics.getDeltaTime();
            shaderPipeline.setTime(shaderTime);
        }
        applyProjection();
    }
    private float shaderTime = 0f;
    public static int DEBUG_DUMP_STAGE = 0; // 1 = capture the next lighting frame stage-by-stage

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
        // When zoomed, a scale-about-pivot is composed inside the translate so the world
        // magnifies around the pivot point (screen center for the dialogue camera).
        Matrix4 t = new Matrix4().translate(tx, ty, 0);
        if (zoom != 1f) {
            t.translate(pivotX, pivotY, 0).scale(zoom, zoom, 1f).translate(-pivotX, -pivotY, 0);
        }
        batch.setTransformMatrix(t);
        shapes.setTransformMatrix(t);
    }

    /** Magnify the world by {@code z} about the given pivot (logical px, y-down). z=1 disables. */
    public void setWorldZoom(float z, float px, float py) {
        this.zoom = z; this.pivotX = px; this.pivotY = py; applyTransform();
    }
    /** Reset world zoom to 1x (no magnification). */
    public void clearWorldZoom() { setWorldZoom(1f, 0f, 0f); }

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

    /**
     * Draw a square texture as a 3x3 nine-slice into the (x,y,w,h) rect: the four corner cells
     * stay a fixed slice size, the four edges stretch along one axis, and the center stretches
     * both ways. Slice size is {@code tex.nativeWidth()/3} (UI.png is 96x96 → 32px slices), so any
     * square texture works. Respects the current {@link #alpha} (drawImage multiplies it), so
     * panel fade-ins keep working. The texture is expected to be already recolored (see
     * {@link #bakePaletteSwap}); this method is palette-agnostic.
     */
    public void drawNineSlice(Sprite tex, int x, int y, int w, int h) {
        if (tex == null) return;
        int s = tex.nativeWidth() / 3;
        if (s <= 0) return;
        // Clamp so corners never overlap on panels smaller than 2 slices; center may collapse to 0.
        int sw = Math.min(s, w / 2); // horizontal corner width
        int sh = Math.min(s, h / 2); // vertical corner height
        int midW = w - 2 * sw;       // stretched horizontal span
        int midH = h - 2 * sh;       // stretched vertical span

        // Native (top-left) source cells of the 3x3 grid.
        Sprite tl = tex.getSubimage(0, 0, s, s);
        Sprite tc = tex.getSubimage(s, 0, s, s);
        Sprite tr = tex.getSubimage(2 * s, 0, s, s);
        Sprite ml = tex.getSubimage(0, s, s, s);
        Sprite mc = tex.getSubimage(s, s, s, s);
        Sprite mr = tex.getSubimage(2 * s, s, s, s);
        Sprite bl = tex.getSubimage(0, 2 * s, s, s);
        Sprite bc = tex.getSubimage(s, 2 * s, s, s);
        Sprite br = tex.getSubimage(2 * s, 2 * s, s, s);

        int xL = x, xC = x + sw, xR = x + sw + midW;
        int yT = y, yC = y + sh, yB = y + sh + midH;

        // Corners (fixed).
        drawImage(tl, xL, yT, sw, sh);
        drawImage(tr, xR, yT, sw, sh);
        drawImage(bl, xL, yB, sw, sh);
        drawImage(br, xR, yB, sw, sh);
        // Edges (stretch on one axis) — only if there's span to fill.
        if (midW > 0) {
            drawImage(tc, xC, yT, midW, sh);
            drawImage(bc, xC, yB, midW, sh);
        }
        if (midH > 0) {
            drawImage(ml, xL, yC, sw, midH);
            drawImage(mr, xR, yC, sw, midH);
        }
        // Center (stretch both).
        if (midW > 0 && midH > 0) {
            drawImage(mc, xC, yC, midW, midH);
        }
    }

    /**
     * Build a recolored copy of {@code src} by exact-match palette swap: every pixel whose RGB
     * matches {@code markers[i]} becomes {@code targets[i]} (its original alpha is preserved);
     * every other pixel is copied through unchanged (including transparency). This is the
     * CPU/Pixmap-baked, shader-free way to give one authored UI.png per-window color themes
     * (main/shadow/highlight/...). Mirrors the one-time {@link #bakeRadialGradient} bake — the
     * caller should cache the result per color-set (it is NOT free; it decodes + re-uploads a
     * texture), never call it per frame.
     */
    public static Sprite bakePaletteSwap(Sprite src, Color[] markers, Color[] targets) {
        if (src == null) return null;
        int w = src.nativeWidth(), h = src.nativeHeight();
        com.badlogic.gdx.graphics.Pixmap out =
            new com.badlogic.gdx.graphics.Pixmap(w, h, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        out.setBlending(com.badlogic.gdx.graphics.Pixmap.Blending.None);
        out.setFilter(com.badlogic.gdx.graphics.Pixmap.Filter.NearestNeighbour);
        // Precompute marker RGBs (0xRRGGBB) once.
        int[] mRGB = new int[markers.length];
        for (int i = 0; i < markers.length; i++) {
            mRGB[i] = (markers[i].getRed() << 16) | (markers[i].getGreen() << 8) | markers[i].getBlue();
        }
        // Batch the decode: getRGB() otherwise re-decodes the whole PNG on EVERY call. One batch
        // decodes the source texture once and reuses it for all w*h pixel reads.
        Sprite.beginPixelBatch();
        try {
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    int argb = src.getRGB(px, py);         // 0xAARRGGBB (local coords)
                    int a   = (argb >>> 24) & 0xFF;
                    int rgb = argb & 0xFFFFFF;
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    for (int i = 0; i < mRGB.length; i++) {
                        if (rgb == mRGB[i]) { // exact RGB match → remap, keep original alpha
                            r = targets[i].getRed(); g = targets[i].getGreen(); b = targets[i].getBlue();
                            break;
                        }
                    }
                    // Pixmap wants RGBA8888 packed as 0xRRGGBBAA.
                    out.drawPixel(px, py, (r << 24) | (g << 16) | (b << 8) | a);
                }
            }
        } finally {
            Sprite.endPixelBatch();
        }
        Texture tex = new Texture(out);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        out.dispose();
        return new Sprite(tex);
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

    // ── Offscreen light-mask FrameBuffer ──────────────────────────────────────
    // The lighting model composites a darkness LAYER over the finished scene, then carves soft holes
    // in that layer where lights reach — so the scene shows through at its NATURAL brightness (no
    // additive white washout). DST_OUT hole-punching needs a real alpha channel, which the default
    // (opaque) framebuffer doesn't provide, so we render the mask into this offscreen RGBA target and
    // then blit it over the scene with normal alpha blending. Sized to the LOGICAL resolution and
    // recreated on resize. See Lightning.draw.
    private com.badlogic.gdx.graphics.glutils.FrameBuffer lightFbo;
    private int lightFboW, lightFboH;
    /** True when the mask in lightFbo was written by the GLSL light shader, whose output is
     *  PREMULTIPLIED (rgb = night*alpha + additive warm glow) and must composite with
     *  (GL_ONE, ONE_MINUS_SRC_ALPHA). The legacy baked mask is straight-alpha (normal blend). */
    private boolean lightMaskPremultiplied = false;
    private boolean lightMaskActive = false;

    /** Bind the light-mask FBO and clear it fully transparent. Draw the darkness fill + light holes,
     *  then call {@link #endLightMask()} and {@link #drawLightMask()} to composite it. */
    public void beginLightMask() {
        if (lightMaskActive) return;
        flush();
        if (lightFbo == null || lightFboW != screenW || lightFboH != screenH) {
            if (lightFbo != null) lightFbo.dispose();
            lightFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, Math.max(1, screenW), Math.max(1, screenH), false);
            lightFboW = screenW; lightFboH = screenH;
        }
        lightFbo.begin();
        Gdx.gl.glViewport(0, 0, lightFboW, lightFboH);
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
        // Same y-down ortho as the scene camera (top=0), so game coords land in the FBO in the SAME
        // orientation as every other render target and drawLightMask can blit it with NO flip.
        // (setToOrtho2D is y-UP — it stored the mask vertically inverted and needed a compensating
        // V-flip at composite time, which is exactly the mirror family of bugs behind "dancing lights".)
        Matrix4 m = new Matrix4().setToOrtho(0, screenW, screenH, 0, 0, 1);
        batch.setProjectionMatrix(m);
        shapes.setProjectionMatrix(m);
        Matrix4 id = new Matrix4();
        batch.setTransformMatrix(id);
        shapes.setTransformMatrix(id);
        lightMaskActive = true;
    }

    /** Unbind the light-mask FBO and restore the scene's camera projection + viewport. */
    public void endLightMask() {
        if (!lightMaskActive) return;
        flush();
        lightFbo.end();
        rebindActiveTarget();
        applyProjection();
        lightMaskActive = false;
        lightMaskPremultiplied = false; // baked path writes a straight-alpha mask
    }

    /**
     * After a nested offscreen FBO (light mask, occluder mask, shader light mask) calls end(), libGDX
     * unbinds to the DEFAULT framebuffer — NOT to any scene-capture FBO that was active before the nest.
     * If we're mid scene-capture (for bloom), everything drawn afterward (including the light-mask
     * composite) must go back into sceneFbo, or it lands on the screen and the bloom blit then overwrites
     * it — which silently ate the darkness overlay. This re-binds sceneFbo when capture is active, else
     * restores the world viewport.
     */
    private void rebindActiveTarget() {
        if (sceneCaptureActive && sceneFbo != null) {
            sceneFbo.begin();
            Gdx.gl.glViewport(0, 0, sceneFboW, sceneFboH);
        } else {
            Gdx.gl.glViewport(worldVpX, worldVpY, worldVpW, worldVpH);
        }
    }

    // ── Occluder mask FBO (silhouette shadow casting) ─────────────────────────
    // Stage 2: entity/object silhouettes are drawn (solid black on transparent) into this offscreen
    // RGBA target. The light shader samples its ALPHA as "is this pixel solid?" and ray-marches from
    // each light to each fragment, dropping fragments behind a silhouette into shadow. Kept separate
    // from lightFbo so both can be bound as textures in the same shader pass.
    private com.badlogic.gdx.graphics.glutils.FrameBuffer occluderFbo;
    private int occluderFboW, occluderFboH;
    private boolean occluderMaskActive = false;

    /** Bind the occluder FBO and clear it transparent; draw caster silhouettes, then endOccluderMask(). */
    public void beginOccluderMask() {
        if (occluderMaskActive) return;
        flush();
        if (occluderFbo == null || occluderFboW != screenW || occluderFboH != screenH) {
            if (occluderFbo != null) occluderFbo.dispose();
            occluderFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, Math.max(1, screenW), Math.max(1, screenH), false);
            // Linear filtering: the shadow ray-march and rim pass sample this mask between texel
            // centers, so smooth edges make shadows round and soft instead of blocky tile squares.
            occluderFbo.getColorBufferTexture().setFilter(
                Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            occluderFboW = screenW; occluderFboH = screenH;
        }
        occluderFbo.begin();
        Gdx.gl.glViewport(0, 0, occluderFboW, occluderFboH);
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
        // Same y-down ortho as the scene camera. THIS orientation is what light.frag's visibility()
        // and rim.frag assume when sampling u_occluders (uv.y = 1 - screenY/res): game-top must sit at
        // texture v=1, exactly like sceneFbo and the shader-written lightFbo. The old setToOrtho2D
        // (y-UP) stored the silhouettes vertically MIRRORED, so every ray-marched shadow was computed
        // from an upside-down world — shadows detached from their casters and slid vertically while
        // walking (the same mirror signature the light pools had before their blit-flip fix).
        Matrix4 m = new Matrix4().setToOrtho(0, screenW, screenH, 0, 0, 1);
        batch.setProjectionMatrix(m);
        shapes.setProjectionMatrix(m);
        batch.setTransformMatrix(new Matrix4());
        shapes.setTransformMatrix(new Matrix4());
        // Silhouettes must be drawn fully opaque so the shader reads a crisp occluder alpha. Reset any
        // stale alpha left by a prior draw (drawImageTinted multiplies its arg by this field), else
        // faint silhouettes would cast faint/no shadows.
        alpha = 1f;
        occluderMaskActive = true;
    }

    /** Unbind the occluder FBO and restore the scene projection/viewport. */
    public void endOccluderMask() {
        if (!occluderMaskActive) return;
        flush();
        occluderFbo.end();
        rebindActiveTarget();
        applyProjection();
        occluderMaskActive = false;
    }

    /** DEBUG: dump the occluder FBO to a PNG so we can see where silhouettes actually land. */
    public void debugDumpOccluder(String path) {
        if (occluderFbo == null) return;
        occluderFbo.begin();
        com.badlogic.gdx.graphics.Pixmap pm = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(
            0, 0, occluderFboW, occluderFboH);
        occluderFbo.end();
        rebindActiveTarget();
        // Make alpha visible as white-on-black so the silhouette is obvious in the PNG.
        com.badlogic.gdx.graphics.Pixmap out = new com.badlogic.gdx.graphics.Pixmap(
            occluderFboW, occluderFboH, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        for (int y = 0; y < occluderFboH; y++)
            for (int x = 0; x < occluderFboW; x++) {
                int a = pm.getPixel(x, occluderFboH - 1 - y) & 0xFF;
                out.drawPixel(x, y, (a << 24) | (a << 16) | (a << 8) | 0xFF);
            }
        com.badlogic.gdx.graphics.PixmapIO.writePNG(com.badlogic.gdx.Gdx.files.local(path), out);
        pm.dispose(); out.dispose();
    }

    /** DEBUG: dump the scene-capture FBO (color) to a PNG to inspect scene+mask alignment. */
    public void debugDumpScene(String path) {
        if (sceneFbo == null) return;
        sceneFbo.begin();
        com.badlogic.gdx.graphics.Pixmap pm = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(
            0, 0, sceneFboW, sceneFboH);
        sceneFbo.end();
        rebindActiveTarget();
        com.badlogic.gdx.graphics.Pixmap out = new com.badlogic.gdx.graphics.Pixmap(
            sceneFboW, sceneFboH, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        for (int y = 0; y < sceneFboH; y++)
            for (int x = 0; x < sceneFboW; x++)
                out.drawPixel(x, y, pm.getPixel(x, sceneFboH - 1 - y));
        com.badlogic.gdx.graphics.PixmapIO.writePNG(com.badlogic.gdx.Gdx.files.local(path), out);
        pm.dispose(); out.dispose();
    }

    /** The occluder mask texture (alpha = solid), for binding into the light shader. Null until first built. */
    public com.badlogic.gdx.graphics.Texture occluderTexture() {
        return occluderFbo == null ? null : occluderFbo.getColorBufferTexture();
    }

    /** DEBUG: dump the light mask FBO (darkness alpha shown as brightness) to a PNG. */
    public void debugDumpLightMask(String path) {
        if (lightFbo == null) return;
        lightFbo.begin();
        com.badlogic.gdx.graphics.Pixmap pm = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(
            0, 0, lightFboW, lightFboH);
        lightFbo.end();
        rebindActiveTarget();
        com.badlogic.gdx.graphics.Pixmap out = new com.badlogic.gdx.graphics.Pixmap(
            lightFboW, lightFboH, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        for (int y = 0; y < lightFboH; y++)
            for (int x = 0; x < lightFboW; x++) {
                int px = pm.getPixel(x, lightFboH - 1 - y);
                int a = px & 0xFF;                  // darkness alpha
                int inv = 255 - a;                  // lit = bright
                out.drawPixel(x, y, (inv << 24) | (inv << 16) | (inv << 8) | 0xFF);
            }
        com.badlogic.gdx.graphics.PixmapIO.writePNG(com.badlogic.gdx.Gdx.files.local(path), out);
        pm.dispose(); out.dispose();
    }

    // ── GLSL light pipeline (smooth per-pixel lighting + shadows + bloom) ─────
    // Lazily created on first use so the GL context exists. If the shaders don't compile on this GPU,
    // pipeline.isAvailable() is false and callers fall back to the baked-texture light path. The
    // pipeline is queried by Lightning via shaderPipeline().
    private gfx.shader.ShaderPipeline shaderPipeline;
    private boolean shaderPipelineTried = false;

    /** The GLSL pipeline (compiled once, lazily). May report isAvailable()==false on old GPUs. */
    public gfx.shader.ShaderPipeline shaderPipeline() {
        if (!shaderPipelineTried) {
            shaderPipelineTried = true;
            try { shaderPipeline = new gfx.shader.ShaderPipeline(); }
            catch (Throwable t) { shaderPipeline = null; }
        }
        return shaderPipeline;
    }

    /**
     * Render the smooth GLSL light mask straight into the light FBO (bound here), replacing the
     * fillRect-darkness + baked-falloff-hole sequence with a single per-pixel shader pass. After this
     * returns the FBO holds the finished darkness mask; call {@link #drawLightMask()} to composite it.
     * Coordinates are SCREEN pixels (top-left origin), matching the game's light screen positions.
     * No-op (returns false) if the pipeline is unavailable — caller should then use the baked path.
     */
    public boolean renderShaderLightMask(Color night, float darkness, int lightCount,
                                         float[] lx, float[] ly, float[] lwx, float[] lwy, float[] lrad,
                                         float[] lr, float[] lg, float[] lb, float[] lint,
                                         boolean shadows, boolean cheap) {
        gfx.shader.ShaderPipeline pipe = shaderPipeline();
        if (pipe == null || !pipe.isAvailable()) return false;
        flush();
        if (lightFbo == null || lightFboW != screenW || lightFboH != screenH) {
            if (lightFbo != null) lightFbo.dispose();
            lightFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, Math.max(1, screenW), Math.max(1, screenH), false);
            lightFboW = screenW; lightFboH = screenH;
        }
        lightFbo.begin();
        Gdx.gl.glViewport(0, 0, lightFboW, lightFboH);
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
        pipe.renderLightMask(screenW, screenH,
            night.getRed() / 255f, night.getGreen() / 255f, night.getBlue() / 255f, darkness,
            lightCount, lx, ly, lwx, lwy, lrad, lr, lg, lb, lint,
            shadows ? occluderTexture() : null, shadows, cheap);
        lightFbo.end();
        rebindActiveTarget();
        applyProjection();
        // The GLSL mask is PREMULTIPLIED (rgb carries night*alpha + additive warm glow);
        // drawLightMask must composite it with (ONE, ONE_MINUS_SRC_ALPHA).
        lightMaskPremultiplied = true;
        return true;
    }

    /** Composite the finished light mask over the scene with normal alpha blending. The darkness (where
     *  no light punched a hole) darkens the scene; lit areas are transparent so the scene shows through
     *  at its true brightness. Call after endLightMask(), in the default framebuffer. */
    public void drawLightMask() {
        if (lightFbo == null) return;
        setBlendMode(BLEND_NORMAL);
        useBatch();
        batch.setColor(1f, 1f, 1f, 1f);
        // The light mask is computed in SCREEN pixels (light positions are already screen-space). It must
        // therefore be blitted full-screen with NO world transform. But when this runs mid scene-capture
        // (HIGH), the batch still carries the world translate — specifically the camera-shake offset
        // (RenderPipeline applies g2.translate(shakeX,shakeY)) and any dialogue pan/zoom. Left in place,
        // the full-screen mask quad gets shifted/scaled by that transform while the world already baked
        // the shake in, so the light pool slides off the scene by the shake amount EVERY frame the camera
        // shakes — i.e. it "dances only when the camera is moving". Neutralize the transform for the blit,
        // then restore it so subsequent world draws are unaffected.
        Matrix4 savedTransform = new Matrix4(batch.getTransformMatrix());
        batch.setTransformMatrix(new Matrix4());
        // Diagnostics for the "lights dance" hunt: record what the world transform WAS at blit time
        // (should be neutralized here — non-zero means shake/dialogue-pan was live this frame) and
        // which composite branch runs.
        gfx.shader.LightDebug.maskBlitTx = savedTransform.val[Matrix4.M03];
        gfx.shader.LightDebug.maskBlitTy = savedTransform.val[Matrix4.M13];
        gfx.shader.LightDebug.maskBlitDuringCapture = sceneCaptureActive;
        gfx.shader.LightDebug.maskPremultiplied = lightMaskPremultiplied;
        // FBO color texture; drawn full-screen, NEVER V-flipped. PROOF (OffCenterLightTest, a probe
        // light 250px ABOVE the player): with the old "flip when not capturing" logic the probe's pool
        // rendered 250px BELOW the player on the non-capture path — the whole mask was vertically
        // MIRRORED about the screen center. The mirror was invisible in every previous verification
        // because those all watched the PLAYER'S OWN pool, which sits at the screen center — and the
        // mirror maps the center to itself. In play it made every off-center light's pool sit in the
        // wrong place and GLIDE vertically in sync with the walking player (camera scroll mirrored =
        // "the lights follow me / dance"), while standing still it just looked like misplaced shadows.
        // The y-down batch projection already writes/reads lightFbo and the current target in the same
        // orientation on BOTH paths (capture and direct), so no flip is ever needed here.
        com.badlogic.gdx.graphics.g2d.TextureRegion region =
            new com.badlogic.gdx.graphics.g2d.TextureRegion(lightFbo.getColorBufferTexture());
        // The GLSL mask is premultiplied (rgb already carries alpha + an ADDITIVE warm glow term), so it
        // must blend (ONE, ONE_MINUS_SRC_ALPHA): the alpha darkens the scene, the glow adds warm light.
        // The legacy baked mask is straight-alpha and keeps the batch's normal blend.
        if (lightMaskPremultiplied) {
            batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_ONE,
                                   com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
        batch.draw(region, 0, 0, screenW, screenH);
        // Flush so the mask quad is drawn with the identity transform, THEN restore the world transform
        // for subsequent draws (the batch applies its transform matrix at flush time, not per-draw).
        batch.flush();
        if (lightMaskPremultiplied) {
            batch.setBlendFunction(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA,
                                   com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
        batch.setTransformMatrix(savedTransform);
    }

    // ── Scene capture FBO (for bloom post-processing) ─────────────────────────
    // The world+lighting are rendered into this offscreen target so bloom can read the finished scene
    // as a texture, blit it back to screen, then add the glow on top. HUD is drawn AFTER (on screen)
    // so it never blooms. Recreated on resize.
    private com.badlogic.gdx.graphics.glutils.FrameBuffer sceneFbo;
    private int sceneFboW, sceneFboH;
    private boolean sceneCaptureActive = false;
    // Strength of the final color grade applied to the captured scene (0 = passthrough). Tunable.
    private float sceneGradeWarmth = 0.5f;
    public void setGradeWarmth(float w) { sceneGradeWarmth = w < 0 ? 0 : (w > 1 ? 1 : w); }
    // Per-sprite rim-light strength (0 = off). Added between grade and bloom.
    private float rimStrength = 1.4f;
    public void setRimStrength(float s) { rimStrength = s < 0 ? 0 : s; }

    /** Begin capturing the world into the scene FBO. Pair with {@link #endSceneCaptureAndBloom}. */
    public void beginSceneCapture() {
        if (sceneCaptureActive) return;
        flush();
        if (sceneFbo == null || sceneFboW != screenW || sceneFboH != screenH) {
            if (sceneFbo != null) sceneFbo.dispose();
            sceneFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888, Math.max(1, screenW), Math.max(1, screenH), false);
            sceneFbo.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            sceneFboW = screenW; sceneFboH = screenH;
        }
        sceneFbo.begin();
        Gdx.gl.glViewport(0, 0, sceneFboW, sceneFboH);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
        applyProjection();
        sceneCaptureActive = true;
    }

    /**
     * End scene capture, blit the captured scene back to the real screen, then additively add bloom.
     * If bloom is unavailable this still blits the scene (so capture is transparent to the look).
     *
     * @param threshold bloom luminance threshold (0..1)
     * @param intensity bloom strength (0..~2); 0 skips the glow add
     */
    public void endSceneCaptureAndBloom(float threshold, float intensity) {
        if (!sceneCaptureActive) return;
        flush();
        sceneFbo.end();
        Gdx.gl.glViewport(worldVpX, worldVpY, worldVpW, worldVpH);
        applyProjection();
        sceneCaptureActive = false;

        setBlendMode(BLEND_NORMAL);
        gfx.shader.ShaderPipeline pipe = shaderPipeline();
        boolean post = pipe != null && pipe.isBloomAvailable();

        if (post) {
            // Blit the captured scene through the color-grade shader (warm split-tone + contrast) so the
            // whole frame reads as one graded image, then add per-sprite rim light, then bloom on top (so
            // rims and lights bloom together).
            Gdx.gl.glViewport(worldVpX, worldVpY, worldVpW, worldVpH);
            pipe.renderGradedBlit(sceneFbo.getColorBufferTexture(), sceneGradeWarmth);
            if (rimStrength > 0f && occluderTexture() != null && !gfx.shader.LightDebug.noRim) {
                pipe.renderRim(sceneFbo.getColorBufferTexture(), occluderTexture(), rimStrength);
            }
            if (intensity > 0f && !gfx.shader.LightDebug.noBloom) {
                pipe.renderBloom(sceneFbo.getColorBufferTexture(), screenW, screenH, threshold, intensity);
            }
            applyProjection(); // shaders left their own GL state; rebind our camera for later draws
        } else {
            // No post shaders: plain scene blit through the batch (grade/bloom unavailable on this GPU).
            useBatch();
            batch.setColor(1f, 1f, 1f, 1f);
            com.badlogic.gdx.graphics.g2d.TextureRegion region =
                new com.badlogic.gdx.graphics.g2d.TextureRegion(sceneFbo.getColorBufferTexture());
            region.flip(false, true); // FBO is bottom-up vs our y-down camera
            batch.draw(region, 0, 0, screenW, screenH);
            flush();
        }
    }

    /** True if the GLSL bloom shaders are ready on this GPU. */
    public boolean bloomAvailable() {
        gfx.shader.ShaderPipeline pipe = shaderPipeline();
        return pipe != null && pipe.isBloomAvailable();
    }

    // ── Device-space overlay (unmagnified HUD/debug) ──────────────────────────
    // Everything above draws in LOGICAL pixels, which the camera magnifies by pixelScale (crisp for
    // pixel art, but it Nearest-upscales small non-pixel text into a jaggy mess in fullscreen). These
    // helpers temporarily swap to a 1:1 DEVICE-pixel projection (top-left origin, y-down) so an overlay
    // can be drawn at its true on-screen size — resolution-independent and never magnified. Coordinates
    // passed while in device space are physical window pixels. Always pair with endDeviceSpace().
    private boolean deviceSpace = false;
    public void beginDeviceSpace() {
        if (deviceSpace) return;
        flush();
        int dw = Gdx.graphics.getBackBufferWidth();
        int dh = Gdx.graphics.getBackBufferHeight();
        // Full-window viewport (world drawing uses an integer-scaled sub-viewport; the overlay wants all
        // of it) and a y-down ortho so (0,0) is top-left like the rest of the game's coordinates.
        Gdx.gl.glViewport(0, 0, dw, dh);
        Matrix4 m = new Matrix4().setToOrtho2D(0, dh, dw, -dh);
        batch.setProjectionMatrix(m);
        shapes.setProjectionMatrix(m);
        Matrix4 id = new Matrix4();
        batch.setTransformMatrix(id);
        shapes.setTransformMatrix(id);
        deviceSpace = true;
    }
    public void endDeviceSpace() {
        if (!deviceSpace) return;
        flush();
        deviceSpace = false;
        // Restore the world camera's viewport + projection for subsequent logical-space draws.
        camera.update();
        Gdx.gl.glViewport(worldVpX, worldVpY, worldVpW, worldVpH);
        applyProjection();
    }
    // The integer-scaled world viewport, mirrored from MichiGame.syncCamera so we can restore it after
    // an overlay temporarily takes the full window. Set via setWorldViewport each resolution change.
    private int worldVpX, worldVpY, worldVpW, worldVpH;
    public void setWorldViewport(int x, int y, int w, int h) {
        worldVpX = x; worldVpY = y; worldVpW = w; worldVpH = h;
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
            float[] verts = new float[p.n * 2];
            for (int i = 0; i < p.n; i++) { verts[i * 2] = (float) p.xs[i]; verts[i * 2 + 1] = (float) p.ys[i]; }
            fillPolygon(verts);
            return;
        }
        if (s instanceof gfx.geom.IntPolygon p) {
            float[] verts = new float[p.npoints * 2];
            for (int i = 0; i < p.npoints; i++) { verts[i * 2] = p.xpoints[i]; verts[i * 2 + 1] = p.ypoints[i]; }
            fillPolygon(verts);
        }
    }

    // Reused across frames so triangulating every collision polygon doesn't churn garbage.
    private final com.badlogic.gdx.math.EarClippingTriangulator triangulator =
        new com.badlogic.gdx.math.EarClippingTriangulator();

    /**
     * Fill an arbitrary SIMPLE polygon (convex OR concave) given as flat [x0,y0,x1,y1,...] vertices.
     * A naive triangle fan from vertex 0 only fills convex polygons correctly — Tiled collision
     * polygons and stroked polylines are frequently concave, and fanning them produced the glitched
     * overlapping-triangle mess seen in the hitbox debug overlay. Ear-clipping triangulates properly.
     */
    private void fillPolygon(float[] verts) {
        if (verts.length < 6) return; // need at least 3 points
        useShape(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(gdxColor());
        com.badlogic.gdx.utils.ShortArray tris = triangulator.computeTriangles(verts);
        for (int i = 0; i < tris.size; i += 3) {
            int a = tris.get(i) * 2, b = tris.get(i + 1) * 2, c = tris.get(i + 2) * 2;
            shapes.triangle(verts[a], verts[a + 1], verts[b], verts[b + 1], verts[c], verts[c + 1]);
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
        if (lightFbo != null) lightFbo.dispose();
        if (occluderFbo != null) occluderFbo.dispose();
        if (sceneFbo != null) sceneFbo.dispose();
        if (shaderPipeline != null) shaderPipeline.dispose();
    }

    public SpriteBatch batch() { return batch; }
    public ShapeRenderer shapes() { return shapes; }
    public OrthographicCamera camera() { return camera; }
}

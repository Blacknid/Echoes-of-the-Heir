package gfx;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * GPU image handle that replaces {@code java.awt.image.BufferedImage} throughout the game.
 * Wraps a libGDX {@link TextureRegion} so that {@code getSubimage} (used for sprite-sheet and
 * tileset slicing) is a zero-copy sub-region, and {@code getWidth/getHeight} report region size
 * in pixels, matching the BufferedImage API the code relied on.
 *
 * <p>Drawing goes through {@code GdxRenderer.drawImage(Sprite, ...)}. Per-pixel work that used
 * {@code createGraphics()} (hit-flash tint, light masks, minimap bake) is reimplemented with
 * FrameBuffer/Pixmap in later stages; {@link #getPixmapARGB()} exposes pixels when truly needed.
 */
public class Sprite {

    // V-flipped region. SpriteBatch emits texture V-coords for a yUp world; under this project's
    // yDown OrthographicCamera an UNflipped region renders upside-down, so we flip V once here.
    // Slicing (getSubimage/getRGB/croppedBottomAligned) uses the native top-left rx/ry/rw/rh below,
    // so it is unaffected by the display flip.
    private final TextureRegion region;

    // Native top-left pixel rect of this sprite within its texture, used for slicing.
    private final int rx, ry, rw, rh;

    // Logical size mirrors java.awt's "pre-scaled" BufferedImage dimensions: callers that asked
    // ResourceCache to scale to (w,h) read getWidth()/getHeight() expecting those numbers, and
    // drawImage(sprite,x,y) should draw at that size. On the GPU we keep the native texture and
    // just report/draw the logical size (nearest-neighbor scaled), so no bitmap is pre-scaled.
    private final int logicalW, logicalH;

    public Sprite(Texture texture) {
        this(texture, 0, 0, texture.getWidth(), texture.getHeight(),
             texture.getWidth(), texture.getHeight());
    }

    /** Wrap an existing region (native, unflipped). Its region rect becomes this sprite's native rect. */
    public Sprite(TextureRegion r) {
        this(r.getTexture(), r.getRegionX(), r.getRegionY(), r.getRegionWidth(), r.getRegionHeight(),
             r.getRegionWidth(), r.getRegionHeight());
    }

    /**
     * Headless sprite: dimensions only, no GPU texture.
     *
     * <p>The authoritative game server runs the same simulation classes as the client but has no
 * GL context, so it cannot create a {@link Texture}. It doesn't need one, the simulation never
     * reads pixels; the only thing it asks of a sprite is its size, because sprite-sheet slicing
     * (Entity.loadSheetVariable and friends) divides sheet width/height to work out frame counts and
     * cell sizes. Those numbers come from {@code logicalW/logicalH}, which this constructor sets
     * from the PNG header alone (see ResourceCache.loadImage in headless mode).
     *
     * <p>Every GPU-bound method ({@link #region()}, {@link #texture()}, {@link #getRGB},
 * {@link #croppedBottomAligned}) is null-safe and inert on such a sprite, the server never
     * calls them, and if a future change does, it fails visibly rather than corrupting state.
     */
    public static Sprite headless(int width, int height) {
        return new Sprite(null, 0, 0, width, height, width, height);
    }

    /** True if this sprite carries no GPU texture (server-side, see {@link #headless}). */
    public boolean isHeadless() { return region == null; }

    private Sprite(Texture tex, int rx, int ry, int rw, int rh, int logicalW, int logicalH) {
        this.rx = rx; this.ry = ry; this.rw = rw; this.rh = rh;
        this.logicalW = logicalW; this.logicalH = logicalH;
        if (tex == null) {
            // Headless: no texture, hence no region. Slicing still works, it only needs rx/ry/rw/rh
            // and the logical size, all of which are set above.
            this.region = null;
            return;
        }
        // V-flip for display: with the yDown OrthographicCamera, SpriteBatch would otherwise render
        // this region upside-down. rx/ry/rw/rh stay native (top-left) so CPU slicing is unaffected.
        this.region = new TextureRegion(tex, rx, ry, rw, rh);
        this.region.flip(false, true);
    }

    /** Returns a view of this sprite that reports/draws at the given logical size. */
    public Sprite withLogicalSize(int w, int h) {
        if (w == logicalW && h == logicalH) return this;
        return new Sprite(texture(), rx, ry, rw, rh, w, h);
    }

    public TextureRegion region() { return region; }
    public Texture texture()      { return region == null ? null : region.getTexture(); }

    public int getWidth()  { return logicalW; }
    public int getHeight() { return logicalH; }
    public int nativeWidth()  { return rw; }
    public int nativeHeight() { return rh; }

    /**
     * Zero-copy sub-region in native top-left pixel coordinates, mirroring
     * {@code BufferedImage.getSubimage(x,y,w,h)} for sprite-sheet frame slicing and tileset tile
     * extraction. Coordinates are relative to this sprite's top-left.
     */
    public Sprite getSubimage(int x, int y, int w, int h) {
        // texture() is null when headless, the private constructor handles that, so sheet slicing
        // keeps producing correctly-sized frames on the server with no GPU behind them.
        return new Sprite(texture(), rx + x, ry + y, w, h, w, h);
    }

    /** True if this sprite is horizontally flipped (used by some animation code). */
    public boolean isFlipX() { return region != null && region.isFlipX(); }
    public boolean isFlipY() { return region != null && region.isFlipY(); }

    // ── Decoded-pixmap batch cache ──
    // getRGB() / croppedBottomAligned() need CPU pixels, obtained via TextureData.consumePixmap(),
    // which DECODES THE WHOLE PNG each call. NPC sprite-sheet trimming calls getRGB() once per pixel
    // (tens of thousands of times) plus croppedBottomAligned() per frame, without batching that is
    // tens of thousands of full-PNG decodes and was making class-select take ~12 seconds.
    //
    // When a pixel batch is open, the decoded Pixmap for each Texture is cached and reused across
    // all calls, then disposed together in endPixelBatch(). Callers doing heavy per-pixel work should
    // wrap it: Sprite.beginPixelBatch(); ...scan...; Sprite.endPixelBatch();
    private static final java.util.IdentityHashMap<Texture, com.badlogic.gdx.graphics.Pixmap>
        pixmapBatch = new java.util.IdentityHashMap<>();
    private static boolean pixelBatchOpen = false;

    /** Begin a batch: decoded Pixmaps are cached+reused until {@link #endPixelBatch()}. */
    public static void beginPixelBatch() { pixelBatchOpen = true; }

    /** End the batch and dispose every cached decoded Pixmap (frees native memory). */
    public static void endPixelBatch() {
        for (com.badlogic.gdx.graphics.Pixmap pm : pixmapBatch.values()) {
            if (pm != null) pm.dispose();
        }
        pixmapBatch.clear();
        pixelBatchOpen = false;
    }

    /**
     * Obtain the decoded Pixmap for this sprite's texture. During a pixel batch it is decoded once
     * and cached; otherwise the caller owns it (see {@code ownsResult}) and must dispose it.
     */
    private com.badlogic.gdx.graphics.Pixmap acquirePixmap(boolean[] ownsResult) {
        Texture tex = texture();
        if (tex == null) return null;  // headless: no pixels to decode (see Sprite.headless)
        if (pixelBatchOpen) {
            com.badlogic.gdx.graphics.Pixmap cached = pixmapBatch.get(tex);
            if (cached == null) {
                com.badlogic.gdx.graphics.TextureData data = tex.getTextureData();
                if (!data.isPrepared()) data.prepare();
                cached = data.consumePixmap(); // batch owns it; disposed in endPixelBatch()
                pixmapBatch.put(tex, cached);
            }
            ownsResult[0] = false;
            return cached;
        }
        com.badlogic.gdx.graphics.TextureData data = tex.getTextureData();
        if (!data.isPrepared()) data.prepare();
        com.badlogic.gdx.graphics.Pixmap pm = data.consumePixmap();
        ownsResult[0] = (pm != null) && data.disposePixmap();
        return pm;
    }

    /**
     * Read a single pixel as packed 0xAARRGGBB (like BufferedImage.getRGB), in this region's
     * local coordinates. CPU-side: decodes the texture's Pixmap, so use only at load/bake time
     * (e.g. minimap terrain sampling), never per frame. Wrap heavy per-pixel scans in
     * beginPixelBatch()/endPixelBatch() to avoid re-decoding. Returns 0 if pixels aren't available.
     */
    public int getRGB(int x, int y) {
        boolean[] owns = {false};
        com.badlogic.gdx.graphics.Pixmap pm = acquirePixmap(owns);
        if (pm == null) return 0;
        try {
            int px = rx + x; // native top-left pixel coords (region field is V-flipped)
            int py = ry + y;
            int rgba8888 = pm.getPixel(px, py); // 0xRRGGBBAA
            // Repack RGBA8888 → ARGB to match java.awt.BufferedImage.getRGB.
            int r = (rgba8888 >>> 24) & 0xFF;
            int g = (rgba8888 >>> 16) & 0xFF;
            int b = (rgba8888 >>> 8) & 0xFF;
            int a = rgba8888 & 0xFF;
            return (a << 24) | (r << 16) | (g << 8) | b;
        } finally {
            if (owns[0]) pm.dispose();
        }
    }

    /**
     * Crop a sub-rectangle of this sprite, scale it to (dw,dh), and place it bottom-aligned and
 * horizontally centered into a transparent cell of (cellW,cellH), the GPU-native replacement
     * for the "new BufferedImage + createGraphics + drawImage(crop, ox, oy, dw, dh)" pattern used
     * by NPC sprite-sheet trimming. Runs at load time via a Pixmap composite.
     */
    public Sprite croppedBottomAligned(int srcX, int srcY, int srcW, int srcH,
                                       int dw, int dh, int cellW, int cellH) {
        return croppedIntoCell(srcX, srcY, srcW, srcH, dw, dh, cellW, cellH, cellH - dh);
    }

    /**
     * Same composite as {@link #croppedBottomAligned}, but centers the scaled crop both
 * horizontally and vertically in the transparent cell instead of anchoring to the bottom
     * for icons that should shrink-and-center (e.g. a smaller inventory sprite) rather than sit on
     * a shared baseline (e.g. sprite-sheet frame trimming).
     */
    public Sprite croppedCentered(int srcX, int srcY, int srcW, int srcH,
                                   int dw, int dh, int cellW, int cellH) {
        return croppedIntoCell(srcX, srcY, srcW, srcH, dw, dh, cellW, cellH, (cellH - dh) / 2);
    }

    private Sprite croppedIntoCell(int srcX, int srcY, int srcW, int srcH,
                                    int dw, int dh, int cellW, int cellH, int oy) {
        boolean[] owns = {false};
        com.badlogic.gdx.graphics.Pixmap src = acquirePixmap(owns);
        // Headless (server): there are no pixels to composite and nothing to draw them with. Hand
        // back a correctly-sized sprite so callers' frame arrays still line up.
        if (src == null) return Sprite.headless(cellW, cellH);
        com.badlogic.gdx.graphics.Pixmap cell =
            new com.badlogic.gdx.graphics.Pixmap(cellW, cellH, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        cell.setBlending(com.badlogic.gdx.graphics.Pixmap.Blending.None);
        cell.setFilter(com.badlogic.gdx.graphics.Pixmap.Filter.NearestNeighbour);
        int ox = (cellW - dw) / 2;
        cell.drawPixmap(src,
            rx + srcX, ry + srcY, srcW, srcH, // src rect (native top-left pixel coords)
            ox, oy, dw, dh);                  // dst rect (scaled)
        Texture out = GdxTextureUtil.managedFromPixmap(cell);
        out.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        if (owns[0]) src.dispose(); // release the decoded source Pixmap (native memory) if unbatched
        return new Sprite(out);
    }

    @Override public String toString() {
        return "Sprite[" + getWidth() + "x" + getHeight() + "]";
    }
}

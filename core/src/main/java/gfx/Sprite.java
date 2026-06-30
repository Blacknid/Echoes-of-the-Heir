package gfx;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * GPU image handle that replaces {@code java.awt.image.BufferedImage} throughout the game.
 * Wraps a libGDX {@link TextureRegion} so that {@code getSubimage} (used for sprite-sheet and
 * tileset slicing) is a zero-copy sub-region, and {@code getWidth/getHeight} report region size
 * in pixels — matching the BufferedImage API the code relied on.
 *
 * <p>Drawing goes through {@code GdxRenderer.drawImage(Sprite, ...)}. Per-pixel work that used
 * {@code createGraphics()} (hit-flash tint, light masks, minimap bake) is reimplemented with
 * FrameBuffer/Pixmap in later stages; {@link #getPixmapARGB()} exposes pixels when truly needed.
 */
public class Sprite {

    private final TextureRegion region;
    // Logical size mirrors java.awt's "pre-scaled" BufferedImage dimensions: callers that asked
    // ResourceCache to scale to (w,h) read getWidth()/getHeight() expecting those numbers, and
    // drawImage(sprite,x,y) should draw at that size. On the GPU we keep the native texture and
    // just report/draw the logical size (nearest-neighbor scaled), so no bitmap is pre-scaled.
    private final int logicalW, logicalH;

    public Sprite(TextureRegion region) {
        this.region = region;
        this.logicalW = region.getRegionWidth();
        this.logicalH = region.getRegionHeight();
    }

    public Sprite(Texture texture) { this(new TextureRegion(texture)); }

    private Sprite(TextureRegion region, int logicalW, int logicalH) {
        this.region = region; this.logicalW = logicalW; this.logicalH = logicalH;
    }

    /** Returns a view of this sprite that reports/draws at the given logical size. */
    public Sprite withLogicalSize(int w, int h) {
        if (w == logicalW && h == logicalH) return this;
        return new Sprite(region, w, h);
    }

    public TextureRegion region() { return region; }
    public Texture texture()      { return region.getTexture(); }

    public int getWidth()  { return logicalW; }
    public int getHeight() { return logicalH; }
    public int nativeWidth()  { return region.getRegionWidth(); }
    public int nativeHeight() { return region.getRegionHeight(); }

    /**
     * Zero-copy sub-region, mirroring {@code BufferedImage.getSubimage(x,y,w,h)} used for
     * sprite-sheet frame slicing and tileset tile extraction. Coordinates are relative to this
     * region's top-left (the regions are already y-down because textures are loaded with the
     * top-left origin convention; see ResourceCache).
     */
    public Sprite getSubimage(int x, int y, int w, int h) {
        return new Sprite(new TextureRegion(region,
            x, y, w, h));
    }

    /** True if this sprite is horizontally flipped (used by some animation code). */
    public boolean isFlipX() { return region.isFlipX(); }
    public boolean isFlipY() { return region.isFlipY(); }

    /**
     * Read a single pixel as packed 0xAARRGGBB (like BufferedImage.getRGB), in this region's
     * local coordinates. CPU-side: consumes the texture's Pixmap, so use only at load/bake time
     * (e.g. minimap terrain sampling), never per frame. Returns 0 if pixels aren't available.
     */
    public int getRGB(int x, int y) {
        Texture tex = region.getTexture();
        if (!tex.getTextureData().isPrepared()) tex.getTextureData().prepare();
        com.badlogic.gdx.graphics.Pixmap pm = tex.getTextureData().consumePixmap();
        if (pm == null) return 0;
        int px = region.getRegionX() + x;
        int py = region.getRegionY() + y;
        int rgba8888 = pm.getPixel(px, py); // 0xRRGGBBAA
        // Repack RGBA8888 → ARGB to match java.awt.BufferedImage.getRGB.
        int r = (rgba8888 >>> 24) & 0xFF;
        int g = (rgba8888 >>> 16) & 0xFF;
        int b = (rgba8888 >>> 8) & 0xFF;
        int a = rgba8888 & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Crop a sub-rectangle of this sprite, scale it to (dw,dh), and place it bottom-aligned and
     * horizontally centered into a transparent cell of (cellW,cellH) — the GPU-native replacement
     * for the "new BufferedImage + createGraphics + drawImage(crop, ox, oy, dw, dh)" pattern used
     * by NPC sprite-sheet trimming. Runs at load time via a Pixmap composite.
     */
    public Sprite croppedBottomAligned(int srcX, int srcY, int srcW, int srcH,
                                       int dw, int dh, int cellW, int cellH) {
        Texture tex = region.getTexture();
        if (!tex.getTextureData().isPrepared()) tex.getTextureData().prepare();
        com.badlogic.gdx.graphics.Pixmap src = tex.getTextureData().consumePixmap();
        com.badlogic.gdx.graphics.Pixmap cell =
            new com.badlogic.gdx.graphics.Pixmap(cellW, cellH, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        cell.setBlending(com.badlogic.gdx.graphics.Pixmap.Blending.None);
        cell.setFilter(com.badlogic.gdx.graphics.Pixmap.Filter.NearestNeighbour);
        int ox = (cellW - dw) / 2;
        int oy = cellH - dh; // bottom-align
        cell.drawPixmap(src,
            region.getRegionX() + srcX, region.getRegionY() + srcY, srcW, srcH, // src rect
            ox, oy, dw, dh);                                                     // dst rect (scaled)
        Texture out = new Texture(cell);
        out.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        cell.dispose();
        return new Sprite(out);
    }

    @Override public String toString() {
        return "Sprite[" + getWidth() + "x" + getHeight() + "]";
    }
}

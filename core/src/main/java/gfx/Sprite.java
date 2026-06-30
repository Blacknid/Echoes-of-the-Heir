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

    public Sprite(TextureRegion region) { this.region = region; }

    public Sprite(Texture texture) { this.region = new TextureRegion(texture); }

    public TextureRegion region() { return region; }
    public Texture texture()      { return region.getTexture(); }

    public int getWidth()  { return region.getRegionWidth(); }
    public int getHeight() { return region.getRegionHeight(); }

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

    @Override public String toString() {
        return "Sprite[" + getWidth() + "x" + getHeight() + "]";
    }
}

package gfx;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;

/**
 * Builds GPU textures from runtime-baked {@link Pixmap}s (rotated sprites, gradients, minimap
 * tiles, procedural icons) so they survive an Android GL context loss/recreate.
 *
 * <p>Plain {@code new Texture(Pixmap)} builds an unmanaged {@code PixmapTextureData} (managed
 * hardcoded false), so libGDX's {@code Texture.invalidateAllTextures()} (fired from
 * {@code AndroidGraphics.onSurfaceCreated} on every context recreate, app backgrounded/resumed,
 * multitasking, etc.) skips it entirely: the GL handle goes stale and the texture renders as
 * blank/garbage until the process restarts. File-backed textures ({@link ResourceCache}) don't
 * have this problem because {@code FileTextureData} is managed by default and just re-decodes
 * from disk on reload.
 *
 * <p>{@link PixmapTextureData} has a 5-arg constructor that accepts {@code managed} directly
 * passing {@code true} registers the texture the same way, and {@code reload()} re-uploads from
 * the same {@link Pixmap} object (not a re-decode), so the source Pixmap must stay alive and
 * MUST NOT be disposed (disposePixmap=false) or reload() would touch freed native memory.
 */
public final class GdxTextureUtil {
    private GdxTextureUtil() {}

    /**
     * Wrap a baked Pixmap as a context-loss-safe managed Texture. Keeps {@code pm} alive for the
 * life of the returned Texture (needed for reload), do not dispose the source Pixmap.
     */
    public static Texture managedFromPixmap(Pixmap pm) {
        return new Texture(new PixmapTextureData(pm, pm.getFormat(), false, false, true));
    }
}

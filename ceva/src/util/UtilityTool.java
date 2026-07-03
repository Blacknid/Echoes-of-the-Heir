package util;

import gfx.Sprite;

/**
 * Image helper. libGDX migration: "scaling" a sprite no longer rasterizes a new bitmap —
 * GPU textures scale at draw time with nearest-neighbor filtering. {@link #scaleImage} therefore
 * returns a logical-size VIEW of the same texture, preserving the old API
 * ({@code getWidth()/getHeight()} report the requested size, draws use it) without any per-pixel work.
 */
public final class UtilityTool {

    private UtilityTool() {}

    public static Sprite scaleImage(Sprite original, int width, int height) {
        if (original == null) return null;
        return original.withLogicalSize(width, height);
    }
}

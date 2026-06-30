package gfx;

/**
 * Android-safe font descriptor mirroring the {@code java.awt.Font} API the game used
 * (name + style + size, {@code deriveFont}, style constants, TRUETYPE_FONT). It carries
 * no rendering state; {@code gfx.GdxRenderer} maps a Font to a sized libGDX BitmapFont
 * (from a FreeType-generated face) at draw time.
 */
public final class Font {

    public static final int PLAIN  = 0;
    public static final int BOLD   = 1;
    public static final int ITALIC = 2;

    /** Marker matching java.awt.Font.TRUETYPE_FONT for the createFont() call sites. */
    public static final int TRUETYPE_FONT = 0;

    private final String name;
    private final int style;
    private final float size;

    public Font(String name, int style, int size) { this(name, style, (float) size); }

    public Font(String name, int style, float size) {
        this.name = name; this.style = style; this.size = size;
    }

    public String getName()    { return name; }
    public String getFamily()  { return name; }
    public int    getStyle()   { return style; }
    public float  getSize2D()  { return size; }
    public int    getSize()    { return Math.round(size); }
    public boolean isBold()    { return (style & BOLD) != 0; }
    public boolean isItalic()  { return (style & ITALIC) != 0; }

    /** java.awt.Font.deriveFont(style, size). */
    public Font deriveFont(int style, float size) { return new Font(name, style, size); }
    /** java.awt.Font.deriveFont(float size). */
    public Font deriveFont(float size)            { return new Font(name, style, size); }
    /** java.awt.Font.deriveFont(int style). */
    public Font deriveFont(int style)             { return new Font(name, style, size); }

    /**
     * Mirrors {@code Font.createFont(type, InputStream)}: builds a base 1pt Font keyed by a
     * registered face name so the renderer can resolve it to the matching FreeType face.
     * The actual .ttf bytes are registered with the renderer's font system at load time
     * (see GdxRenderer / FontSystem); here we only need the logical handle.
     */
    public static Font createFont(int type, java.io.InputStream ttfStream, String registeredName) {
        // Registration of the stream happens in the asset/font layer; this returns the handle.
        return new Font(registeredName, PLAIN, 1f);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Font f)) return false;
        return style == f.style && Float.compare(size, f.size) == 0 && name.equals(f.name);
    }
    @Override public int hashCode() {
        return (name.hashCode() * 31 + style) * 31 + Float.floatToIntBits(size);
    }
    @Override public String toString() { return "Font[" + name + ",style=" + style + ",size=" + size + "]"; }
}

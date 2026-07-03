package gfx;

/**
 * Android-safe RGBA color value type that mirrors the slice of {@code java.awt.Color}'s
 * public API this game actually used, so the migration is a type-swap rather than a
 * logic change. Immutable; components are 0..255 ints (with float convenience ctor).
 *
 * <p>Rendering converts these to libGDX colors on the fly via {@link #toGdx()} /
 * {@link #toFloatBits()} inside {@code gfx.GdxRenderer}; game/UI code never sees libGDX.
 */
public final class Color {

    // Constants used by the codebase (both UPPER and lower-case java.awt aliases).
    public static final Color WHITE      = new Color(255, 255, 255);
    public static final Color white      = WHITE;
    public static final Color BLACK      = new Color(0, 0, 0);
    public static final Color black      = BLACK;
    public static final Color RED        = new Color(255, 0, 0);
    public static final Color red        = RED;
    public static final Color GREEN      = new Color(0, 255, 0);
    public static final Color green      = GREEN;
    public static final Color BLUE       = new Color(0, 0, 255);
    public static final Color blue       = BLUE;
    public static final Color YELLOW     = new Color(255, 255, 0);
    public static final Color yellow     = YELLOW;
    public static final Color ORANGE     = new Color(255, 200, 0);
    public static final Color orange     = ORANGE;
    public static final Color CYAN       = new Color(0, 255, 255);
    public static final Color cyan       = CYAN;
    public static final Color MAGENTA    = new Color(255, 0, 255);
    public static final Color magenta    = MAGENTA;
    public static final Color PINK       = new Color(255, 175, 175);
    public static final Color pink       = PINK;
    public static final Color GRAY       = new Color(128, 128, 128);
    public static final Color gray       = GRAY;
    public static final Color LIGHT_GRAY = new Color(192, 192, 192);
    public static final Color DARK_GRAY  = new Color(64, 64, 64);

    private final int r, g, b, a;

    public Color(int r, int g, int b) { this(r, g, b, 255); }

    public Color(int r, int g, int b, int a) {
        this.r = clamp(r); this.g = clamp(g); this.b = clamp(b); this.a = clamp(a);
    }

    public Color(float r, float g, float b, float a) {
        this(Math.round(r * 255f), Math.round(g * 255f), Math.round(b * 255f), Math.round(a * 255f));
    }

    public Color(float r, float g, float b) { this(r, g, b, 1f); }

    /** java.awt-style packed-int ctor: 0xRRGGBB (opaque) or with hasAlpha, 0xAARRGGBB. */
    public Color(int rgb) {
        this((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
    }
    public Color(int rgba, boolean hasAlpha) {
        this((rgba >> 16) & 0xFF, (rgba >> 8) & 0xFF, rgba & 0xFF,
             hasAlpha ? ((rgba >> 24) & 0xFF) : 255);
    }

    public int getRed()   { return r; }
    public int getGreen() { return g; }
    public int getBlue()  { return b; }
    public int getAlpha() { return a; }

    /** Packed 0xAARRGGBB, matching java.awt.Color.getRGB(). */
    public int getRGB() { return (a << 24) | (r << 16) | (g << 8) | b; }

    /** java.awt.Color.brighter() — scales toward white by 0.7 factor. */
    public Color brighter() {
        int i = (int) (1.0 / (1.0 - 0.7));
        int rr = r, gg = g, bb = b;
        if (rr == 0 && gg == 0 && bb == 0) return new Color(i, i, i, a);
        if (rr > 0 && rr < i) rr = i;
        if (gg > 0 && gg < i) gg = i;
        if (bb > 0 && bb < i) bb = i;
        return new Color(Math.min(255, (int) (rr / 0.7)),
                         Math.min(255, (int) (gg / 0.7)),
                         Math.min(255, (int) (bb / 0.7)), a);
    }

    /** java.awt.Color.darker() — scales toward black by 0.7 factor. */
    public Color darker() {
        return new Color(Math.max(0, (int) (r * 0.7)),
                         Math.max(0, (int) (g * 0.7)),
                         Math.max(0, (int) (b * 0.7)), a);
    }

    /** java.awt.Color.decode("#RRGGBB" or "0x..."). */
    public static Color decode(String nm) {
        return new Color(Integer.decode(nm).intValue());
    }

    /** java.awt.Color.getHSBColor(h,s,b). */
    public static Color getHSBColor(float h, float s, float br) {
        int rgb = hsbToRgb(h, s, br);
        return new Color(rgb);
    }

    // libGDX conversions (used only by the renderer) ──────────────────────────
    public com.badlogic.gdx.graphics.Color toGdx() {
        return new com.badlogic.gdx.graphics.Color(r / 255f, g / 255f, b / 255f, a / 255f);
    }
    /** Packs into a libGDX vertex-color float (ABGR8888) for batch tinting. */
    public float toFloatBits() {
        return com.badlogic.gdx.graphics.Color.toFloatBits(r / 255f, g / 255f, b / 255f, a / 255f);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color c)) return false;
        return r == c.r && g == c.g && b == c.b && a == c.a;
    }
    @Override public int hashCode() { return getRGB(); }
    @Override public String toString() {
        return "Color[r=" + r + ",g=" + g + ",b=" + b + ",a=" + a + "]";
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    // Standard HSB→RGB (mirrors java.awt.Color.HSBtoRGB).
    private static int hsbToRgb(float hue, float saturation, float brightness) {
        int r = 0, g = 0, b = 0;
        if (saturation == 0) {
            r = g = b = (int) (brightness * 255f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6f;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1f - saturation);
            float q = brightness * (1f - saturation * f);
            float t = brightness * (1f - saturation * (1f - f));
            switch ((int) h) {
                case 0 -> { r = (int) (brightness * 255f + 0.5f); g = (int) (t * 255f + 0.5f); b = (int) (p * 255f + 0.5f); }
                case 1 -> { r = (int) (q * 255f + 0.5f); g = (int) (brightness * 255f + 0.5f); b = (int) (p * 255f + 0.5f); }
                case 2 -> { r = (int) (p * 255f + 0.5f); g = (int) (brightness * 255f + 0.5f); b = (int) (t * 255f + 0.5f); }
                case 3 -> { r = (int) (p * 255f + 0.5f); g = (int) (q * 255f + 0.5f); b = (int) (brightness * 255f + 0.5f); }
                case 4 -> { r = (int) (t * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (brightness * 255f + 0.5f); }
                case 5 -> { r = (int) (brightness * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (q * 255f + 0.5f); }
            }
        }
        return (r << 16) | (g << 8) | b;
    }
}

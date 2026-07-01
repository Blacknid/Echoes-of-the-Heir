package gfx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectMap;

/**
 * Resolves {@link Font} descriptors to sized libGDX {@link BitmapFont}s using FreeType, and
 * provides text drawing + measurement ({@link FontMetrics}) for {@link GdxRenderer}.
 *
 * <p>The game used two .ttf faces ("Pixeloid Sans", "m5x7") plus the JVM logical fonts
 * (e.g. "Consolas", "Arial") for debug/HUD text. Registered face names map to generators;
 * unknown names fall back to a bundled default face so nothing renders blank.
 */
public class FontSystem implements Disposable {

    private final ObjectMap<String, FreeTypeFontGenerator> generators = new ObjectMap<>();
    private final ObjectMap<String, BitmapFont> fontCache = new ObjectMap<>(); // key: name|style|size
    private final ObjectMap<BitmapFont, GdxFontMetrics> metricsCache = new ObjectMap<>();
    private final GlyphLayout layout = new GlyphLayout();

    private String defaultFace = "default";
    private final Texture white;

    public FontSystem() {
        // 1x1 white texture for solid fills routed through the batch.
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(1, 1, 1, 1); pm.fill();
        white = new Texture(pm);
        pm.dispose();
    }

    /** Register a .ttf face under a logical name (e.g. "Pixeloid Sans"). */
    public void registerFace(String name, FileHandle ttf) {
        if (ttf != null && ttf.exists()) {
            generators.put(name, new FreeTypeFontGenerator(ttf));
            if ("default".equals(defaultFace)) defaultFace = name;
        }
    }
    /** Choose which registered face stands in for unknown/logical font names. */
    public void setDefaultFace(String name) { if (generators.containsKey(name)) defaultFace = name; }

    public Font defaultFont() { return new Font(defaultFace, Font.PLAIN, 16); }
    public Texture whitePixel() { return white; }

    private BitmapFont resolve(Font f) {
        String face = generators.containsKey(f.getName()) ? f.getName() : defaultFace;
        int size = Math.max(6, f.getSize());
        String key = face + "|" + f.getStyle() + "|" + size;
        BitmapFont bf = fontCache.get(key);
        if (bf != null) return bf;

        FreeTypeFontGenerator gen = generators.get(face);
        if (gen == null) { // no faces registered at all — use libGDX builtin
            bf = new BitmapFont();
            fontCache.put(key, bf);
            return bf;
        }
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = size;
        // yDown camera: glyph quads must be V-flipped just like sprite regions, otherwise each
        // letter renders upside-down. flip=true makes BitmapFont emit glyphs for a top-left origin
        // (y grows downward), matching Graphics2D text placement.
        p.flip = true;
        // Pixel-perfect rasterization: mono (1-bit, NO anti-aliasing) + no hinting distortion. Without
        // this, FreeType anti-aliases glyph edges into gray pixels, which read as a soft "shadow" —
        // especially on thin PLAIN weights and once the whole frame is integer-magnified (pixelScale>1).
        // mono renders each glyph texel as pure on/off, so magnified text stays razor-sharp.
        p.mono = true;
        p.hinting = FreeTypeFontGenerator.Hinting.None;
        // Approximate bold/italic from the base face (FreeType can fake-bold via borderWidth/spacing).
        if (f.isBold()) { p.borderWidth = 0f; p.spaceX = 0; /* face is pixel; bold reads via size */ }
        p.minFilter = Texture.TextureFilter.Nearest;  // pixel-art crisp text
        p.magFilter = Texture.TextureFilter.Nearest;
        bf = gen.generateFont(p);
        // Force integer glyph positions so per-glyph advances never accumulate a fractional offset
        // (which would blur text under integer-scaled rendering). Works with the rounding in draw().
        bf.setUseIntegerPositions(true);
        fontCache.put(key, bf);
        return bf;
    }

    /** Draw text with the baseline at (x,y), matching Graphics2D.drawString. */
    public void draw(SpriteBatch batch, Font f, String s, float x, float y, Color color, float alpha) {
        BitmapFont bf = resolve(f);
        bf.setColor(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f,
                (color.getAlpha() / 255f) * alpha);
        // Graphics2D y is the baseline; a flipped BitmapFont draws with y as the TOP of the line
        // (growing downward). So top = baseline - ascent. getCapHeight approximates the ascent
        // from baseline to the top of caps, matching how Graphics2D positions most UI text.
        // Snap to whole LOGICAL pixels: under integer-scaled rendering (pixelScale>1) a fractional
        // glyph origin smears each texel across the logical->device pixel blocks, giving text a soft
        // "shadow". Rounding keeps every glyph texel on an exact device-pixel grid → pixel-perfect.
        float top = Math.round(y - bf.getCapHeight());
        bf.draw(batch, s, Math.round(x), top);
    }

    public FontMetrics metrics(Font f) {
        BitmapFont bf = resolve(f);
        GdxFontMetrics m = metricsCache.get(bf);
        if (m == null) { m = new GdxFontMetrics(bf, layout); metricsCache.put(bf, m); }
        return m;
    }

    @Override public void dispose() {
        for (BitmapFont bf : fontCache.values()) bf.dispose();
        for (FreeTypeFontGenerator g : generators.values()) g.dispose();
        white.dispose();
    }

    /** FontMetrics backed by a BitmapFont + GlyphLayout. */
    private static final class GdxFontMetrics implements FontMetrics {
        private final BitmapFont bf;
        private final GlyphLayout layout;
        GdxFontMetrics(BitmapFont bf, GlyphLayout layout) { this.bf = bf; this.layout = layout; }
        @Override public int stringWidth(String s) {
            if (s == null || s.isEmpty()) return 0;
            layout.setText(bf, s);
            return Math.round(layout.width);
        }
        @Override public int getHeight()  { return Math.round(bf.getLineHeight()); }
        @Override public int getAscent()  { return Math.round(bf.getCapHeight()); }
        @Override public int getDescent() { return Math.round(-bf.getDescent()); }
        @Override public int charWidth(char c) {
            layout.setText(bf, String.valueOf(c));
            return Math.round(layout.width);
        }
    }
}

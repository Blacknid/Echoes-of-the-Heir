package gfx;

/**
 * Text-measurement contract mirroring the {@code java.awt.FontMetrics} methods the game used
 * (stringWidth, getHeight, getAscent, getDescent, charWidth). Backed by a libGDX BitmapFont +
 * GlyphLayout inside the renderer; obtained via {@code GdxRenderer.getFontMetrics(Font)}.
 */
public interface FontMetrics {
    int stringWidth(String s);
    int getHeight();
    int getAscent();
    int getDescent();
    int charWidth(char c);
    default int charWidth(int codePoint) { return charWidth((char) codePoint); }
    default int getLeading() { return 0; }
}

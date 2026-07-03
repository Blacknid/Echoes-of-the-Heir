package util;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

public final class UtilityTool {

    private static final GraphicsConfiguration GC =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().getDefaultConfiguration();

    private UtilityTool() {}

    public static BufferedImage scaleImage(BufferedImage original, int width, int height) {

        if (original.getWidth() == width && original.getHeight() == height) {
            return original;
        }

        // TRANSLUCENT produces TYPE_INT_ARGB_PRE — fastest for OpenGL pipeline compositing
        // and preserves semi-transparent (anti-aliased) sprite edges correctly.
        BufferedImage scaleImage = GC.createCompatibleImage(width, height, Transparency.TRANSLUCENT);
        Graphics2D g2 = scaleImage.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.drawImage(original, 0, 0, width, height, null);
        g2.dispose();

        return scaleImage;
    }

}

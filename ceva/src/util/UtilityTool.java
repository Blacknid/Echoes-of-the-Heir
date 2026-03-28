package util;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

public final class UtilityTool {

    // Cached graphics config for hardware-compatible image creation
    private static final GraphicsConfiguration GC =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().getDefaultConfiguration();

    private UtilityTool() {}

    public static BufferedImage scaleImage(BufferedImage original, int width, int height) {

        // Create a hardware-compatible image with pre-multiplied alpha for fastest blitting
        BufferedImage scaleImage = GC.createCompatibleImage(width, height, Transparency.BITMASK);
        Graphics2D g2 = scaleImage.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.drawImage(original, 0, 0, width, height, null);
        g2.dispose();

        return scaleImage;
    }

}

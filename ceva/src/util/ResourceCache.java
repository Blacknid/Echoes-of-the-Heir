package util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;

/**
 * Shared cache for frequently reloaded classpath resources.
 *
 * The engine reuses maps, tilesets, and sprites often during map transitions.
 * Caching decoded images and parsed TMX documents avoids repeated disk/classpath
 * reads and expensive XML/image decoding on every reload.
 */
public final class ResourceCache {

    private static final Map<String, BufferedImage> imageCache = new HashMap<>();
    private static final Map<String, BufferedImage> scaledImageCache = new HashMap<>();
    private static final Map<String, Document> xmlCache = new HashMap<>();
    private static final DocumentBuilderFactory xmlFactory = createXmlFactory();

    private ResourceCache() {}

    public static synchronized BufferedImage loadImage(String path) throws IOException {
        BufferedImage cached = imageCache.get(path);
        if (cached != null) {
            return cached;
        }

        try (InputStream stream = ResourceCache.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Resource not found: " + path);
            }

            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                throw new IOException("Failed to decode image: " + path);
            }

            imageCache.put(path, image);
            return image;
        }
    }

    public static synchronized BufferedImage loadScaledImage(String path, int width, int height) throws IOException {
        String cacheKey = path + '|' + width + 'x' + height;
        BufferedImage cached = scaledImageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        BufferedImage image = loadImage(path);
        BufferedImage scaled = (image.getWidth() == width && image.getHeight() == height)
            ? image
            : UtilityTool.scaleImage(image, width, height);
        scaledImageCache.put(cacheKey, scaled);
        return scaled;
    }

    public static synchronized Document loadXml(String path) throws Exception {
        Document cached = xmlCache.get(path);
        if (cached != null) {
            return cached;
        }

        try (InputStream stream = ResourceCache.class.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }

            DocumentBuilder builder = xmlFactory.newDocumentBuilder();
            Document document = builder.parse(stream);
            document.getDocumentElement().normalize();
            xmlCache.put(path, document);
            return document;
        }
    }

    public static synchronized void invalidateXml(String path) {
        xmlCache.remove(path);
    }

    public static synchronized void invalidateImage(String path) {
        imageCache.remove(path);
        scaledImageCache.entrySet().removeIf(entry -> entry.getKey().startsWith(path + '|'));
    }

    private static DocumentBuilderFactory createXmlFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // Best-effort hardening; fallback still works for the local TMX files.
        }
        return factory;
    }
}
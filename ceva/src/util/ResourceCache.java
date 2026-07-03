package util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private static final Set<String> missingImageCache = new HashSet<>();
    private static final Set<String> missingXmlCache = new HashSet<>();
    private static final DocumentBuilderFactory xmlFactory = createXmlFactory();

    /**
     * When set (dev/debug mode only), XML resources are loaded directly from
     * this filesystem directory instead of the classpath.  This means the
     * in-game R reload picks up the latest .tmx saved by Tiled without
     * requiring a resource-sync step first.
     * Activated by calling {@link #setDevSourcePath} at startup.
     */
    private static java.io.File devSourceDir = null;

    /** Call once at startup (DEBUG_MODE only) to enable live .tmx reloading. */
    public static synchronized void setDevSourcePath(String absPath) {
        devSourceDir = new java.io.File(absPath);
        System.out.println("[ResourceCache] Dev source path: " + devSourceDir.getAbsolutePath());
    }

    private ResourceCache() {}

    public static synchronized BufferedImage loadImage(String path) throws IOException {
        BufferedImage image = loadImageIfPresent(path);
        if (image == null) {
            throw new IOException("Resource not found: " + path);
        }
        return image;
    }

    public static synchronized BufferedImage loadImageIfPresent(String path) {
        BufferedImage cached = imageCache.get(path);
        if (cached != null) {
            return cached;
        }

        // Dev mode: read directly from source folder so new images are visible
        // immediately without a sync step. This takes priority over the
        // missing-image cache so that newly added sprites are always found.
        if (devSourceDir != null) {
            java.io.File devFile = new java.io.File(devSourceDir, path);
            if (devFile.exists()) {
                try {
                    BufferedImage image = ImageIO.read(devFile);
                    if (image != null) {
                        imageCache.put(path, image);
                        missingImageCache.remove(path);
                        return image;
                    }
                } catch (IOException ignored) {}
            }
        }

        if (missingImageCache.contains(path)) {
            return null;
        }

        try (InputStream stream = ResourceCache.class.getResourceAsStream(path)) {
            if (stream == null) {
                missingImageCache.add(path);
                System.out.println("[ResourceCache] Missing image: " + path);
                return null;
            }

            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                missingImageCache.add(path);
                System.out.println("[ResourceCache] Failed to decode image: " + path);
                return null;
            }

            imageCache.put(path, image);
            return image;
        } catch (IOException e) {
            missingImageCache.add(path);
            System.out.println("[ResourceCache] Failed to load image: " + path + " (" + e.getMessage() + ")");
            return null;
        }
    }

    public static synchronized BufferedImage loadScaledImage(String path, int width, int height) throws IOException {
        BufferedImage scaled = loadScaledImageIfPresent(path, width, height);
        if (scaled == null) {
            throw new IOException("Resource not found: " + path);
        }
        return scaled;
    }

    public static synchronized BufferedImage loadScaledImageIfPresent(String path, int width, int height) {
        String cacheKey = path + '|' + width + 'x' + height;
        BufferedImage cached = scaledImageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        BufferedImage image = loadImageIfPresent(path);
        if (image == null) {
            return null;
        }
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
        if (missingXmlCache.contains(path)) {
            return null;
        }

        // Dev mode: read directly from the source folder so edits saved in
        // Tiled are visible immediately when R is pressed — no sync needed.
        if (devSourceDir != null) {
            java.io.File devFile = new java.io.File(devSourceDir, path);
            if (devFile.exists()) {
                try {
                    DocumentBuilder builder = xmlFactory.newDocumentBuilder();
                    Document document = builder.parse(devFile);
                    document.getDocumentElement().normalize();
                    xmlCache.put(path, document);
                    return document;
                } catch (Exception e) {
                    System.out.println("[ResourceCache] Failed to load dev XML: " + devFile + " (" + e.getMessage() + ")");
                    // fall through to classpath load
                }
            }
        }

        try (InputStream stream = ResourceCache.class.getResourceAsStream(path)) {
            if (stream == null) {
                missingXmlCache.add(path);
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
        missingXmlCache.remove(path);
    }

    /**
     * Inject a parsed XML document into the cache for {@code path}.
     *
     * <p>This is the integration point used by the multiplayer client's map
     * streamer: the server sends a "skeleton" TMX (no tile data) which the
     * client preloads here under the same {@code /res/maps/<id>.tmx} virtual
     * path that the rest of the engine already understands. The existing
     * TileManager / MapObjectLoader pipeline picks it up via
     * {@link #loadXml(String)} as if the TMX had been loaded from the JAR.
     *
     * <p>Subsequent calls overwrite any previous entry for the same path —
     * this is what allows the same map id to be re-streamed when the
     * multiplayer server changes worlds.
     */
    public static synchronized void preloadXml(String path, byte[] xmlBytes) throws Exception {
        if (path == null || xmlBytes == null) {
            throw new IllegalArgumentException("preloadXml: null arg");
        }
        DocumentBuilder builder = xmlFactory.newDocumentBuilder();
        Document document;
        try (java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(xmlBytes)) {
            document = builder.parse(bin);
        }
        document.getDocumentElement().normalize();
        xmlCache.put(path, document);
        missingXmlCache.remove(path);
    }

    public static synchronized void invalidateImage(String path) {
        imageCache.remove(path);
        missingImageCache.remove(path);
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
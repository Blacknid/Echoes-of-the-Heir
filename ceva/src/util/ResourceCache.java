package util;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;

import gfx.Sprite;

/**
 * Shared cache for frequently reloaded classpath resources.
 *
 * The engine reuses maps, tilesets, and sprites often during map transitions.
 * Caching decoded images and parsed TMX documents avoids repeated disk/classpath
 * reads and expensive XML/image decoding on every reload.
 *
 * <p>libGDX migration: images are libGDX {@link gfx.Sprite} (GPU Texture) instead of
 * BufferedImage. XML (.tmx/.tsx) parsing is unchanged (engine-agnostic). Paths remain the
 * classpath-style {@code /res/...} the whole engine uses; they are resolved against the
 * libGDX assets root (or the dev source dir for live reload). Textures use nearest-neighbor
 * filtering for crisp pixel art, matching the old VALUE_INTERPOLATION_NEAREST_NEIGHBOR hint.
 */
public final class ResourceCache {

    private static final Map<String, Sprite> imageCache = new HashMap<>();
    private static final Map<String, Sprite> scaledImageCache = new HashMap<>();
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

    public static synchronized Sprite loadImage(String path) throws IOException {
        Sprite image = loadImageIfPresent(path);
        if (image == null) {
            throw new IOException("Resource not found: " + path);
        }
        return image;
    }

    public static synchronized Sprite loadImageIfPresent(String path) {
        Sprite cached = imageCache.get(path);
        if (cached != null) {
            return cached;
        }

        // Dev mode: read directly from source folder so new images are visible
        // immediately without a sync step. This takes priority over the
        // missing-image cache so that newly added sprites are always found.
        if (devSourceDir != null) {
            java.io.File devFile = new java.io.File(devSourceDir, path);
            if (devFile.exists()) {
                Sprite image = textureFrom(new com.badlogic.gdx.files.FileHandle(devFile));
                if (image != null) {
                    imageCache.put(path, image);
                    missingImageCache.remove(path);
                    return image;
                }
            }
        }

        if (missingImageCache.contains(path)) {
            return null;
        }

        // Packaged / runtime: resolve the engine's classpath-style "/res/..." path. Try the
        // internal (working-dir) root first, then the classpath (where bundled assets live in the
        // packaged jar and on the dev runtime classpath via core's resources srcDir).
        FileHandle fh = resolve(path);
        if (fh == null) {
            missingImageCache.add(path);
            System.out.println("[ResourceCache] Missing image: " + path);
            return null;
        }
        Sprite image = textureFrom(fh);
        if (image == null) {
            missingImageCache.add(path);
            System.out.println("[ResourceCache] Failed to decode image: " + path);
            return null;
        }
        imageCache.put(path, image);
        return image;
    }

    /**
     * Resolve an engine "/res/..." path to an existing FileHandle: try the internal (working-dir)
     * root first, then the classpath (bundled jar assets / dev runtime classpath). Returns null if
     * neither exists.
     */
    private static FileHandle resolve(String path) {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        FileHandle fh = Gdx.files.internal(rel);
        if (fh.exists()) return fh;
        FileHandle cp = Gdx.files.classpath(path); // classpath uses the leading-slash absolute form
        if (cp.exists()) return cp;
        return null;
    }

    /** Create a nearest-filtered GPU texture sprite from a file handle (crisp pixel art). */
    private static Sprite textureFrom(FileHandle fh) {
        try {
            Texture tex = new Texture(fh, false);
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return new Sprite(tex);
        } catch (Exception e) {
            System.out.println("[ResourceCache] Texture load failed: " + fh + " (" + e.getMessage() + ")");
            return null;
        }
    }

    public static synchronized Sprite loadScaledImage(String path, int width, int height) throws IOException {
        Sprite scaled = loadScaledImageIfPresent(path, width, height);
        if (scaled == null) {
            throw new IOException("Resource not found: " + path);
        }
        return scaled;
    }

    public static synchronized Sprite loadScaledImageIfPresent(String path, int width, int height) {
        String cacheKey = path + '|' + width + 'x' + height;
        Sprite cached = scaledImageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Sprite image = loadImageIfPresent(path);
        if (image == null) {
            return null;
        }
        // GPU "scaling" is a no-op on the bitmap: keep the native texture, report/draw the
        // requested logical size (nearest-neighbor). Matches the old pre-scaled BufferedImage API.
        Sprite scaled = image.withLogicalSize(width, height);
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
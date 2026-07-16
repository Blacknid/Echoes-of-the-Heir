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
import com.badlogic.gdx.graphics.g2d.TextureAtlas;

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

    /**
     * Packed sprite atlas (see gdx-tools TexturePacker output), loaded once at startup if
     * present. Only packaged builds ship an atlas; dev runs have none, so this stays null and
     * every image loads from loose files as before (preserving dev-mode live reload).
     */
    private static TextureAtlas spriteAtlas = null;
    private static boolean spriteAtlasLoadAttempted = false;

    /** Call once at startup (DEBUG_MODE only) to enable live .tmx reloading. */
    public static synchronized void setDevSourcePath(String absPath) {
        devSourceDir = new java.io.File(absPath);
        System.out.println("[ResourceCache] Dev source path: " + devSourceDir.getAbsolutePath());
    }

    /** The live dev source directory set by {@link #setDevSourcePath}, or null outside dev mode. */
    public static synchronized java.io.File getDevSourceDir() {
        return devSourceDir;
    }

    /** Loads res/atlas/sprites.atlas from the classpath, if it exists, once per run. */
    private static void ensureSpriteAtlasLoaded() {
        if (spriteAtlasLoadAttempted) return;
        spriteAtlasLoadAttempted = true;
        // The atlas is a packed GPU texture, there is nothing to pack it into without GL. Leaving
        // it null makes loadImageIfPresent fall through to the loose-file path, which in headless
        // mode reads dimensions from the PNG header (see textureFrom).
        if (headless) return;
        FileHandle fh = Gdx.files.internal("res/atlas/sprites.atlas");
        if (!fh.exists()) fh = Gdx.files.classpath("/res/atlas/sprites.atlas");
        if (!fh.exists()) return;
        try {
            spriteAtlas = new TextureAtlas(fh);
            System.out.println("[ResourceCache] Loaded packed sprite atlas: " + fh);
        } catch (Exception e) {
            System.out.println("[ResourceCache] Failed to load sprite atlas: " + e.getMessage());
        }
    }

    /**
     * Derive a TextureAtlas region name from an engine "/res/..." path: strip the leading
     * "/res/" and the file extension, matching how the packer names regions from input files.
     */
    private static String atlasRegionName(String path) {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.startsWith("res/")) rel = rel.substring(4);
        int dot = rel.lastIndexOf('.');
        return dot > 0 ? rel.substring(0, dot) : rel;
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

        // Packaged builds: prefer the packed sprite atlas over loading individual textures.
        ensureSpriteAtlasLoaded();
        if (spriteAtlas != null) {
            com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion region =
                spriteAtlas.findRegion(atlasRegionName(path));
            if (region != null) {
                Sprite image = new Sprite(region);
                imageCache.put(path, image);
                return image;
            }
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
    /**
     * Headless mode: no GL context, so no textures. Set by the authoritative game server
     * (see main.HeadlessGame) before any asset is touched.
     *
     * <p>This is the ONE place the renderless build diverges from the client, because
     * {@link #textureFrom} is the only spot in the codebase that constructs a {@link Texture}.
 * Everything above it, sheet slicing, frame-count math, entity construction, is pure
     * arithmetic on sprite dimensions and runs identically either way.
     */
    private static boolean headless = false;

    /** Switch to dimension-only image loading (no GPU). Call once, before loading any asset. */
    public static synchronized void setHeadless(boolean value) {
        headless = value;
        if (value) System.out.println("[ResourceCache] Headless mode — images load as dimensions only");
    }

    public static synchronized boolean isHeadless() { return headless; }

    private static Sprite textureFrom(FileHandle fh) {
        if (headless) {
            int[] size = pngSize(fh);
            // A sprite the simulation can measure but not draw. If the header is unreadable we
            // still hand back a 1x1 rather than null: null means "missing asset" to every caller,
            // and a server that can't parse a PNG header hasn't lost anything it actually needs.
            return size != null ? Sprite.headless(size[0], size[1]) : Sprite.headless(1, 1);
        }
        try {
            Texture tex = new Texture(fh, false);
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return new Sprite(tex);
        } catch (Exception e) {
            System.out.println("[ResourceCache] Texture load failed: " + fh + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
 * Read a PNG's pixel dimensions straight out of its IHDR chunk, the first 24 bytes, without
     * decoding the image. That is all the simulation ever wants from a sprite sheet: width and
     * height, to divide into frames.
     *
     * @return {width, height}, or null if this isn't a readable PNG
     */
    private static int[] pngSize(FileHandle fh) {
        try (java.io.InputStream in = fh.read()) {
            byte[] head = in.readNBytes(24);
            if (head.length < 24) return null;
            // 8-byte signature, then the IHDR chunk: 4-byte length, 4-byte type, then W and H as
            // big-endian 32-bit ints at offsets 16 and 20.
            if ((head[0] & 0xFF) != 0x89 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') {
                return null;
            }
            int w = readBE32(head, 16);
            int h = readBE32(head, 20);
            if (w <= 0 || h <= 0) return null;
            return new int[]{ w, h };
        } catch (Exception e) {
            return null;
        }
    }

    private static int readBE32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
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
        // Tiled are visible immediately when R is pressed, no sync needed.
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

        try (InputStream stream = openClasspathStream(path)) {
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

    /**
     * Opens a classpath-style resource path (e.g. {@code "/res/data/items.json"}) as a stream.
     * On the desktop backend the assets are on the JVM classpath, so {@code Class.getResourceAsStream}
 * works; on Android they are packaged as APK assets instead, which are NOT on the classpath
     * {@link com.badlogic.gdx.files.FileHandle} (via {@code Gdx.files.internal}) is the one loading
     * path that works identically on both backends, so every classpath-style resource load in the
     * game (factories, quest/skill data, fonts, XML/TMX parsing) should route through this method
     * instead of calling {@code getResourceAsStream} directly.
     */
    public static InputStream openClasspathStream(String path) {
        String assetPath = path.startsWith("/") ? path.substring(1) : path;
        FileHandle fh = Gdx.files.internal(assetPath);
        return fh.exists() ? fh.read() : null;
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
 * <p>Subsequent calls overwrite any previous entry for the same path
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

    /**
     * Drops every cached {@link Sprite}/{@link TextureAtlas} entry without disposing the GL
 * textures they wrap. Must run at the very start of each {@code MichiGame.create()}, these
     * maps are static, so on Android (singleTask launch mode) they survive a "restart" that
     * reuses the process, while the old GL context and its {@code Texture} objects are gone.
     * Handing out a {@link Sprite} built against a dead context makes the GPU sample whatever
     * texture now happens to occupy that recycled id (e.g. the wrong NPC portrait bleeding into
     * the background). Skipping disposal is intentional: the textures belong to a context that no
     * longer exists, so disposing them would just make GL calls against invalid/reused handles.
     */
    public static synchronized void resetImages() {
        imageCache.clear();
        scaledImageCache.clear();
        missingImageCache.clear();
        spriteAtlas = null;
        spriteAtlasLoadAttempted = false;
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
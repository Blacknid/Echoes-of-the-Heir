package mod;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import main.GamePanel;
import main.Headless;

/**
 * Discovers and runs Lua mods from the {@code /mods} directory.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #preInit()} runs BEFORE {@link GamePanel} sizes its world arrays. It boots each mod
 *       once so config-only calls (e.g. {@code ModApi.setTileDimensions}) can take effect before the
 *       engine reads {@code Config.tileSize}. Content/hook registration during this pass is fine too.</li>
 *   <li>{@link #boot(GamePanel)} runs AFTER {@code gp.setupGame()}. It fires a {@code ready} event so
 *       mods that need a live {@link GamePanel} (spawning, world access) can act, and leaves their
 *       registered event handlers in place for the engine to call each tick.</li>
 * </ol>
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>No mods = no change.</b> If {@code /mods} is absent or empty, every method here is a
 *       cheap no-op and the base game runs byte-for-byte as before.</li>
 *   <li><b>Server-safe.</b> When {@link Headless#isEnabled()} (the authoritative engine), mods are
 *       never loaded, so nothing a mod does can influence server-side simulation or multiplayer.</li>
 *   <li><b>Crash-isolated.</b> A mod that errors while loading is logged and skipped; it cannot take
 *       down the game.</li>
 * </ul>
 */
public final class ModLoader {

    private ModLoader() {}

    /** Directory scanned for mods, relative to the working directory (desktop) / local (Android). */
    public static final String MODS_DIR = "mods";

    private static final List<LoadedMod> LOADED = new ArrayList<>();
    private static boolean preInitDone = false;

    private static final class LoadedMod {
        final ModManifest manifest;
        final Globals globals;
        LoadedMod(ModManifest manifest, Globals globals) {
            this.manifest = manifest;
            this.globals = globals;
        }
    }

    /**
     * Config phase: compile + run each mod's entry script once so declarative registration and safe
     * global tweaks land before the engine sizes itself. Safe to call once; a second call no-ops.
     */
    public static void preInit() {
        if (preInitDone) return;
        preInitDone = true;
        if (Headless.isEnabled()) return; // server never mods

        File[] dirs = discover();
        if (dirs.length == 0) return; // vanilla: nothing to do

        System.out.println("[ModLoader] found " + dirs.length + " mod folder(s) in ./" + MODS_DIR);
        for (File dir : dirs) {
            ModManifest mf = ModManifest.read(dir);
            if (mf == null) continue;
            try {
                Globals g = LuaSandbox.create(mf.id, dir);
                File entry = mf.entryFile();
                if (!entry.isFile()) {
                    System.out.println("[ModLoader] '" + mf.id + "': entry '" + mf.entry + "' not found, skipping");
                    continue;
                }
                String src = new String(java.nio.file.Files.readAllBytes(entry.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                // Compile + execute the chunk in the mod's own sandbox.
                LuaValue chunk = g.load(src, mf.id + "/" + mf.entry);
                chunk.call();
                LOADED.add(new LoadedMod(mf, g));
                System.out.println("[ModLoader] loaded '" + mf.id + "' v" + mf.version + " (" + mf.name + ")");
            } catch (ModSecurityException se) {
                System.out.println("[ModLoader] '" + mf.id + "' BLOCKED: " + se.getMessage());
            } catch (Throwable t) {
                System.out.println("[ModLoader] '" + mf.id + "' failed to load: " + t.getMessage());
            }
        }

        // Apply a config-phase tile-dimension request, if any mod made one.
        applyPendingConfig();
    }

    /**
     * Post-setup phase: fire a {@code ready} event with the live {@link GamePanel} so mods can do
     * world-dependent work. Registered per-event handlers remain active for the engine to fire.
     */
    public static void boot(GamePanel gp) {
        if (Headless.isEnabled()) return;
        if (!preInitDone) preInit();          // defensive: boot() should follow preInit()
        if (LOADED.isEmpty()) return;         // vanilla
        ModEventBus.fire("ready", LuaConv.toLua(gp));
        System.out.println("[ModLoader] " + LOADED.size() + " mod(s) active");
    }

    /** Apply a tile-size a mod requested during preInit, before the world is allocated. */
    private static void applyPendingConfig() {
        int px = ModApi.pendingTileSize;
        if (px > 0) {
            main.Config.originalTileSize = px / 2;      // scale 2.0 default → keep tileSize == px
            main.Config.setScale(main.Config.scale);    // recompute derived tileSize
            System.out.println("[ModLoader] mod '" + ModApi.pendingTileSizeMod
                    + "' set tile dimensions to " + main.Config.tileSize + "px");
        }
    }

    /** List of valid mod subdirectories, or an empty array if {@code /mods} is missing/empty. */
    private static File[] discover() {
        File root = new File(MODS_DIR);
        if (!root.isDirectory()) return new File[0];
        File[] subs = root.listFiles(File::isDirectory);
        if (subs == null) return new File[0];
        Arrays.sort(subs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return subs;
    }

    /** True if any mods are currently loaded (lets hot paths skip mod work entirely). */
    public static boolean anyLoaded() { return !LOADED.isEmpty(); }

    /** Reset all mod state (used for a dev-time reload). */
    public static void reset() {
        LOADED.clear();
        preInitDone = false;
        ModContentRegistry.clear();
        ModEventBus.clear();
        ModApi.reset();
    }
}

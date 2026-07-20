package mod;

import java.io.File;

/**
 * Parsed {@code mod.json} manifest for one mod folder. Minimal on purpose: an id (must match the
 * folder, used to namespace storage and log lines), a display name, a version string, and the entry
 * script filename (defaults to {@code main.lua}). Parsed with the same tolerant {@link MiniJson}
 * used for mod storage, so a mod author needs no special tooling.
 */
public final class ModManifest {

    public final String id;
    public final String name;
    public final String version;
    public final String entry;
    public final File dir;

    private ModManifest(String id, String name, String version, String entry, File dir) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.entry = entry;
        this.dir = dir;
    }

    /**
     * Read and validate {@code <dir>/mod.json}. Returns null (with a logged reason) if the manifest
     * is missing, malformed, or lacks an id — the loader then skips that folder rather than failing
     * the whole game.
     */
    public static ModManifest read(File dir) {
        File mf = new File(dir, "mod.json");
        if (!mf.isFile()) {
            System.out.println("[ModLoader] skipping '" + dir.getName() + "': no mod.json");
            return null;
        }
        try {
            String json = new String(java.nio.file.Files.readAllBytes(mf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.luaj.vm2.LuaValue v = MiniJson.fromJson(json);
            if (!v.istable()) {
                System.out.println("[ModLoader] skipping '" + dir.getName() + "': mod.json is not an object");
                return null;
            }
            String id      = str(v, "id", dir.getName());
            String name    = str(v, "name", id);
            String version = str(v, "version", "1.0");
            String entry   = str(v, "entry", "main.lua");
            if (id.isBlank()) {
                System.out.println("[ModLoader] skipping '" + dir.getName() + "': empty id");
                return null;
            }
            return new ModManifest(id, name, version, entry, dir);
        } catch (Exception e) {
            System.out.println("[ModLoader] skipping '" + dir.getName() + "': bad mod.json (" + e.getMessage() + ")");
            return null;
        }
    }

    private static String str(org.luaj.vm2.LuaValue table, String key, String def) {
        org.luaj.vm2.LuaValue v = table.get(key);
        return (v.isnil()) ? def : v.tojstring();
    }

    /** The mod's entry script file. */
    public File entryFile() { return new File(dir, entry); }
}

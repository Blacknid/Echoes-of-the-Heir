package mod;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Scoped, <b>local-only</b> persistence for mods, exposed to Lua as {@code import "ModStorage"}.
 *
 * <p>This is the deliberate and only sanctioned way for a mod to save data, and it is wired to the
 * player's own machine, never to the save-server database. A mod calls {@code Store.save(key, value)}
 * / {@code Store.load(key)}; the value is serialised to JSON at
 * {@code mods/<modId>/data/<key>.json} through {@link com.badlogic.gdx.Gdx#files} local storage
 * (the working dir on desktop, private app storage on Android — the same place the game keeps its
 * config, and a path {@link data.CloudSaveService} never reads).
 *
 * <p>Because this class has no reference to {@link data.CloudSaveService} and the cloud serialiser
 * never enumerates {@code mods/}, nothing a mod persists can enter the upload path. Keys are
 * sanitised so a mod cannot escape its own {@code mods/<id>/data/} subtree (no {@code ..}, no
 * separators, no absolute paths).
 */
public final class ModStorage {

    private ModStorage() {}

    /** Build the Lua table exposing save/load/delete/keys for {@code modId}. */
    public static LuaValue table(String modId) {
        LuaTable t = new LuaTable();
        t.set("save", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 3 ? 2 : 1;
                String key = args.checkjstring(base);
                LuaValue value = args.arg(base + 1);
                ModStorage.save(modId, key, value);
                return LuaValue.TRUE;
            }
        });
        t.set("load", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 2 ? 2 : 1;
                String key = args.checkjstring(base);
                return ModStorage.load(modId, key);
            }
        });
        t.set("delete", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 2 ? 2 : 1;
                String key = args.checkjstring(base);
                return LuaValue.valueOf(delete(modId, key));
            }
        });
        return t;
    }

    private static com.badlogic.gdx.files.FileHandle file(String modId, String key) {
        String safeKey = sanitize(key);
        String path = "mods/" + sanitize(modId) + "/data/" + safeKey + ".json";
        return com.badlogic.gdx.Gdx.files.local(path);
    }

    private static void save(String modId, String key, LuaValue value) {
        try {
            String json = MiniJson.toJson(value);
            com.badlogic.gdx.files.FileHandle fh = file(modId, key);
            fh.parent().mkdirs();
            fh.writeString(json, false, "UTF-8");
        } catch (Exception e) {
            throw new LuaError("ModStorage.save('" + key + "') failed: " + e.getMessage());
        }
    }

    private static LuaValue load(String modId, String key) {
        try {
            com.badlogic.gdx.files.FileHandle fh = file(modId, key);
            if (!fh.exists()) return LuaValue.NIL;
            return MiniJson.fromJson(fh.readString("UTF-8"));
        } catch (Exception e) {
            throw new LuaError("ModStorage.load('" + key + "') failed: " + e.getMessage());
        }
    }

    private static boolean delete(String modId, String key) {
        com.badlogic.gdx.files.FileHandle fh = file(modId, key);
        return fh.exists() && fh.delete();
    }

    /**
     * Keep a key/id confined to its own {@code mods/<id>/data/} folder: strip anything but safe
     * filename characters, so no {@code ..}, slash, backslash, colon or absolute path can escape the
     * mod's own subtree.
     */
    private static String sanitize(String s) {
        if (s == null || s.isBlank()) throw new LuaError("ModStorage: empty key/id");
        String cleaned = s.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (cleaned.isBlank()) throw new LuaError("ModStorage: invalid key '" + s + "'");
        return cleaned;
    }
}

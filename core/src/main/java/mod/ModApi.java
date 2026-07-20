package mod;

import java.util.LinkedHashMap;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The high-level, curated Lua API a mod reaches via {@code import "ModApi"}. This is the "declarative
 * data + event callbacks" surface: register content ({@code registerMonster}, {@code registerItem},
 * {@code registerNpc}), register custom AI ({@code registerAI}), hook engine events ({@code on}), and
 * adjust safe global knobs ({@code setTileDimensions}). It complements — but never replaces — the raw
 * reflection bridge ({@code import "Entities"} etc.), which remains available for fine-grained control.
 *
 * <p>Everything here funnels into the same sealed engine; nothing in this API can reach auth, the
 * save/multiplayer servers, or crypto. Content registered here is consulted by the existing factories
 * (see {@link ModContentRegistry}).
 */
public final class ModApi {

    private ModApi() {}

    /** Build the ModApi table for a given mod. Each mod gets a table bound to its own id. */
    public static LuaValue table(String modId, java.io.File modDir) {
        LuaTable api = new LuaTable();

        api.set("registerMonster", register(modId, ModContentRegistry::registerMonster, "registerMonster"));
        api.set("registerItem",    register(modId, ModContentRegistry::registerItem, "registerItem"));
        api.set("registerNpc",     register(modId, ModContentRegistry::registerNpc, "registerNpc"));

        // registerAI(behaviorName, function(self, gp) ... end)
        api.set("registerAI", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 3 ? 2 : 1;
                String behavior = args.checkjstring(base);
                LuaValue fn = args.arg(base + 1);
                if (!fn.isfunction()) throw new LuaError("registerAI: second arg must be a function");
                ModContentRegistry.registerAi(behavior, fn);
                return LuaValue.NIL;
            }
        });

        // on(eventName, function(...) ... end)
        api.set("on", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 3 ? 2 : 1;
                String event = args.checkjstring(base);
                LuaValue fn = args.arg(base + 1);
                ModEventBus.on(modId, event, fn);
                return LuaValue.NIL;
            }
        });

        // setTileDimensions(px) — safe global knob. Only takes effect if called during the pre-init
        // (config) phase, BEFORE the world arrays are sized. See ModLoader.preInit / ModApi.pendingTileSize.
        api.set("setTileDimensions", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 2 ? 2 : 1;
                int px = args.checkint(base);
                if (px < 8 || px > 256) throw new LuaError("setTileDimensions: px must be in 8..256");
                pendingTileSize = px;
                pendingTileSizeMod = modId;
                return LuaValue.NIL;
            }
        });

        // modDir() — the mod's own folder, so scripts can build sprite paths relative to themselves.
        api.set("modDir", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(modDir.getPath().replace('\\', '/'));
            }
        });

        // log(msg) — attributable info line.
        api.set("log", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int base = args.arg1().istable() && args.narg() >= 2 ? 2 : 1;
                System.out.println("[mod:" + modId + "] " + args.arg(base).tojstring());
                return LuaValue.NIL;
            }
        });

        return api;
    }

    /** Tile size a config-phase mod requested, applied by ModLoader before world allocation. */
    public static volatile int pendingTileSize = -1;
    public static volatile String pendingTileSizeMod = null;

    /** Reset cross-run state (dev reload). */
    public static void reset() { pendingTileSize = -1; pendingTileSizeMod = null; }

    // ── helpers ──

    private interface DefSink { void accept(String id, Map<String, String> def); }

    /**
     * Shared implementation of the register{Monster,Item,Npc} functions: read a Lua definition table
     * into the flat {@code Map<String,String>} the factories consume, requiring an {@code id} key.
     */
    private static VarArgFunction register(String modId, DefSink sink, String fnName) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                // Accept ModApi.registerX{...} (table as arg1) or ModApi:registerX{...} (self, table).
                LuaValue tbl = args.arg1().istable() && args.narg() >= 2 && args.arg(2).istable()
                        ? args.arg(2) : args.arg1();
                if (!tbl.istable()) throw new LuaError(fnName + ": expected a definition table");
                Map<String, String> def = flatten(tbl.checktable());
                String id = def.get("id");
                if (id == null || id.isBlank()) throw new LuaError(fnName + ": definition needs an 'id'");
                sink.accept(id, def);
                System.out.println("[mod:" + modId + "] " + fnName + " '" + id + "'");
                return LuaValue.NIL;
            }
        };
    }

    /**
     * Flatten a Lua definition table into the {@code Map<String,String>} shape the factories parse
     * JSON into. Nested tables are flattened with a dotted prefix ({@code solidArea.width}) and array
     * tables are joined with commas ({@code framesPerRow = "6,6,6,6"}), matching the existing JSON
     * conventions in MonsterFactory/NPCFactory.
     */
    private static Map<String, String> flatten(LuaTable t) {
        Map<String, String> out = new LinkedHashMap<>();
        flattenInto(t, "", out);
        return out;
    }

    private static void flattenInto(LuaTable t, String prefix, Map<String, String> out) {
        // Array-shaped table → comma-joined scalar under the prefix (drop trailing dot).
        int len = t.length();
        if (len > 0 && t.keyCount() == len) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= len; i++) {
                if (i > 1) sb.append(',');
                sb.append(t.get(i).tojstring());
            }
            if (!prefix.isEmpty()) out.put(prefix.substring(0, prefix.length() - 1), sb.toString());
            return;
        }
        Varargs k = t.next(LuaValue.NIL);
        while (!k.arg1().isnil()) {
            LuaValue key = k.arg1();
            LuaValue val = k.arg(2);
            String name = prefix + key.tojstring();
            if (val.istable()) {
                flattenInto(val.checktable(), name + ".", out);
            } else if (val.isboolean()) {
                out.put(name, String.valueOf(val.toboolean()));
            } else {
                out.put(name, val.tojstring());
            }
            k = t.next(key);
        }
    }
}

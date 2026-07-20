package mod;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

/**
 * Builds the restricted LuaJ {@link Globals} each mod runs in. Deliberately does <b>not</b> install
 * the standard {@code io}, {@code os} (except a couple of safe fns), {@code luajava} or filesystem
 * {@code loadfile}/{@code dofile} libraries, so a mod cannot open files, spawn processes, read the
 * clock-as-entropy, or bootstrap around the reflection gate via LuaJ's own Java-interop library.
 *
 * <p>The one door into Java is the {@code import(name)} global, which routes through {@link JavaBridge}
 * and therefore through {@link SecurityGate}. Content/hook helpers are installed as the {@code ModApi}
 * import target (see {@link ModApi}).
 *
 * <p>Each mod gets its OWN Globals (its own sandbox), so one mod cannot clobber another's globals or
 * see its state except through the shared engine it mutates.
 */
public final class LuaSandbox {

    private LuaSandbox() {}

    /**
     * Create a fresh restricted environment for the mod identified by {@code modId}, whose scripts
     * live under {@code modDir}. The {@code import} global and a scoped print are installed.
     */
    public static Globals create(String modId, java.io.File modDir) {
        Globals g = new Globals();
        // Safe standard libraries only. Notably absent: JseIoLib, JseOsLib, LuajavaLib, CoroutineLib
        // (io/os/luajava are the file/process/interop escape routes; coroutines aren't needed and add
        // reentrancy the engine loop would have to reason about).
        g.load(new JseBaseLib());
        g.load(new PackageLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        g.load(new TableLib());
        g.load(new Bit32Lib());

        // Install the Lua source compiler so g.load(String) can compile a mod's .lua text. Without
        // this LuaJ can only run pre-compiled chunks and throws "No compiler". LuaC only compiles
        // source to bytecode; it opens no files and adds no capability beyond the safe libs above.
        org.luaj.vm2.compiler.LuaC.install(g);

        // Strip the base-library functions that touch the filesystem or load arbitrary chunks. After
        // this, there is no Lua-level way to read a file or evaluate off-disk code except the mod's
        // own entry script, which ModLoader compiles explicitly.
        g.set("loadfile", LuaValue.NIL);
        g.set("dofile", LuaValue.NIL);
        g.set("load", LuaValue.NIL);
        g.set("loadstring", LuaValue.NIL);
        g.set("collectgarbage", LuaValue.NIL);

        // The single sanctioned bridge into Java, gated by SecurityGate.
        g.set("import", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String name = args.checkjstring(1);
                if ("ModApi".equals(name))     return ModApi.table(modId, modDir);
                if ("ModStorage".equals(name)) return ModStorage.table(modId);
                return JavaBridge.importClass(name);
            }
        });

        // Namespaced print so mod output is attributable in the game log.
        g.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) sb.append('\t');
                    sb.append(args.arg(i).tojstring());
                }
                System.out.println("[mod:" + modId + "] " + sb);
                return LuaValue.NONE;
            }
        });

        return g;
    }
}

package mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The reflection bridge between Lua and Java gameplay classes. Backs the sandbox's {@code import(...)}
 * function: given a short name ("Entities") or a fully-qualified class name, it resolves the class,
 * checks it against {@link SecurityGate}, and returns a Lua userdata that lets a mod read/write public
 * fields and call public methods reflectively — the "full reflection" the design calls for.
 *
 * <p>Every reflective touch is filtered through {@link SecurityGate}, and every value crossing back
 * into Lua is checked with {@link SecurityGate#isSealedValue(Object)} so a mod can never obtain a live
 * handle to a sealed (auth/save/server/crypto) object. Values are converted with {@link LuaConv}.
 *
 * <p>A returned userdata is either a <b>class proxy</b> (static fields/methods + {@code new(...)}) or
 * an <b>instance proxy</b> (instance fields/methods of a specific object). Both expose the same
 * dynamic field/method access through Lua's {@code __index} / {@code __newindex} metamethods.
 */
public final class JavaBridge {

    private JavaBridge() {}

    /**
     * Short aliases a mod may {@code import} instead of a fully-qualified class name. Only gameplay
     * surfaces are aliased; there is deliberately no alias for any sealed class, and even a
     * fully-qualified attempt at one is refused by the gate.
     */
    private static final java.util.Map<String, String> ALIASES = new java.util.HashMap<>();
    static {
        ALIASES.put("Entities", "entity.Entity");
        ALIASES.put("Entity", "entity.Entity");
        ALIASES.put("Player", "entity.Player");
        ALIASES.put("Projectile", "entity.Projectile");
        ALIASES.put("Particle", "entity.Particle");
        ALIASES.put("GamePanel", "main.GamePanel");
        ALIASES.put("Config", "main.Config");
        ALIASES.put("Color", "gfx.Color");
        ALIASES.put("InteractiveTile", "tile.interactiveTile");
    }

    /** Resolve an import name (alias or FQN) to a sealed-checked Lua class proxy. */
    public static LuaValue importClass(String name) {
        String fqn = ALIASES.getOrDefault(name, name);
        Class<?> type;
        try {
            type = Class.forName(fqn, true, JavaBridge.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new LuaError("import: no such class '" + name + "' (resolved '" + fqn + "')");
        }
        SecurityGate.assertClassImportable(type);
        return classProxy(type);
    }

    /** Wrap a live Java object as an instance proxy (or nil if it is a sealed value). */
    public static LuaValue instanceProxy(Object target) {
        if (target == null) return LuaValue.NIL;
        if (SecurityGate.isSealedValue(target)) return LuaValue.NIL;
        Class<?> type = target.getClass();
        LuaTable meta = new LuaTable();
        meta.set("__index", new InstanceIndex(target, type));
        meta.set("__newindex", new InstanceNewIndex(target, type));
        LuaTable proxy = new LuaTable();
        // Carry the underlying Java object so this proxy can be passed back into a Java call and
        // unwrapped by LuaConv (see InstanceRegistry). Stored as userdata under a hidden raw key
        // that the metatable's __index never sees (rawget bypasses it).
        proxy.rawset(InstanceRegistry.HANDLE_KEY, org.luaj.vm2.LuaValue.userdataOf(target));
        proxy.setmetatable(meta);
        return proxy;
    }

    // ── Class proxy: static members + new(...) ──

    private static LuaValue classProxy(Class<?> type) {
        LuaTable meta = new LuaTable();
        meta.set("__index", new ClassIndex(type));
        meta.set("__newindex", new ClassNewIndex(type));
        LuaTable proxy = new LuaTable();
        // Constructor: Entities.new(gp) → reflective newInstance through the gate.
        proxy.set("new", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return construct(type, args);
            }
        });
        proxy.set("__javaClass", LuaValue.valueOf(type.getName()));
        proxy.setmetatable(meta);
        return proxy;
    }

    private static Varargs construct(Class<?> type, Varargs args) {
        SecurityGate.assertClassImportable(type);
        // args[1] is the proxy table itself when called as Class.new(...); Lua passes it. We accept
        // both Class.new(a,b) and Class:new(a,b) by skipping a leading table arg.
        Object[] jargs = LuaConv.toJavaArgs(args, isColonCall(args) ? 2 : 1);
        try {
            var ctor = MethodResolver.bestConstructor(type, jargs);
            if (ctor == null) throw new LuaError("no matching constructor for " + type.getName());
            SecurityGate.assertMemberInvokable(ctor);
            ctor.setAccessible(true);
            Object obj = ctor.newInstance(MethodResolver.coerce(ctor.getParameterTypes(), jargs));
            return instanceProxy(obj);
        } catch (LuaError le) {
            throw le;
        } catch (Throwable t) {
            throw new LuaError("constructor error on " + type.getName() + ": " + rootMsg(t));
        }
    }

    private static boolean isColonCall(Varargs args) {
        return args.narg() >= 1 && args.arg1().istable();
    }

    // ── __index / __newindex implementations ──

    private static final class ClassIndex extends VarArgFunction {
        private final Class<?> type;
        ClassIndex(Class<?> type) { this.type = type; }
        @Override public Varargs invoke(Varargs args) {
            String member = args.arg(2).tojstring();
            // Static field?
            Field f = findField(type, member, true);
            if (f != null) return readField(f, null);
            // Otherwise a bound static-method caller.
            return methodCaller(type, null, member);
        }
    }

    private static final class ClassNewIndex extends VarArgFunction {
        private final Class<?> type;
        ClassNewIndex(Class<?> type) { this.type = type; }
        @Override public Varargs invoke(Varargs args) {
            String member = args.arg(2).tojstring();
            LuaValue value = args.arg(3);
            Field f = findField(type, member, true);
            if (f == null) throw new LuaError("no static field '" + member + "' on " + type.getName());
            writeField(f, null, value);
            return LuaValue.NIL;
        }
    }

    private static final class InstanceIndex extends VarArgFunction {
        private final Object target; private final Class<?> type;
        InstanceIndex(Object target, Class<?> type) { this.target = target; this.type = type; }
        @Override public Varargs invoke(Varargs args) {
            String member = args.arg(2).tojstring();
            Field f = findField(type, member, false);
            if (f != null) return readField(f, target);
            return methodCaller(type, target, member);
        }
    }

    private static final class InstanceNewIndex extends VarArgFunction {
        private final Object target; private final Class<?> type;
        InstanceNewIndex(Object target, Class<?> type) { this.target = target; this.type = type; }
        @Override public Varargs invoke(Varargs args) {
            String member = args.arg(2).tojstring();
            LuaValue value = args.arg(3);
            Field f = findField(type, member, false);
            if (f == null) throw new LuaError("no field '" + member + "' on " + type.getName());
            writeField(f, target, value);
            return LuaValue.NIL;
        }
    }

    /** Returns a Lua function that reflectively invokes {@code member} on {@code type}/{@code target}. */
    private static LuaValue methodCaller(Class<?> type, Object target, String member) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                // Support both target:method(a) (target passed as arg1) and target.method(a).
                int start = (args.narg() >= 1 && args.arg1().istable()) ? 2 : 1;
                Object[] jargs = LuaConv.toJavaArgs(args, start);
                Method m = MethodResolver.bestMethod(type, member, jargs, target == null);
                if (m == null) {
                    throw new LuaError("no method '" + member + "' on " + type.getName()
                            + " matching " + jargs.length + " arg(s)");
                }
                SecurityGate.assertMemberInvokable(m);
                SecurityGate.assertNotEscapeHatch(m);
                if (SecurityGate.isSealedType(m.getReturnType())) {
                    throw new LuaError("method '" + member + "' returns a sealed type; denied");
                }
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(target, MethodResolver.coerce(m.getParameterTypes(), jargs));
                    if (SecurityGate.isSealedValue(result)) return LuaValue.NIL;
                    return LuaConv.toLua(result);
                } catch (Throwable t) {
                    throw new LuaError("call '" + member + "' failed: " + rootMsg(t));
                }
            }
        };
    }

    private static LuaValue readField(Field f, Object target) {
        SecurityGate.assertFieldAccess(f);
        SecurityGate.assertNotEscapeHatch(f);
        try {
            f.setAccessible(true);
            Object v = f.get(target);
            if (SecurityGate.isSealedValue(v)) return LuaValue.NIL;
            return LuaConv.toLua(v);
        } catch (Throwable t) {
            throw new LuaError("read field '" + f.getName() + "' failed: " + rootMsg(t));
        }
    }

    private static void writeField(Field f, Object target, LuaValue value) {
        SecurityGate.assertFieldAccess(f);
        SecurityGate.assertNotEscapeHatch(f);
        if (Modifier.isFinal(f.getModifiers())) {
            throw new LuaError("cannot assign final field '" + f.getName() + "'");
        }
        try {
            f.setAccessible(true);
            f.set(target, LuaConv.toJava(value, f.getType()));
        } catch (Throwable t) {
            throw new LuaError("write field '" + f.getName() + "' failed: " + rootMsg(t));
        }
    }

    /** Walk the class hierarchy for a public field of the requested static-ness. */
    private static Field findField(Class<?> type, String name, boolean wantStatic) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers()) == wantStatic) return f;
            } catch (NoSuchFieldException ignored) { /* keep walking */ }
        }
        return null;
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return (m != null ? m : c.getClass().getSimpleName());
    }
}

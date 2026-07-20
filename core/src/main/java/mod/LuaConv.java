package mod;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * Converts values across the Lua↔Java boundary for {@link JavaBridge}.
 *
 * <p>Java → Lua: primitives/strings/booleans become native Lua values; any other object becomes an
 * instance proxy (so a returned {@link entity.Entity} is itself reflectable) — unless it is a sealed
 * value, in which case the proxy layer has already substituted nil. Java arrays and {@link
 * java.util.List}s become Lua sequence tables.
 *
 * <p>Lua → Java: coerces to a requested target type where known, otherwise unwraps to the natural
 * Java type (number → Double/Integer, string → String, boolean → Boolean). Instance proxies passed
 * back from Lua are unwrapped to the underlying Java object.
 */
public final class LuaConv {

    private LuaConv() {}

    /** Convert a batch of Lua call args (1-based, starting at {@code from}) to Java objects. */
    public static Object[] toJavaArgs(Varargs args, int from) {
        int n = Math.max(0, args.narg() - from + 1);
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            out[i] = toJava(args.arg(from + i), null);
        }
        return out;
    }

    /** Java object → Lua value. Non-primitive objects become reflectable instance proxies. */
    public static LuaValue toLua(Object v) {
        if (v == null) return LuaValue.NIL;
        if (v instanceof LuaValue lv) return lv;
        if (v instanceof Boolean b) return LuaValue.valueOf(b);
        if (v instanceof Integer i) return LuaValue.valueOf(i);
        if (v instanceof Long l) return LuaValue.valueOf(l.doubleValue());
        if (v instanceof Short s) return LuaValue.valueOf(s.intValue());
        if (v instanceof Byte by) return LuaValue.valueOf(by.intValue());
        if (v instanceof Float f) return LuaValue.valueOf((double) f);
        if (v instanceof Double d) return LuaValue.valueOf(d);
        if (v instanceof Character c) return LuaValue.valueOf(String.valueOf(c));
        if (v instanceof String s) return LuaValue.valueOf(s);
        if (v instanceof java.util.List<?> list) {
            LuaTable t = new LuaTable();
            for (int i = 0; i < list.size(); i++) t.set(i + 1, toLua(list.get(i)));
            return t;
        }
        if (v.getClass().isArray()) {
            LuaTable t = new LuaTable();
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) t.set(i + 1, toLua(java.lang.reflect.Array.get(v, i)));
            return t;
        }
        // Any other Java object: hand back a reflectable proxy (sealed values become nil there).
        return JavaBridge.instanceProxy(v);
    }

    /**
     * Lua value → Java object, coerced toward {@code target} when it is non-null. When {@code target}
     * is null the natural Java type is produced. Instance proxies carry their underlying object under
     * the hidden {@code __javaObject} key; we unwrap that here.
     */
    public static Object toJava(LuaValue v, Class<?> target) {
        if (v == null || v.isnil()) return null;

        if (v.isboolean()) return coerceNumberOrBool(v.toboolean(), target);
        if (v.isint() && (target == null || target == int.class || target == Integer.class)) {
            return v.toint();
        }
        if (v.isnumber()) return coerceNumber(v.todouble(), target);
        if (v.isstring() && !v.istable()) return v.tojstring();

        if (v.istable()) {
            LuaValue jo = v.rawget("__javaObject");
            if (jo != null && jo.isuserdata()) return jo.touserdata();
            Object unwrapped = InstanceRegistry.unwrap(v);
            if (unwrapped != null) return unwrapped;
            // A plain Lua table (used as a config/def map) is passed through as the LuaValue so the
            // receiving ModApi method can read its keys directly.
            return v;
        }
        if (v.isuserdata()) return v.touserdata();
        return v.tojstring();
    }

    private static Object coerceNumberOrBool(boolean b, Class<?> target) {
        if (target == null || target == boolean.class || target == Boolean.class) return b;
        return b;
    }

    private static Object coerceNumber(double d, Class<?> target) {
        if (target == null) return d;
        if (target == int.class || target == Integer.class) return (int) d;
        if (target == long.class || target == Long.class) return (long) d;
        if (target == short.class || target == Short.class) return (short) d;
        if (target == byte.class || target == Byte.class) return (byte) d;
        if (target == float.class || target == Float.class) return (float) d;
        if (target == double.class || target == Double.class) return d;
        return d;
    }
}

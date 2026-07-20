package mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Chooses which overloaded Java method/constructor a Lua call maps to, and coerces the Lua-derived
 * argument objects to the exact parameter types before invocation. Kept deliberately simple:
 * matching is by arity plus loose assignability (numbers are treated as interchangeable across the
 * numeric primitives/boxes, which is how Lua numbers arrive). {@link JavaBridge} applies the
 * {@link SecurityGate} checks; this class is purely about resolution and coercion.
 */
final class MethodResolver {

    private MethodResolver() {}

    /** Best public method named {@code name} on {@code type} matching the given args, or null. */
    static Method bestMethod(Class<?> type, String name, Object[] args, boolean wantStatic) {
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isStatic(m.getModifiers()) != wantStatic && wantStatic) continue;
            if (m.getParameterCount() != args.length) continue;
            int score = score(m.getParameterTypes(), args);
            if (score > bestScore) { bestScore = score; best = m; }
        }
        return best;
    }

    /** Best public constructor of {@code type} matching the given args, or null. */
    static Constructor<?> bestConstructor(Class<?> type, Object[] args) {
        Constructor<?> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Constructor<?> c : type.getConstructors()) {
            if (c.getParameterCount() != args.length) continue;
            int score = score(c.getParameterTypes(), args);
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    /** Higher is a better fit; Integer.MIN_VALUE means incompatible. */
    private static int score(Class<?>[] params, Object[] args) {
        int total = 0;
        for (int i = 0; i < params.length; i++) {
            int s = scoreOne(params[i], args[i]);
            if (s == Integer.MIN_VALUE) return Integer.MIN_VALUE;
            total += s;
        }
        return total;
    }

    private static int scoreOne(Class<?> param, Object arg) {
        if (arg == null) return param.isPrimitive() ? Integer.MIN_VALUE : 1;
        Class<?> a = arg.getClass();
        if (param.isAssignableFrom(a)) return 3;                 // exact / supertype
        if (isNumeric(param) && arg instanceof Number) return 2; // numeric widening/narrowing
        if ((param == boolean.class || param == Boolean.class) && arg instanceof Boolean) return 3;
        if (param == String.class && arg instanceof String) return 3;
        if (param == char.class && arg instanceof String s && s.length() == 1) return 2;
        return Integer.MIN_VALUE;
    }

    private static boolean isNumeric(Class<?> t) {
        return t == int.class || t == Integer.class || t == long.class || t == Long.class
            || t == short.class || t == Short.class || t == byte.class || t == Byte.class
            || t == float.class || t == Float.class || t == double.class || t == Double.class;
    }

    /** Coerce each arg to the exact parameter type (numbers → the right primitive box). */
    static Object[] coerce(Class<?>[] params, Object[] args) {
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            out[i] = coerceOne(params[i], args[i]);
        }
        return out;
    }

    private static Object coerceOne(Class<?> param, Object arg) {
        if (arg == null) return null;
        if (arg instanceof Number n && isNumeric(param)) {
            if (param == int.class    || param == Integer.class) return n.intValue();
            if (param == long.class   || param == Long.class)    return n.longValue();
            if (param == short.class  || param == Short.class)   return n.shortValue();
            if (param == byte.class   || param == Byte.class)    return n.byteValue();
            if (param == float.class  || param == Float.class)   return n.floatValue();
            if (param == double.class || param == Double.class)  return n.doubleValue();
        }
        if (param == char.class && arg instanceof String s && s.length() == 1) return s.charAt(0);
        return arg;
    }
}

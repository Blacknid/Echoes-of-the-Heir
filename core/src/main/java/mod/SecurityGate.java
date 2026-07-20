package mod;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single source of truth for what a Lua mod is allowed to reach through the reflection bridge
 * (see {@link JavaBridge}). Full reflection is permitted over <em>gameplay</em> classes — entities,
 * the game panel, AI, tiles, objects — but a hard seal blocks everything that touches
 * authentication, the multiplayer/save servers, encrypted saves and cryptographic keys.
 *
 * <h2>Why a name deny-list is not enough</h2>
 * Reflection lets a script walk from a permitted object to a sealed one: {@code gp.saveLoad} would
 * hand back a {@link data.SaveLoad}, whose {@code getCloudSaveService()} exposes the crypto session.
 * So the gate is consulted at <b>four</b> points, not one:
 * <ol>
 *   <li>{@link #assertClassImportable(Class)} — {@code import "X"} of a sealed class is refused.</li>
 *   <li>{@link #assertFieldAccess(Field)} — reading/writing a sealed <em>field</em> is refused, even
 *       on an otherwise-permitted class (e.g. {@code GamePanel.saveLoad}).</li>
 *   <li>{@link #assertMemberInvokable(Member)} — invoking a method on a sealed class is refused.</li>
 *   <li>{@link #isSealedValue(Object)} — any value <em>returned</em> from a permitted call that is
 *       itself a sealed type is neutralized (the bridge returns nil), so a script can never obtain a
 *       live handle to a sealed object and reflect further into it.</li>
 * </ol>
 *
 * <h2>Honest scope</h2>
 * This is <b>soft</b> isolation. A determined attacker who owns the machine owns the JVM and can
 * bypass any in-process guard. The gate's job is to make the auth/save/server/crypto internals
 * genuinely unreachable from <em>well-behaved and casual</em> mods — the realistic threat model for a
 * modding API — and to guarantee nothing a mod does can flow into the save-server upload path or the
 * authoritative simulation (the server never loads mods at all). True hard isolation would need a
 * separate process; that is deliberately out of scope.
 */
public final class SecurityGate {

    private SecurityGate() {}

    /**
     * Fully-qualified class names that a mod may never import, invoke, or receive. These are the
     * auth / multiplayer-server / save-server / crypto surfaces. Matched exactly AND as an
     * assignability check (a subclass of any of these is sealed too, see {@link #isSealedType}).
     */
    private static final Set<String> SEALED_CLASSES = new HashSet<>(Arrays.asList(
            // Save-server client + on-disk crypto.
            "data.CloudSaveService",
            "data.SaveLoad",
            // Multiplayer / networking session + peer discovery.
            "main.MultiplayerClient",
            "main.BleMultiplayerSession",
            "main.MpMapStreamer",
            "main.ServerListManager",
            "main.FriendsListManager",
            // License / authentication holder.
            "main.Main"
    ));

    /**
     * Package prefixes that are sealed wholesale. Anything whose class name starts with one of these
     * (plus a dot) is refused. Covers the authoritative server, the auth/license/transport platform
     * layer, and the raw JDK capabilities a mod has no business touching (crypto, security,
     * networking, reflection-of-reflection, process spawning, classloading).
     */
    private static final List<String> SEALED_PACKAGES = Arrays.asList(
            "server.",                 // authoritative headless engine
            "platform.",               // License*, ItchAuthProvider, Ble*, Nfc*, GameStorage raw I/O
            "javax.crypto.",
            "java.security.",
            "java.net.",
            "java.lang.reflect.",      // no reflecting on reflection to escape the gate
            "java.lang.invoke.",
            "sun.",
            "jdk.internal."
    );

    /**
     * Specific JDK classes that are sealed even though their package prefix is otherwise allowed
     * (e.g. we don't seal all of {@code java.lang}, only the dangerous members of it).
     */
    private static final Set<String> SEALED_JDK_CLASSES = new HashSet<>(Arrays.asList(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.lang.System",           // System.exit / env / props / load — use ModApi instead
            "java.lang.ClassLoader",
            "java.lang.Thread",
            "java.io.File",               // mods persist via the scoped ModStorage, not raw files
            "java.io.FileOutputStream",
            "java.io.FileInputStream",
            "java.io.RandomAccessFile"
    ));

    /**
     * Individual fields, keyed {@code "declaringClassName#fieldName"}, that are sealed even though
     * their declaring class is a permitted gameplay class. This is the reachability seal: a mod may
     * freely reflect over {@link main.GamePanel}, but the fields that hold sealed objects are opaque
     * so it can never traverse into them.
     */
    private static final Set<String> SEALED_FIELDS = new HashSet<>(Arrays.asList(
            "main.GamePanel#saveLoad",
            "main.GamePanel#mpClient",
            "main.GamePanel#serverList",
            "main.GamePanel#friendsListManager",
            "main.GamePanel#bleSession"
    ));

    /** True if {@code name} names a sealed class, either exactly or by sealed-package prefix. */
    private static boolean isSealedName(String name) {
        if (name == null) return false;
        if (SEALED_CLASSES.contains(name) || SEALED_JDK_CLASSES.contains(name)) return true;
        for (String pkg : SEALED_PACKAGES) {
            if (name.startsWith(pkg)) return true;
        }
        return false;
    }

    /**
     * True if {@code type} is sealed — by its own name, or because it is assignable to any sealed
     * class/interface (so a subclass or implementation of a sealed type cannot slip through under a
     * different name). Arrays are unwrapped to their component type.
     */
    public static boolean isSealedType(Class<?> type) {
        if (type == null) return false;
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive()) return false;
        if (isSealedName(type.getName())) return true;
        // Assignability check against the named sealed classes (catches subclasses / anon classes).
        for (String sealed : SEALED_CLASSES) {
            Class<?> sc = tryLoad(sealed);
            if (sc != null && sc.isAssignableFrom(type)) return true;
        }
        return false;
    }

    private static Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, SecurityGate.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    // ── The four gate checks used by JavaBridge ──

    /** Refuse {@code import "X"} of a sealed class. */
    public static void assertClassImportable(Class<?> type) {
        if (isSealedType(type)) {
            throw new ModSecurityException("import of sealed class denied: " + type.getName()
                    + " (auth/save/server/crypto surfaces are not accessible to mods)");
        }
    }

    /** Refuse read/write of a sealed field, or any field whose type is a sealed object. */
    public static void assertFieldAccess(Field f) {
        if (f == null) return;
        String key = f.getDeclaringClass().getName() + "#" + f.getName();
        if (SEALED_FIELDS.contains(key)) {
            throw new ModSecurityException("access to sealed field denied: " + key);
        }
        if (isSealedType(f.getDeclaringClass())) {
            throw new ModSecurityException("field on sealed class denied: " + key);
        }
        if (isSealedType(f.getType())) {
            throw new ModSecurityException("field returns a sealed type, access denied: " + key);
        }
    }

    /** Refuse invoking a member (method/constructor) declared on a sealed class. */
    public static void assertMemberInvokable(Member m) {
        if (m == null) return;
        if (isSealedType(m.getDeclaringClass())) {
            throw new ModSecurityException("invocation on sealed class denied: "
                    + m.getDeclaringClass().getName() + "#" + m.getName());
        }
    }

    /**
     * True if {@code value} is a live instance of a sealed type. The bridge calls this on every
     * return value and every field read so a permitted call that happens to hand back a sealed
     * object (e.g. a getter) is neutralized to nil rather than exposed for further reflection.
     */
    public static boolean isSealedValue(Object value) {
        return value != null && isSealedType(value.getClass());
    }

    /**
     * Defensive extra: refuse access to synthetic/bridge members and to {@code Class}/{@code
     * ClassLoader}-returning accessors that could be used to bootstrap around the gate. Kept separate
     * so the bridge can call it as a belt-and-braces check on any resolved member.
     */
    public static void assertNotEscapeHatch(Member m) {
        if (m == null) return;
        if (m instanceof Field f) {
            Class<?> t = f.getType();
            if (Class.class.isAssignableFrom(t) || ClassLoader.class.isAssignableFrom(t)) {
                throw new ModSecurityException("field exposing Class/ClassLoader denied: " + f.getName());
            }
        }
        // No synthetic access to private state via generated accessor methods.
        if (m.isSynthetic() && !Modifier.isPublic(m.getModifiers())) {
            throw new ModSecurityException("synthetic non-public member denied: " + m.getName());
        }
    }
}

package mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * Named hook points the engine fires into. A mod registers Lua callbacks with {@code ModApi.on(event,
 * fn)} and the engine calls {@link #fire} at the matching integration point (e.g. once per fixed
 * update tick, when a breakable is destroyed, when a map loads). This is the "event callbacks" half
 * of the declarative-data + callbacks hook model.
 *
 * <p>Every callback is invoked defensively: a mod that throws is logged and, after too many
 * failures, disabled for that event, so a broken mod can never crash or stall the fixed-timestep
 * game loop. Firing when no callbacks are registered (the vanilla case) is a cheap no-op.
 */
public final class ModEventBus {

    private ModEventBus() {}

    /** Canonical event names the engine fires. Mods may also use custom names for their own dispatch. */
    public static final String UPDATE       = "update";       // once per fixed sim tick (play states)
    public static final String ENTITY_SPAWN = "entitySpawn";  // an entity was spawned
    public static final String BREAK        = "break";        // a breakable object/tile was destroyed
    public static final String MAP_LOAD     = "mapLoad";      // a map finished loading

    private static final Map<String, List<Handler>> HANDLERS = new LinkedHashMap<>();
    private static final int MAX_FAILURES = 8; // disable a handler after this many throws

    private static final class Handler {
        final String modId;
        final LuaValue fn;
        int failures = 0;
        boolean disabled = false;
        Handler(String modId, LuaValue fn) { this.modId = modId; this.fn = fn; }
    }

    /** Register a callback for {@code event}, attributed to {@code modId}. */
    public static void on(String modId, String event, LuaValue fn) {
        if (fn == null || !fn.isfunction()) {
            throw new LuaError("ModApi.on: second argument must be a function");
        }
        HANDLERS.computeIfAbsent(event, k -> new ArrayList<>()).add(new Handler(modId, fn));
    }

    /** True if anything is listening for {@code event} — lets hot paths skip arg conversion. */
    public static boolean has(String event) {
        List<Handler> hs = HANDLERS.get(event);
        return hs != null && !hs.isEmpty();
    }

    /**
     * Fire {@code event}, passing {@code args} (already Lua values) to every registered handler.
     * Exceptions from a handler are caught, logged and counted; a repeatedly-failing handler is
     * disabled so it cannot keep costing the loop. Never throws.
     */
    public static void fire(String event, LuaValue... args) {
        List<Handler> hs = HANDLERS.get(event);
        if (hs == null || hs.isEmpty()) return;
        for (Handler h : hs) {
            if (h.disabled) continue;
            try {
                switch (args.length) {
                    case 0  -> h.fn.call();
                    case 1  -> h.fn.call(args[0]);
                    case 2  -> h.fn.call(args[0], args[1]);
                    default -> h.fn.invoke(LuaValue.varargsOf(args));
                }
            } catch (Throwable t) {
                h.failures++;
                System.out.println("[mod:" + h.modId + "] error in '" + event + "' handler: "
                        + t.getMessage());
                if (h.failures >= MAX_FAILURES) {
                    h.disabled = true;
                    System.out.println("[mod:" + h.modId + "] disabled '" + event
                            + "' handler after repeated errors");
                }
            }
        }
    }

    /** Remove all handlers (used when reloading mods). */
    public static void clear() { HANDLERS.clear(); }
}

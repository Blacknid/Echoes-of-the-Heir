package mod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where mod-declared content lands so the existing data-driven factories can consult it. The base
 * game's factories (MonsterFactory, ItemFactory, NPCFactory) read their own bundled JSON; each is
 * given a tiny hook to check this registry FIRST, so a mod can add a brand-new monster/item id or
 * override an existing one, with zero change to how vanilla content loads.
 *
 * <p>Definitions are stored in the same flat {@code Map<String,String>} shape the factories already
 * parse their JSON into (e.g. {@code "maxLife" -> "12"}, {@code "aiBehavior" -> "ranged_archer"}),
 * so the factory build path is reused verbatim — a mod monster is built by exactly the same code as
 * a vanilla one. Sprite paths may point under a mod's own folder (e.g. {@code mods/foo/sprites/x.png}).
 *
 * <p>Empty in a vanilla install: with no mods present nothing ever registers here and every factory
 * lookup falls straight through to the bundled JSON, so behaviour is byte-for-byte unchanged.
 */
public final class ModContentRegistry {

    private ModContentRegistry() {}

    private static final Map<String, Map<String, String>> MONSTERS = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> ITEMS    = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> NPCS     = new LinkedHashMap<>();

    /** Custom AI behaviours registered by mods, keyed by the {@code aiBehavior} string. */
    private static final Map<String, org.luaj.vm2.LuaValue> AI = new LinkedHashMap<>();

    // ── Registration (called from ModApi) ──

    public static void registerMonster(String id, Map<String, String> def) { MONSTERS.put(id, def); }
    public static void registerItem(String id, Map<String, String> def)    { ITEMS.put(id, def); }
    public static void registerNpc(String id, Map<String, String> def)     { NPCS.put(id, def); }
    public static void registerAi(String behavior, org.luaj.vm2.LuaValue fn){ AI.put(behavior, fn); }

    // ── Factory-side lookups ──

    public static boolean hasMonster(String id) { return MONSTERS.containsKey(id); }
    public static Map<String, String> monster(String id) { return MONSTERS.get(id); }
    public static Iterable<String> monsterIds() { return MONSTERS.keySet(); }

    public static boolean hasItem(String id) { return ITEMS.containsKey(id); }
    public static Map<String, String> item(String id) { return ITEMS.get(id); }

    public static boolean hasNpc(String id) { return NPCS.containsKey(id); }
    public static Map<String, String> npc(String id) { return NPCS.get(id); }

    public static boolean hasAi(String behavior) { return AI.containsKey(behavior); }
    public static org.luaj.vm2.LuaValue ai(String behavior) { return AI.get(behavior); }

    /** Wipe everything (used when reloading mods, e.g. on returning to the title in dev). */
    public static void clear() {
        MONSTERS.clear();
        ITEMS.clear();
        NPCS.clear();
        AI.clear();
    }
}

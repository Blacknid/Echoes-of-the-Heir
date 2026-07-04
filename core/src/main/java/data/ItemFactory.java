package data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import main.GamePanel;

/**
 * Creates item entities from JSON definitions in res/data/items.json.
 * Simple stat-based items (weapons, shields, buffs, consumables) are fully data-driven.
 * Complex items with special behavior (Door, Chest, Tower, Key, Gem, etc.) keep Java classes.
 */
public class ItemFactory {

    private static final ArrayList<Map<String, String>> itemDefs = new ArrayList<>();
    private static boolean loaded = false;

    // Map JSON type names to Entity type constants
    private static final Map<String, Integer> TYPE_MAP = new HashMap<>();
    static {
        TYPE_MAP.put("sword", 3);      // type_sword
        TYPE_MAP.put("book", 4);       // type_book
        TYPE_MAP.put("shield", 5);     // type_shield
        TYPE_MAP.put("consumable", 6); // type_consumable
        TYPE_MAP.put("pickupOnly", 7); // type_pickupOnly
        TYPE_MAP.put("obstacle", 8);   // type_obstacle
        TYPE_MAP.put("buffs", 9);      // type_buffs
        TYPE_MAP.put("ending", 10);    // type_ending
    }

    /** Load item definitions from JSON resource. Call once at startup. */
    public static void loadDefinitions() {
        if (loaded) return;
        loaded = true;

        try (InputStream is = util.ResourceCache.openClasspathStream("/res/data/items.json")) {
            if (is == null) {
                System.out.println("[ItemFactory] items.json not found");
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseJsonArray(sb.toString());
            System.out.println("[ItemFactory] Loaded " + itemDefs.size() + " item definitions");
        } catch (Exception e) {
            System.out.println("[ItemFactory] Error loading items.json: " + e.getMessage());
        }
    }

    /** Create an item entity by its JSON id. Returns null if id not found. */
    public static Entity create(GamePanel gp, String id) {
        if (!loaded) loadDefinitions();

        for (Map<String, String> def : itemDefs) {
            if (id.equals(def.get("id"))) {
                return buildItem(gp, def);
            }
        }
        return null;
    }

    /** Get all registered item definition IDs. */
    public static ArrayList<String> getRegisteredIds() {
        if (!loaded) loadDefinitions();
        ArrayList<String> ids = new ArrayList<>();
        for (Map<String, String> def : itemDefs) {
            ids.add(def.get("id"));
        }
        return ids;
    }

    private static Entity buildItem(GamePanel gp, Map<String, String> def) {
        Entity item = new Entity(gp);

        String typeName = def.getOrDefault("type", "pickupOnly");
        item.type = TYPE_MAP.getOrDefault(typeName, 7);

        item.itemId = def.get("id");
        item.name = def.getOrDefault("name", "Item");
        item.description = def.getOrDefault("description", "");
        item.stackable = "true".equals(def.get("stackable"));
        item.attackValue = intVal(def, "attackValue", 0);
        item.defenseValue = intVal(def, "defenseValue", 0);
        item.knockBackPower = intVal(def, "knockBackPower", 0);
        item.useCost = intVal(def, "useCost", 0);

        int aw = intVal(def, "attackAreaW", 0);
        int ah = intVal(def, "attackAreaH", 0);
        if (aw > 0 && ah > 0) {
            item.attackArea.width = aw;
            item.attackArea.height = ah;
        }

        item.solidArea.x = intVal(def, "solidArea.x", 16);
        item.solidArea.y = intVal(def, "solidArea.y", 16);
        item.solidArea.width = intVal(def, "solidArea.w", 32);
        item.solidArea.height = intVal(def, "solidArea.h", 32);
        item.solidAreaDefaultX = item.solidArea.x;
        item.solidAreaDefaultY = item.solidArea.y;

        item.collision = "true".equals(def.get("collision"));

        if ("true".equals(def.get("lightSource"))) {
            item.lightSource = true;
            item.lightRadius = intVal(def, "lightRadius", 6);
        }

        String sprite = def.get("sprite");
        if (sprite != null) {
            item.down1 = item.setup(sprite, gp.tileSize, gp.tileSize);
        }

        return item;
    }

    // --- Parsing utilities (same pattern as MonsterFactory) ---

    private static int intVal(Map<String, String> m, String key, int fallback) {
        String v = m.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static void parseJsonArray(String json) {
        itemDefs.clear();
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return;
        json = json.substring(1, json.length() - 1).trim();

        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    parseObject(json.substring(start + 1, i));
                    start = -1;
                }
            }
        }
    }

    private static void parseObject(String obj) {
        Map<String, String> map = new HashMap<>();
        // Handle nested solidArea object by flattening
        int saStart = obj.indexOf("\"solidArea\"");
        if (saStart >= 0) {
            int braceStart = obj.indexOf('{', saStart);
            int braceEnd = obj.indexOf('}', braceStart);
            if (braceStart >= 0 && braceEnd >= 0) {
                String inner = obj.substring(braceStart + 1, braceEnd);
                parseKeyValues(inner, "solidArea.", map);
                obj = obj.substring(0, saStart) + obj.substring(braceEnd + 1);
            }
        }
        parseKeyValues(obj, "", map);
        if (map.containsKey("id")) {
            itemDefs.add(map);
        }
    }

    private static void parseKeyValues(String text, String prefix, Map<String, String> map) {
        int i = 0;
        while (i < text.length()) {
            int keyStart = text.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = text.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = text.substring(keyStart + 1, keyEnd);

            int colon = text.indexOf(':', keyEnd);
            if (colon < 0) break;

            int valStart = colon + 1;
            while (valStart < text.length() && text.charAt(valStart) == ' ') valStart++;

            String value;
            if (valStart < text.length() && text.charAt(valStart) == '"') {
                int valEnd = valStart + 1;
                while (valEnd < text.length()) {
                    char vc = text.charAt(valEnd);
                    if (vc == '\\') { valEnd += 2; continue; }
                    if (vc == '"') break;
                    valEnd++;
                }
                if (valEnd >= text.length()) break;
                value = text.substring(valStart + 1, valEnd)
                        .replace("\\n", "\n").replace("\\t", "\t")
                        .replace("\\r", "\r").replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                i = valEnd + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < text.length() && text.charAt(valEnd) != ','
                       && text.charAt(valEnd) != '}' && text.charAt(valEnd) != ']') {
                    valEnd++;
                }
                value = text.substring(valStart, valEnd).trim();
                i = valEnd + 1;
            }
            map.put(prefix + key, value);
        }
    }
}

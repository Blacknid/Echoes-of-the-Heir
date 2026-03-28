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
 * Creates monster entities from JSON definitions in res/data/monsters.json.
 * Adding a new monster = add a JSON entry + spritesheet image, zero Java code.
 */
public class MonsterFactory {

    private static final ArrayList<Map<String, String>> monsterDefs = new ArrayList<>();
    private static boolean loaded = false;

    /** Load monster definitions from JSON resource. Call once at startup. */
    public static void loadDefinitions() {
        if (loaded) return;
        loaded = true;

        try (InputStream is = MonsterFactory.class.getResourceAsStream("/res/data/monsters.json")) {
            if (is == null) {
                System.out.println("[MonsterFactory] monsters.json not found, using hardcoded fallback");
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseJsonArray(sb.toString());
            System.out.println("[MonsterFactory] Loaded " + monsterDefs.size() + " monster definitions");
        } catch (Exception e) {
            System.out.println("[MonsterFactory] Error loading monsters.json: " + e.getMessage());
        }
    }

    /** Create a monster entity by its JSON id. Returns null if id not found. */
    public static Entity create(GamePanel gp, String id, int col, int row) {
        if (!loaded) loadDefinitions();

        for (Map<String, String> def : monsterDefs) {
            if (id.equals(def.get("id"))) {
                return buildMonster(gp, def, col, row);
            }
        }
        return null;
    }

    /** Get all registered monster definition IDs. */
    public static ArrayList<String> getRegisteredIds() {
        if (!loaded) loadDefinitions();
        ArrayList<String> ids = new ArrayList<>();
        for (Map<String, String> def : monsterDefs) {
            ids.add(def.get("id"));
        }
        return ids;
    }

    private static Entity buildMonster(GamePanel gp, Map<String, String> def, int col, int row) {
        Entity m = new Entity(gp);
        m.type = Entity.type_monster;
        m.collision = true;
        m.worldX = col * gp.tileSize;
        m.worldY = row * gp.tileSize;

        m.name = def.getOrDefault("name", "Monster");
        m.maxLife = intVal(def, "maxLife", 6);
        m.life = m.maxLife;
        m.attack = intVal(def, "attack", 2);
        m.defense = intVal(def, "defense", 0);
        m.exp = intVal(def, "exp", 3);
        m.defaultSpeed = intVal(def, "speed", 1);
        m.speed = m.defaultSpeed;
        m.walkFrameCount = intVal(def, "walkFrameCount", 6);
        m.aggroRange = intVal(def, "aggroRange", 6) * gp.tileSize;
        m.fleeDuration = intVal(def, "fleeDuration", 60);

        // Solid area
        m.solidArea.x = intVal(def, "solidArea.x", 12);
        m.solidArea.y = intVal(def, "solidArea.y", 8);
        m.solidArea.width = intVal(def, "solidArea.width", 40);
        m.solidArea.height = intVal(def, "solidArea.height", 48);
        m.solidAreaDefaultX = m.solidArea.x;
        m.solidAreaDefaultY = m.solidArea.y;

        // Sprites
        String sheet = def.get("spriteSheet");
        if (sheet != null) {
            String fprStr = def.getOrDefault("framesPerRow", "6,6,6,6");
            String[] parts = fprStr.split(",");
            int[] fpr = new int[parts.length];
            for (int i = 0; i < parts.length; i++) fpr[i] = Integer.parseInt(parts[i].trim());
            m.walkFrames = m.loadSheetVariable(sheet, fpr);
        }

        // Projectile for ranged monsters
        String projType = def.get("projectileType");
        if ("arrow".equals(projType)) {
            m.projectile = new object.OBJ_Arrow(gp);
        }

        // Attach AI behavior
        String aiBehavior = def.getOrDefault("aiBehavior", "melee_chase");
        float fleeThreshold = floatVal(def, "fleeThreshold", 0.5f);
        int shootCooldown = intVal(def, "shootCooldown", 90);
        int preferredDist = intVal(def, "preferredDist", 5);
        int fleeDist = intVal(def, "fleeDist", 2);

        // Store AI config in a behavior wrapper that overrides setAction/damageReaction
        return new DataDrivenMonster(gp, m, aiBehavior, fleeThreshold, shootCooldown, preferredDist, fleeDist);
    }

    // --- Minimal JSON parsing (flat array of objects with nested solidArea) ---

    private static void parseJsonArray(String json) {
        monsterDefs.clear();
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
        // Handle framesPerRow array by joining as comma string
        int fpStart = obj.indexOf("\"framesPerRow\"");
        if (fpStart >= 0) {
            int bracketStart = obj.indexOf('[', fpStart);
            int bracketEnd = obj.indexOf(']', bracketStart);
            if (bracketStart >= 0 && bracketEnd >= 0) {
                String inner = obj.substring(bracketStart + 1, bracketEnd).replaceAll("\\s+", "");
                map.put("framesPerRow", inner);
                obj = obj.substring(0, fpStart) + obj.substring(bracketEnd + 1);
            }
        }
        parseKeyValues(obj, "", map);
        if (map.containsKey("id")) {
            monsterDefs.add(map);
        }
    }

    private static void parseKeyValues(String text, String prefix, Map<String, String> map) {
        // Simple key:"value" or key:number parser
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
                int valEnd = text.indexOf('"', valStart + 1);
                if (valEnd < 0) break;
                value = text.substring(valStart + 1, valEnd);
                i = valEnd + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < text.length() && text.charAt(valEnd) != ',' && text.charAt(valEnd) != '}' && text.charAt(valEnd) != ']') {
                    valEnd++;
                }
                value = text.substring(valStart, valEnd).trim();
                i = valEnd;
            }

            map.put(prefix + key, value);
            i = Math.max(i, valStart + 1);
        }
    }

    private static int intVal(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static float floatVal(Map<String, String> m, String key, float def) {
        String v = m.get(key);
        if (v == null) return def;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return def; }
    }
}

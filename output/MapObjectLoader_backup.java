package main;

import entity.Entity;
import entity.NPC_Alucard;
import monster.MON_SkeletonArcher;
import monster.MON_monster;
import object.*;
import tiles_interactive.IT_Coins;
import tiles_interactive.IT_Pot;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Full Tiled-to-Game pipeline: loads objects, monsters, NPCs, interactive tiles,
 * events, and map-level properties from TMX files.
 *
 * â”€â”€â”€ FEATURES â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 *  Map-level properties (set in Tiled â†’ Map â†’ Properties):
 *    mapName       (String)  display name shown in UI
 *    music         (String)  "theme" | integer SFX index
 *    weather       (String)  CLEAR | RAIN | STORM | SNOW
 *    ambientLight  (float)   0.0 (bright) â€“ 0.95 (very dark)
 *    lightLevel    (int)     0=day 1=dusk 2=night 3=dawn
 *    spawnCol/Row  (int)     default map spawn tile
 *    bgColor       (String)  "#rrggbb" background clear color
 *
 *  Entity properties (custom Tiled properties on any object):
 *    facing        (String)  up|down|left|right
 *    id            (String)  persistent object ID for save state
 *    invisible     (bool)    skip draw (for hidden triggers)
 *
 *  Monster properties:
 *    level         (int)     scales HP (Ã—1.5/level) and ATK (Ã—1.25/level)
 *    aggroRange    (int)     aggro detection radius in pixels
 *    wanderRadius  (int)     max idle-wander offset in pixels
 *
 *  NPC properties:
 *    dialogue0â€“4   (String)  override first dialogue line per set  (\\n = newline)
 *    wanderRadius  (int)     max idle-wander offset in pixels
 *
 *  Object properties:
 *    amount        (int)     stack count for Potion/Key/Arrow/Coins
 *    opened        (bool)    Chest starts opened
 *    lightRadius   (int)     Torch custom light radius
 *    lightColor    (String)  Torch "#rrggbb" light tint
 *    spawnId       (String)  Door named spawn point on target map
 *    spawnDirection(String)  Door: direction player faces on arrival
 *
 *  Event types (Events objectgroup):
 *    MapTransition   targetMap, targetCol, targetRow, spawnId
 *    HealingPool     (no extra properties)
 *    DamageTrap      damage (int), repeatable (bool, default true)
 *    DialogueTrigger message (String), speaker (String), oneShot (bool)
 *    LevelGate       minLevel (int), message (String),
 *                    targetMap/targetCol/targetRow (if passable above minLevel)
 *    Checkpoint      silent (bool)
 *    QuestTrigger    questId (String), progress (int), oneShot (bool)
 *    SpawnPoint      id (String) â€” named spawn location referenced by spawnId
 *    CameraShake     intensity: "light"|"medium"|"heavy"
 *
 *  MonsterArea (in Monsters layer, rectangle object):
 *    monster (String)  entity type to spawn
 *    count   (int)     how many to spawn randomly in the area
 *
 *  Type detection: reads both Tiled 1.8 "type" and Tiled 1.9+ "class" attribute.
 *  Rectangle events: area-based events (MapTransition/HealingPool/DamageTrap) cover
 *  every tile inside the object rectangle automatically.
 */
public class MapObjectLoader {

    // Music name â†’ SFX index (extend when you add new music tracks to SFX.java)
    private static final Map<String, Integer> MUSIC_MAP = new HashMap<>();
    static {
        MUSIC_MAP.put("theme",   SFX.MUSIC_THEME);
        MUSIC_MAP.put("dungeon", SFX.MUSIC_THEME); // add new SFX constants for more tracks
        MUSIC_MAP.put("boss",    SFX.MUSIC_THEME);
    }

    private final GamePanel gp;

    public MapObjectLoader(GamePanel gp) {
        this.gp = gp;
    }

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Read the {@code <properties>} block on the {@code <map>} element and apply
     * map-level settings: music, weather, ambient light, name, spawn, bg color.
     * Called automatically from GamePanel.changeMap() after loading tile layers.
     */
    public void loadMapProperties(String tmxPath) {
        try {
            Document doc = parseXmlResource(tmxPath);
            if (doc == null) return;
            applyMapProperties(doc.getDocumentElement());
        } catch (Exception e) {
            System.out.println("MapObjectLoader: Failed to load map properties from " + tmxPath);
            e.printStackTrace(System.out);
        }
    }

    /** Parse entity objectgroups (Objects, Monsters, NPCs, InteractiveTiles). */
    public void loadEntities(String tmxPath) {
        load(tmxPath, true, false);
    }

    /** Parse only the Events objectgroup (MapTransitions, HealingPools, etc.). */
    public void loadEvents(String tmxPath) {
        load(tmxPath, false, true);
    }

    // â”€â”€ Core Loader â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    }

    /**
     * Parse entity objectgroups (Objects, Monsters, NPCs, InteractiveTiles) from a TMX file.
     * Does NOT load Events â€” use loadEvents() for that.
     */
    public void loadEntities(String tmxPath) {
        load(tmxPath, true, false);
    }

    /**
     * Parse only the Events objectgroup (MapTransitions, HealingPools) from a TMX file.
     */
    public void loadEvents(String tmxPath) {
        load(tmxPath, false, true);
    }

    private void load(String tmxPath, boolean entities, boolean events) {
        try {
            Document doc = parseXmlResource(tmxPath);
            if (doc == null) {
                System.out.println("MapObjectLoader: TMX not found: " + tmxPath);
                return;
            }

            // Read map tile size for coordinate conversion
            Element mapEl = doc.getDocumentElement();
            int mapTileSize = 32; // default
            String tw = mapEl.getAttribute("tilewidth");
            if (tw != null && !tw.isEmpty()) {
                mapTileSize = Integer.parseInt(tw);
            }

            double sf = (double) gp.tileSize / mapTileSize;

            // Check for infinite map offsets (chunks shift coordinates)
            int offsetX = 0, offsetY = 0;
            String infinite = mapEl.getAttribute("infinite");
            if ("1".equals(infinite)) {
                // Find minimum chunk offsets across all layers
                NodeList chunks = doc.getElementsByTagName("chunk");
                int minCX = 0, minCY = 0;
                for (int i = 0; i < chunks.getLength(); i++) {
                    Element chunk = (Element) chunks.item(i);
                    int cx = Integer.parseInt(chunk.getAttribute("x"));
                    int cy = Integer.parseInt(chunk.getAttribute("y"));
                    if (cx < minCX) minCX = cx;
                    if (cy < minCY) minCY = cy;
                }
                offsetX = -minCX * gp.tileSize;
                offsetY = -minCY * gp.tileSize;
            }

            // Track array indices
            int objIdx = 0, monIdx = 0, npcIdx = 0, iTileIdx = 0;

            // Count existing entities (don't overwrite non-null slots)
            for (int i = 0; i < gp.obj.length; i++) { if (gp.obj[i] != null) objIdx = i + 1; }
            for (int i = 0; i < gp.monster.length; i++) { if (gp.monster[i] != null) monIdx = i + 1; }
            for (int i = 0; i < gp.npc.length; i++) { if (gp.npc[i] != null) npcIdx = i + 1; }
            for (int i = 0; i < gp.iTile.length; i++) { if (gp.iTile[i] != null) iTileIdx = i + 1; }

            NodeList objectGroups = doc.getElementsByTagName("objectgroup");
            for (int g = 0; g < objectGroups.getLength(); g++) {
                Element og = (Element) objectGroups.item(g);
                String layerName = og.getAttribute("name");

                // Skip collision â€” TileManager handles that
                if ("Collision".equals(layerName)) continue;

                NodeList objects = og.getElementsByTagName("object");
                for (int j = 0; j < objects.getLength(); j++) {
                    Element obj = (Element) objects.item(j);

                    // Read type from custom property, fall back to object name attribute
                    String type = getStringProperty(obj, "type", "");
                    if (type.isEmpty()) {
                        type = obj.getAttribute("name");
                    }
                    if (type == null || type.isEmpty()) {
                        System.out.println("MapObjectLoader: Skipping unnamed object in layer '" + layerName + "'");
                        continue;
                    }

                    // Parse pixel position from TMX
                    String xAttr = obj.getAttribute("x");
                    String yAttr = obj.getAttribute("y");
                    if (xAttr.isEmpty() || yAttr.isEmpty()) continue;

                    double pixelX = Double.parseDouble(xAttr);
                    double pixelY = Double.parseDouble(yAttr);

                    // Convert to tile coordinates
                    int col = (int)(pixelX / mapTileSize);
                    int row = (int)(pixelY / mapTileSize);

                    // Convert to world coordinates
                    int worldX = (int)(pixelX * sf) + offsetX;
                    int worldY = (int)(pixelY * sf) + offsetY;

                    switch (layerName) {
                        case "Objects" -> {
                            if (!entities) continue;
                            if (objIdx >= gp.obj.length) {
                                System.out.println("MapObjectLoader WARNING: obj[] full, skipping " + type);
                                continue;
                            }
                            Entity entity = createObject(type, obj);
                            if (entity != null) {
                                entity.worldX = worldX;
                                entity.worldY = worldY;
                                gp.obj[objIdx++] = entity;
                            }
                        }
                        case "Monsters" -> {
                            if (!entities) continue;
                            if (monIdx >= gp.monster.length) {
                                System.out.println("MapObjectLoader WARNING: monster[] full, skipping " + type);
                                continue;
                            }
                            Entity monster = createMonster(type, col, row);
                            if (monster != null) {
                                gp.monster[monIdx++] = monster;
                            }
                        }
                        case "NPCs" -> {
                            if (!entities) continue;
                            if (npcIdx >= gp.npc.length) {
                                System.out.println("MapObjectLoader WARNING: npc[] full, skipping " + type);
                                continue;
                            }
                            Entity npc = createNPC(type);
                            if (npc != null) {
                                npc.worldX = worldX;
                                npc.worldY = worldY;
                                gp.npc[npcIdx++] = npc;
                            }
                        }
                        case "InteractiveTiles" -> {
                            if (!entities) continue;
                            if (iTileIdx >= gp.iTile.length) {
                                System.out.println("MapObjectLoader WARNING: iTile[] full, skipping " + type);
                                continue;
                            }
                            tiles_interactive.interactiveTile tile = createInteractiveTile(type, col, row);
                            if (tile != null) {
                                gp.iTile[iTileIdx++] = tile;
                            }
                        }
                        case "Events" -> {
                            loadEvent(type, obj, col, row);
                        }
                    }
                }
            }

            // After all objects are loaded, spawn tower eyes
            spawnTowerEyes();

            System.out.println("MapObjectLoader: Loaded from " + tmxPath
                + " â€” obj:" + objIdx + " mon:" + monIdx
                + " npc:" + npcIdx + " iTile:" + iTileIdx);

        } catch (Exception e) {
            System.out.println("MapObjectLoader: Failed to load " + tmxPath);
            e.printStackTrace(System.out);
        }
    }

    // â”€â”€ Entity Factories â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Entity createObject(String type, Element obj) {
        switch (type) {
            case "Chest" -> {
                OBJ_Chest chest = new OBJ_Chest(gp);
                String loot = getStringProperty(obj, "loot", "");
                if (!loot.isEmpty()) {
                    chest.setLoot(createLootByName(loot));
                }
                return chest;
            }
            case "Door" -> {
                OBJ_Door door = new OBJ_Door(gp);
                String dest = getStringProperty(obj, "destination", "");
                if (!dest.isEmpty()) {
                    int destCol = getIntProperty(obj, "destCol", 5);
                    int destRow = getIntProperty(obj, "destRow", 7);
                    boolean locked = getBoolProperty(obj, "isLocked", false);
                    door.setDestination(dest, destCol, destRow, locked);
                }
                return door;
            }
            case "Tent"    -> { return new OBJ_Tent(gp); }
            case "Boots"   -> { return new OBJ_Boots(gp); }
            case "Potion"  -> { return new OBJ_Potion(gp); }
            case "Torch"   -> { return new OBJ_Torch(gp); }
            case "Key"     -> { return new OBJ_Key(gp); }
            case "Gem"     -> { return new OBJ_Gem(gp); }
            case "Book"    -> { return new OBJ_Book(gp); }
            case "Tower"   -> { return new OBJ_Tower(gp); }
            case "Sword"   -> { return new OBJ_Sword_Normal(gp); }
            case "Shield"  -> { return new OBJ_Shield_Wood(gp); }
            case "Coins"   -> { return new OBJ_Coins(gp); }
            case "Heart"   -> { return new OBJ_Heart(gp); }
            case "Mana"    -> { return new OBJ_ManaCrystal(gp); }
            case "Arrow"   -> { return new OBJ_Arrow(gp); }
            case "Compas"  -> { return new OBJ_Compas(gp); }
            default -> {
                System.out.println("MapObjectLoader: Unknown object type '" + type + "'");
                return null;
            }
        }
    }

    private Entity createMonster(String type, int col, int row) {
        switch (type) {
            case "MON_monster"        -> { return new MON_monster(gp, col, row); }
            case "MON_SkeletonArcher" -> { return new MON_SkeletonArcher(gp, col, row); }
            default -> {
                System.out.println("MapObjectLoader: Unknown monster type '" + type + "'");
                return null;
            }
        }
    }

    private Entity createNPC(String type) {
        switch (type) {
            case "NPC_Alucard" -> { return new NPC_Alucard(gp); }
            default -> {
                System.out.println("MapObjectLoader: Unknown NPC type '" + type + "'");
                return null;
            }
        }
    }

    private tiles_interactive.interactiveTile createInteractiveTile(String type, int col, int row) {
        switch (type) {
            case "IT_Pot"   -> { return new IT_Pot(gp, col, row); }
            case "IT_Coins" -> { return new IT_Coins(gp, col, row); }
            default -> {
                System.out.println("MapObjectLoader: Unknown interactive tile type '" + type + "'");
                return null;
            }
        }
    }

    private void loadEvent(String type, Element obj, int col, int row) {
        switch (type) {
            case "MapTransition" -> {
                String targetMap = getStringProperty(obj, "targetMap", "");
                int targetCol = getIntProperty(obj, "targetCol", 5);
                int targetRow = getIntProperty(obj, "targetRow", 5);
                if (!targetMap.isEmpty()) {
                    gp.eHandler.registerMapTransition(col, row, targetMap, targetCol, targetRow);
                }
            }
            case "HealingPool" -> {
                gp.eHandler.registerHealingPool(col, row);
            }
            case "DamageTrap" -> {
                int damage = getIntProperty(obj, "damage", 1);
                gp.eHandler.registerDamageTrap(col, row, damage);
            }
            default -> {
                System.out.println("MapObjectLoader: Unknown event type '" + type + "'");
            }
        }
    }

    // â”€â”€ Loot name â†’ Entity mapping â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Entity createLootByName(String name) {
        switch (name) {
            case "Compas"  -> { return new OBJ_Compas(gp); }
            case "Key"     -> { return new OBJ_Key(gp); }
            case "Potion"  -> { return new OBJ_Potion(gp); }
            case "Boots"   -> { return new OBJ_Boots(gp); }
            case "Gem"     -> { return new OBJ_Gem(gp); }
            case "Sword"   -> { return new OBJ_Sword_Normal(gp); }
            case "Shield"  -> { return new OBJ_Shield_Wood(gp); }
            default -> {
                System.out.println("MapObjectLoader: Unknown loot '" + name + "'");
                return null;
            }
        }
    }

    // â”€â”€ Tower eye spawning (after all objects loaded) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void spawnTowerEyes() {
        for (Entity entity : gp.obj) {
            if (entity instanceof OBJ_Tower tower) {
                tower.spawnEye();
            }
        }
    }

    // â”€â”€ XML Helpers (duplicated from TileManager for encapsulation) â”€â”€

    private Document parseXmlResource(String path) throws Exception {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            System.out.println("MapObjectLoader: XML resource not found: " + path);
            return null;
        }
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(stream);
        doc.getDocumentElement().normalize();
        return doc;
    }

    private String getStringProperty(Element element, String propertyName, String defaultValue) {
        NodeList properties = element.getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if (!propertyName.equals(property.getAttribute("name"))) continue;
            String value = property.getAttribute("value");
            if (value == null || value.isBlank()) value = property.getTextContent();
            return value;
        }
        return defaultValue;
    }

    private int getIntProperty(Element element, String propertyName, int defaultValue) {
        String value = getStringProperty(element, propertyName, null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBoolProperty(Element element, String propertyName, boolean defaultValue) {
        String value = getStringProperty(element, propertyName, null);
        if (value == null || value.isBlank()) return defaultValue;
        return Boolean.parseBoolean(value.trim());
    }
}


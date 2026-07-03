package map;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import audio.SFX;
import gfx.Color;
import gfx.geom.IntPolygon;
import data.ItemFactory;
import data.MonsterFactory;
import entity.BossMonster;
import entity.Entity;
import entity.NPC_Generic;
import main.GamePanel;
import object.OBJ_Arrow;
import object.OBJ_Chest;
import object.OBJ_Coins;
import object.OBJ_Door;
import object.OBJ_Gem;
import object.OBJ_Heart;
import object.OBJ_Key;
import object.OBJ_ManaCrystal;
import object.OBJ_Potion;
import object.OBJ_Tent;
import object.OBJ_Torch;
import object.OBJ_Tower;
import tile.IT_Coins;
import tile.IT_Grass;
import tile.IT_Pot;
import tile.IT_Tree;
import util.ResourceCache;

/**
 * Full Tiled-to-Game pipeline: loads objects, monsters, NPCs, interactive tiles,
 * events, and map-level properties from TMX files.
 *
 * MAP-LEVEL PROPERTIES (set in Tiled: Map - Properties):
 *   mapName       (String)  display name shown in UI
 *   music         (String)  "theme" | integer SFX index
 *   weather       (String)  CLEAR | RAIN | STORM | SNOW
 *   weatherCycle  (bool)    true (default) enables day/night + auto weather;
 *                           false disables both cycles for this map
 *   ambientLight  (float)   0.0 (bright) to 0.95 (very dark)
 *   lightLevel    (int)     0=day 1=dusk 2=night 3=dawn
 *   spawnCol/Row  (int)     default map spawn tile
 *   bgColor       (String)  "#rrggbb" background clear color
 *   dialogueTrigger (String) Message shown to the player once when they spawn on this map
 *
 * ENTITY PROPERTIES (custom Tiled properties on any object):
 *   facing        (String)  up|down|left|right
 *   id            (String)  persistent object ID for save state
 *   invisible     (bool)    skip draw (for hidden triggers)
 *
 * MONSTER PROPERTIES:
 *   level         (int)     scales HP (+50%/level) and ATK (+25%/level)
 *   aggroRange    (int)     aggro detection radius in pixels
 *   wanderRadius  (int)     max idle-wander offset in pixels
 *
 * NPC PROPERTIES:
 *   dialogue0-4        (String)  override first line of each built-in dialogue set (use \n for newline)
 *   wanderRadius       (int)     max idle-wander pixel offset from spawn
 *   staticNPC          (bool)    stand still forever, no wander or pathfinding
 *   guardMode          (bool)    stand still AND face the player every tick;
 *                                start walking when onPath is set to true (e.g. via speak())
 *   walkToCol/Row      (int)     single-destination: walk here after interact, re-enter guardMode on arrival
 *   walkToDialogueSet  (int)     dialogue set to use permanently once walkTo destination is reached
 *   step<N>_walkToCol  (int)     STEP CHAIN step N: walk to this column after speaking (omit = final stop)
 *   step<N>_walkToRow  (int)     step N: row partner of walkToCol
 *   step<N>_dialogue   (int)     step N: dialogue set to play (optional, defaults to N itself)
 *                                Step chain auto-advances on each arrival; re-enters guardMode between steps.
 *   speed              (int)     movement speed override in pixels per tick (default 1)
 *   collision          (bool)    whether NPC blocks player movement (default true)
 *   name               (String)  display name in the dialogue UI header
 *   onSpeakQuestId     (String)  quest ID to progress every time player talks to this NPC
 *   onSpeakQuestAmount (int)     how much to add to the quest per speak event (default 1)
 *   requiredItem       (String)  item name the player must have to trigger the alternate dialogue
 *   requiredItemDialogueSet (int) which dialogue set to use when the player has the item (default 0)
 *
 * OBJECT PROPERTIES:
 *   amount        (int)     stack count for Potion/Key/Arrow/Coins
 *   opened        (bool)    Chest starts opened
 *   lightRadius   (int)     Torch custom light radius
 *   lightColor    (String)  Torch "#rrggbb" light tint
 *   spawnId       (String)  Door: named spawn point id on target map
 *   spawnDirection(String)  Door: direction player faces on arrival
 *
 *   Light object (type="Light" or "Lighting" in Tiled):
 *   lightRadius   (int)     light radius in tiles (default 4)
 *   lightColor    (String)  "#rrggbb" hex color tint (default: warm orange)
 *
 * PER-TILE PROPERTIES (set on individual tiles inside a tileset in Tiled):
 *   depthSort     (bool)    Depth-sort THIS tile against entities — player walks behind it
 *                           when approaching from above, and in front when coming from below.
 *                           Ideal for campfires, barrels, trees, etc.
 *                           Example: select campfire tile → Custom Properties → add bool "depthSort" = true
 *   sortYOffset   (int)     Shift the tile's depth-sort anchor in pixels (default 0 = tile bottom).
 *                           Use a NEGATIVE value to move the anchor UP (e.g. -32 moves it to tile centre).
 *                           Tiles with sortYOffset != 0 are automatically depth-sorted.
 *   foreground    (bool)    Force tile to always draw ON TOP of all entities (e.g. building roofs).
 *   background    (bool)    Force tile to never depth-sort (always behind entities).
 *
 * LAYER PROPERTIES (set on tile layers in Tiled):
 *   background    (bool)    Force ALL tiles on this layer to background — drawn first, behind entities.
 *                           Overrides tileset-level depthSort. Use for Ground layers with mixed tilesets.
 *   depthSort     (bool)    Force ALL tiles on this layer to depth-sort against entities.
 *   foreground    (bool)    Force ALL tiles on this layer to always draw above entities.
 *
 * EVENT TYPES (Events objectgroup):
 *   MapTransition   targetMap, targetCol, targetRow, spawnId
 *   HealingPool     (no extra properties)
 *   DamageTrap      damage (int), repeatable (bool, default true)
 *   DialogueTrigger message (String), speaker (String), oneShot (bool)
 *   Lighting        point/marker-based static light with lightRadius/lightColor
 *   LevelGate       minLevel (int), message (String),
 *                   targetMap/targetCol/targetRow (optional, if passable above minLevel)
 *   Checkpoint      silent (bool)
 *   QuestTrigger    questId (String), progress (int), oneShot (bool)
 *   QuestDefinition questId (String), questName (String), questDesc (String), target (int)
 *   SpawnPoint      id (String) named spawn location referenced by door spawnId
 *   CameraShake     intensity: "light"|"medium"|"heavy"
 *   SpawnZone       monster (String, default "MON_monster"), maxAmount (int, default 5),
 *                   interval (int frames, default 300 = 5 s at 60 fps)
 *                   Rectangle area — mobs spawn randomly inside it up to maxAmount.
 *
 * MONSTER AREA (Monsters layer, rectangle object):
 *   monster (String)  entity type to spawn (e.g. "MON_monster")
 *   count   (int)     how many to spawn randomly in the area
 *
 * TYPE DETECTION: reads Tiled 1.8 "type" attribute AND Tiled 1.9+ "class" attribute.
 * AREA EVENTS: MapTransition/HealingPool/DamageTrap on rectangles cover every tile.
 */
public class MapObjectLoader {

    // Music name to SFX index (extend when adding new music tracks to SFX.java)
    private static final Map<String, Integer> MUSIC_MAP = new HashMap<>();
    static {
        MUSIC_MAP.put("theme",   SFX.MAIN_THEME);
        MUSIC_MAP.put("dungeon", SFX.MAIN_THEME);
        MUSIC_MAP.put("boss",    SFX.MAIN_THEME);
    }

    private final GamePanel gp;

    public MapObjectLoader(GamePanel gp) {
        this.gp = gp;
    }

    /**
     * Read the map-level properties block and apply music, weather, ambient light,
     * map name, default spawn, and background colour.
     * Called automatically from GamePanel.changeMap() after loading tile layers.
     */
    public void loadMapProperties(String tmxPath) {
        try {
            Document doc = parseXmlResource(tmxPath);
            if (doc == null) return;
            applyMapProperties(doc.getDocumentElement());
        } catch (Exception e) {
            System.out.println("MapObjectLoader: Failed to load map properties: " + tmxPath);
            e.printStackTrace(System.out);
        }
    }

    /** Parse entity objectgroups (Objects, Monsters, NPCs, InteractiveTiles). */
    public void loadEntities(String tmxPath) {
        load(tmxPath, true, false);
    }

    /** Parse only the Events objectgroup (MapTransitions, HealingPools, etc.). */
    public void loadEvents(String tmxPath) {
        clearEventLayerLights();
        load(tmxPath, false, true);
    }

    /**
     * Resolve an objectgroup layer name to one of the standard names,
     * using case-insensitive matching with common aliases.
     * Returns the canonical name or "Unknown" if unrecognized.
     */
    private String resolveObjectGroupName(String layerName) {
        if (layerName == null || layerName.isEmpty()) return "Unknown";
        String lower = layerName.trim().toLowerCase();
        return switch (lower) {
            case "objects", "object"                                -> "Objects";
            case "monsters", "monster", "mobs", "mob", "enemies"   -> "Monsters";
            case "npcs", "npc"                                     -> "NPCs";
            case "interactivetiles", "interacttiles", "interactive" -> "InteractiveTiles";
            case "events", "event", "triggers"                     -> "Events";
            case "collision"                                       -> "Collision";
            default -> {
                System.out.println("MapObjectLoader WARN: Unrecognized objectgroup '" + layerName + "', skipping");
                yield "Unknown";
            }
        };
    }

    private void load(String tmxPath, boolean entities, boolean events) {
        try {
            Document doc = parseXmlResource(tmxPath);
            if (doc == null) {
                System.out.println("MapObjectLoader: TMX not found: " + tmxPath);
                return;
            }

            Element mapEl = doc.getDocumentElement();
            int mapTileSize = 32;
            String tw = mapEl.getAttribute("tilewidth");
            if (tw != null && !tw.isEmpty()) mapTileSize = Integer.parseInt(tw);
            double sf = (double) gp.tileSize / mapTileSize;

            // Infinite map chunk offset
            int offsetX = 0, offsetY = 0;
            if ("1".equals(mapEl.getAttribute("infinite"))) {
                int minCX = 0, minCY = 0;
                NodeList chunks = doc.getElementsByTagName("chunk");
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

            int objIdx   = firstFreeSlot(gp.obj);
            int monIdx   = firstFreeSlot(gp.monster);
            int npcIdx   = firstFreeSlot(gp.npc);
            int iTileIdx = firstFreeSlot(gp.iTile);

            NodeList objectGroups = doc.getElementsByTagName("objectgroup");
            for (int g = 0; g < objectGroups.getLength(); g++) {
                Element og = (Element) objectGroups.item(g);
                String layerName = og.getAttribute("name");
                if ("Collision".equalsIgnoreCase(layerName)) continue;

                NodeList objects = og.getElementsByTagName("object");
                for (int j = 0; j < objects.getLength(); j++) {
                    Element obj = (Element) objects.item(j);

                    // Tiled 1.9+ uses "class"; older versions use "type"
                    String type = obj.getAttribute("class");
                    if (type == null || type.isEmpty()) type = obj.getAttribute("type");
                    if (type == null || type.isEmpty()) type = getStringProperty(obj, "type", "");
                    if (type == null || type.isEmpty()) type = obj.getAttribute("name");
                    if (type == null || type.isEmpty()) continue;

                    String xAttr = obj.getAttribute("x");
                    String yAttr = obj.getAttribute("y");
                    if (xAttr.isEmpty() || yAttr.isEmpty()) continue;

                    double pixelX = Double.parseDouble(xAttr);
                    double pixelY = Double.parseDouble(yAttr);
                    String wAttr = obj.getAttribute("width");
                    String hAttr = obj.getAttribute("height");
                    double objW = wAttr.isEmpty() ? 0 : Double.parseDouble(wAttr);
                    double objH = hAttr.isEmpty() ? 0 : Double.parseDouble(hAttr);

                    int col    = (int)(pixelX / mapTileSize);
                    int row    = (int)(pixelY / mapTileSize);
                    int worldX = (int)(pixelX * sf) + offsetX;
                    int worldY = (int)(pixelY * sf) + offsetY;
                    int areaW  = (int)(objW * sf);
                    int areaH  = (int)(objH * sf);

                    // Handle rotation for rectangle objects (explicit width/height)
                    if (areaW > 0 && areaH > 0) {
                        String rotStr = obj.getAttribute("rotation");
                        double rotDeg = (rotStr != null && !rotStr.isEmpty())
                            ? Double.parseDouble(rotStr) : 0.0;
                        if (rotDeg != 0.0) {
                            double rad = Math.toRadians(rotDeg);
                            double cosR = Math.cos(rad);
                            double sinR = Math.sin(rad);
                            double[][] corners = {{0,0},{objW,0},{objW,objH},{0,objH}};
                            double minPX = Double.MAX_VALUE, minPY = Double.MAX_VALUE;
                            double maxPX = -Double.MAX_VALUE, maxPY = -Double.MAX_VALUE;
                            for (double[] c : corners) {
                                double rx = c[0] * cosR - c[1] * sinR;
                                double ry = c[0] * sinR + c[1] * cosR;
                                if (rx < minPX) minPX = rx;
                                if (rx > maxPX) maxPX = rx;
                                if (ry < minPY) minPY = ry;
                                if (ry > maxPY) maxPY = ry;
                            }
                            worldX += (int)(minPX * sf);
                            worldY += (int)(minPY * sf);
                            areaW = (int)((maxPX - minPX) * sf);
                            areaH = (int)((maxPY - minPY) * sf);
                            col = worldX / gp.tileSize;
                            row = worldY / gp.tileSize;
                        }
                    }

                    // Expand area from polyline/polygon child if present and no explicit size
                    boolean isPolyline = false;
                    if (areaW == 0 && areaH == 0) {
                        String polyPoints = null;
                        NodeList polylines = obj.getElementsByTagName("polyline");
                        if (polylines.getLength() > 0) {
                            polyPoints = ((Element) polylines.item(0)).getAttribute("points");
                            isPolyline = true;
                        }
                        if (polyPoints == null || polyPoints.isEmpty()) {
                            NodeList polygons = obj.getElementsByTagName("polygon");
                            if (polygons.getLength() > 0)
                                polyPoints = ((Element) polygons.item(0)).getAttribute("points");
                            isPolyline = false;
                        }
                        if (polyPoints != null && !polyPoints.isEmpty()) {
                            // Tiled stores rotation in degrees, clockwise in screen space (y-down).
                            // We need to rotate each point before computing the bounding box so
                            // that rotated event objects (e.g. LevelGate rotation="270") register
                            // the correct tiles instead of the unrotated axis-aligned tiles.
                            String rotStr = obj.getAttribute("rotation");
                            double rotDeg = (rotStr != null && !rotStr.isEmpty())
                                ? Double.parseDouble(rotStr) : 0.0;
                            boolean hasRotation = rotDeg != 0.0;
                            double cosR = 1, sinR = 0;
                            if (hasRotation) {
                                double rad = Math.toRadians(rotDeg);
                                cosR = Math.cos(rad);
                                sinR = Math.sin(rad);
                            }
                            double minPX = Double.MAX_VALUE, minPY = Double.MAX_VALUE;
                            double maxPX = -Double.MAX_VALUE, maxPY = -Double.MAX_VALUE;
                            for (String pt : polyPoints.trim().split("\\s+")) {
                                String[] xy = pt.split(",");
                                if (xy.length < 2) continue;
                                double px = Double.parseDouble(xy[0]);
                                double py = Double.parseDouble(xy[1]);
                                if (hasRotation) {
                                    // CW rotation in y-down screen space: x'=x·cos-y·sin, y'=x·sin+y·cos
                                    double rx = px * cosR - py * sinR;
                                    double ry = px * sinR + py * cosR;
                                    px = rx; py = ry;
                                }
                                if (px < minPX) minPX = px;
                                if (px > maxPX) maxPX = px;
                                if (py < minPY) minPY = py;
                                if (py > maxPY) maxPY = py;
                            }
                            areaW = (int)((maxPX - minPX) * sf);
                            areaH = (int)((maxPY - minPY) * sf);
                            // Adjust origin to top-left of bounding box
                            worldX += (int)(minPX * sf);
                            worldY += (int)(minPY * sf);
                            col = worldX / gp.tileSize;
                            row = worldY / gp.tileSize;
                        }
                    }

                    String resolvedGroup = resolveObjectGroupName(layerName);
                    switch (resolvedGroup) {
                        case "Objects" -> {
                            if (!entities) continue;
                            if (objIdx >= gp.obj.length) {
                                System.out.println("MapObjectLoader WARNING: obj[] full, skipping " + type);
                                continue;
                            }
                            Entity entity = createObject(type, obj);
                            if (entity != null) {
                                applyLoadedWorldPosition(entity, type, obj, worldX, worldY);
                                // Resize door to match the dimensions drawn in Tiled
                                if (entity instanceof object.OBJ_Door door && areaW > 0 && areaH > 0) {
                                    door.setSize(areaW, areaH);
                                }
                                applyCommonProperties(entity, obj);
                                gp.obj[objIdx++] = entity;
                            }
                        }
                        case "Monsters" -> {
                            if (!entities) continue;
                            if ("MonsterArea".equals(type)) {
                                spawnMonsterArea(obj, col, row, areaW, areaH, monIdx);
                                monIdx = firstFreeSlot(gp.monster);
                            } else {
                                if (monIdx >= gp.monster.length) {
                                    System.out.println("MapObjectLoader WARNING: monster[] full, skipping " + type);
                                    continue;
                                }
                                Entity monster = createMonster(type, col, row, obj);
                                if (monster != null) {
                                    applyCommonProperties(monster, obj);
                                    gp.monster[monIdx++] = monster;
                                }
                            }
                        }
                        case "NPCs" -> {
                            if (!entities) continue;
                            if (npcIdx >= gp.npc.length) {
                                System.out.println("MapObjectLoader WARNING: npc[] full, skipping " + type);
                                continue;
                            }
                            Entity npc = createNPC(type, obj, worldX, worldY);
                            if (npc != null) {
                                npc.worldX = worldX;
                                npc.worldY = worldY;
                                applyCommonProperties(npc, obj);
                                gp.npc[npcIdx++] = npc;
                            }
                        }
                        case "InteractiveTiles" -> {
                            if (!entities) continue;
                            if (iTileIdx >= gp.iTile.length) {
                                System.out.println("MapObjectLoader WARNING: iTile[] full, skipping " + type);
                                continue;
                            }
                            tile.interactiveTile tile =
                                createInteractiveTile(type, col, row, obj);
                            if (tile != null) {
                                applyCommonProperties(tile, obj);
                                gp.iTile[iTileIdx++] = tile;
                            }
                        }
                        case "Events" -> {
                            if (!events) continue;
                            if (isLightMarkerType(type)) {
                                if (objIdx >= gp.obj.length) {
                                    System.out.println("MapObjectLoader WARNING: obj[] full, skipping event light " + type);
                                    continue;
                                }
                                Entity light = createLightMarker(obj, true);
                                applyLoadedWorldPosition(light, type, obj, worldX, worldY);
                                applyCommonProperties(light, obj);
                                gp.obj[objIdx++] = light;
                                continue;
                            }
                            // For polyline events, ensure the thin dimension is at
                            // least half a tile so the barrier is practical to collide with.
                            if (isPolyline) {
                                int minThickness = gp.tileSize / 2;
                                if (areaW < minThickness) {
                                    int expand = minThickness - areaW;
                                    worldX -= expand / 2;
                                    areaW = minThickness;
                                }
                                if (areaH < minThickness) {
                                    int expand = minThickness - areaH;
                                    worldY -= expand / 2;
                                    areaH = minThickness;
                                }
                            }
                            loadEvent(type, obj, col, row, worldX, worldY, areaW, areaH, sf);
                        }
                    }
                }
            }

            spawnTowerEyes();
            System.out.println("MapObjectLoader: Loaded " + tmxPath
                + " obj:" + objIdx + " mon:" + monIdx
                + " npc:" + npcIdx + " iTile:" + iTileIdx);

        } catch (Exception e) {
            System.out.println("MapObjectLoader: Failed to load " + tmxPath);
            e.printStackTrace(System.out);
        }
    }

    private void applyMapProperties(Element mapEl) {
        // Reset per-map overrides each time a map loads
        gp.eManager.pinnedFilterAlpha = -1f;
        gp.eManager.weatherCycleEnabled = true;
        gp.mapManager.defaultSpawnCol = -1;
        gp.mapManager.defaultSpawnRow = -1;
        gp.mapManager.hasNewGameSpawn = false;
        gp.mapManager.mapBackgroundColor = Color.BLACK;

        String mapName = getStringProperty(mapEl, "mapName", null);
        if (mapName != null && !mapName.isBlank()) {
            gp.mapManager.currentMapDisplayName = mapName;
            System.out.println("MapObjectLoader: mapName='" + mapName + "'");
        }

        String music = getStringProperty(mapEl, "music", null);
        if (music != null && !music.isBlank()) applyMapMusic(music.trim());

        String weather = getStringProperty(mapEl, "weather", null);
        if (weather != null && !weather.isBlank()) {
            gp.eManager.setWeatherByName(weather.trim());
            System.out.println("MapObjectLoader: weather=" + weather.trim().toUpperCase());
        } else {
            // No weather property — release the pin so auto-cycle works on this map
            gp.eManager.pinnedWeather = -1;
        }

        // weatherCycle: when false, disables both day/night and auto-weather cycling
        if (!getBoolProperty(mapEl, "weatherCycle", true)) {
            gp.eManager.weatherCycleEnabled = false;
            System.out.println("MapObjectLoader: weatherCycle=false (cycling disabled)");
        }

        String ambStr = getStringProperty(mapEl, "ambientLight", null);
        if (ambStr != null && !ambStr.isBlank()) {
            try {
                float al = Math.max(0f, Math.min(0.99f, Float.parseFloat(ambStr.trim())));
                gp.eManager.pinnedFilterAlpha = al;
                gp.eManager.filterAlpha = al;
                System.out.println("MapObjectLoader: ambientLight=" + al);
            } catch (NumberFormatException ignored) {}
        }

        String llStr = getStringProperty(mapEl, "lightLevel", null);
        if (llStr != null && !llStr.isBlank()) {
            try {
                gp.eManager.setTimeOfDay(Integer.parseInt(llStr.trim()));
                System.out.println("MapObjectLoader: lightLevel=" + llStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        int sc = getIntProperty(mapEl, "spawnCol", -1);
        int sr = getIntProperty(mapEl, "spawnRow", -1);
        if (sc >= 0 && sr >= 0) { gp.mapManager.defaultSpawnCol = sc; gp.mapManager.defaultSpawnRow = sr; }

        String bgColor = getStringProperty(mapEl, "bgColor", null);
        if (bgColor != null && !bgColor.isBlank()) {
            try { gp.mapManager.mapBackgroundColor = Color.decode(bgColor.trim()); }
            catch (NumberFormatException ignored) {}
        }

        // dialogueTrigger: message shown to the player once when they spawn on this map
        String dialogueTrigger = getStringProperty(mapEl, "dialogueTrigger", null);
        gp.mapManager.pendingDialogueTrigger = (dialogueTrigger != null && !dialogueTrigger.isBlank())
            ? dialogueTrigger.trim() : "";
        // dialogueTriggerDuration: how many frames the message stays (default 300)
        gp.mapManager.pendingDialogueTriggerDuration = getIntProperty(mapEl, "dialogueTriggerDuration", 300);

        // actTitle: large fade-in/fade-out title card shown on map entry (e.g. "Act I: The Awakening Cave")
        String actTitle = getStringProperty(mapEl, "actTitle", null);
        gp.mapManager.pendingActTitle = (actTitle != null && !actTitle.isBlank()) ? actTitle.trim() : "";
        // actTitleNewGameOnly: when true the title only shows during the New Game cutscene, not on normal transitions
        gp.mapManager.pendingActTitleNewGameOnly = getBoolProperty(mapEl, "actTitleNewGameOnly", false);
    }

    private void applyMapMusic(String value) {
        try {
            gp.audio.playMusic(Integer.parseInt(value));
            System.out.println("MapObjectLoader: music track " + value);
            return;
        } catch (NumberFormatException ignored) {}
        Integer idx = MUSIC_MAP.get(value.toLowerCase());
        if (idx != null) {
            gp.audio.playMusic(idx);
            System.out.println("MapObjectLoader: music '" + value + "' -> track " + idx);
        } else {
            System.out.println("MapObjectLoader: Unknown music '" + value + "'. Add to MUSIC_MAP.");
        }


        
    }

    private Entity createObject(String type, Element obj) {
        String itemId = getFirstStringProperty(obj, null, "itemId", "factoryId");
        if (itemId != null && !itemId.isBlank()) {
            Entity item = createFactoryItem(itemId, obj);
            if (item != null) return item;
        }

        switch (type) {
            case "Chest" -> {
                OBJ_Chest chest = new OBJ_Chest(gp);
                String loot = getFirstStringProperty(obj, "", "lootId", "loot");
                if (!loot.isEmpty()) {
                    Entity le = createEntityByName(loot);
                    if (le != null) chest.setLoot(le);
                }
                String reqItem = getStringProperty(obj, "requiredItem", "");
                if (!reqItem.isEmpty()) {
                    chest.requiredItem = reqItem;
                    chest.consumeItem = getBoolProperty(obj, "consumeItem", false);
                    chest.setDialogue(); // refresh dialogue with requirement name
                }
                String animSheet = getStringProperty(obj, "openAnimation", "");
                int animFrames = getIntProperty(obj, "openFrames", 0);
                if (!animSheet.isEmpty() && animFrames > 1) {
                    chest.loadOpenAnimation(animSheet, animFrames);
                }
                if (getBoolProperty(obj, "opened", false)) {
                    chest.opened = true;
                    chest.down1 = chest.image1;
                }
                return chest;
            }
            case "Door" -> {
                boolean vis = getBoolProperty(obj, "visible", true);
                OBJ_Door door = vis ? new OBJ_Door(gp) : new OBJ_Door(gp, true);
                String dest = getStringProperty(obj, "destination", "");
                if (!dest.isEmpty()) {
                    int dCol   = getIntProperty(obj, "destCol", 5);
                    int dRow   = getIntProperty(obj, "destRow", 7);
                    boolean lk = getBoolProperty(obj, "isLocked", false);
                    String sid = getStringProperty(obj, "spawnId", "");
                    door.setDestination(dest, dCol, dRow, lk, sid);
                    String sd = getStringProperty(obj, "spawnDirection", "");
                    if (!sd.isEmpty()) door.spawnDirection = parseDirection(sd);
                }
                String prompt = getStringProperty(obj, "promptText", "");
                if (!prompt.isEmpty()) door.promptText = prompt;
                return door;
            }
            case "Torch" -> {
                OBJ_Torch torch = new OBJ_Torch(gp);
                torch.lightRadius = getIntProperty(obj, "lightRadius", 6);
                String lc = getStringProperty(obj, "lightColor", null);
                if (lc != null && !lc.isBlank()) {
                    try { torch.lightColor = Color.decode(lc.trim()); }
                    catch (NumberFormatException ignored) {}
                }
                return torch;
            }
            case "Coins"  -> { OBJ_Coins  c = new OBJ_Coins(gp);  c.coinValue = getIntProperty(obj, "amount", 1); return c; }
            case "Potion" -> { OBJ_Potion p = new OBJ_Potion(gp); p.amount = getIntProperty(obj, "amount", 1); return p; }
            case "Key"    -> { OBJ_Key    k = new OBJ_Key(gp);    k.amount = getIntProperty(obj, "amount", 1); return k; }
            case "Arrow"  -> { OBJ_Arrow  a = new OBJ_Arrow(gp);  a.amount = getIntProperty(obj, "amount", 1); return a; }
            case "Item"   -> { return createFactoryItem(itemId, obj); }
            case "Tent"   -> { return new OBJ_Tent(gp); }
            case "Boots"  -> { return ItemFactory.create(gp, "boots"); }
            case "Gem"    -> { return new OBJ_Gem(gp); }
            case "Book"   -> { return ItemFactory.create(gp, "spell_book"); }
            case "Tower"  -> { return new OBJ_Tower(gp); }
            case "Sword"  -> { return ItemFactory.create(gp, "wooden_sword"); }
            case "Shield" -> { return ItemFactory.create(gp, "wooden_shield"); }
            case "Heart"  -> { return new OBJ_Heart(gp); }
            case "Mana"   -> { return new OBJ_ManaCrystal(gp); }
            case "Compas" -> { return ItemFactory.create(gp, "compas"); }
            case "Light", "Lighting" -> { return createLightMarker(obj, false); }
            default -> {
                Entity e = createEntityByName(type);
                if (e != null) return e;
                System.out.println("MapObjectLoader WARNING: Unknown object type '" + type + "' — skipped");
                return null;
            }
        }
    }

    private Entity createMonster(String type, int col, int row, Element obj) {
        // Accept both legacy TMX class names (deprecated) and JSON ids
        String id = switch (type) {
            case "MON_monster"         -> "painted_crab";
            case "MON_SkeletonArcher"  -> "drowned_sketch";
            case "MON_Shade"           -> "shade_wolf";
            case "MON_Inkblot", "Inkblot" -> "inkblot";
            case "BOSS_WitheredTree"   -> "withered_tree";
            default                    -> type;
        };
        Entity m = MonsterFactory.create(gp, id, col, row);
        if (m == null) {
            System.out.println("MapObjectLoader WARNING: Unknown monster type '" + type + "' (resolved: '" + id + "') — skipped");
            return null;
        }

        // Level scaling: +50% HP and +25% ATK per level above 1
        int level = getIntProperty(obj, "level", 1);
        if (level > 1) {
            double e = level - 1;
            m.maxLife = (int)(m.maxLife * (1 + 0.5 * e));
            m.life    = m.maxLife;
            m.attack  = (int)(m.attack  * (1 + 0.25 * e));
            System.out.println("MapObjectLoader: Monster level " + level
                + " HP=" + m.maxLife + " ATK=" + m.attack);
        }

        int aggroRange   = getIntProperty(obj, "aggroRange", -1);
        int wanderRadius = getIntProperty(obj, "wanderRadius", -1);
        if (aggroRange   > 0) m.aggroRange   = aggroRange;
        if (wanderRadius > 0) m.wanderRadius = wanderRadius;

        // Boss wrapper
        int bossId = getIntProperty(obj, "bossId", 0);
        if (bossId > 0) {
            float threshold  = getFloatProperty(obj, "phase2Threshold", 0.5f);
            int   speedBoost = getIntProperty(obj, "phase2SpeedBoost", 1);
            m = new BossMonster(gp, m, bossId, threshold, speedBoost);
            ((BossMonster) m).onDeathQuestId = getStringProperty(obj, "onDeathQuestId", "");
        }

        return m;
    }

    private Entity createNPC(String type, Element obj, int worldX, int worldY) {
        Entity npc = switch (type) {
            case "NPC_Generic", "NPC_Alucard" -> {
                // If the Tiled type is NPC_Alucard (legacy), default the npcId to "alucard"
                String npcId = getStringProperty(obj, "npcId", null);
                if (npcId == null && "NPC_Alucard".equals(type)) npcId = "alucard";
                if (npcId != null && !npcId.isBlank() && data.NPCFactory.has(npcId)) {
                    NPC_Generic g = data.NPCFactory.createAt(gp, npcId, worldX, worldY);
                    if (g != null) {
                        // Allow Tiled property overrides on top of JSON definitions
                        String overrideSprite = getStringProperty(obj, "sprite", null);
                        if (overrideSprite != null && !overrideSprite.isBlank()) {
                            g.spritePath = overrideSprite;
                            g.getImage();
                        }
                        loadDataDrivenDialogues(g, obj);  // Tiled dialogue overrides JSON
                        loadFragmentProperties(g, obj);
                        loadChoiceProperties(g, obj);
                        yield g;
                    }
                }

                NPC_Generic g = new NPC_Generic(gp);
                // sprite path (walk sheet)
                String sprite = getStringProperty(obj, "sprite", null);
                if (sprite != null && !sprite.isBlank()) g.spritePath = sprite;
                // idle sprite path
                String idleSprite = getStringProperty(obj, "idleSprite", null);
                if (idleSprite != null && !idleSprite.isBlank()) g.idleSpritePath = idleSprite;
                g.getImage();
                // data-driven dialogue: dialogue_S_L (set S, line L)
                loadDataDrivenDialogues(g, obj);
                // memory fragment
                loadFragmentProperties(g, obj);
                // choice dialogue
                loadChoiceProperties(g, obj);
                yield g;
            }
            default -> {
                System.out.println("MapObjectLoader WARNING: Unknown NPC type '" + type + "' — skipped. Use 'NPC_Generic' with npcId property.");
                yield null;
            }
        };
        if (npc == null) return null;

        // dialogue0..dialogue4 properties override first line of each dialogue set
        String[][] dialogues = npc.ensureDialogues();
        for (int i = 0; i < 5; i++) {
            String d = getStringProperty(obj, "dialogue" + i, null);
            if (d != null && !d.isBlank() && i < dialogues.length
                    && dialogues[i] != null && dialogues[i].length > 0) {
                dialogues[i][0] = d.replace("\\n", "\n");
            }
        }

        // speed: override movement speed (default 1)
        int npcSpeed = getIntProperty(obj, "speed", -1);
        if (npcSpeed > 0) npc.speed = npcSpeed;

        // staticNPC: stand still, never wander or path-follow
        if (getBoolProperty(obj, "staticNPC", false)) npc.staticNPC = true;

        // guardMode: static from spawn AND faces the player every tick; unlocked by setting onPath=true
        if (getBoolProperty(obj, "guardMode", false)) npc.guardMode = true;

        // idleDirection: force a direction while idle (0=down, 1=left, 2=right, 3=up)
        int idleDir = getIntProperty(obj, "idleDirection", -1);
        if (idleDir >= 0) npc.idleDirection = idleDir;

        // walkToCol / walkToRow: after the player interacts, NPC walks to this tile then re-enters guardMode
        int wCol = getIntProperty(obj, "walkToCol", -1);
        int wRow = getIntProperty(obj, "walkToRow", -1);
        if (wCol >= 0) npc.walkToCol = wCol;
        if (wRow >= 0) npc.walkToRow = wRow;

        // walkToDialogueSet: dialogue set index to use permanently after the NPC arrives at walkTo destination
        int wDlg = getIntProperty(obj, "walkToDialogueSet", -1);
        if (wDlg >= 0) npc.walkToDialogueSet = wDlg;

        // step<N>_dialogue is optional — defaults to N so steps 0/1/2 automatically play
        // dialogue sets 0/1/2 from the NPC class's setDialogue() without any Tiled config.
        for (int si = 0; si < 20; si++) {
            int sCol = getIntProperty(obj, "step" + si + "_walkToCol", -2);
            int sRow = getIntProperty(obj, "step" + si + "_walkToRow", -2);
            int sDlg = getIntProperty(obj, "step" + si + "_dialogue",  -2);
            // stop when none of the three properties exist for this step index
            if (sCol == -2 && sRow == -2 && sDlg == -2) break;
            // default dialogue set to the step index itself
            if (sDlg < 0) sDlg = si;
            // -1 for col/row means "no walk" (final stop)
            npc.npcSteps.add(new int[]{ sDlg, (sCol == -2 ? -1 : sCol), (sRow == -2 ? -1 : sRow) });
        }
        // If steps were defined, load the first step into the active walkTo fields
        if (!npc.npcSteps.isEmpty()) {
            int[] first = npc.npcSteps.get(0);
            npc.walkToDialogueSet = first[0];
            npc.walkToCol = first[1];
            npc.walkToRow = first[2];
            npc.npcStepIndex = 1; // next step to load on arrival
        }

        // collision: whether this NPC blocks the player (default true)
        String collisionProp = getStringProperty(obj, "collision", null);
        if (collisionProp != null && !collisionProp.isBlank()) {
            npc.collision = "true".equalsIgnoreCase(collisionProp);
        }

        // name: display name shown above dialogue box
        String npcName = getStringProperty(obj, "name", null);
        if (npcName != null && !npcName.isBlank()) npc.name = npcName;

        // onSpeakQuestId / onSpeakQuestAmount: progress a quest when the player speaks to this NPC
        String qid = getStringProperty(obj, "onSpeakQuestId", null);
        if (qid != null && !qid.isBlank()) {
            npc.onSpeakQuestId     = qid;
            npc.onSpeakQuestAmount = getIntProperty(obj, "onSpeakQuestAmount", 1);
        }

        // requiredItem / requiredItemDialogueSet: switch to a different dialogue when the player has a specific item
        String reqItem = getFirstStringProperty(obj, null, "deliveryItem", "requiredItem");
        if (reqItem != null && !reqItem.isBlank()) {
            npc.requiredItem = reqItem;
            npc.requiredItemDialogueSet = getFirstIntProperty(obj, 0, "deliveryDialogueSet", "requiredItemDialogueSet");
        }

        // requiredItemConsumed: remove the item from inventory when dialogue switches
        if (getFirstBoolProperty(obj, false, "deliveryConsumeItem", "requiredItemConsumed")) npc.requiredItemConsumed = true;

        String requiredItemQuestId = getFirstStringProperty(obj, null, "deliveryQuestId", "requiredItemQuestId");
        if (requiredItemQuestId != null && !requiredItemQuestId.isBlank()) {
            npc.requiredItemQuestId = requiredItemQuestId;
            npc.requiredItemQuestAmount = getFirstIntProperty(obj, 1, "deliveryQuestAmount", "requiredItemQuestAmount");
        }
        npc.requiredItemPostQuestSet = getFirstIntProperty(obj, -1, "deliveryPostDialogueSet", "requiredItemPostQuestSet");
        npc.requiredItemRewardCoins = getFirstIntProperty(obj, 0, "deliveryRewardCoins", "requiredItemRewardCoins");
        String deliveryRewardItem = getFirstStringProperty(obj, null, "deliveryRewardItem", "requiredItemRewardItem");
        if (deliveryRewardItem != null && !deliveryRewardItem.isBlank()) npc.requiredItemRewardItemId = deliveryRewardItem;
        String deliveryRewardFragment = getFirstStringProperty(obj, null, "deliveryRewardFragmentId", "requiredItemRewardFragmentId");
        if (deliveryRewardFragment != null && !deliveryRewardFragment.isBlank()) npc.requiredItemRewardFragmentId = deliveryRewardFragment;

        // idleAnimSpeed: ticks between idle animation frame advances (default 24; lower = faster)
        int idleAnimSpeed = getIntProperty(obj, "idleAnimSpeed", -1);
        if (idleAnimSpeed > 0) npc.idleAnimationInterval = idleAnimSpeed;

        // portrait: path to a 96×96 portrait image shown in the dialogue box
        String portrait = getStringProperty(obj, "portrait", null);
        if (portrait != null && !portrait.isBlank()) npc.portraitPath = portrait.trim();

        // giveItem / giveItemDialogueSet: give an item to the player on first interaction
        String giveItem = getFirstStringProperty(obj, null, "giftItem", "giveItem");
        if (giveItem != null && !giveItem.isBlank()) {
            npc.giveItemId         = giveItem;
            npc.giveItemDialogueSet = getFirstIntProperty(obj, 0, "giftDialogueSet", "giveItemDialogueSet");
        }

        String giveQuestId = getFirstStringProperty(obj, null, "giftQuestId", "giveItemQuestId");
        if (giveQuestId != null && !giveQuestId.isBlank()) {
            npc.giveItemQuestId = giveQuestId;
            npc.giveItemQuestName = getFirstStringProperty(obj, giveQuestId, "giftQuestName", "giveItemQuestName");
            npc.giveItemQuestDesc = getFirstStringProperty(obj, "", "giftQuestDesc", "giveItemQuestDesc");
            npc.giveItemQuestTarget = getFirstIntProperty(obj, 1, "giftQuestTarget", "giveItemQuestTarget");
        }

        // giveItem2 / giveItem2DialogueSet: give a second item after the NPC finishes walking (post-help)
        String giveItem2 = getStringProperty(obj, "giveItem2", null);
        if (giveItem2 != null && !giveItem2.isBlank()) {
            npc.giveItem2Id          = giveItem2;
            npc.giveItem2DialogueSet  = getIntProperty(obj, "giveItem2DialogueSet", -1);
        }

        int wanderRadius = getIntProperty(obj, "wanderRadius", -1);
        if (wanderRadius > 0) npc.wanderRadius = wanderRadius;
        npc.syncQuestDrivenNpcState();
        return npc;
    }

    /**
     * Load dialogue_S_L Tiled properties into the NPC's dialogue array.
     * Supports up to 10 sets × 10 lines (dialogue_0_0 .. dialogue_9_9).
     */
    private void loadDataDrivenDialogues(Entity npc, Element obj) {
        String[][] dialogues = npc.ensureDialogues();
        for (int s = 0; s < dialogues.length; s++) {
            for (int l = 0; l < dialogues[s].length; l++) {
                String val = getStringProperty(obj, "dialogue_" + s + "_" + l, null);
                if (val != null && !val.isBlank()) {
                    dialogues[s][l] = val.replace("\\n", "\n");
                }
            }
        }
    }

    /** Load memory fragment properties from Tiled. */
    private void loadFragmentProperties(Entity npc, Element obj) {
        String fid = getStringProperty(obj, "memoryFragmentId", null);
        if (fid == null || fid.isBlank()) return;
        npc.memoryFragmentId = fid;
        npc.memoryFragmentName = getStringProperty(obj, "memoryFragmentName", fid);
        // memoryText0..memoryText4
        int count = 0;
        for (int i = 0; i < 5; i++) {
            String t = getStringProperty(obj, "memoryText" + i, null);
            if (t != null && !t.isBlank()) {
                npc.memoryFragmentText[i] = t.replace("\\n", "\n");
                count++;
            }
        }
        npc.fragmentRequiredCount = getIntProperty(obj, "fragmentRequiredCount", 0);
        npc.fragmentRequiredItem  = getStringProperty(obj, "fragmentRequiredItem", null);
        npc.fragmentRequiredBoss  = getIntProperty(obj, "fragmentRequiredBoss", -1);
        npc.fragmentRequiredQuest = getStringProperty(obj, "fragmentRequiredQuest", null);

        // Register with journal
        if (gp.memoryJournal != null) {
            String[] textArr = new String[count];
            int idx = 0;
            for (int i = 0; i < 5; i++) {
                if (npc.memoryFragmentText[i] != null) textArr[idx++] = npc.memoryFragmentText[i];
            }
            int order = getIntProperty(obj, "fragmentOrder", 99);
            String source = getStringProperty(obj, "fragmentSource", npc.name);
            gp.memoryJournal.registerFragment(fid, npc.memoryFragmentName, textArr, order, source);
        }
    }

    /** Load choice dialogue properties from Tiled. */
    private void loadChoiceProperties(Entity npc, Element obj) {
        String choices = getStringProperty(obj, "dialogueChoices", null);
        if (choices == null || choices.isBlank()) return;
        npc.dialogueChoices = choices.split("\\|");
        String nextSets = getStringProperty(obj, "choiceNextSet", null);
        if (nextSets != null && !nextSets.isBlank()) {
            String[] parts = nextSets.split("\\|");
            npc.choiceNextSet = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { npc.choiceNextSet[i] = Integer.parseInt(parts[i].trim()); }
                catch (NumberFormatException e) { npc.choiceNextSet[i] = 0; }
            }
        }
        npc.choiceResultKey = getStringProperty(obj, "choiceResultKey", null);
    }

    private tile.interactiveTile createInteractiveTile(String type, int col, int row,
                                                                     Element obj) {
        switch (type) {
            case "IT_Pot"   -> { return new IT_Pot(gp, col, row); }
            case "IT_Grass" -> { return new IT_Grass(gp, col, row); }
            case "IT_Tree"  -> { return new IT_Tree(gp, col, row); }
            case "IT_Coins" -> {
                IT_Coins ic = new IT_Coins(gp, col, row);
                ic.coinValue = getIntProperty(obj, "amount", 1);
                return ic;
            }
            default -> {
                System.out.println("MapObjectLoader: Unknown interactive tile type '" + type + "'");
                return null;
            }
        }
    }

    private void applyCommonProperties(Entity entity, Element obj) {
        String facing = getStringProperty(obj, "facing", null);
        if (facing != null && !facing.isBlank()) entity.direction = parseDirection(facing);
        String id = getStringProperty(obj, "id", null);
        if (id != null && !id.isBlank()) entity.objectId = id;
        if (getBoolProperty(obj, "invisible", false)) entity.invisible = true;
        entity.removeOnPickup = getBoolProperty(obj, "removeOnPickup", entity.removeOnPickup);
    }

    private void loadEvent(String type, Element obj, int col, int row,
                           int worldX, int worldY, int areaW, int areaH, double sf) {
        // Default event dimensions: if no area, use one tile
        int ew = (areaW > 0) ? areaW : gp.tileSize;
        int eh = (areaH > 0) ? areaH : gp.tileSize;

        switch (type) {
            case "MapTransition" -> {
                String tMap   = getStringProperty(obj, "targetMap", "");
                int    tCol   = getIntProperty(obj, "targetCol", 5);
                int    tRow   = getIntProperty(obj, "targetRow", 5);
                String spawnId = getStringProperty(obj, "spawnId", "");
                if (!tMap.isEmpty()) {
                    gp.eHandler.registerMapTransition(worldX, worldY, ew, eh,
                        tMap, tCol, tRow, spawnId);
                }
            }
            case "HealingPool" -> {
                gp.eHandler.registerHealingPool(worldX, worldY, ew, eh);
            }
            case "DamageTrap" -> {
                int     damage     = getIntProperty(obj, "damage", 1);
                boolean repeatable = getBoolProperty(obj, "repeatable", true);
                gp.eHandler.registerDamageTrap(worldX, worldY, ew, eh, damage, repeatable);
            }
            case "DialogueTrigger" -> {
                String msg    = getStringProperty(obj, "message", "...").replace("\\n", "\n");
                String speaker = getStringProperty(obj, "speaker", "");
                boolean one   = getBoolProperty(obj, "oneShot", true);
                EventHandler.DialogueData shared = new EventHandler.DialogueData(msg, speaker, one);
                gp.eHandler.registerDialogueTrigger(worldX, worldY, ew, eh, shared);
            }
            case "ThoughtTrigger" -> {
                String msg    = getStringProperty(obj, "message", "...").replace("\\n", "\n");
                boolean one   = getBoolProperty(obj, "oneShot", true);
                int linger    = getIntProperty(obj, "linger", 120);
                int delay     = getIntProperty(obj, "delay", 0);
                EventHandler.ThoughtData data = new EventHandler.ThoughtData(msg, one, linger, delay);
                gp.eHandler.registerThoughtTrigger(worldX, worldY, ew, eh, data);
            }
            case "LevelGate" -> {
                int    min   = getIntProperty(obj, "minLevel", 0);
                String msg   = getStringProperty(obj, "message",
                    "You cannot pass here.").replace("\\n", "\n");
                String tMap  = getStringProperty(obj, "targetMap", "");
                int    tCol  = getIntProperty(obj, "targetCol", 5);
                int    tRow  = getIntProperty(obj, "targetRow", 5);
                String sid   = getStringProperty(obj, "spawnId", "");
                String reqItem = getStringProperty(obj, "requiredItem", "");
                boolean consume = getBoolProperty(obj, "consumeItem", false);
                String reqFrag = getStringProperty(obj, "requiredFragment", "");
                boolean permOpen = getBoolProperty(obj, "permanentOpen", false);
                gp.eHandler.registerLevelGate(worldX, worldY, ew, eh,
                    min, msg, tMap, tCol, tRow, sid, reqItem, consume, reqFrag, permOpen);
            }
            case "Checkpoint" -> {
                boolean silent = getBoolProperty(obj, "silent", false);
                gp.eHandler.registerCheckpoint(worldX, worldY, ew, eh, silent);
            }
            case "QuestTrigger" -> {
                String  qId   = getStringProperty(obj, "questId", "");
                int     prog  = getIntProperty(obj, "progress", 1);
                boolean one   = getBoolProperty(obj, "oneShot", true);
                if (!qId.isEmpty())
                    gp.eHandler.registerQuestTrigger(worldX, worldY, ew, eh, qId, prog, one);
            }
            case "FragmentTrigger" -> {
                String  fId  = getStringProperty(obj, "fragmentId", "");
                boolean one  = getBoolProperty(obj, "oneShot", true);
                if (!fId.isEmpty())
                    gp.eHandler.registerFragmentTrigger(worldX, worldY, ew, eh, fId, one);
            }
            case "SpawnPoint" -> {
                // If this SpawnPoint has newGame=true it is the authoritative New Game spawn.
                // Otherwise it only overwrites the default if no newGame spawn has been set yet.
                boolean isNewGameSpawn = getBoolProperty(obj, "newGame", false);
                boolean hasNewGameSpawn = gp.mapManager.hasNewGameSpawn;
                if (isNewGameSpawn || !hasNewGameSpawn) {
                    gp.mapManager.defaultSpawnCol = col;
                    gp.mapManager.defaultSpawnRow = row;
                    if (isNewGameSpawn) gp.mapManager.hasNewGameSpawn = true;
                    System.out.println("MapObjectLoader: Default SpawnPoint at (" + col + "," + row + ")"
                        + (isNewGameSpawn ? " [newGame]" : ""));
                }
                // Also register as a named spawn if an 'id' is provided (for door transitions)
                String sid = getStringProperty(obj, "id", "");
                if (!sid.isEmpty()) {
                    gp.eHandler.registerNamedSpawnPoint(sid, col, row);
                    System.out.println("MapObjectLoader: SpawnPoint '" + sid + "' registered as named spawn");
                }
            }
            case "CameraShake" -> {
                String intensity = getStringProperty(obj, "intensity", "medium");
                gp.eHandler.registerCameraShake(worldX, worldY, ew, eh, intensity);
            }
            case "SpawnZone" -> {
                String monType  = getStringProperty(obj, "monster",   "MON_monster");
                int    maxAmt   = getIntProperty(obj, "maxAmount",  5);
                int    interval = getIntProperty(obj, "interval",   300);
                boolean confined = getBoolProperty(obj, "confined",   false);
                int    actRange  = getIntProperty(obj, "activationRange", 0);
                int    totalLim  = getIntProperty(obj, "totalLimit",  0);
                String lootItm  = getStringProperty(obj, "lootItem",  "");
                String lootFrag = getStringProperty(obj, "lootFragment", "");
                // Check for a <polygon> child — if present build an exact polygon zone.
                // NOTE: worldX/worldY have already been shifted to the bounding-box top-left
                // by the outer parsing block (worldX += minPX*sf). To recover the correct
                // absolute world coords for each point use (rawX - minRawX)*sf + worldX,
                // mirroring the same two-pass approach used by the WaterZone polygon case.
                IntPolygon spawnPoly = null;
                NodeList spawnPolyNodes = obj.getElementsByTagName("polygon");
                if (spawnPolyNodes.getLength() > 0) {
                    String pts = ((Element) spawnPolyNodes.item(0)).getAttribute("points");
                    if (pts != null && !pts.isEmpty()) {
                        String[] tokens = pts.trim().split("\\s+");
                        double[] rawX = new double[tokens.length];
                        double[] rawY = new double[tokens.length];
                        int n = 0;
                        double minRawX = Double.MAX_VALUE, minRawY = Double.MAX_VALUE;
                        double maxRawX = -Double.MAX_VALUE, maxRawY = -Double.MAX_VALUE;
                        for (String pt : tokens) {
                            String[] xy = pt.split(",");
                            if (xy.length < 2) continue;
                            rawX[n] = Double.parseDouble(xy[0]);
                            rawY[n] = Double.parseDouble(xy[1]);
                            if (rawX[n] < minRawX) minRawX = rawX[n];
                            if (rawY[n] < minRawY) minRawY = rawY[n];
                            if (rawX[n] > maxRawX) maxRawX = rawX[n];
                            if (rawY[n] > maxRawY) maxRawY = rawY[n];
                            n++;
                        }
                        if (n >= 3) {
                            int[] xs = new int[n];
                            int[] ys = new int[n];
                            for (int pi = 0; pi < n; pi++) {
                                // worldX is already the bounding-box top-left, so:
                                // worldX + (rawX[pi] - minRawX) * sf
                                xs[pi] = worldX + (int)((rawX[pi] - minRawX) * sf);
                                ys[pi] = worldY + (int)((rawY[pi] - minRawY) * sf);
                            }
                            spawnPoly = new IntPolygon(xs, ys, n);
                            // Override the AABB bounds so the sampling loop uses the polygon's bounds
                            areaW = (int)((maxRawX - minRawX) * sf);
                            areaH = (int)((maxRawY - minRawY) * sf);
                            // worldX/worldY are already the correct bounding-box origin
                        }
                    }
                }
                gp.eHandler.registerSpawnZone(worldX, worldY, areaW, areaH, spawnPoly,
                                               monType, maxAmt, interval,
                                               confined, actRange, totalLim, lootItm, lootFrag);
            }
            case "MobSpawnerZone" -> {
                // Registers a rectangle where the MobSpawner (time-of-day spawning) may place mobs.
                // Place a Rectangle object in the Events layer with type=MobSpawnerZone.
                gp.mobSpawner.addZone(worldX, worldY, Math.max(gp.tileSize, areaW), Math.max(gp.tileSize, areaH));
            }
            case "QuestDefinition" -> {
                // Register a new quest from Tiled map properties.
                // Properties: questId (String), questName (String), questDesc (String), target (int)
                String qId   = getStringProperty(obj, "questId", "");
                String qName = getStringProperty(obj, "questName", "");
                String qDesc = getStringProperty(obj, "questDesc", "");
                int    qTgt  = getIntProperty(obj, "target", 1);
                if (!qId.isEmpty() && !qName.isEmpty() && gp.questManager != null) {
                    gp.questManager.addQuest(qId, qName, qDesc, qTgt);
                    System.out.println("MapObjectLoader: Quest registered '" + qId + "' -> " + qName);
                }
            }
            case "WaterZone" -> {
                // Check for a polygon child — if present, register the exact polygon shape.
                // NOTE: worldX/worldY have already been shifted to the bounding-box top-left
                // by the time loadEvent is called (worldX += minPX*sf). To recover absolute
                // world coords for each point we use: worldX + (px - minPX)*sf, which requires
                // a two-pass parse (first to find minPX/minPY, then to build the polygon).
                NodeList polyNodes = obj.getElementsByTagName("polygon");
                if (polyNodes.getLength() > 0) {
                    String pts = ((org.w3c.dom.Element) polyNodes.item(0)).getAttribute("points");
                    if (pts != null && !pts.isEmpty()) {
                        String[] tokens = pts.trim().split("\\s+");
                        double[] rawX = new double[tokens.length];
                        double[] rawY = new double[tokens.length];
                        int n = 0;
                        double minPX = Double.MAX_VALUE, minPY = Double.MAX_VALUE;
                        for (String pt : tokens) {
                            String[] xy = pt.split(",");
                            if (xy.length < 2) continue;
                            rawX[n] = Double.parseDouble(xy[0]);
                            rawY[n] = Double.parseDouble(xy[1]);
                            if (rawX[n] < minPX) minPX = rawX[n];
                            if (rawY[n] < minPY) minPY = rawY[n];
                            n++;
                        }
                        if (n >= 3) {
                            int[] xs = new int[n];
                            int[] ys = new int[n];
                            for (int k = 0; k < n; k++) {
                                // worldX is bounding-box top-left: worldX + (px - minPX)*sf
                                xs[k] = worldX + (int)((rawX[k] - minPX) * sf);
                                ys[k] = worldY + (int)((rawY[k] - minPY) * sf);
                            }
                            gp.eHandler.registerWaterZone(new IntPolygon(xs, ys, n));
                        } else {
                            gp.eHandler.registerWaterZone(worldX, worldY, ew, eh);
                        }
                    } else {
                        gp.eHandler.registerWaterZone(worldX, worldY, ew, eh);
                    }
                } else {
                    gp.eHandler.registerWaterZone(worldX, worldY, ew, eh);
                }
            }
            case "MemoryGate" -> {
                int    req  = getIntProperty(obj, "requiredFragments", 1);
                String msg  = getStringProperty(obj, "message",
                    "The path is sealed. You need more memories.").replace("\\n", "\n");
                String tMap = getStringProperty(obj, "targetMap", "");
                int    tCol = getIntProperty(obj, "targetCol", 5);
                int    tRow = getIntProperty(obj, "targetRow", 5);
                String sid  = getStringProperty(obj, "spawnId", "");
                gp.eHandler.registerMemoryGate(worldX, worldY, ew, eh,
                    req, msg, tMap, tCol, tRow, sid);
            }
            default -> System.out.println("MapObjectLoader: Unknown event '" + type + "'");
        }
    }

    /**
     * Creates a monster entity by name for use in dynamic systems (e.g. SpawnZone).
     * No level scaling or property reading — uses defaults.
     */
    public Entity createMonsterByName(String type, int col, int row) {
        String id = switch (type) {
            case "MON_monster"         -> "painted_crab";
            case "MON_SkeletonArcher"  -> "drowned_sketch";
            case "MON_Shade"           -> "shade_wolf";
            case "MON_Inkblot", "Inkblot" -> "inkblot";
            case "BOSS_WitheredTree"   -> "withered_tree";
            default                    -> type;
        };
        Entity m = MonsterFactory.create(gp, id, col, row);
        if (m == null) System.out.println("MapObjectLoader: Unknown monster type '" + type + "'");
        return m;
    }

    private void spawnMonsterArea(Element obj, int startCol, int startRow,
                                  int areaW, int areaH, int startIdx) {
        String monType = getStringProperty(obj, "monster", "MON_monster");
        int    count   = getIntProperty(obj, "count", 3);
        int    cols    = Math.max(1, areaW / gp.tileSize);
        int    rows    = Math.max(1, areaH / gp.tileSize);
        int    pCol    = gp.player.worldX / gp.tileSize;
        int    pRow    = gp.player.worldY / gp.tileSize;
        java.util.Random rng = new java.util.Random();
        int placed = 0;
        for (int i = 0; i < count && startIdx + placed < gp.monster.length; i++) {
            int c = startCol + rng.nextInt(cols);
            int r = startRow + rng.nextInt(rows);
            // Skip positions too close to the player (within 3 tiles)
            if (Math.abs(c - pCol) < 3 && Math.abs(r - pRow) < 3) continue;
            Entity m = createMonster(monType, c, r, obj);
            if (m != null) {
                applyCommonProperties(m, obj);
                gp.monster[startIdx + placed++] = m;
            }
        }
        System.out.println("MapObjectLoader: MonsterArea -> " + placed + " x " + monType);
    }

    /**
     * Creates any game entity by name string.
     * Used for chest loot, generic object fallback, and MonsterArea monster type.
     */
    public Entity createEntityByName(String name) {
        Entity entity = switch (name) {
            case "Compas"  -> ItemFactory.create(gp, "compas");
            case "Key"     -> new OBJ_Key(gp);
            case "Potion"  -> new OBJ_Potion(gp);
            case "Boots"   -> ItemFactory.create(gp, "boots");
            case "Gem"     -> new OBJ_Gem(gp);
            case "Sword", "Normal Sword"   -> ItemFactory.create(gp, "wooden_sword");
            case "Shield", "Wood Shield"   -> ItemFactory.create(gp, "wooden_shield");
            case "Heart"   -> new OBJ_Heart(gp);
            case "Mana"    -> new OBJ_ManaCrystal(gp);
            case "Arrow"   -> new OBJ_Arrow(gp);
            case "Torch"   -> new OBJ_Torch(gp);
            case "Book"    -> ItemFactory.create(gp, "spell_book");
            case "Coins"   -> new OBJ_Coins(gp);
            case "Tower"   -> new OBJ_Tower(gp);
            case "Tent"    -> new OBJ_Tent(gp);
            case "Light"   -> {
                Entity l = new Entity(gp);
                l.name = "Light"; l.type = Entity.TYPE_UTILITY; l.lightSource = true; l.lightRadius = 4; l.collision = false;
                yield l;
            }
            default        -> null;
        };
        if (entity != null) return entity;

        entity = ItemFactory.create(gp, name);
        if (entity != null) return entity;

        return ItemFactory.create(gp, normalizeItemId(name));
    }

    private Entity createFactoryItem(String itemId, Element obj) {
        if (itemId == null || itemId.isBlank()) return null;
        Entity item = ItemFactory.create(gp, itemId);
        if (item == null) item = ItemFactory.create(gp, normalizeItemId(itemId));
        if (item == null) return null;

        int amount = getIntProperty(obj, "amount", 1);
        if (amount > 1) item.amount = amount;
        return item;
    }

    private String normalizeItemId(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private Entity createLightMarker(Element obj, boolean eventLayer) {
        Entity light = new Entity(gp);
        light.name = "Light";
        light.type = Entity.TYPE_UTILITY;  // never picked up or interacted with
        light.lightSource = true;
        light.lightRadius = getIntProperty(obj, "lightRadius", 4);
        String colorHex = getStringProperty(obj, "lightColor", null);
        if (colorHex != null && !colorHex.isBlank()) {
            try { light.lightColor = Color.decode(colorHex.trim()); }
            catch (NumberFormatException ignored) {}
        }
        light.collision = false;
        light.eventLayerLight = eventLayer;
        return light;
    }

    private void spawnTowerEyes() {
        for (Entity entity : gp.obj) {
            if (entity instanceof OBJ_Tower tower) tower.spawnEye();
        }
    }

    private void clearEventLayerLights() {
        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] != null && gp.obj[i].eventLayerLight) {
                gp.obj[i] = null;
            }
        }
    }

    private void applyLoadedWorldPosition(Entity entity, String type, Element obj, int worldX, int worldY) {
        entity.worldX = worldX;
        entity.worldY = worldY;
        if (entity.lightSource && isLightMarkerType(type) && isPointObject(obj)) {
            entity.worldX -= gp.tileSize / 2;
            entity.worldY -= gp.tileSize / 2;
        }
    }

    private boolean isLightMarkerType(String type) {
        return "Light".equalsIgnoreCase(type) || "Lighting".equalsIgnoreCase(type);
    }

    private boolean isPointObject(Element obj) {
        return obj.getElementsByTagName("point").getLength() > 0;
    }

    private int firstFreeSlot(Entity[] arr) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == null) return i;
        return arr.length;
    }

    private int firstFreeSlot(tile.interactiveTile[] arr) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == null) return i;
        return arr.length;
    }

    private int parseDirection(String dir) {
        return switch (dir.trim().toLowerCase()) {
            case "up"    -> Entity.DIR_UP;
            case "left"  -> Entity.DIR_LEFT;
            case "right" -> Entity.DIR_RIGHT;
            default      -> Entity.DIR_DOWN;
        };
    }

    private Document parseXmlResource(String path) throws Exception {
        Document document = ResourceCache.loadXml(path);
        if (document == null) {
            System.out.println("MapObjectLoader: XML resource not found: " + path);
            return null;
        }
        return document;
    }

    /** Reads a named property from the direct {@code <properties>} child of {@code element}. */
    private String getStringProperty(Element element, String name, String defaultValue) {
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int c = 0; c < children.getLength(); c++) {
            if (!(children.item(c) instanceof Element child)) continue;
            if (!"properties".equals(child.getTagName())) continue;
            org.w3c.dom.NodeList props = child.getChildNodes();
            for (int p = 0; p < props.getLength(); p++) {
                if (!(props.item(p) instanceof Element property)) continue;
                if (!"property".equals(property.getTagName())) continue;
                if (!name.equals(property.getAttribute("name"))) continue;
                String value = property.getAttribute("value");
                if (value == null || value.isBlank()) value = property.getTextContent();
                // Treat blank/empty the same as missing — use the caller's default.
                // This makes message="" in Tiled fall back to "You cannot pass here." etc.
                if (value == null || value.isBlank()) return defaultValue;
                return value;
            }
        }
        return defaultValue;
    }

    private String getFirstStringProperty(Element element, String defaultValue, String... names) {
        for (String name : names) {
            String value = getStringProperty(element, name, null);
            if (value != null && !value.isBlank()) return value;
        }
        return defaultValue;
    }

    private int getIntProperty(Element element, String name, int defaultValue) {
        String v = getStringProperty(element, name, null);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private int getFirstIntProperty(Element element, int defaultValue, String... names) {
        for (String name : names) {
            String value = getStringProperty(element, name, null);
            if (value == null || value.isBlank()) continue;
            try { return Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private float getFloatProperty(Element element, String name, float defaultValue) {
        String v = getStringProperty(element, name, null);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean getBoolProperty(Element element, String name, boolean defaultValue) {
        String v = getStringProperty(element, name, null);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    private boolean getFirstBoolProperty(Element element, boolean defaultValue, String... names) {
        for (String name : names) {
            String value = getStringProperty(element, name, null);
            if (value == null || value.isBlank()) continue;
            return Boolean.parseBoolean(value.trim());
        }
        return defaultValue;
    }
}

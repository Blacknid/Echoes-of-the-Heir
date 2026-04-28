package map;

import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import entity.Entity;
import main.GamePanel;
import tiles_interactive.interactiveTile;
import util.ResourceCache;

/**
 * Manages map registry, map transitions, and per-map entity persistence.
 * Extracted from GamePanel to reduce god-class complexity.
 */
public class MapManager {

    private final GamePanel gp;

    // Map registry: id -> tmx path
    public Map<String, String> mapRegistry = new HashMap<>();

    // Track which map is currently active (matches a key in mapRegistry)
    public String currentMapId = "harta";

    // TRANSITION
    public String nextMapId;
    public int nextCol;
    public int nextRow;

    // ENTRY POINT TRACKING
    public String previousMapId = "harta";
    public int previousTriggerCol = 24;
    public int previousTriggerRow = 15;

    // DOOR ENTRY TRACKING
    public int doorEntryCol = -1;
    public int doorEntryRow = -1;

    // MAP PROPERTIES (loaded from Tiled map-level <properties> block)
    public String currentMapDisplayName = "";
    public int defaultSpawnCol = -1;
    public int defaultSpawnRow = -1;
    public java.awt.Color mapBackgroundColor = java.awt.Color.BLACK;
    /** Named spawn point to resolve after map loads. */
    public String nextSpawnId = "";
    /** Message to display once after the player spawns on this map (from dialogueTrigger TMX property). */
    public String pendingDialogueTrigger = "";
    /** How long (in frames) the spawn dialogue message stays on screen. Default 300. */
    public int pendingDialogueTriggerDuration = 300;
    /** Act title card text to show on map entry (from actTitle TMX property). */
    public String pendingActTitle = "";

    /** Spawn position to use when retrying after death (set on every map transition). */
    public int retrySpawnCol = -1;
    public int retrySpawnRow = -1;

    public boolean loadingGame = false;

    // MAP ENTITY STORAGE: Preserve entity states when switching between maps
    private final Map<String, Entity[]> savedObjects = new HashMap<>();
    private final Map<String, Entity[]> savedNPCs = new HashMap<>();
    private final Map<String, Entity[]> savedMonsters = new HashMap<>();
    private final Map<String, interactiveTile[]> savedITiles = new HashMap<>();

    public MapManager(GamePanel gp) {
        this.gp = gp;
    }

    public void registerMap(String id, String tmxPath) {
        mapRegistry.put(id, tmxPath);
    }

    /**
     * Auto-discover all .tmx map files in /res/maps/ and register them.
     * Map ID = filename without extension, lowercased (e.g. "Awakening_Cave.tmx" → "awakening_cave").
     * If the TMX has a map-level "mapId" property, that overrides the filename-derived ID.
     */
    public void discoverMaps() {
        mapRegistry.clear();
        String mapsDir = "/res/maps/";
        try {
            // Try classpath resource listing (works in JAR and filesystem)
            URL dirUrl = getClass().getResource(mapsDir);
            if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
                // Running from filesystem (IDE / bin folder)
                java.io.File dir = new java.io.File(dirUrl.toURI());
                java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".tmx"));
                if (files != null) {
                    for (java.io.File f : files) {
                        String tmxPath = mapsDir + f.getName();
                        String id = deriveMapId(f.getName(), tmxPath);
                        mapRegistry.put(id, tmxPath);
                    }
                }
            } else if (dirUrl != null && "jar".equals(dirUrl.getProtocol())) {
                // Running from JAR
                String jarPath = dirUrl.getPath().substring(5, dirUrl.getPath().indexOf("!"));
                try (JarFile jar = new JarFile(java.net.URLDecoder.decode(jarPath, "UTF-8"))) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entryName = entries.nextElement().getName();
                        // entries are like "res/maps/harta.tmx" (no leading slash)
                        String prefix = mapsDir.substring(1); // "res/maps/"
                        if (entryName.startsWith(prefix) && entryName.toLowerCase().endsWith(".tmx")) {
                            String fileName = entryName.substring(prefix.length());
                            if (fileName.contains("/")) continue; // skip subdirectories
                            String tmxPath = mapsDir + fileName;
                            String id = deriveMapId(fileName, tmxPath);
                            mapRegistry.put(id, tmxPath);
                        }
                    }
                }
            }

            if (mapRegistry.isEmpty()) {
                System.out.println("MapManager WARNING: No .tmx files discovered in " + mapsDir);
            } else {
                System.out.println("MapManager: Discovered " + mapRegistry.size() + " maps:");
                for (Map.Entry<String, String> entry : mapRegistry.entrySet()) {
                    System.out.println("  \"" + entry.getKey() + "\" -> " + entry.getValue());
                }
            }
        } catch (Exception e) {
            System.out.println("MapManager: Failed to discover maps from " + mapsDir);
            e.printStackTrace(System.out);
        }
    }

    /**
     * Derive a map ID from a .tmx filename. Checks the TMX for an explicit "mapId" property first.
     * Falls back to filename without extension, lowercased.
     */
    private String deriveMapId(String fileName, String tmxPath) {
        // Try reading mapId from TMX properties
        try {
            Document doc = ResourceCache.loadXml(tmxPath);
            if (doc != null) {
                Element mapRoot = doc.getDocumentElement();
                NodeList propsList = mapRoot.getChildNodes();
                for (int i = 0; i < propsList.getLength(); i++) {
                    if (!(propsList.item(i) instanceof Element)) continue;
                    Element child = (Element) propsList.item(i);
                    if (!"properties".equals(child.getTagName())) continue;
                    NodeList props = child.getElementsByTagName("property");
                    for (int j = 0; j < props.getLength(); j++) {
                        Element prop = (Element) props.item(j);
                        if ("mapId".equals(prop.getAttribute("name"))) {
                            String val = prop.getAttribute("value").trim();
                            if (!val.isEmpty()) return val;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // If TMX can't be parsed for mapId, fall back to filename
        }
        // Filename without extension, lowercased
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.toLowerCase();
    }

    /**
     * Begin a smooth fade-to-black → map load → fade-from-black transition.
     */
    public void startTransition(String mapId, int spawnCol, int spawnRow) {
        nextMapId = mapId;
        nextCol = spawnCol;
        nextRow = spawnRow;
        gp.ui.transitionAlpha = 0f;
        gp.ui.subState = 0;
        gp.gameState = GamePanel.transitionState;
    }

    /** Called by UI.drawTransition() at peak darkness. */
    public void changeMap() {
        changeMap(nextMapId, nextCol, nextRow);
    }

    public void changeMap(String mapIdOrPath, int spawnCol, int spawnRow) {
        String path = mapRegistry.getOrDefault(mapIdOrPath, mapIdOrPath);

        previousMapId = currentMapId;
        previousTriggerCol = gp.eHandler.lastTriggerCol;
        previousTriggerRow = gp.eHandler.lastTriggerRow;

        if (mapRegistry.containsKey(mapIdOrPath)) {
            currentMapId = mapIdOrPath;
        } else {
            currentMapId = mapIdOrPath;
            for (Map.Entry<String, String> entry : mapRegistry.entrySet()) {
                if (entry.getValue().equals(mapIdOrPath)) {
                    currentMapId = entry.getKey();
                    break;
                }
            }
        }

        gp.tileM.mapLayers.clear();
        gp.tileM.loadMapFromTMX(path);
        gp.tileM.loadCollisionLayer(path);
        gp.tileM.initTileLitMap();
        gp.mapObjectLoader.loadMapProperties(path);
        gp.cChecker.updateCollisionRectsCache();
        if (gp.minimap != null) gp.minimap.bakeTerrainImage();

        saveMapEntities(previousMapId);

        for (int i = 0; i < gp.obj.length; i++) gp.obj[i] = null;
        for (int i = 0; i < gp.npc.length; i++) gp.npc[i] = null;
        for (int i = 0; i < gp.monster.length; i++) gp.monster[i] = null;
        for (int i = 0; i < gp.iTile.length; i++) gp.iTile[i] = null;
        gp.projectilesList.clear();
        gp.particleList.clear();

        // Invalidate torch shadow cache — torches on new map are different
        if (gp.eManager != null && gp.eManager.lightning != null) {
            gp.eManager.lightning.clearShadowCaches();
        }

        gp.eHandler.reset();

        if (savedObjects.containsKey(currentMapId)) {
            restoreMapEntities(currentMapId);
        } else {
            gp.aSetter.setObject();
            gp.aSetter.setInteractiveTile();
            gp.aSetter.setNPC();
            gp.aSetter.setMonster();
            gp.aSetter.loadEntitiesFromTMX();
        }

        gp.aSetter.loadEventsFromTMX();

        if (nextSpawnId != null && !nextSpawnId.isEmpty()) {
            int[] sp = gp.eHandler.getNamedSpawnPoint(nextSpawnId);
            if (sp != null) {
                spawnCol = sp[0];
                spawnRow = sp[1];
                System.out.println("MapManager: Resolved spawnId '" + nextSpawnId + "' -> (" + spawnCol + "," + spawnRow + ")");
            } else {
                System.out.println("MapManager: SpawnId '" + nextSpawnId + "' not found, using col/row fallback");
            }
            nextSpawnId = "";
        }

        // -1 means "use map default spawn" (set by SpawnPoint objects during loadEventsFromTMX)
        if (spawnCol < 0 || spawnRow < 0) {
            if (defaultSpawnCol >= 0 && defaultSpawnRow >= 0) {
                spawnCol = defaultSpawnCol;
                spawnRow = defaultSpawnRow;
            } else {
                // Last resort: map center
                spawnCol = gp.maxWorldCol / 2;
                spawnRow = gp.maxWorldRow / 2;
            }
        }

        gp.player.worldX = spawnCol * gp.tileSize;
        gp.player.worldY = spawnRow * gp.tileSize;

        // Remember where the player entered so death-retry respawns here
        retrySpawnCol = spawnCol;
        retrySpawnRow = spawnRow;

        // Show map-entry dialogue trigger message if one was defined
        if (!pendingDialogueTrigger.isEmpty()) {
            gp.ui.addMessage(pendingDialogueTrigger, new java.awt.Color(255, 240, 180), pendingDialogueTriggerDuration);
            pendingDialogueTrigger = "";
        }

        // Show act title card if one was defined for this map
        if (!pendingActTitle.isEmpty()) {
            gp.ui.showActTitle(pendingActTitle);
            pendingActTitle = "";
        }

        doorEntryCol = -1;
        doorEntryRow = -1;
    }

    private void saveMapEntities(String mapId) {
        Entity[] objCopy = new Entity[gp.obj.length];
        Entity[] npcCopy = new Entity[gp.npc.length];
        Entity[] monsterCopy = new Entity[gp.monster.length];
        interactiveTile[] iTileCopy = new interactiveTile[gp.iTile.length];

        System.arraycopy(gp.obj, 0, objCopy, 0, gp.obj.length);
        System.arraycopy(gp.npc, 0, npcCopy, 0, gp.npc.length);
        System.arraycopy(gp.monster, 0, monsterCopy, 0, gp.monster.length);
        System.arraycopy(gp.iTile, 0, iTileCopy, 0, gp.iTile.length);

        savedObjects.put(mapId, objCopy);
        savedNPCs.put(mapId, npcCopy);
        savedMonsters.put(mapId, monsterCopy);
        savedITiles.put(mapId, iTileCopy);
    }

    private void restoreMapEntities(String mapId) {
        Entity[] objCopy = savedObjects.get(mapId);
        Entity[] npcCopy = savedNPCs.get(mapId);
        Entity[] monsterCopy = savedMonsters.get(mapId);
        interactiveTile[] iTileCopy = savedITiles.get(mapId);

        if (objCopy != null) System.arraycopy(objCopy, 0, gp.obj, 0, gp.obj.length);
        if (npcCopy != null) System.arraycopy(npcCopy, 0, gp.npc, 0, gp.npc.length);
        if (monsterCopy != null) System.arraycopy(monsterCopy, 0, gp.monster, 0, gp.monster.length);
        if (iTileCopy != null) System.arraycopy(iTileCopy, 0, gp.iTile, 0, gp.iTile.length);
    }

    public void resetGame(boolean restart) {
        gp.deathSoundPlayed = false;

        if (restart) {
            currentMapId = "Awakening Cave";
            String path = mapRegistry.getOrDefault(currentMapId, "/res/maps/Awakening_Cave.tmx");
            gp.tileM.mapLayers.clear();
            gp.tileM.loadMapFromTMX(path);
            gp.tileM.loadCollisionLayer(path);
            gp.cChecker.updateCollisionRectsCache();
            if (gp.minimap != null) gp.minimap.bakeTerrainImage();

            savedObjects.clear();
            savedNPCs.clear();
            savedMonsters.clear();
            savedITiles.clear();

            for (int i = 0; i < gp.obj.length; i++) gp.obj[i] = null;
            for (int i = 0; i < gp.npc.length; i++) gp.npc[i] = null;
            for (int i = 0; i < gp.monster.length; i++) gp.monster[i] = null;
            for (int i = 0; i < gp.iTile.length; i++) gp.iTile[i] = null;
            gp.projectilesList.clear();
            gp.particleList.clear();
            gp.eHandler.reset();

            gp.player.setDefaultValues();
            gp.aSetter.setObject();
            gp.aSetter.setInteractiveTile();

            retrySpawnCol = -1;
            retrySpawnRow = -1;
        }

        gp.player.restoreLifeAndMana();
        gp.aSetter.setObject();
        gp.aSetter.setInteractiveTile();
        gp.aSetter.setNPC();
        gp.aSetter.setMonster();
        gp.aSetter.loadEntitiesFromTMX();
        gp.aSetter.loadEventsFromTMX();

        if (!restart && retrySpawnCol >= 0) {
            gp.player.worldX = retrySpawnCol * gp.tileSize;
            gp.player.worldY = retrySpawnRow * gp.tileSize;
            gp.player.direction = Entity.DIR_DOWN;
        } else {
            gp.player.setDefaultPositions();
        }
    }
}

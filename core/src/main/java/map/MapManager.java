package map;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import entity.Entity;
import main.GamePanel;
import tile.interactiveTile;
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
    public String currentMapId = "awakening_cave";

    public String nextMapId;
    public int nextCol;
    public int nextRow;

    public String previousMapId = "awakening_cave";
    public int previousTriggerCol = 24;
    public int previousTriggerRow = 15;

    public int doorEntryCol = -1;
    public int doorEntryRow = -1;

    // MAP PROPERTIES (loaded from Tiled map-level <properties> block)
    public String currentMapDisplayName = "";
    public int defaultSpawnCol = -1;
    public int defaultSpawnRow = -1;
    /** True once a SpawnPoint with newGame=true has been registered for the current map load. */
    public boolean hasNewGameSpawn = false;
    public gfx.Color mapBackgroundColor = gfx.Color.BLACK;
    /** Named spawn point to resolve after map loads. */
    public String nextSpawnId = "";
    /** Message to display once after the player spawns on this map (from dialogueTrigger TMX property). */
    public String pendingDialogueTrigger = "";
    /** How long (in frames) the spawn dialogue message stays on screen. Default 300. */
    public int pendingDialogueTriggerDuration = 300;
    /** Act title card text to show on map entry (from actTitle TMX property). */
    public String pendingActTitle = "";
    /** When true, the pending actTitle is only shown during the New Game cutscene, never on normal map transitions. */
    public boolean pendingActTitleNewGameOnly = false;
    /** Map IDs whose actTitle has already been shown this run — never shown again until New Game. */
    public final java.util.Set<String> shownActTitles = new java.util.HashSet<>();

    /** Spawn position to use when retrying after death (set on every map transition). */
    public int retrySpawnCol = -1;
    public int retrySpawnRow = -1;

    public boolean loadingGame = false;

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
            // Dev mode: scan the live source folder first so newly added maps show up
            // without a rebuild (matches ResourceCache's dev-source live-reload).
            java.io.File devSourceDir = ResourceCache.getDevSourceDir();
            if (devSourceDir != null) {
                java.io.File dir = new java.io.File(devSourceDir, mapsDir);
                java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".tmx"));
                if (files != null) {
                    for (java.io.File f : files) {
                        String tmxPath = mapsDir + f.getName();
                        String id = deriveMapId(f.getName(), tmxPath);
                        mapRegistry.put(id, tmxPath);
                    }
                }
            }

            // Packaged/runtime: libGDX's FileHandle.list() works uniformly across the desktop
            // (jar or filesystem classpath) and Android (APK asset) backends, unlike
            // Class.getResource()/URL, which returns null for Android's opaque asset scheme.
            if (mapRegistry.isEmpty()) {
                com.badlogic.gdx.files.FileHandle dir = com.badlogic.gdx.Gdx.files.internal(mapsDir.substring(1));
                if (dir.exists() && dir.isDirectory()) {
                    for (com.badlogic.gdx.files.FileHandle f : dir.list()) {
                        if (!f.extension().equalsIgnoreCase("tmx")) continue;
                        String tmxPath = mapsDir + f.name();
                        String id = deriveMapId(f.name(), tmxPath);
                        mapRegistry.put(id, tmxPath);
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

        try {
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
        gp.cloudLayer.clearAll();
        gp.dustFogLayer.clearAll();
        gp.tensionBeats.reset();

        // Invalidate torch shadow cache — torches on new map are different
        if (gp.eManager != null && gp.eManager.lightning != null) {
            gp.eManager.lightning.clearShadowCaches();
        }

        // Invalidate render pipeline world cache to prevent black ground after transitions
        if (gp.renderPipeline != null) {
            gp.renderPipeline.invalidateWorldCache();
        }

        gp.eHandler.reset();

        // Load this map's painted wind field (or code gradient fallback).
        gp.windField.loadForMap(currentMapId, gp.tileM.currentMapCols, gp.tileM.currentMapRows);

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
        gp.player.snapCamera();
        gp.player.spawnFadeAlpha = 0f;

        // Remember where the player entered so death-retry respawns here
        retrySpawnCol = spawnCol;
        retrySpawnRow = spawnRow;

        // Show map-entry dialogue trigger message if one was defined.
        // Skip during save-game loading so stale map messages don't pop up.
        if (!pendingDialogueTrigger.isEmpty()) {
            if (!loadingGame) {
                gp.ui.addMessage(pendingDialogueTrigger, new gfx.Color(255, 240, 180), pendingDialogueTriggerDuration);
            }
            pendingDialogueTrigger = "";
        }

        // Show act title card if one was defined for this map, but only once per run.
        // If actTitleNewGameOnly=true, skip here — the New Game cutscene will show it instead.
        // Skip during save-game loading for the same reason.
        if (!pendingActTitle.isEmpty()) {
            if (!loadingGame && !pendingActTitleNewGameOnly && !shownActTitles.contains(currentMapId)) {
                shownActTitles.add(currentMapId);
                gp.ui.showActTitle(pendingActTitle);
            }
            if (pendingActTitleNewGameOnly) {
                // Leave pendingActTitle set so the cutscene can consume it.
            } else {
                pendingActTitle = "";
            }
        }

        doorEntryCol = -1;
        doorEntryRow = -1;

        // Notify quest manager so "go" steps complete on map arrival
        if (gp.questManager != null) {
            gp.questManager.notifyMapEntered(currentMapId);
        }
        } catch (Throwable t) {
            // A failure here (bad TMX, missing asset, etc.) used to propagate up through the
            // render loop and kill the whole app mid-transition (black screen, music cut).
            // Log the full trace so the real cause is visible, and keep the app alive.
            System.out.println("[MapManager] changeMap FAILED for '" + mapIdOrPath + "' (path=" + path + "):");
            t.printStackTrace(System.out);
            try {
                // GameStorage.outputStream always truncates (no append mode across both
                // backends) — read-modify-write to preserve prior crash entries in the file.
                byte[] prior = new byte[0];
                if (platform.GameStorage.exists("crash_log.txt")) {
                    try (java.io.InputStream is = platform.GameStorage.inputStream("crash_log.txt")) {
                        prior = is.readAllBytes();
                    }
                }
                try (java.io.OutputStream os = platform.GameStorage.outputStream("crash_log.txt");
                     java.io.PrintWriter pw = new java.io.PrintWriter(os)) {
                    os.write(prior);
                    pw.println("=== changeMap FAILED for '" + mapIdOrPath + "' (path=" + path + ") ===");
                    t.printStackTrace(pw);
                }
            } catch (Exception ignored) {}
        }
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
            currentMapId = "awakening_cave";
            String path = mapRegistry.getOrDefault(currentMapId, "/res/maps/Awakening_Cave.tmx");
            gp.tileM.mapLayers.clear();
            gp.tileM.loadMapFromTMX(path);
            gp.tileM.loadCollisionLayer(path);
            gp.tileM.initTileLitMap();
            gp.mapObjectLoader.loadMapProperties(path);
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
            // Note: setObject() and setInteractiveTile() are called in the common block
            // below, so we skip them here to avoid spawning objects twice.

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

    /**
     * Drop the cached entity state for the given map so the next load
     * (or debug reload) spawns entities fresh from the TMX/JSON definitions
     * rather than restoring whatever state they were saved in.
     */
    public void clearSavedMapEntities(String mapId) {
        savedObjects.remove(mapId);
        savedNPCs.remove(mapId);
        savedMonsters.remove(mapId);
        savedITiles.remove(mapId);
    }
}

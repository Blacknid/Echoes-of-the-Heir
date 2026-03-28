package map;

import java.util.HashMap;
import java.util.Map;

import entity.Entity;
import main.GamePanel;
import tiles_interactive.interactiveTile;

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

    public boolean loadingGame = false;

    // MAP ENTITY STORAGE: Preserve entity states when switching between maps
    private Map<String, Entity[]> savedObjects = new HashMap<>();
    private Map<String, Entity[]> savedNPCs = new HashMap<>();
    private Map<String, Entity[]> savedMonsters = new HashMap<>();
    private Map<String, interactiveTile[]> savedITiles = new HashMap<>();

    public MapManager(GamePanel gp) {
        this.gp = gp;
    }

    public void registerMap(String id, String tmxPath) {
        mapRegistry.put(id, tmxPath);
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

        gp.player.worldX = spawnCol * gp.tileSize;
        gp.player.worldY = spawnRow * gp.tileSize;

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
            currentMapId = "harta";
            String path = mapRegistry.getOrDefault(currentMapId, "/res/maps/harta.tmx");
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
        }

        gp.player.restoreLifeAndMana();
        gp.aSetter.setNPC();
        gp.aSetter.setMonster();

        if (restart) {
            gp.aSetter.loadEntitiesFromTMX();
        }
        gp.aSetter.loadEventsFromTMX();
        gp.player.setDefaultPositions();
    }
}

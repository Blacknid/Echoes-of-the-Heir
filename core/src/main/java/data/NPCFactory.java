package data;

import gfx.Sprite;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import entity.Entity;
import entity.NPC_Generic;
import main.GamePanel;

/**
 * Creates NPC entities from JSON definitions in res/data/npcs.json.
 * Adding a new NPC = add a JSON entry + spritesheets, zero Java code.
 *
 * JSON structure per NPC:
 * {
 *   "id": "smith",
 *   "name": "Gunther the Smith",
 *   "sprite": "/res/NPC/smith_walk-sheet",
 *   "idleSprite": "/res/NPC/smith_idle-sheet",
 *   "speed": 1,
 *   "walkFrameCount": 6,
 *   "staticNPC": true,
 *   "guardMode": false,
 *   "portrait": "/res/NPC/smith_portrait",
 *   "lightRadius": 4,
 *   "lightColor": "#8844ff",
 *   "activities": {
 *     "forge": { "sprite": "/res/NPC/smith_forge-sheet", "frames": [4,4,4,4], "speed": 10 },
 *     "hammer": { "sprite": "/res/NPC/smith_hammer-sheet", "frames": [6,6,6,6], "speed": 8 }
 *   },
 *   "dialogues": {
 *     "0": ["Welcome, traveler.", "Bring me iron ore."],
 *     "1": ["Good iron! Let me work.", "Come back soon."],
 *     "2": ["Your sword is ready!"]
 *   },
 *   "states": [
 *     { "id": "idle_shop", "direction": 0, "dialogueSet": 0, "stationary": true },
 *     { "id": "forging", "animation": "forge", "direction": 3, "offset": [2, 0],
 *       "dialogueSet": 1, "stationary": true, "requiredQuestComplete": "gather_iron" }
 *   ]
 * }
 */
public class NPCFactory {

    private static final HashMap<String, NPCDef> npcDefs = new HashMap<>();
    private static boolean loaded = false;

    /** Clears the cached definitions so the next call to loadDefinitions() re-reads the JSON.
     *  Also auto-syncs src/res/data/npcs.json → bin/res/data/npcs.json when running from
     *  the file system (debug mode), so pressing R picks up edits without a manual sync step. */
    public static void invalidateCache() {
        try {
            java.net.URL url = NPCFactory.class.getResource("/res/data/npcs.json");
            if (url != null && "file".equals(url.getProtocol())) {
                java.io.File binFile = new java.io.File(url.toURI());
                java.io.File srcFile = new java.io.File(
                    binFile.getAbsolutePath().replace(
                        java.io.File.separator + "bin" + java.io.File.separator,
                        java.io.File.separator + "src" + java.io.File.separator));
                if (srcFile.exists()) {
                    java.nio.file.Files.copy(srcFile.toPath(), binFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[NPCFactory] Synced npcs.json from src");
                }
            }
        } catch (Exception e) {
            System.out.println("[NPCFactory] Sync warning: " + e.getMessage());
        }
        npcDefs.clear();
        loaded = false;
    }

    /** Load NPC definitions from JSON resource. Call once at startup. */
    public static void loadDefinitions() {
        if (loaded) return;
        loaded = true;

        try (InputStream is = util.ResourceCache.openClasspathStream("/res/data/npcs.json")) {
            if (is == null) {
                System.out.println("[NPCFactory] npcs.json not found — no data-driven NPCs loaded");
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseJsonArray(sb.toString());
            System.out.println("[NPCFactory] Loaded " + npcDefs.size() + " NPC definitions");
        } catch (Exception e) {
            System.out.println("[NPCFactory] Error loading npcs.json: " + e.getMessage());
        }
    }

    /** Check if a definition exists for the given id. */
    public static boolean has(String id) {
        if (!loaded) loadDefinitions();
        return npcDefs.containsKey(id);
    }

    /**
     * Create an NPC entity from its JSON id. Applies all JSON-defined properties
     * (sprites, dialogues, activities, states). Returns null if id not found.
     *
     * @param gp  GamePanel reference
     * @param id  NPC definition id from npcs.json
     * @param col tile column for spawn position
     * @param row tile row for spawn position
     */
    public static NPC_Generic create(GamePanel gp, String id, int col, int row) {
        return createAt(gp, id, col * gp.tileSize, row * gp.tileSize);
    }

    /**
     * Create an NPC at an exact pixel position. col/row are derived from the
     * pixel coords so spawnCol/spawnRow always match the actual world position.
     */
    public static NPC_Generic createAt(GamePanel gp, String id, int worldX, int worldY) {
        if (!loaded) loadDefinitions();

        NPCDef def = npcDefs.get(id);
        if (def == null) {
            System.out.println("[NPCFactory] Unknown NPC id: '" + id + "'");
            return null;
        }

        int col = worldX / gp.tileSize;
        int row = worldY / gp.tileSize;

        NPC_Generic npc = new NPC_Generic(gp);
        npc.worldX = worldX;
        npc.worldY = worldY;
        npc.spawnCol = col;
        npc.spawnRow = row;

        if (def.props.containsKey("name")) npc.name = def.props.get("name");
        if (def.props.containsKey("speed")) npc.speed = intVal(def.props, "speed", 1);
        if (def.props.containsKey("walkFrameCount")) npc.walkFrameCount = intVal(def.props, "walkFrameCount", 6);
        if (boolVal(def.props, "staticNPC", false)) npc.staticNPC = true;
        if (boolVal(def.props, "guardMode", false)) npc.guardMode = true;
        int idleDir = intVal(def.props, "idleDirection", -1);
        if (idleDir >= 0) npc.idleDirection = idleDir;
        int idleRowsVal = intVal(def.props, "idleRows", 4);
        npc.idleRows = idleRowsVal;
        float spriteAspectVal = floatVal(def.props, "spriteAspect", 1.0f);
        npc.spriteAspect = Math.max(0.1f, spriteAspectVal);
        float spriteScaleVal = floatVal(def.props, "spriteScale", 1.0f);
        if (spriteScaleVal > 0) npc.spriteScale = spriteScaleVal;
        int interactRangeVal = intVal(def.props, "interactRange", 0);
        npc.interactRange = interactRangeVal;
        int depthSortYOffsetVal = intVal(def.props, "depthSortYOffset", 0);
        npc.depthSortYOffset = depthSortYOffsetVal;
        // Activity key to switch to while this NPC is being talked to (e.g. blacksmith stops
        // forging and just idles mid-conversation), restored automatically when dialogue ends.
        String dialogueActivityVal = def.props.get("dialogueActivity");
        if (dialogueActivityVal != null && !dialogueActivityVal.isBlank()) {
            npc.dialogueActivity = dialogueActivityVal;
        }
        String portrait = def.props.get("portrait");
        if (portrait != null && !portrait.isBlank()) npc.portraitPath = portrait;

        // lightRadius / lightColor: make this NPC emit light so it beckons the player in dark areas.
        // Mirrors MapObjectLoader's Tiled "lightRadius"/"lightColor" NPC properties, but data-driven from npcs.json.
        int npcLightRadius = intVal(def.props, "lightRadius", -1);
        if (npcLightRadius > 0) {
            npc.lightSource = true;
            npc.lightRadius = npcLightRadius;
            String npcLightColor = def.props.get("lightColor");
            if (npcLightColor != null && !npcLightColor.isBlank()) {
                try { npc.lightColor = gfx.Color.decode(npcLightColor.trim()); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (def.props.containsKey("sprite")) npc.spritePath = def.props.get("sprite");
        if (def.props.containsKey("idleSprite")) npc.idleSpritePath = def.props.get("idleSprite");

        for (Map.Entry<String, ActivityDef> entry : def.activities.entrySet()) {
            String actName = entry.getKey();
            ActivityDef act = entry.getValue();
            if (act.sprite == null) continue;
            try {
                Sprite[][] mapped = new Sprite[4][];
                int cellW, cellH;
                if (act.frameWidth > 0 && act.frameHeight > 0) {
                    // Explicit pixel size (e.g. "frameWidth": 48, "frameHeight": 48) — simplest,
                    // least error-prone option: no aspect-ratio math, just the exact cell size.
                    cellW = act.frameWidth;
                    cellH = act.frameHeight;
                } else {
                    // Aspect-derived cell size — same approach idleSprite/walk already use, so
                    // non-square frames (e.g. a forging pose taller than wide) aren't squashed into
                    // a square cell before being scaled to tileSize x tileSize. -1 (unset) falls
                    // back to the NPC's own spriteAspect.
                    float aspect = act.aspect > 0 ? act.aspect : spriteAspectVal;
                    Sprite rawSheet = util.ResourceCache.loadImage(act.sprite + ".png");
                    int rows = act.framesPerRow.length;
                    cellH = rawSheet.getHeight() / Math.max(1, rows);
                    cellW = Math.max(1, Math.round(cellH * aspect));
                }
                Sprite[][] matrix = npc.loadSpriteMatrix(act.sprite, cellW, cellH);
                // Scale each frame to tileSize*spriteScale (matches the eventual on-screen draw
                // size — see drawW/drawH in Entity.draw/drawOccluder) instead of always a flat
                // tileSize, so a bigger spriteScale gets a sharper source crop instead of the same
                // small frame stretched further at draw time.
                int ts = Math.round(gp.tileSize * spriteScaleVal);
                // Trim/pad each row to the requested frame count (frames:[...] or frameCount).
                Sprite[][] scaled = new Sprite[matrix.length][];
                for (int r = 0; r < matrix.length; r++) {
                    int wanted = r < act.framesPerRow.length ? act.framesPerRow[r] : matrix[r].length;
                    scaled[r] = new Sprite[wanted];
                    for (int c = 0; c < wanted; c++) {
                        Sprite src = c < matrix[r].length ? matrix[r][c] : matrix[r][matrix[r].length - 1];
                        scaled[r][c] = util.UtilityTool.scaleImage(src, ts, ts);
                    }
                }
                // Map rows: 0=down, 1=left, 2=right, 3=up (standard walk sheet order)
                int[] dirMap = {Entity.DIR_DOWN, Entity.DIR_LEFT, Entity.DIR_RIGHT, Entity.DIR_UP};
                for (int r = 0; r < Math.min(scaled.length, dirMap.length); r++) {
                    mapped[dirMap[r]] = scaled[r];
                }
                // Fill missing directions from row 0 (single-row sprite sheets)
                if (mapped[Entity.DIR_DOWN] != null) {
                    if (mapped[Entity.DIR_UP]    == null) mapped[Entity.DIR_UP]    = mapped[Entity.DIR_DOWN];
                    if (mapped[Entity.DIR_LEFT]  == null) mapped[Entity.DIR_LEFT]  = mapped[Entity.DIR_DOWN];
                    if (mapped[Entity.DIR_RIGHT] == null) mapped[Entity.DIR_RIGHT] = mapped[Entity.DIR_DOWN];
                }
                if (npc.activityAnimations == null) npc.activityAnimations = new HashMap<>();
                npc.activityAnimations.put(actName, mapped);
                if (act.speed > 0) {
                    if (npc.activityAnimSpeeds == null) npc.activityAnimSpeeds = new HashMap<>();
                    npc.activityAnimSpeeds.put(actName, act.speed);
                }
            } catch (Exception e) {
                System.out.println("[NPCFactory] Failed to load activity '" + actName + "' sprite for NPC '" + id + "': " + e.getMessage());
            }
        }

        // Load walk/idle sprites (after setting paths)
        npc.getImage();

        // Dialogues — support both numeric ("0","1") and named ("intro","thanks") keys
        {
            int nextNamedIdx = 0;
            // First pass: find max numeric index so named keys start after
            for (String key : def.dialogues.keySet()) {
                try { int n = Integer.parseInt(key); if (n >= nextNamedIdx) nextNamedIdx = n + 1; }
                catch (NumberFormatException e) { /* named key */ }
            }
            for (Map.Entry<String, ArrayList<String>> entry : def.dialogues.entrySet()) {
                String key = entry.getKey();
                int setIdx;
                try { setIdx = Integer.parseInt(key); }
                catch (NumberFormatException e) { setIdx = nextNamedIdx++; }
                if (npc.dialogueNameMap == null) npc.dialogueNameMap = new java.util.HashMap<>();
                npc.dialogueNameMap.put(key, setIdx);
                ArrayList<String> lines = entry.getValue();
                String[][] dialogues = npc.ensureDialogues();
                if (setIdx >= 0 && setIdx < dialogues.length) {
                    for (int l = 0; l < lines.size() && l < dialogues[setIdx].length; l++) {
                        dialogues[setIdx][l] = lines.get(l).replace("\\n", "\n");
                    }
                }
            }
        }

        for (StateDef sd : def.states) {
            NPC_Generic.NPCActivityState state = new NPC_Generic.NPCActivityState();
            state.id = sd.id;
            state.animationKey = sd.animation;
            state.direction = sd.direction;
            state.dialogueSet = sd.dialogueSet;
            state.stationary = sd.stationary;
            // Offset-based position: relative to spawn tile
            if (sd.offsetCol != 0 || sd.offsetRow != 0) {
                state.posCol = col + sd.offsetCol;
                state.posRow = row + sd.offsetRow;
            } else {
                state.posCol = sd.posCol;
                state.posRow = sd.posRow;
            }
            state.requiredQuestComplete = sd.requiredQuestComplete;
            state.requiredQuestActive = sd.requiredQuestActive;
            state.dialogueName = sd.dialogueName;
            state.requiredFragments = sd.requiredFragments;
            state.requiredBoss = sd.requiredBoss;
            state.requiredStoryAct = sd.requiredStoryAct;
            state.requiredLevel = sd.requiredLevel;
            state.npcNotMet = sd.npcNotMet;
            state.marksNpcMet = sd.marksNpcMet;
            npc.activityStates.add(state);
        }

        int animSpeed = intVal(def.props, "idleAnimSpeed", -1);
        if (animSpeed > 0) npc.idleAnimationInterval = animSpeed;

        // objectId for save state — default to npcId so Tiled 'id' property is not required
        String objId = def.props.get("objectId");
        npc.objectId = (objId != null && !objId.isBlank()) ? objId : id;

        npc.syncQuestDrivenNpcState();
        return npc;
    }

    private static class NPCDef {
        Map<String, String> props = new HashMap<>();
        HashMap<String, ActivityDef> activities = new HashMap<>();
        LinkedHashMap<String, ArrayList<String>> dialogues = new LinkedHashMap<>();
        ArrayList<StateDef> states = new ArrayList<>();
    }

    private static class ActivityDef {
        String sprite;
        int[] framesPerRow = {6, 6, 6, 6};
        int speed = 0; // 0 = use default idle interval
        // -1 = unset: falls back to the NPC's top-level "spriteAspect" (see spriteAspectVal in
        // createAt). Set an "aspect" key on the activity itself to override just that activity.
        float aspect = -1f;
        // Simplest, least error-prone way to slice a sheet: say the frame size directly in pixels
        // (e.g. "frameWidth": 48, "frameHeight": 48) instead of deriving it from aspect ratio.
        // 0 = unset, falls back to the aspect-derived size.
        int frameWidth = 0;
        int frameHeight = 0;
    }

    private static class StateDef {
        String id;
        String animation;
        String dialogueName;  // named dialogue key (resolved at runtime via dialogueNameMap)
        int direction = -1;
        int posCol = -1, posRow = -1;
        int offsetCol = 0, offsetRow = 0;
        int dialogueSet = -1;
        boolean stationary = false;
        String requiredQuestComplete;
        String requiredQuestActive;
        int requiredFragments = -1;
        int requiredBoss = -1;
        int requiredStoryAct = -1;
        int requiredLevel = -1;
        String npcNotMet = null;
        boolean marksNpcMet = false;
    }

    private static void parseJsonArray(String json) {
        npcDefs.clear();
        json = json.trim();
        if (!json.startsWith("{")) return;

        // Top-level is an object: { "smith": {...}, "baker": {...} }
        // Strip outer braces
        json = json.substring(1, json.length() - 1).trim();

        // Find each top-level key-value pair
        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String npcId = json.substring(keyStart + 1, keyEnd);

            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) break;

            // Find the matching brace for the NPC object
            int braceStart = json.indexOf('{', colon);
            if (braceStart < 0) break;
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceEnd < 0) break;

            String npcJson = json.substring(braceStart + 1, braceEnd);
            NPCDef def = parseNPCDef(npcJson);
            def.props.put("id", npcId);
            npcDefs.put(npcId, def);

            i = braceEnd + 1;
        }
    }

    private static NPCDef parseNPCDef(String json) {
        NPCDef def = new NPCDef();

        // Extract "activities": { ... } block
        int actIdx = json.indexOf("\"activities\"");
        if (actIdx >= 0) {
            int actBrace = json.indexOf('{', actIdx);
            if (actBrace >= 0) {
                int actEnd = findMatchingBrace(json, actBrace);
                if (actEnd >= 0) {
                    parseActivities(json.substring(actBrace + 1, actEnd), def);
                    json = json.substring(0, actIdx) + json.substring(actEnd + 1);
                }
            }
        }

        // Extract "dialogues": { ... } block
        int dlgIdx = json.indexOf("\"dialogues\"");
        if (dlgIdx >= 0) {
            int dlgBrace = json.indexOf('{', dlgIdx);
            if (dlgBrace >= 0) {
                int dlgEnd = findMatchingBrace(json, dlgBrace);
                if (dlgEnd >= 0) {
                    parseDialogues(json.substring(dlgBrace + 1, dlgEnd), def);
                    json = json.substring(0, dlgIdx) + json.substring(dlgEnd + 1);
                }
            }
        }

        // Extract "states": [ ... ] array
        int stIdx = json.indexOf("\"states\"");
        if (stIdx >= 0) {
            int bracketStart = json.indexOf('[', stIdx);
            if (bracketStart >= 0) {
                int bracketEnd = findMatchingBracket(json, bracketStart);
                if (bracketEnd >= 0) {
                    parseStates(json.substring(bracketStart + 1, bracketEnd), def);
                    json = json.substring(0, stIdx) + json.substring(bracketEnd + 1);
                }
            }
        }

        // Parse remaining flat keys
        parseKeyValues(json, def.props);

        return def;
    }

    private static void parseActivities(String json, NPCDef def) {
        // "forge": { "sprite": "...", "frames": [4,4,4,4], "speed": 10 }, ...
        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String actName = json.substring(keyStart + 1, keyEnd);

            int braceStart = json.indexOf('{', keyEnd);
            if (braceStart < 0) break;
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceEnd < 0) break;

            String actJson = json.substring(braceStart + 1, braceEnd);
            ActivityDef act = new ActivityDef();

            int framesIdx = actJson.indexOf("\"frames\"");
            if (framesIdx >= 0) {
                int bStart = actJson.indexOf('[', framesIdx);
                int bEnd = actJson.indexOf(']', bStart);
                if (bStart >= 0 && bEnd >= 0) {
                    String framesStr = actJson.substring(bStart + 1, bEnd).replaceAll("\\s+", "");
                    String[] parts = framesStr.split(",");
                    act.framesPerRow = new int[parts.length];
                    for (int j = 0; j < parts.length; j++) {
                        try { act.framesPerRow[j] = Integer.parseInt(parts[j].trim()); }
                        catch (NumberFormatException e) { act.framesPerRow[j] = 6; }
                    }
                    actJson = actJson.substring(0, framesIdx) + actJson.substring(bEnd + 1);
                }
            }

            Map<String, String> actProps = new HashMap<>();
            parseKeyValues(actJson, actProps);
            act.sprite = actProps.get("sprite");
            if (actProps.containsKey("speed")) {
                try { act.speed = Integer.parseInt(actProps.get("speed")); }
                catch (NumberFormatException e) { /* keep default */ }
            }
            if (actProps.containsKey("aspect")) {
                try { act.aspect = Math.max(0.1f, Float.parseFloat(actProps.get("aspect"))); }
                catch (NumberFormatException e) { /* keep default */ }
            }
            // Simpler alternative to "frames": [n,n,n,n] — one number, same frame count on every
            // direction row. Wins over "frames" if both are present (rare; last one wins by intent).
            if (actProps.containsKey("frameCount")) {
                try {
                    int n = Integer.parseInt(actProps.get("frameCount"));
                    act.framesPerRow = new int[] { n, n, n, n };
                } catch (NumberFormatException e) { /* keep default */ }
            }
            if (actProps.containsKey("frameWidth")) {
                try { act.frameWidth = Integer.parseInt(actProps.get("frameWidth")); }
                catch (NumberFormatException e) { /* keep default */ }
            }
            if (actProps.containsKey("frameHeight")) {
                try { act.frameHeight = Integer.parseInt(actProps.get("frameHeight")); }
                catch (NumberFormatException e) { /* keep default */ }
            }

            def.activities.put(actName, act);
            i = braceEnd + 1;
        }
    }

    private static void parseDialogues(String json, NPCDef def) {
        // "0": ["line1", "line2"], "1": ["line1"], ...
        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String setKey = json.substring(keyStart + 1, keyEnd);

            int bracketStart = json.indexOf('[', keyEnd);
            if (bracketStart < 0) break;
            int bracketEnd = findMatchingBracket(json, bracketStart);
            if (bracketEnd < 0) break;

            String arrJson = json.substring(bracketStart + 1, bracketEnd);
            ArrayList<String> lines = new ArrayList<>();
            int j = 0;
            while (j < arrJson.length()) {
                int qStart = arrJson.indexOf('"', j);
                if (qStart < 0) break;
                int qEnd = findClosingQuote(arrJson, qStart);
                if (qEnd < 0) break;
                lines.add(arrJson.substring(qStart + 1, qEnd));
                j = qEnd + 1;
            }
            def.dialogues.put(setKey, lines);
            i = bracketEnd + 1;
        }
    }

    private static void parseStates(String json, NPCDef def) {
        // Array of state objects: { "id": "forging", ... }, { ... }
        int i = 0;
        while (i < json.length()) {
            int braceStart = json.indexOf('{', i);
            if (braceStart < 0) break;
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceEnd < 0) break;

            String stateJson = json.substring(braceStart + 1, braceEnd);
            StateDef sd = new StateDef();

            // Extract "offset" array: [col, row]
            int offIdx = stateJson.indexOf("\"offset\"");
            if (offIdx >= 0) {
                int bStart = stateJson.indexOf('[', offIdx);
                int bEnd = stateJson.indexOf(']', bStart);
                if (bStart >= 0 && bEnd >= 0) {
                    String[] parts = stateJson.substring(bStart + 1, bEnd).replaceAll("\\s+", "").split(",");
                    if (parts.length >= 2) {
                        try { sd.offsetCol = Integer.parseInt(parts[0]); } catch (NumberFormatException e) {}
                        try { sd.offsetRow = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {}
                    }
                    stateJson = stateJson.substring(0, offIdx) + stateJson.substring(bEnd + 1);
                }
            }

            Map<String, String> stProps = new HashMap<>();
            parseKeyValues(stateJson, stProps);

            sd.id = stProps.get("id");
            sd.animation = stProps.get("animation");
            sd.dialogueName = stProps.get("dialogue");
            sd.direction = intVal(stProps, "direction", -1);
            sd.posCol = intVal(stProps, "posCol", -1);
            sd.posRow = intVal(stProps, "posRow", -1);
            sd.dialogueSet = intVal(stProps, "dialogueSet", -1);
            // If "dialogueSet" was a non-numeric string (e.g. "greeting"), treat it as a named dialogue key
            if (sd.dialogueName == null && sd.dialogueSet < 0) {
                String rawDs = stProps.get("dialogueSet");
                if (rawDs != null && !rawDs.isBlank()) {
                    try { Integer.parseInt(rawDs); } catch (NumberFormatException ignore) { sd.dialogueName = rawDs; }
                }
            }
            sd.stationary = boolVal(stProps, "stationary", false);
            sd.requiredQuestComplete = stProps.get("requiredQuestComplete");
            sd.requiredQuestActive = stProps.get("requiredQuestActive");
            sd.requiredFragments = intVal(stProps, "requiredFragments", -1);
            sd.requiredBoss = intVal(stProps, "requiredBoss", -1);
            sd.requiredStoryAct = intVal(stProps, "requiredStoryAct", -1);
            sd.requiredLevel = intVal(stProps, "requiredLevel", -1);
            sd.npcNotMet = stProps.get("npcNotMet");
            sd.marksNpcMet = boolVal(stProps, "marksNpcMet", false);

            def.states.add(sd);
            i = braceEnd + 1;
        }
    }

    private static void parseKeyValues(String text, Map<String, String> map) {
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
                int valEnd = findClosingQuote(text, valStart);
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

            map.put(key, value);
            i = Math.max(i, valStart + 1);
        }
    }

    private static int findMatchingBrace(String text, int openPos) {
        int depth = 0;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = findClosingQuote(text, i); if (i < 0) return -1; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static int findMatchingBracket(String text, int openPos) {
        int depth = 0;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = findClosingQuote(text, i); if (i < 0) return -1; continue; }
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** Find the closing quote, handling escaped quotes with backslash. */
    private static int findClosingQuote(String text, int openPos) {
        for (int i = openPos + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') { i++; continue; } // skip escaped char
            if (text.charAt(i) == '"') return i;
        }
        return -1;
    }

    private static int intVal(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolVal(Map<String, String> m, String key, boolean def) {
        String v = m.get(key);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v);
    }

    private static float floatVal(Map<String, String> m, String key, float def) {
        String v = m.get(key);
        if (v == null) return def;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return def; }
    }
}

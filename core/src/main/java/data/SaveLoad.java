package data;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import entity.Entity;
import main.GamePanel;
import main.Main;
import main.QuestManager;
import object.OBJ_Chest;
import object.OBJ_Door;
import object.OBJ_Gem;
import object.OBJ_Key;
import object.OBJ_Potion;
import platform.GameStorage;

public class SaveLoad {

    // AES-128 key, 16 bytes (change these values to make your save unique)
    private static final byte[] SAVE_KEY = {
        0x4D,0x69,0x63,0x68,0x69,0x41,0x64,0x76,
        0x65,0x6E,0x74,0x75,0x72,0x65,0x32,0x30
    };

    GamePanel gp;
    private final CloudSaveService cloudSaveService = new CloudSaveService();

    public SaveLoad(GamePanel gp) {
        this.gp = gp;
        cloudSaveService.startHeartbeat();
    }

    public boolean isServerOnline() {
        return cloudSaveService.isServerOnline();
    }

    public CloudSaveService getCloudSaveService() {
        return cloudSaveService;
    }

    private byte[] encrypt(String plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SAVE_KEY, "AES"), new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        // File layout: [16-byte IV][ciphertext]
        byte[] out = new byte[16 + enc.length];
        System.arraycopy(iv,  0, out,  0, 16);
        System.arraycopy(enc, 0, out, 16, enc.length);
        return out;
    }

    private String decrypt(byte[] data) throws GeneralSecurityException {
        if (data.length < 17) {
            throw new GeneralSecurityException("Save file is corrupted or too short");
        }
        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        byte[] enc = new byte[data.length - 16];
        System.arraycopy(data, 16, enc, 0, enc.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SAVE_KEY, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    public Entity getObject(String name) {

        if (name == null || name.equals("NA")) return null;

        switch (name) {
            case "Chest" -> {
                return new OBJ_Chest(gp);
            }
            case "Door" -> {
                return new OBJ_Door(gp);
            }
            case "Gem" -> {
                return new OBJ_Gem(gp);
            }
            case "Key" -> {
                return new OBJ_Key(gp);
            }
            case "Potion" -> {
                return new OBJ_Potion(gp);
            }
            default -> {
            }
        }

        if ("Spell book".equals(name)) return ItemFactory.create(gp, "spell_book");
        if ("Boots".equals(name)) return ItemFactory.create(gp, "boots");
        if ("Compas".equals(name)) return ItemFactory.create(gp, "compas");
        if ("Wood_Shield".equals(name)) return ItemFactory.create(gp, "wooden_shield");
        if ("Normal Sword".equals(name)) return ItemFactory.create(gp, "wooden_sword");

        Entity item = ItemFactory.create(gp, name);
        if (item != null) return item;
        return ItemFactory.create(gp, normalizeItemId(name));
    }

    private String normalizeItemId(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String serializeEntityId(Entity entity) {
        if (entity == null) return "NA";
        if (entity.itemId != null && !entity.itemId.isBlank()) return entity.itemId;
        return entity.name;
    }

    private String escapeValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private String unescapeValue(String value) {
        if (value == null) return "";
        return value.replace("\\n", "\n").replace("\\\\", "\\");
    }

    /** Guards against stacking one cloud-upload thread per save while a slow upload is in flight. */
    private final java.util.concurrent.atomic.AtomicBoolean cloudSaveInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public void save() {
        saveToDisk();

        // Snapshot the game state NOW, on the caller (render) thread — buildGameState reads live
        // entities, so it must not run concurrently with the simulation. The network half then
        // runs on a worker: CloudSaveService.save() does a real blocking round trip (pingPool can
        // burn a 3s connect timeout PER endpoint when offline), and doing that inline froze the
        // window on every Save Game / Save & Quit / skill unlock.
        final GameState gs;
        try {
            gs = buildGameState();
        } catch (RuntimeException e) {
            System.out.println("Cloud save skipped, could not snapshot state: " + e.getMessage());
            return;
        }

        if (!cloudSaveInFlight.compareAndSet(false, true)) {
            // An upload is already running; the offline-cache + heartbeat sync path will still
            // deliver the newest local save.dat state on the next successful save or reconnect.
            return;
        }
        Thread uploader = new Thread(() -> {
            try {
                CloudSaveService.SaveResult result =
                        cloudSaveService.save(gs, Main.LICENSE_KEY, Main.OFFLINE_MODE);
                if (!result.ok()) {
                    System.out.println(result.message());
                }
            } catch (RuntimeException e) {
                System.out.println("Cloud save failed: " + e.getMessage());
            } finally {
                cloudSaveInFlight.set(false);
            }
        }, "CloudSave-Upload");
        uploader.setDaemon(true);
        uploader.start();
    }

    private GameState buildGameState() {

        GameState gs = new GameState();

        gs.playerX = gp.player.worldX;
        gs.playerY = gp.player.worldY;
        gs.playerZ = 0;
        gs.direction = gp.player.direction;
        gs.mapID = gp.mapManager.currentMapId;

        gs.level = gp.player.level;
        gs.maxHealth = gp.player.maxLife;
        gs.health = gp.player.life;
        gs.maxMana = gp.player.maxMana;
        gs.mana = gp.player.mana;
        gs.strength = gp.player.strenght;
        gs.dexterity = gp.player.dexterity;
        gs.exp = gp.player.exp;
        gs.nextLevelExp = gp.player.nextLevelExp;
        gs.coin = gp.player.coin;

        gs.skillPoints = gp.player.skillPoints;
        gs.dashUnlocked = gp.player.dashUnlocked;
        gs.shockwaveUnlocked = gp.player.shockwaveUnlocked;
        gs.voidSnareUnlocked = gp.player.voidSnareUnlocked;
        gs.frostNovaUnlocked = gp.player.frostNovaUnlocked;
        gs.overdriveUnlocked = gp.player.overdriveUnlocked;

        for (main.SkillTree.SkillNode n : gp.player.skillTree.getNodes()) {
            if (n.unlocked) gs.unlockedSkillNodes.add(n.id);
        }

        for (Entity e : gp.player.inventory) {
            gs.itemNames.add(serializeEntityId(e));
            gs.itemAmounts.add(e.amount);
        }
        gs.currentWeaponSlot = gp.player.getCurrentWeaponSlot();
        gs.currentShieldSlot = gp.player.getCurrentShieldSlot();

        if (gp.questManager != null) {
            for (QuestManager.QuestState quest : gp.questManager.getQuestStates()) {
                gs.questIds.add(quest.id);
                gs.questNames.add(quest.name);
                gs.questDescriptions.add(quest.description);
                gs.questProgress.add(quest.current);
                gs.questTargets.add(quest.target);
                gs.questCurrentSteps.add(quest.currentStep);
                gs.questStepProgress.add(quest.stepProgress);
            }
        }

        int size = gp.obj.length;
        gs.mapObjectNames   = new String[size];
        gs.mapObjectWorldX  = new int[size];
        gs.mapObjectWorldY  = new int[size];
        gs.mapObjectLootName = new String[size];
        gs.mapObjectOpened  = new boolean[size];

        for (int i = 0; i < size; i++) {
            if (gp.obj[i] == null) {
                gs.mapObjectNames[i] = "NA";
                gs.mapObjectLootName[i] = "NA";
                continue;
            }
            gs.mapObjectNames[i]  = serializeEntityId(gp.obj[i]);
            gs.mapObjectWorldX[i] = gp.obj[i].worldX;
            gs.mapObjectWorldY[i] = gp.obj[i].worldY;
            gs.mapObjectOpened[i] = gp.obj[i].opened;
            gs.mapObjectLootName[i] = gp.obj[i].loot != null ? serializeEntityId(gp.obj[i].loot) : "NA";
        }

        if (gp.memoryJournal != null) {
            gs.collectedFragmentIds = new java.util.ArrayList<>(gp.memoryJournal.getCollectedIds());
            gs.totalFragmentsCollected = gp.memoryJournal.getCount();
        }

        gs.boss1Defeated = gp.boss1Defeated;
        gs.boss2Defeated = gp.boss2Defeated;
        gs.boss3Defeated = gp.boss3Defeated;
        gs.boss4Defeated = gp.boss4Defeated;

        gs.storyAct = gp.storyAct;
        gs.endingChosen = gp.endingChosen;

        gs.openedGates = new java.util.ArrayList<>(gp.openedGates);
        gs.metNPCs    = new java.util.ArrayList<>(gp.metNPCs);

        gs.shopStock = new java.util.ArrayList<>();
        for (var e : gp.shopStock.entrySet()) {
            gs.shopStock.add(e.getKey() + "=" + e.getValue());
        }

        gs.timestamp = System.currentTimeMillis();
        return gs;
    }

    private void saveToDisk() {

        try {
            StringBuilder sb = new StringBuilder();

            sb.append("timestamp=").append(System.currentTimeMillis()).append('\n');
            sb.append("player.level=").append(gp.player.level).append('\n');
            sb.append("player.maxLife=").append(gp.player.maxLife).append('\n');
            sb.append("player.life=").append(gp.player.life).append('\n');
            sb.append("player.maxMana=").append(gp.player.maxMana).append('\n');
            sb.append("player.mana=").append(gp.player.mana).append('\n');
            sb.append("player.strenght=").append(gp.player.strenght).append('\n');
            sb.append("player.dexterity=").append(gp.player.dexterity).append('\n');
            sb.append("player.exp=").append(gp.player.exp).append('\n');
            sb.append("player.nextLevelExp=").append(gp.player.nextLevelExp).append('\n');
            sb.append("player.coin=").append(gp.player.coin).append('\n');

            sb.append("player.worldX=").append(gp.player.worldX).append('\n');
            sb.append("player.worldY=").append(gp.player.worldY).append('\n');
            sb.append("player.direction=").append(gp.player.direction).append('\n');
            sb.append("mapID=").append(gp.mapManager.currentMapId == null ? "" : gp.mapManager.currentMapId).append('\n');

            sb.append("player.weaponSlot=").append(gp.player.getCurrentWeaponSlot()).append('\n');
            sb.append("player.shieldSlot=").append(gp.player.getCurrentShieldSlot()).append('\n');

            sb.append("inventory.size=").append(gp.player.inventory.size()).append('\n');
            for (int i = 0; i < gp.player.inventory.size(); i++) {
                sb.append("inventory.").append(i).append(".name=").append(serializeEntityId(gp.player.inventory.get(i))).append('\n');
                sb.append("inventory.").append(i).append(".amount=").append(gp.player.inventory.get(i).amount).append('\n');
            }

            if (gp.questManager != null) {
                ArrayList<QuestManager.QuestState> quests = gp.questManager.getQuestStates();
                sb.append("quests.size=").append(quests.size()).append('\n');
                for (int i = 0; i < quests.size(); i++) {
                    QuestManager.QuestState quest = quests.get(i);
                    sb.append("quests.").append(i).append(".id=").append(escapeValue(quest.id)).append('\n');
                    sb.append("quests.").append(i).append(".name=").append(escapeValue(quest.name)).append('\n');
                    sb.append("quests.").append(i).append(".desc=").append(escapeValue(quest.description)).append('\n');
                    sb.append("quests.").append(i).append(".current=").append(quest.current).append('\n');
                    sb.append("quests.").append(i).append(".target=").append(quest.target).append('\n');
                    sb.append("quests.").append(i).append(".step=").append(quest.currentStep).append('\n');
                    sb.append("quests.").append(i).append(".stepProgress=").append(quest.stepProgress).append('\n');
                }
            }

            int size = gp.obj.length;
            sb.append("obj.size=").append(size).append('\n');
            for (int i = 0; i < size; i++) {
                if (gp.obj[i] == null) {
                    sb.append("obj.").append(i).append(".name=NA\n");
                    continue;
                }
                sb.append("obj.").append(i).append(".name=").append(serializeEntityId(gp.obj[i])).append('\n');
                sb.append("obj.").append(i).append(".worldX=").append(gp.obj[i].worldX).append('\n');
                sb.append("obj.").append(i).append(".worldY=").append(gp.obj[i].worldY).append('\n');
                sb.append("obj.").append(i).append(".opened=").append(gp.obj[i].opened).append('\n');
                String lootName = gp.obj[i].loot != null ? serializeEntityId(gp.obj[i].loot) : "NA";
                sb.append("obj.").append(i).append(".loot=").append(lootName).append('\n');
            }

            if (gp.memoryJournal != null) {
                java.util.List<String> ids = gp.memoryJournal.getCollectedIds();
                sb.append("fragments.size=").append(ids.size()).append('\n');
                for (int i = 0; i < ids.size(); i++) {
                    sb.append("fragments.").append(i).append('=').append(ids.get(i)).append('\n');
                }
            } else {
                sb.append("fragments.size=0\n");
            }

            sb.append("boss1Defeated=").append(gp.boss1Defeated).append('\n');
            sb.append("boss2Defeated=").append(gp.boss2Defeated).append('\n');
            sb.append("boss3Defeated=").append(gp.boss3Defeated).append('\n');
            sb.append("boss4Defeated=").append(gp.boss4Defeated).append('\n');

            sb.append("storyAct=").append(gp.storyAct).append('\n');
            sb.append("endingChosen=").append(gp.endingChosen).append('\n');

            java.util.List<String> gatesList = new java.util.ArrayList<>(gp.openedGates);
            sb.append("openedGates.size=").append(gatesList.size()).append('\n');
            for (int i = 0; i < gatesList.size(); i++) {
                sb.append("openedGates.").append(i).append('=').append(gatesList.get(i)).append('\n');
            }
            java.util.List<String> metList = new java.util.ArrayList<>(gp.metNPCs);
            sb.append("metNPCs.size=").append(metList.size()).append('\n');
            for (int i = 0; i < metList.size(); i++) {
                sb.append("metNPCs.").append(i).append('=').append(metList.get(i)).append('\n');
            }

            // Remaining shop stock, only present for listings with a finite "stock" in npcs.json
            // (see ui.ShopListing.infinite()); most shops never touch this.
            java.util.List<String> stockKeys = new java.util.ArrayList<>(gp.shopStock.keySet());
            sb.append("shopStock.size=").append(stockKeys.size()).append('\n');
            for (int i = 0; i < stockKeys.size(); i++) {
                sb.append("shopStock.").append(i).append(".key=").append(stockKeys.get(i)).append('\n');
                sb.append("shopStock.").append(i).append(".val=").append(gp.shopStock.get(stockKeys.get(i))).append('\n');
            }

            sb.append("player.skillPoints=").append(gp.player.skillPoints).append('\n');
            main.SkillTree.SkillNode[] skillNodes = gp.player.skillTree.getNodes();
            int unlockedCount = 0;
            for (main.SkillTree.SkillNode n : skillNodes) { if (n.unlocked) unlockedCount++; }
            sb.append("skilltree.size=").append(unlockedCount).append('\n');
            int si = 0;
            for (main.SkillTree.SkillNode n : skillNodes) {
                if (n.unlocked) { sb.append("skilltree.").append(si++).append('=').append(n.id).append('\n'); }
            }

            byte[] encrypted = encrypt(sb.toString());
            // Atomic replace: a crash mid-write must never leave a truncated save.dat behind.
            GameStorage.writeAtomic("save.dat", encrypted);

        } catch (java.io.IOException | java.security.GeneralSecurityException | RuntimeException e) {
            System.out.println("Save to disk failed: " + e.getMessage());
            System.out.println("Save Exception!");
        }
    }

    /**
     * Disk-only save for application shutdown: no cloud thread (the JVM is exiting, a daemon
     * upload would be killed mid-flight anyway) — the heartbeat/pending-upload path syncs the
     * state to the cloud on the next launch instead.
     */
    public void saveOnExit() {
        saveToDisk();
    }

    public void load() {
        applyFetched(fetch());
    }

    /**
     * Network half of a load: pull the cloud save and parse it, touching NO game state and NO GL
 * resource. Safe, and intended, to call off the render thread, since {@code download()} does a
     * real blocking round trip (pingPool + transfer) that would otherwise freeze the window.
     * Reading the local timestamp is plain file I/O, so it belongs on this side of the split too.
     *
     * @return the parsed cloud state, or null to mean "use the local save instead".
     */
    public GameState fetch() {
        try {
            CloudSaveService.DownloadResult result = cloudSaveService.download(Main.LICENSE_KEY);
            if (result.ok() && result.json() != null && !result.json().isBlank()) {
                GameState state = parseGameStateJson(result.json());
                // Cloud is only authoritative if it's actually newer than what's on disk
                // otherwise a stale cloud save (e.g. from before this session's progress) would
                // silently overwrite newer local progress with no error shown. Returning null
                // hands applyFetched() back to the local save, which is exactly what we want.
                if (state != null && state.timestamp >= localSaveTimestamp()) return state;
            } else if (!result.ok()) {
                System.out.println(result.message());
            }
        } catch (RuntimeException e) {
            System.out.println("Cloud load failed, falling back to local save: " + e.getMessage());
        }
        return null;
    }

    /**
     * State half of a load: apply what {@link #fetch()} returned, falling back to the local save if
 * it returned null. MUST run on the render thread, it rebuilds entities and re-bakes the
     * minimap, which construct GPU Textures/Pixmaps and need a current GL context.
     */
    public void applyFetched(GameState state) {
        if (state != null) {
            try {
                applyGameState(state);
                saveToDisk();
                return;
            } catch (RuntimeException e) {
                // A malformed cloud save must NOT strand the player on the title screen: fall back
                // to the local save.dat instead of letting the exception bubble up to continueGame(),
                // which would silently bounce back to the title ("Continue does nothing").
                System.out.println("[Load] Cloud save could not be applied (" + e
                        + ") — falling back to local save.");
            }
        }
        loadFromDisk();
    }

    /** Timestamp of the local save.dat, or 0 if absent/unreadable, so a missing/corrupt local
 * file never blocks a valid cloud save from loading. */
    private long localSaveTimestamp() {
        if (!GameStorage.exists("save.dat")) return 0L;
        try (InputStream fis = GameStorage.inputStream("save.dat")) {
            String plaintext = decrypt(fis.readAllBytes());
            for (String line : plaintext.split("\n")) {
                if (line.startsWith("timestamp=")) {
                    try { return Long.parseLong(line.substring("timestamp=".length()).trim()); }
                    catch (NumberFormatException ignored) { return 0L; }
                }
            }
        } catch (Exception ignored) { /* unreadable/legacy save, treat as oldest */ }
        return 0L;
    }

    /** Tolerant per-field parse: a single corrupt value falls back to its default instead of
     * aborting the whole load and leaving the game half-applied. */
    private static int parseInt(Map<String, String> map, String key, int fallback) {
        String v = map.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private void loadFromDisk() {

        if (!GameStorage.exists("save.dat")) {
            System.out.println("No local save found — nothing to load.");
            return;
        }

        try {
            byte[] raw;
            try (InputStream fis = GameStorage.inputStream("save.dat")) {
                raw = fis.readAllBytes();
            }
            String plaintext = decrypt(raw);

            Map<String, String> map = new HashMap<>();
            for (String line : plaintext.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }

            // MAP RELOAD, load the saved map's TMX + entities BEFORE applying
            // saved state on top, otherwise the world tiles/collisions/NPCs/events
            // are still those of whatever map was loaded at startup (awakening_cave).
            // That mismatch is what caused: player teleported to wrong spot, drawn
            // under tiles, and unable to move more than a couple of tiles.
            String savedMapId = map.getOrDefault("mapID", "").trim();
            int savedWorldX = parseInt(map, "player.worldX", 0);
            int savedWorldY = parseInt(map, "player.worldY", 0);
            reloadSavedMap(savedMapId, savedWorldX, savedWorldY);

            // SKILL TREE FIRST: node effects are applied on top of setDefaultValues() (multipliers,
            // ability flags, cooldown bonuses aren't serialized), and only THEN are the absolute
            // stats below written from the save. The old order (stats first, effects last) re-added
            // VITALITY_CORE/AETHER_RESERVE-style permanent bonuses on top of saved values that
            // already contained them, inflating maxLife/maxMana a little more on every single load.
            int stSize = parseInt(map, "skilltree.size", 0);
            for (int i = 0; i < stSize; i++) {
                String nid = map.get("skilltree." + i);
                if (nid != null && !nid.isBlank()) {
                    gp.player.skillTree.markUnlocked(nid);
                    // markUnlocked() only flips the node's visual "unlocked" flag; this is the ONLY
                    // place the node's actual gameplay effect (dashUnlocked, multipliers, ...) gets
                    // re-applied on load. Quiet: no "Unlocked skill" toast/sound per node.
                    gp.player.applySkillNodeEffect(nid, false);
                }
            }
            gp.player.skillPoints = parseInt(map, "player.skillPoints", 0);

            gp.player.level        = parseInt(map, "player.level",        1);
            gp.player.maxLife      = parseInt(map, "player.maxLife",      6);
            gp.player.life         = parseInt(map, "player.life",         6);
            gp.player.maxMana      = parseInt(map, "player.maxMana",      4);
            gp.player.mana         = parseInt(map, "player.mana",         4);
            gp.player.strenght     = parseInt(map, "player.strenght",     1);
            gp.player.dexterity    = parseInt(map, "player.dexterity",    1);
            gp.player.exp          = parseInt(map, "player.exp",          0);
            gp.player.nextLevelExp = parseInt(map, "player.nextLevelExp", 5);
            gp.player.coin         = parseInt(map, "player.coin",         0);

            // Clamp: a hand-edited/corrupt save must not produce dead-on-arrival or negative stats.
            gp.player.maxLife = Math.max(1, gp.player.maxLife);
            gp.player.life    = Math.max(1, Math.min(gp.player.life, gp.player.maxLife));
            gp.player.maxMana = Math.max(0, gp.player.maxMana);
            gp.player.mana    = Math.max(0, Math.min(gp.player.mana, gp.player.maxMana));

            gp.player.worldX = savedWorldX;
            gp.player.worldY = savedWorldY;
            gp.player.direction = parseInt(map, "player.direction", 2);

            gp.player.inventory.clear();
            int invSize = parseInt(map, "inventory.size", 0);
            for (int i = 0; i < invSize; i++) {
                Entity item = getObject(map.get("inventory." + i + ".name"));
                if (item != null) {
                    item.amount = Math.max(1, parseInt(map, "inventory." + i + ".amount", 1));
                    gp.player.inventory.add(item);
                }
            }

            if (gp.questManager != null && map.containsKey("quests.size")) {
                gp.questManager.clearQuests();
                int questSize = parseInt(map, "quests.size", 0);
                for (int i = 0; i < questSize; i++) {
                    String questId = unescapeValue(map.get("quests." + i + ".id"));
                    if (questId.isBlank()) continue;
                    String questName = unescapeValue(map.getOrDefault("quests." + i + ".name", questId));
                    String questDesc = unescapeValue(map.getOrDefault("quests." + i + ".desc", ""));
                    int current = parseInt(map, "quests." + i + ".current", 0);
                    int target = parseInt(map, "quests." + i + ".target", 1);
                    int step = parseInt(map, "quests." + i + ".step", -1);
                    int stepProg = parseInt(map, "quests." + i + ".stepProgress", 0);
                    gp.questManager.restoreQuest(questId, questName, questDesc, target, current, step, stepProg);
                }
            }

            int weaponSlot = parseInt(map, "player.weaponSlot", 0);
            int shieldSlot = parseInt(map, "player.shieldSlot", 1);
            if (weaponSlot >= 0 && weaponSlot < gp.player.inventory.size()) gp.player.currentWeapon = gp.player.inventory.get(weaponSlot);
            if (shieldSlot >= 0 && shieldSlot < gp.player.inventory.size()) gp.player.currentShield = gp.player.inventory.get(shieldSlot);

            gp.player.getAttack();
            gp.player.getDefense();
            gp.player.getPlayerAttackImages();

            int objSize = parseInt(map, "obj.size", 0);
            for (int i = 0; i < Math.min(objSize, gp.obj.length); i++) {
                // Event-layer lights (Tiled "Lighting" objects) are transient, reloadSavedMap() already
                // recreated them fresh from the TMX just above, and they're never serialized into the
                // save (serializeEntityId would just save their shared "Light" name, which getObject()
                // can't reconstruct anyway). Skip these slots entirely so the save-state restore doesn't
                // null them back out, this was why Tiled lights disappeared on Continue until a debug
                // map reload (R) recreated them again with no save-restore afterward to wipe them.
                if (gp.obj[i] != null && gp.obj[i].eventLayerLight) continue;

                String name = map.get("obj." + i + ".name");
                if (name == null || name.equals("NA")) {
                    gp.obj[i] = null;
                    continue;
                }
                gp.obj[i] = getObject(name);
                if (gp.obj[i] == null) continue;

                gp.obj[i].worldX = parseInt(map, "obj." + i + ".worldX", 0);
                gp.obj[i].worldY = parseInt(map, "obj." + i + ".worldY", 0);
                gp.obj[i].opened = Boolean.parseBoolean(map.getOrDefault("obj." + i + ".opened", "false"));

                String lootName = map.get("obj." + i + ".loot");
                if (gp.obj[i].opened || lootName == null || lootName.equals("NA")) {
                    gp.obj[i].loot = null;
                } else {
                    gp.obj[i].loot = getObject(lootName);
                }
                if (gp.obj[i].opened) {
                    gp.obj[i].down1 = gp.obj[i].image1;
                }
            }

            if (gp.memoryJournal != null) {
                int fragSize = parseInt(map, "fragments.size", 0);
                for (int i = 0; i < fragSize; i++) {
                    String fid = map.get("fragments." + i);
                    if (fid != null && !fid.isBlank()) {
                        gp.memoryJournal.addById(fid);
                    }
                }
            }

            gp.boss1Defeated = Boolean.parseBoolean(map.getOrDefault("boss1Defeated", "false"));
            gp.boss2Defeated = Boolean.parseBoolean(map.getOrDefault("boss2Defeated", "false"));
            gp.boss3Defeated = Boolean.parseBoolean(map.getOrDefault("boss3Defeated", "false"));
            gp.boss4Defeated = Boolean.parseBoolean(map.getOrDefault("boss4Defeated", "false"));

            gp.storyAct = parseInt(map, "storyAct", 0);
            gp.endingChosen = parseInt(map, "endingChosen", 0);

            int gatesSize = parseInt(map, "openedGates.size", 0);
            gp.openedGates.clear();
            for (int i = 0; i < gatesSize; i++) {
                String gid = map.get("openedGates." + i);
                if (gid != null && !gid.isBlank()) gp.openedGates.add(gid);
            }
            int metSize = parseInt(map, "metNPCs.size", 0);
            gp.metNPCs.clear();
            for (int i = 0; i < metSize; i++) {
                String mid = map.get("metNPCs." + i);
                if (mid != null && !mid.isBlank()) gp.metNPCs.add(mid);
            }

            int stockSize = parseInt(map, "shopStock.size", 0);
            gp.shopStock.clear();
            for (int i = 0; i < stockSize; i++) {
                String key = map.get("shopStock." + i + ".key");
                String val = map.get("shopStock." + i + ".val");
                if (key != null && !key.isBlank() && val != null) {
                    try { gp.shopStock.put(key, Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
                }
            }

        } catch (java.io.IOException | java.security.GeneralSecurityException | RuntimeException e) {
            System.out.println("Load from disk failed: " + e.getMessage());
            System.out.println("Load Exception!");
        }
    }

    /**
     * Reload the TMX, collision, events, and base entities for the saved map
     * BEFORE the rest of the save state is layered on top. Without this the
     * world still shows the startup map (awakening_cave) while player coords
     * reference a different map, producing the "stuck under tiles / can only
     * move a few tiles" bug.
     */
    private void reloadSavedMap(String savedMapId, int savedWorldX, int savedWorldY) {
        if (gp.mapManager == null) return;

        String targetId = (savedMapId != null && !savedMapId.isBlank()
                            && gp.mapManager.mapRegistry.containsKey(savedMapId))
                ? savedMapId
                : gp.mapManager.currentMapId;

        int tile = gp.tileSize;
        int spawnCol = tile > 0 ? Math.max(0, savedWorldX / tile) : 0;
        int spawnRow = tile > 0 ? Math.max(0, savedWorldY / tile) : 0;

        gp.mapManager.clearSavedMapEntities(targetId);
        gp.mapManager.nextSpawnId = "";

        gp.mapManager.loadingGame = true;
        gp.mapManager.changeMap(targetId, spawnCol, spawnRow);
        gp.mapManager.loadingGame = false;
    }

    private void applyGameState(GameState state) {

        if (state == null) return;

        // MAP RELOAD, must happen before applying obj[]/player state, see
        // reloadSavedMap() doc. Uses the saved coords as the spawn point so
        // changeMap doesn't snap the player to the map's default spawn.
        reloadSavedMap(state.mapID, state.playerX, state.playerY);

        // SKILL TREE FIRST: re-apply each unlocked node's gameplay effect (multipliers, ability
        // flags, cooldown bonuses — none of which are serialized) on top of setDefaultValues(),
        // and only THEN write the absolute stats below from the save. This mirrors loadFromDisk():
        // before, the cloud path never applied node effects at all, so a cloud Continue restored
        // e.g. IRON_WILL/BLADE_MASTERY as visually unlocked but without their actual effect.
        if (state.unlockedSkillNodes != null && !state.unlockedSkillNodes.isEmpty()) {
            for (String nid : state.unlockedSkillNodes) {
                gp.player.skillTree.markUnlocked(nid);
                gp.player.applySkillNodeEffect(nid, false);
            }
        } else {
            // Legacy saves: only the 5 ability booleans exist; the flags themselves are set
            // directly below, so just restore the visual unlocked state here.
            if (state.dashUnlocked)      gp.player.skillTree.markUnlocked("WINDSTEP");
            if (state.shockwaveUnlocked) gp.player.skillTree.markUnlocked("SHOCKWAVE");
            if (state.voidSnareUnlocked) gp.player.skillTree.markUnlocked("VOID_SNARE");
            if (state.frostNovaUnlocked) gp.player.skillTree.markUnlocked("FROST_NOVA");
            if (state.overdriveUnlocked) gp.player.skillTree.markUnlocked("OVERDRIVE");
        }

        // PLAYER STATS (absolute values from the save; these already include permanent node
        // bonuses like VITALITY_CORE's +2 maxLife, which is why they must come AFTER the
        // effect re-application above rather than before it).
        gp.player.level = Math.max(1, state.level);
        gp.player.maxLife = Math.max(1, state.maxHealth);
        gp.player.life = Math.max(1, Math.min(state.health, gp.player.maxLife));
        gp.player.maxMana = Math.max(0, state.maxMana);
        gp.player.mana = Math.max(0, Math.min(state.mana, gp.player.maxMana));
        gp.player.strenght = Math.max(1, state.strength);
        gp.player.dexterity = Math.max(1, state.dexterity);
        gp.player.exp = Math.max(0, state.exp);
        gp.player.nextLevelExp = Math.max(1, state.nextLevelExp);
        gp.player.coin = Math.max(0, state.coin);

        // LOCATION, override changeMap's spawn position with exact saved coords.
        gp.player.worldX = state.playerX;
        gp.player.worldY = state.playerY;
        gp.player.direction = state.direction;

        gp.player.skillPoints = Math.max(0, state.skillPoints);
        if (state.dashUnlocked)      gp.player.dashUnlocked = true;
        if (state.shockwaveUnlocked) gp.player.shockwaveUnlocked = true;
        if (state.voidSnareUnlocked) gp.player.voidSnareUnlocked = true;
        if (state.frostNovaUnlocked) gp.player.frostNovaUnlocked = true;
        if (state.overdriveUnlocked) gp.player.overdriveUnlocked = true;

        gp.player.inventory.clear();
        int invSize = Math.min(state.itemNames.size(), state.itemAmounts.size());
        for (int i = 0; i < invSize; i++) {
            Entity item = getObject(state.itemNames.get(i));
            if (item == null) continue;
            item.amount = Math.max(1, state.itemAmounts.get(i));
            gp.player.inventory.add(item);
        }

        if (gp.questManager != null && state.questIds != null && !state.questIds.isEmpty()) {
            gp.questManager.clearQuests();
            int questSize = Math.min(
                Math.min(state.questIds.size(), state.questNames.size()),
                Math.min(state.questDescriptions.size(), Math.min(state.questProgress.size(), state.questTargets.size()))
            );
            for (int i = 0; i < questSize; i++) {
                Integer csRaw = (state.questCurrentSteps != null && i < state.questCurrentSteps.size())
                        ? state.questCurrentSteps.get(i) : null;
                Integer spRaw = (state.questStepProgress  != null && i < state.questStepProgress.size())
                        ? state.questStepProgress.get(i) : null;
                gp.questManager.restoreQuest(
                    state.questIds.get(i),
                    state.questNames.get(i),
                    state.questDescriptions.get(i),
                    state.questTargets.get(i),
                    state.questProgress.get(i),
                    csRaw != null ? csRaw : -1,
                    spRaw != null ? spRaw : 0
                );
            }
        }

        if (!gp.player.inventory.isEmpty()) {
            int weaponSlot = Math.max(0, Math.min(state.currentWeaponSlot, gp.player.inventory.size() - 1));
            int shieldSlot = Math.max(0, Math.min(state.currentShieldSlot, gp.player.inventory.size() - 1));
            gp.player.currentWeapon = gp.player.inventory.get(weaponSlot);
            gp.player.currentShield = gp.player.inventory.get(shieldSlot);
        }

        gp.player.getAttack();
        gp.player.getDefense();
        gp.player.getPlayerAttackImages();

        for (int i = 0; i < gp.obj.length; i++) {
            // Event-layer lights (Tiled "Lighting" objects) are transient, reloadSavedMap() already
            // recreated them fresh from the TMX just above, and they're never meaningfully serialized
            // (serializeEntityId only saves their shared "Light" name, which getObject() can't
            // reconstruct). Skip these slots so the save-state restore doesn't null them back out
            // this was why Tiled lights disappeared on Continue until a debug map reload (R) recreated
            // them again with no save-restore afterward to wipe them.
            if (gp.obj[i] != null && gp.obj[i].eventLayerLight) continue;

            String name = getAt(state.mapObjectNames, i, "NA");
            if (name == null || name.equals("NA")) {
                gp.obj[i] = null;
                continue;
            }

            gp.obj[i] = getObject(name);
            if (gp.obj[i] == null) continue;

            gp.obj[i].worldX = getAt(state.mapObjectWorldX, i, 0);
            gp.obj[i].worldY = getAt(state.mapObjectWorldY, i, 0);
            gp.obj[i].opened = getAt(state.mapObjectOpened, i, false);

            String lootName = getAt(state.mapObjectLootName, i, "NA");
            if (gp.obj[i].opened || lootName == null || lootName.equals("NA")) {
                gp.obj[i].loot = null;
            } else {
                gp.obj[i].loot = getObject(lootName);
            }
            if (gp.obj[i].opened) {
                gp.obj[i].down1 = gp.obj[i].image1;
            }
        }

        if (gp.memoryJournal != null && state.collectedFragmentIds != null) {
            for (String fid : state.collectedFragmentIds) {
                if (fid != null && !fid.isBlank()) {
                    gp.memoryJournal.addById(fid);
                }
            }
        }

        gp.shopStock.clear();
        if (state.shopStock != null) {
            for (String entry : state.shopStock) {
                int eq = entry.lastIndexOf('=');
                if (eq <= 0) continue;
                try { gp.shopStock.put(entry.substring(0, eq), Integer.parseInt(entry.substring(eq + 1))); }
                catch (NumberFormatException ignored) {}
            }
        }

        gp.boss1Defeated = state.boss1Defeated;
        gp.boss2Defeated = state.boss2Defeated;
        gp.boss3Defeated = state.boss3Defeated;
        gp.boss4Defeated = state.boss4Defeated;

        gp.storyAct = state.storyAct;
        gp.endingChosen = state.endingChosen;

        if (state.openedGates != null) {
            gp.openedGates.clear();
            gp.openedGates.addAll(state.openedGates);
        }
        if (state.metNPCs != null) {
            gp.metNPCs.clear();
            gp.metNPCs.addAll(state.metNPCs);
        }
    }

    private static String getAt(String[] arr, int index, String fallback) {
        if (arr == null || index < 0 || index >= arr.length) return fallback;
        return arr[index];
    }

    private static int getAt(int[] arr, int index, int fallback) {
        if (arr == null || index < 0 || index >= arr.length) return fallback;
        return arr[index];
    }

    private static boolean getAt(boolean[] arr, int index, boolean fallback) {
        if (arr == null || index < 0 || index >= arr.length) return fallback;
        return arr[index];
    }

    private GameState parseGameStateJson(String json) {
        if (json == null || json.isBlank()) return null;

        GameState state = tryParseGameStateWithGson(json);
        if (state != null) return state;

        state = tryParseGameStateWithJackson(json);
        if (state != null) return state;

        return parseGameStateJsonFallback(json);
    }

    private GameState tryParseGameStateWithGson(String json) {
        try {
            Class<?> gsonClass = Class.forName("com.google.gson.Gson");
            Object gson = gsonClass.getDeclaredConstructor().newInstance();
            Object parsed = gsonClass
                    .getMethod("fromJson", String.class, Class.class)
                    .invoke(gson, json, GameState.class);
            return parsed instanceof GameState gs ? gs : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | SecurityException e) {
            System.out.println("Cloud JSON parse (Gson) failed: " + e.getMessage());
            return null;
        }
    }

    private GameState tryParseGameStateWithJackson(String json) {
        try {
            Class<?> mapperClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.getDeclaredConstructor().newInstance();
            Object parsed = mapperClass
                    .getMethod("readValue", String.class, Class.class)
                    .invoke(mapper, json, GameState.class);
            return parsed instanceof GameState gs ? gs : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | SecurityException e) {
            System.out.println("Cloud JSON parse (Jackson) failed: " + e.getMessage());
            return null;
        }
    }

    private GameState parseGameStateJsonFallback(String json) {
        try {
            GameState gs = new GameState();

            // POSITION
            gs.playerX = extractJsonInt(json, "playerX", 0);
            gs.playerY = extractJsonInt(json, "playerY", 0);
            gs.playerZ = extractJsonInt(json, "playerZ", 0);
            gs.direction = extractJsonInt(json, "direction", 0);
            gs.mapID = extractJsonString(json, "mapID", null);

            // STATS
            gs.level = extractJsonInt(json, "level", 1);
            gs.maxHealth = extractJsonInt(json, "maxHealth", 6);
            gs.health = extractJsonInt(json, "health", gs.maxHealth);
            gs.maxMana = extractJsonInt(json, "maxMana", 4);
            gs.mana = extractJsonInt(json, "mana", gs.maxMana);
            gs.strength = extractJsonInt(json, "strength", 1);
            gs.dexterity = extractJsonInt(json, "dexterity", 1);
            gs.exp = extractJsonInt(json, "exp", 0);
            gs.nextLevelExp = extractJsonInt(json, "nextLevelExp", 5);
            gs.coin = extractJsonInt(json, "coin", 0);

            // SKILLS
            gs.skillPoints = extractJsonInt(json, "skillPoints", 0);
            gs.dashUnlocked = extractJsonBoolean(json, "dashUnlocked", false);
            gs.shockwaveUnlocked = extractJsonBoolean(json, "shockwaveUnlocked", false);
            gs.voidSnareUnlocked = extractJsonBoolean(json, "voidSnareUnlocked", false);
            gs.frostNovaUnlocked = extractJsonBoolean(json, "frostNovaUnlocked", false);
            gs.overdriveUnlocked = extractJsonBoolean(json, "overdriveUnlocked", false);

            // INVENTORY
            gs.itemNames = extractJsonStringArray(json, "itemNames");
            gs.itemAmounts = extractJsonIntArray(json, "itemAmounts");
            gs.currentWeaponSlot = extractJsonInt(json, "currentWeaponSlot", 0);
            gs.currentShieldSlot = extractJsonInt(json, "currentShieldSlot", 1);

            // OBJECTS ON MAP
            gs.mapObjectNames = extractJsonStringArray(json, "mapObjectNames").toArray(String[]::new);
            gs.mapObjectWorldX = toIntArray(extractJsonIntArray(json, "mapObjectWorldX"));
            gs.mapObjectWorldY = toIntArray(extractJsonIntArray(json, "mapObjectWorldY"));
            gs.mapObjectLootName = extractJsonStringArray(json, "mapObjectLootName").toArray(String[]::new);
            gs.mapObjectOpened = toBooleanArray(extractJsonBooleanArray(json, "mapObjectOpened"));

            // QUESTS
            gs.questIds = extractJsonStringArray(json, "questIds");
            gs.questNames = extractJsonStringArray(json, "questNames");
            gs.questDescriptions = extractJsonStringArray(json, "questDescriptions");
            gs.questProgress = extractJsonIntArray(json, "questProgress");
            gs.questTargets = extractJsonIntArray(json, "questTargets");
            gs.questCurrentSteps = extractJsonIntArray(json, "questCurrentSteps");
            gs.questStepProgress = extractJsonIntArray(json, "questStepProgress");

            // STORY / WORLD PROGRESSION — these were never extracted here, and since neither Gson
            // nor Jackson is on the runtime classpath this fallback is the parser that ALWAYS runs
            // for cloud saves. Every cloud Continue therefore silently reset story act, bosses,
            // gates, met NPCs, journal fragments, shop stock and full skill-tree state.
            gs.unlockedSkillNodes = extractJsonStringArray(json, "unlockedSkillNodes");
            gs.collectedFragmentIds = extractJsonStringArray(json, "collectedFragmentIds");
            gs.totalFragmentsCollected = extractJsonInt(json, "totalFragmentsCollected",
                    gs.collectedFragmentIds.size());
            gs.boss1Defeated = extractJsonBoolean(json, "boss1Defeated", false);
            gs.boss2Defeated = extractJsonBoolean(json, "boss2Defeated", false);
            gs.boss3Defeated = extractJsonBoolean(json, "boss3Defeated", false);
            gs.boss4Defeated = extractJsonBoolean(json, "boss4Defeated", false);
            gs.storyAct = extractJsonInt(json, "storyAct", 0);
            gs.endingChosen = extractJsonInt(json, "endingChosen", 0);
            gs.openedGates = extractJsonStringArray(json, "openedGates");
            gs.metNPCs = extractJsonStringArray(json, "metNPCs");
            gs.shopStock = extractJsonStringArray(json, "shopStock");

            gs.timestamp = extractJsonLong(json, "timestamp", 0L);
            return gs;

        } catch (RuntimeException e) {
            System.out.println("Cloud JSON parse (fallback) failed: " + e.getMessage());
            return null;
        }
    }

    private static int[] toIntArray(ArrayList<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static boolean[] toBooleanArray(ArrayList<Boolean> values) {
        boolean[] out = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\"";
        int keyPos = json.indexOf(search);
        if (keyPos < 0) return null;

        int colon = json.indexOf(':', keyPos + search.length());
        if (colon < 0) return null;

        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        int end = findJsonValueEnd(json, start);
        if (end <= start) return null;

        return json.substring(start, end).trim();
    }

    private static int findJsonValueEnd(String json, int start) {
        char first = json.charAt(start);

        if (first == '"') {
            boolean escaped = false;
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') return i + 1;
            }
            return json.length();
        }

        if (first == '[' || first == '{') {
            char open = first;
            char close = (first == '[') ? ']' : '}';
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);

                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }

                if (c == '"') {
                    inString = true;
                    continue;
                }
                if (c == open) depth++;
                if (c == close) {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            return json.length();
        }

        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']') break;
            i++;
        }
        return i;
    }

    private static int extractJsonInt(String json, String key, int fallback) {
        String raw = extractJsonValue(json, key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long extractJsonLong(String json, String key, long fallback) {
        String raw = extractJsonValue(json, key);
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean extractJsonBoolean(String json, String key, boolean fallback) {
        String raw = extractJsonValue(json, key);
        if (raw == null) return fallback;
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        return fallback;
    }

    private static String extractJsonString(String json, String key, String fallback) {
        String raw = extractJsonValue(json, key);
        if (raw == null || "null".equals(raw)) return fallback;
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return unescapeJsonString(raw.substring(1, raw.length() - 1));
        }
        return fallback;
    }

    private static ArrayList<String> extractJsonStringArray(String json, String key) {
        ArrayList<String> out = new ArrayList<>();
        String raw = extractJsonValue(json, key);
        if (raw == null) return out;

        ArrayList<String> tokens = splitJsonArray(raw);
        for (String token : tokens) {
            String t = token.trim();
            if ("null".equals(t)) {
                out.add(null);
            } else if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
                out.add(unescapeJsonString(t.substring(1, t.length() - 1)));
            }
        }
        return out;
    }

    private static ArrayList<Integer> extractJsonIntArray(String json, String key) {
        ArrayList<Integer> out = new ArrayList<>();
        String raw = extractJsonValue(json, key);
        if (raw == null) return out;

        ArrayList<String> tokens = splitJsonArray(raw);
        for (String token : tokens) {
            try {
                out.add(Integer.valueOf(token.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static ArrayList<Boolean> extractJsonBooleanArray(String json, String key) {
        ArrayList<Boolean> out = new ArrayList<>();
        String raw = extractJsonValue(json, key);
        if (raw == null) return out;

        ArrayList<String> tokens = splitJsonArray(raw);
        for (String token : tokens) {
            String t = token.trim();
            if ("true".equalsIgnoreCase(t)) out.add(true);
            else if ("false".equalsIgnoreCase(t)) out.add(false);
        }
        return out;
    }

    private static ArrayList<String> splitJsonArray(String rawArray) {
        ArrayList<String> out = new ArrayList<>();
        if (rawArray == null) return out;
        String raw = rawArray.trim();
        if (raw.length() < 2 || raw.charAt(0) != '[' || raw.charAt(raw.length() - 1) != ']') return out;

        String body = raw.substring(1, raw.length() - 1).trim();
        if (body.isEmpty()) return out;

        int tokenStart = 0;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[' || c == '{') depth++;
            if (c == ']' || c == '}') depth--;

            if (c == ',' && depth == 0) {
                out.add(body.substring(tokenStart, i));
                tokenStart = i + 1;
            }
        }

        out.add(body.substring(tokenStart));
        return out;
    }

    private static String unescapeJsonString(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }

            char n = value.charAt(++i);
            switch (n) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append('u');
                        }
                    } else {
                        out.append('u');
                    }
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }
}

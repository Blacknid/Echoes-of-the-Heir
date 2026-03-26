package data;

import entity.Entity;
import java.io.*;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import main.GamePanel;
import main.Main;
import object.*;

public class SaveLoad {

    // AES-128 key — 16 bytes (change these values to make your save unique)
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

    // =========================
    // CRYPTO HELPERS
    // =========================
    private byte[] encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SAVE_KEY, "AES"), new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(plaintext.getBytes("UTF-8"));
        // File layout: [16-byte IV][ciphertext]
        byte[] out = new byte[16 + enc.length];
        System.arraycopy(iv,  0, out,  0, 16);
        System.arraycopy(enc, 0, out, 16, enc.length);
        return out;
    }

    private String decrypt(byte[] data) throws Exception {
        if (data.length < 17) {
            throw new Exception("Save file is corrupted or too short");
        }
        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        byte[] enc = new byte[data.length - 16];
        System.arraycopy(data, 16, enc, 0, enc.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SAVE_KEY, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(enc), "UTF-8");
    }

    // =========================
    // OBJECT FACTORY
    // =========================
    public Entity getObject(String name) {

        if (name == null || name.equals("NA")) return null;

        switch (name) {
            case "Spell book": return new OBJ_Book(gp);
            case "Boots": return new OBJ_Boots(gp);
            case "Chest": return new OBJ_Chest(gp);
            case "Door": return new OBJ_Door(gp);
            case "Gem": return new OBJ_Gem(gp);
            case "Key": return new OBJ_Key(gp);
            case "Compas": return new OBJ_Compas(gp);
            case "Potion": return new OBJ_Potion(gp);
            case "Wood_Shield": return new OBJ_Shield_Wood(gp);
            case "Normal Sword": return new OBJ_Sword_Normal(gp);
        }
        return null;
    }

    // =========================
    // SAVE
    // =========================
    public void save() {

        DataStorage ds = buildDataStorage();
        GameState gs = buildGameState();

        // Local save.dat (backward-compatible binary)
        saveToDisk(ds);

        // Cloud / encrypted save
        CloudSaveService.SaveResult result =
                cloudSaveService.save(gs, Main.LICENSE_KEY, Main.OFFLINE_MODE);

        if (!result.ok()) {
            System.out.println(result.message());
        }
    }

    // =========================
    // GAME STATE BUILDER
    // =========================
    private GameState buildGameState() {

        GameState gs = new GameState();

        // Position
        gs.playerX = gp.player.worldX;
        gs.playerY = gp.player.worldY;
        gs.playerZ = 0; // reserved for future layer/elevation support
        gs.direction = switch (gp.player.direction) {
            case Entity.DIR_UP    -> "up";
            case Entity.DIR_DOWN  -> "down";
            case Entity.DIR_LEFT  -> "left";
            case Entity.DIR_RIGHT -> "right";
            default -> "down";
        };
        gs.mapID = gp.currentMapId;

        // Stats
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

        // Skills
        gs.skillPoints = gp.player.skillPoints;
        gs.dashUnlocked = gp.player.dashUnlocked;
        gs.shockwaveUnlocked = gp.player.shockwaveUnlocked;
        gs.voidSnareUnlocked = gp.player.voidSnareUnlocked;
        gs.frostNovaUnlocked = gp.player.frostNovaUnlocked;
        gs.overdriveUnlocked = gp.player.overdriveUnlocked;

        // Inventory
        for (Entity e : gp.player.inventory) {
            gs.itemNames.add(e.name);
            gs.itemAmounts.add(e.amount);
        }
        gs.currentWeaponSlot = gp.player.getCurrentWeaponSlot();
        gs.currentShieldSlot = gp.player.getCurrentShieldSlot();

        // Objects on map (chests, doors, etc.)
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
            gs.mapObjectNames[i]  = gp.obj[i].name;
            gs.mapObjectWorldX[i] = gp.obj[i].worldX;
            gs.mapObjectWorldY[i] = gp.obj[i].worldY;
            gs.mapObjectOpened[i] = gp.obj[i].opened;
            gs.mapObjectLootName[i] = gp.obj[i].loot != null ? gp.obj[i].loot.name : "NA";
        }

        gs.timestamp = System.currentTimeMillis();
        return gs;
    }

    // =========================
    // DATA STORAGE (legacy)
    // =========================
    private DataStorage buildDataStorage() {

        DataStorage ds = new DataStorage();

        // PLAYER STATS
        ds.level = gp.player.level;
        ds.maxLife = gp.player.maxLife;
        ds.life = gp.player.life;
        ds.maxMana = gp.player.maxMana;
        ds.mana = gp.player.mana;
        ds.strenght = gp.player.strenght;
        ds.dexterity = gp.player.dexterity;
        ds.exp = gp.player.exp;
        ds.nextLevelExp = gp.player.nextLevelExp;
        ds.coin = gp.player.coin;

        // LOCATION
        ds.playerWorldX = gp.player.worldX;
        ds.playerWorldY = gp.player.worldY;

        // INVENTORY
        for (Entity e : gp.player.inventory) {
            ds.itemNames.add(e.name);
            ds.itemAmounts.add(e.amount);
        }

        ds.currentWeaponSlot = gp.player.getCurrentWeaponSlot();
        ds.currentShieldSlot = gp.player.getCurrentShieldSlot();

        // OBJECTS ON MAP
        int size = gp.obj.length;

        ds.mapObjectNames = new String[size];
        ds.mapObjectWorldX = new int[size];
        ds.mapObjectWorldY = new int[size];
        ds.mapObjectLootName = new String[size];
        ds.mapObjectOpened = new boolean[size];

        for (int i = 0; i < size; i++) {

            if (gp.obj[i] == null) {
                ds.mapObjectNames[i] = "NA";
                ds.mapObjectLootName[i] = "NA";
                continue;
            }

            ds.mapObjectNames[i] = gp.obj[i].name;
            ds.mapObjectWorldX[i] = gp.obj[i].worldX;
            ds.mapObjectWorldY[i] = gp.obj[i].worldY;
            ds.mapObjectOpened[i] = gp.obj[i].opened;

            if (gp.obj[i].loot != null) {
                ds.mapObjectLootName[i] = gp.obj[i].loot.name;
            } else {
                ds.mapObjectLootName[i] = "NA";
            }
        }

        return ds;
    }

    private void saveToDisk(DataStorage ds) {

        try {
            StringBuilder sb = new StringBuilder();

            // PLAYER STATS
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

            // LOCATION
            sb.append("player.worldX=").append(gp.player.worldX).append('\n');
            sb.append("player.worldY=").append(gp.player.worldY).append('\n');

            // EQUIPMENT SLOTS
            sb.append("player.weaponSlot=").append(gp.player.getCurrentWeaponSlot()).append('\n');
            sb.append("player.shieldSlot=").append(gp.player.getCurrentShieldSlot()).append('\n');

            // INVENTORY
            sb.append("inventory.size=").append(gp.player.inventory.size()).append('\n');
            for (int i = 0; i < gp.player.inventory.size(); i++) {
                sb.append("inventory.").append(i).append(".name=").append(gp.player.inventory.get(i).name).append('\n');
                sb.append("inventory.").append(i).append(".amount=").append(gp.player.inventory.get(i).amount).append('\n');
            }

            // OBJECTS ON MAP
            int size = gp.obj.length;
            sb.append("obj.size=").append(size).append('\n');
            for (int i = 0; i < size; i++) {
                if (gp.obj[i] == null) {
                    sb.append("obj.").append(i).append(".name=NA\n");
                    continue;
                }
                sb.append("obj.").append(i).append(".name=").append(gp.obj[i].name).append('\n');
                sb.append("obj.").append(i).append(".worldX=").append(gp.obj[i].worldX).append('\n');
                sb.append("obj.").append(i).append(".worldY=").append(gp.obj[i].worldY).append('\n');
                sb.append("obj.").append(i).append(".opened=").append(gp.obj[i].opened).append('\n');
                String lootName = gp.obj[i].loot != null ? gp.obj[i].loot.name : "NA";
                sb.append("obj.").append(i).append(".loot=").append(lootName).append('\n');
            }

            byte[] encrypted = encrypt(sb.toString());
            try (FileOutputStream fos = new FileOutputStream("save.dat")) {
                fos.write(encrypted);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Save Exception!");
        }
    }

    // =========================
    // LOAD
    // =========================
    public void load() {

        try {
            byte[] raw;
            try (FileInputStream fis = new FileInputStream("save.dat")) {
                raw = fis.readAllBytes();
            }
            String plaintext = decrypt(raw);

            Map<String, String> map = new HashMap<>();
            for (String line : plaintext.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }

            // PLAYER STATS
            gp.player.level        = Integer.parseInt(map.getOrDefault("player.level",        "1"));
            gp.player.maxLife      = Integer.parseInt(map.getOrDefault("player.maxLife",      "6"));
            gp.player.life         = Integer.parseInt(map.getOrDefault("player.life",         "6"));
            gp.player.maxMana      = Integer.parseInt(map.getOrDefault("player.maxMana",      "4"));
            gp.player.mana         = Integer.parseInt(map.getOrDefault("player.mana",         "4"));
            gp.player.strenght     = Integer.parseInt(map.getOrDefault("player.strenght",     "1"));
            gp.player.dexterity    = Integer.parseInt(map.getOrDefault("player.dexterity",    "1"));
            gp.player.exp          = Integer.parseInt(map.getOrDefault("player.exp",          "0"));
            gp.player.nextLevelExp = Integer.parseInt(map.getOrDefault("player.nextLevelExp", "5"));
            gp.player.coin         = Integer.parseInt(map.getOrDefault("player.coin",         "0"));

            // LOCATION
            gp.player.worldX = Integer.parseInt(map.getOrDefault("player.worldX", "0"));
            gp.player.worldY = Integer.parseInt(map.getOrDefault("player.worldY", "0"));

            // INVENTORY
            gp.player.inventory.clear();
            int invSize = Integer.parseInt(map.getOrDefault("inventory.size", "0"));
            for (int i = 0; i < invSize; i++) {
                Entity item = getObject(map.get("inventory." + i + ".name"));
                if (item != null) {
                    item.amount = Integer.parseInt(map.getOrDefault("inventory." + i + ".amount", "1"));
                    gp.player.inventory.add(item);
                }
            }

            // CURRENT EQUIPMENT
            int weaponSlot = Integer.parseInt(map.getOrDefault("player.weaponSlot", "0"));
            int shieldSlot = Integer.parseInt(map.getOrDefault("player.shieldSlot", "1"));
            if (weaponSlot < gp.player.inventory.size()) gp.player.currentWeapon = gp.player.inventory.get(weaponSlot);
            if (shieldSlot < gp.player.inventory.size()) gp.player.currentShield = gp.player.inventory.get(shieldSlot);

            gp.player.getAttack();
            gp.player.getDefense();
            gp.player.getPlayerAttackImages();

            // OBJECTS ON MAP
            int objSize = Integer.parseInt(map.getOrDefault("obj.size", "0"));
            for (int i = 0; i < Math.min(objSize, gp.obj.length); i++) {
                String name = map.get("obj." + i + ".name");
                if (name == null || name.equals("NA")) {
                    gp.obj[i] = null;
                    continue;
                }
                gp.obj[i] = getObject(name);
                if (gp.obj[i] == null) continue;

                gp.obj[i].worldX = Integer.parseInt(map.getOrDefault("obj." + i + ".worldX", "0"));
                gp.obj[i].worldY = Integer.parseInt(map.getOrDefault("obj." + i + ".worldY", "0"));
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

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Load Exception!");
        }
    }
}

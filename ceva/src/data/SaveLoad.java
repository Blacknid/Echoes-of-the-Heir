package data;

import java.io.*;

import entity.Entity;
import main.GamePanel;
import main.Main;
import object.*;

public class SaveLoad {

    GamePanel gp;
    private final CloudSaveService cloudSaveService = new CloudSaveService();

    public SaveLoad(GamePanel gp) {
        this.gp = gp;
        cloudSaveService.startHeartbeat();
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
        gs.direction = gp.player.direction;
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

        try (ObjectOutputStream oos =
             new ObjectOutputStream(new FileOutputStream("save.dat"))) {

            oos.writeObject(ds);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Save Exception!");
        }
    }

    // =========================
    // LOAD
    // =========================
    public void load() {

        try (ObjectInputStream ois =
             new ObjectInputStream(new FileInputStream("save.dat"))) {

            DataStorage ds = (DataStorage) ois.readObject();

            // PLAYER STATS
            gp.player.level = ds.level;
            gp.player.maxLife = ds.maxLife;
            gp.player.life = ds.life;
            gp.player.maxMana = ds.maxMana;
            gp.player.mana = ds.mana;
            gp.player.strenght = ds.strenght;
            gp.player.dexterity = ds.dexterity;
            gp.player.exp = ds.exp;
            gp.player.nextLevelExp = ds.nextLevelExp;
            gp.player.coin = ds.coin;

            // LOCATION
            gp.player.worldX = ds.playerWorldX;
            gp.player.worldY = ds.playerWorldY;

            // INVENTORY
            gp.player.inventory.clear();

            for (int i = 0; i < ds.itemNames.size(); i++) {
                Entity item = getObject(ds.itemNames.get(i));
                if (item != null) {
                    item.amount = ds.itemAmounts.get(i);
                    gp.player.inventory.add(item);
                }
            }

            // CURRENT EQUIPMENT
            if (ds.currentWeaponSlot < gp.player.inventory.size()) {
                gp.player.currentWeapon =
                        gp.player.inventory.get(ds.currentWeaponSlot);
            }

            if (ds.currentShieldSlot < gp.player.inventory.size()) {
                gp.player.currentShield =
                        gp.player.inventory.get(ds.currentShieldSlot);
            }

            gp.player.getAttack();
            gp.player.getDefense();
            gp.player.getPlayerAttackImages();

            // OBJECTS ON MAP
            for (int i = 0; i < gp.obj.length; i++) {

                if (ds.mapObjectNames[i].equals("NA")) {
                    gp.obj[i] = null;
                    continue;
                }

                gp.obj[i] = getObject(ds.mapObjectNames[i]);
                if (gp.obj[i] == null) continue;

                gp.obj[i].worldX = ds.mapObjectWorldX[i];
                gp.obj[i].worldY = ds.mapObjectWorldY[i];
                gp.obj[i].opened = ds.mapObjectOpened[i];

                if (!gp.obj[i].opened && !ds.mapObjectLootName[i].equals("NA")) {
                    gp.obj[i].loot = getObject(ds.mapObjectLootName[i]);
                } else {
                    gp.obj[i].loot = null;
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

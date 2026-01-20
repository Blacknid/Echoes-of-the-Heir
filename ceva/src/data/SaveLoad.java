package data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import entity.Entity;
import main.GamePanel;
import object.OBJ_Book;
import object.OBJ_Boots;
import object.OBJ_Chest;
import object.OBJ_Compas;
import object.OBJ_Door;
import object.OBJ_Gem;
import object.OBJ_Key;
import object.OBJ_Potion;

public class SaveLoad {

    GamePanel gp;

    public SaveLoad ( GamePanel gp ) {
        this.gp = gp;
    }
    public Entity getObject(String itemName) {
        
        Entity obj = null;

        switch (itemName) {
            case "Spell book": obj = new OBJ_Book(gp); break;
            case "Boots": obj = new OBJ_Boots(gp); break;
            case "Chest": obj = new OBJ_Chest(gp); break;
            case "Door": obj = new OBJ_Door(gp); break;
            case "Gem": obj = new OBJ_Gem(gp); break;
            case "Key": obj = new OBJ_Key(gp); break;
            case "Compas": obj = new OBJ_Compas(gp); break;
            case "Potion": obj = new OBJ_Potion(gp); break;
            case "Wood_Shield": obj = new object.OBJ_Shield_Wood(gp); break;
            case "Normal Sword": obj = new object.OBJ_Sword_Normal(gp); break;
        }
        return obj;
    }
    public void save() {

        try {
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File("save.dat")));

            DataStorage ds = new DataStorage();

            // STATS
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

            // INVENTORY
            
            for ( int i = 0; i < gp.player.inventory.size(); i++ ){

                ds.itemNames.add(gp.player.inventory.get(i).name);
                ds.itemAmounts.add(gp.player.inventory.get(i).amount);

            }

            // CURRENT WEAPON AND SHIELD SLOTS
            ds.currentWeaponSlot = gp.player.getCurrentWeaponSlot();
            ds.currentShieldSlot = gp.player.getCurrentShieldSlot();

            // OBJECTS ON MAP
            ds.mapObjectNames = new String[gp.obj.length];
            ds.mapObjectWorldX = new int[gp.obj.length];
            ds.mapObjectWorldY = new int[gp.obj.length];
            ds.mapObjectLootName = new String[gp.obj.length];
            ds.mapObjectOpened = new boolean[gp.obj.length];

            for ( int i = 0; i < gp.obj.length; i++ ) {

                if ( gp.obj[i] == null ) {
                    ds.mapObjectNames[i] = "NA";
                    continue;
                }
                    ds.mapObjectNames[i] = gp.obj[i].name;
                    ds.mapObjectWorldX[i] = gp.obj[i].worldX;
                    ds.mapObjectWorldY[i] = gp.obj[i].worldY;
                    ds.mapObjectOpened[i] = gp.obj[i].opened;

                    if ( gp.obj[i].loot != null ) {
                        ds.mapObjectLootName[i] = gp.obj[i].loot.name;
                    }
                }

            // Write the DataStorage object to the file
            oos.writeObject(ds);
            oos.close();
            }
        
        catch (Exception e) {
            System.out.println("Save Exception!");
    } 
}  

    public void load() {

        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(new File("save.dat")));

            // Read the DataStorage object from the file
            DataStorage ds = (DataStorage) ois.readObject();

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

            // INVENTORY
            gp.player.inventory.clear();

            for (int i = 0; i < ds.itemNames.size(); i++) {
            
                String name = ds.itemNames.get(i);
                Entity item = getObject(name);
            
                if (item == null) {
                    System.out.println("❌ UNKNOWN ITEM IN SAVE FILE: [" + name + "]");
                    continue; // SKIP IT
                }
            
                item.amount = ds.itemAmounts.get(i);
                gp.player.inventory.add(item);
            }

            // CURRENT WEAPON AND SHIELD SLOTS
            gp.player.currentWeapon = gp.player.inventory.get( ds.currentWeaponSlot );
            gp.player.currentShield = gp.player.inventory.get( ds.currentShieldSlot );
            gp.player.getAttack();
            gp.player.getDefense();
            gp.player.getPlayerAttackImages();

            // OBJECTS ON MAP
            for ( int i = 0 ; i < gp.obj.length; i++ ) {

                if ( ds.mapObjectNames[i].equals("NA") ) {
                    gp.obj[i] = null;
                }
                else {
                    gp.obj[i] = getObject(ds.mapObjectNames[i]);
                    gp.obj[i].worldX = ds.mapObjectWorldX[i];
                    gp.obj[i].worldY = ds.mapObjectWorldY[i];
                    gp.obj[i].opened = ds.mapObjectOpened[i];

                    if (!gp.obj[i].opened && !ds.mapObjectLootName[i].equals("NA")) {
                        gp.obj[i].loot = getObject(ds.mapObjectLootName[i]);
                    }

                    if (gp.obj[i].opened) {
                        gp.obj[i].down1 = gp.obj[i].image1;
                        gp.obj[i].loot = null; // explicitly empty
                    }
                }
            }
            ois.close();
        }
        catch (Exception e) {
            System.out.println("Load Exception!");
        }
    }
    }




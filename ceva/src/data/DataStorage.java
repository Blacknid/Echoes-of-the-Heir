package data;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;

public class DataStorage implements java.io.Serializable {
    
    // PLAYER STATS
    int level;
    int maxLife;
    int life;
    int maxMana;
    int mana;
    int strenght;
    int dexterity;
    int exp;
    int nextLevelExp;
    int coin;
    
    // Player inventory
    ArrayList<String> itemNames = new ArrayList<>();
    ArrayList<Integer> itemAmounts = new ArrayList<>();
    int currentWeaponSlot;
    int currentShieldSlot;

    // OBJECTS ON MAP
    String mapObjectNames[];
    int mapObjectWorldX[];
    int mapObjectWorldY[];
    String mapObjectLootName[];
    boolean mapObjectOpened[];

}

package data;

import java.io.Serializable;
import java.util.ArrayList;

public class DataStorage implements Serializable {
    
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
    public String mapObjectNames[];
    public int mapObjectWorldX[];
    public int mapObjectWorldY[];
    public String mapObjectLootName[];
    public boolean mapObjectOpened[];

}

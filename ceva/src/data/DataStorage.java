package data;

import java.io.Serializable;
import java.util.ArrayList;

public class DataStorage implements Serializable {

    private static final long serialVersionUID = 1L;

    // PLAYER STATS
    public int level;
    public int maxLife;
    public int life;
    public int maxMana;
    public int mana;
    public int strenght;
    public int dexterity;
    public int exp;
    public int nextLevelExp;
    public int coin;

    // INVENTORY
    public ArrayList<String> itemNames = new ArrayList<>();
    public ArrayList<Integer> itemAmounts = new ArrayList<>();
    public int currentWeaponSlot;
    public int currentShieldSlot;

    // OBJECTS ON MAP
    public String[] mapObjectNames;
    public int[] mapObjectWorldX;
    public int[] mapObjectWorldY;
    public String[] mapObjectLootName;
    public boolean[] mapObjectOpened;
}

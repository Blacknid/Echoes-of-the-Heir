package data;

import java.io.Serializable;
import java.util.ArrayList;

public class GameState implements Serializable {

    private static final long serialVersionUID = 2L;

    // POSITION
    public int playerX;
    public int playerY;
    public int playerZ;
    public String direction;
    public String mapID;

    // STATS
    public int level;
    public int maxHealth;
    public int health;
    public int maxMana;
    public int mana;
    public int strength;
    public int dexterity;
    public int exp;
    public int nextLevelExp;
    public int coin;

    // SKILLS
    public int skillPoints;
    public boolean dashUnlocked;
    public boolean shockwaveUnlocked;
    public boolean voidSnareUnlocked;
    public boolean frostNovaUnlocked;
    public boolean overdriveUnlocked;

    // INVENTORY
    public ArrayList<String> itemNames = new ArrayList<>();
    public ArrayList<Integer> itemAmounts = new ArrayList<>();
    public int currentWeaponSlot;
    public int currentShieldSlot;

    // OBJECTS ON MAP (chests, doors, etc.)
    public String[] mapObjectNames;
    public int[] mapObjectWorldX;
    public int[] mapObjectWorldY;
    public String[] mapObjectLootName;
    public boolean[] mapObjectOpened;

    // SYNC
    public long timestamp;
}

package data;

import java.io.Serializable;
import java.util.ArrayList;

public class GameState implements Serializable {

    private static final long serialVersionUID = 2L;

    public int playerX;
    public int playerY;
    public int playerZ;
    public int direction;
    public String mapID;

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

    public int skillPoints;
    public boolean dashUnlocked;
    public boolean shockwaveUnlocked;
    public boolean voidSnareUnlocked;
    public boolean frostNovaUnlocked;
    public boolean overdriveUnlocked;

    public ArrayList<String> itemNames = new ArrayList<>();
    public ArrayList<Integer> itemAmounts = new ArrayList<>();
    public int currentWeaponSlot;
    public int currentShieldSlot;

    public String[] mapObjectNames;
    public int[] mapObjectWorldX;
    public int[] mapObjectWorldY;
    public String[] mapObjectLootName;
    public boolean[] mapObjectOpened;

    public ArrayList<String> collectedFragmentIds = new ArrayList<>();
    public int totalFragmentsCollected;

    public boolean boss1Defeated;
    public boolean boss2Defeated;
    public boolean boss3Defeated;
    public boolean boss4Defeated;

    public int storyAct;           // 0=tutorial, 1=shatterLake, 2=ashenWoods, 3=citadel, 4=gallery, 5=frame
    public int endingChosen;       // 0=none, 1=confront, 2=sacrifice, 3=forgive

    public ArrayList<String> openedGates = new ArrayList<>();

    public ArrayList<String> claimedNPCFragments = new ArrayList<>();
    public ArrayList<String> metNPCs = new ArrayList<>();
    public ArrayList<String> completedSideQuests = new ArrayList<>();

    /** Unlocked skill tree node IDs — supersedes the individual boolean fields. */
    public ArrayList<String> unlockedSkillNodes = new ArrayList<>();
    public ArrayList<String> questIds = new ArrayList<>();
    public ArrayList<String> questNames = new ArrayList<>();
    public ArrayList<String> questDescriptions = new ArrayList<>();
    public ArrayList<Integer> questProgress = new ArrayList<>();
    public ArrayList<Integer> questTargets = new ArrayList<>();
    public ArrayList<Integer> questCurrentSteps = new ArrayList<>();
    public ArrayList<Integer> questStepProgress = new ArrayList<>();
    public ArrayList<String> dialogueChoicesMade = new ArrayList<>(); // key=value pairs

    public long timestamp;
}

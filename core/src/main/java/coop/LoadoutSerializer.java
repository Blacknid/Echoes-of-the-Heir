package coop;

import entity.Entity;
import entity.Player;
import data.ItemFactory;
import main.GamePanel;

/**
 * Converts a {@link Player}'s loadout to/from the compact JSON sent over {@link BossCoopProtocol}.
 * Unlike {@link PlayerLoadout} (which snapshots real {@link Entity} references for a same-process
 * restore), this crosses a socket to a different JVM entirely, so inventory items travel as their
 * {@code itemId} + stack amount and get rebuilt via {@link ItemFactory} on the receiving end —
 * anything not registered in items.json (a null itemId) is simply not transferable and is skipped.
 */
public final class LoadoutSerializer {
    private LoadoutSerializer() {}

    public static String toJson(Player p) {
        StringBuilder items = new StringBuilder();
        boolean first = true;
        for (Entity e : p.inventory) {
            if (e.itemId == null) continue;
            if (!first) items.append(',');
            items.append("{\"id\":\"").append(BossCoopProtocol.jsonEscape(e.itemId))
                 .append("\",\"amount\":").append(e.amount).append('}');
            first = false;
        }

        return "{"
            + "\"level\":" + p.level + ","
            + "\"maxLife\":" + p.maxLife + ","
            + "\"maxMana\":" + p.maxMana + ","
            + "\"strenght\":" + p.strenght + ","
            + "\"dexterity\":" + p.dexterity + ","
            + "\"meleeDamageMultiplier\":" + p.meleeDamageMultiplier + ","
            + "\"shockwaveUnlocked\":" + p.shockwaveUnlocked + ","
            + "\"voidSnareUnlocked\":" + p.voidSnareUnlocked + ","
            + "\"frostNovaUnlocked\":" + p.frostNovaUnlocked + ","
            + "\"overdriveUnlocked\":" + p.overdriveUnlocked + ","
            + "\"soulReaperUnlocked\":" + p.soulReaperUnlocked + ","
            + "\"berserkerFuryUnlocked\":" + p.berserkerFuryUnlocked + ","
            + "\"shadowStepUnlocked\":" + p.shadowStepUnlocked + ","
            + "\"manaSiphonUnlocked\":" + p.manaSiphonUnlocked + ","
            + "\"manaShieldUnlocked\":" + p.manaShieldUnlocked + ","
            + "\"thornsUnlocked\":" + p.thornsUnlocked + ","
            + "\"secondWindUnlocked\":" + p.secondWindUnlocked + ","
            + "\"vampiricStrikeUnlocked\":" + p.vampiricStrikeUnlocked + ","
            + "\"lastStandUnlocked\":" + p.lastStandUnlocked + ","
            + "\"undyingWillUnlocked\":" + p.undyingWillUnlocked + ","
            + "\"items\":[" + items + "]"
            + "}";
    }

    /** Overlays the host's loadout (parsed from {@code json}) onto {@code target}, for the fight only. */
    public static void applyTo(GamePanel gp, String json, Player target) {
        target.level = BossCoopProtocol.extractInt(json, "level", target.level);
        target.maxLife = BossCoopProtocol.extractInt(json, "maxLife", target.maxLife);
        target.life = target.maxLife;
        target.maxMana = BossCoopProtocol.extractInt(json, "maxMana", target.maxMana);
        target.mana = target.maxMana;
        target.strenght = BossCoopProtocol.extractInt(json, "strenght", target.strenght);
        target.dexterity = BossCoopProtocol.extractInt(json, "dexterity", target.dexterity);

        target.shockwaveUnlocked = extractBool(json, "shockwaveUnlocked");
        target.voidSnareUnlocked = extractBool(json, "voidSnareUnlocked");
        target.frostNovaUnlocked = extractBool(json, "frostNovaUnlocked");
        target.overdriveUnlocked = extractBool(json, "overdriveUnlocked");
        target.soulReaperUnlocked = extractBool(json, "soulReaperUnlocked");
        target.berserkerFuryUnlocked = extractBool(json, "berserkerFuryUnlocked");
        target.shadowStepUnlocked = extractBool(json, "shadowStepUnlocked");
        target.manaSiphonUnlocked = extractBool(json, "manaSiphonUnlocked");
        target.manaShieldUnlocked = extractBool(json, "manaShieldUnlocked");
        target.thornsUnlocked = extractBool(json, "thornsUnlocked");
        target.secondWindUnlocked = extractBool(json, "secondWindUnlocked");
        target.vampiricStrikeUnlocked = extractBool(json, "vampiricStrikeUnlocked");
        target.lastStandUnlocked = extractBool(json, "lastStandUnlocked");
        target.undyingWillUnlocked = extractBool(json, "undyingWillUnlocked");

        target.inventory.clear();
        int idx = json.indexOf("\"items\":[");
        if (idx >= 0) {
            int end = json.indexOf(']', idx);
            String itemsBlock = end > idx ? json.substring(idx, end) : "";
            for (String entry : itemsBlock.split("\\},\\{")) {
                String id = BossCoopProtocol.extractString(entry.contains("{") ? entry : "{" + entry, "id");
                int amount = BossCoopProtocol.extractInt(entry, "amount", 1);
                if (id == null || id.isBlank()) continue;
                Entity item = ItemFactory.create(gp, id);
                if (item != null) {
                    item.amount = amount;
                    target.inventory.add(item);
                }
            }
        }
        target.attack = target.getAttack();
        target.defense = target.getDefense();
    }

    private static boolean extractBool(String json, String key) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return false;
        int start = i + search.length();
        return json.startsWith("true", start);
    }
}

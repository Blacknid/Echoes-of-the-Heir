package coop;

import java.util.ArrayList;

import entity.Entity;
import entity.Player;

/**
 * Snapshot of everything that makes up a {@link Player}'s combat loadout — inventory, unlocked
 * abilities, and core stats. Used by co-op boss sessions (see {@link BossCoopSession}) to give a
 * joiner a temporary clone of the HOST's loadout for the fight (per design: joiners shouldn't show
 * up empty-handed to a boss scaled for a full party), then restore the joiner's own original
 * loadout the moment the session ends, win or lose. Nothing here touches the host — only the
 * joiner's {@link Player} is ever snapshotted-and-overwritten-and-restored.
 *
 * <p>Field list mirrors exactly what {@code Player.setDefaultValues()} resets on a new game, since
 * that's the authoritative list of "everything a loadout consists of" already maintained elsewhere
 * in this codebase.
 */
public final class PlayerLoadout {

    private final ArrayList<Entity> inventory;
    private final Entity currentWeapon;
    private final Entity currentShield;

    private final int level, strenght, dexterity;
    private final int maxLife, life, maxMana, mana;
    private final int coin;

    private final float meleeDamageMultiplier;
    private final float damageTakenMultiplier;
    private final boolean dashUnlocked;
    private final boolean shockwaveUnlocked;
    private final boolean voidSnareUnlocked;
    private final boolean frostNovaUnlocked;
    private final boolean overdriveUnlocked;
    private final boolean soulReaperUnlocked;
    private final boolean berserkerFuryUnlocked;
    private final boolean shadowStepUnlocked;
    private final boolean manaSiphonUnlocked;
    private final boolean manaShieldUnlocked;
    private final boolean thornsUnlocked;
    private final boolean secondWindUnlocked;
    private final boolean vampiricStrikeUnlocked;
    private final boolean lastStandUnlocked;
    private final boolean undyingWillUnlocked;

    private PlayerLoadout(Player p) {
        this.inventory = new ArrayList<>(p.inventory);
        this.currentWeapon = p.currentWeapon;
        this.currentShield = p.currentShield;
        this.level = p.level;
        this.strenght = p.strenght;
        this.dexterity = p.dexterity;
        this.maxLife = p.maxLife;
        this.life = p.life;
        this.maxMana = p.maxMana;
        this.mana = p.mana;
        this.coin = p.coin;
        this.meleeDamageMultiplier = p.meleeDamageMultiplier;
        this.damageTakenMultiplier = p.damageTakenMultiplier;
        this.dashUnlocked = p.dashUnlocked;
        this.shockwaveUnlocked = p.shockwaveUnlocked;
        this.voidSnareUnlocked = p.voidSnareUnlocked;
        this.frostNovaUnlocked = p.frostNovaUnlocked;
        this.overdriveUnlocked = p.overdriveUnlocked;
        this.soulReaperUnlocked = p.soulReaperUnlocked;
        this.berserkerFuryUnlocked = p.berserkerFuryUnlocked;
        this.shadowStepUnlocked = p.shadowStepUnlocked;
        this.manaSiphonUnlocked = p.manaSiphonUnlocked;
        this.manaShieldUnlocked = p.manaShieldUnlocked;
        this.thornsUnlocked = p.thornsUnlocked;
        this.secondWindUnlocked = p.secondWindUnlocked;
        this.vampiricStrikeUnlocked = p.vampiricStrikeUnlocked;
        this.lastStandUnlocked = p.lastStandUnlocked;
        this.undyingWillUnlocked = p.undyingWillUnlocked;
    }

    /** Capture the given player's current loadout so it can be restored later via {@link #applyTo}. */
    public static PlayerLoadout capture(Player p) {
        return new PlayerLoadout(p);
    }

    /** Overwrite {@code target}'s loadout fields with the ones captured here. */
    public void applyTo(Player target) {
        target.inventory.clear();
        target.inventory.addAll(inventory);
        target.currentWeapon = currentWeapon;
        target.currentShield = currentShield;
        target.level = level;
        target.strenght = strenght;
        target.dexterity = dexterity;
        target.maxLife = maxLife;
        target.life = life;
        target.maxMana = maxMana;
        target.mana = mana;
        target.coin = coin;
        target.meleeDamageMultiplier = meleeDamageMultiplier;
        target.damageTakenMultiplier = damageTakenMultiplier;
        target.dashUnlocked = dashUnlocked;
        target.shockwaveUnlocked = shockwaveUnlocked;
        target.voidSnareUnlocked = voidSnareUnlocked;
        target.frostNovaUnlocked = frostNovaUnlocked;
        target.overdriveUnlocked = overdriveUnlocked;
        target.soulReaperUnlocked = soulReaperUnlocked;
        target.berserkerFuryUnlocked = berserkerFuryUnlocked;
        target.shadowStepUnlocked = shadowStepUnlocked;
        target.manaSiphonUnlocked = manaSiphonUnlocked;
        target.manaShieldUnlocked = manaShieldUnlocked;
        target.thornsUnlocked = thornsUnlocked;
        target.secondWindUnlocked = secondWindUnlocked;
        target.vampiricStrikeUnlocked = vampiricStrikeUnlocked;
        target.lastStandUnlocked = lastStandUnlocked;
        target.undyingWillUnlocked = undyingWillUnlocked;
        target.attack = target.getAttack();
        target.defense = target.getDefense();
    }
}

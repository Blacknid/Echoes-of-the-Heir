package server;

import data.MonsterFactory;

/**
 * The server's own copy of one monster's combat state.
 *
 * <p>This exists so the outcome of a fight is decided by the server, from the shared
 * {@code monsters.json} rulebook, instead of being asserted by a client. A client says
 * "I hit mob 7 for 9 damage"; the server looks up what that monster's {@code defense} and
 * {@code maxLife} actually are, applies the same subtraction the client's {@code Player} does,
 * and is the one that decides when the mob is dead and how much XP the kill is worth. A modified
 * client can still <em>ask</em>, but it can no longer <em>declare</em> a mob dead or hand itself
 * a fortune in experience.
 *
 * <p>Damage is validated, not blindly recorded. The {@link #DAMAGE_CAP} bounds a single hit so a
 * client cannot one-shot a boss by claiming an absurd number; the real per-swing damage formula
 * lives in {@code Player} and moving it here in full is the next step of combat authority.
 */
final class AuthoritativeMob {

    /**
     * Hard ceiling on the damage a single reported hit may apply. The honest melee formula tops
     * out well under this even with heavy buffs; it exists purely to stop a client claiming a
     * kill-in-one-hit on something with a large life pool. Tightening this to the attacker's
     * actual server-known attack stat is the deeper follow-up.
     */
    static final int DAMAGE_CAP = 999;

    final int mobId;
    final String mobType;
    final int maxLife;
    final int expReward;
    final int defense;

    int life;
    boolean alive = true;
    boolean dying = false;
    int lastAttackerPid = -1;

    AuthoritativeMob(int mobId, String mobType) {
        this.mobId = mobId;
        this.mobType = mobType;
        // Pull the real numbers from the same JSON the client renders from. Unknown types (a mob
        // the server has no definition for) fall back to conservative defaults rather than trust
        // the client, so a bogus mob_type can't invent a huge XP pinata.
        this.maxLife = Math.max(1, MonsterFactory.defStat(mobType, "maxLife", 6));
        this.defense = Math.max(0, MonsterFactory.defStat(mobType, "defense", 0));
        this.expReward = Math.max(0, MonsterFactory.defStat(mobType, "exp", 0));
        this.life = this.maxLife;
    }

    /**
     * Apply a claimed hit. The server owns the life pool: it clamps the damage to a sane range,
     * subtracts the monster's real defense (matching the client formula), and reports whether
     * this hit killed the mob. Returns the damage actually applied (0 if the mob was already
     * dead or the claim was rejected).
     */
    int applyHit(int claimedDamage, int attackerPid) {
        if (!alive) return 0;
        if (claimedDamage <= 0) return 0;

        int dmg = Math.min(claimedDamage, DAMAGE_CAP);
        // The client's Player subtracts monster defense; do the same so the server and client
        // agree on how much a hit is worth rather than double-counting or diverging.
        dmg = Math.max(1, dmg - defense);

        int before = life;
        life = Math.max(0, life - dmg);
        lastAttackerPid = attackerPid;
        if (life == 0) {
            alive = false;
            dying = true;
        }
        return before - life;
    }
}

package entity;

/**
 * Interface for entities that participate in combat (monsters, player, NPCs with HP).
 * Entity.java implements this — fields remain in Entity for now.
 */
public interface ICombatable {
    int getMaxLife();
    int getLife();
    void setLife(int life);
    int getAttack();
    int getDefense();
    boolean isInvincible();
    boolean isDying();
    void beginDeath(int rewardExp, int rewardQuestKills, int rewardCoins);
    void applyCrowdControl(int frames);
    void damageReaction();
}

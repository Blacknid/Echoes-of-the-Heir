package main;

/**
 * Entity type identifiers. Replaces the scattered int constants in Entity class.
 * Used for collision filtering, pickup logic, and AI behavior.
 */
public enum EntityType {
    PLAYER,
    NPC,
    MONSTER,
    SWORD,
    BOOK,
    SHIELD,
    CONSUMABLE,
    PICKUP_ONLY,
    OBSTACLE,
    BUFFS,
    ENDING;
}

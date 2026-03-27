package main;

/**
 * All possible game states. Replaces scattered int constants throughout GamePanel.
 * Using an enum prevents invalid state values and makes switch statements exhaustive.
 */
public enum GameState {
    TITLE,
    PLAY,
    PAUSE,
    DIALOGUE,
    CHARACTER,
    OPTIONS,
    GAME_OVER,
    CUTSCENE,
    TRANSITION,
    LEVEL_UP,
    SKILL_TREE,
    MULTIPLAYER_PLAY;
}

package audio;

/**
 * Sound effect indices as named constants.
 * Eliminates magic numbers when calling playSE() / playMusic().
 */
public final class SFX {
    private SFX() {}

    public static final int MAIN_THEME    = 0;
    public static final int CANVAS_VILLAGE = 1;
    public static final int AWAKENING_CAVE = 2;
    public static final int DOOR           = 3;
    public static final int EQUIP          = 4;
    public static final int MENU_SELECT    = 5;
    public static final int GAME_OVER      = 6;
    public static final int GOT_GEM        = 7;
    public static final int VICTORY        = 8;
    public static final int MENU_CURSOR    = 9;
    public static final int PLAYER_HIT     = 10;
    public static final int MONSTER_HIT    = 11;
    public static final int WEAPON_SWING   = 12;
    public static final int LEVEL_UP       = 13;
    public static final int ARROW          = 14;
    public static final int MUSIC_BOX_1    = 15;
}

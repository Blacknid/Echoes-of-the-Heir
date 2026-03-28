package audio;

/**
 * Sound effect indices as named constants.
 * Eliminates magic numbers when calling playSE() / playMusic().
 */
public final class SFX {
    private SFX() {}

    public static final int MUSIC_THEME    = 0;
    public static final int DOOR           = 1;
    public static final int EQUIP          = 2;
    public static final int MENU_SELECT    = 3;
    public static final int GAME_OVER      = 4;
    public static final int GOT_GEM        = 5;
    public static final int VICTORY        = 6;
    public static final int MENU_CURSOR    = 7;
    public static final int PLAYER_HIT     = 8;
    public static final int MONSTER_HIT    = 9;
    public static final int WEAPON_SWING   = 10;
    public static final int LEVEL_UP       = 11;
    public static final int ARROW          = 12;
    public static final int MUSIC_BOX_1    = 13;
}

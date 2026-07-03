package main;

import java.io.InputStream;

/**
 * Validates that critical game resources exist at startup.
 * Logs clear error messages for any missing asset so problems
 * are caught early instead of causing NPEs mid-game.
 */
public class AssetValidator {

    private int missing = 0;

    // Critical resources that the game cannot function without
    private static final String[] CRITICAL_ASSETS = {
        "/res/player/Player_walking-sheet.png",
        "/res/player/Player_idle-sheet.png",
        "/res/maps/Canvas_Village.tmx",
        "/res/sound/Michiduta Theme.wav",
        "/res/sound/GameOver.wav",
        "/res/weapons/Wooden Sword.png",
        "/res/shields/Wooden Shield.png",
        "/res/objects/Key.png",
        "/res/objects/full_heart.png",
        "/res/consumables/Potion.png",
        "/res/monster/Monster_walking-sheet.png",
        "/res/objects/chests.png",
        "/res/data/skilltree.json",
        "/res/build.properties",
    };

    /**
     * Check all critical assets and log results.
     * @return number of missing assets (0 = all good)
     */
    public int validate() {
        missing = 0;
        System.out.println("[AssetValidator] Checking critical resources...");

        for (String path : CRITICAL_ASSETS) {
            check(path);
        }

        if (missing == 0) {
            System.out.println("[AssetValidator] All " + CRITICAL_ASSETS.length + " critical assets OK.");
        } else {
            System.out.println("[AssetValidator] WARNING: " + missing + " of "
                + CRITICAL_ASSETS.length + " critical assets MISSING!");
        }
        return missing;
    }

    private void check(String resourcePath) {
        InputStream is = util.ResourceCache.openClasspathStream(resourcePath);
        if (is == null) {
            System.out.println("[AssetValidator] MISSING: " + resourcePath);
            missing++;
        } else {
            try { is.close(); } catch (Exception ignored) {}
        }
    }
}

package desktop;

import desktop.update.UpdateClient;

/** Pre-launch bootstrap: update check. Runs before the GL window opens. */
public final class DesktopBootstrap {

    private DesktopBootstrap() {}

    /** Returns false if the process should exit before opening the window (update applied/declined). */
    public static boolean bootstrap() {
        // Mandatory update check — must run before the window/game so the JAR can be replaced.
        if (!UpdateClient.checkAndApply()) {
            return false;
        }

        // License activation now happens in MichiGame.create() (platform.LicenseActivation),
        // same call on every backend — Gdx isn't live yet at this point in desktop startup.

        // Dev mode: let ResourceCache read .tmx/.tsx files directly from disk so in-game R
        // reload picks up Tiled edits without a resource sync step.
        if (main.Main.DEBUG_MODE) {
            util.ResourceCache.setDevSourcePath(System.getProperty("user.dir") + "/core/assets");
        }
        return true;
    }
}

package desktop;

import desktop.data.LicenseManager;
import desktop.update.UpdateClient;

/** Pre-launch bootstrap: update check + license load/watchdog. Runs before the GL window opens. */
public final class DesktopBootstrap {

    private DesktopBootstrap() {}

    /** Returns false if the process should exit before opening the window (update applied/declined). */
    public static boolean bootstrap() {
        // Mandatory update check — must run before the window/game so the JAR can be replaced.
        if (!UpdateClient.checkAndApply()) {
            return false;
        }

        // Load + verify the signed, machine-bound license (null on any failure — game still runs).
        main.Main.LICENSE_KEY = LicenseManager.load();
        if (main.Main.LICENSE_KEY != null) {
            LicenseManager.startWatchdog(60);
        }

        // Dev mode: let ResourceCache read .tmx/.tsx files directly from disk so in-game R
        // reload picks up Tiled edits without a resource sync step.
        if (main.Main.DEBUG_MODE) {
            util.ResourceCache.setDevSourcePath(System.getProperty("user.dir") + "/core/assets");
        }
        return true;
    }
}

package desktop;

import desktop.itch.DesktopItchAuth;
import desktop.update.UpdateClient;

/** Pre-launch bootstrap: update check + itch auth wiring. Runs before the GL window opens. */
public final class DesktopBootstrap {

    private DesktopBootstrap() {}

    /** Returns false if the process should exit before opening the window (update applied/declined). */
    public static boolean bootstrap() {
        // Mandatory update check — must run before the window/game so the JAR can be replaced.
        if (!UpdateClient.checkAndApply()) {
            return false;
        }

        // Proof-of-purchase provider for first-run activation. Registered (not invoked) here:
        // LicenseActivation only calls it if this install has no license yet, so a returning
        // player never sees a browser window. Desktop-only — the OAuth flow needs AWT/HttpServer,
        // which is why it lives in this module and core sees only the interface.
        platform.ItchAuthProvider.set(new DesktopItchAuth());

        // License activation itself happens in MichiGame.create() (platform.LicenseActivation),
        // same call on every backend — Gdx isn't live yet at this point in desktop startup.

        // Dev mode: let ResourceCache read .tmx/.tsx files directly from disk so in-game R
        // reload picks up Tiled edits without a resource sync step.
        if (main.Main.DEBUG_MODE) {
            util.ResourceCache.setDevSourcePath(System.getProperty("user.dir") + "/core/assets");
        }
        return true;
    }
}

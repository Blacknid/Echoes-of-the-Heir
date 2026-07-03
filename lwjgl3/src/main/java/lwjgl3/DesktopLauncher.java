package lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import main.Main;
import main.MichiGame;

/**
 * Desktop entry point (LWJGL3 backend). Replaces the legacy Swing {@code Main}/{@code JFrame}
 * bootstrap. The window, GL context, and game loop are owned by libGDX from here on.
 *
 * <p>Pre-launch responsibilities that must survive from the old {@code Main.main} — the mandatory
 * update check and license load/watchdog — run via {@link Main#bootstrap()} before the window opens.
 */
public class DesktopLauncher {

    public static void main(String[] args) {
        // --version passthrough (the build's JAR-verify step calls this).
        for (String a : args) {
            if ("--version".equals(a)) {
                System.out.println("Michi's Adventure (libGDX build)");
                return;
            }
        }

        // Update check + license (may exit before opening the window if an update was applied).
        if (!Main.bootstrap()) {
            return;
        }

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Michi's Adventure");
        config.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        config.useVsync(true);
        config.setResizable(true);
        config.setForegroundFPS(60);
        // Window icon: only set if resolvable from the working dir (dev runs from project root),
        // else skip (the classpath-bundled icon can't be used by setWindowIcon directly).
        try {
            java.io.File icon = new java.io.File("ceva/src/res/icon.png");
            if (icon.exists()) {
                config.setWindowIcon(com.badlogic.gdx.Files.FileType.Absolute, icon.getAbsolutePath());
            }
        } catch (Throwable ignored) {}

        new Lwjgl3Application(new MichiGame(), config);
    }
}

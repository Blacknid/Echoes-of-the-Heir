package lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import main.MichiGame;

/**
 * Desktop entry point (LWJGL3 backend). Replaces the legacy Swing {@code Main}/{@code JFrame}
 * bootstrap. The window, GL context, and game loop are owned by libGDX from here on.
 *
 * <p>The legacy {@code Main.main} responsibilities that must survive (mandatory update check,
 * license load + watchdog) are re-attached here in Stage 6 once they are isolated behind
 * {@code core.platform.PlatformServices}. For Stage 0/1 this just brings up the libGDX window.
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

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Michi's Adventure");
        config.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        config.useVsync(true);
        // Match the legacy "undecorated, resizable" window behavior.
        config.setResizable(true);
        config.setForegroundFPS(60);
        // Window icon (multiple sizes recommended; the single icon.png works as a baseline).
        config.setWindowIcon(com.badlogic.gdx.Files.FileType.Internal, "res/icon.png");

        new Lwjgl3Application(new MichiGame(), config);
    }
}

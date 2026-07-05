package main;

/** Static app state. libGDX owns the window (desktop's DesktopLauncher/DesktopBootstrap → MichiGame). */
public class Main {

    public static final boolean OFFLINE_MODE = false;
    public static final boolean DEBUG_MODE = true;
    public static volatile String LICENSE_KEY = null;  // Set by platform.LicenseActivation.ensureActivated()

    /** Called on tamper/session-invalidation detection to kill saves/MP. */
    public static void invalidateLicense() {
        LICENSE_KEY = null;
    }
}

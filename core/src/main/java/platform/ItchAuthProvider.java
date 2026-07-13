package platform;

/**
 * Supplies an itch.io OAuth token as proof of purchase, once, at first activation.
 *
 * <p>The implementation is platform-specific and deliberately lives OUTSIDE core: the desktop
 * one ({@code desktop.itch.DesktopItchAuth}) needs {@code java.awt.Desktop} and
 * {@code com.sun.net.httpserver}, neither of which exists on Android. Core only ever sees this
 * interface, so it stays buildable on every backend.
 *
 * <p>If no provider is registered, {@link LicenseActivation} activates without a purchase token —
 * which succeeds only against a server that has its itch gate switched off.
 *
 * @see LicenseActivation#ensureActivated()
 */
public interface ItchAuthProvider {

    /**
     * Obtains an itch.io access token for the player, blocking until they finish (or abandon)
     * the browser flow. Never called on a later run — only when this install has no license yet.
     *
     * @return the token, or null if unsupported, cancelled, or timed out.
     */
    String authorize();

    // ---------------------------------------------------------------------------------

    /** Registered by the platform launcher at startup. Null on backends with no itch flow. */
    java.util.concurrent.atomic.AtomicReference<ItchAuthProvider> ACTIVE =
            new java.util.concurrent.atomic.AtomicReference<>();

    static void set(ItchAuthProvider provider) { ACTIVE.set(provider); }

    /** The registered provider's token, or null if none is registered. */
    static String tokenOrNull() {
        ItchAuthProvider p = ACTIVE.get();
        return p == null ? null : p.authorize();
    }
}

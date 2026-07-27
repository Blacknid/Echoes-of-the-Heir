package androidlauncher.itch;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import platform.ItchAuthProvider;

/**
 * Android itch.io OAuth, the mobile counterpart to {@code desktop.itch.DesktopItchAuth}.
 *
 * <p><b>Why this is not the desktop's loopback flow.</b> The desktop build binds a one-shot
 * {@code ServerSocket} on {@code http://127.0.0.1:34567/} and lets the browser redirect into it.
 * That pattern cannot work on Android, and this class used to try:
 *
 * <ul>
 *   <li>{@code 127.0.0.1} inside a browser is the <i>browser's</i> loopback, not this app's. The
 *       redirect never had a reason to reach our listener.</li>
 *   <li>Browsers block a navigation from a public origin ({@code itch.io}) to a private/loopback
 *       address (Private Network Access). Chrome and Samsung Internet both drop it <i>silently</i>
 *       — the tab simply sits there doing nothing, which is exactly how the bug presented.</li>
 * </ul>
 *
 * <p>The supported Android mechanism is a custom URI scheme: itch redirects to
 * {@code michi://itch-auth}, the OS resolves that intent against the {@code BROWSABLE} filter on
 * {@code AndroidLauncher}, and the token is delivered straight into the app. Because the routing
 * is done by the <b>OS</b> and not by the browser, this is browser-agnostic by construction —
 * Chrome, Samsung Internet, Firefox, and any default-browser choice all behave identically. There
 * is no socket, no cleartext hop, and nothing listening in the background.
 *
 * <p><b>The redirect URI must be registered on the itch OAuth application</b> (itch.io → Settings
 * → OAuth applications) alongside the desktop's existing {@code http://127.0.0.1:34567/}. itch
 * matches {@code redirect_uri} by exact string, so it must read exactly {@code michi://itch-auth},
 * and it must stay in sync with the {@code <data>} element in {@code AndroidManifest.xml}.
 *
 * <p>itch returns the token in the URL <i>fragment</i>. The desktop flow needs a JS bootstrap page
 * to recover it, because a fragment is never sent to an HTTP server; here Android hands the whole
 * URI over intact, so {@link Uri#getFragment()} reads it directly and that workaround is gone.
 *
 * @see platform.LicenseActivation#ensureActivated()
 * @see androidlauncher.AndroidLauncher#onNewIntent(Intent)
 */
public final class AndroidItchAuth implements ItchAuthProvider {

    /** Must match the redirect URI registered on the itch OAuth app, and the manifest filter. */
    private static final String REDIRECT_SCHEME = "michi";
    private static final String REDIRECT_HOST   = "itch-auth";
    private static final String REDIRECT_URI    = REDIRECT_SCHEME + "://" + REDIRECT_HOST;

    /** Same public client id the desktop build ships; a client id is not a secret. */
    private static final String CLIENT_ID = "00477f3fb217b3b7fc21fb520c5a65b3";

    private static final String SCOPE = "profile:me";
    private static final int AUTH_TIMEOUT_SECONDS = 180;

    /** Finished, but produced no token (denied / error). Distinct from "nothing arrived". */
    private static final String DENIED = "denied:no-token";

    /**
     * Handoff from the launcher's {@code onNewIntent} to the thread blocked in {@link #authorize()}.
     *
     * <p>Static because the redirect can arrive at a <i>different</i> Activity instance than the one
     * that started the flow: the browser is a separate task, and an OEM/aggressive memory manager
     * can destroy and recreate the game's Activity while it is backgrounded. An instance field
     * would be lost in that window and the sign-in would hang to the full timeout.
     *
     * <p>Capacity 1 with {@code offer} semantics: the first result wins and later duplicates (a
     * re-delivered intent, a double tap on "authorise") are dropped rather than queued for a
     * subsequent sign-in.
     */
    private static final ArrayBlockingQueue<String> RESULT = new ArrayBlockingQueue<>(1);

    private final Activity activity;

    public AndroidItchAuth(Activity activity) {
        this.activity = activity;
    }

    /**
     * Feed a redirect back into a waiting {@link #authorize()} call.
     *
     * <p>Called from the launcher for every {@code VIEW} intent; safe to call with anything, it
     * ignores URIs that are not our redirect.
     *
     * @return true if the intent was an itch redirect and was consumed here.
     */
    public static boolean handleRedirect(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (data == null) return false;
        if (!REDIRECT_SCHEME.equalsIgnoreCase(data.getScheme())) return false;
        if (!REDIRECT_HOST.equalsIgnoreCase(data.getHost())) return false;

        // itch puts the result in the fragment (response_type=token). Some browsers normalise a
        // custom-scheme URI oddly, so fall back to the query string rather than failing outright.
        String payload = data.getFragment();
        if (payload == null || payload.isEmpty()) payload = data.getEncodedQuery();

        String token = paramValue(payload, "access_token");
        String error = paramValue(payload, "error");

        if (token != null && !token.isEmpty()) {
            RESULT.offer(token);
        } else {
            System.out.println("[Itch] Authorization did not complete: "
                    + (error == null ? "empty token" : error));
            RESULT.offer(DENIED);
        }
        return true;
    }

    @Override
    public String authorize() {
        // Drop anything left over from an abandoned earlier attempt, so a stale DENIED can never
        // instantly fail the sign-in the player just started.
        RESULT.clear();

        String authUrl = "https://itch.io/user/oauth"
                + "?client_id="    + enc(CLIENT_ID)
                + "&scope="        + enc(SCOPE)
                + "&response_type=token"
                + "&redirect_uri=" + enc(REDIRECT_URI);

        if (!openBrowser(authUrl)) {
            System.out.println("[Itch] No browser available to complete sign-in.");
            toast("No browser available for itch.io sign-in.");
            return null;
        }

        try {
            String token = RESULT.poll(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (token == null) {
                System.out.println("[Itch] Authorization timed out after "
                        + AUTH_TIMEOUT_SECONDS + "s.");
                return null;
            }
            if (DENIED.equals(token)) {
                System.out.println("[Itch] Authorization did not complete (denied or empty).");
                return null;
            }
            System.out.println("[Itch] Signed in — sending proof of identity.");
            return token;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Open the sign-in page in the player's browser.
     *
     * <p>A plain {@code ACTION_VIEW} on purpose, rather than a Custom Tab: Custom Tabs would pull
     * in androidx.browser, and the return trip here is an OS-routed intent that works the same
     * whichever browser handles the page. {@code NEW_TASK} keeps the browser in its own task so the
     * game is not visually replaced by it, and the redirect later re-surfaces the game's existing
     * (singleTask) Activity rather than stacking a second copy.
     */
    private boolean openBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            System.out.println("[Itch] Could not open a browser: " + e);
            return false;
        }
    }

    /** Toasts must be posted to the UI thread; authorize() runs on a background thread. */
    private void toast(String message) {
        try {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
        } catch (RuntimeException ignored) {
            // Activity going away mid-flow — the log line above is enough.
        }
    }

    /** Pull one parameter out of a raw {@code a=1&b=2} fragment/query string. */
    private static String paramValue(String raw, String key) {
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !key.equals(pair.substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException impossible) {
                return null;
            }
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            return s;
        }
    }
}

package desktop.itch;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import platform.ItchAuthProvider;

/**
 * Desktop itch.io OAuth — used purely as proof of purchase, exactly once, at first activation.
 *
 * <p><b>itch is the door, not the landlord.</b> The token this returns is handed to our save
 * server, which asks itch "did this account buy the game?" and, if so, issues a license that
 * belongs to <i>us</i>. From then on the game authenticates with that license (activation_id +
 * encrypted blob) and itch is never contacted again — the player can play offline, without the
 * itch app, forever.
 *
 * <p>The loopback listener is likewise one-shot: it binds an ephemeral port, waits for the single
 * redirect carrying the token, and shuts down. Nothing keeps running in the background.
 *
 * <p>Lives in the desktop module because {@code java.awt.Desktop} and
 * {@code com.sun.net.httpserver} do not exist on Android; core sees only
 * {@link ItchAuthProvider}.
 */
public final class DesktopItchAuth implements ItchAuthProvider {

    /**
     * Baked-in itch.io OAuth application client id (itch.io → Settings → OAuth applications).
     * Public by design — a client id is not a secret. The API key that actually proves
     * ownership is a different value entirely and never leaves the server.
     */
    private static final String ITCH_CLIENT_ID_BAKED = "00477f3fb217b3b7fc21fb520c5a65b3";

    /** Overridable with {@code -Dmichi.itch.clientId=...} for testing against a second app. */
    private static final String CLIENT_ID =
            System.getProperty("michi.itch.clientId", ITCH_CLIENT_ID_BAKED);

    /** Only the player's identity is needed — the server does the ownership check itself. */
    private static final String SCOPE = "profile:me";

    private static final int AUTH_TIMEOUT_SECONDS = 180;

    @Override
    public String authorize() {
        if (CLIENT_ID.isBlank()) {
            System.out.println("[Itch] No OAuth client id configured (set michi.itch.clientId "
                    + "or bake one into DesktopItchAuth) — skipping the purchase check.");
            return null;
        }
        if (!browserAvailable()) {
            System.out.println("[Itch] No browser available on this system — cannot verify purchase.");
            return null;
        }

        HttpServer server = null;
        try {
            // Port 0 = the OS picks a free one. Register "http://127.0.0.1/" as the redirect URI
            // on the itch OAuth app; itch then accepts it on any port, which is what lets this
            // listener be ephemeral rather than a fixed, permanently-open port.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final int port = server.getAddress().getPort();
            final String redirectUri = "http://127.0.0.1:" + port + "/";

            final ArrayBlockingQueue<String> result = new ArrayBlockingQueue<>(1);

            server.createContext("/", exchange -> {
                String token = paramValue(exchange.getRequestURI().getRawQuery(), "access_token");

                if (token == null) {
                    // itch returns the token in the URL *fragment*, which browsers never send to
                    // the server. Serve a page whose JS copies the fragment into the query string
                    // and re-requests — the second hit is the one that actually carries the token.
                    respond(exchange,
                            "<p>Verifying your itch.io purchase…</p>"
                          + "<script>"
                          + "var f=location.hash.slice(1);"
                          + "location.replace(location.pathname+'?'+(f||'error=no_fragment'));"
                          + "</script>");
                    return;
                }

                boolean ok = !token.isBlank();
                respond(exchange, ok
                        ? "<h2>You're all set.</h2><p>Return to the game — you can close this tab.</p>"
                        : "<h2>Something went wrong.</h2><p>Please try again from the game.</p>");
                if (ok) result.offer(token);
            });

            server.start();

            String authUrl = "https://itch.io/user/oauth"
                    + "?client_id="    + enc(CLIENT_ID)
                    + "&scope="        + enc(SCOPE)
                    + "&response_type=token"
                    + "&redirect_uri=" + enc(redirectUri);

            if (!openBrowser(authUrl)) {
                System.out.println("[Itch] Could not open a browser. Open this URL manually:\n" + authUrl);
            }

            String token = result.poll(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (token == null) {
                System.out.println("[Itch] Authorization timed out or was cancelled.");
                return null;
            }
            System.out.println("[Itch] Authorization complete — sending proof of purchase.");
            return token;

        } catch (IOException e) {
            System.out.println("[Itch] Authorization failed: " + e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            // One-shot by design: the listener never outlives the purchase check.
            if (server != null) server.stop(0);
        }
    }

    private static boolean browserAvailable() {
        return Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
    }

    private static boolean openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
            return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private static void respond(HttpExchange exchange, String bodyHtml) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Michi's Adventure</title>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding-top:3em\">"
                + bodyHtml + "</body>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** Pull one parameter out of a raw {@code a=1&b=2} query string. */
    private static String paramValue(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !key.equals(pair.substring(0, eq))) continue;
            return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

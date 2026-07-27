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
 * Desktop itch.io OAuth, used as proof of <i>identity</i>, exactly once, at first activation.
 *
 * <p><b>itch is the door, not the landlord.</b> The token this returns is handed to our save
 * server, which resolves it to a real itch.io account and issues a license that belongs to
 * <i>us</i>. From then on the game authenticates with that license (activation_id + encrypted
 * blob) and itch is never contacted again, the player can play offline, without the itch app,
 * forever.
 *
 * <p>The game is free, so this is no longer a <i>purchase</i> check: itch does not mint a
 * download key for a free download, only for a purchase or an explicit claim, so requiring one
 * would refuse ordinary players. What the token still buys us is (a) rate-limiting activation
 * behind "you must have a real itch.io account", which is what keeps bulk/abusive activation
 * expensive, and (b) a stable identity, which is the ONLY way a player who loses
 * {@code activation.dat} can be reunited with their existing cloud saves.
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
 * Public by design, a client id is not a secret. The API key that actually proves
     * ownership is a different value entirely and never leaves the server.
     *
     * <p>While it is blank the purchase check is skipped, which only works against a server
     * with its itch gate switched off.
     */
    private static final String ITCH_CLIENT_ID_BAKED = "00477f3fb217b3b7fc21fb520c5a65b3";

    /** Overridable with {@code -Dmichi.itch.clientId=...} for testing against a second app. */
    private static final String CLIENT_ID =
            System.getProperty("michi.itch.clientId", ITCH_CLIENT_ID_BAKED);

    /** Only the player's identity is needed, the server does the ownership check itself. */
    private static final String SCOPE = "profile:me";

    private static final int AUTH_TIMEOUT_SECONDS = 180;

    /**
     * Sentinel pushed onto the result queue when the flow finished but produced no token
     * (player denied, or itch returned an error). Distinct from "nothing arrived at all",
     * which is a timeout — without it a denial waited out the full timeout for no reason.
     * A real itch token can never collide with this: {@code :} is outside the token charset.
     */
    private static final String DENIED = "denied:no-token";

    /**
 * itch.io validates redirect_uri with an exact string match, it does NOT accept a
     * registered "http://127.0.0.1/" on an arbitrary port. Must match the redirect URI
     * registered on the OAuth application exactly, port included.
     */
    private static final int REDIRECT_PORT = 34567;

    @Override
    public String authorize() {
        if (CLIENT_ID.isBlank()) {
            System.out.println("[Itch] No OAuth client id configured (set michi.itch.clientId "
                    + "or bake one into DesktopItchAuth) — skipping the itch sign-in.");
            return null;
        }

        HttpServer server = null;
        try {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", REDIRECT_PORT), 0);
            } catch (java.net.BindException bind) {
                // The port is fixed by itch's exact redirect_uri matching, so we cannot fall
                // back to an ephemeral one — a different port would just be rejected by itch.
                // Usually this is a previous run's listener that hasn't released the socket yet
                // (TIME_WAIT), so one short retry clears it. If something else genuinely owns
                // the port, say so in plain language instead of dying as a silent IOException,
                // which used to read to the player as "activation just doesn't work".
                System.out.println("[Itch] Port " + REDIRECT_PORT + " busy (" + bind.getMessage()
                        + ") — retrying once.");
                try {
                    Thread.sleep(1200);
                    server = HttpServer.create(new InetSocketAddress("127.0.0.1", REDIRECT_PORT), 0);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (IOException stillBusy) {
                    System.out.println("[Itch] Port " + REDIRECT_PORT + " is still in use — "
                            + "cannot run the sign-in flow. Close any other copy of the game and retry.");
                    showBlockedPortDialog();
                    return null;
                }
            }
            final int port = server.getAddress().getPort();
            final String redirectUri = "http://127.0.0.1:" + port + "/";

            final ArrayBlockingQueue<String> result = new ArrayBlockingQueue<>(1);

            server.createContext("/", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String token = paramValue(query, "access_token");
                String error = paramValue(query, "error");

                if (token == null && error == null) {
                    // itch returns the token in the URL *fragment*, which browsers never send to
                    // the server. Serve a page whose JS copies the fragment into the query string
                    // and re-requests, the second hit is the one that actually carries the token.
                    respond(exchange,
                            "<p>Signing you in with itch.io…</p>"
                          + "<script>"
                          + "var f=location.hash.slice(1);"
                          + "location.replace(location.pathname+'?'+(f||'error=no_fragment'));"
                          + "</script>");
                    return;
                }

                if (token == null || token.isBlank()) {
                    // The player denied access, or itch reported a problem. Previously this
                    // re-served the bootstrap page, so a denied sign-in bounced between the
                    // redirect and this handler until the 180s timeout expired with no
                    // explanation. Answer once, and unblock the waiting thread immediately.
                    String detail = "access_denied".equals(error)
                            ? "You declined the sign-in request."
                            : "no_fragment".equals(error)
                                ? "The sign-in response came back empty."
                                : "itch.io reported: " + safeText(error);
                    respond(exchange, "<h2>Sign-in wasn't completed.</h2><p>" + detail
                            + "</p><p>Return to the game and try again.</p>");
                    System.out.println("[Itch] Authorization did not complete: "
                            + (error == null ? "empty token" : error));
                    result.offer(DENIED);
                    return;
                }

                respond(exchange,
                        "<h2>You're all set.</h2><p>Return to the game — you can close this tab.</p>");
                result.offer(token);
            });

            server.start();

            String authUrl = "https://itch.io/user/oauth"
                    + "?client_id="    + enc(CLIENT_ID)
                    + "&scope="        + enc(SCOPE)
                    + "&response_type=token"
                    + "&redirect_uri=" + enc(redirectUri);

            // Try to open the default browser automatically. If that fails (no default browser,
            // headless AWT on a stripped runtime, a locked-down machine, etc.) the buyer is
            // running the GUI .exe with NO console, so a System.out message would be invisible and
            // activation would appear to "do nothing". Fall back to a visible Swing dialog that
            // shows the URL and lets them copy it, so the flow is never silent.
            if (!openBrowser(authUrl)) {
                System.out.println("[Itch] Could not open a browser automatically. Showing manual URL dialog.");
                showManualAuthDialog(authUrl);
            }

            String token = result.poll(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (token == null) {
                System.out.println("[Itch] Authorization timed out after "
                        + AUTH_TIMEOUT_SECONDS + "s (no response from the browser).");
                return null;
            }
            if (DENIED.equals(token)) return null;  // handled and reported above
            System.out.println("[Itch] Signed in — sending proof of identity.");
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

    /**
     * Open the player's default browser at {@code url}. Tries {@code java.awt.Desktop} first,
     * then falls back to the Windows shell ({@code rundll32 url.dll,FileProtocolHandler}), which
 * works even when AWT reports BROWSE unsupported. Returns false only if every route failed
     * the caller then shows the URL in a visible dialog rather than failing silently.
     */
    private static boolean openBrowser(String url) {
        if (Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                System.out.println("[Itch] Desktop.browse failed (" + e + ") — trying shell fallback.");
            }
        }
        // Shell fallback: works on stripped runtimes / locked-down machines where AWT BROWSE is
        // unavailable but a default browser is still registered.
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            return true;
        } catch (IOException e) {
            System.out.println("[Itch] Shell browser fallback failed: " + e);
            return false;
        }
    }

    /**
     * Last-resort visible prompt when no browser could be launched automatically. A buyer running
     * the packaged .exe has no console, so a printed URL would be invisible; this Swing dialog puts
     * the auth URL in front of them (pre-selected for copy) and keeps the loopback listener alive
     * while they open it by hand. Swing lives in the bundled {@code java.desktop} runtime module.
     */
    private static void showManualAuthDialog(String authUrl) {
        try {
            javax.swing.JTextArea area = new javax.swing.JTextArea(authUrl);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(false);
            area.setCaretPosition(0);
            area.selectAll();
            javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(area);
            scroll.setPreferredSize(new java.awt.Dimension(460, 90));

            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 8));
            panel.add(new javax.swing.JLabel(
                    "<html>We couldn't open your browser automatically.<br>"
                  + "Copy this link into a browser to sign in with itch.io, "
                  + "then return to the game:</html>"), java.awt.BorderLayout.NORTH);
            panel.add(scroll, java.awt.BorderLayout.CENTER);

            // Show on the EDT and don't block this (background) thread past the dialog; the caller
            // keeps waiting on the loopback listener afterwards regardless.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                // Copy to clipboard so "paste" just works even if they don't hand-select.
                try {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new java.awt.datatransfer.StringSelection(authUrl), null);
                } catch (RuntimeException ignored) { /* headless / no clipboard, dialog still shows */ }
                javax.swing.JOptionPane.showMessageDialog(null, panel,
                        "Michi's Adventure — sign in with itch.io",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (Exception e) {
            // If even Swing is unavailable, we've done all we can, the URL is still in the log.
            System.out.println("[Itch] Could not show manual-auth dialog (" + e + "). URL:\n" + authUrl);
        }
    }

    /**
     * Escape a value that came back in the redirect before echoing it into the response page.
     * The query string is attacker-influenceable (anything can hit this loopback listener), so
     * reflecting it raw would be a self-XSS vector, minor here but free to avoid.
     */
    private static String safeText(String s) {
        if (s == null || s.isBlank()) return "the sign-in was not completed";
        String trimmed = s.length() > 100 ? s.substring(0, 100) : s;
        return trimmed.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                      .replace("\"", "&quot;");
    }

    /**
     * Shown when the fixed redirect port can't be bound. The packaged .exe has no console, so
     * without this the sign-in would appear to do nothing at all.
     */
    private static void showBlockedPortDialog() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "<html>Couldn't start the itch.io sign-in step because port " + REDIRECT_PORT
                  + " on this computer is already in use.<br><br>"
                  + "This usually means another copy of the game is already running.<br>"
                  + "Close it and start the game again.</html>",
                    "Michi's Adventure — sign-in unavailable",
                    javax.swing.JOptionPane.WARNING_MESSAGE));
        } catch (Exception e) {
            System.out.println("[Itch] Port " + REDIRECT_PORT + " blocked; dialog failed too: " + e);
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

package androidlauncher.itch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
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
 * <p>Without this, {@code ItchAuthProvider.ACTIVE} was never set on Android at all, so
 * {@code ItchAuthProvider.tokenOrNull()} always returned null and a gated ACTIVATE could
 * <i>never</i> succeed on a phone. The owner-secret file was the only way an Android install
 * could ever get a license, which is exactly why activation appeared to "only work for the
 * owner" there.
 *
 * <p><b>Why a raw {@link ServerSocket} and not a custom URI scheme.</b> itch.io matches
 * {@code redirect_uri} by exact string, and the OAuth application is already registered with
 * {@code http://127.0.0.1:34567/} for the desktop build. Reusing that same loopback redirect
 * means this works against the <i>existing</i> itch app registration with no dashboard change
 * and no second client id. Android has no {@code com.sun.net.httpserver}, so the listener here
 * is a minimal one-shot HTTP responder rather than an {@code HttpServer}.
 *
 * <p>Like the desktop flow it is strictly one-shot: bind, serve the single redirect, close.
 * Nothing keeps listening in the background, and the socket is bound to loopback only, so it is
 * not reachable from the network.
 *
 * @see platform.LicenseActivation#ensureActivated()
 */
public final class AndroidItchAuth implements ItchAuthProvider {

    /** Must match the redirect URI registered on the itch OAuth app exactly, port included. */
    private static final int REDIRECT_PORT = 34567;

    /** Same public client id the desktop build ships; a client id is not a secret. */
    private static final String CLIENT_ID = "00477f3fb217b3b7fc21fb520c5a65b3";

    private static final String SCOPE = "profile:me";
    private static final int AUTH_TIMEOUT_SECONDS = 180;
    private static final int ACCEPT_TIMEOUT_MS = AUTH_TIMEOUT_SECONDS * 1000;

    /** Finished, but produced no token (denied / error). Distinct from "nothing arrived". */
    private static final String DENIED = "denied:no-token";

    private final Activity activity;

    public AndroidItchAuth(Activity activity) {
        this.activity = activity;
    }

    @Override
    public String authorize() {
        final String redirectUri = "http://127.0.0.1:" + REDIRECT_PORT + "/";
        final ArrayBlockingQueue<String> result = new ArrayBlockingQueue<>(1);

        ServerSocket listener;
        try {
            // Loopback-only bind: never exposed to the LAN, and it matches the registered
            // redirect_uri host. Backlog 2 so the fragment-bootstrap re-request (see below)
            // can never be refused while the first connection is still being handled.
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), REDIRECT_PORT), 2);
            listener.setSoTimeout(ACCEPT_TIMEOUT_MS);
        } catch (IOException e) {
            System.out.println("[Itch] Could not bind port " + REDIRECT_PORT + " for sign-in: " + e);
            toast("Couldn't start itch.io sign-in (port " + REDIRECT_PORT + " busy).");
            return null;
        }

        Thread serverThread = new Thread(() -> serveUntilToken(listener, result), "itch-oauth-listener");
        serverThread.setDaemon(true);
        serverThread.start();

        String authUrl = "https://itch.io/user/oauth"
                + "?client_id="    + enc(CLIENT_ID)
                + "&scope="        + enc(SCOPE)
                + "&response_type=token"
                + "&redirect_uri=" + enc(redirectUri);

        if (!openBrowser(authUrl)) {
            System.out.println("[Itch] No browser available to complete sign-in.");
            toast("No browser available for itch.io sign-in.");
            closeQuietly(listener);
            return null;
        }

        try {
            String token = result.poll(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (token == null) {
                System.out.println("[Itch] Authorization timed out after " + AUTH_TIMEOUT_SECONDS + "s.");
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
        } finally {
            // One-shot by design — the listener never outlives the sign-in attempt, including
            // on the timeout path, where accept() is still blocked and must be broken out of.
            closeQuietly(listener);
        }
    }

    /**
     * Accept connections until one carries an {@code access_token} (or an explicit error).
     *
     * <p>Two hits are expected: itch returns the token in the URL <i>fragment</i>, which no
     * browser ever sends to a server, so the first response is a page whose JS copies the
     * fragment into the query string and re-requests. The second hit carries the token.
     */
    private static void serveUntilToken(ServerSocket listener, ArrayBlockingQueue<String> result) {
        try {
            while (!listener.isClosed()) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(10_000);
                    String path = readRequestTarget(socket);
                    if (path == null) continue;

                    String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : null;
                    String token = paramValue(query, "access_token");
                    String error = paramValue(query, "error");

                    if (token == null && error == null) {
                        respond(socket,
                                "<p>Signing you in with itch.io…</p>"
                              + "<script>"
                              + "var f=location.hash.slice(1);"
                              + "location.replace(location.pathname+'?'+(f||'error=no_fragment'));"
                              + "</script>");
                        continue;  // wait for the JS-driven second request
                    }

                    if (token == null || token.isEmpty()) {
                        respond(socket, "<h2>Sign-in wasn't completed.</h2>"
                                + "<p>" + describe(error) + "</p>"
                                + "<p>Return to the game and try again.</p>");
                        result.offer(DENIED);
                        return;
                    }

                    respond(socket, "<h2>You're all set.</h2>"
                            + "<p>Return to the game — you can close this tab.</p>");
                    result.offer(token);
                    return;
                } catch (IOException perConnection) {
                    // A single dropped/garbled connection shouldn't end the flow; keep waiting
                    // until the socket timeout or an explicit close ends it.
                    if (listener.isClosed()) return;
                }
            }
        } catch (Exception e) {
            System.out.println("[Itch] Sign-in listener stopped: " + e);
        }
    }

    /** Read the request line and return its target, e.g. {@code /?access_token=...}. */
    private static String readRequestTarget(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = in.readLine();
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        // "GET /?access_token=... HTTP/1.1"
        return parts.length >= 2 ? parts[1] : null;
    }

    private static void respond(Socket socket, String bodyHtml) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Michi's Adventure</title>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding-top:3em\">"
                + bodyHtml + "</body>").getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String describe(String error) {
        if ("access_denied".equals(error)) return "You declined the sign-in request.";
        if ("no_fragment".equals(error)) return "The sign-in response came back empty.";
        return "itch.io reported: " + safeText(error);
    }

    /** Escape a redirect-supplied value before echoing it into the response page. */
    private static String safeText(String s) {
        if (s == null || s.isEmpty()) return "the sign-in was not completed";
        String trimmed = s.length() > 100 ? s.substring(0, 100) : s;
        return trimmed.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                      .replace("\"", "&quot;");
    }

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

    private static void closeQuietly(ServerSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {}
    }

    /** Pull one parameter out of a raw {@code a=1&b=2} query string. */
    private static String paramValue(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !key.equals(pair.substring(0, eq))) continue;
            try {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
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

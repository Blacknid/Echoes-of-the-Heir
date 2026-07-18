package util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bounded reads from untrusted network peers.
 *
 * <p>Every socket the game opens (save server, multiplayer server, license server) points at a
 * host the player can freely type or that ships in an editable {@code save_servers.txt}. A
 * malicious or spoofed peer answering on that port must not be able to crash the client, and
 * {@code BufferedReader.readLine()} happily buffers an endless line until the JVM dies with an
 * {@code OutOfMemoryError}. These helpers put a hard ceiling on how much a single line or a
 * decompressed payload may grow; past it the connection is treated as hostile and dropped.
 */
public final class NetIO {

    private NetIO() {}

    /** Generous ceiling for a protocol DATA frame (base64 of a ~10 MB max server payload). */
    public static final int MAX_FRAME_CHARS = 16 * 1024 * 1024;
    /** Handshake/status lines (HELLO/OK/AUTH_OK/PONG...) are tiny; anything huge is hostile. */
    public static final int MAX_HANDSHAKE_CHARS = 8 * 1024;

    /**
     * Like {@link BufferedReader#readLine()} but refuses to buffer more than {@code maxChars}.
     *
     * @return the line without its terminator, or null on EOF before any character.
     * @throws IOException if the peer sends a line longer than {@code maxChars}.
     */
    public static String readLineBounded(BufferedReader r, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 256));
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c == '\r') continue; // tolerate CRLF; a lone CR line ends at the following LF/EOF
            if (sb.length() >= maxChars) {
                throw new IOException("peer sent a line longer than " + maxChars + " chars — dropping connection");
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Read at most {@code maxBytes} from {@code in} (e.g. a GZIPInputStream over network data).
     *
     * @throws IOException if the stream yields more than {@code maxBytes} (decompression bomb).
     */
    public static byte[] readAllBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) {
                throw new IOException("payload expanded past " + maxBytes + " bytes — dropping it");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}

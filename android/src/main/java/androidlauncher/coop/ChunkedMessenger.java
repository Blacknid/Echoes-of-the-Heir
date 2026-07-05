package androidlauncher.coop;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Splits/reassembles whole UTF-8 messages across BLE's tiny per-packet payload. BLE's default ATT
 * MTU is 23 bytes (20 usable after the 3-byte header) and Android never negotiates a bigger one
 * automatically — a boss-co-op JSON message (especially a {@code START} with a full loadout, which
 * can easily be several hundred bytes once inventory items are listed) will never fit in one
 * write/notify, so every message is framed as:
 *
 * <pre>
 *   [4-byte big-endian total length][payload bytes, chunked across as many writes/notifies as needed]
 * </pre>
 *
 * <p>The length prefix rides in the FIRST chunk only; every instance here is single-message-in-
 * flight (this class's caller only ever awaits one complete message before parsing/acting on it,
 * matching how {@code coop.BossCoopHostServer}/{@code BossCoopClient} process one line at a time),
 * so there's no need for a message-boundary marker beyond "we've received {@code totalLength} bytes".
 */
final class ChunkedMessenger {

    // Conservative default assuming no MTU negotiation succeeded — see BleBossCoop's note on MTU.
    private static final int CHUNK_SIZE = 20;
    private static final int LENGTH_PREFIX_BYTES = 4;

    private final Consumer<byte[]> rawSender;

    private ByteArrayOutputStream incoming;
    private int expectedLength = -1;

    ChunkedMessenger(Consumer<byte[]> rawSender) {
        this.rawSender = rawSender;
    }

    /** Splits {@code message} into MTU-safe chunks and sends each one, in order, via {@code rawSender}. */
    void send(String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[LENGTH_PREFIX_BYTES + payload.length];
        framed[0] = (byte) (payload.length >>> 24);
        framed[1] = (byte) (payload.length >>> 16);
        framed[2] = (byte) (payload.length >>> 8);
        framed[3] = (byte) (payload.length);
        System.arraycopy(payload, 0, framed, LENGTH_PREFIX_BYTES, payload.length);

        for (int offset = 0; offset < framed.length; offset += CHUNK_SIZE) {
            int end = Math.min(offset + CHUNK_SIZE, framed.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(framed, offset, chunk, 0, chunk.length);
            rawSender.accept(chunk);
        }
    }

    /**
     * Feed one received chunk. Returns the fully reassembled message once the last chunk of a
     * frame arrives, or {@code null} while a message is still incomplete.
     */
    String receive(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return null;

        int chunkOffset = 0;
        if (incoming == null) {
            // Start of a new frame — first LENGTH_PREFIX_BYTES bytes are the big-endian length.
            if (chunk.length < LENGTH_PREFIX_BYTES) return null; // malformed/too-small first chunk
            expectedLength = ((chunk[0] & 0xFF) << 24) | ((chunk[1] & 0xFF) << 16)
                    | ((chunk[2] & 0xFF) << 8) | (chunk[3] & 0xFF);
            incoming = new ByteArrayOutputStream(Math.max(expectedLength, 0));
            chunkOffset = LENGTH_PREFIX_BYTES;
        }
        if (chunkOffset < chunk.length) {
            incoming.write(chunk, chunkOffset, chunk.length - chunkOffset);
        }

        if (incoming.size() >= expectedLength) {
            String message = incoming.toString(StandardCharsets.UTF_8);
            incoming = null;
            expectedLength = -1;
            return message;
        }
        return null;
    }
}

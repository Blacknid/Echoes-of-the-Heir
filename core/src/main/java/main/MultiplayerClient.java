package main;

import entity.DamageNumber;
import entity.Entity;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Multiplayer-server client v2.
 *
 * <p>Uses the same security model as the cloud-save service:
 *  <ul>
 *    <li>RSA-OAEP-SHA256 license-bound handshake</li>
 *    <li>AES-256-GCM authenticated encryption per frame</li>
 *    <li>HKDF-SHA256 delivery + session keys</li>
 *    <li>Per-direction sequence counters bound into AAD (anti-replay)</li>
 *    <li>Timestamp + nonce check against the server's replay cache</li>
 *  </ul>
 *
 * <p>The IP/port to connect to is supplied by the in-game UI
 * (Direct Connect or saved-server list); this class never hard-codes a host.
 */
public class MultiplayerClient {

    private static final String DEBUG_FALLBACK_HOST = "127.0.0.1";
    private static final int DEBUG_FALLBACK_PORT = 7777;

    // ── Embedded RSA public key (must match the MP server's private key) ─
    private static final String RSA_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuEwRNiBMB0MkhCI7+3Xs"
          + "fsZZWZMvk4WbgjZk7CBMUwyXSnY6vscwMcWIvlVj6BItyfNJP1PFUaaJgfoOFXl1"
          + "vTn1jKiCBdLC14NhLDeW2E7N1XWD3s4YhXWqc91soETgb2TRpYIqfkfiKwdsKwiH"
          + "XemA4XLgrZWHihbTWrPzb1TANd2a2tQWMDqq7QGMka07Yb2L5CBdaZFaX0iDUvcH"
          + "aN2dOguvkmYWrotrd1GVQtvF/bYIWR19QCKvfQZV++vxEUFhcZdjgXXSewN194VS"
          + "QzrMKiwVogXLUTVGBFt1mmF/fXUjzM8oY4SEKBCoiybHPbwbIJLZ43L54PjmlCRf"
          + "1wIDAQAB";

    private static final String PROTOCOL_TAG    = "v2";
    private static final byte[] LICENSE_PEPPER  = "michi-license-pepper-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIVERY_INFO   = "michi-delivery-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIVERY_AAD    = "MichiMpSession".getBytes(StandardCharsets.UTF_8);

    private static final byte DIR_S2C = 0x01;
    private static final byte DIR_C2S = 0x02;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final GamePanel gp;
    private Socket socket;
    private BufferedWriter out;
    private BufferedReader in;
    private byte[] sessionKey;

    private final AtomicLong sendSeq = new AtomicLong(0);
    private final AtomicLong recvSeq = new AtomicLong(0);

    private Thread receiveThread;
    private final AtomicBoolean connected  = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    public final ConcurrentHashMap<Integer, RemotePlayerState> remotePlayers = new ConcurrentHashMap<>();
    public int localId = -1;
    public String serverMessage = "";
    public String connectionStatus = "";

    public final MpMapStreamer mapStreamer;

    private int sendCounter = 0;
    private static final int SEND_INTERVAL = 3;

    public MultiplayerClient(GamePanel gp) {
        this.gp = gp;
        this.mapStreamer = new MpMapStreamer(gp, this);
    }

    public boolean isConnected()  { return connected.get(); }
    public boolean isConnecting() { return connecting.get(); }
    public boolean isWorldReady() { return mapStreamer.isWorldReady(); }

    /** Async connect.  Sends license-authenticated handshake before joining. */
    public void connect(String ip, int port, String playerName, String playerClass) {
        if (connected.get() || connecting.get()) return;
        connecting.set(true);
        connectionStatus = "Connecting to " + ip + ":" + port + "...";

        new Thread(() -> {
            try {
                String license = Main.LICENSE_KEY;
                if (license == null || license.isBlank()) {
                    connecting.set(false);
                    connectionStatus = "No valid license. Multiplayer requires a signed license.";
                    System.out.println("[MP Client] Missing license — aborting connect.");
                    return;
                }

                if (!connectAndHandshake(ip, port, license, playerName, playerClass)) {
                    connecting.set(false);
                    connectionStatus = "Handshake failed (auth rejected or bad protocol).";
                    closeQuietly();
                    return;
                }

                connected.set(true);
                connecting.set(false);
                connectionStatus = "Connected!";

                receiveThread = new Thread(this::receiveLoop, "MP-Receive");
                receiveThread.setDaemon(true);
                receiveThread.start();

                Thread keepalive = new Thread(this::keepaliveLoop, "MP-Keepalive");
                keepalive.setDaemon(true);
                keepalive.start();

            } catch (Exception e) {
                connecting.set(false);
                connected.set(false);
                connectionStatus = "Failed: " + e.getMessage();
                System.out.println("[MP Client] Connection failed: " + e.getMessage());
                closeQuietly();
            }
        }, "MP-Connect").start();
    }

    private boolean connectAndHandshake(String ip, int port, String license, String playerName, String playerClass) throws IOException {
        try {
            openSocket(ip, port);
            return performHandshake(license, playerName, playerClass);
        } catch (IOException first) {
            closeQuietly();
            if (!Main.DEBUG_MODE || isLocalhost(ip)) {
                throw first;
            }
            System.out.println("[MP Client] " + ip + ":" + port + " unreachable (" + first.getMessage()
                    + "), retrying " + DEBUG_FALLBACK_HOST + ":" + DEBUG_FALLBACK_PORT + " because DEBUG_MODE is enabled.");
            connectionStatus = "Retrying local debug server...";
            openSocket(DEBUG_FALLBACK_HOST, DEBUG_FALLBACK_PORT);
            return performHandshake(license, playerName, playerClass);
        }
    }

    private void openSocket(String ip, int port) throws IOException {
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(ip, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(),  StandardCharsets.UTF_8));
    }

    private boolean isLocalhost(String ip) {
        String host = ip == null ? "" : ip.trim().toLowerCase();
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    public void disconnect() {
        connected.set(false);
        connecting.set(false);
        remotePlayers.clear();
        localId = -1;
        connectionStatus = "";
        sessionKey = null;
        sendSeq.set(0);
        recvSeq.set(0);
        if (mapStreamer != null) mapStreamer.reset();
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException ignored) {}
        socket = null;
    }

    /** Called every game tick. */
    public void update() {
        if (!connected.get()) return;
        sendCounter++;
        if (sendCounter >= SEND_INTERVAL) {
            sendCounter = 0;
            sendPlayerState();
        }
    }

    private void sendPlayerState() {
        try {
            var p = gp.player;
            // Server uses originalTileSize (32px) coordinates; client uses scaled
            // tileSize (64px at scale=2). Divide before sending so the server's
            // collision / anti-teleport logic works in the correct pixel space.
            int coordScale = gp.tileSize / gp.originalTileSize;
            int sx = p.worldX / coordScale;
            int sy = p.worldY / coordScale;
            String msg = "{\"type\":\"move\","
                    + "\"x\":"        + sx          + ","
                    + "\"y\":"        + sy          + ","
                    + "\"dir\":"      + p.direction + ","
                    + "\"sprite\":"   + p.spriteNum + ","
                    + "\"attacking\":" + p.attacking
                    + "}";
            sendEncrypted(msg);
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending state: " + e.getMessage());
            disconnect();
        }
    }

    public void sendChat(String message) {
        if (!connected.get()) return;
        try {
            sendEncrypted("{\"type\":\"chat\",\"msg\":\"" + jsonEscape(message) + "\"}");
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending chat: " + e.getMessage());
        }
    }

    /** Request a single map chunk from the server. Called by MpMapStreamer. */
    public void sendChunkRequest(int layerIdx, int cx, int cy) {
        if (!connected.get()) return;
        try {
            sendEncrypted("{\"type\":\"chunk_request\",\"layer_idx\":" + layerIdx
                    + ",\"cx\":" + cx + ",\"cy\":" + cy + "}");
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending chunk_request: " + e.getMessage());
        }
    }

    /** Inform the server the client has finished applying every chunk. */
    public void sendWorldReady() {
        if (!connected.get()) return;
        try {
            sendEncrypted("{\"type\":\"world_ready\"}");
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending world_ready: " + e.getMessage());
        }
    }

    // =====================================================================
    //  HANDSHAKE
    // =====================================================================

    private boolean performHandshake(String license, String name, String cls) {
        try {
            // Step 1: HELLO
            byte[] clientNonce = new byte[16];
            SECURE_RANDOM.nextBytes(clientNonce);
            sendLine("HELLO " + PROTOCOL_TAG + " " + Base64.getEncoder().encodeToString(clientNonce));

            // Step 2: server nonce
            String okLine = readLine();
            if (okLine == null || !okLine.startsWith("OK ")) {
                if ("RATE_LIMIT".equals(okLine))      connectionStatus = "Rate-limited by server.";
                else if ("SERVER_FULL".equals(okLine)) connectionStatus = "Server is full.";
                else if ("BANNED".equals(okLine))      connectionStatus = "You are banned from this server.";
                else if ("USERNAME_TAKEN".equals(okLine)) connectionStatus = "Username is already taken. Change it in the main menu (press U).";
                return false;
            }
            byte[] serverNonce = Base64.getDecoder().decode(okLine.substring(3));
            if (serverNonce.length != 16) return false;

            // Step 3: LOGIN (RSA-OAEP) — the server resolves our license via activation_id/enc_blob
            // (issued once by platform.LicenseActivation's online ACTIVATE against save_server),
            // so the handshake JSON carries no license field at all:
            // "LOGIN <enc_b64> <activation_id> <enc_blob_b64>"
            String activationId = platform.License.getActivationId();
            String encBlob = platform.License.getEncBlob();
            String handshakeJson = "{"
                    + "\"ts\":"             + (System.currentTimeMillis() / 1000L) + ","
                    + "\"client_nonce\":\"" + toHex(clientNonce)              + "\","
                    + "\"server_nonce\":\"" + toHex(serverNonce)              + "\","
                    + "\"name\":\""         + jsonEscape(name)                + "\","
                    + "\"class\":\""        + jsonEscape(cls)                 + "\""
                    + "}";
            byte[] enc = rsaOaepEncrypt(handshakeJson.getBytes(StandardCharsets.UTF_8));
            sendLine("LOGIN " + Base64.getEncoder().encodeToString(enc)
                    + " " + (activationId != null ? activationId : "")
                    + " " + (encBlob != null ? encBlob : ""));

            // Step 4: AUTH_OK + encrypted session key
            String authLine = readLine();
            if (authLine == null || !authLine.startsWith("AUTH_OK ")) {
                if ("BANNED".equals(authLine))
                    connectionStatus = "You are banned from this server.";
                else if ("USERNAME_TAKEN".equals(authLine))
                    connectionStatus = "Username \"" + name + "\" is already taken. Change it in the main menu (press U).";
                else if ("AUTH_FAIL".equals(authLine))
                    connectionStatus = "Authentication failed.";
                return false;
            }
            byte[] encSession = Base64.getDecoder().decode(authLine.substring(8));

            byte[] deliveryKey = hkdf(
                    concat(license.getBytes(StandardCharsets.UTF_8), LICENSE_PEPPER),
                    serverNonce, DELIVERY_INFO, 32);
            byte[] nonceForDelivery = Arrays.copyOfRange(clientNonce, 0, 12);
            byte[] sk = aesGcmDecrypt(encSession, deliveryKey, nonceForDelivery, DELIVERY_AAD);
            if (sk.length != 32) return false;
            this.sessionKey = sk;

            return true;
        } catch (Exception e) {
            System.out.println("[MP Client] Handshake failed: " + e.getMessage());
            return false;
        }
    }

    // =====================================================================
    //  ENCRYPTED FRAMING
    // =====================================================================

    private synchronized void sendEncrypted(String json) throws IOException, GeneralSecurityException {
        if (sessionKey == null || out == null) return;
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[12];
        SECURE_RANDOM.nextBytes(nonce);
        long seq = sendSeq.getAndIncrement();
        byte[] seqBytes = longBE(seq);
        byte[] aad = new byte[]{ DIR_C2S, seqBytes[0], seqBytes[1], seqBytes[2], seqBytes[3],
                                 seqBytes[4], seqBytes[5], seqBytes[6], seqBytes[7] };
        byte[] ct = aesGcmEncrypt(plaintext, sessionKey, nonce, aad);
        byte[] frame = concat(seqBytes, nonce, ct);
        synchronized (out) {
            out.write("DATA " + Base64.getEncoder().encodeToString(frame));
            out.newLine();
            out.flush();
        }
    }

    /** @return decrypted JSON, or null on EOF. */
    private String recvEncrypted() throws IOException, GeneralSecurityException {
        String line = readLine();
        if (line == null) return null;
        if (!line.startsWith("DATA ")) {
            // Server may send plaintext error frames before/after handshake
            if (line.equals("AUTH_FAIL") || line.equals("RATE_LIMIT")
                    || line.equals("SERVER_FULL") || line.equals("BUSY")) {
                throw new IOException("server error: " + line);
            }
            throw new IOException("expected DATA frame, got: " + truncate(line, 32));
        }
        byte[] frame = Base64.getDecoder().decode(line.substring(5));
        if (frame.length < 8 + 12 + 16) throw new IOException("frame too short");
        long seq = readLongBE(frame, 0);
        long expected = recvSeq.getAndIncrement();
        if (seq != expected)
            throw new IOException("server seq mismatch (got " + seq + ", expected " + expected + ")");
        byte[] nonce = Arrays.copyOfRange(frame, 8, 20);
        byte[] ct    = Arrays.copyOfRange(frame, 20, frame.length);
        byte[] aad   = new byte[]{ DIR_S2C, frame[0], frame[1], frame[2], frame[3],
                                   frame[4], frame[5], frame[6], frame[7] };
        byte[] plaintext = aesGcmDecrypt(ct, sessionKey, nonce, aad);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private void sendLine(String text) throws IOException {
        synchronized (out) {
            out.write(text);
            out.newLine();
            out.flush();
        }
    }

    private String readLine() throws IOException {
        return in.readLine();
    }

    // =====================================================================
    //  RECEIVE / KEEPALIVE LOOPS
    // =====================================================================

    private void receiveLoop() {
        try {
            while (connected.get()) {
                String json;
                try {
                    json = recvEncrypted();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (json == null) break;
                handleMessage(json.trim());
            }
        } catch (Exception e) {
            if (connected.get()) {
                System.out.println("[MP Client] Receive error: " + e.getMessage());
            }
        } finally {
            connected.set(false);
            connectionStatus = "Disconnected";
        }
    }

    private void keepaliveLoop() {
        while (connected.get()) {
            try {
                Thread.sleep(3000);
                if (connected.get()) {
                    sendEncrypted("{\"type\":\"ping\"}");
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    // =====================================================================
    //  APPLICATION MESSAGES (parsed plaintext JSON)
    // =====================================================================

    private void handleMessage(String json) {
        String type = extractString(json, "type");
        if (type == null) return;

        switch (type) {
            case "welcome" -> {
                localId = extractInt(json, "id", -1);
                connectionStatus = "Connected (ID: " + localId + ")";
                int sx = extractInt(json, "spawn_x", -1);
                int sy = extractInt(json, "spawn_y", -1);
                // Store the authoritative spawn; finishWorldLoad() applies it AFTER
                // all chunks arrive so applyWorldInfo() cannot overwrite it.
                mapStreamer.welcomeSpawnX = sx;
                mapStreamer.welcomeSpawnY = sy;
                System.out.println("[MP Client] Welcome! Local ID = " + localId
                        + "  spawn=(" + sx + "," + sy + ")");
                parsePlayerList(json);
            }
            case "world_info" -> handleWorldInfo(json);
            case "chunk"      -> handleChunk(json);
            case "pos_correction" -> {
                int coordScale = gp.tileSize / gp.originalTileSize;
                int cx = extractInt(json, "x", gp.player.worldX / coordScale) * coordScale;
                int cy = extractInt(json, "y", gp.player.worldY / coordScale) * coordScale;
                String reason = extractString(json, "reason");
                mapStreamer.applyPositionCorrection(cx, cy, reason);
            }
            case "trigger" -> handleTrigger(json);
            case "map_change" -> {
                String newMap = extractString(json, "map_id");
                connectionStatus = "Server requested map change to '" + newMap
                        + "' (cross-map transitions require reconnect).";
                System.out.println("[MP Client] map_change -> " + newMap);
                disconnect();
            }
            case "player_join" -> {
                int id = extractInt(json, "id", -1);
                String name = extractString(json, "name");
                String cls  = extractString(json, "class");
                if (id >= 0 && id != localId) {
                    RemotePlayerState rp = remotePlayers.getOrDefault(id, new RemotePlayerState());
                    rp.name        = name != null ? name : "Player";
                    rp.playerClass = cls  != null ? cls  : "Fighter";
                    int coordScale = gp.tileSize / gp.originalTileSize;
                    rp.worldX = extractInt(json, "x", 0) * coordScale;
                    rp.worldY = extractInt(json, "y", 0) * coordScale;
                    remotePlayers.put(id, rp);
                    gp.ui.addMessage(rp.name + " joined!", new gfx.Color(100, 220, 100));
                }
            }
            case "player_leave" -> {
                int id = extractInt(json, "id", -1);
                RemotePlayerState removed = remotePlayers.remove(id);
                if (removed != null) {
                    gp.ui.addMessage(removed.name + " left.", new gfx.Color(220, 100, 100));
                }
            }
            case "player_update" -> {
                int id = extractInt(json, "id", -1);
                if (id >= 0 && id != localId) {
                    RemotePlayerState rp = remotePlayers.get(id);
                    if (rp == null) {
                        // player_update arrived before player_join — create on the fly
                        rp = new RemotePlayerState();
                        remotePlayers.put(id, rp);
                    }
                    int coordScale = gp.tileSize / gp.originalTileSize;
                    int newX = extractInt(json, "x", 0) * coordScale;
                    int newY = extractInt(json, "y", 0) * coordScale;

                    // Server velocity is in px/server-tick (at 32px tile space); scale to client space.
                    float rawVx = (float) extractDouble(json, "vx", 0.0) * coordScale;
                    float rawVy = (float) extractDouble(json, "vy", 0.0) * coordScale;

                    long nowNs = System.nanoTime();
                    // Expected duration of the next segment matches the server broadcast interval.
                    long segDurNs = 1_000_000_000L / 20; // 50 ms in nanoseconds

                    if (!rp.spReady) {
                        // First update: snap to position, no spline yet.
                        rp.spPsX = newX; rp.spPsY = newY;
                        rp.spPeX = newX; rp.spPeY = newY;
                        rp.spVsX = 0f;   rp.spVsY = 0f;
                        rp.spVeX = rawVx / segDurNs; rp.spVeY = rawVy / segDurNs;
                        rp.spStartNs = nowNs;
                        rp.spDurationNs = segDurNs;
                        rp.spReady = true;
                    } else {
                        // Sample current visual position and velocity to use as the new start.
                        float[] pos = rp.evalSpline(nowNs);
                        // Derivative of Hermite at current t: dP/dt / duration.
                        float curVxPxNs = hermiteVelocity(rp, nowNs);
                        float curVyPxNs = hermiteVelocityY(rp, nowNs);

                        rp.spPsX = pos[0];          rp.spPsY = pos[1];
                        rp.spVsX = curVxPxNs;       rp.spVsY = curVyPxNs;
                        rp.spPeX = newX;             rp.spPeY = newY;
                        rp.spVeX = rawVx / segDurNs; rp.spVeY = rawVy / segDurNs;
                        rp.spStartNs = nowNs;
                        rp.spDurationNs = segDurNs;
                    }

                    rp.worldX     = newX;
                    rp.worldY     = newY;
                    rp.direction  = extractInt(json, "dir",     rp.direction);
                    rp.spriteNum  = extractInt(json, "sprite",  rp.spriteNum);
                    rp.attacking  = extractBool(json, "attacking");
                    rp.life       = extractInt(json, "life",    rp.life);
                    rp.maxLife    = extractInt(json, "maxLife", rp.maxLife);
                }
            }
            case "server_full" -> {
                connectionStatus = "Server is full!";
                disconnect();
            }
            case "chat" -> {
                String from = extractString(json, "from");
                String msg  = extractString(json, "msg");
                if (from != null && msg != null) {
                    gp.ui.addMessage(from + ": " + msg, new gfx.Color(200, 200, 255));
                }
            }
            case "chat_throttled" -> { /* silently dropped — UI optional */ }
            case "pong" -> { /* keepalive */ }
            case "kick" -> {
                String reason = extractString(json, "reason");
                connectionStatus = "Kicked: " + (reason != null ? reason : "No reason");
                disconnect();
            }
            case "player_stats" -> {
                var p = gp.player;
                p.level         = extractInt(json, "level",        p.level);
                p.maxLife       = extractInt(json, "maxLife",      p.maxLife);
                p.life          = extractInt(json, "life",         p.life);
                p.strenght      = extractInt(json, "strength",     p.strenght);
                p.dexterity     = extractInt(json, "dexterity",    p.dexterity);
                p.maxMana       = extractInt(json, "maxMana",      p.maxMana);
                p.mana          = extractInt(json, "mana",         p.mana);
                p.exp           = extractInt(json, "exp",          p.exp);
                p.nextLevelExp  = extractInt(json, "nextLevelExp", p.nextLevelExp);
                p.coin          = extractInt(json, "coin",         p.coin);
                p.skillPoints   = extractInt(json, "skillPoints",  p.skillPoints);
                p.attack        = p.getAttack();
                p.defense       = p.getDefense();
                System.out.println("[MP Client] Server stats applied: level=" + p.level
                        + " life=" + p.life + "/" + p.maxLife);
            }
            case "mob_damage" -> handleMobDamage(json);
            case "mob_death" -> handleMobDeath(json);
            default -> { /* ignore unknown */ }
        }
    }

    // ── World streaming message decoders ──
    private void handleWorldInfo(String json) {
        try {
            MpMapStreamer.WorldInfo info = new MpMapStreamer.WorldInfo();
            info.mapId      = stringOr(extractString(json, "map_id"), "");
            info.width      = extractInt(json, "width", 0);
            info.height     = extractInt(json, "height", 0);
            info.tilewidth  = extractInt(json, "tilewidth", 32);
            info.tileheight = extractInt(json, "tileheight", 32);
            info.chunkSize  = extractInt(json, "chunk_size", 32);
            info.chunksX    = extractInt(json, "chunks_x", 0);
            info.chunksY    = extractInt(json, "chunks_y", 0);
            int[] spawn = extractIntPair(json, "default_spawn");
            info.spawnCol   = spawn != null ? spawn[0] : 0;
            info.spawnRow   = spawn != null ? spawn[1] : 0;
            info.layerNames = extractLayerNames(json);

            String skeletonB64 = extractString(json, "skeleton_xml_b64");
            if (skeletonB64 == null) {
                System.out.println("[MP Client] world_info missing skeleton_xml_b64");
                return;
            }
            info.skeletonXmlBytes = Base64.getDecoder().decode(skeletonB64);
            mapStreamer.applyWorldInfo(info);
        } catch (Exception e) {
            System.out.println("[MP Client] handleWorldInfo error: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }

    private void handleChunk(String json) {
        try {
            MpMapStreamer.ChunkPacket ch = new MpMapStreamer.ChunkPacket();
            ch.layerIdx   = extractInt(json, "layer_idx", -1);
            ch.layerName  = stringOr(extractString(json, "layer"), "");
            ch.cx         = extractInt(json, "cx", -1);
            ch.cy         = extractInt(json, "cy", -1);
            ch.w          = extractInt(json, "w", 0);
            ch.h          = extractInt(json, "h", 0);
            ch.dataBase64 = extractString(json, "data");
            if (ch.layerIdx < 0 || ch.cx < 0 || ch.cy < 0 || ch.dataBase64 == null) return;
            mapStreamer.applyChunk(ch);
        } catch (Exception e) {
            System.out.println("[MP Client] handleChunk error: " + e.getMessage());
        }
    }

    private void handleTrigger(String json) {
        try {
            MpMapStreamer.TriggerPacket t = new MpMapStreamer.TriggerPacket();
            t.id          = extractInt(json, "id", 0);
            t.name        = stringOr(extractString(json, "name"), "");
            t.triggerType = stringOr(extractString(json, "trigger_type"), "");
            t.x = extractDouble(json, "x", 0);
            t.y = extractDouble(json, "y", 0);
            t.w = extractDouble(json, "w", 0);
            t.h = extractDouble(json, "h", 0);
            t.rawJson     = json;
            mapStreamer.applyTrigger(t);
        } catch (Exception e) {
            System.out.println("[MP Client] handleTrigger error: " + e.getMessage());
        }
    }

    private static String stringOr(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    /**
     * Parse a JSON array of two ints, e.g. {@code "default_spawn":[24,15]}.
     * Returns {@code null} if the key is missing or malformed.
     */
    private static int[] extractIntPair(String json, String key) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return null;
        int start = json.indexOf('[', i);
        int end   = json.indexOf(']', start);
        if (start < 0 || end < 0) return null;
        String[] parts = json.substring(start + 1, end).split(",");
        if (parts.length < 2) return null;
        try {
            return new int[]{ Integer.parseInt(parts[0].trim()),
                              Integer.parseInt(parts[1].trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Extract the {@code layers} array of objects, returning each layer's {@code name}. */
    private static java.util.List<String> extractLayerNames(String json) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int idx = json.indexOf("\"layers\"");
        if (idx < 0) return out;
        int arrStart = json.indexOf('[', idx);
        int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return out;
        String arr = json.substring(arrStart + 1, arrEnd);
        for (String entry : arr.split("\\},\\s*\\{")) {
            String wrapped = "{" + entry.replace("{", "").replace("}", "") + "}";
            String name = extractString(wrapped, "name");
            out.add(name != null ? name : "");
        }
        return out;
    }

    private static double extractDouble(String json, String key, double fallback) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return fallback;
        int start = i + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e' || c == 'E' || c == '+') end++;
            else break;
        }
        if (end == start) return fallback;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void parsePlayerList(String json) {
        int idx = json.indexOf("\"players\"");
        if (idx < 0) return;
        int arrStart = json.indexOf('[', idx);
        int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return;

        String arr = json.substring(arrStart + 1, arrEnd);
        String[] entries = arr.split("\\},\\s*\\{");
        for (String entry : entries) {
            entry = entry.replace("{", "").replace("}", "").trim();
            if (entry.isEmpty()) continue;
            String wrapped = "{" + entry + "}";
            int id = extractInt(wrapped, "id", -1);
            if (id >= 0 && id != localId) {
                RemotePlayerState rp = new RemotePlayerState();
                rp.name        = extractString(wrapped, "name");
                if (rp.name == null) rp.name = "Player";
                rp.playerClass = extractString(wrapped, "class");
                if (rp.playerClass == null) rp.playerClass = "Fighter";
                int coordScale = gp.tileSize / gp.originalTileSize;
                rp.worldX    = extractInt(wrapped, "x", 0) * coordScale;
                rp.worldY    = extractInt(wrapped, "y", 0) * coordScale;
                rp.direction = extractInt(wrapped, "dir", 0);
                remotePlayers.put(id, rp);
            }
        }
    }

    // =====================================================================
    //  CRYPTO PRIMITIVES
    // =====================================================================

    private static PublicKey loadRSAPublicKey() throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(RSA_PUBLIC_KEY_B64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] rsaOaepEncrypt(byte[] plain) throws GeneralSecurityException {
        OAEPParameterSpec spec = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.ENCRYPT_MODE, loadRSAPublicKey(), spec);
        return c.doFinal(plain);
    }

    private static byte[] aesGcmEncrypt(byte[] plain, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(plain);
    }

    private static byte[] aesGcmDecrypt(byte[] ct, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(ct);
    }

    private static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        if (salt == null) salt = new byte[hmac.getMacLength()];
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(secret);

        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            hmac.reset();
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(t);
            if (info != null) hmac.update(info);
            hmac.update(counter);
            t = hmac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
            counter++;
        }
        return out;
    }

    // =====================================================================
    //  SMALL UTILITIES
    // =====================================================================

    private static byte[] longBE(long v) {
        return new byte[]{
                (byte)(v >>> 56), (byte)(v >>> 48), (byte)(v >>> 40), (byte)(v >>> 32),
                (byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8),  (byte) v
        };
    }
    private static long readLongBE(byte[] b, int off) {
        return  ((long)(b[off]   & 0xff) << 56)
              | ((long)(b[off+1] & 0xff) << 48)
              | ((long)(b[off+2] & 0xff) << 40)
              | ((long)(b[off+3] & 0xff) << 32)
              | ((long)(b[off+4] & 0xff) << 24)
              | ((long)(b[off+5] & 0xff) << 16)
              | ((long)(b[off+6] & 0xff) << 8)
              | ((long)(b[off+7] & 0xff));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }

    // ── Hermite spline derivative helpers ──

    /**
     * Returns the X component of the spline's instantaneous velocity at {@code nowNs},
     * in pixels per nanosecond — the same units as {@code spVsX}/{@code spVeX}.
     * Formula: dP/dt = (6t²-6t)Ps + (3t²-4t+1)Vs + (-6t²+6t)Pe + (3t²-2t)Ve,
     * then divide by duration to convert from px/segment → px/ns.
     */
    private static float hermiteVelocity(RemotePlayerState rp, long nowNs) {
        if (!rp.spReady || rp.spDurationNs <= 0) return 0f;
        float t = Math.min(1.3f, (float)(nowNs - rp.spStartNs) / rp.spDurationNs);
        float t2 = t * t;
        float dh00 = 6*t2 - 6*t;
        float dh10 = 3*t2 - 4*t + 1;
        float dh01 = -6*t2 + 6*t;
        float dh11 = 3*t2 - 2*t;
        float vsX = rp.spVsX * rp.spDurationNs;
        float veX = rp.spVeX * rp.spDurationNs;
        float dpdt = dh00 * rp.spPsX + dh10 * vsX + dh01 * rp.spPeX + dh11 * veX;
        return dpdt / rp.spDurationNs;
    }

    private static float hermiteVelocityY(RemotePlayerState rp, long nowNs) {
        if (!rp.spReady || rp.spDurationNs <= 0) return 0f;
        float t = Math.min(1.3f, (float)(nowNs - rp.spStartNs) / rp.spDurationNs);
        float t2 = t * t;
        float dh00 = 6*t2 - 6*t;
        float dh10 = 3*t2 - 4*t + 1;
        float dh01 = -6*t2 + 6*t;
        float dh11 = 3*t2 - 2*t;
        float vsY = rp.spVsY * rp.spDurationNs;
        float veY = rp.spVeY * rp.spDurationNs;
        float dpdt = dh00 * rp.spPsY + dh10 * vsY + dh01 * rp.spPeY + dh11 * veY;
        return dpdt / rp.spDurationNs;
    }

    // ── Simple JSON helpers for inbound application messages ──

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int i = json.indexOf(search);
        if (i < 0) return null;
        int start = i + search.length();
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int p = start; p < json.length(); p++) {
            char c = json.charAt(p);
            if (escape) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static int extractInt(String json, String key, int fallback) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return fallback;
        int start = i + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean extractBool(String json, String key) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return false;
        int start = i + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        return json.startsWith("true", start);
    }

    // =====================================================================
    //  MOB SYNCHRONIZATION (multiplayer damage/death sync)
    // =====================================================================

    /** Send mob damage to the server for synchronization with other players. */
    public void sendMobDamage(int mobId, int damage, int currentLife, int maxLife, String mobType) {
        if (!connected.get()) return;
        try {
            String msg = "{\"type\":\"mob_damage\","
                    + "\"mob_id\":" + mobId + ","
                    + "\"damage\":" + damage + ","
                    + "\"life\":" + currentLife + ","
                    + "\"max_life\":" + maxLife + ","
                    + "\"mob_type\":\"" + jsonEscape(mobType) + "\""
                    + "}";
            sendEncrypted(msg);
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending mob_damage: " + e.getMessage());
        }
    }

    /** Send mob death to the server for synchronization with other players. */
    public void sendMobDeath(int mobId, String mobType) {
        if (!connected.get()) return;
        try {
            String msg = "{\"type\":\"mob_death\","
                    + "\"mob_id\":" + mobId + ","
                    + "\"mob_type\":\"" + jsonEscape(mobType) + "\""
                    + "}";
            sendEncrypted(msg);
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending mob_death: " + e.getMessage());
        }
    }

    /** Handle incoming mob damage from another player via the server. */
    private void handleMobDamage(String json) {
        try {
            int mobId = extractInt(json, "mob_id", -1);
            int life = extractInt(json, "life", -1);
            int damage = extractInt(json, "damage", 0);
            int attackerPid = extractInt(json, "attacker_pid", -1);

            if (mobId < 0 || mobId >= gp.monster.length) return;
            Entity mob = gp.monster[mobId];
            if (mob == null || !mob.alive) return;

            // Don't apply damage from ourselves (already applied locally)
            if (attackerPid == localId) return;

            // Apply synchronized damage
            mob.life = life;
            mob.hitFlashCounter = 6;
            mob.damageReaction();

            // Show damage number
            if (damage > 0) {
                DamageNumber dn = gp.damageNumberPool.get();
                int x = mob.worldX + gp.tileSize / 4;
                int y = mob.worldY - 8;
                dn.set(x, y, String.valueOf(damage), new gfx.Color(255, 80, 60), false);
                gp.damageNumbers.add(dn);
            }

            System.out.println("[MP] Mob " + mobId + " took " + damage + " damage from player " + attackerPid);
        } catch (Exception e) {
            System.out.println("[MP Client] handleMobDamage error: " + e.getMessage());
        }
    }

    /** Handle incoming mob death from another player via the server. */
    private void handleMobDeath(String json) {
        try {
            int mobId = extractInt(json, "mob_id", -1);
            int killerPid = extractInt(json, "killer_pid", -1);

            if (mobId < 0 || mobId >= gp.monster.length) return;
            Entity mob = gp.monster[mobId];
            if (mob == null) return;

            // If we're the killer, we already handled this locally
            if (killerPid == localId) return;

            // Only kill if not already dying/dead
            if (mob.alive && !mob.dying) {
                int coinDrop = Math.max(1, mob.exp / 2);
                mob.beginDeath(mob.exp, 1, coinDrop);
                System.out.println("[MP] Mob " + mobId + " killed by player " + killerPid);
            }
        } catch (Exception e) {
            System.out.println("[MP Client] handleMobDeath error: " + e.getMessage());
        }
    }

    /** Holds the state of a remote player for rendering. */
    public static class RemotePlayerState {
        public String name = "Player";
        public String playerClass = "Fighter";

        // Canonical world position (set from server; used as spline endpoint).
        public int worldX, worldY;

        // Cubic Hermite spline segment for smooth interpolation between server snapshots.
        // All positions in client pixel space; velocities in px per nanosecond.
        public float spPsX, spPsY;          // start position
        public float spPeX, spPeY;          // end position
        public float spVsX, spVsY;          // start velocity (px/ns)
        public float spVeX, spVeY;          // end velocity   (px/ns)
        public long  spStartNs;             // System.nanoTime() at segment start
        public long  spDurationNs;          // expected duration of this segment
        public boolean spReady = false;     // false until we have at least one update

        public int direction = 0;
        public int spriteNum = 1;
        public boolean attacking = false;
        public int life = 6, maxLife = 6;

        /**
         * Evaluates the cubic Hermite spline at the given render time and returns
         * the interpolated (x, y) in client pixel space as a two-element float array.
         *
         * P(t) = (2t³ - 3t² + 1)Ps + (t³ - 2t² + t)Vs + (-2t³ + 3t²)Pe + (t³ - t²)Ve
         *
         * Vs and Ve here are the tangents scaled to the segment duration
         * (velocity px/ns × durationNs = px per segment), so that t=1 lands
         * exactly on Pe with the correct exit slope.
         */
        public float[] evalSpline(long nowNs) {
            if (!spReady) return new float[]{ worldX, worldY };
            long elapsed = nowNs - spStartNs;
            // Clamp t to [0, 1.3] — allow slight overshoot rather than hard-snapping.
            float t = spDurationNs > 0 ? (float) elapsed / spDurationNs : 1f;
            if (t > 1.3f) t = 1.3f;

            // Scale velocity from px/ns to px/segment so the Hermite formula works correctly.
            float vsX = spVsX * spDurationNs;
            float vsY = spVsY * spDurationNs;
            float veX = spVeX * spDurationNs;
            float veY = spVeY * spDurationNs;

            float t2 = t * t;
            float t3 = t2 * t;
            float h00 = 2*t3 - 3*t2 + 1;
            float h10 = t3  - 2*t2  + t;
            float h01 = -2*t3 + 3*t2;
            float h11 = t3  - t2;

            return new float[]{
                h00 * spPsX + h10 * vsX + h01 * spPeX + h11 * veX,
                h00 * spPsY + h10 * vsY + h01 * spPeY + h11 * veY,
            };
        }
    }
}

package main;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP client for the multiplayer game server.
 * Protocol: newline-delimited JSON messages over TCP.
 */
public class MultiplayerClient {

    private final GamePanel gp;
    private Socket socket;
    private BufferedWriter out;
    private BufferedReader in;
    private Thread receiveThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // Remote player data: id -> state
    public final ConcurrentHashMap<Integer, RemotePlayerState> remotePlayers = new ConcurrentHashMap<>();
    public int localId = -1;
    public String serverMessage = "";

    // Send throttle: only send position every N frames
    private int sendCounter = 0;
    private static final int SEND_INTERVAL = 3; // every 3 ticks = 20 updates/sec at 60 UPS

    // Connection status
    public String connectionStatus = "";

    public MultiplayerClient(GamePanel gp) {
        this.gp = gp;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    /**
     * Connect to a multiplayer server asynchronously.
     */
    public void connect(String ip, int port, String playerName, String playerClass) {
        if (connected.get() || connecting.get()) return;
        connecting.set(true);
        connectionStatus = "Connecting to " + ip + ":" + port + "...";

        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(ip, port), 5000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(10000); // 10s read timeout for keepalive detection

                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                // Send join message
                sendMessage("{\"type\":\"join\",\"name\":\"" + escapeJson(playerName)
                        + "\",\"class\":\"" + escapeJson(playerClass) + "\"}");

                connected.set(true);
                connecting.set(false);
                connectionStatus = "Connected!";

                // Start receive loop
                receiveThread = new Thread(this::receiveLoop, "MP-Receive");
                receiveThread.setDaemon(true);
                receiveThread.start();

                // Start keepalive
                Thread keepalive = new Thread(this::keepaliveLoop, "MP-Keepalive");
                keepalive.setDaemon(true);
                keepalive.start();

            } catch (Exception e) {
                connecting.set(false);
                connected.set(false);
                connectionStatus = "Failed: " + e.getMessage();
                System.out.println("[MP Client] Connection failed: " + e.getMessage());
            }
        }, "MP-Connect").start();
    }

    public void disconnect() {
        connected.set(false);
        connecting.set(false);
        remotePlayers.clear();
        localId = -1;
        connectionStatus = "";
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        socket = null;
    }

    /**
     * Called every game tick from GamePanel.update().
     * Sends local player state to server.
     */
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
            String msg = "{\"type\":\"move\","
                    + "\"x\":" + p.worldX + ","
                    + "\"y\":" + p.worldY + ","
                    + "\"dir\":" + p.direction + ","
                    + "\"sprite\":" + p.spriteNum + ","
                    + "\"attacking\":" + p.attacking + ","
                    + "\"life\":" + p.life + ","
                    + "\"maxLife\":" + p.maxLife
                    + "}";
            sendMessage(msg);
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending state: " + e.getMessage());
            disconnect();
        }
    }

    public void sendChat(String message) {
        if (!connected.get()) return;
        try {
            sendMessage("{\"type\":\"chat\",\"msg\":\"" + escapeJson(message) + "\"}");
        } catch (Exception e) {
            System.out.println("[MP Client] Error sending chat: " + e.getMessage());
        }
    }

    private void sendMessage(String json) throws IOException {
        if (out == null) return;
        synchronized (out) {
            out.write(json);
            out.newLine();
            out.flush();
        }
    }

    private void receiveLoop() {
        try {
            while (connected.get()) {
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException e) {
                    // Timeout without data — server might be dead
                    continue;
                }
                if (line == null) {
                    // Server closed connection
                    break;
                }
                handleMessage(line.trim());
            }
        } catch (IOException e) {
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
                    sendMessage("{\"type\":\"ping\"}");
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    private void handleMessage(String json) {
        // Simple JSON parsing without external library
        String type = extractString(json, "type");
        if (type == null) return;

        switch (type) {
            case "welcome" -> {
                localId = extractInt(json, "id", -1);
                connectionStatus = "Connected (ID: " + localId + ")";
                System.out.println("[MP Client] Welcome! Local ID = " + localId);
                // Parse existing players
                parsePlayerList(json);
            }
            case "player_join" -> {
                int id = extractInt(json, "id", -1);
                String name = extractString(json, "name");
                String cls = extractString(json, "class");
                if (id >= 0 && id != localId) {
                    RemotePlayerState rp = new RemotePlayerState();
                    rp.name = name != null ? name : "Player";
                    rp.playerClass = cls != null ? cls : "Fighter";
                    remotePlayers.put(id, rp);
                    gp.ui.addMessage(rp.name + " joined!", new java.awt.Color(100, 220, 100));
                }
            }
            case "player_leave" -> {
                int id = extractInt(json, "id", -1);
                RemotePlayerState removed = remotePlayers.remove(id);
                if (removed != null) {
                    gp.ui.addMessage(removed.name + " left.", new java.awt.Color(220, 100, 100));
                }
            }
            case "player_update" -> {
                int id = extractInt(json, "id", -1);
                if (id >= 0 && id != localId) {
                    RemotePlayerState rp = remotePlayers.get(id);
                    if (rp != null) {
                        rp.worldX = extractInt(json, "x", rp.worldX);
                        rp.worldY = extractInt(json, "y", rp.worldY);
                        rp.direction = extractInt(json, "dir", rp.direction);
                        rp.spriteNum = extractInt(json, "sprite", rp.spriteNum);
                        rp.attacking = extractBool(json, "attacking");
                        rp.life = extractInt(json, "life", rp.life);
                        rp.maxLife = extractInt(json, "maxLife", rp.maxLife);
                    }
                }
            }
            case "server_full" -> {
                connectionStatus = "Server is full!";
                disconnect();
            }
            case "chat" -> {
                String from = extractString(json, "from");
                String msg = extractString(json, "msg");
                if (from != null && msg != null) {
                    gp.ui.addMessage(from + ": " + msg, new java.awt.Color(200, 200, 255));
                }
            }
            case "pong" -> { /* keepalive response, ignore */ }
            case "kick" -> {
                String reason = extractString(json, "reason");
                connectionStatus = "Kicked: " + (reason != null ? reason : "No reason");
                disconnect();
            }
        }
    }

    private void parsePlayerList(String json) {
        // Parse "players" array from welcome message
        // Format: "players":[{"id":1,"name":"X","class":"Y","x":0,"y":0,...},...]
        int idx = json.indexOf("\"players\"");
        if (idx < 0) return;
        int arrStart = json.indexOf('[', idx);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return;

        String arr = json.substring(arrStart + 1, arrEnd);
        // Split by },{ pattern
        String[] entries = arr.split("\\},\\s*\\{");
        for (String entry : entries) {
            entry = entry.replace("{", "").replace("}", "").trim();
            if (entry.isEmpty()) continue;
            String wrapped = "{" + entry + "}";
            int id = extractInt(wrapped, "id", -1);
            if (id >= 0 && id != localId) {
                RemotePlayerState rp = new RemotePlayerState();
                rp.name = extractString(wrapped, "name");
                if (rp.name == null) rp.name = "Player";
                rp.playerClass = extractString(wrapped, "class");
                if (rp.playerClass == null) rp.playerClass = "Fighter";
                rp.worldX = extractInt(wrapped, "x", 0);
                rp.worldY = extractInt(wrapped, "y", 0);
                rp.direction = extractInt(wrapped, "dir", 0);
                remotePlayers.put(id, rp);
            }
        }
    }

    // ── Simple JSON helpers (no external library needed) ──

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int i = json.indexOf(search);
        if (i < 0) return null;
        int start = i + search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static int extractInt(String json, String key, int fallback) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return fallback;
        int start = i + search.length();
        // Skip whitespace
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

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    /**
     * Holds the state of a remote player for rendering.
     */
    public static class RemotePlayerState {
        public String name = "Player";
        public String playerClass = "Fighter";
        public int worldX, worldY;
        public int direction = 0;
        public int spriteNum = 1;
        public boolean attacking = false;
        public int life = 6, maxLife = 6;
    }
}

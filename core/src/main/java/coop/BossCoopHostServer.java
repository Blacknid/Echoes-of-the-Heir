package coop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import main.GamePanel;

/**
 * Embedded TCP server the HOST's own game process runs when inviting friends to a LAN co-op boss
 * fight (see the design doc in {@link BossCoopProtocol}). This is intentionally much lighter than
 * {@code main.MultiplayerClient}'s dedicated-server protocol: no RSA/license handshake, just a
 * per-session random token (see {@link BossCoopProtocol#randomToken()}) that only friends the host
 * explicitly invited ever receive, handed to them alongside the mDNS-discovered invite.
 *
 * <p>Only ever runs on the machine hosting the fight. Joiners connect via {@link BossCoopClient}.
 */
public final class BossCoopHostServer {

    private final GamePanel gp;
    private final BossCoopSession session;
    private final String sessionToken;
    private final int bossId;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ClientHandle> clientsByUsername = new ConcurrentHashMap<>();

    public BossCoopHostServer(GamePanel gp, int bossId) {
        this.gp = gp;
        this.bossId = bossId;
        this.sessionToken = BossCoopProtocol.randomToken();
        this.session = new BossCoopSession(BossCoopSession.Role.HOST, gp.ui.playerUsername, bossId);
    }

    public BossCoopSession session() { return session; }
    public String sessionToken() { return sessionToken; }

    /** Only friends who receive this exact token (via the invite flow) can ever join. */
    public int port() { return serverSocket != null ? serverSocket.getLocalPort() : -1; }

    public boolean start() {
        try {
            serverSocket = new ServerSocket(BossCoopProtocol.DEFAULT_PORT);
        } catch (IOException e) {
            // Port already bound by another local session — extremely unlikely (one host per
            // machine at a time in practice), but fall back to an ephemeral port rather than fail.
            try {
                serverSocket = new ServerSocket(0);
            } catch (IOException e2) {
                session.setStatusMessage("Could not start LAN session: " + e2.getMessage());
                return false;
            }
        }
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "BossCoop-Host-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (ClientHandle c : clientsByUsername.values()) c.close();
        clientsByUsername.clear();
    }

    /** Host clicks "Proceed" in the waiting room — freezes the roster and tells everyone to start. */
    public void proceed() {
        if (session.state() != BossCoopSession.State.WAITING) return;
        session.setState(BossCoopSession.State.ACTIVE);

        coop.PlayerLoadout hostLoadout = coop.PlayerLoadout.capture(gp.player);
        String loadoutJson = LoadoutSerializer.toJson(gp.player);
        int playerCount = session.playerCount();

        for (ClientHandle c : clientsByUsername.values()) {
            c.send("{\"cmd\":\"" + BossCoopProtocol.CMD_START + "\",\"playerCount\":" + playerCount
                    + ",\"loadout\":" + jsonStringLiteral(loadoutJson) + "}");
        }
    }

    /** Called by the host's own game when the boss actually dies (see BOSS_WitheredTree hook). */
    public void notifyBossDefeated(int rewardExp) {
        for (ClientHandle c : clientsByUsername.values()) {
            c.send("{\"cmd\":\"" + BossCoopProtocol.CMD_BOSS_DEFEATED + "\",\"bossId\":" + bossId
                    + ",\"rewardExp\":" + rewardExp + "}");
        }
        session.setState(BossCoopSession.State.ENDED);
    }

    public void endSession(String reason) {
        for (ClientHandle c : clientsByUsername.values()) {
            c.send("{\"cmd\":\"" + BossCoopProtocol.CMD_SESSION_ENDED + "\",\"reason\":"
                    + jsonStringLiteral(reason) + "}");
        }
        session.setState(BossCoopSession.State.ENDED);
        stop();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket), "BossCoop-Host-Client").start();
            } catch (IOException e) {
                if (running.get()) {
                    // Unexpected accept failure while still supposed to be running — nothing more
                    // to do but stop cleanly; the host UI will show the session as ended.
                    running.set(false);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String username = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line = in.readLine();
            if (line == null) { socket.close(); return; }

            String cmd = BossCoopProtocol.extractString(line, "cmd");
            if (!BossCoopProtocol.CMD_JOIN.equals(cmd)) { socket.close(); return; }

            String token = BossCoopProtocol.extractString(line, "token");
            username = BossCoopProtocol.extractString(line, "username");

            if (session.state() != BossCoopSession.State.WAITING) {
                sendLine(out, "{\"cmd\":\"" + BossCoopProtocol.CMD_REJECTED + "\",\"reason\":\"Session already started.\"}");
                socket.close();
                return;
            }
            if (session.isFull()) {
                sendLine(out, "{\"cmd\":\"" + BossCoopProtocol.CMD_REJECTED + "\",\"reason\":\"Session is full.\"}");
                socket.close();
                return;
            }
            if (token == null || !token.equals(sessionToken)) {
                sendLine(out, "{\"cmd\":\"" + BossCoopProtocol.CMD_REJECTED + "\",\"reason\":\"Invalid or expired invite.\"}");
                socket.close();
                return;
            }
            // Friendship was already verified when the host picked this person from their LAN
            // friend list before the invite was ever sent (see the invite-picker UI) — the token
            // itself is proof they were on that list, so no second save-server round trip here.
            if (username == null || username.isBlank()) { socket.close(); return; }

            ClientHandle handle = new ClientHandle(socket, out, username);
            clientsByUsername.put(username, handle);
            session.addPlayer(username);
            broadcastWaitingRoom();

            String reply;
            while ((reply = in.readLine()) != null) {
                String rc = BossCoopProtocol.extractString(reply, "cmd");
                if (BossCoopProtocol.CMD_LEAVE.equals(rc)) break;
            }
        } catch (IOException ignored) {
            // Connection dropped — treated the same as an explicit LEAVE below.
        } finally {
            if (username != null) {
                ClientHandle removed = clientsByUsername.remove(username);
                if (removed != null) removed.close();
                session.removePlayer(username);
                if (session.state() == BossCoopSession.State.WAITING) broadcastWaitingRoom();
            }
        }
    }

    private void broadcastWaitingRoom() {
        StringBuilder names = new StringBuilder();
        List<String> players = session.players();
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) names.append(',');
            names.append('"').append(BossCoopProtocol.jsonEscape(players.get(i))).append('"');
        }
        String msg = "{\"cmd\":\"" + BossCoopProtocol.CMD_WAITING + "\",\"players\":[" + names + "]}";
        for (ClientHandle c : clientsByUsername.values()) c.send(msg);
    }

    private static void sendLine(BufferedWriter out, String line) {
        try { out.write(line); out.newLine(); out.flush(); } catch (IOException ignored) {}
    }

    private static String jsonStringLiteral(String s) {
        return "\"" + BossCoopProtocol.jsonEscape(s) + "\"";
    }

    private static final class ClientHandle {
        final Socket socket;
        final BufferedWriter out;

        ClientHandle(Socket socket, BufferedWriter out, String username) {
            this.socket = socket;
            this.out = out;
        }

        void send(String line) { sendLine(out, line); }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}

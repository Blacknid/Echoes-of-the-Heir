package coop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import main.GamePanel;

/**
 * Joiner-side connection to a friend's {@link BossCoopHostServer}. Handed the host's LAN address
 * + port (discovered via mDNS) and the session token that proves the host actually invited this
 * specific friend (see {@link BossCoopProtocol}'s trust-model note).
 */
public final class BossCoopClient {

    private final GamePanel gp;
    private final BossCoopSession session;
    private Socket socket;
    private BufferedWriter out;
    private Thread receiveThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Snapshot of the joiner's OWN loadout, taken right before the host's is overlaid — restored on end. */
    private PlayerLoadout ownLoadout;

    public BossCoopClient(GamePanel gp, String hostUsername, int bossId) {
        this.gp = gp;
        this.session = new BossCoopSession(BossCoopSession.Role.JOINER, hostUsername, bossId);
    }

    public BossCoopSession session() { return session; }
    public boolean isConnected() { return connected.get(); }

    /** Async connect + JOIN handshake. */
    public void connect(String hostIp, int port, String sessionToken) {
        new Thread(() -> {
            try {
                socket = new Socket(hostIp, port);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                String join = "{\"cmd\":\"" + BossCoopProtocol.CMD_JOIN + "\",\"token\":\""
                        + BossCoopProtocol.jsonEscape(sessionToken) + "\",\"username\":\""
                        + BossCoopProtocol.jsonEscape(gp.ui.playerUsername) + "\"}";
                out.write(join);
                out.newLine();
                out.flush();

                connected.set(true);
                receiveThread = new Thread(() -> receiveLoop(in), "BossCoop-Client-Receive");
                receiveThread.setDaemon(true);
                receiveThread.start();
            } catch (IOException e) {
                session.setStatusMessage("Could not connect: " + e.getMessage());
                session.setState(BossCoopSession.State.ENDED);
            }
        }, "BossCoop-Client-Connect").start();
    }

    public void leave() {
        try {
            if (out != null) { out.write("{\"cmd\":\"" + BossCoopProtocol.CMD_LEAVE + "\"}"); out.newLine(); out.flush(); }
        } catch (IOException ignored) {
        } finally {
            close();
            restoreOwnLoadout();
        }
    }

    private void receiveLoop(BufferedReader in) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = BossCoopProtocol.extractString(line, "cmd");
                if (cmd == null) continue;
                switch (cmd) {
                    case BossCoopProtocol.CMD_WAITING -> {
                        for (String name : BossCoopProtocol.extractStringArray(line, "players")) {
                            session.addPlayer(name);
                        }
                    }
                    case BossCoopProtocol.CMD_START -> {
                        ownLoadout = PlayerLoadout.capture(gp.player);
                        String loadoutJson = BossCoopProtocol.extractString(line, "loadout");
                        // The "loadout" value is itself a JSON object, so extractString (which only
                        // grabs quoted string values) can't read it directly — re-scan for the raw
                        // object span instead.
                        int idx = line.indexOf("\"loadout\":");
                        if (idx >= 0) {
                            int braceStart = line.indexOf('{', idx);
                            if (braceStart >= 0) {
                                String obj = BossCoopProtocol.extractJsonObject(line, braceStart);
                                LoadoutSerializer.applyTo(gp, obj, gp.player);
                            }
                        }
                        session.setState(BossCoopSession.State.ACTIVE);
                    }
                    case BossCoopProtocol.CMD_BOSS_DEFEATED -> {
                        int rewardExp = BossCoopProtocol.extractInt(line, "rewardExp", 0);
                        int bossId = BossCoopProtocol.extractInt(line, "bossId", session.bossId());
                        RewardingHelpers.rewardingHelpers(gp, bossId, rewardExp);
                        session.setState(BossCoopSession.State.ENDED);
                        restoreOwnLoadout();
                    }
                    case BossCoopProtocol.CMD_REJECTED -> {
                        session.setStatusMessage(BossCoopProtocol.extractString(line, "reason"));
                        session.setState(BossCoopSession.State.ENDED);
                    }
                    case BossCoopProtocol.CMD_SESSION_ENDED -> {
                        session.setStatusMessage(BossCoopProtocol.extractString(line, "reason"));
                        session.setState(BossCoopSession.State.ENDED);
                        restoreOwnLoadout();
                    }
                    default -> { /* ignore unknown */ }
                }
            }
        } catch (IOException ignored) {
            // Host disconnected without an explicit SESSION_ENDED — treat the same way.
        } finally {
            connected.set(false);
            if (session.state() != BossCoopSession.State.ENDED) {
                session.setState(BossCoopSession.State.ENDED);
                restoreOwnLoadout();
            }
        }
    }

    private void restoreOwnLoadout() {
        if (ownLoadout != null) {
            ownLoadout.applyTo(gp.player);
            ownLoadout = null;
        }
    }

    private void close() {
        connected.set(false);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}

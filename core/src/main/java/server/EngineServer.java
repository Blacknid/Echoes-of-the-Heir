package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import data.MonsterFactory;
import main.GamePanel;
import main.HeadlessGame;

/**
 * The authoritative gameplay engine, run as a child process of the Python multiplayer server.
 *
 * <p><b>Why a child process, not a rewrite.</b> The Python server is the network front end: it
 * owns the TCP socket, the AES-GCM handshake, license verification, bans, rate limiting, the
 * admin console and map/chunk streaming. All of that is audited and works, and none of it is
 * gameplay. This process owns the other half, the <em>rules</em> of the game (combat, XP, and
 * in time drops and quests), and it owns them by running <b>the same Java classes the client
 * runs</b> (see {@link HeadlessGame}). One rulebook, so the server and client can never disagree
 * about an outcome, and nothing has to be ported to Python and kept in sync.
 *
 * <p><b>The link.</b> Python launches this jar and talks to it over stdin/stdout with one JSON
 * object per line, a private, loopback-only channel between a parent and its child, so it needs
 * no encryption of its own (the client-facing crypto already terminated in Python). Requests come
 * in on stdin; events go out on stdout; everything else (our own logging) goes to stderr so it
 * can never corrupt the protocol stream.
 *
 * <p><b>Protocol.</b> Line-delimited JSON. Every message has a {@code "cmd"} (requests, C→E) or
 * {@code "event"} (responses, E→C). Requests carry an {@code "rid"} (request id) which the
 * matching event echoes, so Python can correlate a reply with its call. Current commands:
 * <ul>
 * <li>{@code {"cmd":"ping","rid":N}} → {@code {"event":"pong","rid":N}}, liveness.</li>
 *   <li>{@code {"cmd":"mob_hit","rid":N,"mobId":M,"mobType":"slime","damage":D,"pid":P}} →
 *       {@code {"event":"mob_state","rid":N,"mobId":M,"life":L,"maxLife":ML,"alive":b,
 *       "dying":b,"applied":A,"killed":b,"exp":X,"pid":P}}. The server owns the life pool and
 *       decides {@code killed}/{@code exp} from the shared monster definitions.</li>
 * <li>{@code {"cmd":"reset_mobs","rid":N}} → {@code {"event":"ok","rid":N}}, forget mob
 *       state (e.g. on map change), so ids can be reused.</li>
 * </ul>
 *
 * <p>On boot it emits {@code {"event":"ready","tps":T,"map":"...","monsters":N}} once the
 * simulation is up, so the parent knows the child is alive before it forwards any traffic.
 */
public final class EngineServer {

    /** Simulation ticks per second. The parent drives real time; we advance the world at this rate. */
    private static final int TICK_RATE = 20;

    private final GamePanel gp;
    private final Map<Integer, AuthoritativeMob> mobs = new HashMap<>();
    /** stdout, reserved strictly for protocol lines. Logging must use {@link #log}. */
    private final PrintStream out;
    private volatile boolean running = true;

    private EngineServer(GamePanel gp, PrintStream out) {
        this.gp = gp;
        this.out = out;
    }

    public static void main(String[] args) {
        // Grab the real stdout for the protocol BEFORE anything else can print to it, then send
        // System.out to stderr so any stray println in the game code (there are many) cannot
        // corrupt the JSON stream the parent is parsing.
        PrintStream protocolOut = System.out;
        System.setOut(System.err);

        log("[EngineServer] Booting authoritative simulation...");
        GamePanel gp = HeadlessGame.boot(TICK_RATE);
        gp.gameState = GamePanel.playState;
        MonsterFactory.loadDefinitions();

        EngineServer engine = new EngineServer(gp, protocolOut);
        engine.emitReady();
        engine.run();
    }

    /** Tell the parent we are alive and what world we loaded. */
    private void emitReady() {
        int monsters = MonsterFactory.getRegisteredIds().size();
        emit("{\"event\":\"ready\",\"tps\":" + TICK_RATE
                + ",\"map\":\"" + Json.esc(gp.mapManager.currentMapId) + "\""
                + ",\"monsters\":" + monsters + "}");
    }

    /**
     * The main loop. It does two things forever: drain any pending requests from stdin, and
     * advance the simulation one tick, sleeping to hold {@link #TICK_RATE}. Reading stdin is
     * non-blocking-ish: we only block on {@code readLine} when the parent has sent something,
     * checked via {@code ready()}, so a quiet parent never stalls the tick.
     */
    private void run() {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final long tickNanos = 1_000_000_000L / TICK_RATE;

        while (running) {
            long start = System.nanoTime();
            try {
                while (in.ready()) {
                    String line = in.readLine();
                    if (line == null) {          // parent closed stdin → shut down cleanly
                        running = false;
                        break;
                    }
                    line = line.trim();
                    if (!line.isEmpty()) handleLine(line);
                }
            } catch (IOException e) {
                log("[EngineServer] stdin closed: " + e.getMessage());
                running = false;
                break;
            }

            // Advance the authoritative world. Combat outcomes come from command handling above;
            // ticking keeps AI/timers moving so the simulation stays live for future authority.
            gp.update();

            long elapsed = System.nanoTime() - start;
            long sleep = tickNanos - elapsed;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        log("[EngineServer] Shutting down.");
        HeadlessGame.shutdown();
    }

    private void handleLine(String line) {
        String cmd = Json.str(line, "cmd");
        int rid = Json.integer(line, "rid", -1);
        if (cmd == null) return;

        switch (cmd) {
            case "ping" -> emit("{\"event\":\"pong\",\"rid\":" + rid + "}");
            case "mob_hit" -> handleMobHit(line, rid);
            case "reset_mobs" -> {
                mobs.clear();
                emit("{\"event\":\"ok\",\"rid\":" + rid + "}");
            }
            default -> log("[EngineServer] unknown cmd: " + cmd);
        }
    }

    /**
     * A client claims it hit a monster. WE decide the result: the mob's life pool lives here and
     * comes from the shared JSON, so we clamp and validate the damage, subtract the real defense,
     * and report the authoritative life left, whether the mob died, and the XP the kill is worth.
     */
    private void handleMobHit(String line, int rid) {
        int mobId = Json.integer(line, "mobId", -1);
        int damage = Json.integer(line, "damage", 0);
        int pid = Json.integer(line, "pid", -1);
        String mobType = Json.str(line, "mobType");
        if (mobType == null) mobType = "unknown";

        if (mobId < 0) {
            emit("{\"event\":\"mob_state\",\"rid\":" + rid + ",\"error\":\"bad_mob_id\"}");
            return;
        }

        AuthoritativeMob mob = mobs.get(mobId);
        if (mob == null) {
            mob = new AuthoritativeMob(mobId, mobType);
            mobs.put(mobId, mob);
        }

        int applied = mob.applyHit(damage, pid);
        boolean killed = !mob.alive && applied > 0;
        int exp = killed ? mob.expReward : 0;

        emit("{\"event\":\"mob_state\""
                + ",\"rid\":" + rid
                + ",\"mobId\":" + mob.mobId
                + ",\"life\":" + mob.life
                + ",\"maxLife\":" + mob.maxLife
                + ",\"alive\":" + mob.alive
                + ",\"dying\":" + mob.dying
                + ",\"applied\":" + applied
                + ",\"killed\":" + killed
                + ",\"exp\":" + exp
                + ",\"pid\":" + pid + "}");
    }

    /** Write one protocol line to stdout and flush, so the parent sees it immediately. */
    private synchronized void emit(String jsonLine) {
        out.print(jsonLine);
        out.print('\n');
        out.flush();
    }

    /** Log to stderr, never stdout, which is the protocol channel. */
    private static void log(String msg) {
        System.err.println(msg);
    }
}

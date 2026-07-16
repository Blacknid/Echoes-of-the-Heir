package main;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;

/**
 * Boots the game's simulation with no window and no GPU, the foundation the authoritative
 * server is built on.
 *
 * <p>The point of this class is that the server runs <b>the same code as the client</b>. Not a
 * port, not a reimplementation: the very same {@link GamePanel}, {@code Player}, {@code Entity},
 * monster AI, {@code CollisionChecker} and {@code QuestManager}. Two implementations of one
 * rulebook always drift apart, and every place they drift is a desync or an exploit. One
 * implementation cannot disagree with itself.
 *
 * <p>Two things make that possible:
 * <ul>
 *   <li><b>libGDX's headless backend</b> supplies {@code Gdx.files} and {@code Gdx.app} without
 *       opening a window or creating a GL context, so assets (TMX maps, JSON, PNG headers) still
 *       load through the normal {@code ResourceCache} path.</li>
 *   <li><b>{@link Headless}</b> switches off the handful of code paths that genuinely need the
 * GPU. There are very few, because the simulation never reads a pixel, it only ever asks a
 *       sprite how big it is, in order to slice a sheet into frames.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   GamePanel gp = HeadlessGame.boot();
 *   while (running) { gp.update(); }   // one authoritative tick
 * </pre>
 */
public final class HeadlessGame {

    private static HeadlessApplication app;

    private HeadlessGame() {}

    /**
     * Start the headless libGDX application and build a fully set-up {@link GamePanel} whose
     * simulation is ready to tick. Rendering is not initialised and must never be invoked.
     *
     * @param targetTps simulation ticks per second the caller intends to drive (recorded on the
     *                  libGDX config; the caller still owns the actual loop)
     */
    public static synchronized GamePanel boot(int targetTps) {
        // Order matters: enable() flips ResourceCache into dimension-only image loading, and
        // GamePanel's field initialisers (UI, TileManager) load assets as they construct.
        Headless.enable();

        if (app == null) {
            HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
            cfg.updatesPerSecond = targetTps;
            // The listener does nothing: we drive the simulation ourselves rather than letting
            // libGDX call render(). We only want it for Gdx.files / Gdx.app.
            app = new HeadlessApplication(new com.badlogic.gdx.ApplicationAdapter() {}, cfg);
        }
        if (Gdx.files == null) {
            throw new IllegalStateException(
                    "Headless libGDX did not initialise Gdx.files — cannot load assets.");
        }

        GamePanel gp = new GamePanel();
        gp.setupGame();
        System.out.println("[HeadlessGame] Simulation booted (no window, no GL).");
        return gp;
    }

    /** Shut the headless libGDX application down. */
    public static synchronized void shutdown() {
        if (app != null) {
            app.exit();
            app = null;
        }
    }

    /**
     * Smoke test: boot the simulation with no GPU and tick it, proving the game logic runs
     * renderless. Run with {@code ./gradlew :core:runHeadless}.
     */
    public static void main(String[] args) {
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 120;

        long t0 = System.nanoTime();
        GamePanel gp = boot(60);
        long bootMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("[HeadlessGame] Booted in " + bootMs + " ms");
        System.out.println("[HeadlessGame]   map      = " + gp.mapManager.currentMapId);
        System.out.println("[HeadlessGame]   player   = lvl " + gp.player.level
                + "  life " + gp.player.life + "/" + gp.player.maxLife
                + "  @(" + gp.player.worldX + "," + gp.player.worldY + ")");
        System.out.println("[HeadlessGame]   npcs     = " + countNonNull(gp.npc));
        System.out.println("[HeadlessGame]   monsters = " + countNonNull(gp.monster));
        System.out.println("[HeadlessGame]   objects  = " + countNonNull(gp.obj));

        // Drive the simulation. gameState starts at titleState, which does nothing; put it into
        // playState so update() actually runs entities, collision, AI and events.
        gp.gameState = GamePanel.playState;
        t0 = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            gp.update();
        }
        long tickMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("[HeadlessGame] Ticked " + ticks + " times in " + tickMs + " ms ("
                + String.format("%.2f", tickMs / (double) ticks) + " ms/tick)");
        System.out.println("[HeadlessGame]   player now @(" + gp.player.worldX + ","
                + gp.player.worldY + ")  life " + gp.player.life);
        System.out.println("[HeadlessGame] OK — simulation runs with no window and no GL.");

        shutdown();
        System.exit(0);
    }

    private static int countNonNull(Object[] arr) {
        if (arr == null) return 0;
        int n = 0;
        for (Object o : arr) if (o != null) n++;
        return n;
    }
}

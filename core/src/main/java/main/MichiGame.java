package main;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;

import gfx.FontSystem;
import gfx.GdxRenderer;

/**
 * libGDX application entry point — the GPU replacement for the old Swing {@code GamePanel} game
 * loop. {@link com.badlogic.gdx.ApplicationListener#render()} is libGDX's per-frame callback,
 * driven by the LWJGL3 backend (desktop) or Android backend (later) — no daemon thread or
 * {@code paintComponent}.
 *
 * <h2>Top-left pixel origin</h2>
 * The game uses Graphics2D-style coordinates: (0,0) top-left, +Y down. We flip ONCE here via an
 * {@link OrthographicCamera} sized to the window with the top edge at y=0 (see {@link #syncCamera}),
 * so every existing draw coordinate works unchanged.
 *
 * <p>Each frame: clear → {@code gp.stepUpdates(delta)} (fixed 60 UPS) → {@code gp.draw(renderer)}.
 */
public class MichiGame extends ApplicationAdapter {

    /** Design baseline resolution (legacy 1280x720); actual size follows the window. */
    public static final int BASE_W = 1280;
    public static final int BASE_H = 720;

    private OrthographicCamera camera;
    private GdxRenderer renderer;
    private FontSystem fonts;
    private GamePanel gp;

    @Override
    public void create() {
        camera = new OrthographicCamera();
        syncCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // Font faces used by the game (loaded from the assets root /res/fonts).
        fonts = new FontSystem();
        registerFace("Pixeloid Sans", "res/fonts/Pixeloid Sans.ttf");
        registerFace("m5x7", "res/fonts/m5x7.ttf");
        fonts.setDefaultFace("Pixeloid Sans");

        renderer = new GdxRenderer(camera, fonts);

        // Build the game and run its one-time setup (loads maps, entities, pools, etc.).
        gp = new GamePanel();
        gp.config.loadConfig();
        gp.applyFpsTarget(gp.config.fpsTarget);
        gp.setupGame();
        gp.startGameThread(); // no-op now; kept for API parity

        // Route input to both handlers (key + mouse) via a multiplexer.
        InputMultiplexer mux = new InputMultiplexer();
        mux.addProcessor(gp.keyH);
        mux.addProcessor(gp.mouseH);
        Gdx.input.setInputProcessor(mux);

        Gdx.app.log("MichiGame", "create() OK — GL " + Gdx.gl.glGetString(GL20.GL_VERSION)
            + ", window " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight());
    }

    private void registerFace(String name, String path) {
        com.badlogic.gdx.files.FileHandle fh = Gdx.files.internal(path);
        if (fh.exists()) fonts.registerFace(name, fh);
        else Gdx.app.log("MichiGame", "Font face missing: " + path);
    }

    /** Configure the camera so (0,0) is top-left and +Y points down, sized to the window. */
    private void syncCamera(int w, int h) {
        camera.setToOrtho(true, w, h); // yDown = true
        camera.update();
        if (gp != null) gp.applyNewResolution(w, h);
    }

    @Override
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        syncCamera(width, height);
    }

    @Override
    public void render() {
        // Clear to the current map background (or atmospheric dark), replacing the old
        // back-buffer clear in drawToTempScreen.
        gfx.Color bg = (gp != null && gp.mapManager != null && gp.mapManager.mapBackgroundColor != null)
            ? gp.mapManager.mapBackgroundColor : new gfx.Color(8, 6, 14);
        Gdx.gl.glClearColor(bg.getRed() / 255f, bg.getGreen() / 255f, bg.getBlue() / 255f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (gp == null) return;

        // Fixed-timestep simulation, then render this frame.
        gp.stepUpdates(Gdx.graphics.getDeltaTime());

        // Memory flashback freezes updates but must still render; gp.draw handles state internally.
        renderer.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        gp.draw(renderer);
        if (gp.memoryFlashback != null && gp.memoryFlashback.isActive()) {
            gp.memoryFlashback.draw(renderer);
        }
        renderer.end();
    }

    @Override
    public void dispose() {
        if (renderer != null) renderer.dispose();
        if (fonts != null) fonts.dispose();
    }
}

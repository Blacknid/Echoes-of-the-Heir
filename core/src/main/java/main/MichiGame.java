package main;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;

import gfx.FontSystem;
import gfx.GdxRenderer;
import mobile.GamepadInputAdapter;
import mobile.TouchControlsOverlay;

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
    // protected (not private) so dev/screenshot harnesses in the desktop module can subclass and force
    // game state / dump debug FBOs for automated visual verification. Not used in production.
    protected GdxRenderer renderer;
    private FontSystem fonts;
    protected GamePanel gp;
    private TouchControlsOverlay touchOverlay;

    @Override
    public void create() {
        // No-ops on every backend except Android. Must run here (not from AndroidLauncher's
        // onCreate() before initialize()) since Gdx.app/Gdx.files aren't live until this point
        // on the Android backend.
        platform.AndroidLicense.primeIfAndroid();

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

        // Route input to both handlers (key + mouse) via a multiplexer. On Android, the touch
        // overlay's Stage goes first so it can consume taps on its own widgets (joystick/action
        // buttons) before they'd otherwise reach MouseHandler as world-taps.
        InputMultiplexer mux = new InputMultiplexer();
        boolean isAndroid = Gdx.app.getType() == Application.ApplicationType.Android;
        if (isAndroid) {
            touchOverlay = new TouchControlsOverlay(gp);
            mux.addProcessor(touchOverlay.getStage());
        }
        mux.addProcessor(gp.keyH);
        mux.addProcessor(gp.mouseH);
        Gdx.input.setInputProcessor(mux);

        // Gamepad support coexists with touch/keyboard — same shared input fields, no
        // exclusivity logic needed. Harmless no-op if no controller is paired.
        Controllers.addListener(new GamepadInputAdapter(gp));

        Gdx.app.log("MichiGame", "create() OK — GL " + Gdx.gl.glGetString(GL20.GL_VERSION)
            + ", window " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight());
    }

    private void registerFace(String name, String path) {
        com.badlogic.gdx.files.FileHandle fh = Gdx.files.internal(path);
        if (fh.exists()) fonts.registerFace(name, fh);
        else Gdx.app.log("MichiGame", "Font face missing: " + path);
    }

    /**
     * Configure the camera + GL viewport for Moonshire-style integer-scaled rendering — identical for
     * windowed and fullscreen.
     *
     * <p>We pick the largest whole-number {@code pixelScale} the window can FULLY support, then the
     * world is always drawn at that exact integer magnification (crisp pixel art, no fractional
     * sampling). Any space left over because the window isn't an exact multiple is NOT letterboxed —
     * it becomes extra LOGICAL pixels, which the tile culling turns into MORE visible map tiles. So a
     * window that is 2.4x wide and a clean 2x tall renders the world at a crisp 2x and the leftover
     * 0.4x of width fills with additional tiles.
     *
     * <p>Scale is {@code floor(min(widthRatio, heightRatio))}: we only step up to Nx once a COMPLETE
     * Nx of the 1280x720 baseline fits on BOTH axes, so the extra screen area only ever ADDS tiles and
     * never hides any of the baseline view. Consequence: a "2x" WINDOW often stays at 1x because the OS
     * title bar steals a few px of height (so a full 2x of 720 doesn't fit) — fullscreen has no chrome,
     * so it reaches 2x/3x cleanly. This is the intended trade-off (never crop the baseline view).
     */
    private void syncCamera(int deviceW, int deviceH) {
        // Choose the integer magnification from how far the window has zoomed past the baseline. Use
        // the SMALLER axis ratio as the ceiling (so we never zoom in so far that the logical view drops
        // below the 1280x720 baseline on either axis — the extra screen must only ever ADD tiles, never
        // remove them), but FLOOR it so we step up to Nx exactly when a full Nx of baseline fits.
        double fitRatio = Math.min(deviceW / (double) BASE_W, deviceH / (double) BASE_H);
        int pixelScale = Math.max(1, (int) Math.floor(fitRatio));

        int logicalW = deviceW / pixelScale;
        int logicalH = deviceH / pixelScale;
        int usedW = logicalW * pixelScale;
        int usedH = logicalH * pixelScale;
        int marginX = (deviceW - usedW) / 2;
        int marginY = (deviceH - usedH) / 2;

        camera.setToOrtho(true, logicalW, logicalH); // yDown = true, box sized in logical px
        camera.update();
        Gdx.gl.glViewport(marginX, marginY, usedW, usedH);
        if (renderer != null) renderer.setWorldViewport(marginX, marginY, usedW, usedH);

        if (gp != null) {
            gp.applyNewResolution(logicalW, logicalH, pixelScale, marginX, marginY, deviceW, deviceH);
        }
    }

    @Override
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        syncCamera(width, height);
        if (touchOverlay != null) touchOverlay.getStage().getViewport().update(width, height, true);
        if (touchOverlay != null) touchOverlay.layout();
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

        // Dancing-lights hunt: F-key kill-switches (polled here so they work regardless of game
        // state / the KeyHandler). See gfx.shader.LightDebug + ui.LightDebugHud.
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F5))  gfx.shader.LightDebug.hud        = !gfx.shader.LightDebug.hud;
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F6))  gfx.shader.LightDebug.freezeTime = !gfx.shader.LightDebug.freezeTime;
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F7))  gfx.shader.LightDebug.noDetail   = !gfx.shader.LightDebug.noDetail;
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F8))  gfx.shader.LightDebug.noBloom    = !gfx.shader.LightDebug.noBloom;
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F9))  gfx.shader.LightDebug.noShadows  = !gfx.shader.LightDebug.noShadows;
        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F10)) gfx.shader.LightDebug.noRim      = !gfx.shader.LightDebug.noRim;

        // Touch overlay reads/writes KeyHandler/MouseHandler fields before stepUpdates() consumes
        // them this frame, exactly like a physical key/mouse event would have already arrived.
        if (touchOverlay != null) touchOverlay.act(Gdx.graphics.getDeltaTime());

        // Fixed-timestep simulation, then render this frame.
        gp.stepUpdates(Gdx.graphics.getDeltaTime());

        // Memory flashback freezes updates but must still render; gp.draw handles state internally.
        // Use the LOGICAL resolution (not device px) — the camera/viewport magnify it by pixelScale.
        renderer.begin(gp.screenWidth, gp.screenHeight);
        gp.draw(renderer);
        if (gp.memoryFlashback != null && gp.memoryFlashback.isActive()) {
            gp.memoryFlashback.draw(renderer);
        }
        renderer.end();

        if (touchOverlay != null) touchOverlay.draw();
    }

    @Override
    public void dispose() {
        if (renderer != null) renderer.dispose();
        if (fonts != null) fonts.dispose();
        if (touchOverlay != null) touchOverlay.dispose();
    }
}

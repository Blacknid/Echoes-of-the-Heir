package main;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * libGDX application entry point — the GPU replacement for the old Swing {@code GamePanel}
 * game loop. {@link com.badlogic.gdx.ApplicationListener#render()} is libGDX's per-frame
 * callback; it is driven by the LWJGL3 backend (desktop) or the Android backend (later),
 * so there is no hand-rolled daemon thread / {@code paintComponent} anymore.
 *
 * <h2>Top-left pixel origin</h2>
 * The user requires Graphics2D-style coordinates: (0,0) at the top-left, +Y going DOWN.
 * libGDX's default is bottom-left, +Y up. We flip ONCE here by inverting the camera's
 * Y axis ({@link OrthographicCamera#setToOrtho(boolean, float, float)} with {@code yDown=true}).
 * After this, every existing draw coordinate from the Java2D code works unchanged.
 *
 * <p>STAGE 0/1: this class only sets up the camera + clears the screen (proves the libGDX
 * shell runs). The real game state, update loop, and rendering pipeline are wired in over
 * the subsequent stages, reusing the existing engine logic via the {@code gfx.GdxRenderer}
 * facade.
 */
public class MichiGame extends ApplicationAdapter {

    /** Logical/back-buffer resolution baseline (matches the legacy 1280x720 design size). */
    public static final int BASE_W = 1280;
    public static final int BASE_H = 720;

    private OrthographicCamera camera;
    private Viewport viewport;

    @Override
    public void create() {
        camera = new OrthographicCamera();
        // yDown=true → (0,0) is top-left and +Y points downward, matching Graphics2D.
        camera.setToOrtho(true, BASE_W, BASE_H);
        // ScreenViewport: 1 world unit = 1 pixel, adapts to window size (dynamic-viewport
        // mode, like the legacy stretchToFill=false default). We refine viewport choice when
        // the render pipeline is ported.
        viewport = new ScreenViewport(camera);
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        Gdx.app.log("MichiGame", "create() OK — GL " + Gdx.gl.glGetString(GL20.GL_VERSION)
            + ", top-left origin camera " + BASE_W + "x" + BASE_H
            + ", window " + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight());
    }

    @Override
    public void resize(int width, int height) {
        // Keep top-left origin on resize.
        viewport.update(width, height, true);
    }

    @Override
    public void render() {
        // Deep atmospheric dark — matches the legacy letterbox fill color (8,6,14).
        Gdx.gl.glClearColor(8f / 255f, 6f / 255f, 14f / 255f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camera.update();
        // Rendering is added stage by stage. For now the window simply clears.
    }

    @Override
    public void dispose() {
        // Disposable GPU resources are released here as subsystems are ported.
    }

    public OrthographicCamera getCamera() { return camera; }
    public Viewport getViewport() { return viewport; }
}

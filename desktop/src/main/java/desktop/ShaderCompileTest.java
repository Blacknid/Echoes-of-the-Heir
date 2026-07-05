package desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import gfx.shader.ShaderPipeline;

/**
 * Dev harness: boots a real (but hidden) GL context and compiles the {@link ShaderPipeline}, printing
 * whether the GLSL lighting shaders compile on THIS machine's GPU/driver, then exits. This verifies the
 * riskiest part of the shader work (does the GLSL compile + link on the target driver) without needing
 * to click through the game to a lit scene.
 *
 * Run: {@code ./gradlew :desktop:run -PmainClass=desktop.ShaderCompileTest}
 */
public final class ShaderCompileTest {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("ShaderCompileTest");
        cfg.setWindowedMode(320, 200);
        cfg.setInitialVisible(false); // no visible window; we just need the GL context
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                ShaderPipeline pipe = new ShaderPipeline();
                if (pipe.isAvailable()) {
                    System.out.println("SHADER_COMPILE_TEST: PASS — GLSL lighting compiled OK on this GPU.");
                } else {
                    System.out.println("SHADER_COMPILE_TEST: FAIL — " + pipe.failureLog());
                }
                pipe.dispose();
                Gdx.app.exit();
            }
        }, cfg);
    }
}

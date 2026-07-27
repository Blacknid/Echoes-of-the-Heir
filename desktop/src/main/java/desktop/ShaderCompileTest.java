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
                boolean pass = true;

                // 1) The real pipeline: file-based sources, plus the MED/cheap variant and the
                // bloom/grade/rim group. isAvailable() covers light; isBloomAvailable() covers post.
                ShaderPipeline pipe = new ShaderPipeline();
                if (pipe.isAvailable()) {
                    System.out.println("  [1] light pipeline (file sources): PASS");
                } else {
                    System.out.println("  [1] light pipeline (file sources): FAIL — " + pipe.failureLog());
                    pass = false;
                }
                if (pipe.isBloomAvailable()) {
                    System.out.println("  [2] bloom/grade/rim group: PASS");
                } else {
                    // Not fatal to lighting, but it disables the whole post chain, so surface it loudly.
                    System.out.println("  [2] bloom/grade/rim group: FAIL (post chain would be disabled)");
                    pass = false;
                }
                pipe.dispose();

                // 2) The BAKED-IN fallback sources. These are a second, hand-maintained copy of the same
                // GLSL used whenever a shader file is missing or unreadable (a real path on Android).
                // They have drifted from the files before, so compile them explicitly rather than
                // trusting that the file path passing implies the fallback does.
                String bakedReport = ShaderPipeline.verifyBakedSources();
                System.out.print(bakedReport);
                if (bakedReport.contains("FAIL")) pass = false;

                System.out.println(pass
                    ? "SHADER_COMPILE_TEST: PASS — every shader variant compiled on this GPU."
                    : "SHADER_COMPILE_TEST: FAIL — see the per-variant lines above.");
                Gdx.app.exit();
            }
        }, cfg);
    }
}

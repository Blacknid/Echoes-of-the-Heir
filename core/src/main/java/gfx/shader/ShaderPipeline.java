package gfx.shader;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/**
 * Owns the GLSL shader programs for the "stunning" lighting/bloom pipeline and, critically, guards
 * portability: every shader is compiled at construction and, if ANY of them fails to compile or link
 * on the current GPU/driver, {@link #isAvailable()} returns false and callers fall back to the legacy
 * baked-texture lighting path ({@code Lightning}'s additive/dst-out draw). No exceptions escape; the
 * game never crashes because of a shader.
 *
 * <h2>Why this exists</h2>
 * The legacy lighting baked a radial-gradient TEXTURE and blended it. That bands, can't do smooth HDR
 * falloff, and can't sample an occluder map to cast shadows. Moving the light math into a fragment
 * shader gives per-pixel smooth falloff, soft penumbra, and (stage 2) shadow sampling — all on any GPU
 * that supports GLSL ES 1.00 / GLSL 120, i.e. essentially everything from the last ~15 years. There is
 * no vendor-specific code (no "tensor cores" etc.); a single portable shader is both the fastest and
 * the most compatible choice for 2D lighting.
 *
 * <h2>Fullscreen pass</h2>
 * All passes are a single fullscreen triangle-pair ({@link #fullscreenQuad}) drawn in clip space
 * ([-1,1]) with UVs in [0,1]; the fragment shader does the work. The caller binds the target FBO and
 * sets uniforms; this class just compiles, validates, and draws.
 */
public final class ShaderPipeline {

    /** Max simultaneous lights uploaded to the light shader in one pass (matches Lightning.MAX_LIGHTS). */
    public static final int MAX_LIGHTS = 20;

    /** Compile-time defines prepended to light.frag for the MED/mobile variant: a 12-step shadow march
     *  (vs 32) and CHEAP (skips the value-noise/ripple organic detail). Same source, one tier knob. */
    private static final String CHEAP_DEFINES = "#define SHADOW_STEPS 12\n#define CHEAP 1\n";

    private final ShaderProgram lightShader;
    /** MED/mobile variant of the light shader: same source compiled with fewer shadow-march steps and
     *  the organic-noise detail stripped ({@link #CHEAP_DEFINES}). Falls back to {@link #lightShader}
     *  if it fails to compile, so the cheap tier can never lose lighting. */
    private final ShaderProgram lightShaderCheap;
    private final ShaderProgram bloomBright;
    private final ShaderProgram bloomBlur;
    private final ShaderProgram bloomCombine;
    private final ShaderProgram gradeShader;
    private final ShaderProgram rimShader;
    private final Mesh fullscreenQuad;
    private final boolean available;   // light path
    private final boolean bloomOk;     // bloom path (a subset; light can be up while bloom is down)
    private String failureLog = "";

    // Half-resolution ping-pong FBOs for the bloom blur (allocated lazily on first bloom, resized on demand).
    private FrameBuffer bloomA, bloomB;
    private int bloomW, bloomH;

    // Cached light state from the most recent renderLightMask, reused by renderRim in the same frame so
    // the rim pass lights match the lighting pass exactly without re-passing all the arrays.
    private int   cN, cW, cH;
    private final float[] cLx = new float[MAX_LIGHTS], cLy = new float[MAX_LIGHTS], cLr = new float[MAX_LIGHTS];
    private final float[] cCr = new float[MAX_LIGHTS], cCg = new float[MAX_LIGHTS], cCb = new float[MAX_LIGHTS];
    private final float[] cLi = new float[MAX_LIGHTS];

    /** Seconds since start, driven by the caller each frame; animates flicker/breathing/noise. */
    private float time = 0f;
    public void setTime(float t) { this.time = t; }

    /** Diagnostic (-Dlight.freezetime=1): freeze u_time so the shader's noise/ripple/flicker stop
     *  drifting. If the light STILL appears to "2x/track camera" while walking with this on, the drift
     *  is a coordinate/frame-lag bug, not the time-driven organic-detail animation. Non-destructive. */
    private static final boolean FREEZE_TIME = "1".equals(System.getProperty("light.freezetime"));

    public ShaderPipeline() {
        // Don't throw on a bad shader — we want a clean boolean the caller can branch on.
        ShaderProgram.pedantic = false;

        // Shaders live as editable .vert/.frag files under res/shaders/; loaded at runtime with the
        // in-class string constants as a guaranteed fallback (a missing/unreadable file never breaks the
        // build or the game — it just uses the baked-in source).
        // Load the vertex source: file with a baked-in fallback. We keep BOTH strings so compile() can
        // self-heal (retry with the baked-in source) if a file edit produces invalid GLSL.
        String vertFile = src("res/shaders/fullscreen.vert", ShaderSources.FULLSCREEN_VERT);
        String vertBake = ShaderSources.FULLSCREEN_VERT;

        ShaderProgram light = null, lightCheap = null, bright = null, blur = null, combine = null,
                grade = null, rim = null;
        Mesh quad = null;
        boolean ok = false, bOk = false;
        try {
            light = compile(vertFile, vertBake, "res/shaders/light.frag", ShaderSources.LIGHT_FRAG);
            if (!light.isCompiled()) {
                failureLog = "light.frag: " + light.getLog();
            } else {
                // MED/mobile light variant: the SAME light.frag source compiled with defines prepended
                // (fewer shadow-march steps, organic noise stripped). Optional — any failure logs and
                // leaves lightCheap null, and renderLightMask silently reuses the full shader.
                String cheapFrag = CHEAP_DEFINES + src("res/shaders/light.frag", ShaderSources.LIGHT_FRAG);
                lightCheap = new ShaderProgram(vertFile, cheapFrag);
                if (!lightCheap.isCompiled()) {
                    Gdx.app.error("ShaderPipeline", "cheap light variant failed — retrying baked source. Log: "
                        + lightCheap.getLog());
                    lightCheap.dispose();
                    lightCheap = new ShaderProgram(vertBake, CHEAP_DEFINES + ShaderSources.LIGHT_FRAG);
                    if (!lightCheap.isCompiled()) {
                        Gdx.app.error("ShaderPipeline", "cheap light variant unavailable (full shader will be "
                            + "used on MED too). Log: " + lightCheap.getLog());
                        lightCheap.dispose();
                        lightCheap = null;
                    }
                }
                quad = buildFullscreenQuad();
                ok = true;
                // Bloom+grade are optional: if they fail to compile, lighting still runs (post disabled).
                bright  = compile(vertFile, vertBake, "res/shaders/bloom_bright.frag",  ShaderSources.BLOOM_BRIGHT_FRAG);
                blur    = compile(vertFile, vertBake, "res/shaders/bloom_blur.frag",    ShaderSources.BLOOM_BLUR_FRAG);
                combine = compile(vertFile, vertBake, "res/shaders/bloom_combine.frag", ShaderSources.BLOOM_COMBINE_FRAG);
                grade   = compile(vertFile, vertBake, "res/shaders/grade.frag",         ShaderSources.GRADE_FRAG);
                rim     = compile(vertFile, vertBake, "res/shaders/rim.frag",           ShaderSources.RIM_FRAG);
                bOk = bright.isCompiled() && blur.isCompiled() && combine.isCompiled()
                        && grade.isCompiled() && rim.isCompiled();
                if (!bOk) {
                    Gdx.app.error("ShaderPipeline", "Bloom/grade/rim shaders failed to compile — post disabled. "
                        + "bright=" + bright.getLog() + " blur=" + blur.getLog()
                        + " combine=" + combine.getLog() + " grade=" + grade.getLog() + " rim=" + rim.getLog());
                }
            }
        } catch (Throwable t) {
            // Any driver-level explosion (no GL context, ancient GLSL, etc.) → unavailable, not fatal.
            failureLog = String.valueOf(t.getMessage());
            ok = false;
        }

        this.lightShader = light;
        this.lightShaderCheap = lightCheap;
        this.bloomBright = bright;
        this.bloomBlur = blur;
        this.bloomCombine = combine;
        this.gradeShader = grade;
        this.rimShader = rim;
        this.fullscreenQuad = quad;
        this.available = ok;
        this.bloomOk = ok && bOk;

        if (!ok) {
            Gdx.app.error("ShaderPipeline",
                "GLSL lighting unavailable — falling back to baked-texture lighting. Reason: " + failureLog);
            if (light != null) light.dispose();
            if (lightCheap != null) lightCheap.dispose();
            if (bright != null) bright.dispose();
            if (blur != null) blur.dispose();
            if (combine != null) combine.dispose();
            if (grade != null) grade.dispose();
            if (rim != null) rim.dispose();
        } else {
            Gdx.app.log("ShaderPipeline", "GLSL lighting pipeline compiled OK. Bloom=" + bOk);
        }
    }

    /** True when the light shader compiled/linked; callers must check this before using the light pass. */
    public boolean isAvailable() { return available; }

    /** True when the bloom shaders also compiled (a subset of {@link #isAvailable()}). */
    public boolean isBloomAvailable() { return bloomOk; }

    /** Diagnostic: the compile/link log if {@link #isAvailable()} is false (empty otherwise). */
    public String failureLog() { return failureLog; }

    /**
     * Render the full-screen smooth-light pass into the currently-bound framebuffer. The caller is
     * responsible for binding the target FBO, setting the viewport, and clearing. This writes an RGBA
     * "darkness mask": alpha = remaining darkness (0 = fully lit, night alpha = unlit), rgb = the night
     * tint, so compositing it over the scene with normal alpha blending darkens unlit pixels and lets
     * lit pixels show through at natural brightness — same contract as the legacy mask, but per-pixel
     * smooth and HDR.
     *
     * @param screenW,screenH  target size in pixels (for aspect / pixel→uv mapping)
     * @param nightR,nightG,nightB  night tint (0..1)
     * @param darkness  base darkness alpha (0..1) applied where no light reaches
     * @param lightCount  number of valid entries in the light arrays
     * @param lx,ly  light centers in SCREEN pixels (top-left origin, y-down — matching game coords)
     * @param lradius  light radius in screen pixels
     * @param lr,lg,lb  per-light color (0..1)
     * @param lintensity  per-light strength 0..1 (how strongly it lifts the darkness)
     */
    public void renderLightMask(int screenW, int screenH,
                                float nightR, float nightG, float nightB, float darkness,
                                int lightCount,
                                float[] lx, float[] ly, float[] lwx, float[] lwy, float[] lradius,
                                float[] lr, float[] lg, float[] lb, float[] lintensity,
                                com.badlogic.gdx.graphics.Texture occluders, boolean shadows,
                                boolean cheap) {
        if (!available) return;
        int n = Math.min(lightCount, MAX_LIGHTS);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ZERO); // this pass WRITES the mask; no blending needed

        boolean castShadows = shadows && occluders != null;
        if (castShadows) {
            occluders.bind(1);                       // texture unit 1
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0); // leave unit 0 active for libGDX's next batch
        }

        // MED/mobile asks for the cheap variant; if it didn't compile, the full shader still runs.
        ShaderProgram prog = (cheap && lightShaderCheap != null) ? lightShaderCheap : lightShader;
        prog.bind();
        prog.setUniformf("u_resolution", screenW, screenH);
        prog.setUniformf("u_night", nightR, nightG, nightB);
        prog.setUniformf("u_darkness", darkness);
        prog.setUniformi("u_lightCount", n);
        prog.setUniformi("u_shadows", castShadows ? 1 : 0);
        prog.setUniformi("u_occluders", 1); // sampler on unit 1
        // F6 (LightDebug.freezeTime) freezes the shimmer/breathe/flicker animation; F7 (noDetail)
        // zeroes the noise/ripple term entirely — both isolate "the light LOOKS like it moves"
        // from "the light IS mispositioned" during the dancing-lights hunt.
        prog.setUniformf("u_time", (FREEZE_TIME || LightDebug.freezeTime) ? 0f : time);
        prog.setUniformf("u_detail", LightDebug.noDetail ? 0f : 1f);
        for (int i = 0; i < n; i++) {
            prog.setUniformf("u_lightPos[" + i + "]", lx[i], ly[i]);
            prog.setUniformf("u_lightWorld[" + i + "]", lwx[i], lwy[i]);
            prog.setUniformf("u_lightRadius[" + i + "]", lradius[i]);
            prog.setUniformf("u_lightColor[" + i + "]", lr[i], lg[i], lb[i]);
            prog.setUniformf("u_lightIntensity[" + i + "]", lintensity[i]);
        }
        fullscreenQuad.render(prog, GL20.GL_TRIANGLES);

        // Cache for renderRim (same-frame reuse).
        cN = n; cW = screenW; cH = screenH;
        for (int i = 0; i < n; i++) {
            cLx[i] = lx[i]; cLy[i] = ly[i]; cLr[i] = lradius[i];
            cCr[i] = lr[i]; cCg[i] = lg[i]; cCb[i] = lb[i]; cLi[i] = lintensity[i];
        }
    }

    /**
     * Per-sprite rim-light pass. Reads the scene color + occluder silhouette mask and additively draws a
     * warm rim along every sprite edge that faces a light, using the light state cached from the most
     * recent {@link #renderLightMask}. Draws additively onto the currently-bound framebuffer (the screen),
     * before bloom, so rims bloom too. No-op if bloom/post shaders are unavailable.
     *
     * @param sceneTex   the finished lit scene as a texture
     * @param occluders  the silhouette mask (alpha = sprite)
     * @param strength   global rim strength (0 disables)
     */
    public void renderRim(Texture sceneTex, Texture occluders, float strength) {
        if (!bloomOk || sceneTex == null || occluders == null || strength <= 0f || cN <= 0) return;

        sceneTex.bind(0);
        occluders.bind(1);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE); // additive rim

        rimShader.bind();
        rimShader.setUniformi("u_scene", 0);
        rimShader.setUniformi("u_occluders", 1);
        rimShader.setUniformf("u_resolution", cW, cH);
        rimShader.setUniformf("u_texel", 1f / cW, 1f / cH);
        rimShader.setUniformi("u_lightCount", cN);
        rimShader.setUniformf("u_strength", strength);
        for (int i = 0; i < cN; i++) {
            rimShader.setUniformf("u_lightPos[" + i + "]", cLx[i], cLy[i]);
            rimShader.setUniformf("u_lightRadius[" + i + "]", cLr[i]);
            rimShader.setUniformf("u_lightColor[" + i + "]", cCr[i], cCg[i], cCb[i]);
            rimShader.setUniformf("u_lightIntensity[" + i + "]", cLi[i]);
        }
        fullscreenQuad.render(rimShader, GL20.GL_TRIANGLES);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Run the bloom chain on {@code sceneTex} and additively composite the glow onto the currently
     * bound framebuffer (the screen). Bright-pass → horizontal blur → vertical blur at half resolution,
     * then an additive fullscreen draw. Caller must have the scene already drawn on screen; this only
     * ADDS the glow. No-op if bloom is unavailable.
     *
     * @param sceneTex   the finished scene as a texture (full-res)
     * @param screenW,screenH  screen size (for the additive combine viewport)
     * @param threshold  luminance above which pixels bloom (0..1; ~0.6 keeps only bright lights)
     * @param intensity  glow strength added back (0..~2)
     */
    public void renderBloom(Texture sceneTex, int screenW, int screenH, float threshold, float intensity) {
        if (!bloomOk || sceneTex == null) return;
        int bw = Math.max(1, screenW / 2), bh = Math.max(1, screenH / 2); // half-res glow
        if (bloomA == null || bloomW != bw || bloomH != bh) {
            if (bloomA != null) bloomA.dispose();
            if (bloomB != null) bloomB.dispose();
            bloomA = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
            bloomB = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
            bloomA.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            bloomB.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            bloomW = bw; bloomH = bh;
        }

        Gdx.gl.glDisable(GL20.GL_BLEND);

        // 1) Bright-pass: sceneTex → bloomA
        bloomA.begin();
        Gdx.gl.glViewport(0, 0, bw, bh);
        sceneTex.bind(0);
        bloomBright.bind();
        bloomBright.setUniformi("u_scene", 0);
        bloomBright.setUniformf("u_threshold", threshold);
        fullscreenQuad.render(bloomBright, GL20.GL_TRIANGLES);
        bloomA.end();

        // 2) Horizontal blur: bloomA → bloomB
        bloomB.begin();
        Gdx.gl.glViewport(0, 0, bw, bh);
        bloomA.getColorBufferTexture().bind(0);
        bloomBlur.bind();
        bloomBlur.setUniformi("u_tex", 0);
        bloomBlur.setUniformf("u_dir", 1f / bw, 0f);
        fullscreenQuad.render(bloomBlur, GL20.GL_TRIANGLES);
        bloomB.end();

        // 3) Vertical blur: bloomB → bloomA
        bloomA.begin();
        Gdx.gl.glViewport(0, 0, bw, bh);
        bloomB.getColorBufferTexture().bind(0);
        bloomBlur.bind();
        bloomBlur.setUniformi("u_tex", 0);
        bloomBlur.setUniformf("u_dir", 0f, 1f / bh);
        fullscreenQuad.render(bloomBlur, GL20.GL_TRIANGLES);
        bloomA.end();

        // 4) Additively combine the blurred glow onto the screen.
        Gdx.gl.glViewport(0, 0, screenW, screenH);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE); // additive
        bloomA.getColorBufferTexture().bind(0);
        bloomCombine.bind();
        bloomCombine.setUniformi("u_bloom", 0);
        bloomCombine.setUniformf("u_intensity", intensity);
        fullscreenQuad.render(bloomCombine, GL20.GL_TRIANGLES);
        // Restore normal alpha blending for whatever the caller draws next.
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Blit {@code sceneTex} to the currently-bound framebuffer through the color-grade shader (warm
     * split-tone + gentle contrast). Replaces a plain scene blit so the whole frame reads as one graded
     * image. No-op-safe: caller should only use this when {@link #isBloomAvailable()} (grade ships with
     * the bloom shader group). u_warmth 0 = passthrough.
     */
    public void renderGradedBlit(Texture sceneTex, float warmth) {
        if (!bloomOk || sceneTex == null) return;
        Gdx.gl.glDisable(GL20.GL_BLEND);
        sceneTex.bind(0);
        gradeShader.bind();
        gradeShader.setUniformi("u_scene", 0);
        gradeShader.setUniformf("u_warmth", warmth);
        fullscreenQuad.render(gradeShader, GL20.GL_TRIANGLES);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Load shader source from an asset path, falling back to a baked-in string if the file is missing or
     * unreadable. Tries the internal (working-dir) root first, then the classpath (bundled jar / dev
     * resources) — the same resolution the rest of the engine uses for {@code res/...} assets. Any failure
     * is non-fatal: we log and return {@code fallback}, so editing/removing a shader file can never break
     * the game.
     */
    private static String src(String path, String fallback) {
        try {
            com.badlogic.gdx.files.FileHandle fh = Gdx.files.internal(path);
            if (!fh.exists()) fh = Gdx.files.classpath("/" + path);
            if (fh.exists()) {
                String s = fh.readString("UTF-8");
                if (s != null && s.trim().length() > 0) return s;
            }
        } catch (Throwable t) {
            Gdx.app.error("ShaderPipeline", "shader file '" + path + "' unreadable — using baked-in source: " + t);
        }
        return fallback;
    }

    /**
     * Compile a shader from its FILE source (frag path + vert file source); if that fails to compile —
     * e.g. someone edited the .frag into invalid GLSL — retry once with the BAKED-IN fallback strings so
     * the game self-heals instead of losing lighting. Returns whichever program compiled (or the failed
     * file-based one if even the fallback fails, so the caller's isCompiled() check still reports it).
     */
    private static ShaderProgram compile(String vertFile, String vertBake, String fragPath, String fragBake) {
        String fragFile = src(fragPath, fragBake);
        ShaderProgram p = new ShaderProgram(vertFile, fragFile);
        if (p.isCompiled()) return p;
        // File source didn't compile — fall back to the baked-in source.
        Gdx.app.error("ShaderPipeline", "shader file '" + fragPath
            + "' compiled with errors — falling back to baked-in source. Log: " + p.getLog());
        ShaderProgram fb = new ShaderProgram(vertBake, fragBake);
        if (fb.isCompiled()) { p.dispose(); return fb; }
        fb.dispose();
        return p; // both failed; return the original so its log surfaces
    }

    private static Mesh buildFullscreenQuad() {
        // Two triangles covering clip space. attrs: position (x,y in [-1,1]) + uv (in [0,1]).
        Mesh mesh = new Mesh(true, 4, 6,
            new VertexAttribute(Usage.Position, 2, "a_position"),
            new VertexAttribute(Usage.TextureCoordinates, 2, "a_texCoord0"));
        mesh.setVertices(new float[] {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
             1f,  1f, 1f, 1f,
            -1f,  1f, 0f, 1f,
        });
        mesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
        return mesh;
    }

    public void dispose() {
        if (lightShader != null) lightShader.dispose();
        if (lightShaderCheap != null) lightShaderCheap.dispose();
        if (bloomBright != null) bloomBright.dispose();
        if (bloomBlur != null) bloomBlur.dispose();
        if (bloomCombine != null) bloomCombine.dispose();
        if (gradeShader != null) gradeShader.dispose();
        if (rimShader != null) rimShader.dispose();
        if (bloomA != null) bloomA.dispose();
        if (bloomB != null) bloomB.dispose();
        if (fullscreenQuad != null) fullscreenQuad.dispose();
    }
}

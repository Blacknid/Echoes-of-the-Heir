package gfx.shader;

/**
 * GLSL source for the lighting/bloom pipeline, kept as string constants so the shaders ship with the
 * class (no asset-loading failure mode) and compile identically on desktop LWJGL3 and Android GLES.
 *
 * <h2>Portability</h2>
 * Targets GLSL ES 1.00 / desktop GLSL 120 (the {@code #version} line is intentionally omitted so
 * libGDX's {@link com.badlogic.gdx.graphics.glutils.ShaderProgram} prepends the right header per
 * platform). We use only {@code attribute}/{@code varying}, no dynamic-length loops that some drivers
 * reject, the light loop runs to a COMPILE-TIME constant {@code MAX_LIGHTS} and breaks early at the
 * uniform {@code u_lightCount}, which every GLSL 1.00 driver accepts.
 */
final class ShaderSources {

    private ShaderSources() {}

    /** Shared fullscreen vertex shader: passes clip-space position through and forwards UV. */
    static final String FULLSCREEN_VERT =
        "attribute vec4 a_position;\n" +
        "attribute vec2 a_texCoord0;\n" +
        "varying vec2 v_uv;\n" +
        "void main() {\n" +
        "    v_uv = a_texCoord0;\n" +
        "    gl_Position = a_position;\n" +
        "}\n";

    // Keep in sync with ShaderPipeline.MAX_LIGHTS.
    private static final int MAX_LIGHTS = 32;

    /**
     * Smooth per-pixel lighting. Output is a PREMULTIPLIED darkness mask (rgb = night tint × alpha +
     * warm additive glow, a = remaining darkness), composited over the scene with
     * {@code glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)}: darkness multiplies the scene down, the glow
     * adds each light's warm color where it falls. The MED/mobile variant is built from this SAME source
     * by prepending {@code #define SHADOW_STEPS 12} + {@code #define CHEAP 1} (fewer shadow steps, no
 * organic noise), see ShaderPipeline.
     */
    static final String LIGHT_FRAG =
        "#ifdef GL_ES\n" +
        "precision highp float;\n" +
        "#endif\n" +
        "varying vec2 v_uv;\n" +
        "uniform vec2  u_resolution;\n" +
        "uniform vec3  u_night;\n" +
        "uniform float u_darkness;\n" +
        "uniform int   u_lightCount;\n" +
        "uniform vec2  u_lightPos[" + MAX_LIGHTS + "];\n" +
        "uniform vec2  u_lightWorld[" + MAX_LIGHTS + "];\n" + // light center in WORLD px, anchors noise to ground
        "uniform float u_lightRadius[" + MAX_LIGHTS + "];\n" +
        "uniform vec3  u_lightColor[" + MAX_LIGHTS + "];\n" +
        "uniform float u_lightIntensity[" + MAX_LIGHTS + "];\n" +
        "uniform sampler2D u_occluders;\n" +   // alpha = solid silhouette
        "uniform int   u_shadows;\n" +          // 1 = ray-march shadows, 0 = skip (MED tier)
        "uniform float u_time;\n" +             // seconds, for flicker/breathing/noise animation
        "uniform float u_detail;\n" +           // 1 = organic detail on, 0 = clean circle (debug F7)
        "\n" +
        // Cheap 2D value noise (hash + bilinear), gives the light pool an organic, textured falloff
        // instead of a sterile perfect circle. No texture lookup needed.
        "float hash(vec2 p) {\n" +
        "    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);\n" +
        "}\n" +
        "#ifndef CHEAP\n" +
        "float vnoise(vec2 p) {\n" +
        "    vec2 i = floor(p); vec2 f = fract(p);\n" +
        "    f = f * f * (3.0 - 2.0 * f);\n" +
        "    float a = hash(i);\n" +
        "    float b = hash(i + vec2(1.0, 0.0));\n" +
        "    float c = hash(i + vec2(0.0, 1.0));\n" +
        "    float d = hash(i + vec2(1.0, 1.0));\n" +
        "    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);\n" +
        "}\n" +
        "#endif\n" +
        "\n" +
        // SHADOW_STEPS is overridable: the MED/mobile variant prepends "#define SHADOW_STEPS 12".
        "#ifndef SHADOW_STEPS\n" +
        "#define SHADOW_STEPS 32\n" +
        "#endif\n" +
        "const float LIGHT_EXCLUDE = 0.10;\n" +
        "const float FRAG_EXCLUDE = 0.03;\n" +
        "\n" +
        // Hard-max + soft-average shadow march (mirrors res/shaders/light.frag): one solid wall on the
        // ray blocks decisively (readable shadows); the average term grades the penumbra edge.
        "float visibility(vec2 lightPos, vec2 frag, float distNorm) {\n" +
        "    vec2 uvL = vec2(lightPos.x, u_resolution.y - lightPos.y) / u_resolution;\n" +
        "    vec2 uvF = vec2(frag.x,     u_resolution.y - frag.y)     / u_resolution;\n" +
        "    float hard = 0.0;\n" +
        "    float soft = 0.0;\n" +
        "    float hitT = 1.0;\n" +
        "    for (int s = 1; s < SHADOW_STEPS; s++) {\n" +
        "        float t = float(s) / float(SHADOW_STEPS);\n" +
        "        if (t < LIGHT_EXCLUDE) continue;\n" +
        "        if (t > 1.0 - FRAG_EXCLUDE) break;\n" +
        // Eased edge on the Linear-filtered occluder → round silhouette corners, no hard tile squares.
        "        float occ = smoothstep(0.20, 0.85, texture2D(u_occluders, mix(uvL, uvF, t)).a);\n" +
        "        hard = max(hard, occ * smoothstep(LIGHT_EXCLUDE, 0.35, t));\n" +
        "        if (occ > 0.4 && t < hitT) hitT = t;\n" +
        "        soft += occ;\n" +
        "    }\n" +
        "    soft /= float(SHADOW_STEPS);\n" +
        "    float shade = clamp(hard * 0.80 + soft * 0.55, 0.0, 1.0);\n" +
        // Length taper: contact shadows stay deep; long stretched shadows melt ("oversized" fix).
        "    float behind = (1.0 - hitT) * (0.35 + 0.65 * distNorm);\n" +
        "    shade *= 1.0 - 0.70 * smoothstep(0.12, 0.80, behind);\n" +
        "    shade *= mix(1.0, 0.72, distNorm);\n" +
        "    shade = min(shade, 0.85);\n" +
        "    return 1.0 - shade;\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        // Fragment position in screen pixels, top-left origin (y-down) to match game coords.
        "    vec2 frag = vec2(v_uv.x, 1.0 - v_uv.y) * u_resolution;\n" +
        "    float lit = 0.0;\n" +          // accumulated 'how lit' (0..1+)
        "    vec3  lightTint = vec3(0.0);\n" +
        "    for (int i = 0; i < " + MAX_LIGHTS + "; i++) {\n" +
        "        if (i >= u_lightCount) break;\n" +
        "        float r = u_lightRadius[i];\n" +
        "        if (r <= 0.0) continue;\n" +
        "        vec2  toL = frag - u_lightPos[i];\n" +
        "        float d = length(toL) / r;\n" +
        "        if (d >= 1.0) continue;\n" +
        // Falloff: punchy hot core (biased) easing to 0 at the edge, a lamp, not a flat disc.
        "        float f = 1.0 - d;\n" +
        "        f = f * f * (3.0 - 2.0 * f);\n" +
        "        f = mix(f, f * f, 0.35);\n" +
        "        float core = 1.0 - smoothstep(0.0, 0.28, d);\n" +
        "        f += core * core * 0.25;\n" +
        "#ifndef CHEAP\n" +
        "        vec2 worldFrag = u_lightWorld[i] + toL;\n" + // noise in world space: texture stays on the ground
        "        float ang = atan(toL.y, toL.x);\n" +
        "        float n = vnoise(worldFrag * 0.016);\n" +
        "        float shimmer = 0.85 + 0.15 * sin(u_time * 0.7 + float(i) * 2.3);\n" + // amplitude only, no motion
        "        float ripple = 0.05 * sin(ang * 5.0 + u_time * 0.6);\n" +
        "        f *= 1.0 + ((n - 0.5) * 0.35 * d * shimmer + ripple * d) * u_detail;\n" +
        "#endif\n" +
        "        float ph = float(i) * 1.7;\n" +
        "        float breathe = 1.0 + 0.04 * sin(u_time * 1.3 + ph);\n" +
        "        float flick   = 1.0 + 0.020 * sin(u_time * 9.0 + ph * 3.1);\n" +
        "        f *= breathe * flick;\n" +
        "        f = clamp(f, 0.0, 1.1);\n" +
        "        float vis = 1.0;\n" +
        "        if (u_shadows == 1) vis = visibility(u_lightPos[i], frag, d);\n" +
        "        float contrib = f * u_lightIntensity[i] * vis;\n" +
        "        lit += contrib;\n" +
        "        vec3 warmCore = min(u_lightColor[i] + vec3(0.22, 0.12, 0.0), vec3(1.0));\n" +
        "        vec3 lc = mix(u_lightColor[i], warmCore, f * 0.85);\n" +
        "        lightTint += lc * contrib;\n" +
        "    }\n" +
        "    float litClamped = clamp(lit, 0.0, 1.0);\n" +
        "    float darkAlpha = u_darkness * (1.0 - litClamped);\n" +
        // Dither kills 8-bit banding rings in the slow radial gradient (visible on MED).
        "    darkAlpha = clamp(darkAlpha + (hash(frag * 0.7) - 0.5) / 128.0, 0.0, 1.0);\n" +
        "    vec3 tint = u_night;\n" +
        "    if (lit > 0.001) {\n" +
        "        vec3 lightHue = normalize(lightTint + 0.0001);\n" +
        "        tint = mix(u_night, lightHue, clamp(0.45 * lit, 0.0, 0.75));\n" +
        "    }\n" +
        // PREMULTIPLIED output (composite with GL_ONE, ONE_MINUS_SRC_ALPHA): darkness multiplies the
        // scene down, warmGlow ADDS the light's warm hue where it falls, tapered out of the overbright
        // core so the center doesn't blow out to white under bloom.
        "    vec3 warmGlow = normalize(lightTint + 0.0001) * litClamped * (0.14 * u_darkness);\n" +
        "    gl_FragColor = vec4(tint * darkAlpha + warmGlow, darkAlpha);\n" +
        "}\n";

    /**
 * Per-sprite RIM LIGHTING, screen-space, no hitboxes, no authoring. Reads the occluder mask (which
     * already holds every sprite's true silhouette alpha) and the scene color. At each pixel it computes
     * the silhouette's edge NORMAL from the alpha gradient (Sobel-ish), then for the nearest lights adds
 * a warm rim wherever that edge FACES a light, a bright kiss of light along the lit-facing contour of
     * characters, trees, objects. The rim is tinted by BOTH the light color and the sprite's own color at
     * that pixel, so it "works with the sprite and its colors" and never looks like a flat white outline.
     * Runs additively over the lit scene, before bloom, so rims bloom too. Purely GPU.
     */
    static final String RIM_FRAG =
        "#ifdef GL_ES\n" +
        "precision highp float;\n" +
        "#endif\n" +
        "varying vec2 v_uv;\n" +
        "uniform sampler2D u_scene;\n" +
        "uniform sampler2D u_occluders;\n" +
        "uniform vec2  u_resolution;\n" +
        "uniform vec2  u_texel;\n" +          // 1/resolution
        "uniform int   u_lightCount;\n" +
        "uniform vec2  u_lightPos[" + MAX_LIGHTS + "];\n" +
        "uniform float u_lightRadius[" + MAX_LIGHTS + "];\n" +
        "uniform vec3  u_lightColor[" + MAX_LIGHTS + "];\n" +
        "uniform float u_lightIntensity[" + MAX_LIGHTS + "];\n" +
        "uniform float u_strength;\n" +        // global rim strength
        "\n" +
        "float occ(vec2 uv) { return texture2D(u_occluders, uv).a; }\n" +
        "\n" +
        "void main() {\n" +
        "    float a = occ(v_uv);\n" +
        // Only sprite pixels get a rim. Skip empty space fast.
        "    if (a < 0.02) { gl_FragColor = vec4(0.0); return; }\n" +
        // Edge normal from the alpha gradient (points OUT of the silhouette, toward empty space).
        "    float ax = occ(v_uv + vec2(u_texel.x, 0.0)) - occ(v_uv - vec2(u_texel.x, 0.0));\n" +
        "    float ay = occ(v_uv + vec2(0.0, u_texel.y)) - occ(v_uv - vec2(0.0, u_texel.y));\n" +
        "    vec2 grad = vec2(ax, ay);\n" +
        "    float edge = length(grad);\n" +
        // Interior (flat alpha) has ~zero gradient → no rim; only the silhouette contour lights up.
        "    if (edge < 0.02) { gl_FragColor = vec4(0.0); return; }\n" +
        "    vec2 normal = normalize(grad);\n" +          // screen-space, y-down UV
        // Fragment position in screen pixels (top-left origin) to match light positions.
        "    vec2 frag = vec2(v_uv.x, 1.0 - v_uv.y) * u_resolution;\n" +
        "    vec3 sceneCol = texture2D(u_scene, v_uv).rgb;\n" +
        "    vec3 rim = vec3(0.0);\n" +
        "    for (int i = 0; i < " + MAX_LIGHTS + "; i++) {\n" +
        "        if (i >= u_lightCount) break;\n" +
        "        float r = u_lightRadius[i];\n" +
        "        if (r <= 0.0) continue;\n" +
        "        vec2 toLight = u_lightPos[i] - frag;\n" +
        "        float dist = length(toLight);\n" +
        "        if (dist > r) continue;\n" +
        "        vec2 Ldir = toLight / max(dist, 0.001);\n" +
        // UV normal is y-down; light dir is y-down screen too, but UV y is flipped vs screen y, so flip
        // the normal's y to compare in the same space.
        "        vec2 nScreen = vec2(normal.x, -normal.y);\n" +
        "        float facing = max(0.0, dot(nScreen, Ldir));\n" +   // edge faces the light?
        "        float atten = 1.0 - dist / r;\n" +
        "        atten *= atten;\n" +
        // A light sitting basically ON this sprite (the carried player light) has no rim DIRECTION, every
        // edge faces it, so it would trace a flat outline. Fade rim in only once the light is a bit away,
        // so rim comes from OFFSET lights (torches, crystals) as a true directional highlight.
        "        float dirWeight = smoothstep(0.10, 0.35, dist / r);\n" +
        // rim = edge sharpness * facing^2 (tight highlight) * distance falloff * intensity * direction.
        "        float k = edge * facing * facing * atten * u_lightIntensity[i] * dirWeight;\n" +
        "        rim += u_lightColor[i] * k;\n" +
        "    }\n" +
        // Tint the rim by the sprite's own color (so a red cloak gets a warm-red rim, not white), lifted
        // toward the light color. Multiply by silhouette alpha so semi-transparent edges rim softly.
        "    vec3 spriteTint = sceneCol * 0.6 + 0.4;\n" +
        "    vec3 outRim = rim * spriteTint * a * u_strength;\n" +
        "    gl_FragColor = vec4(outRim, 1.0);\n" +
        "}\n";

    // ── Bloom (bright-pass → separable Gaussian blur → additive combine) ───────

    /** Extract bright pixels: keep only luminance above a threshold, softened, for the glow source. */
    static final String BLOOM_BRIGHT_FRAG =
        "#ifdef GL_ES\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "varying vec2 v_uv;\n" +
        "uniform sampler2D u_scene;\n" +
        "uniform float u_threshold;\n" +   // luminance below this contributes no bloom
        "void main() {\n" +
        "    vec3 c = texture2D(u_scene, v_uv).rgb;\n" +
        "    float lum = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "    float k = max(0.0, lum - u_threshold) / max(0.0001, 1.0 - u_threshold);\n" +
        "    gl_FragColor = vec4(c * k, 1.0);\n" +
        "}\n";

    /**
     * One axis of a separable Gaussian blur. u_dir is (texelW,0) for horizontal, (0,texelH) for
 * vertical. 9-tap kernel, wide enough for a soft glow, cheap enough for two passes per frame.
     */
    static final String BLOOM_BLUR_FRAG =
        "#ifdef GL_ES\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "varying vec2 v_uv;\n" +
        "uniform sampler2D u_tex;\n" +
        "uniform vec2 u_dir;\n" +
        "void main() {\n" +
        "    vec3 sum = vec3(0.0);\n" +
        // Tighter kernel (was +/-4 taps): narrower glow radius overall, per user request to reduce blur.
        "    sum += texture2D(u_tex, v_uv + u_dir * -2.5).rgb * 0.0540;\n" +
        "    sum += texture2D(u_tex, v_uv + u_dir * -1.5).rgb * 0.1216;\n" +
        "    sum += texture2D(u_tex, v_uv + u_dir * -0.5).rgb * 0.1946;\n" +
        "    sum += texture2D(u_tex, v_uv + u_dir *  0.5).rgb * 0.1946;\n" +
        "    sum += texture2D(u_tex, v_uv + u_dir *  1.5).rgb * 0.1216;\n" +
        "    sum += texture2D(u_tex, v_uv + u_dir *  2.5).rgb * 0.0540;\n" +
        "    sum /= 0.6404;\n" +
        "    gl_FragColor = vec4(sum, 1.0);\n" +
        "}\n";

    /**
     * Final color grade applied when blitting the captured scene back to screen: a gentle filmic S-curve
     * for contrast, a warm highlight lift + cool shadow tint for a cohesive cinematic tone, and a touch
 * of saturation. Keeps the pixel art readable, subtle, not an Instagram filter. u_warmth scales the
     * whole effect (0 = passthrough).
     */
    static final String GRADE_FRAG =
        "#ifdef GL_ES\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "varying vec2 v_uv;\n" +
        "uniform sampler2D u_scene;\n" +
        "uniform float u_warmth;\n" +
        "void main() {\n" +
        "    vec3 c = texture2D(u_scene, v_uv).rgb;\n" +
        "    float lum = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        // filmic-ish S-curve: deepen shadows, lift a touch, smoothstep gives a gentle contrast bump.
        "    vec3 curved = mix(c, smoothstep(0.0, 1.0, c), 0.35);\n" +
        // warm highlights, cool shadows (split-tone), scaled by luminance and warmth.
        "    vec3 warm = vec3(1.06, 1.0, 0.92);\n" +
        "    vec3 cool = vec3(0.94, 0.98, 1.08);\n" +
        "    vec3 tone = mix(cool, warm, lum);\n" +
        "    vec3 graded = curved * mix(vec3(1.0), tone, u_warmth);\n" +
        // slight saturation lift for richer color.
        "    graded = mix(vec3(lum), graded, 1.0 + 0.12 * u_warmth);\n" +
        "    gl_FragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);\n" +
        "}\n";

    /** Additively combine the blurred bloom over the scene already on screen (drawn with additive blend). */
    static final String BLOOM_COMBINE_FRAG =
        "#ifdef GL_ES\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "varying vec2 v_uv;\n" +
        "uniform sampler2D u_bloom;\n" +
        "uniform float u_intensity;\n" +
        "void main() {\n" +
        "    vec3 b = texture2D(u_bloom, v_uv).rgb * u_intensity;\n" +
        "    gl_FragColor = vec4(b, 1.0);\n" +
        "}\n";
}

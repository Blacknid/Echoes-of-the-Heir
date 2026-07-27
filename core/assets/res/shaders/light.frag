// Smooth per-pixel 2D lighting with ray-marched texture shadows + warm additive glow.
//
// OUTPUT IS PREMULTIPLIED: rgb = nightTint * darkAlpha + warmGlow, a = darkAlpha.
// Composite over the scene with glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA):
//   • darkAlpha multiplies the scene down where no light reaches (the darkness),
//   • warmGlow is ADDED where light falls, so pools of light glow warm instead of
//     merely revealing the scene at its neutral color.
//
// Shadows are a CONSEQUENCE of light: each fragment marches the segment from every light
// to itself through the occluder silhouette mask (sprite/tile texture alpha). Anything the
// ray hits blocks the light and leaves a soft-edged shadow behind it. No shadow objects.
//
// Tier variants (compiled from this ONE source by ShaderPipeline):
//   HIGH — as-is: 32 shadow steps + organic noise/ripple/flicker detail.
//   MED  — prepends "#define SHADOW_STEPS 12" and "#define CHEAP 1": fewer steps, no noise;
//          light + real shadows still run on mobile-class GPUs.
// MAX_LIGHTS must match ShaderPipeline.MAX_LIGHTS.
// PRECISION — highp is OPTIONAL in a GLSL ES 1.00 fragment shader. Requesting it unconditionally on a
// device whose driver lacks it is a COMPILE ERROR, which took the whole light pipeline down to the
// baked fallback on some Android GPUs (the "no lighting at all on mobile" report). GL_FRAGMENT_PRECISION_HIGH
// is defined by the driver exactly when highp is available, so this picks highp where it exists and
// mediump where it doesn't, and compiles everywhere. The math below is written to survive mediump:
// no normalize() of a near-zero vector, no divide by an unguarded length.
#ifdef GL_ES
  #ifdef GL_FRAGMENT_PRECISION_HIGH
    precision highp float;
  #else
    precision mediump float;
  #endif
#endif

// Must match ShaderPipeline.MAX_LIGHTS. Kept modest: GLSL ES 1.00 guarantees only 16 fragment uniform
// vectors beyond the minimum, and 32 lights × 4 arrays is already a large uniform footprint on low-end
// mobile GPUs. The loop below is bounded by this constant (required — GLSL ES 1.00 needs a
// compile-time-constant loop bound) and breaks early at u_lightCount.
#define MAX_LIGHTS 32

#ifndef SHADOW_STEPS
#define SHADOW_STEPS 32
#endif

varying vec2 v_uv;
uniform vec2  u_resolution;
uniform vec3  u_night;
uniform float u_darkness;
uniform int   u_lightCount;
uniform vec2  u_lightPos[MAX_LIGHTS];
uniform vec2  u_lightWorld[MAX_LIGHTS]; // light center in WORLD px — anchors the organic noise texture
                                        // to the ground so it doesn't scroll with the camera as you walk
uniform float u_lightRadius[MAX_LIGHTS];
uniform vec3  u_lightColor[MAX_LIGHTS];
uniform float u_lightIntensity[MAX_LIGHTS];
uniform sampler2D u_occluders;   // alpha = solid silhouette
uniform int   u_shadows;         // 1 = ray-march shadows, 0 = skip
uniform float u_time;            // seconds, for flicker/breathing/noise animation
uniform float u_detail;          // 1 = organic noise/ripple detail on, 0 = clean circle (debug F7)

// Cheap 2D value noise (hash + bilinear) — gives the light pool an organic, textured falloff
// instead of a sterile perfect circle. No texture lookup needed.
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
#ifndef CHEAP
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
#endif

// Skip occluders within this normalized ray-length of the light (kills self-shadow near a caster).
const float LIGHT_EXCLUDE = 0.10;
// Skip the sliver next to the fragment so a caster doesn't hard-shadow the ground at its own feet.
const float FRAG_EXCLUDE = 0.03;

// Soft visibility of `frag` from `lightPos`, in [0,1]. Marches the light→fragment segment through
// the occluder mask. Two terms combine:
//   • hard — the MAX occlusion seen along the ray. One solid wall anywhere on the path blocks the
//     light decisively, so shadows behind walls are deep and readable (the old averaging term let a
//     thin wall contribute only a few of 32 samples → shadows were washed-out streaks).
//   • soft — the AVERAGE occlusion, which grows with blocker thickness. Added on top it grades the
//     penumbra: grazing rays that clip a corner get a light shadow, buried ones go dark.
// The hard term ramps in over the first third of the ray (smoothstep on t) so a blocker hugging the
// light itself casts a softer, wider penumbra than one right next to the fragment — the cheap-2D
// stand-in for a real area-light penumbra. Shadows also ease off for far-away fragments (they're dim
// anyway; keeping their shadows soft avoids harsh black walls at the pool edge), and never reach
// pure black so the scene keeps its moody depth.
float visibility(vec2 lightPos, vec2 frag, float distNorm) {
    vec2 uvL = vec2(lightPos.x, u_resolution.y - lightPos.y) / u_resolution;
    vec2 uvF = vec2(frag.x,     u_resolution.y - frag.y)     / u_resolution;
    float hard = 0.0;
    float soft = 0.0;
    float taken = 0.0;  // steps actually sampled (the loop skips the excluded head and tail)
    float hitT = 1.0;   // ray position of the first solid hit (1.0 = ray reached the fragment clean)
    for (int s = 1; s < SHADOW_STEPS; s++) {
        float t = float(s) / float(SHADOW_STEPS);
        if (t < LIGHT_EXCLUDE) continue;
        if (t > 1.0 - FRAG_EXCLUDE) break;
        // The occluder texture is Linear-filtered; easing its edge alpha ROUNDS the silhouette —
        // square tile corners melt into curves instead of stamping hard right angles into the shadow.
        float occ = smoothstep(0.20, 0.85, texture2D(u_occluders, mix(uvL, uvF, t)).a);
        hard = max(hard, occ * smoothstep(LIGHT_EXCLUDE, 0.35, t));
        if (occ > 0.4 && t < hitT) hitT = t;
        soft += occ;
        taken += 1.0;
    }
    // Normalize by the steps actually TAKEN, not the nominal count: the loop skips the excluded head
    // and tail, so HIGH (32) samples ~28 and MED (12) ~10. Dividing each by its own full count made
    // `soft` mean slightly different things per tier; dividing by `taken` makes it a true occluded
    // FRACTION of the marched span on both.
    soft /= max(taken, 1.0);
    // SHADE RESPONSE — the two terms must SHARE the 0..1 range, not both try to fill it.
    // Previously this was `hard * 0.80 + soft * 0.55`, which sums to 1.35: the moment anything solid
    // sat on the ray `hard` went to 1 and spent 0.80 of the budget on its own, leaving 0.20 of
    // headroom, so the sum clamped to 1.0 at only ~37% occlusion. Every shadow past that threshold
    // came out byte-for-byte identical — flat, uniformly dark, hard-edged. That is the "shadows don't
    // work properly on HIGH" symptom, and it is worst on HIGH precisely because 28 samples resolve a
    // fine `soft` gradient that the clamp then threw away.
    // Now `hard` sets a floor (a definite blocker always reads as a real shadow) and `soft` grades the
    // remaining range by how MUCH of the ray was blocked, so thin/grazing occluders give light
    // penumbra and deeply-buried fragments go dark, monotonically, with no clamping in normal use.
    float shade = hard * (0.35 + 0.65 * soft);
    // SHADOW LENGTH TAPER — shadows are anchored to their caster, not stamped across the whole pool.
    // `behind` = how far past its blocker this fragment sits (0 = contact, →1 = a long stretched
    // shadow), scaled by distance so short rays keep their full shadow. Contact shadows stay deep
    // and readable; as a shadow reaches away from its caster the light wraps around and it melts —
    // soft round pools of shade instead of oversized full-length streaks.
    float behind = (1.0 - hitT) * (0.35 + 0.65 * distNorm);
    shade *= 1.0 - 0.70 * smoothstep(0.12, 0.80, behind);
    // Distance-aware softening + cap below 1: deep shadows, never absolute black.
    shade *= mix(1.0, 0.72, distNorm);
    shade = min(shade, 0.85);
    return 1.0 - shade;
}

void main() {
    // Fragment position in screen pixels, top-left origin (y-down) to match game coords.
    vec2 frag = vec2(v_uv.x, 1.0 - v_uv.y) * u_resolution;
    float lit = 0.0;                  // accumulated 'how lit' (0..1+)
    vec3  lightTint = vec3(0.0);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= u_lightCount) break;
        float r = u_lightRadius[i];
        if (r <= 0.0) continue;
        vec2  toL = frag - u_lightPos[i];
        float d = length(toL) / r;
        if (d >= 1.0) continue;
        // Falloff: bright, smooth plateau near the core easing to 0 at the edge. Using an inverse-square
        // -ish curve (not a linear ramp) gives a punchy hot core with a long gentle tail — the shape of
        // a real lamp, and the part that makes light read as "glowing" rather than a flat disc.
        float f = 1.0 - d;
        f = f * f * (3.0 - 2.0 * f);      // smoothstep body
        f = mix(f, f * f, 0.35);          // bias toward a brighter, tighter core
        // INCANDESCENT HEART: add a tight extra hotspot near the very center so the light has a glowing
        // core that blooms — the difference between "a lit area" and "a beautiful glowing lamp". Kept
        // modest so a single light (player or NPC) reads as gently lit rather than blown out, and so two
        // overlapping lights (player walking up to an NPC) don't sum past full brightness over a wide area.
        float core = 1.0 - smoothstep(0.0, 0.28, d);
        f += core * core * 0.25;
#ifndef CHEAP
        // ORGANIC DETAIL: break the perfect circle with value noise + a faint angular ripple (weighted
        // by d so the bright core stays clean). The noise is sampled in WORLD space (light's world center
        // + the fragment's offset from the light), so the textured falloff is painted onto the GROUND and
        // stays locked to the world as the camera scrolls. A slow u_time term modulates only the noise
        // AMPLITUDE so the pool shimmers softly in place without translating.
        vec2 worldFrag = u_lightWorld[i] + toL;   // toL is identical in screen & world (camera = translate)
        float ang = atan(toL.y, toL.x);
        float n = vnoise(worldFrag * 0.016);
        float shimmer = 0.85 + 0.15 * sin(u_time * 0.7 + float(i) * 2.3); // amplitude breathing, no motion
        float ripple = 0.05 * sin(ang * 5.0 + u_time * 0.6);
        f *= 1.0 + ((n - 0.5) * 0.35 * d * shimmer + ripple * d) * u_detail;
#endif
        // FLICKER/BREATHING per light (centered on 1 so no net dim). Subtle — a candle's life, not a strobe.
        float ph = float(i) * 1.7;
        float breathe = 1.0 + 0.04 * sin(u_time * 1.3 + ph);
        float flick   = 1.0 + 0.020 * sin(u_time * 9.0 + ph * 3.1);
        f *= breathe * flick;
        // Capped tighter than before (was 1.5): less HDR headroom per light means overlapping lights
        // (player standing next to an NPC) sum to a lower peak instead of both blowing past full bright.
        f = clamp(f, 0.0, 1.1);
        // shadow attenuation (soft, distance-aware).
        float vis = 1.0;
        if (u_shadows == 1) vis = visibility(u_lightPos[i], frag, d);
        float contrib = f * u_lightIntensity[i] * vis;
        lit += contrib;
        // WARM CORE -> COOLER RIM: a hot gold heart bleeding to the light's base color, so every lamp
        // has an incandescent centre. Mix toward warm as the core brightens.
        vec3 warmCore = min(u_lightColor[i] + vec3(0.22, 0.12, 0.0), vec3(1.0));
        vec3 lc = mix(u_lightColor[i], warmCore, f * 0.85);
        lightTint += lc * contrib;
    }
    float litClamped = clamp(lit, 0.0, 1.0);
    // Darkness remaining: HDR-ish — allow a touch of over-bright at light cores (lit can exceed 1) so
    // the brightest pools fully clear the darkness and feed the bloom pass for a glow.
    float darkAlpha = u_darkness * (1.0 - litClamped);
    // DITHER: ±0.5/255 of ordered-noise on the darkness alpha breaks the 8-bit banding rings that an
    // RGBA8888 mask shows across a slow radial gradient (the MED-tier "mid-radius ring").
    darkAlpha = clamp(darkAlpha + (hash(frag * 0.7) - 0.5) / 128.0, 0.0, 1.0);
    // HUE EXTRACTION — computed ONCE, guarded against the zero vector.
    // `normalize(lightTint + 0.0001)` was the single most dangerous line in this shader. When a
    // fragment is barely lit, lightTint tends to (0,0,0); adding 0.0001 to each channel gives a vector
    // of length ~1.7e-4, and normalize() divides by it. In mediump — which is what most Android GLES
    // drivers actually give a fragment shader regardless of the highp request, since highp support is
    // optional in GLSL ES 1.00 — that length UNDERFLOWS to exactly 0 and the divide yields Inf/NaN.
    // A NaN here propagates through gl_FragColor into the light mask, and because that mask is then
    // blended over the scene AND fed to the bloom bright-pass, one NaN texel smears into a flickering
    // white blotch. Adding a scalar epsilon to the LENGTH instead of the vector removes the singularity
    // entirely while leaving the hue unchanged everywhere it was already well-defined.
    // This is the medium/high-tier "broken lighting": it needs several lights close together (the
    // Phantom arena's torches plus the player light) to push enough near-zero fragments through the
    // divide for the artifact to become constant rather than a rare sparkle.
    float tintLen = length(lightTint);
    vec3  lightHue = lightTint / max(tintLen, 1e-4);
    // Colored light tint blended into the night, weighted by how lit the pixel is. Using the hue keeps
    // the color while the alpha carries brightness, so colored lamps tint their pool without graying out.
    vec3 tint = u_night;
    if (tintLen > 1e-4) {
        tint = mix(u_night, lightHue, clamp(0.45 * lit, 0.0, 0.75));
    }
    // WARM GLOW (premultiplied add): pour the light's warm HUE back onto the scene where light falls.
    // Hue × a modest scalar — NOT the raw accumulated magnitude (which reached ~0.35/channel at the
    // core and, stacked with bloom, blew the player out to a white ball). Monotonic in litClamped
    // (a mid-radius taper reads as an ugly glow donut). Scaled by u_darkness so the wash only exists
    // when it's actually dark (no daytime tinting).
    vec3 warmGlow = lightHue * litClamped * (0.14 * u_darkness);
    // Final guard: clamp to a sane range so no single bad texel can ever reach the bloom bright-pass
    // as an out-of-range or negative value. Costs nothing and makes the mask total-garbage-proof.
    vec3 outRgb = clamp(tint * darkAlpha + warmGlow, 0.0, 4.0);
    gl_FragColor = vec4(outRgb, darkAlpha);
}

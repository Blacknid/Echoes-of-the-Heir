// Per-sprite RIM LIGHTING — screen-space, no hitboxes. Reads the occluder silhouette mask + scene
// color, computes each silhouette edge normal from the alpha gradient, and adds a warm rim where that
// edge faces a light, tinted by both the light color and the sprite's own color. Additive, before bloom.
#ifdef GL_ES
precision highp float;
#endif

#define MAX_LIGHTS 20

varying vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_occluders;
uniform vec2  u_resolution;
uniform vec2  u_texel;           // 1/resolution
uniform int   u_lightCount;
uniform vec2  u_lightPos[MAX_LIGHTS];
uniform float u_lightRadius[MAX_LIGHTS];
uniform vec3  u_lightColor[MAX_LIGHTS];
uniform float u_lightIntensity[MAX_LIGHTS];
uniform float u_strength;        // global rim strength

float occ(vec2 uv) { return texture2D(u_occluders, uv).a; }

void main() {
    float a = occ(v_uv);
    if (a < 0.02) { gl_FragColor = vec4(0.0); return; }
    // Edge normal from the alpha gradient (points OUT of the silhouette).
    float ax = occ(v_uv + vec2(u_texel.x, 0.0)) - occ(v_uv - vec2(u_texel.x, 0.0));
    float ay = occ(v_uv + vec2(0.0, u_texel.y)) - occ(v_uv - vec2(0.0, u_texel.y));
    vec2 grad = vec2(ax, ay);
    float edge = length(grad);
    if (edge < 0.02) { gl_FragColor = vec4(0.0); return; }
    vec2 normal = normalize(grad);
    vec2 frag = vec2(v_uv.x, 1.0 - v_uv.y) * u_resolution;
    vec3 sceneCol = texture2D(u_scene, v_uv).rgb;
    vec3 rim = vec3(0.0);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= u_lightCount) break;
        float r = u_lightRadius[i];
        if (r <= 0.0) continue;
        vec2 toLight = u_lightPos[i] - frag;
        float dist = length(toLight);
        if (dist > r) continue;
        vec2 Ldir = toLight / max(dist, 0.001);
        // UV y is flipped vs screen y, so flip the normal's y to compare in screen space.
        vec2 nScreen = vec2(normal.x, -normal.y);
        float facing = max(0.0, dot(nScreen, Ldir));
        float atten = 1.0 - dist / r;
        atten *= atten;
        // Fade rim in only once the light is a bit away, so a light AT the sprite (carried player
        // light) doesn't trace a flat outline — rim comes from OFFSET lights as a directional highlight.
        float dirWeight = smoothstep(0.10, 0.35, dist / r);
        float k = edge * facing * facing * atten * u_lightIntensity[i] * dirWeight;
        rim += u_lightColor[i] * k;
    }
    // Tint the rim by the sprite's own color (a red cloak -> warm-red rim, not white).
    vec3 spriteTint = sceneCol * 0.6 + 0.4;
    vec3 outRim = rim * spriteTint * a * u_strength;
    gl_FragColor = vec4(outRim, 1.0);
}

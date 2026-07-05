// Final color grade for the captured scene: gentle filmic S-curve contrast, warm-highlight/cool-shadow
// split-tone, and a touch of saturation. u_warmth scales the whole effect (0 = passthrough).
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_scene;
uniform float u_warmth;
void main() {
    vec3 c = texture2D(u_scene, v_uv).rgb;
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 curved = mix(c, smoothstep(0.0, 1.0, c), 0.35);
    vec3 warm = vec3(1.06, 1.0, 0.92);
    vec3 cool = vec3(0.94, 0.98, 1.08);
    vec3 tone = mix(cool, warm, lum);
    vec3 graded = curved * mix(vec3(1.0), tone, u_warmth);
    graded = mix(vec3(lum), graded, 1.0 + 0.12 * u_warmth);
    gl_FragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}

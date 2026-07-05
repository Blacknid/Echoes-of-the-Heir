// Bloom bright-pass: keep only luminance above a threshold, softened, as the glow source.
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_scene;
uniform float u_threshold;   // luminance below this contributes no bloom
void main() {
    vec3 c = texture2D(u_scene, v_uv).rgb;
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    float k = max(0.0, lum - u_threshold) / max(0.0001, 1.0 - u_threshold);
    gl_FragColor = vec4(c * k, 1.0);
}

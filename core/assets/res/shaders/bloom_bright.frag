// Bloom bright-pass: keep only luminance above a threshold, softened, as the glow source.
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_scene;
uniform float u_threshold;   // luminance below this contributes no bloom
void main() {
    // Clamp the incoming scene BEFORE any arithmetic. This is the gate that keeps a single bad texel
    // from becoming a screen-wide artifact: the blur passes that follow are wide separable kernels, so
    // one Inf/NaN pixel here would spread across ~9 texels per pass and then get added over the whole
    // frame. Anything non-finite fails both sides of a clamp and resolves to the low bound, so this
    // also acts as a NaN scrub. Cheap insurance on a pass that is already texture-bandwidth-bound.
    vec3 c = clamp(texture2D(u_scene, v_uv).rgb, 0.0, 4.0);
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    float k = max(0.0, lum - u_threshold) / max(0.0001, 1.0 - u_threshold);
    gl_FragColor = vec4(c * k, 1.0);
}

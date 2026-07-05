// Additively combine the blurred bloom over the scene already on screen (draw with additive blend).
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_bloom;
uniform float u_intensity;
void main() {
    vec3 b = texture2D(u_bloom, v_uv).rgb * u_intensity;
    gl_FragColor = vec4(b, 1.0);
}

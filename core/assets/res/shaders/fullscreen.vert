// Shared fullscreen vertex shader: passes clip-space position through and forwards UV.
// No #version line — libGDX prepends the right header per platform (GLSL ES 1.00 / desktop 120).
attribute vec4 a_position;
attribute vec2 a_texCoord0;
varying vec2 v_uv;
void main() {
    v_uv = a_texCoord0;
    gl_Position = a_position;
}

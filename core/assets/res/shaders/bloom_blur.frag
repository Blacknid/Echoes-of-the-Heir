// One axis of a separable 6-tap Gaussian blur (tighter radius, was 9-tap +/-4). u_dir = (texelW,0)
// horizontal, (0,texelH) vertical.
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_dir;
void main() {
    vec3 sum = vec3(0.0);
    sum += texture2D(u_tex, v_uv + u_dir * -2.5).rgb * 0.0540;
    sum += texture2D(u_tex, v_uv + u_dir * -1.5).rgb * 0.1216;
    sum += texture2D(u_tex, v_uv + u_dir * -0.5).rgb * 0.1946;
    sum += texture2D(u_tex, v_uv + u_dir *  0.5).rgb * 0.1946;
    sum += texture2D(u_tex, v_uv + u_dir *  1.5).rgb * 0.1216;
    sum += texture2D(u_tex, v_uv + u_dir *  2.5).rgb * 0.0540;
    sum /= 0.6404;
    gl_FragColor = vec4(sum, 1.0);
}

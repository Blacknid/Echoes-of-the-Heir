// One axis of a separable 9-tap Gaussian blur. u_dir = (texelW,0) horizontal, (0,texelH) vertical.
#ifdef GL_ES
precision mediump float;
#endif
varying vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_dir;
void main() {
    vec3 sum = vec3(0.0);
    sum += texture2D(u_tex, v_uv + u_dir * -4.0).rgb * 0.0162;
    sum += texture2D(u_tex, v_uv + u_dir * -3.0).rgb * 0.0540;
    sum += texture2D(u_tex, v_uv + u_dir * -2.0).rgb * 0.1216;
    sum += texture2D(u_tex, v_uv + u_dir * -1.0).rgb * 0.1946;
    sum += texture2D(u_tex, v_uv).rgb                * 0.2270;
    sum += texture2D(u_tex, v_uv + u_dir *  1.0).rgb * 0.1946;
    sum += texture2D(u_tex, v_uv + u_dir *  2.0).rgb * 0.1216;
    sum += texture2D(u_tex, v_uv + u_dir *  3.0).rgb * 0.0540;
    sum += texture2D(u_tex, v_uv + u_dir *  4.0).rgb * 0.0162;
    gl_FragColor = vec4(sum, 1.0);
}

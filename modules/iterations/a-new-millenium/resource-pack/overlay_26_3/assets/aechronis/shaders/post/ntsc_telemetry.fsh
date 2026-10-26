#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <aechronis:ntsc.glsl>

uniform sampler2D TelemPrevSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// One-pixel exponential smoother for the decoded telemetry, persisted
// across frames (telem / telem_prev ping-pong) so OSD numbers and the
// signal-path degradation glide between the 20-step quantized control-word
// levels instead of flicking. Speed keeps 16 bits of precision (B = high
// byte, A = low byte) so the km/h readout sits steady between levels.
// Values hold during "special" (no video) rather than chasing the
// reserved control-word range.

const float ALPHA = 0.12;

void main() {
    FpvState st = decodeFpv(GameTime);
    vec4 prev = texelFetch(TelemPrevSampler, ivec2(0, 0), 0);
    vec3 prevVal = vec3(prev.r, prev.g, prev.b + prev.a / 255.0);

    vec3 sm = st.special ? prevVal
                         : mix(prevVal, vec3(st.battery, st.link, st.speed), ALPHA);

    float hi = floor(sm.z * 255.0) / 255.0;
    fragColor = vec4(sm.x, sm.y, hi, (sm.z - hi) * 255.0);
}

#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <aechronis:ntsc.glsl>

uniform sampler2D DecodedSampler;
uniform sampler2D LineInfoSampler;

in vec2 texCoord;

out vec4 fragColor;

// LCD goggle scan-out of the decoded 525-line raster: the 480i picture is
// simply scaled to the panel (no CRT beam profile or phosphor shimmer).
// Vertical hold is driven by the measured health of the received vsync
// broad pulses: sustained loss free-runs into a roll through the blanking
// bar. The
// control word's inverted flag is applied at the camera (encode pass), so
// the picture inverts while artifacts stay screen-aligned.

vec3 fetchYiq(int x, int row) {
    vec4 t = texelFetch(DecodedSampler, ivec2(clamp(x, 0, ACTIVE_WIDTH - 1), row), 0);
    return vec3(t.r * 1.6 - 0.3, (t.g - 0.5) * 1.4, (t.b - 0.5) * 1.4);
}

vec3 lineYiq(float xt, int row) {
    int x0 = int(floor(xt - 0.5));
    float f = (xt - 0.5) - float(x0);
    return mix(fetchYiq(x0, row), fetchYiq(x0 + 1, row), f);
}

void main() {
    float anim = GameTime * 24000.0;
    uint field = uint(floor(anim * 3.0));

    // A crashed feed is complete receiver snow, not a damaged picture with
    // OSD, color flashes, frame kicks, or a rolling vertical-blanking bar.
    if (decodeFpv(GameTime).special) {
        vec2 analogSize = vec2(float(ACTIVE_WIDTH), float(VISIBLE_LINES));
        uvec2 px = uvec2(floor(gl_FragCoord.xy * analogSize / ScreenSize));
        uvec3 seed = uvec3(px, field ^ 0x57A71Cu);
        float snow = 0.5 * (hash1(seed) + hash1(seed ^ uvec3(0x9E37u, 0x85EBu, 0xC2B2u)));
        fragColor = vec4(vec3(snow), 1.0);
        return;
    }

    // vertical lock health, measured from the received vsync broad pulses
    float vsyncQ = (texelFetch(LineInfoSampler, ivec2(0, 3), 0).g
                  + texelFetch(LineInfoSampler, ivec2(0, 4), 0).g
                  + texelFetch(LineInfoSampler, ivec2(0, 5), 0).g) / 3.0;

    // Goggle decoder behavior: sustained sync loss free-runs into a
    // continuous roll. Horizontal tearing still comes from measured H-sync.
    float rollAmt = 1.0 - smoothstep(0.05, 0.4, vsyncQ);
    float roll = rollAmt * mod(anim * 6.5, float(LINES_PER_FRAME));

    float lf = float(VBI_LINES) + (1.0 - texCoord.y) * float(VISIBLE_LINES) + roll;
    float xt = texCoord.x * float(ACTIVE_WIDTH);

    // panel scaler: linear blend between the two adjacent scanlines
    int l0 = int(floor(lf - 0.5));
    float f = (lf - 0.5) - float(l0);
    int r0 = int(mod(float(l0), float(LINES_PER_FRAME)));
    int r1 = int(mod(float(l0 + 1), float(LINES_PER_FRAME)));
    vec3 yiq = mix(lineYiq(xt, r0), lineYiq(xt, r1), f);

    fragColor = vec4(clamp(yiqToRgb(yiq), 0.0, 1.0), 1.0);
}

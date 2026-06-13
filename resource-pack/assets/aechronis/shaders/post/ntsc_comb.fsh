#version 330

#moj_import <aechronis:ntsc.glsl>

uniform sampler2D CompositeSampler;

in vec2 texCoord;

out vec4 fragColor;

// 2H glass-delay-line comb filter. Same-field neighbours sit two raster rows
// away with opposite subcarrier phase, so their average reinforces luma and
// cancels chroma; the difference does the reverse. Vertical detail leaking
// into the difference is genuine NTSC cross-color (rainbowing), and the
// averaging cost is the genuine "hanging dots" on vertical color edges.
// Output texel: R = comb luma (packed IRE), G = comb chroma ([-60,60] IRE).

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float c0 = unpackIre(texelFetch(CompositeSampler, px, 0).r);
    int up = min(px.y + 2, LINES_PER_FRAME - 1);
    int dn = max(px.y - 2, 0);
    float cu = unpackIre(texelFetch(CompositeSampler, ivec2(px.x, up), 0).r);
    float cd = unpackIre(texelFetch(CompositeSampler, ivec2(px.x, dn), 0).r);

    float avg = 0.5 * (cu + cd);
    float luma = 0.5 * (c0 + avg);
    float chroma = 0.5 * (c0 - avg);

    fragColor = vec4(packIre(luma), (chroma + 60.0) / 120.0, 0.0, 1.0);
}

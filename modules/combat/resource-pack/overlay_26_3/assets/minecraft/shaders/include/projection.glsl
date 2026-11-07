#ifndef MINECRAFT_PROJECTION_GLSL
#define MINECRAFT_PROJECTION_GLSL

// 26.3 copy of the base pack's projection.glsl: #include instead of #moj_import, behind vanilla's
// include guard. 26.3's globals.glsl is guarded too, so shaders that also include it still compile.
#include <minecraft:globals.glsl>

// Vanilla's Projection block under another name: std140 layout, so only the block name binds.
// Every world shader reads ProjMat through the macro below, which applies the gun aim zoom.
// First-person guns use AechronisRawProjMat so the held model keeps its size.
layout(std140) uniform Projection {
    mat4 AechronisRawProjMat;
};

// Zoom of GunZoom level n (GunZoom.LEVELS): 0 = 1x.
const float AECHRONIS_ZOOMS[6] = float[6](1.0, 1.5, 2.0, 3.0, 4.0, 6.0);

// GunZoom bands (GunZoom.kt), all while aiming:
// - 19000 + (n - 1) * 1000: holding level n (1..5), a 500-tick phase plus 500 ticks of grace.
// - easing pair i at i * 500 (i < 16) or 12000 + (i - 16) * 500 (i 16..29): i = from * 5 + to,
//   skipping to == from, so from = i / 5 and the rest r gives to = r < from ? r : r + 1.
// An ease starts at a phase divisible by 5, so the phase mod 5 is its age; it takes 4 ticks and holds
// its end zoom for 1 more, covering a late refresh. The fraction keeps it frame-smooth.
// Drone telemetry also uses 0..23999, but only in a drone camera, where GunZoom is off.
float aechronis_zoom_factor() {
    float time = GameTime * 24000.0;
    float clock = floor(time + 0.008);
    if (clock >= 19000.0 && clock < 24000.0) return AECHRONIS_ZOOMS[int((clock - 19000.0) / 1000.0) + 1];
    int pair;
    if (clock < 8000.0) {
        pair = int(clock / 500.0);
    } else if (clock >= 12000.0 && clock < 19000.0) {
        pair = 16 + int((clock - 12000.0) / 500.0);
    } else {
        return 1.0;
    }
    int from = pair / 5;
    int rest = pair - from * 5;
    int to = rest < from ? rest : rest + 1;
    // Cubic ease-out: quick to react, then settles gently into the end zoom.
    float progress = 1.0 - pow(1.0 - clamp(mod(mod(time, 500.0), 5.0) / 4.0, 0.0, 1.0), 3.0);
    // Ease in log space so zooming in and out feel equally fast.
    return exp(mix(log(AECHRONIS_ZOOMS[from]), log(AECHRONIS_ZOOMS[to]), progress));
}

mat4 aechronis_zoomed_projection() {
    mat4 projection = AechronisRawProjMat;
    // Orthographic (GUI) projections never zoom.
    if (projection[3][3] != 0.0) return projection;
    float zoom = aechronis_zoom_factor();
    projection[0][0] *= zoom;
    projection[1][1] *= zoom;
    return projection;
}

#define ProjMat aechronis_zoomed_projection()

vec4 projection_from_position(vec4 position) {
    vec4 projection = position * 0.5;
    projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = position.zw;
    return projection;
}

#endif

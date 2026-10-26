#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

layout(location = 4) flat in int aechronisTrail;
layout(location = 5) flat in float aechronisTrailFade;
layout(location = 6) flat in vec2 aechronisTrailMotion;
layout(location = 7) noperspective in vec2 aechronisTrailUV;
layout(location = 8) flat in ivec2 aechronisFlashOrigin;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    if (aechronisTrail == 1) {
        // Only a bright head travels from muzzle to impact over 90 ms.
        // Keep moving beyond the endpoint so no light remains parked there.
        // Screen-linear progress keeps distant shots from collapsing to a dot
        // at the endpoint after their first frame.
        float along = clamp(aechronisTrailUV.x / max(aechronisTrailMotion.y, 0.001), 0.0, 1.0);
        float travel = aechronisTrailMotion.x / 1.8;
        float headDistance = (along - travel) / 0.060;
        if (abs(headDistance) >= 2.0) discard;
        float head = exp(-headDistance * headDistance) * (1.0 - smoothstep(1.0, 2.0, abs(headDistance)));

        float across = abs(aechronisTrailUV.y);
        float core = exp(-7.0 * across * across);
        float softness = exp(-3.0 * across * across) * (1.0 - smoothstep(0.65, 1.0, across));
        if (across >= 1.0 || aechronisTrailFade <= 0.001) discard;
        float alpha = min(1.0, (1.05 * core + 0.40 * softness) * head * aechronisTrailFade);
        vec3 color = mix(vec3(1.0, 0.68, 0.25), vec3(1.0, 1.0, 0.94), core * (0.65 + 0.35 * head));
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, alpha);
        #elif defined(OIT_ACCUMULATE)
        fragColor = sampleColorForAccumulation(vec4(color, alpha));
        #else
        fragColor = vec4(color, alpha);
        #endif
        return;
    }

    vec2 nativeUV = texCoord0;
    if (all(greaterThanEqual(aechronisFlashOrigin, ivec2(0)))) {
        vec2 atlasSize = vec2(textureSize(Sampler0, 0));
        vec2 relative = texCoord0 * atlasSize - vec2(aechronisFlashOrigin);
        // Adjacent atlas sprites can share a corner with Flash. Test the
        // interpolated fragment UV before redirecting it to the artwork tile,
        // so a neighboring particle keeps its original texture throughout.
        if (all(greaterThan(relative, vec2(0.0))) && all(lessThan(relative, vec2(1024.0, 512.0)))) {
            nativeUV = (vec2(aechronisFlashOrigin) + relative * vec2(32.0 / 1024.0, 32.0 / 512.0)) / atlasSize;
        }
    }
    vec4 color = texture(Sampler0, nativeUV) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}

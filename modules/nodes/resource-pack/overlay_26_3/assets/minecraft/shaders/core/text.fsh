#version 330
#extension GL_ARB_separate_shader_objects : require

#include <aechronis:config.glsl>

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in vec2 uvCoord;
layout(location = 5) in vec2 markerLocalCoord;
layout(location = 6) flat in int custom;
layout(location = 7) flat in int markerTypeData;
layout(location = 8) flat in int markerScaleData;
layout(location = 9) flat in int territoryBorderMaskData;
layout(location = 10) flat in int territoryHomeData;
layout(location = 11) flat in vec4 markerFillColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif

#if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)
#ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
#else
    vec4 fogColor = FogColor;
#endif
    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
    return color;
}

void main() {
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

    vec4 color = texColor * vertexColor * ColorModulator;

    #include <aechronis:fragment_body.glsl>

    if (color.a < 0.1) {
        discard;
    }

#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    fragColor = calculateFinalColor(color);
#endif
}

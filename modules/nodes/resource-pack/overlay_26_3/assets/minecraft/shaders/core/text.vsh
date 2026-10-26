#version 330
#extension GL_ARB_separate_shader_objects : require

#define MARKER_DEPTH 0.7
// Reversed depth: larger clip-space Z values are in front.
#define MARKER_DEPTH_FRONT_DIRECTION 1.0

#include <aechronis:config.glsl>

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out vec2 uvCoord;
layout(location = 5) out vec2 markerLocalCoord;
layout(location = 6) flat out int custom;
layout(location = 7) flat out int markerTypeData;
layout(location = 8) flat out int markerScaleData;
layout(location = 9) flat out int territoryBorderMaskData;
layout(location = 10) flat out int territoryHomeData;
layout(location = 11) flat out vec4 markerFillColor;

#include <aechronis:vertex_utils.glsl>

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    uvCoord = vec2(0);
    markerLocalCoord = vec2(0);
    custom = 0;
    markerTypeData = 0;
    markerScaleData = 0;
    territoryBorderMaskData = 0;
    territoryHomeData = 0;
    markerFillColor = vec4(1);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);

    #include <aechronis:vertex_body.glsl>
#else
    vertexColor = Color;
#endif
}

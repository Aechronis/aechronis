#version 430
#define UNREL_ID
#define MARKER_DEPTH 0.7

#ifdef GL_ARB_shader_draw_parameters
#extension GL_ARB_shader_draw_parameters : require
#endif

#moj_import <aechronis:config.glsl>

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;

out vec2 uvCoord;
out vec2 markerLocalCoord;
flat out int custom;
flat out int markerTypeData;
flat out int markerScaleData;
flat out int territoryBorderMaskData;
flat out int territoryHomeData;
flat out vec4 markerFillColor;

#moj_import <aechronis:vertex_utils.glsl>

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

    #moj_import <aechronis:vertex_body.glsl>
#else
    vertexColor = Color;
#endif
}
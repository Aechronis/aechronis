#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0;

#include <aechronis:gun_trail.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;
layout(location = 4) flat out int aechronisTrail;
layout(location = 5) flat out float aechronisTrailFade;
layout(location = 6) flat out vec2 aechronisTrailMotion;
layout(location = 7) noperspective out vec2 aechronisTrailUV;
layout(location = 8) flat out ivec2 aechronisFlashOrigin;

void main() {
    aechronisTrail = 0;
    aechronisTrailFade = 0.0;
    aechronisTrailMotion = vec2(0.0);
    aechronisTrailUV = vec2(0.0);
    texCoord0 = UV0;
    if (aechronis_render_trail(Position, UV0, Color, gl_Position, aechronisTrailUV, aechronisTrailFade, aechronisTrailMotion, aechronisFlashOrigin)) {
        aechronisTrail = 1;
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
        vertexColor = vec4(1.0);
        return;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
}

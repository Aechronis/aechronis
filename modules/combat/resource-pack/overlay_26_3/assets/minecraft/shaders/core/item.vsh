#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0;
#include <aechronis:gun_animation.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif

void main() {
    vec3 position;
    vec3 normal;
    vec2 uv;
    bool emissive;
    vec4 color = Color;
    bool animatedGun = aechronis_animate_gun(
        (ModelViewMat * vec4(Position, 1.0)).xyz, mat3(ModelViewMat) * Normal,
        UV0, Color, gl_VertexIndex, position, normal, uv, emissive);
    if (animatedGun) {
        color = vec4(1.0);
        gl_Position = ProjMat * vec4(position, 1.0);
    } else {
        // The helper's out parameters are undefined for ordinary item textures.
        position = Position;
        normal = Normal;
        uv = UV0;
        gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    }

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = animatedGun ? 0.0 : fog_spherical_distance(position);
    cylindricalVertexDistance = animatedGun ? 0.0 : fog_cylindrical_distance(position);
    #endif

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, color);
    #ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif
    if (emissive) {
        vertexColor = vec4(1.0);
        #ifndef OIT_ALPHA_ONLY
        lightMapColor = vec4(1.0);
        overlayColor = vec4(1.0);
        #endif
    }

    texCoord0 = uv;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(uv, 0.0, 1.0)).xy;
    #endif
    #endif
}

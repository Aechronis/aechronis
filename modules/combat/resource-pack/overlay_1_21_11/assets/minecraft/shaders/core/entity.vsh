#version 330

// 1.21.11 renders held items through this shared entity pipeline.
// Preserve its lighting variants and ordinary geometry when no gun marker is present.

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;
#moj_import <aechronis:gun_animation.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    vec3 position;
    vec3 normal;
    vec2 uv;
    bool emissive;
    vec4 color = Color;
    bool animatedGun = aechronis_animate_gun(
        (ModelViewMat * vec4(Position, 1.0)).xyz, mat3(ModelViewMat) * Normal,
        UV0, Color, gl_VertexID, position, normal, uv, emissive);
    if (animatedGun) {
        color = vec4(1.0);
        gl_Position = ProjMat * vec4(position, 1.0);
    } else {
        position = Position;
        normal = Normal;
        uv = UV0;
        gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    }

    sphericalVertexDistance = animatedGun ? 0.0 : fog_spherical_distance(position);
    cylindricalVertexDistance = animatedGun ? 0.0 : fog_cylindrical_distance(position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, color);
#endif
#ifndef EMISSIVE
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
#endif
    overlayColor = texelFetch(Sampler1, UV1, 0);

    if (emissive) {
#ifdef PER_FACE_LIGHTING
        vertexPerFaceColorBack = vec4(1.0);
        vertexPerFaceColorFront = vec4(1.0);
#else
        vertexColor = vec4(1.0);
#endif
        lightMapColor = vec4(1.0);
        overlayColor = vec4(1.0);
    }

    texCoord0 = uv;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(uv, 0.0, 1.0)).xy;
#endif
}

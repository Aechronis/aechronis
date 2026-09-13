#version 330

// 1.21.11 uses this separate pipeline for translucent item rendering.
// Decode gun carriers here as well as in the shared entity pipeline.

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
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;


out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;

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

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, color) * texelFetch(Sampler2, UV2 / 16, 0);
    if (emissive) vertexColor = vec4(1.0);
    texCoord0 = uv;
    texCoord1 = UV1;
    texCoord2 = UV2;
}

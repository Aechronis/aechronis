#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
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
out vec4 vertexColor;
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
        // The helper's out parameters are undefined for ordinary item textures.
        position = Position;
        normal = Normal;
        uv = UV0;
        gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    }

    sphericalVertexDistance = animatedGun ? 0.0 : fog_spherical_distance(position);
    cylindricalVertexDistance = animatedGun ? 0.0 : fog_cylindrical_distance(position);

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, color);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    if (emissive) {
        vertexColor = vec4(1.0);
        lightMapColor = vec4(1.0);
        overlayColor = vec4(1.0);
    }

    texCoord0 = uv;
}

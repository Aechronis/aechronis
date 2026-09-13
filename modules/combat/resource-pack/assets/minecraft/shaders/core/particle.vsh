#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

#moj_import <aechronis:gun_trail.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
flat out int aechronisTrail;
flat out float aechronisTrailFade;
flat out vec2 aechronisTrailMotion;
noperspective out vec2 aechronisTrailUV;
flat out ivec2 aechronisFlashOrigin;

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

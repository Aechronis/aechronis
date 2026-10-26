#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 Normal;
layout(location = 3) in float LineWidth;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) noperspective out vec3 encodedLinePosition;
layout(location = 4) noperspective out vec2 encodedLineEndpoint;
layout(location = 5) flat out float sourceLineWidth;

const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
const mat4 VIEW_SCALE = mat4(
    VIEW_SHRINK, 0.0, 0.0, 0.0,
    0.0, VIEW_SHRINK, 0.0, 0.0,
    0.0, 0.0, VIEW_SHRINK, 0.0,
    0.0, 0.0, 0.0, 1.0
);

void main() {
    vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);
    vec4 linePosEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position + Normal, 1.0);

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;

    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth / ScreenSize;

    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    if (gl_VertexIndex % 2 == 0) {
        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;

    encodedLinePosition = Position;
    int lineVertex = gl_VertexIndex % 4;
    encodedLineEndpoint.x = lineVertex < 2 ? 0.0 : 1.0;
    encodedLineEndpoint.y = lineVertex == 0 || lineVertex == 3 ? 1.0 : 0.0;
    sourceLineWidth = LineWidth;

	// Match ModelManager's aiming/combat bands, including late-refresh grace. Hide
	// Minecraft's translucent black selection outline around its fake sculk veins.
	float tickGameTime = floor(GameTime * 24000.0 + 0.008);
	bool combat = (tickGameTime >= 9000.0 && tickGameTime < 10000.0) || (tickGameTime >= 11000.0 && tickGameTime < 12000.0);
	if (combat) {
		if (Color == vec4(0, 0, 0, .4)) {
			vertexColor = vec4(0, 0, 0, 0);
			gl_Position = vec4(0);
		}
	}
}

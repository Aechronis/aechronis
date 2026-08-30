#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec3 Normal;
in float LineWidth;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
noperspective out vec3 encodedLinePosition;
noperspective out vec2 encodedLineEndpoint;
flat out float sourceLineWidth;

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

    if (gl_VertexID % 2 == 0) {
        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;

    encodedLinePosition = Position;
    int lineVertex = gl_VertexID % 4;
    encodedLineEndpoint.x = lineVertex < 2 ? 0.0 : 1.0;
    encodedLineEndpoint.y = lineVertex == 0 || lineVertex == 3 ? 1.0 : 0.0;
    sourceLineWidth = LineWidth;
	
	// ModelManager sends time 11000 while combat interaction is active. Hide
	// Minecraft's translucent black selection outline around its fake lichen.
	int tickGameTime = int(GameTime * 24000);
	if ((tickGameTime / 1000) % 10 == 1) {
		if (Color == vec4(0, 0, 0, .4)) {
			vertexColor = vec4(0, 0, 0, 0);
			gl_Position = vec4(0);
		}
	}
}

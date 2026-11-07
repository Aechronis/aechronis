#version 330
#extension GL_ARB_separate_shader_objects : require

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl and projection.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

// Inline Globals too: GUI shaders also run before resource packs load.
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) noperspective out vec2 guiPosition;
layout(location = 2) noperspective out vec2 quadCorner;
layout(location = 3) flat out vec2 guiExtent;
layout(location = 4) flat out float combatCooldownPass;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    // ColoredRectangleRenderState emits four vertices: (x0,y0), (x0,y1),
    // (x1,y1), (x1,y0). Mixed-format batching can rotate these corner labels
    // through an arbitrary base vertex; the fragment shader handles both axes.
    int corner = gl_VertexIndex & 3;
    quadCorner = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
    vec2 projectionExtent = 2.0 / max(abs(vec2(ProjMat[0][0], ProjMat[1][1])), vec2(0.000001));
    guiPosition = (gl_Position.xy / gl_Position.w * vec2(0.5, -0.5) + 0.5) * projectionExtent;
    // GuiRenderer projects physical size / scale, but HUD layout uses the
    // window's ceil-rounded GUI dimensions when the division is fractional.
    guiExtent = ceil(projectionExtent - 0.001);
    float clock = floor(GameTime * 24000.0 + 0.008);
    // Match ModelManager's aiming/combat bands, including late-refresh grace.
    bool combat = (clock >= 9000.0 && clock < 10000.0) || (clock >= 11000.0 && clock < 12000.0);
    bool orthographic = abs(ProjMat[2][3]) < 0.000001 && abs(ProjMat[3][3] - 1.0) < 0.000001;
    combatCooldownPass = combat && orthographic ? 1.0 : 0.0;
}

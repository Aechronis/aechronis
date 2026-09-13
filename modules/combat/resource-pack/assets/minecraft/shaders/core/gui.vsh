#version 330

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl and projection.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

// Inline Globals too: GUI shaders also run before resource packs load.
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
noperspective out vec2 guiPosition;
noperspective out vec2 quadCorner;
flat out vec2 guiExtent;
flat out float combatCooldownPass;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    // ColoredRectangleRenderState emits four vertices: (x0,y0), (x0,y1),
    // (x1,y1), (x1,y0). Mixed-format batching can rotate these corner labels
    // through an arbitrary base vertex; the fragment shader handles both axes.
    int corner = gl_VertexID & 3;
    quadCorner = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
    vec2 projectionExtent = 2.0 / max(abs(vec2(ProjMat[0][0], ProjMat[1][1])), vec2(0.000001));
    guiPosition = (gl_Position.xy / gl_Position.w * vec2(0.5, -0.5) + 0.5) * projectionExtent;
    // GuiRenderer projects physical size / scale, but HUD layout uses the
    // window's ceil-rounded GUI dimensions when the division is fractional.
    guiExtent = ceil(projectionExtent - 0.001);
    float clock = floor(GameTime * 24000.0 + 0.008);
    bool orthographic = abs(ProjMat[2][3]) < 0.000001 && abs(ProjMat[3][3] - 1.0) < 0.000001;
    combatCooldownPass = clock >= 11000.0 && clock < 12000.0 && orthographic ? 1.0 : 0.0;
}

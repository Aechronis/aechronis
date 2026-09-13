#version 330

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
noperspective in vec2 guiPosition;
noperspective in vec2 quadCorner;
flat in vec2 guiExtent;
flat in float combatCooldownPass;

out vec4 fragColor;

void main() {
    // Derivatives recover the entire rectangle, not just pixels falling over
    // the hotbar. A fullscreen tint or unrelated panel must remain intact.
    vec2 dx = dFdx(quadCorner), dy = dFdy(quadCorner);
    vec2 positionDx = dFdx(guiPosition), positionDy = dFdy(guiPosition);
    bool cooldownColor = all(lessThan(abs(vertexColor * 255.0 - vec4(255.0, 255.0, 255.0, 127.0)), vec4(0.25)));
    bool constantColor = all(lessThan(fwidth(vertexColor), vec4(0.000001)));
    bool direct = abs(dx.y) < 0.000001 && abs(dy.x) < 0.000001 && abs(dx.x) > 0.000001 && abs(dy.y) > 0.000001;
    bool swapped = abs(dx.x) < 0.000001 && abs(dy.y) < 0.000001 && abs(dx.y) > 0.000001 && abs(dy.x) > 0.000001;
    bool aligned = direct || swapped;
    if (combatCooldownPass > 0.5 && cooldownColor && constantColor && aligned &&
        all(lessThan(abs(ColorModulator - vec4(1.0)), vec4(0.000001)))) {
        // StagedVertexBuffer aligns mixed GUI formats to a vertex, not a quad.
        // Odd base vertices rotate the corner labels and exchange their axes.
        vec2 corner = swapped ? quadCorner.yx : quadCorner;
        vec2 size = swapped ? vec2(positionDx.x / dx.y, positionDy.y / dy.x)
                            : vec2(positionDx.x / dx.x, positionDy.y / dy.y);
        vec2 origin = guiPosition - corner * size;
        vec2 low = min(origin, origin + size), high = max(origin, origin + size);
        float center = floor(guiExtent.x * 0.5 + 0.0001);
        float slot = round((low.x - (center - 88.0)) / 20.0);
        bool mainSlot = slot >= 0.0 && slot <= 8.0 && abs(low.x - (center - 88.0 + slot * 20.0)) < 0.03;
        bool offhandSlot = abs(low.x - (center - 117.0)) < 0.03 || abs(low.x - (center + 101.0)) < 0.03;
        bool itemRectangle = abs(abs(size.x) - 16.0) < 0.03 &&
            low.y >= guiExtent.y - 19.03 && low.y <= guiExtent.y - 3.97 &&
            abs(high.y - (guiExtent.y - 3.0)) < 0.03;
        if ((mainSlot || offhandSlot) && itemRectangle) discard;
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}

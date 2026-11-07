#version 330
#extension GL_ARB_separate_shader_objects : require

// This shader also runs during startup, so keep vanilla's inline uniform blocks
// instead of importing resource-pack includes. Layouts match 26.3.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// BEGIN GENERATED ATTACK INDICATOR PALETTE
const vec3 ATTACK_INDICATOR_COLORS[19] = vec3[](
    vec3(0.0, 0.0, 0.0),
    vec3(43.0, 32.0, 31.0),
    vec3(58.0, 59.0, 60.0),
    vec3(71.0, 54.0, 53.0),
    vec3(88.0, 71.0, 70.0),
    vec3(91.0, 74.0, 73.0),
    vec3(98.0, 77.0, 70.0),
    vec3(109.0, 89.0, 87.0),
    vec3(111.0, 71.0, 75.0),
    vec3(130.0, 109.0, 108.0),
    vec3(133.0, 89.0, 92.0),
    vec3(149.0, 107.0, 110.0),
    vec3(159.0, 161.0, 175.0),
    vec3(175.0, 176.0, 181.0),
    vec3(189.0, 190.0, 195.0),
    vec3(210.0, 210.0, 214.0),
    vec3(215.0, 217.0, 234.0),
    vec3(234.0, 236.0, 242.0),
    vec3(255.0, 255.0, 255.0)
);
// END GENERATED ATTACK INDICATOR PALETTE

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    // ModelManager sends this before changing the held item's aiming material.
    float tickGameTime = floor(GameTime * 24000.0 + 0.008);
    // Each 500-tick phase has 500 ticks of grace for late clock refreshes.
    // Aiming precedes idle/combat, so their clocks cannot advance into it.
    bool aiming = tickGameTime >= 9000.0 && tickGameTime < 10000.0;
    bool combat = aiming || (tickGameTime >= 11000.0 && tickGameTime < 12000.0);
    // Only crosshair.png uses this RGBA marker. Restore its vanilla white when
    // visible; checking the texel before tinting leaves other HUD sprites alone.
    if (all(lessThan(abs(texel * 255.0 - vec4(17.0, 143.0, 79.0, 254.0)), vec4(0.5)))) {
        // ModelManager sends the 9000..9499 aiming band plus a partial tick.
        // Other gun states use separate bands; drone cameras own their telemetry.
        if (aiming) {
            discard;
        }
        texel = vec4(1.0);
    }
    // Attack sprites encode a palette index in red and a unique GBA signature.
    // Both the crosshair and hotbar indicator pipelines use this shader.
    // Keep them hidden in hip-fire too: leaving ADS changes the item material
    // after the aiming signal ends, which also resets vanilla attack strength.
    if (all(lessThan(abs(texel.gba * 255.0 - vec3(143.0, 80.0, 254.0)), vec3(0.5)))) {
        int paletteIndex = int(round(texel.r * 255.0));
        if (paletteIndex < ATTACK_INDICATOR_COLORS.length()) {
            if (combat) {
                discard;
            }
            texel = vec4(ATTACK_INDICATOR_COLORS[paletteIndex] / 255.0, 1.0);
        }
    }
    vec4 color = texel * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}

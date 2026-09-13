#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

flat in int aechronisTrail;
flat in float aechronisTrailFade;
flat in vec2 aechronisTrailMotion;
noperspective in vec2 aechronisTrailUV;
flat in ivec2 aechronisFlashOrigin;

out vec4 fragColor;

void main() {
    if (aechronisTrail == 1) {
        // Only a bright head travels from muzzle to impact over 90 ms.
        // Keep moving beyond the endpoint so no light remains parked there.
        // Screen-linear progress keeps distant shots from collapsing to a dot
        // at the endpoint after their first frame.
        float along = clamp(aechronisTrailUV.x / max(aechronisTrailMotion.y, 0.001), 0.0, 1.0);
        float travel = aechronisTrailMotion.x / 1.8;
        float headDistance = (along - travel) / 0.060;
        if (abs(headDistance) >= 2.0) discard;
        float head = exp(-headDistance * headDistance) * (1.0 - smoothstep(1.0, 2.0, abs(headDistance)));

        float across = abs(aechronisTrailUV.y);
        float core = exp(-7.0 * across * across);
        float softness = exp(-3.0 * across * across) * (1.0 - smoothstep(0.65, 1.0, across));
        if (across >= 1.0 || aechronisTrailFade <= 0.001) discard;
        float alpha = min(1.0, (1.05 * core + 0.40 * softness) * head * aechronisTrailFade);
        vec3 color = mix(vec3(1.0, 0.68, 0.25), vec3(1.0, 1.0, 0.94), core * (0.65 + 0.35 * head));
        fragColor = vec4(color, alpha);
        return;
    }

    vec2 nativeUV = texCoord0;
    if (all(greaterThanEqual(aechronisFlashOrigin, ivec2(0)))) {
        vec2 atlasSize = vec2(textureSize(Sampler0, 0));
        vec2 relative = texCoord0 * atlasSize - vec2(aechronisFlashOrigin);
        // Adjacent atlas sprites can share a corner with Flash. Test the
        // interpolated fragment UV before redirecting it to the artwork tile,
        // so a neighboring particle keeps its original texture throughout.
        if (all(greaterThan(relative, vec2(0.0))) && all(lessThan(relative, vec2(1024.0, 512.0)))) {
            nativeUV = (vec2(aechronisFlashOrigin) + relative * vec2(32.0 / 1024.0, 32.0 / 512.0)) / atlasSize;
        }
    }
    vec4 color = texture(Sampler0, nativeUV) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

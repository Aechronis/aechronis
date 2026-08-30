#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
noperspective in vec3 encodedLinePosition;
noperspective in vec2 encodedLineEndpoint;
flat in float sourceLineWidth;

out vec4 fragColor;

const float HITBOX_LINE_WIDTH = 2.5;
const float SCALE_TOLERANCE = 0.001;
const float COLOR_TOLERANCE = 0.001;

// Must match RelationshipHitbox.kt.
const float TOWN_SCALE = 0.994;
const float NATION_SCALE = 0.998;
const float ALLY_SCALE = 1.002;
const float NEUTRAL_SCALE = 1.006;
const float ENEMY_SCALE = 1.010;

const vec4 TOWN_COLOR = vec4(85.0, 255.0, 85.0, 255.0) / 255.0;
const vec4 NATION_COLOR = vec4(0.0, 170.0, 0.0, 255.0) / 255.0;
const vec4 ALLY_COLOR = vec4(0.0, 170.0, 170.0, 255.0) / 255.0;
const vec4 NEUTRAL_COLOR = vec4(255.0, 170.0, 0.0, 255.0) / 255.0;
const vec4 ENEMY_COLOR = vec4(255.0, 85.0, 85.0, 255.0) / 255.0;

void considerRelationshipScale(
    float scale,
    float candidateScale,
    vec4 candidateColor,
    inout float closestDistance,
    inout vec4 closestColor
) {
    float distance = abs(scale - candidateScale);
    if (distance < closestDistance) {
        closestDistance = distance;
        closestColor = candidateColor;
    }
}

void considerPlayerDimension(
    float lineLength,
    float baseDimension,
    inout float closestDistance,
    inout vec4 closestColor
) {
    float scale = lineLength / baseDimension;
    considerRelationshipScale(scale, TOWN_SCALE, TOWN_COLOR, closestDistance, closestColor);
    considerRelationshipScale(scale, NATION_SCALE, NATION_COLOR, closestDistance, closestColor);
    considerRelationshipScale(scale, ALLY_SCALE, ALLY_COLOR, closestDistance, closestColor);
    considerRelationshipScale(scale, NEUTRAL_SCALE, NEUTRAL_COLOR, closestDistance, closestColor);
    considerRelationshipScale(scale, ENEMY_SCALE, ENEMY_COLOR, closestDistance, closestColor);
}

bool relationshipColor(float lineLength, out vec4 color) {
    float closestDistance = SCALE_TOLERANCE + 1.0;
    vec4 closestColor = vec4(1.0);

    // Normal player width, standing height, and crouching height. Swimming,
    // fall-flying, and spin-attacking use the same 0.6 dimension as the width.
    considerPlayerDimension(lineLength, 0.6, closestDistance, closestColor);
    considerPlayerDimension(lineLength, 1.5, closestDistance, closestColor);
    considerPlayerDimension(lineLength, 1.8, closestDistance, closestColor);

    color = closestColor;
    return closestDistance <= SCALE_TOLERANCE;
}

bool isWhiteHitboxLine() {
    return abs(sourceLineWidth - HITBOX_LINE_WIDTH) < 0.001 &&
        all(greaterThan(vertexColor, vec4(0.999)));
}

bool isHitboxIndicatorLine() {
    if (abs(sourceLineWidth - HITBOX_LINE_WIDTH) >= 0.001) return false;

    bool isEyeHeightRed = all(lessThan(
        abs(vertexColor - vec4(1.0, 0.0, 0.0, 1.0)),
        vec4(COLOR_TOLERANCE)
    ));
    bool isLookDirectionBlue = all(lessThan(
        abs(vertexColor - vec4(0.0, 0.0, 1.0, 1.0)),
        vec4(COLOR_TOLERANCE)
    ));
    return isEyeHeightRed || isLookDirectionBlue;
}

bool candidateRelationshipColor(
    vec3 lineDelta,
    float gradientSquared,
    out float lineLength,
    out vec4 color
) {
    vec3 absoluteDelta = abs(lineDelta);
    lineLength = length(lineDelta);
    float offAxisLength = absoluteDelta.x + absoluteDelta.y + absoluteDelta.z -
        max(absoluteDelta.x, max(absoluteDelta.y, absoluteDelta.z));

    if (
        gradientSquared <= 1.0e-8 ||
        offAxisLength > 0.001
    ) {
        return false;
    }
    return relationshipColor(lineLength, color);
}

bool decodedRelationshipColor(out vec4 color) {
    // Staged draws can begin at any base vertex. These two endpoint phases cover
    // both possible parities while preserving vanilla's [start, start, end, end].
    vec2 endpointDx = dFdx(encodedLineEndpoint);
    vec2 endpointDy = dFdy(encodedLineEndpoint);
    vec2 gradientSquared = endpointDx * endpointDx + endpointDy * endpointDy;
    vec3 positionDx = dFdx(encodedLinePosition);
    vec3 positionDy = dFdy(encodedLinePosition);
    vec3 lineDeltaFirst = (
        positionDx * endpointDx.x + positionDy * endpointDy.x
    ) / max(gradientSquared.x, 1.0e-8);
    vec3 lineDeltaSecond = (
        positionDx * endpointDx.y + positionDy * endpointDy.y
    ) / max(gradientSquared.y, 1.0e-8);

    // Derivatives must be evaluated before any data-dependent branch.
    if (!isWhiteHitboxLine()) return false;

    bool useFirst = dot(lineDeltaFirst, lineDeltaFirst) >= dot(lineDeltaSecond, lineDeltaSecond);
    vec3 lineDelta = useFirst ? lineDeltaFirst : lineDeltaSecond;
    float selectedGradientSquared = useFirst ? gradientSquared.x : gradientSquared.y;
    float lineLength;
    return candidateRelationshipColor(lineDelta, selectedGradientSquared, lineLength, color);
}

void main() {
    vec4 color = vertexColor;
    vec4 decodedColor;
    bool hasDecodedColor = decodedRelationshipColor(decodedColor);

    // F3+B draws the eye-height box in opaque red and the look arrow in
    // opaque blue. Discard them instead of writing transparent depth.
    if (isHitboxIndicatorLine()) discard;

    if (hasDecodedColor) color = decodedColor;

    color *= ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

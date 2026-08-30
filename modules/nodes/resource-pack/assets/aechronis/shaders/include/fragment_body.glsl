if (markerTypeData == 3)
{
    color = markerFillColor;
    float markerInterior = 1.0;

    // Keep the individual chunk grid at 4x only.
    if (markerScaleData == 4)
    {
        float markerEdgeDistance = min(
            min(markerLocalCoord.x, 1.0 - markerLocalCoord.x),
            min(markerLocalCoord.y, 1.0 - markerLocalCoord.y)
        );
        markerInterior = smoothstep(0.0, max(fwidth(markerEdgeDistance), 0.0001), markerEdgeDistance);
    }

    // Territory boundaries remain visible at both scales. Home territories use the strongest outline.
    float territoryBorderWidth = markerScaleData == 4
        ? (territoryHomeData != 0 ? 5.0 : 2.5)
        : (territoryHomeData != 0 ? 2.5 : 1.0);
    vec2 territoryBorderFeather = max(
        fwidth(markerLocalCoord) * territoryBorderWidth,
        vec2(0.0001)
    );
    if ((territoryBorderMaskData & 1) != 0)
        markerInterior = min(markerInterior, smoothstep(0.0, territoryBorderFeather.x, markerLocalCoord.x));
    if ((territoryBorderMaskData & 2) != 0)
        markerInterior = min(markerInterior, smoothstep(0.0, territoryBorderFeather.x, 1.0 - markerLocalCoord.x));
    if ((territoryBorderMaskData & 4) != 0)
        markerInterior = min(markerInterior, smoothstep(0.0, territoryBorderFeather.y, markerLocalCoord.y));
    if ((territoryBorderMaskData & 8) != 0)
        markerInterior = min(markerInterior, smoothstep(0.0, territoryBorderFeather.y, 1.0 - markerLocalCoord.y));

    color.rgb = mix(markerFillColor.rgb * 0.35, markerFillColor.rgb, markerInterior);
}

if (
    custom == 3 ||
    (custom == 2 && length(uvCoord - 1 - MAP_CROP_RADIUS) > MAP_CROP_RADIUS)
)
    discard;

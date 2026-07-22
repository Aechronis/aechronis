vec2 texSize = textureSize(Sampler0, 0);
ivec2 uv = ivec2(UV0 * texSize);

const vec2 corners[] = vec2[](vec2(0, 0), vec2(0, 1), vec2(1, 1), vec2(1, 0));
#ifdef UNREL_ID // We can't rely on gl_VertexID as-is because of merged buffers.
    #ifdef GL_ARB_shader_draw_parameters
int idx = gl_VertexID - gl_BaseVertexARB;
    #else
int idx = 0;
    #endif
#else
int idx = gl_VertexID;
#endif

vec2 corner = corners[idx % 4];
vec4 testColor = texelFetch(Sampler0, uv, 0);
int idTex = id(uv);

custom = 0;
markerTypeData = 0;
markerScaleData = 0;
territoryBorderMaskData = 0;
territoryHomeData = 0;
markerLocalCoord = vec2(0);
markerFillColor = vec4(1);

if (texSize == vec2(256) && round(testColor.a * 255) == 3 && ((idTex & 0xffff) == 0x0100)) // Markers
{
#ifndef GL_ARB_shader_draw_parameters // Recover the quad corner from control pixels on older GPUs.
    idx = int(round(testColor.r * 255)) - 1;
    corner = corners[idx % 4];
#endif
    int meta = int(round(Color.b * 255));
    int markerType = int(round(texelFetch(Sampler0, uv + ivec2(0, 1 - corner.y * 2), 0).r * 255));
    bool isWaypoint = markerType >= 7 && markerType <= 16;
    bool isDeathWaypoint = markerType == 8 || markerType == 10;
    bool isScaleTwelveWaypoint = markerType == 9 || markerType == 10 || markerType == 13 || markerType == 14 || markerType == 16;
    bool isScaleTwelve = isWaypoint ? isScaleTwelveWaypoint : (meta / 4) % 2 != 0;
    markerTypeData = markerType;
    markerScaleData = isScaleTwelve ? 12 : 4;
    territoryBorderMaskData = markerType == 3 ? meta / 16 : 0;
    territoryHomeData = markerType == 3 ? (meta / 8) % 2 : 0;
    markerLocalCoord = corner;
    if (markerType == 3)
    {
        ivec2 centerOffset = ivec2(corner.x < 0.5 ? 1 : -1, corner.y < 0.5 ? 1 : -1);
        markerFillColor = texelFetch(Sampler0, uv + centerOffset, 0);
    }

    // Red and green carry absolute chunk coordinates modulo 256. Waypoint blue carries its
    // exact in-chunk block position; other markers use blue for scale and territory metadata.
    // Territory markers use bit 3 for home status and the high nibble for four outlined edges.
    // Camera globals provide frame-interpolated movement without imprecise large-world floats.
    ivec2 cameraChunkPosition = ivec2(
        floor_div(CameraBlockPos.x, 16),
        floor_div(CameraBlockPos.z, 16)
    );
    vec2 cameraPositionInChunk = vec2(
        floor_mod(CameraBlockPos.x, 16),
        floor_mod(CameraBlockPos.z, 16)
    ) + CameraOffset.xz;
    ivec2 encodedChunkPosition = ivec2(round(Color.rg * 255.0));
    ivec2 chunkDelta = ivec2(
        wrapped_chunk_delta(encodedChunkPosition.x, cameraChunkPosition.x),
        wrapped_chunk_delta(encodedChunkPosition.y, cameraChunkPosition.y)
    );
    float mapScale = isScaleTwelve ? 12.0 : 4.0;
    ivec2 waypointBlockPosition = ivec2(meta / 16, meta % 16);
    vec2 markerCoordinate = markerType == 4
        ? vec2(0.0)
        : isWaypoint
            ? (vec2(chunkDelta * 16 + waypointBlockPosition) + vec2(0.5) - cameraPositionInChunk) * (2.0 / mapScale)
            : (vec2(chunkDelta) * 16.0 + vec2(8.0) - cameraPositionInChunk) * (2.0 / mapScale);
    vec2 scaleData = markerType == 4 ? vec2(2.5) : vec2(8.0 / mapScale);
    if (isWaypoint)
    {
        float waypointDistance = length(markerCoordinate);
        if (waypointDistance > MAP_CROP_RADIUS * 2.0)
        {
            float outsideRadius = MAP_CROP_RADIUS * 2.0 + 2.0 * length(scaleData);
            // Follow the waypoint naturally through the ring before settling at the outside radius.
            // Clamping immediately to outsideRadius makes the marker jump as soon as its center exits.
            float displayRadius = min(waypointDistance, outsideRadius);
            markerCoordinate *= displayRadius / waypointDistance;
        }
    }
    vec2 pos = (markerCoordinate + 128.0) / 256.0;
    vec3 local = transpose(mat3(ModelViewMat)) * vec3(1, 0, 0);
    float yaw = atan(local.z, local.x);
    mat2 rotAngle = mat2_rotate_z(yaw);

    // Text opacity carries the client-reported player yaw. Compare it with the frame-smooth
    // camera yaw so the front-facing third-person view can hide the minimap.
    float playerYawIndex = clamp(
        round(Color.a * 255.0) - PLAYER_YAW_OPACITY_OFFSET,
        0.0,
        PLAYER_YAW_BUCKETS - 1.0
    );
    float playerYaw = playerYawIndex / PLAYER_YAW_BUCKETS * 2.0 * PI + PI;
    float cameraPlayerDelta = atan(sin(yaw - playerYaw), cos(yaw - playerYaw));
    float frontCameraError = PI - abs(cameraPlayerDelta);
    float cameraDepth = abs((ModelViewMat * vec4(Position, 1.0)).z);
    // A normal fast turn can make round-trip yaw metadata briefly stale. First require the
    // text display to be displaced from the camera, then check for the 180-degree F5 reversal.
    bool isFrontCamera =
        cameraDepth > THIRD_PERSON_MIN_CAMERA_DEPTH &&
        frontCameraError < FRONT_CAMERA_YAW_TOLERANCE;

    float offset = (1.0 + MAP_CROP_RADIUS) / 128.0;
    mat2 markerRotAngle = markerType == 4 ? mat2(1, 0, 0, 1) : rotAngle;
    vec2 map = markerRotAngle * (((corner - 0.5) / 64 * scaleData) + pos - 0.5) + offset;
    uvCoord = map * 128;
    bool allowPartialVisibility = markerType == 3 || markerType == 5;
    bool markerCenterOutsideCrop = !isWaypoint && !allowPartialVisibility && length(markerCoordinate) > MAP_CROP_RADIUS * 2.0;
    if (isFrontCamera || markerCenterOutsideCrop)
        custom = 3;
    // Waypoints bypass the circular fragment crop so the entire glyph can cross the ring smoothly.
    else if (isWaypoint)
        custom = 4;
    else
        custom = 2;
    map = map * MAP_SIZE + MAP_OFFSET;

    float markerDepth = MARKER_DEPTH;
    if (markerType == 6) markerDepth += 0.01; // Territory core
    if (markerType == 5) markerDepth += 0.02; // Attack overlay
    if (markerType == 2) markerDepth += 0.03; // Building icon
    if (isWaypoint && !isDeathWaypoint) markerDepth += 0.035; // Permanent waypoint
    if (isDeathWaypoint) markerDepth += 0.037; // Death waypoint
    if (markerType == 4) markerDepth += 0.04; // Player marker
    gl_Position = vec4(vec2(1, -ProjMat[1][1]/ProjMat[0][0]) * map + vec2(-1, 1), markerDepth, 1);
    vertexColor = vec4(1);

    sphericalVertexDistance = 0;
    cylindricalVertexDistance = 0;
}

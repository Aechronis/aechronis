#moj_import <aechronis:gun_pose.glsl>

// One stationary, owner-only vanilla FLASH carries the server ray endpoint.
// R phase low; G phase high | hand<<1 | gait<<2 | target<<6 | 128;
// B profile<<4 | aim code (0 hip, 1 ADS, 2..8 aim-in ticks, 9..15 aim-out ticks).
// Profile occupies B high nibble; G bit 7 identifies this protocol.
// The marked 1024x512 sprite retains the ordinary 32px artwork at its top left.
// The fragment pass remaps ordinary Flash artwork inside these sprite bounds;
// only matching RGB payloads become tracers. Other sprites retain native UVs.
bool aechronis_render_trail(vec3 position, vec2 uv, vec4 color,
                           out vec4 clipPosition, out vec2 trailUV, out float fade,
                           out vec2 motion, out ivec2 flashOrigin) {
    flashOrigin = ivec2(-1);
    ivec2 atlasSize = textureSize(Sampler0, 0);
    ivec2 edge = ivec2(round(uv * vec2(atlasSize)));
    int cornerId = -1;
    ivec2 spriteOrigin = ivec2(0);
    // A staged particle draw can have any baseVertex alignment. Identify the
    // native Flash corner from its UV edge and sprite marker, never vertex ID.
    for (int candidate = 0; candidate < 4; candidate++) {
        ivec2 origin = edge - ivec2(candidate < 2 ? 1024 : 0,
                                    candidate == 0 || candidate == 3 ? 512 : 0);
        if (any(lessThan(origin, ivec2(0))) || any(greaterThanEqual(origin + ivec2(1023, 511), atlasSize))) continue;
        ivec4 marker = ivec4(round(texelFetch(Sampler0, origin, 0) * 255.0));
        ivec4 version = ivec4(round(texelFetch(Sampler0, origin + ivec2(1, 0), 0) * 255.0));
        if (all(equal(marker, ivec4(71, 84, 82, 1))) && all(equal(version, ivec4(26, 2, 2, 1)))) {
            cornerId = candidate;
            spriteOrigin = origin;
            break;
        }
    }
    if (cornerId < 0) return false;
    flashOrigin = spriteOrigin;
    ivec3 bytes = ivec3(round(color.rgb * 255.0));
    int profile = bytes.b >> 4;
    int aimCode = bytes.b & 15;
    int target = (bytes.g >> 6) & 1;
    int startLevel = (bytes.g >> 2) & 15;
    int startPhase = bytes.r + ((bytes.g & 1) << 8);
    if ((bytes.g & 128) == 0 || !aechronis_valid_profile(profile) || startLevel > 8 || startPhase >= 500) return false;
    aechronis_begin_curves(spriteOrigin + ivec2(0, 192), 1024, aechronis_authored_key(profile));
    vec2 corner = vec2(cornerId < 2 ? 1.0 : -1.0,
                       cornerId == 0 || cornerId == 3 ? -1.0 : 1.0);

    clipPosition = vec4(2.0, 2.0, 2.0, 1.0);
    trailUV = vec2(0.0);
    fade = 0.0;
    motion = vec2(0.0);
    float clock = GameTime * 24000.0;
    float elapsed = mod(clock - float(startPhase), 500.0);
    // Leaving combat switches the owner's clock band and removes the carrier.
    // Allow the final ADS phase 499 shot to finish across its 12000 clock edge.
    if (clock < 11000.0 || clock >= 12003.0 || elapsed >= 2.016) return true;

    // Native Flash uses alpha=.725-.125*(integer age+partial tick). Alpha is
    // quantized to a byte, but the shared render partial recovers integer age.
    // Reproduce Mth's sine-table phase, rather than estimating size from alpha.
    float partialTick = fract(clock);
    float particleAge = round((0.725 - color.a) * 8.0 - partialTick);
    float angle = (particleAge + partialTick - 1.0) * 0.25 * 3.1415927;
    int sineIndex = int(angle * 10430.378350470453) & 65535;
    float size = 7.1 * sin(float(sineIndex) * (6.283185307179586 / 65536.0));
    // LOOKAT_XYZ exactly cancels the camera rotation in ModelViewMat. Floating
    // GameTime precision leaves at most a small sub-block endpoint tolerance.
    vec3 endpoint = (ModelViewMat * vec4(position, 1.0)).xyz - vec3(corner * size, 0.0);
    if (endpoint.z >= -0.05) return true;

    int mode = (aimCode >= 1 && aimCode <= 8 ? 2 : 0) | ((bytes.g >> 1) & 1);
    float fireAimTime = aimCode >= 2 ? (float(aimCode - (aimCode <= 8 ? 2 : 9)) + elapsed) / 7.0 : -1.0;
    vec3 muzzle = aechronis_muzzle_view(mode, elapsed, float(startLevel), target, clock, profile, fireAimTime);
    // Held guns use a fixed 70-degree HUD projection; world particles use the
    // configured world FOV. Map the same muzzle screen point into world depth.
    // Vanilla death/fluid HUD FOV changes are not available to this shader.
    vec2 screen = max(ScreenSize, vec2(1.0));
    vec2 muzzleNdc = muzzle.xy / (-muzzle.z * tan(radians(35.0)));
    muzzleNdc.x *= screen.y / screen.x;
    vec4 reference = ProjMat * vec4(0.0, 0.0, muzzle.z, 1.0);
    vec4 startH = inverse(ProjMat) * vec4(muzzleNdc * reference.w, reference.z, reference.w);
    vec3 start = startH.xyz / startH.w;
    vec4 startClip = ProjMat * vec4(start, 1.0);
    vec4 endClip = ProjMat * vec4(endpoint, 1.0);
    vec2 direction = (endClip.xy / endClip.w - startClip.xy / startClip.w) * screen;
    float projectedLength = length(direction);
    // Native particles retain back-face culling. This side gives the unchanged
    // quad indices a counterclockwise ribbon after replacing its four vertices.
    vec2 side = projectedLength > 0.001 ? vec2(direction.y, -direction.x) / projectedLength : vec2(1.0, 0.0);
    bool atEnd = cornerId >= 2;
    clipPosition = atEnd ? endClip : startClip;
    // A broad luminous head with soft edges and a visible minimum width at distance.
    float halfWidthPixels = clamp(0.020 * abs(ProjMat[1][1]) * screen.y / clipPosition.w, 1.6, 4.2);
    clipPosition.xy += side * corner.y * halfWidthPixels * (2.0 / screen) * clipPosition.w;
    float trailLength = length(endpoint - start);
    trailUV = vec2(atEnd ? trailLength : 0.0, corner.y);
    fade = 1.0 - smoothstep(1.8, 2.016, elapsed);
    motion = vec2(elapsed, trailLength);
    return true;
}

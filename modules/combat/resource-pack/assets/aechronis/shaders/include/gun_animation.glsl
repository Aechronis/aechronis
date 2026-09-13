// Gun first-person animation. Protocol and lifecycle: combat/utils/GunAnimation.kt.
// Carrier quads store a face record in the atlas; all four UVs address its header.
// Reconstruct in view space, retaining the carrier plane's native equip height.
// The ordinary item shader remains responsible for unmarked geometry.

#moj_import <aechronis:gun_pose.glsl>

float aechronis_u16(uvec2 bytes) {
    return float(bytes.x * 256u + bytes.y);
}

uvec4 aechronis_texel(ivec2 pixel) {
    return uvec4(round(texelFetch(Sampler0, pixel, 0) * 255.0));
}

vec3 aechronis_magazine_angles(float t, float hand, int profile) {
    if (aechronis_belt(profile) || aechronis_pistol(profile)) return vec3(0.0);
    float rock = aechronis_ramp(t, 0.14, 0.23) * (1.0 - aechronis_ramp(t, 0.55, 0.67));
    float withdraw = aechronis_ramp(t, 0.21, 0.36) * (1.0 - aechronis_ramp(t, 0.44, 0.59));
    return vec3(-24.0 * rock, 0.0, -5.0 * hand * withdraw);
}

vec3 aechronis_magazine_shift(float t, float hand, int profile) {
    if (aechronis_belt(profile)) {
        float withdraw = aechronis_ramp(t, 0.16, 0.35) * (1.0 - aechronis_ramp(t, 0.55, 0.75));
        return vec3(-9.0 * hand, -4.0, 0.0) * withdraw;
    }
    if (aechronis_pistol(profile)) {
        // Straight withdrawal along the raked pistol grip, then a firm seat.
        float withdraw = aechronis_ramp(t, 0.16, 0.34) * (1.0 - aechronis_ramp(t, 0.44, 0.64));
        return vec3(-0.8 * hand, -10.0, 2.0) * withdraw;
    }
    float withdraw = aechronis_ramp(t, 0.21, 0.36) * (1.0 - aechronis_ramp(t, 0.44, 0.59));
    return vec3(-0.7 * hand, -8.0, 2.0) * withdraw;
}

vec3 aechronis_magazine_point(vec3 p, float t, float hand, int profile, out mat3 rotation) {
    rotation = aechronis_rotation(aechronis_magazine_angles(t, hand, profile));
    vec3 pivot = aechronis_profile_magazine(profile);
    return pivot + rotation * (p - pivot) + aechronis_magazine_shift(t, hand, profile);
}

float aechronis_bolt_pull(float t) {
    return aechronis_ramp(t, 0.69, 0.77) * (1.0 - aechronis_ramp(t, 0.79, 0.83));
}

vec3 aechronis_hand_point(bool support, float hand, int action, float t, int profile) {
    vec3 center = support ? aechronis_profile_support(profile) : aechronis_profile_dominant(profile);
    center.x = 8.0 + (center.x - 8.0) * hand;
    if (support && !aechronis_launcher(profile) && (action == 2 || action == 5)) {
        mat3 rotation;
        vec3 grip = aechronis_profile_magazine(profile) + vec3(-0.7 * hand, -1.0, 0.0);
        vec3 magazine = aechronis_magazine_point(grip, t, hand, profile, rotation);
        float weight = aechronis_ramp(t, 0.03, 0.17) * (1.0 - aechronis_ramp(t, 0.75, 0.95));
        center = mix(center, magazine, weight);
    }
    return center;
}

bool aechronis_animate_gun(
    vec3 inputPosition, vec3 inputNormal, vec2 inputUV, vec4 inputColor, int vertexId,
    out vec3 viewPosition, out vec3 viewNormal, out vec2 textureUV, out bool emissive
) {
    emissive = false;
    ivec2 atlasSize = textureSize(Sampler0, 0);
    ivec2 header = clamp(ivec2(inputUV * vec2(atlasSize)), ivec2(0), atlasSize - 1);
    if (any(notEqual(aechronis_texel(header), uvec4(17, 143, 81, 255)))) return false;

    uvec4 origin = aechronis_texel(header + ivec2(1, 0));
    uvec4 options = aechronis_texel(header + ivec2(2, 0));
    if (options.x > 3u || options.y > 2u || options.z > 31u || options.w != 255u) return false;
    ivec2 atlasOrigin = header - ivec2(aechronis_u16(origin.xy), aechronis_u16(origin.zw));
    int profile = int((options.z & 1u) | ((options.z >> 2u) << 1u));
    aechronis_begin_curves(atlasOrigin + ivec2(0, 192), 1024, aechronis_authored_key(profile));
    bool empty = (options.z & 2u) != 0u;
    int corner = vertexId & 3;
    ivec2 record = header + ivec2(3 + corner * 4, 0);
    uvec4 a = aechronis_texel(record);
    uvec4 b = aechronis_texel(record + ivec2(1, 0));
    uvec4 c = aechronis_texel(record + ivec2(2, 0));
    uvec4 d = aechronis_texel(record + ivec2(3, 0));
    vec3 p = (vec3(aechronis_u16(a.xy), aechronis_u16(a.zw), aechronis_u16(b.xy)) - 32768.0) / 256.0;
    vec3 n = normalize(vec3(b.zw, c.x) / 127.5 - 1.0);
    int bone = int(c.y);
    textureUV = (vec2(atlasOrigin) + vec2(aechronis_u16(c.zw), aechronis_u16(d.xy)) / (options.y == 2u ? 32.0 : 128.0)) / vec2(atlasSize);
    bool slimArms = false;
    if (bone == 3 || bone == 4) {
        // Hand faces bind the shared gun-hands atlas; the personal pack patches its skin and flags.
        // Never show a substitute skin while that pack is loading/unavailable.
        bool skinLayout = all(equal(aechronis_texel(atlasOrigin + ivec2(256, 0)), uvec4(83, 75, 73, 255)));
        if (!skinLayout || aechronis_texel(atlasOrigin + ivec2(258, 0)).x != 1u) {
            viewPosition = vec3(0.0, 0.0, 1.0);
            viewNormal = vec3(0.0, 0.0, 1.0);
            return true;
        }
        slimArms = aechronis_texel(atlasOrigin + ivec2(257, 0)).x == 1u;
        if (slimArms) {
            uvec4 slimUV = aechronis_texel(header + ivec2(19 + corner, 0));
            textureUV = (vec2(atlasOrigin) + vec2(aechronis_u16(slimUV.xy), aechronis_u16(slimUV.zw)) / (options.y == 2u ? 32.0 : 128.0)) / vec2(atlasSize);
            // Keep native sleeve inflation (.25px) instead of shrinking it.
            p.x -= sign(p.x) * 0.5;
        }
    }

    uvec3 control = uvec3(round(clamp(inputColor.rgb, 0.0, 1.0) * 255.0));
    int action = int((control.y >> 1u) & 7u);
    float start = float(control.x + ((control.y & 1u) << 8u));
    bool reload = action == 2 || action == 5;
    float duration = reload ? max(float(control.z), 1.0) : (action == 1 ? aechronis_profile_fire_ticks(profile) : 7.0);
    // All three HUD state bases (10000/11000/11500) are divisible by 500.
    // Keep the fractional tick: flooring here would turn this back into 20 FPS.
    float clock = GameTime * 24000.0;
    float elapsed = mod(clock - start, 500.0);
    uint movement = (control.y >> 4u) | (control.z << 4u);
    float startLevel = float(movement & 31u);
    int target = int((movement >> 5u) & 3u);
    float sampled = float(movement >> 7u);
    // FIRE resamples movement at its own start phase. Reserved sample values
    // 20..26 instead carry elapsed aim ticks at the shot, independently of recoil.
    bool firingAim = action == 1 && sampled >= 20.0 && sampled <= 26.0;
    bool valid = start < 500.0 && (reload ? control.z > 0u : (startLevel <= 16.0 && target <= 2 && (sampled < 20.0 || firingAim)));
    float gait = valid && !reload ? aechronis_gait(startLevel, target, firingAim ? elapsed : mod(clock - sampled, 20.0)) : 0.0;
    float fireAimTime = valid && firingAim ? (sampled - 20.0 + elapsed) / 7.0 : -1.0;
    float t = valid ? clamp(elapsed / duration, 0.0, 1.0) : 1.0;
    if (!valid) action = 0;
    if (bone == 7) {
        // One brief muzzle streak uses the same accepted-shot clock as recoil.
        // Shrink the opaque mesh instead of vertex alpha (native items cut out
        // texture alpha before multiplying vertexColor, with blending disabled).
        float pulse = aechronis_ramp(elapsed, 0.0, 0.12) * (1.0 - aechronis_ramp(elapsed, 0.3, 1.8));
        if (action != 1 || t >= 1.0 || pulse <= 0.0001) {
            viewPosition = vec3(0.0, 0.0, 1.0);
            viewNormal = vec3(0.0, 0.0, 1.0);
            return true;
        }
        uvec4 pivotXY = aechronis_texel(header + ivec2(19, 0));
        uvec4 pivotZ = aechronis_texel(header + ivec2(20, 0));
        vec3 muzzle = (vec3(aechronis_u16(pivotXY.xy), aechronis_u16(pivotXY.zw), aechronis_u16(pivotZ.xy)) - 32768.0) / 256.0;
        vec3 scale = vec3(sqrt(pulse), sqrt(pulse), pulse * mix(0.4, 1.0, aechronis_ramp(elapsed, 0.0, 0.6)));
        p = muzzle + (p - muzzle) * scale;
        n = normalize(n / scale);
        emissive = true;
    }

    float equip = options.y >= 1u ? aechronis_native_equip(inputPosition, inputNormal) : 0.0;
    AechronisGunPose gunPose = aechronis_gun_pose(int(options.x), action, t, gait, clock, equip, profile, fireAimTime);
    // The AWP uses the existing full-screen scope/reticle at full ADS.
    if (aechronis_scoped(profile) && gunPose.aim >= 0.999) {
        viewPosition = vec3(1000.0);
        viewNormal = vec3(0.0, 0.0, 1.0);
        return true;
    }
    float hand = gunPose.hand;
    if (bone == 1 || bone == 2) {
        vec3 shift = aechronis_authored_channels(profile, action, bone, 0, t, gunPose.aim, gait, clock, equip, gunPose.aimAction, gunPose.aimTime);
        vec3 angles = aechronis_authored_channels(profile, action, bone, 1, t, gunPose.aim, gait, clock, equip, gunPose.aimAction, gunPose.aimTime);
        shift.x *= hand;
        angles.yz *= hand;
        // Timeline values are local Euler channels. Combine their offsets with
        // the procedural channels before rotating about the authored pivot.
        if (reload && bone == 1) {
            shift += aechronis_magazine_shift(t, hand, profile);
            angles += aechronis_magazine_angles(t, hand, profile);
        }
        if (bone == 2) {
            if (action == 1) shift.z += (aechronis_pistol(profile) ? 2.0 : 1.45) * aechronis_ramp(t, 0.0, 0.2) * (1.0 - aechronis_ramp(t, 0.2, 0.65));
            if (reload) shift.z += (aechronis_pistol(profile) ? 2.0 : 1.65) * aechronis_bolt_pull(t);
            if (empty && aechronis_locks_open(profile)) {
                float heldBack = aechronis_pistol(profile) ? 2.0 : 1.45;
                if (reload) heldBack *= 1.0 - aechronis_ramp(t, 0.77, 0.83);
                // Last-shot recoil and the bolt catch describe one rearward
                // position, not two translations added to one another.
                shift.z = max(shift.z, heldBack);
            }
        }
        if (any(notEqual(shift, vec3(0.0))) || any(notEqual(angles, vec3(0.0)))) {
            mat3 rotation = aechronis_rotation(angles);
            vec3 pivot = aechronis_authored_pivot(profile, bone);
            p = pivot + rotation * (p - pivot) + shift;
            n = rotation * n;
        }
    }

    if (bone == 3 || bone == 4) {
        bool support = bone == 4;
        vec3 handPoint = aechronis_hand_point(support, hand, action, t, profile);
        vec3 handShift = aechronis_authored_channels(profile, action, bone, 0, t, gunPose.aim, gait, clock, equip, gunPose.aimAction, gunPose.aimTime);
        handShift.x *= hand;
        handPoint += handShift;
        vec3 grip = aechronis_gun_view(handPoint, gunPose);
        // These camera-side guides set the direction only. Each arm stays a
        // rigid 12px cuboid; it never stretches or tapers to reach the camera.
        vec3 shoulderGuide = (support ? vec3(-0.35 * hand, -1.30, -0.05) : vec3(1.05 * hand, -0.85, -0.06)) + gunPose.armOffset;
        vec3 along = normalize(grip - shoulderGuide);
        vec3 across = normalize(cross(along, vec3(0.0, 0.0, 1.0)));
        vec3 depth = cross(across, along);
        viewPosition = grip + (across * p.x + along * (p.y - 12.0) + depth * p.z) / 16.0;
        viewNormal = normalize(across * n.x + along * n.y + depth * n.z);
        return true;
    }
    n = gunPose.body * n;
    viewPosition = aechronis_gun_view(p, gunPose);
    viewNormal = normalize(n);
    return true;
}

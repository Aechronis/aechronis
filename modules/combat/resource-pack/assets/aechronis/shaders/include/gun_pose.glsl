#moj_import <aechronis:gun_profiles.glsl>
// Gun viewmodel pose shared by the item mesh and its owner-only tracer.
// Initialize the bound atlas's curve context first. Modes: hip/ADS, right/left.

mat3 aechronis_rotation(vec3 degrees) {
    vec3 a = radians(degrees);
    vec3 c = cos(a), s = sin(a);
    mat3 rx = mat3(1, 0, 0, 0, c.x, s.x, 0, -s.x, c.x);
    mat3 ry = mat3(c.y, 0, -s.y, 0, 1, 0, s.y, 0, c.y);
    mat3 rz = mat3(c.z, s.z, 0, -s.z, c.z, 0, 0, 0, 1);
    return rx * ry * rz;
}

float aechronis_ramp(float t, float begin, float end) {
    return smoothstep(begin, end, t);
}

#moj_import <aechronis:gun_curves.glsl>
#moj_import <aechronis:gun_animation_tracks.glsl>

// The item and particle passes share these authored deltas so the tracer
// continues to follow the muzzle when a whole-rig track changes the pose.
vec3 aechronis_authored_channels(int profile, int action, int bone, int channel,
                               float t, float aim, float gait, float clock, float equip,
                               int aimAction, float aimTime) {
    float walkTime = mod(clock, 25.0) / 25.0;
    float sprintTime = mod(clock, 12.5) / 12.5;
    vec3 delta = aechronis_sample_curve(0, bone, channel, walkTime);
    if (action >= 1 && action <= 5) {
        delta += aechronis_sample_curve(action, bone, channel, t);
    }
    // Recoil overlays an ongoing aim transition; it does not replace the
    // author's aim-in/out offsets or restart their original seven-tick clock.
    if (action == 1 && (aimAction == 3 || aimAction == 4) && aimTime < 1.0) {
        delta += aechronis_sample_curve(aimAction, bone, channel, max(aimTime, 0.0));
    }
    // Match built-in locomotion and native equip.
    float lowered = equip;
    float moving = action == 2 || action == 5 ? 0.0 : min(gait, 1.0) * (1.0 - lowered);
    float running = max(gait - 1.0, 0.0);
    float weight = moving * mix(1.0, 0.08, aim);
    delta += aechronis_sample_curve(8, bone, channel, walkTime) * (weight * (1.0 - running));
    delta += aechronis_sample_curve(9, bone, channel, sprintTime) * (weight * running);
    return delta;
}

// Locomotion is independent of action timing. Sixteen levels span idle, walk
// and sprint; the client eases between server samples at render-frame speed.
float aechronis_gait(float startLevel, int target, float elapsed) {
    float endLevel = float(target) * 8.0;
    float level = startLevel + clamp(endLevel - startLevel, -2.0 * elapsed, 2.0 * elapsed);
    return clamp(level / 8.0, 0.0, 2.0);
}

struct AechronisGunPose {
    float hand;
    float aim;
    int aimAction;
    float aimTime;
    mat3 body;
    vec3 translation;
    vec3 viewRoot;
    vec3 armOffset;
};

// Carrier UP planes have Y=0 after Minecraft's model-centering translation.
// Projecting onto their transformed normal removes camera/view-lag rotations.
// The dead zone covers vanilla's <=0.1-block bob translation and packed normals;
// full lowering precedes the native visible-item replacement threshold.
float aechronis_native_equip(vec3 carrierPosition, vec3 carrierNormal) {
    float height = dot(carrierPosition, normalize(carrierNormal));
    return smoothstep(0.12, 0.42, -0.52 - height);
}

AechronisGunPose aechronis_gun_pose(int mode, int action, float t, float gait, float clock, float equip, int profile, float fireAimTime) {
    AechronisGunPose pose;
    bool leftHand = (mode & 1) != 0;
    pose.hand = leftHand ? -1.0 : 1.0;
    pose.aim = mode >= 2 ? 1.0 : 0.0;
    pose.aimAction = 0;
    pose.aimTime = 0.0;
    if (action == 3) pose.aim = aechronis_ramp(t, 0.0, 1.0);
    if (action == 4) pose.aim = 1.0 - aechronis_ramp(t, 0.0, 1.0);
    if (action == 5) pose.aim = 1.0 - aechronis_ramp(t, 0.0, 0.15);
    if (action == 1 && fireAimTime >= 0.0) {
        pose.aimAction = mode >= 2 ? 3 : 4;
        pose.aimTime = fireAimTime;
        float blend = aechronis_ramp(fireAimTime, 0.0, 1.0);
        pose.aim = mode >= 2 ? blend : 1.0 - blend;
    }
    bool manualScopedCycle = action == 1 && aechronis_scoped(profile) && aechronis_profile_fire_ticks(profile) > 2.0;
    if (manualScopedCycle) {
        // Leave the optic for the hand-operated bolt action. Preserve the aim
        // amount at the shot and exit over two ticks without resetting FIRE.
        pose.aim *= 1.0 - aechronis_ramp(t * aechronis_profile_fire_ticks(profile), 0.0, 2.0);
    }

    float lowered = equip;
    pose.aim *= 1.0 - aechronis_ramp(equip, 0.0, 0.85);

    vec3 angles = vec3(0.0);
    pose.translation = vec3(0.0);
    if (action == 1) {
        // Launcher and manual-action tracks explicitly replace the sampled hip
        // impulse. Keep their baseline identical in ADS so cancellation cannot
        // turn a small authored kick into a downward recoil impulse.
        float recoilAim = aechronis_launcher(profile) || manualScopedCycle ? 0.0 : pose.aim;
        float kick = aechronis_ramp(t, 0.0, 0.16) * (1.0 - aechronis_ramp(t, 0.16, 1.0));
        angles = vec3(mix(aechronis_pistol(profile) ? 7.0 : 3.0, aechronis_pistol(profile) ? 1.8 : 0.65, recoilAim), 0.0, -0.6 * (1.0 - recoilAim)) * kick;
        pose.translation = vec3(0.0, 0.08, mix(0.8, 0.36, recoilAim)) * kick;
    } else if (action == 2 || action == 5) {
        float inspect = aechronis_ramp(t, 0.0, 0.15) * (1.0 - aechronis_ramp(t, 0.85, 1.0));
        angles = (aechronis_pistol(profile) ? vec3(-8.0, -12.0, -24.0) : vec3(-8.0, 6.0, -20.0)) * inspect;
        pose.translation = (aechronis_pistol(profile) ? vec3(-2.0, 4.0, -3.0) : vec3(-3.5, 1.7, -1.0)) * inspect;
    }
    // A compact, low-ready sprint pose, with two steps per cycle. Both periods
    // divide the 500-tick clock, so state bands and phase wrap never jump a step.
    float moving = action == 2 || action == 5 ? 0.0 : min(gait, 1.0) * (1.0 - lowered);
    float running = max(gait - 1.0, 0.0);
    float walkPhase = mod(clock, 25.0) * (6.28318530718 / 25.0);
    float runPhase = mod(clock, 12.5) * (6.28318530718 / 12.5);
    float weight = moving * mix(1.0, 0.08, pose.aim);
    vec3 walkAngles = vec3(0.5 * cos(2.0 * walkPhase), 0.55 * sin(walkPhase), 1.1 * sin(walkPhase));
    vec3 runAngles = vec3(1.2 * cos(2.0 * runPhase), 1.2 * sin(runPhase), 2.0 * sin(runPhase));
    vec3 walkShift = vec3(0.25 * sin(walkPhase), 0.16 * cos(2.0 * walkPhase), 0.10 * sin(2.0 * walkPhase));
    vec3 runShift = vec3(0.40 * sin(runPhase), 0.40 * cos(2.0 * runPhase), 0.16 * cos(runPhase));
    angles += mix(walkAngles, runAngles, running) * weight;
    pose.translation += mix(walkShift, runShift, running) * weight;
    float sprintPose = moving * running * (1.0 - pose.aim);
    angles += vec3(-12.0, 14.0, -18.0) * sprintPose;
    pose.translation += vec3(0.5, -1.6, 0.6) * sprintPose;

    // Native equip lowers the whole rig, including the shoulder guides used by
    // the rigid native-size arms.
    angles += vec3(-30.0, 12.0, -10.0) * lowered;
    if (aechronis_launcher(profile) && (action == 2 || action == 5)) {
        float replace = aechronis_ramp(t, 0.12, 0.35) * (1.0 - aechronis_ramp(t, 0.65, 0.95));
        angles = vec3(-25.0, 0.0, -10.0) * replace;
        pose.translation = vec3(0.0, -24.0, 8.0) * replace;
    }
    angles += aechronis_authored_channels(profile, action, 0, 1, t, pose.aim, gait, clock, equip, pose.aimAction, pose.aimTime);
    pose.translation += aechronis_authored_channels(profile, action, 0, 0, t, pose.aim, gait, clock, equip, pose.aimAction, pose.aimTime);
    angles.yz *= pose.hand;
    pose.translation.x *= pose.hand;
    pose.body = aechronis_rotation(angles);

    vec3 hipRoot = aechronis_profile_hip(profile);
    hipRoot.x *= pose.hand;
    vec3 sight = aechronis_profile_sight(profile);
    vec3 adsRoot = vec3(-(sight.x - 8.0) / 16.0, -(sight.y - 8.0) / 16.0, sight.z);
    pose.armOffset = vec3(pose.hand * 0.12, -1.2, 0.20) * lowered;
    pose.viewRoot = mix(hipRoot, adsRoot, pose.aim) + pose.armOffset;
    return pose;
}

vec3 aechronis_gun_view(vec3 modelPosition, AechronisGunPose pose) {
    vec3 grip = vec3(8.0, 6.0, 12.0);
    vec3 p = grip + pose.body * (modelPosition - grip) + pose.translation;
    return pose.viewRoot + (p - vec3(8.0)) / 16.0;
}

vec3 aechronis_muzzle_view(int mode, float elapsed, float startLevel, int target, float clock, int profile, float fireAimTime) {
    // Source barrel-opening centres, quantized exactly as the item's fixed-point
    // face metadata so the particle pass follows the same authored muzzle.
    vec3 muzzle = aechronis_profile_muzzle(profile);
    float gait = aechronis_gait(startLevel, target, elapsed);
    return aechronis_gun_view(muzzle, aechronis_gun_pose(mode, 1, clamp(elapsed / aechronis_profile_fire_ticks(profile), 0.0, 1.0), gait, clock, 0.0, profile, fireAimTime));
}

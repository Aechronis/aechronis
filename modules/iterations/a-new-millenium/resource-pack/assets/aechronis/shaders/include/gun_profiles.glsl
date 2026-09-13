// Built combat profiles. Edit rigs in saved Blockbench projects and Build combat assets.
// profile 0 ak74 64
// profile 1 glock17 44
// profile 2 m4a1 50
// profile 3 ak12 60
// profile 4 qbz-95 60
// profile 5 g3 60
// profile 6 awp 80
// profile 7 m9 40
// profile 8 mg3 100
// profile 9 at4 100
// profile 10 mp5 48
// profile 11 vz61 60
bool aechronis_valid_profile(int profile) { return profile == 0 || profile == 1 || profile == 2 || profile == 3 || profile == 4 || profile == 5 || profile == 6 || profile == 7 || profile == 8 || profile == 9 || profile == 10 || profile == 11; }
bool aechronis_pistol(int profile) { return profile == 1 || profile == 7; }
bool aechronis_belt(int profile) { return profile == 8; }
bool aechronis_launcher(int profile) { return profile == 9; }
bool aechronis_scoped(int profile) { return profile == 6; }
bool aechronis_locks_open(int profile) { return profile == 1 || profile == 7 || profile == 11; }
float aechronis_profile_fire_ticks(int profile) {
    if (profile == 6) return 24.0;
    return 2.0;
}
vec3 aechronis_profile_hip(int profile) {
    if (profile == 0) return vec3(0.4, -0.2, -1.1);
    if (profile == 1) return vec3(0.3, -0.27, -1.18);
    if (profile == 2) return vec3(0.4, -0.2, -1.1);
    if (profile == 3) return vec3(0.4, -0.2, -1.1);
    if (profile == 4) return vec3(0.4, -0.2, -1.1);
    if (profile == 5) return vec3(0.4, -0.2, -1.1);
    if (profile == 6) return vec3(0.4, -0.2, -1.1);
    if (profile == 7) return vec3(0.3, -0.27, -1.18);
    if (profile == 8) return vec3(0.4, -0.2, -1.1);
    if (profile == 9) return vec3(0.4, -0.2, -0.4);
    if (profile == 10) return vec3(0.4, -0.2, -1.1);
    if (profile == 11) return vec3(0.4, -0.2, -1.1);
    return vec3(0.0);
}
vec3 aechronis_profile_sight(int profile) {
    if (profile == 0) return vec3(7.82912, 10.01477, -1.1);
    if (profile == 1) return vec3(8.01875, 10.5102, -1.18);
    if (profile == 2) return vec3(7.93251, 9.637315, -1.1);
    if (profile == 3) return vec3(7.98131, 8.9414, -1.1);
    if (profile == 4) return vec3(7.94, 11.9, -1.1);
    if (profile == 5) return vec3(8.0, 7.28014, -1.1);
    if (profile == 6) return vec3(8.0, 8.825, -1.1);
    if (profile == 7) return vec3(8.225, 10.26807, -1.18);
    if (profile == 8) return vec3(8.796875, 8.47932, -1.1);
    if (profile == 9) return vec3(6.894115, 7.86148, -0.4);
    if (profile == 10) return vec3(8.0, 9.26, -1.1);
    if (profile == 11) return vec3(8.1951, 9.47, -1.1);
    return vec3(0.0);
}
vec3 aechronis_profile_magazine(int profile) {
    if (profile == 0) return vec3(7.825, 7.8, 8.65);
    if (profile == 1) return vec3(8.02, 0.6, 12.5);
    if (profile == 2) return vec3(7.933, 7.221, 8.074);
    if (profile == 3) return vec3(7.981, 6.82, 7.912);
    if (profile == 4) return vec3(8.0, 8.487, 11.208);
    if (profile == 5) return vec3(8.0, 5.28, 7.79);
    if (profile == 6) return vec3(8.0, 5.875, 12.977);
    if (profile == 7) return vec3(8.225, 8.868, 11.978);
    if (profile == 8) return vec3(6.836, 7.94, 9.947);
    if (profile == 9) return vec3(7.825, 7.8, 9.5);
    if (profile == 10) return vec3(8.0, 7.429, 4.216);
    if (profile == 11) return vec3(8.195, 6.68, 6.214);
    return vec3(0.0);
}
vec3 aechronis_profile_dominant(int profile) {
    if (profile == 0) return vec3(8.65, 5.7, 14.25);
    if (profile == 1) return vec3(8.82, 5.5, 13.0);
    if (profile == 2) return vec3(8.65, 5.7, 14.25);
    if (profile == 3) return vec3(8.65, 5.7, 14.25);
    if (profile == 4) return vec3(8.8, 5.1, 7.2);
    if (profile == 5) return vec3(8.8, 4.0, 13.0);
    if (profile == 6) return vec3(8.8, 4.7, 17.0);
    if (profile == 7) return vec3(8.82, 5.5, 13.0);
    if (profile == 8) return vec3(9.0, 4.5, 13.0);
    if (profile == 9) return vec3(8.8, 7.65, 5.85);
    if (profile == 10) return vec3(8.65, 5.7, 14.25);
    if (profile == 11) return vec3(8.8, 5.2, 12.0);
    return vec3(0.0);
}
vec3 aechronis_profile_support(int profile) {
    if (profile == 0) return vec3(7.83, 5.9, 3.9);
    if (profile == 1) return vec3(7.02, 5.3, 10.7);
    if (profile == 2) return vec3(7.83, 5.9, 3.9);
    if (profile == 3) return vec3(7.83, 5.9, 3.9);
    if (profile == 4) return vec3(7.2, 6.2, 1.0);
    if (profile == 5) return vec3(7.2, 4.8, 2.0);
    if (profile == 6) return vec3(7.2, 5.8, 3.0);
    if (profile == 7) return vec3(7.02, 5.3, 10.7);
    if (profile == 8) return vec3(7.3, 5.4, 2.0);
    if (profile == 9) return vec3(7.2, 5.8, 3.0);
    if (profile == 10) return vec3(7.83, 5.9, 3.9);
    if (profile == 11) return vec3(7.2, 5.5, 6.0);
    return vec3(0.0);
}
vec3 aechronis_profile_muzzle(int profile) {
    if (profile == 0) return vec3(7.828125, 8.23828125, -7.1640625);
    if (profile == 1) return vec3(8.23046875, 9.6484375, 0.953125);
    if (profile == 2) return vec3(7.93359375, 7.69140625, -5.08203125);
    if (profile == 3) return vec3(7.98046875, 7.46484375, -6.171875);
    if (profile == 4) return vec3(7.90625, 7.67578125, -9.421875);
    if (profile == 5) return vec3(8.0, 5.8046875, -6.16796875);
    if (profile == 6) return vec3(8.0, 6.2421875, -8.24609375);
    if (profile == 7) return vec3(8.20703125, 9.390625, 0.609375);
    if (profile == 8) return vec3(8.796875, 7.05078125, -4.19140625);
    if (profile == 9) return vec3(8.078125, 6.671875, -2.1875);
    if (profile == 10) return vec3(8.0, 7.48828125, -4.12109375);
    if (profile == 11) return vec3(8.1875, 7.8203125, 0.12890625);
    return vec3(0.0);
}

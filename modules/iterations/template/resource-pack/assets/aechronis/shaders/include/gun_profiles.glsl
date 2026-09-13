// Built combat profiles. Edit rigs in saved Blockbench projects and Build combat assets.
// profile 12 ak47 60
bool aechronis_valid_profile(int profile) { return profile == 12; }
bool aechronis_pistol(int profile) { return false; }
bool aechronis_belt(int profile) { return false; }
bool aechronis_launcher(int profile) { return false; }
bool aechronis_scoped(int profile) { return false; }
bool aechronis_locks_open(int profile) { return false; }
float aechronis_profile_fire_ticks(int profile) {
    return 2.0;
}
vec3 aechronis_profile_hip(int profile) {
    if (profile == 12) return vec3(0.4, -0.2, -1.1);
    return vec3(0.0);
}
vec3 aechronis_profile_sight(int profile) {
    if (profile == 12) return vec3(7.99724, 8.66608, -1.1);
    return vec3(0.0);
}
vec3 aechronis_profile_magazine(int profile) {
    if (profile == 12) return vec3(7.998, 5.237, 5.716);
    return vec3(0.0);
}
vec3 aechronis_profile_dominant(int profile) {
    if (profile == 12) return vec3(8.8, 4.8, 11.8);
    return vec3(0.0);
}
vec3 aechronis_profile_support(int profile) {
    if (profile == 12) return vec3(7.2, 6.2, 1.0);
    return vec3(0.0);
}
vec3 aechronis_profile_muzzle(int profile) {
    if (profile == 12) return vec3(7.9609375, 6.5, -8.78125);
    return vec3(0.0);
}

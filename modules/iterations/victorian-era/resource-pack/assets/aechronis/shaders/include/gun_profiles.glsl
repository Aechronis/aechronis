// Built combat profiles. Edit rigs in saved Blockbench projects and Build combat assets.
// profile 0 dreyse-needle-gun 60
// profile 1 chassepot 64
// profile 2 martini-henry 72
// profile 3 berdan-ii-rifle 64
// profile 4 m1867-werndl-holub 68
// profile 5 vetterli-model-1870 56
// profile 6 adams-mkiii-revolver 100
// profile 7 colt-1851-navy-revolver 120
// profile 8 coach-gun 80
// profile 10 mauser-model-1871-jaegerbuechse 60
// profile 11 chassepot-carbine 56
// profile 12 mauser-model-1871 64
// profile 9 winchester-model-1873 100
// profile 13 spencer-model-1860 84
// profile 14 colt-single-action-army 120
// profile 15 enfield-pattern-1853 120
bool aechronis_valid_profile(int profile) { return profile == 0 || profile == 1 || profile == 2 || profile == 3 || profile == 4 || profile == 5 || profile == 6 || profile == 7 || profile == 8 || profile == 10 || profile == 11 || profile == 12 || profile == 9 || profile == 13 || profile == 14 || profile == 15; }
bool aechronis_pistol(int profile) { return profile == 6 || profile == 7 || profile == 14; }
bool aechronis_belt(int profile) { return false; }
bool aechronis_launcher(int profile) { return false; }
bool aechronis_scoped(int profile) { return false; }
bool aechronis_locks_open(int profile) { return false; }
float aechronis_profile_fire_ticks(int profile) {
    if (profile == 0) return 6.0;
    if (profile == 1) return 6.0;
    if (profile == 2) return 6.0;
    if (profile == 3) return 6.0;
    if (profile == 10) return 6.0;
    if (profile == 11) return 6.0;
    if (profile == 12) return 6.0;
    if (profile == 9) return 12.0;
    if (profile == 13) return 12.0;
    if (profile == 14) return 6.0;
    if (profile == 15) return 6.0;
    return 2.0;
}
vec3 aechronis_profile_hip(int profile) {
    if (profile == 0) return vec3(0.4, -0.2, -1.1);
    if (profile == 1) return vec3(0.4, -0.2, -1.1);
    if (profile == 2) return vec3(0.4, -0.2, -1.1);
    if (profile == 3) return vec3(0.4, -0.2, -1.1);
    if (profile == 4) return vec3(0.4, -0.2, -1.1);
    if (profile == 5) return vec3(0.4, -0.2, -1.1);
    if (profile == 6) return vec3(0.3, -0.27, -1.18);
    if (profile == 7) return vec3(0.3, -0.27, -1.18);
    if (profile == 8) return vec3(0.4, -0.2, -1.1);
    if (profile == 10) return vec3(0.4, -0.2, -1.1);
    if (profile == 11) return vec3(0.4, -0.2, -1.1);
    if (profile == 12) return vec3(0.4, -0.2, -1.1);
    if (profile == 9) return vec3(0.4, -0.2, -1.1);
    if (profile == 13) return vec3(0.4, -0.2, -1.1);
    if (profile == 14) return vec3(0.3, -0.27, -1.18);
    if (profile == 15) return vec3(0.4, -0.2, -1.1);
    return vec3(0.0);
}
vec3 aechronis_profile_sight(int profile) {
    if (profile == 0) return vec3(8.0005, 8.68442, -1.1);
    if (profile == 1) return vec3(8.0, 8.99017, -1.1);
    if (profile == 2) return vec3(8.00100505, 9.06518209, -1.1);
    if (profile == 3) return vec3(8.0, 8.99017, -1.1);
    if (profile == 4) return vec3(8.0, 9.03319567, -1.1);
    if (profile == 5) return vec3(8.0, 9.16, -1.1);
    if (profile == 6) return vec3(8.0, 9.31, -1.18);
    if (profile == 7) return vec3(8.0, 9.18, -1.18);
    if (profile == 8) return vec3(8.0, 9.205, -1.1);
    if (profile == 10) return vec3(8.0, 9.23, -1.1);
    if (profile == 11) return vec3(8.0, 8.99017, -1.1);
    if (profile == 12) return vec3(8.0, 9.23, -1.1);
    if (profile == 9) return vec3(8.0, 9.14, -1.1);
    if (profile == 13) return vec3(8.0, 9.14, -1.1);
    if (profile == 14) return vec3(8.0, 9.67, -1.18);
    if (profile == 15) return vec3(8.0, 9.14, -1.1);
    return vec3(0.0);
}
vec3 aechronis_profile_magazine(int profile) {
    if (profile == 0) return vec3(8.0, 8.37879, 8.4);
    if (profile == 1) return vec3(8.0, 8.37879, 8.4);
    if (profile == 2) return vec3(8.0, 7.00245, 10.08749);
    if (profile == 3) return vec3(8.0, 8.37879, 8.4);
    if (profile == 4) return vec3(8.294, 8.52343, 11.30179);
    if (profile == 5) return vec3(8.0, 8.5, 9.15);
    if (profile == 6) return vec3(8.0, 8.31, 9.87);
    if (profile == 7) return vec3(8.0, 8.3, 9.8);
    if (profile == 8) return vec3(8.0, 8.12, 10.15);
    if (profile == 10) return vec3(8.0, 8.5, 9.15);
    if (profile == 11) return vec3(8.0, 8.37879, 8.4);
    if (profile == 12) return vec3(8.0, 8.5, 9.15);
    if (profile == 9) return vec3(8.0, 6.9, 10.5);
    if (profile == 13) return vec3(8.0, 7.1, 9.4);
    if (profile == 14) return vec3(8.0, 8.25, 9.0);
    if (profile == 15) return vec3(8.0, 6.94, -3.0);
    return vec3(0.0);
}
vec3 aechronis_profile_dominant(int profile) {
    if (profile == 0) return vec3(8.8, 5.7, 13.8);
    if (profile == 1) return vec3(8.8, 5.7, 13.8);
    if (profile == 2) return vec3(8.8, 5.7, 13.8);
    if (profile == 3) return vec3(8.8, 5.7, 13.8);
    if (profile == 4) return vec3(8.8, 5.7, 13.8);
    if (profile == 5) return vec3(8.8, 5.7, 13.8);
    if (profile == 6) return vec3(8.8, 4.72, 11.6);
    if (profile == 7) return vec3(8.8, 5.1, 11.9);
    if (profile == 8) return vec3(8.8, 5.7, 13.8);
    if (profile == 10) return vec3(8.8, 5.7, 13.8);
    if (profile == 11) return vec3(8.8, 5.7, 13.8);
    if (profile == 12) return vec3(8.8, 5.7, 13.8);
    if (profile == 9) return vec3(8.8, 5.7, 13.4);
    if (profile == 13) return vec3(8.8, 5.7, 12.6);
    if (profile == 14) return vec3(8.8, 5.1, 11.9);
    if (profile == 15) return vec3(8.8, 5.7, 13.8);
    return vec3(0.0);
}
vec3 aechronis_profile_support(int profile) {
    if (profile == 0) return vec3(7.7, 6.1, 1.8);
    if (profile == 1) return vec3(7.7, 6.1, 1.8);
    if (profile == 2) return vec3(7.7, 6.1, 1.8);
    if (profile == 3) return vec3(7.7, 6.1, 1.8);
    if (profile == 4) return vec3(7.7, 6.1, 1.8);
    if (profile == 5) return vec3(7.7, 6.1, 1.8);
    if (profile == 6) return vec3(7.15, 4.46, 11.4);
    if (profile == 7) return vec3(7.15, 4.9, 11.7);
    if (profile == 8) return vec3(7.7, 6.4, 4.7);
    if (profile == 10) return vec3(7.7, 6.1, 1.8);
    if (profile == 11) return vec3(7.7, 6.1, 1.8);
    if (profile == 12) return vec3(7.7, 6.1, 1.8);
    if (profile == 9) return vec3(7.7, 6.1, 1.8);
    if (profile == 13) return vec3(7.7, 6.1, 1.8);
    if (profile == 14) return vec3(7.15, 4.9, 11.7);
    if (profile == 15) return vec3(7.7, 6.1, 1.8);
    return vec3(0.0);
}
vec3 aechronis_profile_muzzle(int profile) {
    if (profile == 0) return vec3(8.0, 7.98828125, -18.0);
    if (profile == 1) return vec3(8.0, 8.38671875, -17.12890625);
    if (profile == 2) return vec3(8.0, 8.38671875, -14.9296875);
    if (profile == 3) return vec3(8.0, 8.38671875, -14.328125);
    if (profile == 4) return vec3(8.0, 8.48828125, -15.4296875);
    if (profile == 5) return vec3(8.0, 8.5, -15.1015625);
    if (profile == 6) return vec3(8.0, 8.51171875, 1.80078125);
    if (profile == 7) return vec3(8.0, 8.55078125, -0.0390625);
    if (profile == 8) return vec3(8.0, 8.5, -8.0703125);
    if (profile == 10) return vec3(8.0, 8.5, -13.2109375);
    if (profile == 11) return vec3(8.0, 8.38671875, -13.02734375);
    if (profile == 12) return vec3(8.0, 8.5, -16.2109375);
    if (profile == 9) return vec3(8.0, 8.5, -11.44921875);
    if (profile == 13) return vec3(8.0, 8.5, -9.73046875);
    if (profile == 14) return vec3(8.0, 8.55078125, -0.94140625);
    if (profile == 15) return vec3(8.0, 8.5, -15.55859375);
    return vec3(0.0);
}

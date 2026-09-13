// Generated dispatcher; individual track files remain editable in Blockbench.
#moj_import <aechronis:gun_animation_tracks_ak74.glsl>
#moj_import <aechronis:gun_animation_tracks_glock17.glsl>
#moj_import <aechronis:gun_animation_tracks_m4a1.glsl>
#moj_import <aechronis:gun_animation_tracks_ak12.glsl>
#moj_import <aechronis:gun_animation_tracks_qbz_95.glsl>
#moj_import <aechronis:gun_animation_tracks_g3.glsl>
#moj_import <aechronis:gun_animation_tracks_awp.glsl>
#moj_import <aechronis:gun_animation_tracks_m9.glsl>
#moj_import <aechronis:gun_animation_tracks_mg3.glsl>
#moj_import <aechronis:gun_animation_tracks_at4.glsl>
#moj_import <aechronis:gun_animation_tracks_mp5.glsl>
#moj_import <aechronis:gun_animation_tracks_vz61.glsl>

int aechronis_authored_key(int profile) {
    if (profile == 0) return int(aechronis_curve_key_ak74());
    if (profile == 1) return int(aechronis_curve_key_glock17());
    if (profile == 2) return int(aechronis_curve_key_m4a1());
    if (profile == 3) return int(aechronis_curve_key_ak12());
    if (profile == 4) return int(aechronis_curve_key_qbz_95());
    if (profile == 5) return int(aechronis_curve_key_g3());
    if (profile == 6) return int(aechronis_curve_key_awp());
    if (profile == 7) return int(aechronis_curve_key_m9());
    if (profile == 8) return int(aechronis_curve_key_mg3());
    if (profile == 9) return int(aechronis_curve_key_at4());
    if (profile == 10) return int(aechronis_curve_key_mp5());
    if (profile == 11) return int(aechronis_curve_key_vz61());
    return 0;
}

vec3 aechronis_authored_pivot(int profile, int bone) {
    if (profile == 0) return aechronis_authored_pivot_ak74(bone);
    if (profile == 1) return aechronis_authored_pivot_glock17(bone);
    if (profile == 2) return aechronis_authored_pivot_m4a1(bone);
    if (profile == 3) return aechronis_authored_pivot_ak12(bone);
    if (profile == 4) return aechronis_authored_pivot_qbz_95(bone);
    if (profile == 5) return aechronis_authored_pivot_g3(bone);
    if (profile == 6) return aechronis_authored_pivot_awp(bone);
    if (profile == 7) return aechronis_authored_pivot_m9(bone);
    if (profile == 8) return aechronis_authored_pivot_mg3(bone);
    if (profile == 9) return aechronis_authored_pivot_at4(bone);
    if (profile == 10) return aechronis_authored_pivot_mp5(bone);
    if (profile == 11) return aechronis_authored_pivot_vz61(bone);
    return vec3(0.0);
}

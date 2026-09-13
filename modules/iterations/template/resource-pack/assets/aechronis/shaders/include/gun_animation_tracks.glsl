// Generated dispatcher; individual track files remain editable in Blockbench.
#moj_import <aechronis:gun_animation_tracks_ak47.glsl>

int aechronis_authored_key(int profile) {
    if (profile == 12) return int(aechronis_curve_key_ak47());
    return 0;
}

vec3 aechronis_authored_pivot(int profile, int bone) {
    if (profile == 12) return aechronis_authored_pivot_ak47(bone);
    return vec3(0.0);
}

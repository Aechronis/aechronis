// Generated dispatcher; individual track files remain editable in Blockbench.
#moj_import <aechronis:gun_animation_tracks_dreyse_needle_gun.glsl>
#moj_import <aechronis:gun_animation_tracks_chassepot.glsl>
#moj_import <aechronis:gun_animation_tracks_martini_henry.glsl>
#moj_import <aechronis:gun_animation_tracks_berdan_ii_rifle.glsl>
#moj_import <aechronis:gun_animation_tracks_m1867_werndl_holub.glsl>
#moj_import <aechronis:gun_animation_tracks_vetterli_model_1870.glsl>
#moj_import <aechronis:gun_animation_tracks_adams_mkiii_revolver.glsl>
#moj_import <aechronis:gun_animation_tracks_colt_1851_navy_revolver.glsl>
#moj_import <aechronis:gun_animation_tracks_coach_gun.glsl>
#moj_import <aechronis:gun_animation_tracks_mauser_model_1871_jaegerbuechse.glsl>
#moj_import <aechronis:gun_animation_tracks_chassepot_carbine.glsl>
#moj_import <aechronis:gun_animation_tracks_mauser_model_1871.glsl>

int aechronis_authored_key(int profile) {
    if (profile == 0) return int(aechronis_curve_key_dreyse_needle_gun());
    if (profile == 1) return int(aechronis_curve_key_chassepot());
    if (profile == 2) return int(aechronis_curve_key_martini_henry());
    if (profile == 3) return int(aechronis_curve_key_berdan_ii_rifle());
    if (profile == 4) return int(aechronis_curve_key_m1867_werndl_holub());
    if (profile == 5) return int(aechronis_curve_key_vetterli_model_1870());
    if (profile == 6) return int(aechronis_curve_key_adams_mkiii_revolver());
    if (profile == 7) return int(aechronis_curve_key_colt_1851_navy_revolver());
    if (profile == 8) return int(aechronis_curve_key_coach_gun());
    if (profile == 10) return int(aechronis_curve_key_mauser_model_1871_jaegerbuechse());
    if (profile == 11) return int(aechronis_curve_key_chassepot_carbine());
    if (profile == 12) return int(aechronis_curve_key_mauser_model_1871());
    return 0;
}

vec3 aechronis_authored_pivot(int profile, int bone) {
    if (profile == 0) return aechronis_authored_pivot_dreyse_needle_gun(bone);
    if (profile == 1) return aechronis_authored_pivot_chassepot(bone);
    if (profile == 2) return aechronis_authored_pivot_martini_henry(bone);
    if (profile == 3) return aechronis_authored_pivot_berdan_ii_rifle(bone);
    if (profile == 4) return aechronis_authored_pivot_m1867_werndl_holub(bone);
    if (profile == 5) return aechronis_authored_pivot_vetterli_model_1870(bone);
    if (profile == 6) return aechronis_authored_pivot_adams_mkiii_revolver(bone);
    if (profile == 7) return aechronis_authored_pivot_colt_1851_navy_revolver(bone);
    if (profile == 8) return aechronis_authored_pivot_coach_gun(bone);
    if (profile == 10) return aechronis_authored_pivot_mauser_model_1871_jaegerbuechse(bone);
    if (profile == 11) return aechronis_authored_pivot_chassepot_carbine(bone);
    if (profile == 12) return aechronis_authored_pivot_mauser_model_1871(bone);
    return vec3(0.0);
}

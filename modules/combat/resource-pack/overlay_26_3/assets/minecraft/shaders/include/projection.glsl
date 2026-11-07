// Replaces the base pack's zooming projection.glsl: 26.3's #include is not known to
// de-duplicate globals.glsl, so 26.3 clients keep vanilla's projection and get no aim zoom.
layout(std140) uniform Projection {
    mat4 ProjMat;
};

#define AechronisRawProjMat ProjMat

vec4 projection_from_position(vec4 position) {
    vec4 projection = position * 0.5;
    projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = position.zw;
    return projection;
}

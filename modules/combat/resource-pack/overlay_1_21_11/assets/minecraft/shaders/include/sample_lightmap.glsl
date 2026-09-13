// Minecraft 1.21.11 lightmap lookup for the shared animation shaders.
vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texelFetch(lightMap, uv / 16, 0);
}

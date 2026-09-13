// Authored curves live in the bound atlas, keeping shader size independent of
// the number of models and keys. Call begin once before evaluating a gun pose.
ivec2 aechronis_curve_origin = ivec2(0);
int aechronis_curve_width = 1;
int aechronis_curve_capacity = 0;
int aechronis_curve_block = -1;

uvec4 aechronis_curve_texel(int pixel) {
    ivec2 address = aechronis_curve_origin + ivec2(pixel % aechronis_curve_width, pixel / aechronis_curve_width);
    return uvec4(round(texelFetch(Sampler0, address, 0) * 255.0));
}

int aechronis_curve_u24(uvec3 bytes) {
    return int((bytes.x << 16u) | (bytes.y << 8u) | bytes.z);
}

void aechronis_begin_curves(ivec2 origin, int width, int key) {
    aechronis_curve_block = -1;
    ivec2 size = textureSize(Sampler0, 0);
    if (width <= 0 || key < 0 || any(lessThan(origin, ivec2(0))) ||
        origin.x + width > size.x || origin.y >= size.y) return;
    aechronis_curve_origin = origin;
    aechronis_curve_width = width;
    aechronis_curve_capacity = (size.y - origin.y) * width;
    if (any(notEqual(aechronis_curve_texel(0), uvec4(65, 67, 86, 255)))) return;
    uvec4 header = aechronis_curve_texel(1);
    if (header.x != 1u || header.w != 255u) return;
    int count = int((header.y << 8u) | header.z);
    if (count == 0 || 2 + count * 2 > aechronis_curve_capacity) return;
    int low = 0;
    int high = count - 1;
    // The universal dictionary has a uint16 count; individual channel searches
    // below remain bounded by the author's 192 union boundaries.
    while (low <= high) {
        int middle = (low + high) / 2;
        int candidate = aechronis_curve_u24(aechronis_curve_texel(2 + middle * 2).rgb);
        if (candidate == key) {
            int block = aechronis_curve_u24(aechronis_curve_texel(3 + middle * 2).rgb);
            if (block >= 2 + count * 2 && block + 100 <= aechronis_curve_capacity) aechronis_curve_block = block;
            return;
        }
        if (candidate < key) low = middle + 1;
        else high = middle - 1;
    }
}

// Four little-endian float32 values occupy the RGB bytes of six opaque texels.
// The last two bytes are padding; alpha is never part of animation data.
vec4 aechronis_curve_point(int pixel) {
    uvec3 a = aechronis_curve_texel(pixel).rgb;
    uvec3 b = aechronis_curve_texel(pixel + 1).rgb;
    uvec3 c = aechronis_curve_texel(pixel + 2).rgb;
    uvec3 d = aechronis_curve_texel(pixel + 3).rgb;
    uvec3 e = aechronis_curve_texel(pixel + 4).rgb;
    uvec3 f = aechronis_curve_texel(pixel + 5).rgb;
    return uintBitsToFloat(uvec4(
        a.x | (a.y << 8u) | (a.z << 16u) | (b.x << 24u),
        b.y | (b.z << 8u) | (c.x << 16u) | (c.y << 24u),
        c.z | (d.x << 8u) | (d.y << 16u) | (d.z << 24u),
        e.x | (e.y << 8u) | (e.z << 16u) | (f.x << 24u)));
}

float aechronis_curve_time(int pixel) {
    uvec3 a = aechronis_curve_texel(pixel + 4).rgb;
    uint last = aechronis_curve_texel(pixel + 5).r;
    return uintBitsToFloat(a.x | (a.y << 8u) | (a.z << 16u) | (last << 24u));
}

vec3 aechronis_sample_curve(int clip, int bone, int channel, float t) {
    if (aechronis_curve_block < 0 || clip < 0 || clip > 9 || bone < 0 || bone > 4 || channel < 0 || channel > 1) return vec3(0.0);
    uvec4 range = aechronis_curve_texel(aechronis_curve_block + (clip * 5 + bone) * 2 + channel);
    int count = int(range.z);
    if (count == 0 || count > 192 || range.w != 255u) return vec3(0.0);
    int first = int((range.x << 8u) | range.y);
    int base = aechronis_curve_block + 100 + first * 12;
    if (base + count * 12 > aechronis_curve_capacity) return vec3(0.0);
    int low = 0;
    int high = count - 1;
    // A strictly shrinking interval needs at most eight comparisons. A dynamic
    // loop avoids driver unrolling of the texture decoder at every call site.
    while (low < high) {
        int middle = (low + high + 1) / 2;
        if (t >= aechronis_curve_time(base + middle * 12)) low = middle;
        else high = middle - 1;
    }
    vec4 a = aechronis_curve_point(base + low * 12);
    vec4 b = aechronis_curve_point(base + low * 12 + 6);
    float duration = b.w - a.w;
    return duration > 0.0 ? mix(a.xyz, b.xyz, clamp((t - a.w) / duration, 0.0, 1.0)) : a.xyz;
}

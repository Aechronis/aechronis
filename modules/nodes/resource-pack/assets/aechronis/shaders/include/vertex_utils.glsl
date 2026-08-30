#define PI 3.14159

int id(vec4 color) {
    color = round(color * 255);
    return int(color.r * 0x10000 + color.g * 0x100 + color.b);
}

int id(ivec2 uv)
{
    return id(texelFetch(Sampler0, uv, 0));
}

int floor_div(int value, int divisor) {
    return value >= 0 ? value / divisor : -((-value + divisor - 1) / divisor);
}

int floor_mod(int value, int divisor) {
    return value - floor_div(value, divisor) * divisor;
}

int wrapped_chunk_delta(int encodedChunk, int cameraChunk) {
    int delta = encodedChunk - floor_mod(cameraChunk, 256);
    if (delta > 127) delta -= 256;
    if (delta < -128) delta += 256;
    return delta;
}

mat2 mat2_rotate_z(float radians) {
    return mat2(
        cos(radians), -sin(radians),
        sin(radians), cos(radians)
    );
}

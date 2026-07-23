#version 330

// Shared NTSC-M signal model.
//
// The composite raster is sampled at 4*fsc, the standard digital-composite
// rate (SMPTE 244M / D-2):
//   subcarrier fsc = 315/88 MHz = 3.5795454 MHz
//   sample rate    = 4*fsc = 14.318182 MHz -> exactly 90 deg of subcarrier
//                    phase per sample
//   line rate      = 2*fsc/455 = 15734.27 Hz -> exactly 910 samples per
//                    63.556 us line
//   frame          = 525 lines, two interlaced fields at 59.94 Hz
//
// Line timing in samples from the hsync leading edge (0H):
//   sync 4.7 us [2,69) | burst 9 cycles [78,114) | active 52.7 us [134,888)
//   front porch 1.5 us closes the line.

const float PI = 3.14159265358979;
const float TAU = 6.28318530717959;
const float HALF_PI = 1.57079632679490;

const int SAMPLES_PER_LINE = 910;
const int LINES_PER_FRAME = 525;
const int ACTIVE_START = 134;
const int ACTIVE_WIDTH = 754;
const int VBI_LINES = 39;      // rows [0,39): vertical sync + blanking
const int VISIBLE_LINES = 486;

const float IRE_SYNC = -40.0;
const float IRE_BLACK = 7.5;   // NTSC setup
const float IRE_VIDEO = 92.5;  // black-to-white span
const float BURST_IRE = 20.0;  // half-amplitude of the 40 IRE p-p burst
const float CHROMA_AXIS = 0.57595865316;  // I/Q axes lead the burst by 33 deg

// composite signal <-> stored texel: [-50,150] IRE spans [0,1]
float packIre(float v) { return (v + 50.0) / 200.0; }
float unpackIre(float v) { return v * 200.0 - 50.0; }

// FCC Y'IQ on gamma-corrected R'G'B', as broadcast NTSC used
vec3 rgbToYiq(vec3 c) {
    return vec3(
        dot(c, vec3(0.299, 0.587, 0.114)),
        dot(c, vec3(0.595716, -0.274453, -0.321263)),
        dot(c, vec3(0.211456, -0.522591, 0.311135)));
}

vec3 yiqToRgb(vec3 y) {
    return vec3(
        y.x + 0.956296 * y.y + 0.621024 * y.z,
        y.x - 0.272122 * y.y - 0.647381 * y.z,
        y.x - 1.106989 * y.y + 1.704615 * y.z);
}

// Chroma phase parity from transmission order: 227.5 subcarrier cycles per
// line flip the phase 180 deg every transmitted line, and field B's lines
// (odd raster rows) are transmitted 263 lines after field A starts. The
// leftover half-cycle per frame flips everything again on odd frames.
float linePhase(int line, int framePar) {
    int trans = ((line & 1) == 0) ? (line >> 1) : 263 + (line >> 1);
    return PI * float((trans + framePar) & 1);
}

// absolute subcarrier phase at (possibly fractional) sample position u
float scPhase(float u, int line, int framePar) {
    return HALF_PI * u + linePhase(line, framePar);
}

// transmission-order predecessor of a raster line (same field, or the last
// line of the other field across the frame boundary)
int prevTransLine(int line) { return (line >= 2) ? line - 2 : 523 + line; }

// band-limited pulse edge (~130 ns rise, like real sync/blanking edges)
float edgeF(float u, float x0) { return smoothstep(x0 - 0.9, x0 + 0.9, u); }

uint hashU(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

float hash1(uvec3 p) {
    return float(hashU(p.x ^ hashU(p.y ^ hashU(p.z)))) * (1.0 / 4294967295.0);
}

float gaussRand(uvec3 seed) {
    float u1 = max(hash1(seed), 6.0e-8);
    float u2 = hash1(seed ^ uvec3(0x9E3779B9u, 0x85EBCA6Bu, 0xC2B2AE35u));
    return sqrt(-2.0 * log(u1)) * cos(TAU * u2);
}

float sincf(float x) {
    x = abs(x);
    return (x < 1.0e-5) ? 1.0 : sin(PI * x) / (PI * x);
}

// Hann-windowed sinc lowpass tap; fcn = cutoff frequency / sample rate.
// Callers normalize by the accumulated tap sum for exact unity DC gain.
float lpfTap(int k, float fcn, int halfWidth) {
    float w = 0.5 + 0.5 * cos(PI * float(k) / float(halfWidth + 1));
    return 2.0 * fcn * sincf(2.0 * fcn * float(k)) * w;
}

// Control word the server encodes into the world time, decoded from the
// GameTime uniform (rounded to the nearest tick):
//   tick >= 12000          -> camera inverted (then tick -= 12000)
//   tick in [8000, 12000)  -> special: static / ModelManager base signals
//   else tick = battery*400 + link*20 + speed*2 (+1 spare bit);
//   battery/link are 0..19, speed is 0..9
struct FpvState {
    bool inverted;   // camera mounted upside down
    bool special;    // no video signal: receiver shows static
    float battery;   // 0..1 pack charge
    float link;      // 0..1 RF link quality
    float speed;     // 0..1 throttle / airspeed
};

FpvState decodeFpv(float gameTime) {
    // GameTime carries the partial tick in its fraction, so round DOWN with
    // a small epsilon: floor(x + 0.5) would flip to the next tick's word
    // mid-tick and flicker the low bits (speed) at 20 Hz
    float t = floor(gameTime * 24000.0 + 0.008);
    FpvState st;
    st.inverted = step(12000.0, t) > 0.5;
    t = mod(t, 12000.0);
    st.special = t >= 8000.0;
    st.battery = clamp(floor(t / 400.0) / 19.0, 0.0, 1.0);
    st.link = clamp(floor(mod(t, 400.0) / 20.0) / 19.0, 0.0, 1.0);
    st.speed = clamp(floor(mod(t, 20.0) / 2.0) / 9.0, 0.0, 1.0);
    return st;
}

// ---- MAX7456-style OSD ----
// The character overlay grid is the MAX7456's NTSC raster: 30x13 cells of
// 12x18 overlay pixels (360x234 across the active picture). Glyphs are 5x7,
// doubled to 10x14 inside the cell with a one-pixel black outline.
//
// Glyph ids: 0-9 digits, 10 '.', 11 ':', 12 A, 13 C, 14 D, 15 E, 16 H,
// 17 I, 18 L, 19 N, 20 O, 21 R, 22 S, 23 V, 24 W, 25 K, 26 M, 27 P;
// -1 = empty. Rows 0-3 pack into .x (row r at bits 5r..5r+4, MSB = left
// column), rows 4-6 into .y.
const int OSD_COLS = 30;
const int OSD_ROWS = 13;
const uvec2 OSD_FONT[28] = uvec2[28](
    uvec2(0x000ace2eu, 0x00003a39u),
    uvec2(0x00021184u, 0x00003884u),
    uvec2(0x0001062eu, 0x00007d04u),
    uvec2(0x0001105fu, 0x00003a21u),
    uvec2(0x000928c2u, 0x0000085fu),
    uvec2(0x0000fa1fu, 0x00003a21u),
    uvec2(0x000f4106u, 0x00003a31u),
    uvec2(0x0002083fu, 0x00002108u),
    uvec2(0x0007462eu, 0x00003a31u),
    uvec2(0x0007c62eu, 0x00003041u),
    uvec2(0x00000000u, 0x00003180u),
    uvec2(0x00003180u, 0x0000018cu),
    uvec2(0x000fc62eu, 0x00004631u),
    uvec2(0x0008422eu, 0x00003a30u),
    uvec2(0x0008c63eu, 0x00007a31u),
    uvec2(0x000f421fu, 0x00007e10u),
    uvec2(0x000fc631u, 0x00004631u),
    uvec2(0x0002108eu, 0x00003884u),
    uvec2(0x00084210u, 0x00007e10u),
    uvec2(0x0009d731u, 0x00004631u),
    uvec2(0x0008c62eu, 0x00003a31u),
    uvec2(0x000f463eu, 0x00004654u),
    uvec2(0x0007420fu, 0x00007821u),
    uvec2(0x0008c631u, 0x00001151u),
    uvec2(0x000ac631u, 0x00002ab5u),
    uvec2(0x000c5251u, 0x00004654u),
    uvec2(0x000ad771u, 0x00004631u),
    uvec2(0x000f463eu, 0x00004210u));

int glyphBit(int g, int gx, int gy) {
    if (gx < 0 || gx > 4 || gy < 0 || gy > 6) return 0;
    uint rowBits = (gy < 4) ? (OSD_FONT[g].x >> uint(5 * gy))
                            : (OSD_FONT[g].y >> uint(5 * (gy - 4)));
    return int((rowBits >> uint(4 - gx)) & 1u);
}

// 2 = white core, 1 = black outline, 0 = transparent for one 12x18 cell pixel
int glyphPixel(int g, int px, int py) {
    if (g < 0) return 0;
    int gx = (px >= 1) ? (px - 1) / 2 : -1;
    int gy = (py >= 2) ? (py - 2) / 2 : -1;
    if (glyphBit(g, gx, gy) == 1) return 2;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            if (glyphBit(g, gx + dx, gy + dy) == 1) return 1;
        }
    }
    return 0;
}

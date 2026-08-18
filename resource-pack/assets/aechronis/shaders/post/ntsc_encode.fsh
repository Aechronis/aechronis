#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <aechronis:ntsc.glsl>

uniform sampler2D InSampler;
uniform sampler2D TelemSampler;

in vec2 texCoord;

out vec4 fragColor;

// FPV camera -> flight-controller OSD -> 5.8 GHz FM video link.
//
// All flight state arrives as a control word the server encodes into the
// world time (see decodeFpv in the include): battery charge, RF link
// quality, airspeed, the camera-inverted flag and a "special" mode where
// the receiver sees no video at all, just static.
//
// Camera: wide-angle lens (mild center magnification + vignette), subtle
// throttle-driven CMOS rolling-shutter vibration, camera sharpening, then
// NTSC-M composite generation with crystal-locked sync.
// The inverted flag flips the camera mount: the picture inverts but the
// OSD (injected downstream by the FC) stays upright, exactly like a real
// upside-down camera install.
//
// OSD: MAX7456-style character overlay keyed into the luma between camera
// and VTX - RSSI from link quality, battery voltage with current sag,
// airspeed, craft name, and a blinking LAND NOW once the pack runs down.
// It rides the composite signal, so it degrades with the link like
// everything else.
//
// Link: falling link quality raises the analog noise floor continuously. Near
// the receiver threshold the waveform is progressively captured by noise, so
// the existing decoder naturally loses color, then sync, then the picture.

const float DIFF_PHASE = 0.19;  // ~11 deg chroma tint at white: a fixed
const float DIFF_GAIN = 0.14;   // nonlinearity of the camera/VTX chain (FM
                                // capture keeps it independent of RSSI)
const float CAM_PEAK = 0.45;    // FPV-camera aperture correction
const float LENS_K = 0.12;      // wide-angle remap strength
const float VIGNETTE = 0.20;

// per-frame state shared into cleanSignal
float gSpeed;
float gJelloPhase;
ivec4 gOsd;      // x = RSSI 0-99, y = volts*10, z = airspeed mph
bool gLand;      // LAND NOW warning is visible

// mild wide-angle remap, corners anchored so the full frame stays visible
vec2 lensMap(vec2 p, float aspect) {
    vec2 c = (p - 0.5) * vec2(aspect, 1.0);
    float r2 = dot(c, c);
    float rMax2 = 0.25 * (aspect * aspect + 1.0);
    float k = (1.0 + LENS_K * r2) / (1.0 + LENS_K * rMax2);
    return 0.5 + (p - 0.5) * k;
}

// Betaflight-flavored OSD layout on the 30x13 character grid
int glyphAt(int cx, int cy) {
    if (cy == 1 && cx >= 2 && cx <= 8) {
        if (cx == 2) return 21;                  // R
        if (cx == 3) return 22;                  // S
        if (cx == 4) return 22;                  // S
        if (cx == 5) return 17;                  // I
        if (cx == 7) return (gOsd.x / 10) % 10;
        if (cx == 8) return gOsd.x % 10;
    }
    if (cy == 11) {
        if (cx >= 2 && cx <= 6) {                // battery "16.8V"
            if (cx == 2) return (gOsd.y / 100) % 10;
            if (cx == 3) return (gOsd.y / 10) % 10;
            if (cx == 4) return 10;              // .
            if (cx == 5) return gOsd.y % 10;
            return 23;                           // V
        }
        if (cx >= 21 && cx <= 27) {              // airspeed "45 MPH"
            if (cx == 21) return (gOsd.z >= 100) ? (gOsd.z / 100) % 10 : -1;
            if (cx == 22) return (gOsd.z >= 10) ? (gOsd.z / 10) % 10 : -1;
            if (cx == 23) return gOsd.z % 10;
            if (cx == 25) return 26;             // M
            if (cx == 26) return 27;             // P
            if (cx == 27) return 16;             // H
            return -1;
        }
    }
    if (cy == 12 && cx >= 10 && cx <= 18) {      // craft name
        int t = cx - 10;
        if (t == 0) return 12;                   // A
        if (t == 1) return 15;                   // E
        if (t == 2) return 13;                   // C
        if (t == 3) return 16;                   // H
        if (t == 4) return 21;                   // R
        if (t == 5) return 20;                   // O
        if (t == 6) return 19;                   // N
        if (t == 7) return 17;                   // I
        return 22;                               // S
    }
    if (gLand && cy == 6 && cx >= 11 && cx <= 18) {
        int t = cx - 11;
        if (t == 0) return 18;                   // L
        if (t == 1) return 12;                   // A
        if (t == 2) return 19;                   // N
        if (t == 3) return 14;                   // D
        if (t == 5) return 19;                   // N
        if (t == 6) return 20;                   // O
        if (t == 7) return 24;                   // W
    }
    return -1;
}

// Ideal transmitted signal (IRE) at sample position u of a line.
float cleanSignal(float u, int line, int framePar, bool flipped) {
    // vertical sync interval: equalizing / broad / equalizing pulse trains
    // at twice line rate (half-line period = 455 samples)
    if (line < 9) {
        float h = mod(u, 455.0);
        float w = (line >= 3 && line < 6) ? 388.0 : 33.0;
        return IRE_SYNC * (edgeF(h, 2.0) - edgeF(h, 2.0 + w));
    }

    float s = IRE_SYNC * (edgeF(u, 2.0) - edgeF(u, 69.0));

    float burstEnv = edgeF(u, 78.0) - edgeF(u, 114.0);
    s -= burstEnv * BURST_IRE * sin(scPhase(u, line, framePar));

    float activeEnv = edgeF(u, float(ACTIVE_START)) - edgeF(u, float(ACTIVE_START + ACTIVE_WIDTH));
    if (activeEnv > 0.001 && line >= VBI_LINES) {
        float aspect = ScreenSize.x / ScreenSize.y;
        float vSrc = 1.0 - (float(line - VBI_LINES) + 0.5) / float(VISIBLE_LINES);

        // rolling-shutter jello: a small coherent ~160 Hz prop vibration
        // wobbles the sensor readout while sync stays put. There is no motion
        // at zero throttle and no per-frame random phase kick.
        float jello = 1.25 * gSpeed * gSpeed
                    * sin(TAU * (0.0114 * float(line) + gJelloPhase));

        // 13-tap source read: light anti-alias kernel for luma, ~1.3 MHz
        // windowed-sinc pre-filter for I/Q as the encoder spec requires
        float y = 0.0;
        vec2 iq = vec2(0.0);
        float wSum = 0.0;
        float ym2 = 0.0;
        float yc = 0.0;
        float yp2 = 0.0;
        for (int k = -6; k <= 6; ++k) {
            vec2 pPic = vec2((u - float(ACTIVE_START) + jello + float(k) + 0.5) / float(ACTIVE_WIDTH), vSrc);
            vec2 sc = lensMap(pPic, aspect);
            sc.x = clamp(sc.x, 0.001, 0.999);
            sc.y = clamp(sc.y, 0.001, 0.999);
            if (flipped) sc = vec2(1.0) - sc;
            vec3 yiq = rgbToYiq(textureLod(InSampler, sc, 0.0).rgb);
            if (abs(k) <= 1) y += ((k == 0) ? 0.5 : 0.25) * yiq.x;
            if (k == -2) ym2 = yiq.x;
            if (k == 0) yc = yiq.x;
            if (k == 2) yp2 = yiq.x;
            float w = lpfTap(k, 0.0908, 6);
            iq += w * yiq.yz;
            wSum += w;
        }
        iq /= wSum;

        // camera aperture correction: the crispy edge halos FPV cams add
        y += CAM_PEAK * (yc - 0.5 * (ym2 + yp2));

        // lens vignette
        vec2 cv = (vec2((u - float(ACTIVE_START) + 0.5) / float(ACTIVE_WIDTH), vSrc) - 0.5) * vec2(aspect, 1.0);
        float vig = 1.0 - VIGNETTE * dot(cv, cv) / (0.25 * (aspect * aspect + 1.0));
        y *= vig;
        iq *= vig;

        // FC OSD keyed into the signal after the camera: white/black
        // character pixels replace the video (chroma blanked), unflipped
        float ox = (u - float(ACTIVE_START)) * (float(12 * OSD_COLS) / float(ACTIVE_WIDTH));
        float oy = (float(line - VBI_LINES) + 0.5) * (float(18 * OSD_ROWS) / float(VISIBLE_LINES));
        int gp = glyphPixel(glyphAt(int(ox) / 12, int(oy) / 18), int(ox) % 12, int(oy) % 18);
        if (gp == 2) {
            y = 0.78;
            iq = vec2(0.0);
        } else if (gp == 1) {
            y = 0.0;
            iq = vec2(0.0);
        }
        y = clamp(y, 0.0, 1.2);

        // differential phase/gain: chroma rotates and compresses as the
        // instantaneous luma level rises, a classic analog nonlinearity
        float phi = scPhase(u, line, framePar) + CHROMA_AXIS + DIFF_PHASE * y;
        float cGain = 1.0 - DIFF_GAIN * y;
        s += activeEnv * (IRE_BLACK + IRE_VIDEO * y
                          + IRE_VIDEO * cGain * (iq.y * sin(phi) + iq.x * cos(phi)));
    }
    return s;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    int n = px.x;
    int line = px.y;

    // animation clock: the raw tick value plus its sub-tick fraction; the
    // server rewrites the tick every update, so phases are field-hashed
    // rather than accumulated
    float anim = GameTime * 24000.0;
    uint field = uint(floor(anim * 3.0));        // ~60 Hz refresh
    int framePar = int(floor(anim * 1.5)) & 1;   // ~30 Hz frame parity

    // special/inverted come raw from the control word; battery/link/speed
    // come from the persistent telemetry smoother so numbers and noise
    // levels glide between the quantized 1/19 steps instead of flicking
    FpvState st = decodeFpv(GameTime);
    vec4 telem = texelFetch(TelemSampler, ivec2(0, 0), 0);
    float battery = telem.r;
    float speed = telem.b + telem.a / 255.0;

    // Link strength is already smoothed in the telemetry pass. Keep every
    // impairment tied to that measured value instead of inventing random
    // fades or dropouts while the drone and controller are stationary.
    float link = clamp(telem.g, 0.0, 1.0);
    float loss = 1.0 - link;

    gSpeed = speed;
    // Eight whole vibration cycles per game tick remain continuous when the
    // integer telemetry control word changes; only the sub-tick phase moves.
    gJelloPhase = 8.0 * fract(anim);

    // OSD telemetry; speed 1.0 = 1.0 blocks/tick = 20 m/s = 44.74 mph
    float volt = mix(13.2, 16.8, battery) - 0.3 * speed;
    gOsd = ivec4(int(round(99.0 * link)),
                 int(round(volt * 10.0)),
                 int(round(44.73873 * speed)),
                 0);
    // GameTime carries telemetry rather than a monotonic clock, so keep the
    // warning steady instead of letting telemetry changes randomize its blink.
    gLand = battery < 0.12;

    float s = cleanSignal(float(n), line, framePar, st.inverted);

    // FM threshold: a weakening carrier first raises the continuous noise
    // floor, then progressively captures the complete waveform. Corrupting
    // sync and burst here lets the real decoder stages produce color loss,
    // tearing, and eventual roll without unrelated random effect switches.
    float capture = smoothstep(0.58, 1.0, loss);
    float breakdown = st.special ? 1.0 : capture * capture;
    s = mix(s, 30.0 + 22.0 * gaussRand(uvec3(uint(n), uint(line) ^ (field << 10), 0xB10Bu)), breakdown);

    // Above threshold the picture remains recognizable while fine snow rises
    // smoothly with distance. Randomness remains only in the physical noise.
    float noiseRise = smoothstep(0.12, 1.0, loss);
    float sigma = 0.28 + 3.2 * noiseRise * noiseRise;
    s += sigma * gaussRand(uvec3(uint(n), uint(line) ^ (field << 10), 0xA17Eu));

    // ~1 LSB dither so the 8-bit composite store doesn't band
    s += (hash1(uvec3(uint(n), uint(line), field ^ 0xD17Au)) - 0.5) * 0.78;

    fragColor = vec4(packIre(clamp(s, -50.0, 150.0)), 0.0, 0.0, 1.0);
}

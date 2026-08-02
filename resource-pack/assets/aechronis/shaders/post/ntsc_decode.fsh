#version 330

#moj_import <aechronis:ntsc.glsl>

uniform sampler2D CombSampler;
uniform sampler2D LineInfoSampler;

in vec2 texCoord;

out vec4 fragColor;

// TV back-end. Horizontal lock comes from a causal "flywheel" average of the
// per-line sync detections (an H-AFC loop that only follows drift slowly, so
// fast time-base error stays visible as waviness and bad detections tear
// lines). Chroma is demodulated against the burst-locked carrier at the
// ~0.6 MHz equiband bandwidth of a single-chip CVBS goggle decoder (equal
// I/Q bandwidth makes the 33-degree axis equivalent to plain U/V), with
// automatic color control and a color killer driven by the recovered burst
// amplitude.
// Output rows hold Y'IQ for every raster line at active resolution.

vec4 lineInfo(int line) {
    return texelFetch(LineInfoSampler, ivec2(0, clamp(line, 0, LINES_PER_FRAME - 1)), 0);
}

float combLuma(int x, int line) {
    return unpackIre(texelFetch(CombSampler, ivec2(clamp(x, 0, SAMPLES_PER_LINE - 1), line), 0).r);
}

float combChroma(int x, int line) {
    return texelFetch(CombSampler, ivec2(clamp(x, 0, SAMPLES_PER_LINE - 1), line), 0).g * 120.0 - 60.0;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    int i = px.x;
    int line = px.y;

    // flywheel sync: weighted average over the previous same-field lines,
    // skipping lines where the separator found nothing
    const float FW[5] = float[5](0.32, 0.26, 0.19, 0.14, 0.09);
    float offAcc = 0.0;
    float offW = 0.0;
    for (int k = 0; k < 5; ++k) {
        vec4 li = lineInfo(line - 2 * k);
        if (li.r < 0.984) {
            offAcc += FW[k] * (li.r * 64.0 - 32.0);
            offW += FW[k];
        }
    }
    float off = (offW > 1.0e-4) ? offAcc / offW : 0.0;

    // chroma PLL: symmetric amplitude-weighted vector average of the burst
    // phase across neighbouring same-field lines
    const float PW[4] = float[4](0.28, 0.20, 0.12, 0.04);
    vec2 ph = vec2(0.0);
    float ampAcc = 0.0;
    for (int k = -3; k <= 3; ++k) {
        vec4 li = lineInfo(line + 2 * k);
        float w = PW[abs(k)];
        float amp = li.a * 40.0;
        ph += w * amp * vec2(li.b * 2.0 - 1.0, li.g * 2.0 - 1.0);
        ampAcc += w * amp;
    }
    float burstAmp = ampAcc;  // PW weights sum to 1.0
    float theta = (length(ph) > 1.0e-4) ? atan(ph.x, ph.y) : 0.0;

    float xSig = float(ACTIVE_START) + float(i) + 0.5 + off;

    // luma: ~4.2 MHz windowed sinc over the comb-filtered signal (the small
    // negative lobes are the edge ringing a real set shows)
    float yAcc = 0.0;
    float yW = 0.0;
    for (int k = -4; k <= 4; ++k) {
        float xt = xSig + float(k);
        int x0 = int(floor(xt - 0.5));
        float f = (xt - 0.5) - float(x0);
        float v = mix(combLuma(x0, line), combLuma(x0 + 1, line), f);
        float w = lpfTap(k, 0.2934, 4);
        yAcc += w * v;
        yW += w;
    }
    float yIre = yAcc / yW;

    // chroma: synchronous demodulation of the comb difference signal against
    // the burst-locked carrier, lowpassed to receiver I/Q bandwidths
    int xc = int(round(xSig));
    float iAcc = 0.0;
    float qAcc = 0.0;
    float iW = 0.0;
    float qW = 0.0;
    for (int k = -12; k <= 12; ++k) {
        int xs = xc + k;
        float c = combChroma(xs, line);
        float phc = HALF_PI * float(xs) + linePhase(line, 0) + theta + CHROMA_AXIS;
        float wi = lpfTap(k, 0.0419, 12);
        float wq = lpfTap(k, 0.0419, 12);
        iAcc += wi * c * cos(phc);
        qAcc += wq * c * sin(phc);
        iW += wi;
        qW += wq;
    }
    float iVal = 2.0 * (iAcc / iW) / IRE_VIDEO;
    float qVal = 2.0 * (qAcc / qW) / IRE_VIDEO;

    // ACC normalizes saturation by burst amplitude; the killer drops chroma
    // entirely when burst sinks toward the noise floor (snow goes B&W)
    float sat = clamp(BURST_IRE / max(burstAmp, 4.0), 0.3, 2.0)
              * smoothstep(5.0, 8.5, burstAmp);
    iVal *= sat;
    qVal *= sat;

    float yN = (yIre - IRE_BLACK) / IRE_VIDEO;
    fragColor = vec4(clamp((yN + 0.3) / 1.6, 0.0, 1.0),
                     clamp(iVal / 1.4 + 0.5, 0.0, 1.0),
                     clamp(qVal / 1.4 + 0.5, 0.0, 1.0),
                     1.0);
}

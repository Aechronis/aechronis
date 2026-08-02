#version 330

#moj_import <aechronis:ntsc.glsl>

uniform sampler2D CompositeSampler;

in vec2 texCoord;

out vec4 fragColor;

// Goggle-decoder front-end, one fragment per scanline: sync separator and
// burst detector. Scans the (noisy) composite for the hsync falling edge
// near 0H, then correlates the colorburst window against the nominal
// subcarrier to recover the phase reference and burst amplitude the decoder
// locks to.
// Output texel: R = sync offset (1.0 = no sync found), G/B = cos/sin of the
// burst phase error, A = burst half-amplitude / 40 IRE.
// Vsync rows 0-8 carry no burst; there G holds the broad-pulse confidence
// instead, which the display pass uses for its vertical lock.

float fetchIre(int x, int line) {
    int l = line;
    if (x < 0) {
        x += SAMPLES_PER_LINE;
        l = prevTransLine(line);
    }
    return unpackIre(texelFetch(CompositeSampler, ivec2(x, clamp(l, 0, LINES_PER_FRAME - 1)), 0).r);
}

float smoothed(int x, int line) {
    return (fetchIre(x - 1, line) + fetchIre(x, line) + fetchIre(x + 1, line)) / 3.0;
}

void main() {
    int line = int(gl_FragCoord.y);

    // hunt for the falling edge through -20 IRE (sync midpoint), with
    // sub-sample interpolation; noise corrupting this is what tears lines
    float offset = 0.0;
    bool found = false;
    float prev = smoothed(-31, line);
    for (int x = -30; x <= 44; ++x) {
        float cur = smoothed(x, line);
        if (prev >= -20.0 && cur < -20.0) {
            float frac = (prev + 20.0) / max(prev - cur, 1.0e-4);
            offset = float(x - 1) + frac - 2.0;  // nominal edge sits at u=2
            found = true;
            break;
        }
        prev = cur;
    }

    float offEnc = found ? (clamp(offset, -30.0, 30.0) + 32.0) / 64.0 : 1.0;

    // vsync rows: score how much of the broad-pulse interval actually sits
    // below the sync slice level - the display's vertical hold reads this
    if (line < 9) {
        int below = 0;
        for (int x = 2; x < 390; x += 4) {
            if (fetchIre(x, line) < -20.0) below++;
        }
        fragColor = vec4(offEnc, float(below) / 97.0, 0.5, 0.0);
        return;
    }

    // correlate the burst window (shifted by the detected offset) against
    // quadrature carriers; burst = -A*sin(phase + theta) so the accumulated
    // vector is -(N/2)*A*(sin theta, cos theta)
    int bo = int(round(clamp(offset, -30.0, 30.0)));
    vec2 acc = vec2(0.0);
    for (int x = 0; x < 36; ++x) {
        int xs = 78 + bo + x;
        float ph = HALF_PI * float(xs) + linePhase(line, 0);
        acc += fetchIre(xs, line) * vec2(cos(ph), sin(ph));
    }
    float ampHalf = length(acc) / 18.0;
    vec2 dir = (length(acc) > 1.0e-4) ? -acc / length(acc) : vec2(0.0, 1.0);

    fragColor = vec4(offEnc,
                     0.5 * dir.y + 0.5,
                     0.5 * dir.x + 0.5,
                     clamp(ampHalf / 40.0, 0.0, 1.0));
}

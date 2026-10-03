// Bakes the diffuse-field equalizer for the baked KEMAR HRIR table.
//
// A measured HRIR carries more than direction. The MIT KEMAR set was recorded
// with a small loudspeaker into a dummy head's ear canal, so every response
// also holds that speaker's bass roll-off and the ear canal's own resonance —
// about -15 dB at 100 Hz, +10 dB at 2.5 kHz and -12 dB at 8 kHz, the same for
// every direction. Convolved raw, that is what a binaural render sounds like:
// thin and honky, "tinny". What says *where* a sound is lies in how the
// responses differ from one direction to another, not in what they share.
//
// Diffuse-field equalization removes what they share: average the power
// response over every direction (weighted by the solid angle each grid point
// stands for), and divide it out. This computes that average from the table
// itself, smooths it to third-octaves (so the correction follows the overall
// balance, not individual notches), limits the correction, and emits it as a
// minimum-phase FIR — no pre-ringing, and the least delay a filter with that
// magnitude can have.
//
// BUILD-TIME ONLY. From this directory:
//   c++ -std=c++17 -O2 -I.. bake_dfe.cpp -o bake_dfe && ./bake_dfe ../hrir_dfe.h
#include <cmath>
#include <complex>
#include <cstdio>
#include <vector>

#include "hrir_table.h"

using namespace tf::atmos::render;
using cd = std::complex<double>;

namespace {

constexpr double kRate = 48000.0;
constexpr int kN = 4096;          // analysis/cepstrum size
constexpr int kTaps = 512;        // emitted FIR length (10.7 ms at 48 kHz)
constexpr double kMaxBoostDb = 15.0;
constexpr double kMaxCutDb = 15.0;

void fft(std::vector<cd>& a, bool inverse) {
    const int n = static_cast<int>(a.size());
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = 2 * M_PI / len * (inverse ? 1 : -1);
        const cd wl(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            cd w(1);
            for (int k = 0; k < len / 2; k++) {
                const cd u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
    if (inverse) for (auto& x : a) x /= n;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s out.h\n", argv[0]);
        return 2;
    }
    const int half = kN / 2;

    // 1. Diffuse-field power: every direction, both ears, weighted by cos(el).
    std::vector<double> power(half + 1, 0.0);
    double wsum = 0.0;
    for (int e = 0; e < kHrirElCount; e++) {
        const double el = (kHrirElMin + e * kHrirElStep) * M_PI / 180.0;
        const double w = std::cos(el);
        for (int a = 0; a < kHrirAzCount; a++) {
            for (int ear = 0; ear < 2; ear++) {
                std::vector<cd> x(kN, 0.0);
                for (int t = 0; t < kHrirTaps; t++) x[t] = kHrirData[e][a][ear][t];
                fft(x, false);
                for (int k = 0; k <= half; k++) power[k] += w * std::norm(x[k]);
                wsum += w;
            }
        }
    }
    for (auto& p : power) p /= wsum;

    // 2. Third-octave smoothing in power.
    std::vector<double> smooth(half + 1, 0.0);
    for (int k = 1; k <= half; k++) {
        const double f = k * kRate / kN;
        const double lo = f * std::pow(2.0, -1.0 / 6.0), hi = f * std::pow(2.0, 1.0 / 6.0);
        double s = 0.0;
        int n = 0;
        for (int j = 1; j <= half; j++) {
            const double fj = j * kRate / kN;
            if (fj >= lo && fj <= hi) { s += power[j]; n++; }
        }
        smooth[k] = n ? s / n : power[k];
    }
    smooth[0] = smooth[1];

    // 3. Inverse magnitude, in absolute terms: the corrected diffuse field
    //    sits at -3 dB in each ear, so both ears together carry what the
    //    equal-power pan gives a channel (0 dB) and switching between the
    //    two modes keeps the loudness. The shape is then limited relative to
    //    the midrange (500 Hz - 4 kHz), where the correction is trustworthy.
    std::vector<double> gainDb(half + 1);
    for (int k = 0; k <= half; k++) gainDb[k] = -10.0 * std::log10(std::max(smooth[k], 1e-12)) - 3.0103;
    double ref = 0.0;
    int nref = 0;
    for (int k = 0; k <= half; k++) {
        const double f = k * kRate / kN;
        if (f >= 500.0 && f <= 4000.0) { ref += gainDb[k]; nref++; }
    }
    ref /= nref;
    for (int k = 0; k <= half; k++) {
        // Above 10 kHz the boost limit falls to 6 dB by 16 kHz: much of the
        // set's top-octave loss is the measuring loudspeaker's roll-off, and
        // making all of it up would bring hiss and sibilance with it.
        const double f = k * kRate / kN;
        const double t = std::max(0.0, std::min(1.0, (f - 10000.0) / 6000.0));
        const double maxBoost = kMaxBoostDb + (6.0 - kMaxBoostDb) * t;
        gainDb[k] = ref + std::max(-kMaxCutDb, std::min(maxBoost, gainDb[k] - ref));
    }

    // 4. Minimum phase from the magnitude, by the real cepstrum.
    std::vector<cd> c(kN);
    for (int k = 0; k < kN; k++) {
        const int kk = k <= half ? k : kN - k;
        c[k] = gainDb[kk] / 20.0 * std::log(10.0);  // log magnitude
    }
    fft(c, true);  // real cepstrum
    for (int n = 1; n < half; n++) { c[n] *= 2.0; c[kN - n] = 0.0; }
    c[half] = c[half];  // Nyquist kept
    fft(c, false);
    for (auto& x : c) x = std::exp(x);
    fft(c, true);  // minimum-phase impulse response

    // 5. Truncate with a half-Hann fade over the last eighth.
    std::vector<double> h(kTaps);
    const int fade = kTaps / 8;
    for (int t = 0; t < kTaps; t++) {
        double w = 1.0;
        if (t >= kTaps - fade) w = 0.5 + 0.5 * std::cos(M_PI * (t - (kTaps - fade)) / fade);
        h[t] = c[t].real() * w;
    }

    FILE* out = std::fopen(argv[1], "w");
    if (!out) return 1;
    std::fprintf(out,
        "// GENERATED by tools/bake_dfe.cpp — do not edit.\n"
        "// Diffuse-field equalizer for hrir_table.h: the inverse of the KEMAR set's\n"
        "// direction-averaged response (third-octave smoothed, limited to +%.0f/-%.0f dB,\n"
        "// the boost easing to +6 dB from 10 to 16 kHz,\n"
        "// the corrected diffuse field at -3 dB per ear, so both ears match the\n"
        "// equal-power pan), minimum phase, at 48 kHz.\n"
        "#ifndef TF_ATMOS_RENDER_HRIR_DFE_H\n#define TF_ATMOS_RENDER_HRIR_DFE_H\n\n"
        "namespace tf { namespace atmos { namespace render {\n\n"
        "constexpr int kDfeTaps = %d;\n\nconstexpr float kDfe[kDfeTaps] = {\n",
        kMaxBoostDb, kMaxCutDb, kTaps);
    for (int t = 0; t < kTaps; t++) std::fprintf(out, "%.9ef,%s", h[t], (t % 6 == 5) ? "\n" : "");
    std::fprintf(out, "\n};\n\n}}}  // namespace tf::atmos::render\n\n#endif\n");
    std::fclose(out);

    // Report the correction at a few frequencies.
    const double fs[] = {100, 300, 1000, 2500, 4000, 8000, 12000, 16000};
    for (double f : fs) {
        const int k = static_cast<int>(f * kN / kRate + 0.5);
        std::printf("%6.0f Hz: diffuse %+6.1f dB -> correction %+6.1f dB\n", f,
                    10 * std::log10(smooth[k]), gainDb[k]);
    }
    return 0;
}

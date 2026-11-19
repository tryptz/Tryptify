// Host test: a low band lands on the frequency it was asked for, at every
// rate the engine runs at, oversampled rates included.
//
// biquad.h once held the frequency to at least a thousandth of the sample
// rate, so a 31 Hz band sat at 44 Hz on a 44.1 kHz stream and at 192 Hz at
// 192 kHz, and kept its coefficients and state in floats, which cannot hold a
// 20 Hz filter's poles at 192 kHz (a1 is within a float's step of -2).
//
// Built with -ffast-math, as the phone builds the header, so nothing here
// leans on NaN and finiteness is asked of dspIsFinite (util/finite.h).
#include <cmath>
#include <complex>
#include <cstdio>
#include "biquad.h"
#include "finite.h"

static int failures = 0;
static void check(bool ok, const char* what, double sr, double got) {
    std::printf("%s  %s @ %.0f Hz (%.2f dB)\n", ok ? "ok  " : "FAIL", what, sr, got);
    if (!ok) failures++;
}

// Gain in dB of the filter at frequency f, read off its impulse response.
static double gainDb(Biquad& b, double f, double sr) {
    const int n = 1 << 21;
    const double w = 2.0 * M_PI * f / sr;
    std::complex<double> sum = 0.0;
    b.reset();
    for (int i = 0; i < n; i++) {
        sum += static_cast<double>(b.process(i == 0 ? 1.0f : 0.0f)) * std::polar(1.0, -w * i);
    }
    return 20.0 * std::log10(std::abs(sum));
}

int main() {
    for (double sr : {8000.0, 44100.0, 48000.0, 96000.0, 192000.0, 384000.0}) {
        Biquad bell;
        bell.configure(BiquadType::Peaking, sr, 20.0, 1.4142135, 6.0);
        const double peak = gainDb(bell, 20.0, sr);
        check(std::fabs(peak - 6.0) < 0.1, "a 20 Hz +6 dB bell peaks at 20 Hz", sr, peak);

        Biquad hp;
        hp.configure(BiquadType::HighPass, sr, 20.0, 0.70710678);
        const double corner = gainDb(hp, 20.0, sr);
        check(std::fabs(corner + 3.01) < 0.1, "a 20 Hz high-pass is 3 dB down at 20 Hz", sr, corner);
    }
    // What the floor was there for still holds: a band asked for at or under
    // 0 Hz is held to a real frequency and its output stays finite.
    Biquad b;
    b.configure(BiquadType::Peaking, 48000.0, 0.0, 1.0, 6.0);
    check(dspIsFinite(b.process(1.0f)), "a 0 Hz band stays finite", 48000.0, 0.0);
    b.configure(BiquadType::Peaking, 48000.0, -50.0, 1.0, 6.0);
    check(dspIsFinite(b.process(1.0f)), "a negative frequency stays finite", 48000.0, 0.0);
    // And the top is where it was: a 20 kHz band on a 22.05 kHz stream.
    Biquad top;
    top.configure(BiquadType::Peaking, 22050.0, 20000.0, 1.0, 12.0);
    float last = 0.0f;
    for (int i = 0; i < 22050; i++) last = top.process(i == 0 ? 1.0f : 0.0f);
    check(dspIsFinite(last) && std::fabs(last) < 1.0f, "a band past Nyquist does not run away", 22050.0, 0.0);
    std::puts(failures == 0 ? "all passed" : "FAILED");
    return failures == 0 ? 0 : 1;
}

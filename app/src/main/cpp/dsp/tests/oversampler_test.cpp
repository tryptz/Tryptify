// Host test for the halfband oversampler (util/oversampler.h).
//
// What the engine relies on: latency() is the exact, whole-sample delay of a
// round trip; the round trip is linear phase, so a delayed dry signal nulls
// against it; audio inside the passband comes back unchanged; images of the
// zero-stuffing are gone before a nonlinearity can fold them back; and the
// factor is capped where oversampling no longer buys anything.

#include <cmath>
#include <cstdio>
#include <vector>

#include "oversampler.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

/** Magnitude of [hz] in [x] at [rate], by Goertzel, as a linear amplitude. */
double toneLevel(const std::vector<float>& x, size_t from, double hz, double rate) {
    const size_t n = x.size() - from;
    const double w = 2.0 * M_PI * hz / rate;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (size_t i = from; i < x.size(); i++) {
        // Hann window: the tone's own skirt must not read as leakage.
        const double win = 0.5 - 0.5 * std::cos(2.0 * M_PI * (i - from) / (n - 1));
        const double s = x[i] * win + coeff * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    const double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    return 2.0 * std::sqrt(std::max(0.0, power)) / (0.5 * n);
}

double db(double x) { return 20.0 * std::log10(std::max(x, 1e-30)); }

/** A round trip through [os] with nothing in between. */
std::vector<float> roundTrip(ChannelOversampler& os, const std::vector<float>& in) {
    std::vector<float> up(in.size() * static_cast<size_t>(os.factor()));
    std::vector<float> out(in.size());
    // In ragged blocks, as the engine hands them over.
    const int blocks[] = {1, 17, 256, 5, 1000};
    size_t done = 0;
    for (int b = 0; done < in.size(); b++) {
        const int n = static_cast<int>(std::min<size_t>(blocks[b % 5], in.size() - done));
        os.upsample(&in[done], up.data(), n);
        os.downsample(up.data(), &out[done], n);
        done += static_cast<size_t>(n);
    }
    return out;
}

void latencyAndPhase(double rate, int factor) {
    ChannelOversampler os;
    os.prepare(rate, factor);
    const int lat = os.latency();
    std::vector<float> impulse(2048, 0.0f);
    impulse[100] = 1.0f;
    const auto y = roundTrip(os, impulse);
    size_t peak = 0;
    for (size_t i = 0; i < y.size(); i++) if (std::fabs(y[i]) > std::fabs(y[peak])) peak = i;
    char what[160];
    std::snprintf(what, sizeof what, "%.1f kHz %dx: impulse returns %d samples late, as latency() says (%d)",
                  rate / 1000.0, factor, static_cast<int>(peak) - 100, lat);
    check(static_cast<int>(peak) == 100 + lat, what);
    // Linear phase: the response is symmetric about its peak.
    double asym = 0.0, energy = 0.0;
    for (int k = 1; k < lat && k < 90; k++) {
        asym = std::max(asym, static_cast<double>(std::fabs(y[peak - k] - y[peak + k])));
        energy += y[peak + k] * y[peak + k];
    }
    std::snprintf(what, sizeof what, "%.1f kHz %dx: symmetric response, linear phase (worst %.2g)",
                  rate / 1000.0, factor, asym);
    check(asym < 1e-5, what);
}

void passbandNull(double rate, int factor) {
    ChannelOversampler os;
    os.prepare(rate, factor);
    const int lat = os.latency();
    const double tones[] = {40.0, 1000.0, 10000.0, 18000.0};
    const size_t n = static_cast<size_t>(rate / 4);
    double worst = -300.0;
    double worstHz = 0.0;
    for (double hz : tones) {
        if (hz > 0.4 * rate) continue;
        std::vector<float> x(n);
        for (size_t i = 0; i < n; i++) x[i] = static_cast<float>(0.5 * std::sin(2.0 * M_PI * hz * i / rate));
        const auto y = roundTrip(os, x);
        // Against the input held back by the latency: what the engine's dry
        // path does.
        double err = 0.0, sig = 0.0;
        for (size_t i = 4096; i < n; i++) {
            const double d = y[i] - x[i - static_cast<size_t>(lat)];
            err += d * d;
            sig += static_cast<double>(x[i]) * x[i];
        }
        const double nullDb = 10.0 * std::log10(std::max(err, 1e-30) / sig);
        if (nullDb > worst) { worst = nullDb; worstHz = hz; }
    }
    char what[160];
    std::snprintf(what, sizeof what, "%.1f kHz %dx: nulls against the delayed dry to %.1f dB (worst at %.0f Hz)",
                  rate / 1000.0, factor, worst, worstHz);
    // 18 kHz at 44.1 kHz is inside the passband but close to its edge.
    check(worst < -60.0, what);
}

void imageRejection(double rate) {
    // A 19 kHz tone upsampled 2x: whatever of its image at rate − 19 kHz
    // survives is what a nonlinearity would fold back.
    ChannelOversampler os;
    os.prepare(rate, 2);
    const double hz = std::min(19000.0, 0.43 * rate);
    const size_t n = 16384;
    std::vector<float> x(n), up(n * 2);
    for (size_t i = 0; i < n; i++) x[i] = static_cast<float>(0.5 * std::sin(2.0 * M_PI * hz * i / rate));
    os.upsample(x.data(), up.data(), static_cast<int>(n));
    const double tone = toneLevel(up, 4096, hz, 2.0 * rate);
    const double image = toneLevel(up, 4096, rate - hz, 2.0 * rate);
    char what[160];
    std::snprintf(what, sizeof what, "%.1f kHz: the image of a %.0f Hz tone sits %.1f dB below it",
                  rate / 1000.0, hz, db(tone) - db(image));
    check(db(tone) - db(image) > 85.0, what);
}

void dcIsUnity() {
    ChannelOversampler os;
    os.prepare(48000.0, 4);
    std::vector<float> x(4000, 0.25f);
    const auto y = roundTrip(os, x);
    double worst = 0.0;
    for (size_t i = 1000; i < y.size(); i++) worst = std::max(worst, std::fabs(y[i] - 0.25));
    check(worst < 1e-5, "a constant comes back the same constant");
}

void capAtHighRates() {
    check(ChannelOversampler::effectiveFactor(44100.0, 4) == 4, "44.1 kHz keeps 4x (176.4 kHz inside)");
    check(ChannelOversampler::effectiveFactor(48000.0, 4) == 4, "48 kHz keeps 4x (192 kHz inside)");
    check(ChannelOversampler::effectiveFactor(96000.0, 4) == 2, "96 kHz asked for 4x runs 2x");
    check(ChannelOversampler::effectiveFactor(88200.0, 2) == 2, "88.2 kHz keeps 2x");
    check(ChannelOversampler::effectiveFactor(192000.0, 4) == 1, "192 kHz runs without oversampling");
    check(ChannelOversampler::effectiveFactor(384000.0, 2) == 1, "384 kHz runs without oversampling");
    check(ChannelOversampler::effectiveFactor(8000.0, 7) == 4, "a nonsense factor snaps to a real one");
}

}  // namespace

int main() {
    for (double rate : {44100.0, 48000.0, 96000.0}) {
        for (int f : {2, 4}) {
            if (ChannelOversampler::effectiveFactor(rate, f) != f) continue;
            latencyAndPhase(rate, f);
            passbandNull(rate, f);
        }
        imageRejection(rate);
    }
    // A low rate, where the filter is longest relative to the audio band.
    latencyAndPhase(22050.0, 4);
    dcIsUnity();
    capAtHighRates();
    std::printf("%s\n", failures ? "FAILED" : "all passed");
    return failures ? 1 : 0;
}

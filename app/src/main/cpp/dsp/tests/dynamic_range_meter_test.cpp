// DynamicRangeMeter against signals whose DR is known by hand, built with
// -ffast-math as on the phone so its "no reading" survives the flag.
//
// The meter's RMS is sqrt(2 · mean x²), so a sine of any level reads its own
// peak and has a DR of 0; DR = 20·log10(second-highest block peak / RMS of the
// loudest 20 % of 3 s blocks).

#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>

#include "meter/dynamic_range_meter.h"

using tryptify::DynamicRangeMeter;

static int failures = 0;

static void expectNear(const char* what, float got, float want, float tol) {
    if (std::fabs(got - want) > tol) {
        std::printf("FAIL %s: got %.3f, want %.3f ±%.2f\n", what, got, want, tol);
        ++failures;
    } else {
        std::printf("ok   %s: %.3f\n", what, got);
    }
}

static void expectTrue(const char* what, bool ok) {
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

/** One block's worth of a channel layout: what each lit channel plays. */
struct Signal {
    double amp = 0.0;      // sine peak, linear
    bool square = false;   // a square wave at [amp] instead
    int spikeAt = -1;      // a single sample at full scale, this many frames in
};

// Feeds [seconds] of [s] to the [lit] channels of a [channels]-wide stream, in
// buffer-sized pushes like the tap makes. Other channels are silent unless
// [other] is given.
static void feed(DynamicRangeMeter& m, int rate, int channels, double seconds, const Signal& s,
                 const std::vector<int>& lit, const Signal* other = nullptr, const std::vector<int>& otherCh = {}) {
    const int total = int(seconds * rate);
    const int chunk = 1024;
    std::vector<float> buf(size_t(chunk) * channels);
    const double w = 2.0 * M_PI * 997.0 / rate;
    for (int done = 0; done < total; done += chunk) {
        const int n = total - done < chunk ? total - done : chunk;
        for (int f = 0; f < n; ++f) {
            const int i = done + f;
            auto value = [&](const Signal& sig) {
                float v = sig.square ? float(std::sin(w * i) >= 0 ? sig.amp : -sig.amp) : float(sig.amp * std::sin(w * i));
                if (i == sig.spikeAt) v = 1.f;
                return v;
            };
            for (int c = 0; c < channels; ++c) buf[size_t(f) * channels + c] = 0.f;
            for (int c : lit) buf[size_t(f) * channels + c] = value(s);
            if (other) for (int c : otherCh) buf[size_t(f) * channels + c] = value(*other);
        }
        m.process(buf.data(), n);
    }
}

int main() {
    // A sine reads its own peak: DR 0 at any level and rate.
    for (int rate : {44100, 48000, 96000}) {
        DynamicRangeMeter m;
        m.configure(rate, 2);
        feed(m, rate, 2, 30.0, {1.0}, {0, 1});
        char what[64];
        std::snprintf(what, sizeof what, "full-scale sine is DR 0 at %d Hz", rate);
        expectNear(what, m.reading(), 0.f, 0.05f);
        std::snprintf(what, sizeof what, "ten whole blocks at %d Hz", rate);
        expectTrue(what, m.blocks() == 10);
    }
    {
        DynamicRangeMeter m;
        m.configure(48000, 2);
        feed(m, 48000, 2, 30.0, {0.1}, {0, 1});
        expectNear("a -20 dBFS sine is DR 0 too", m.reading(), 0.f, 0.05f);
    }

    // A square wave's RMS (with the meter's ×2) is √2 times its peak: DR -3.01.
    {
        DynamicRangeMeter m;
        m.configure(44100, 2);
        feed(m, 44100, 2, 15.0, {0.5, true}, {0, 1});
        expectNear("square wave is DR -3.01", m.reading(), -3.01f, 0.05f);
    }

    // Peaks: the second highest block peak counts, so one stray sample does not.
    {
        const int rate = 48000;
        DynamicRangeMeter m;
        m.configure(rate, 2);
        Signal a{0.1};
        a.spikeAt = rate;  // in the first block
        feed(m, rate, 2, 3.0, a, {0, 1});
        Signal b{0.1};
        b.spikeAt = rate;  // in the second block
        feed(m, rate, 2, 3.0, b, {0, 1});
        feed(m, rate, 2, 24.0, {0.1}, {0, 1});
        expectNear("two full-scale spikes over a -20 dB sine: DR 20", m.reading(), 20.f, 0.1f);

        DynamicRangeMeter one;
        one.configure(rate, 2);
        feed(one, rate, 2, 3.0, a, {0, 1});
        feed(one, rate, 2, 27.0, {0.1}, {0, 1});
        expectNear("one stray spike is not the peak: DR 0", one.reading(), 0.f, 0.1f);
    }

    // Loudness: the RMS of the loudest 20 % of blocks, not of all of them.
    {
        const int rate = 44100;
        DynamicRangeMeter m;
        m.configure(rate, 2);
        Signal loud{0.5};
        loud.spikeAt = rate;
        for (int k = 0; k < 2; ++k) feed(m, rate, 2, 3.0, loud, {0, 1});  // two with a spike
        for (int k = 0; k < 2; ++k) feed(m, rate, 2, 3.0, {0.5}, {0, 1});
        for (int k = 0; k < 16; ++k) feed(m, rate, 2, 3.0, {0.05}, {0, 1});
        // Top 4 of 20 blocks are the 0.5 ones: 20·log10(1 / 0.5) = 6.02.
        expectNear("loudest 20 % of blocks set the loudness", m.reading(), 6.02f, 0.1f);
    }

    // Nothing to read yet, and silence: no reading, never a NaN.
    {
        DynamicRangeMeter m;
        m.configure(48000, 2);
        expectTrue("a fresh meter has no reading", m.reading() == DynamicRangeMeter::kNone);
        feed(m, 48000, 2, 2.0, {1.0}, {0, 1});
        expectTrue("under one block: no reading", m.reading() == DynamicRangeMeter::kNone);
        expectTrue("but its frames are counted", m.frames() == 96000);
        DynamicRangeMeter quiet;
        quiet.configure(48000, 2);
        feed(quiet, 48000, 2, 12.0, {0.0}, {0, 1});
        expectTrue("silence: no reading", quiet.reading() == DynamicRangeMeter::kNone);
    }

    // The block still filling at the end is left out, its frames still counted.
    {
        DynamicRangeMeter m;
        m.configure(48000, 2);
        feed(m, 48000, 2, 9.5, {1.0}, {0, 1});
        expectTrue("9.5 s is three whole blocks", m.blocks() == 3);
        expectTrue("and 9.5 s of frames", m.frames() == 456000);
    }

    // A new track, or a new format, starts again.
    {
        DynamicRangeMeter m;
        m.configure(48000, 2);
        feed(m, 48000, 2, 6.0, {1.0}, {0, 1});
        m.reset();
        expectTrue("reset clears the reading", m.reading() == DynamicRangeMeter::kNone && m.frames() == 0 && m.blocks() == 0);
        feed(m, 48000, 2, 6.0, {1.0}, {0, 1});
        m.configure(44100, 2);
        expectTrue("a new rate clears it", m.reading() == DynamicRangeMeter::kNone && m.blocks() == 0);
        m.configure(44100, 2);
        feed(m, 44100, 2, 3.0, {1.0}, {0, 1});
        m.configure(44100, 2);
        expectTrue("the same format again keeps it", m.blocks() == 1);
    }

    // 5.1: the LFE is not measured, and silent channels say nothing.
    {
        const int rate = 48000;
        DynamicRangeMeter m;
        m.configure(rate, 6);
        Signal a{0.1};
        a.spikeAt = rate;
        Signal b{0.1};
        b.spikeAt = rate * 2;  // in the second block (each feed counts from its own start)
        Signal lfe{1.0};
        feed(m, rate, 6, 3.0, a, {0, 1}, &lfe, {3});
        feed(m, rate, 6, 3.0, b, {0, 1}, &lfe, {3});
        feed(m, rate, 6, 24.0, {0.1}, {0, 1}, &lfe, {3});
        expectNear("5.1: front pair read, LFE and silent channels left out", m.reading(), 20.f, 0.1f);
    }

    // Garbage in: a NaN or an infinity is silence. One block and every
    // measured channel hit, so no clean block or channel can stand in for a
    // missing guard; the reading must equal the same signal with those
    // samples at zero. The values are built from their bits, so no compiler
    // can fold them away under -ffast-math.
    {
        auto bits = [](uint32_t b) { float f; std::memcpy(&f, &b, sizeof f); return f; };
        const float nan = bits(0x7FC00000u);
        const float inf = bits(0x7F800000u);
        for (int channels : {1, 2}) {
            const int rate = 48000;
            std::vector<float> clean(size_t(rate) * 3 * channels);
            for (size_t i = 0; i < clean.size(); ++i) clean[i] = float(0.5 * std::sin(i * 0.13));
            std::vector<float> dirty = clean;
            for (int c = 0; c < channels; ++c) {
                clean[size_t(100) * channels + c] = 0.f;
                clean[size_t(9000) * channels + c] = 0.f;
                dirty[size_t(100) * channels + c] = nan;
                dirty[size_t(9000) * channels + c] = inf;
            }
            DynamicRangeMeter a, b;
            a.configure(rate, channels);
            b.configure(rate, channels);
            a.process(clean.data(), rate * 3);
            b.process(dirty.data(), rate * 3);
            char what[96];
            std::snprintf(what, sizeof what, "%d channel(s): NaN and inf read as silence", channels);
            expectTrue(what, a.reading() != DynamicRangeMeter::kNone);
            expectNear(what, b.reading(), a.reading(), 0.01f);
        }
    }

    if (failures) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all checks passed\n");
    return 0;
}

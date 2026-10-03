// LoudnessMeter against the reference signals of EBU Tech 3341 (loudness) and
// Tech 3342 (loudness range), plus the true-peak and channel-weight rules.
// Every expectation is the published one, within the tolerance the spec
// allows a meter: ±0.1 LU for loudness, ±1 LU for range.

#include <cmath>
#include <cstdio>
#include <vector>

#include "meter/loudness_meter.h"

using tryptify::LoudnessMeter;

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

// Feeds [seconds] of a sine at [dbfs] peak to the given channels of a
// [channels]-wide stream, in buffer-sized pushes like the tap makes.
static void feedSine(LoudnessMeter& m, int rate, int channels, double seconds, double dbfs,
                     double hz, const std::vector<int>& lit, double phase0 = 0.0) {
    const double amp = std::pow(10.0, dbfs / 20.0);
    const int total = int(seconds * rate);
    const int chunk = 1024;
    std::vector<float> buf(size_t(chunk) * channels);
    static double phase = 0.0;
    if (phase0 != 0.0) phase = phase0;
    for (int done = 0; done < total; done += chunk) {
        const int n = total - done < chunk ? total - done : chunk;
        for (int f = 0; f < n; ++f) {
            const float v = float(amp * std::sin(phase));
            phase += 2.0 * M_PI * hz / rate;
            for (int c = 0; c < channels; ++c) buf[size_t(f) * channels + c] = 0.f;
            for (int c : lit) buf[size_t(f) * channels + c] = v;
        }
        m.process(buf.data(), n);
    }
}

static void feedSilence(LoudnessMeter& m, int rate, int channels, double seconds) {
    feedSine(m, rate, channels, seconds, -200.0, 1000.0, {});
}

int main() {
    // Tech 3341 case 1: stereo 1 kHz at −23 dBFS reads −23.0 LUFS on M, S, I.
    for (int rate : {44100, 48000, 96000}) {
        LoudnessMeter m;
        m.configure(rate, 2);
        feedSine(m, rate, 2, 20.0, -23.0, 1000.0, {0, 1});
        char label[64];
        std::snprintf(label, sizeof label, "1k −23 dBFS @%d: momentary", rate);
        expectNear(label, m.reading().momentary, -23.f, 0.1f);
        std::snprintf(label, sizeof label, "1k −23 dBFS @%d: short-term", rate);
        expectNear(label, m.reading().shortTerm, -23.f, 0.1f);
        std::snprintf(label, sizeof label, "1k −23 dBFS @%d: integrated", rate);
        expectNear(label, m.reading().integrated, -23.f, 0.1f);
    }

    // Tech 3341 case 3: −36 / −23 / −36 dBFS for 10 / 60 / 10 s. The relative
    // gate (−10 LU) drops the quiet ends, so integrated is the loud part's.
    {
        LoudnessMeter m;
        m.configure(48000, 2);
        feedSine(m, 48000, 2, 10.0, -36.0, 1000.0, {0, 1});
        feedSine(m, 48000, 2, 60.0, -23.0, 1000.0, {0, 1});
        feedSine(m, 48000, 2, 10.0, -36.0, 1000.0, {0, 1});
        expectNear("gated integrated, −36/−23/−36", m.reading().integrated, -23.f, 0.1f);
    }

    // Silence is below the absolute gate: no integrated reading at all, and the
    // instantaneous values sit on the floor rather than turning into −inf.
    {
        LoudnessMeter m;
        m.configure(48000, 2);
        feedSilence(m, 48000, 2, 5.0);
        expectTrue("silence: no integrated reading", m.reading().integrated == LoudnessMeter::kNone);
        expectNear("silence: momentary on the floor", m.reading().momentary, LoudnessMeter::kFloorLufs, 0.01f);
        expectNear("silence: true peak on the floor", m.reading().truePeak, LoudnessMeter::kFloorLufs, 0.01f);
    }

    // Tech 3342 case 1: −20 then −30 dBFS, 20 s each → LRA 10 LU.
    {
        LoudnessMeter m;
        m.configure(48000, 2);
        feedSine(m, 48000, 2, 20.0, -20.0, 1000.0, {0, 1});
        feedSine(m, 48000, 2, 20.0, -30.0, 1000.0, {0, 1});
        expectNear("loudness range, −20/−30", m.reading().range, 10.f, 1.f);
    }

    // True peak catches the inter-sample over a sample peak misses: a full-scale
    // sine at fs/4 sampled 45° off its crests reads −3 dB sample-peak but
    // reaches 0 dB between the samples.
    {
        LoudnessMeter m;
        m.configure(48000, 1);
        feedSine(m, 48000, 1, 1.0, 0.0, 12000.0, {0}, M_PI / 4.0);
        expectNear("true peak, fs/4 at 45°", m.reading().truePeak, 0.f, 0.3f);
    }
    {
        LoudnessMeter m;
        m.configure(48000, 2);
        feedSine(m, 48000, 2, 1.0, -6.0, 997.0, {0, 1});
        expectNear("true peak, 997 Hz at −6 dBFS", m.reading().truePeak, -6.f, 0.2f);
    }

    // 5.1: the LFE (channel 3) is not measured; a surround counts +1.5 dB.
    {
        LoudnessMeter m;
        m.configure(48000, 6);
        feedSine(m, 48000, 6, 5.0, -20.0, 1000.0, {3});
        expectTrue("5.1: LFE alone gives no integrated reading",
                   m.reading().integrated == LoudnessMeter::kNone);
    }
    {
        LoudnessMeter front, rear;
        front.configure(48000, 6);
        rear.configure(48000, 6);
        feedSine(front, 48000, 6, 5.0, -20.0, 1000.0, {0});
        feedSine(rear, 48000, 6, 5.0, -20.0, 1000.0, {4});
        expectNear("5.1: rear channel weighted +1.5 dB",
                   rear.reading().integrated - front.reading().integrated, 1.49f, 0.05f);
    }

    // Reset clears everything, and a format change resets by itself.
    {
        LoudnessMeter m;
        m.configure(48000, 2);
        feedSine(m, 48000, 2, 5.0, -23.0, 1000.0, {0, 1});
        m.reset();
        expectTrue("reset: integrated cleared", m.reading().integrated == LoudnessMeter::kNone);
        expectTrue("reset: true peak cleared", m.reading().truePeak == LoudnessMeter::kNone);
        feedSine(m, 48000, 2, 5.0, -23.0, 1000.0, {0, 1});
        m.configure(44100, 2);
        expectTrue("new rate: measurement restarted", m.reading().momentary == LoudnessMeter::kNone);
    }

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("all loudness tests passed\n");
    return 0;
}

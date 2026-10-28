// The finite guards, built the way the phone builds them: with -ffast-math,
// which lets a compiler take any float it can see for finite. clang 21 (NDK
// r29) does that for every float a function is handed, and folded the bit
// tests in util/finite.h away, so on the phone a NaN went through every guard
// while every host compiler here kept them. run_host_tests.sh therefore also
// builds this with the NDK and runs it under qemu when both are installed.
//
//   ./run_host_tests.sh

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

#include "../dsp_engine.h"
#include "../multilane_engine.h"
#include "biquad.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

// Bits the compiler cannot know: a NaN or infinity it could see coming would
// itself be undefined under -ffinite-math-only.
volatile uint32_t kNanBits = 0x7FC00000u;
volatile uint32_t kInfBits = 0xFF800000u;
volatile uint64_t kNanBits64 = 0x7FF8000000000000ull;

float bitsToFloat(uint32_t b) {
    float f;
    std::memcpy(&f, &b, sizeof f);
    return f;
}

bool finiteBits(const float* x, int n) {
    for (int i = 0; i < n; i++) {
        uint32_t b;
        std::memcpy(&b, &x[i], sizeof b);
        if ((b & 0x7F800000u) == 0x7F800000u) return false;
    }
    return true;
}

// Called, not inlined, so the float arrives as a function's argument: what
// clang 21 marks as never NaN.
__attribute__((noinline)) bool isFiniteArg(float v) { return dspIsFinite(v); }
__attribute__((noinline)) bool isFiniteArg(double v) { return dspIsFinite(v); }

void helpers() {
    const float nan = bitsToFloat(kNanBits);
    const float inf = bitsToFloat(kInfBits);
    double nan64;
    const uint64_t b64 = kNanBits64;
    std::memcpy(&nan64, &b64, sizeof nan64);
    check(!isFiniteArg(nan) && !isFiniteArg(inf) && isFiniteArg(0.5f), "dspIsFinite(float) sees a NaN and an infinity");
    check(!isFiniteArg(nan64) && isFiniteArg(0.5), "dspIsFinite(double) sees a NaN");
    float x[37];
    for (int i = 0; i < 37; i++) x[i] = 0.01f * static_cast<float>(i);
    check(dspAllFinite(x, 37), "dspAllFinite passes finite samples");
    x[11] = nan;
    x[30] = inf;
    check(!dspAllFinite(x, 37), "dspAllFinite catches a NaN and an infinity");
    check(dspZeroNonFinite(x, 37) && finiteBits(x, 37) && x[11] == 0.0f && x[30] == 0.0f && x[12] == 0.12f,
          "dspZeroNonFinite silences them and nothing else");
}

void engine() {
    const float nan = bitsToFloat(kNanBits);
    const float inf = bitsToFloat(kInfBits);
    DspEngine e(48000, 1024);
    e.addPlugin(0, 0, static_cast<int>(SnapinType::REVERB));
    std::vector<float> l(512), r(512);
    bool finite = true;
    for (int b = 0; b < 20; b++) {
        for (int i = 0; i < 512; i++) { l[i] = 0.1f * static_cast<float>(i % 7 - 3); r[i] = -l[i]; }
        if (b == 3) { l[100] = nan; r[200] = inf; }
        e.process(l.data(), r.data(), 512);
        finite = finite && finiteBits(l.data(), 512) && finiteBits(r.data(), 512);
    }
    check(finite, "a NaN and an infinity into the stereo engine never come out");

    const int first[] = {0, 2, 3, 4, 6, 8, 10};
    const int second[] = {1, -1, -1, 5, 7, 9, 11};
    MultiLaneEngine m(48000, 1024);
    m.forEach([](DspEngine& d) { d.addPlugin(0, 0, static_cast<int>(SnapinType::REVERB)); });
    m.configureLanes(first, second, 7);
    std::vector<std::vector<float>> ch(12, std::vector<float>(512));
    float* p[12];
    for (int c = 0; c < 12; c++) p[c] = ch[c].data();
    finite = true;
    for (int b = 0; b < 20; b++) {
        for (int c = 0; c < 12; c++) {
            for (int i = 0; i < 512; i++) ch[c][i] = 0.05f * static_cast<float>((i + c) % 5 - 2);
        }
        if (b == 3) { ch[0][10] = nan; ch[2][20] = inf; ch[9][30] = nan; }
        m.processPlanar(p, 12, 512);
        for (int c = 0; c < 12; c++) finite = finite && finiteBits(ch[c].data(), 512);
    }
    check(finite, "nor out of a 7.1.4 stream");

    // Knobs: a NaN is refused, or taken as the knob's neutral value.
    DspEngine k(48000, 1024);
    k.setBusGain(0, nan);
    std::vector<float> a(256, 0.25f), b(256, 0.25f);
    k.process(a.data(), b.data(), 256);
    check(k.lastProcessUnchanged() && a[255] == 0.25f, "a NaN fader is taken as 0 dB");
    std::unique_ptr<SnapinProcessor> gain(createSnapin(SnapinType::GAIN));
    gain->setDryWet(nan);
    check(gain->getDryWet() == 1.0f, "a NaN dry/wet is taken as fully wet");
    gain->applyParameter(0, 3.0f);
    gain->applyParameter(0, nan);
    check(gain->getParameter(0) == 3.0f, "a NaN parameter is ignored");
    Biquad q;
    q.configure(BiquadType::LowPass, 48000.0, 1000.0, 0.707);
    float before = 0.5f, after = 0.5f;
    Biquad q2 = q;
    q.configure(BiquadType::LowPass, 48000.0, static_cast<double>(nan), 0.707);
    for (int i = 0; i < 64; i++) { before = q2.process(0.5f); after = q.process(0.5f); }
    check(before == after && dspIsFinite(after), "a biquad given a NaN frequency keeps its old one");
}

}  // namespace

int main() {
    helpers();
    engine();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}

// Host test for render/channel_placer.h, the audio side of the mixer's
// spatial map.
//
//   c++ -std=c++17 -O2 -ffast-math -I.. channel_placer_test.cpp -o t && ./t
//
// Built with -ffast-math, as the library is on the phone.

#include <cmath>
#include <cstdio>
#include <vector>

#include "render/channel_placer.h"

using tf::atmos::render::ChannelPlacer;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr float kDeg = 3.14159265f / 180.0f;

/** RMS of one ear (0 left, 1 right) of interleaved stereo, from [from]. */
double rms(const std::vector<float>& st, int ear, size_t from) {
    double s = 0.0;
    size_t n = 0;
    for (size_t i = from; i * 2 + 1 < st.size(); i++, n++) s += st[i * 2 + ear] * st[i * 2 + ear];
    return std::sqrt(s / std::max<size_t>(1, n));
}

double db(double x) { return 20.0 * std::log10(std::max(x, 1e-12)); }

/**
 * Runs [seconds] of a 4 kHz tone on channel [lit] of a 6-channel bed
 * (FL FR FC LFE BL BR) through [p], in ragged blocks, and returns the stereo.
 */
std::vector<float> run(ChannelPlacer& p, int lit, double seconds, int channels = 6) {
    const size_t total = static_cast<size_t>(kRate * seconds);
    std::vector<std::vector<float>> ch(channels, std::vector<float>(1024, 0.0f));
    std::vector<const float*> ptr(channels);
    std::vector<float> out(total * 2), block(2048);
    const int sizes[] = {256, 1024, 77, 512};
    size_t done = 0;
    for (int b = 0; done < total; b++) {
        const int n = static_cast<int>(std::min<size_t>(sizes[b % 4], total - done));
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < n; i++) {
                const double t = static_cast<double>(done + i) / kRate;
                // 4 kHz: where the head shadows the far ear, so the level
                // difference between the ears says plainly where it was put.
                ch[c][i] = c == lit ? static_cast<float>(0.5 * std::sin(2 * M_PI * 4000 * t)) : 0.0f;
            }
            ptr[c] = ch[c].data();
        }
        p.process(ptr.data(), n, block.data());
        std::copy(block.begin(), block.begin() + 2 * n, out.begin() + 2 * done);
        done += static_cast<size_t>(n);
    }
    return out;
}

void place(ChannelPlacer& p, int channel, float azDeg, float elDeg, float gain, int channels = 6) {
    std::vector<float> az(channels, 0.0f), el(channels, 0.0f), g(channels, 1.0f);
    az[channel] = azDeg * kDeg;
    el[channel] = elDeg * kDeg;
    g[channel] = gain;
    p.setPlacement(az.data(), el.data(), g.data(), channels);
}

void binauralFollowsTheMap() {
    ChannelPlacer p;
    p.configure(kRate, 6, 3);
    p.setMode(true, 1.0f, true, false, 80);

    // At 60 degrees, not 90: a measured head's shadow is shallowest straight
    // to the side, where sound bending round it meets in phase at the far ear
    // (KEMAR gives ~11 dB at 60 degrees and ~6 dB at 90 degrees, at 4 kHz).
    place(p, 0, -60.0f, 0.0f, 1.0f);
    auto left = run(p, 0, 0.5);
    const double ildLeft = db(rms(left, 0, 4800)) - db(rms(left, 1, 4800));
    char what[160];
    std::snprintf(what, sizeof what, "a channel dragged to the left is louder in the left ear (%.1f dB)", ildLeft);
    check(ildLeft > 8.0, what);

    place(p, 0, 60.0f, 0.0f, 1.0f);
    auto right = run(p, 0, 0.5);
    const double ildRight = db(rms(right, 1, 4800)) - db(rms(right, 0, 4800));
    std::snprintf(what, sizeof what, "the same channel dragged to the right is louder in the right ear (%.1f dB)", ildRight);
    check(ildRight > 8.0, what);

    place(p, 0, 0.0f, 0.0f, 1.0f);
    auto front = run(p, 0, 0.5);
    const double ildFront = std::fabs(db(rms(front, 0, 4800)) - db(rms(front, 1, 4800)));
    std::snprintf(what, sizeof what, "straight ahead is balanced between the ears (%.2f dB)", ildFront);
    check(ildFront < 1.5, what);

    // Distance: half the gain is 6 dB down.
    place(p, 0, 0.0f, 0.0f, 0.5f);
    auto far = run(p, 0, 0.5);
    const double drop = db(rms(front, 0, 4800)) - db(rms(far, 0, 4800));
    std::snprintf(what, sizeof what, "pulled back to half gain it is 6 dB quieter (%.2f dB)", drop);
    check(std::fabs(drop - 6.02) < 0.3, what);
}

void panMode() {
    ChannelPlacer p;
    p.configure(kRate, 6, 3);
    p.setMode(false, 1.0f, true, false, 80);

    place(p, 0, -90.0f, 0.0f, 1.0f);
    auto hard = run(p, 0, 0.2);
    check(rms(hard, 1, 1000) < 1e-6 && rms(hard, 0, 1000) > 0.1, "pan: hard left is all left");

    place(p, 0, 0.0f, 0.0f, 1.0f);
    auto centre = run(p, 0, 0.2);
    const double l = rms(centre, 0, 1000), r = rms(centre, 1, 1000);
    char what[160];
    std::snprintf(what, sizeof what, "pan: ahead is -3 dB each side, equal power (%.2f / %.2f dB)",
                  db(l) - db(rms(hard, 0, 1000)), db(r) - db(rms(hard, 0, 1000)));
    check(std::fabs(l - r) < 1e-4 && std::fabs(db(l) - db(rms(hard, 0, 1000)) + 3.01) < 0.1, what);

    // Behind folds to the same place as ahead: stereo has no back.
    place(p, 0, 180.0f, 0.0f, 1.0f);
    auto behind = run(p, 0, 0.2);
    check(std::fabs(rms(behind, 0, 1000) - l) < 1e-3, "pan: behind folds to the centre, as stereo must");
}

void lfeIsDiffuse() {
    for (bool binaural : {true, false}) {
        ChannelPlacer p;
        p.configure(kRate, 6, 3);
        p.setMode(binaural, 1.0f, true, false, 80);
        // Wherever it is dragged, the LFE reaches both ears alike.
        place(p, 3, -120.0f, 0.0f, 1.0f);
        auto out = run(p, 3, 0.3);
        const double diff = std::fabs(db(rms(out, 0, 2000)) - db(rms(out, 1, 2000)));
        check(diff < 0.01, binaural ? "binaural: the LFE is non-directional" : "pan: the LFE is non-directional");
    }
}

void dragIsSmoothAndFinite() {
    // A dot swept round the listener every block, with gain changes, on a
    // 16-channel bed: never a non-finite sample and no step between blocks
    // larger than the signal itself could make.
    ChannelPlacer p;
    p.configure(kRate, 16, 3);
    p.setMode(true, 1.0f, true, true, 80);
    std::vector<std::vector<float>> ch(16, std::vector<float>(256));
    std::vector<const float*> ptr(16);
    std::vector<float> out(512);
    bool finite = true;
    float worstJump = 0.0f, last = 0.0f;
    long n = 0;
    for (int b = 0; b < 400; b++) {
        std::vector<float> az(16), el(16), g(16);
        for (int c = 0; c < 16; c++) {
            az[c] = (static_cast<float>(b * 3 + c * 22) - 180.0f) * kDeg;
            el[c] = (c >= 10 ? 45.0f : 0.0f) * kDeg;
            g[c] = 0.5f + 0.5f * static_cast<float>((b + c) % 3);
        }
        p.setPlacement(az.data(), el.data(), g.data(), 16);
        for (int c = 0; c < 16; c++) {
            for (int i = 0; i < 256; i++) ch[c][i] = 0.05f * static_cast<float>(std::sin(0.01 * (n + i) * (c + 1)));
            ptr[c] = ch[c].data();
        }
        p.process(ptr.data(), 256, out.data());
        for (int i = 0; i < 512; i++) {
            uint32_t bits;
            std::memcpy(&bits, &out[i], 4);
            if ((bits & 0x7F800000u) == 0x7F800000u) finite = false;
        }
        worstJump = std::max(worstJump, std::fabs(out[0] - last));
        last = out[510];
        n += 256;
    }
    check(finite, "a 16-channel bed with every dot dragging stays finite");
    char what[160];
    std::snprintf(what, sizeof what, "no click at block edges while dragging (worst step %.4f)", worstJump);
    check(worstJump < 0.2f, what);
}

void nonsenseIsIgnored() {
    ChannelPlacer p;
    p.configure(kRate, 6, 3);
    p.setMode(true, 1.0f, true, false, 80);
    const float nan = std::nanf("");
    std::vector<float> az(6, nan), el(6, INFINITY), g(6, 1e30f);
    p.setPlacement(az.data(), el.data(), g.data(), 6);
    auto out = run(p, 0, 0.1);
    bool finite = true;
    for (float v : out) {
        uint32_t bits;
        std::memcpy(&bits, &v, 4);
        if ((bits & 0x7F800000u) == 0x7F800000u) finite = false;
    }
    check(finite, "NaN, infinite and huge placements play finite audio");
}

}  // namespace

int main() {
    binauralFollowsTheMap();
    panMode();
    lfeIsDiffuse();
    dragIsSmoothAndFinite();
    nonsenseIsIgnored();
    std::printf("%s\n", failures ? "FAILED" : "all passed");
    return failures ? 1 : 0;
}

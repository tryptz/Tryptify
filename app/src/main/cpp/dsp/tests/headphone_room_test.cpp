// Host test: the Crossfeed page's Room (headphone_room.h).
//
// - The LFE split is linear phase and complementary: where the room passes
//   its input through, a song with the same two sides comes out as itself,
//   late by the split's delay and nothing else, whatever the crossover.
// - The LFE is one channel: the bass of one side reaches both ears alike,
//   at the level it is set to, while what goes into the room has none.
// - It says how late it is, with the LFE on and off.
// - A listener's HRTF set is heard (one with its ears swapped turns a left
//   sound to the right), and clearing it is the built-in set again, sample
//   for sample.
//
// Built with -ffast-math, as the phone builds it; finiteness is asked of
// dspAllFinite (util/finite.h).
#include <cmath>
#include <cstdio>
#include <vector>

#include "../headphone_room.h"
#include "../util/finite.h"

namespace {

int failures = 0;
void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

void run(HeadphoneRoom& room, std::vector<float>& l, std::vector<float>& r, int block) {
    for (size_t at = 0; at < l.size(); at += static_cast<size_t>(block)) {
        const int n = static_cast<int>(std::min(l.size() - at, static_cast<size_t>(block)));
        room.process(l.data() + at, r.data() + at, n);
    }
}

double rms(const std::vector<float>& x, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to; i++) e += static_cast<double>(x[i]) * x[i];
    return std::sqrt(e / static_cast<double>(to - from));
}

void tone(std::vector<float>& x, double hz, double rate, double amp) {
    for (size_t i = 0; i < x.size(); i++) x[i] += static_cast<float>(amp * std::sin(6.283185307179586 * hz * i / rate));
}

void noise(std::vector<float>& x, unsigned seed, float amp) {
    unsigned s = seed;
    for (float& v : x) {
        s = s * 1664525u + 1013904223u;
        v += (static_cast<int>(s >> 8) / 8388608.0f - 1.0f) * amp;
    }
}

// At 32 kHz the room has no rate to play at (its own 38.4 to 50 kHz, or a
// half or a quarter of the stream's) and passes its input through: what is
// left is the split.
void splitSumsBack() {
    std::printf("-- the split adds back to the song, delayed, at every crossover\n");
    const int rate = 32000, n = 32000;
    for (float hz : {40.0f, 80.0f, 200.0f}) {
        HeadphoneRoom room;
        room.prepare(rate, 4096);
        room.setParameter(HeadphoneRoom::LFE_ON, 1.0f);
        room.setParameter(HeadphoneRoom::LFE_HZ, hz);
        std::vector<float> l(n, 0.0f);
        noise(l, 11u, 0.3f);
        tone(l, 50.0, rate, 0.4);
        std::vector<float> in = l, r = l;
        run(room, l, r, 777);
        const int d = room.splitDelay();
        double worst = 0.0;
        for (int i = d; i < n; i++) {
            worst = std::max(worst, static_cast<double>(std::fabs(l[static_cast<size_t>(i)] - in[static_cast<size_t>(i - d)])));
            worst = std::max(worst, static_cast<double>(std::fabs(r[static_cast<size_t>(i)] - in[static_cast<size_t>(i - d)])));
        }
        char what[160];
        std::snprintf(what, sizeof what, "crossover %.0f Hz: the song %d samples late, within %.2g", hz, d, worst);
        check(worst < 2e-5 && dspAllFinite(l.data(), n), what);
    }
}

// The bass of one side is in both ears alike, at its level; the room's
// share has none of it.
void lfeIsOneChannel() {
    std::printf("-- the LFE is one channel, at its level, and the room gets no bass\n");
    const int rate = 32000, n = 64000;
    auto bassOf = [&](float db, std::vector<float>& l, std::vector<float>& r) {
        HeadphoneRoom room;
        room.prepare(rate, 4096);
        room.setParameter(HeadphoneRoom::LFE_ON, 1.0f);
        room.setParameter(HeadphoneRoom::LFE_HZ, 120.0f);
        room.setParameter(HeadphoneRoom::LFE_DB, db);
        l.assign(n, 0.0f);
        r.assign(n, 0.0f);
        tone(l, 30.0, rate, 0.5);  // bass on the left only
        run(room, l, r, 1024);
    };
    std::vector<float> l0, r0, l6, r6;
    bassOf(0.0f, l0, r0);
    bassOf(-6.0f, l6, r6);
    const size_t from = n / 2, to = n;
    const double left = rms(l0, from, to), right = rms(r0, from, to);
    char what[160];
    std::snprintf(what, sizeof what, "a 30 Hz tone on the left: left %.4f, right %.4f (one channel, half each)", left, right);
    check(std::fabs(left - right) < 0.01 * left && std::fabs(left - 0.5 * 0.5 / std::sqrt(2.0)) < 0.02 * left, what);
    const double ratio = rms(l6, from, to) / left;
    std::snprintf(what, sizeof what, "at -6 dB the bass is %.3f of what it is at 0 dB", ratio);
    check(std::fabs(ratio - std::pow(10.0, -6.0 / 20.0)) < 0.01, what);
}

void saysHowLate() {
    std::printf("-- it says how late it is\n");
    HeadphoneRoom room;
    room.prepare(48000, 4096);
    const int off = room.latency();
    room.setParameter(HeadphoneRoom::LFE_ON, 1.0f);
    const int on = room.latency();
    char what[160];
    std::snprintf(what, sizeof what, "48 kHz: %d samples with the LFE off, %d on (the split's %d more, about 21 ms)",
                  off, on, room.splitDelay());
    check(off > 0 && on == off + room.splitDelay() && room.splitDelay() == 1023, what);
    for (int rate : {44100, 96000, 192000}) {
        HeadphoneRoom r;
        r.prepare(rate, 4096);
        r.setParameter(HeadphoneRoom::LFE_ON, 1.0f);
        const double ms = 1000.0 * r.splitDelay() / rate;
        std::snprintf(what, sizeof what, "%d Hz: the split is %.1f ms late", rate, ms);
        check(ms > 20.0 && ms < 23.5, what);
    }
}

// The built-in table, packed as AtmosNative.nativePrepareSofa packs a file,
// with its ears swapped where [swap].
std::vector<float> builtInSet(bool swap) {
    using namespace tf::atmos::render;
    std::vector<float> taps, delays;
    for (int e = 0; e < kHrirElCount; e++) {
        for (int a = 0; a < kHrirAzCount; a++) {
            for (int ear = 0; ear < 2; ear++) {
                const int from = swap ? 1 - ear : ear;
                taps.insert(taps.end(), &kHrirData[e][a][from][0], &kHrirData[e][a][from][0] + kHrirTaps);
                delays.push_back(kHrirDelay[e][a][from]);
            }
        }
    }
    return SofaSet::pack(taps.data(), delays.data(), 0, nullptr, nullptr, nullptr);
}

void choosesItsHrtf() {
    std::printf("-- a chosen HRTF is heard, and clearing it is the built-in one again\n");
    const int rate = 48000, n = 48000;
    auto play = [&](int how, std::vector<float>& l, std::vector<float>& r) {
        HeadphoneRoom room;
        room.prepare(rate, 4096);
        room.setParameter(HeadphoneRoom::ROOM, 0.0f);
        room.setParameter(HeadphoneRoom::BINAURAL, 100.0f);
        room.setParameter(HeadphoneRoom::ANGLE, 60.0f);
        if (how == 1) {
            const std::vector<float> set = builtInSet(true);
            check(room.setSofaSet(set.data(), set.size()), "the packed set is taken");
        }
        if (how == 2) {
            const std::vector<float> set = builtInSet(true);
            room.setSofaSet(set.data(), set.size());
            room.clearSofaSet();
        }
        room.reset();
        l.assign(n, 0.0f);
        r.assign(n, 0.0f);
        noise(l, 5u, 0.2f);  // a sound on the left
        run(room, l, r, 512);
    };
    std::vector<float> l0, r0, l1, r1, l2, r2;
    play(0, l0, r0);
    play(1, l1, r1);
    play(2, l2, r2);
    const size_t from = n / 4;
    char what[200];
    std::snprintf(what, sizeof what, "built in: left %.4f > right %.4f; ears swapped: left %.4f < right %.4f",
                  rms(l0, from, n), rms(r0, from, n), rms(l1, from, n), rms(r1, from, n));
    check(rms(l0, from, n) > 1.2 * rms(r0, from, n) && rms(r1, from, n) > 1.2 * rms(l1, from, n), what);
    double worst = 0.0;
    for (int i = 0; i < n; i++) {
        worst = std::max(worst, static_cast<double>(std::fabs(l2[static_cast<size_t>(i)] - l0[static_cast<size_t>(i)])));
        worst = std::max(worst, static_cast<double>(std::fabs(r2[static_cast<size_t>(i)] - r0[static_cast<size_t>(i)])));
    }
    std::snprintf(what, sizeof what, "cleared, it is the built-in set again (within %.2g)", worst);
    check(worst < 1e-6, what);
    std::vector<float> junk(100, 1.0f);
    HeadphoneRoom room;
    room.prepare(rate, 4096);
    check(!room.setSofaSet(junk.data(), junk.size()), "something that is not a set is refused");
}

}  // namespace

int main() {
    splitSumsBack();
    lfeIsOneChannel();
    saysHowLate();
    choosesItsHrtf();
    if (failures) {
        std::printf("%d failed\n", failures);
        return 1;
    }
    std::printf("headphone_room_test: all held\n");
    return 0;
}

// Host test for the mixer's music links: knobs that follow a band of the song.
// Build and run with the other host tests:
//
//   ./run_host_tests.sh
//
// Two halves. The follower and the formula against the numbers the Kotlin
// side is held to (MusicLinkFollowerTest, LinkFormulaTest), so a knob and a
// visual setting linked alike move alike. Then the engine: a link moves its
// knob with the band and only between lowest and highest, a save keeps the
// knob's own value, links follow their effects when the chain changes, go
// with them, give the knob back when they pause or go, and a following lane
// takes the primary's values.

#include <cmath>
#include <cstdio>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include "../dsp_engine.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr float kFrame = 1.0f / 60.0f;

float run(modroute::Follower& f, const modroute::Shape& s, float db, float seconds) {
    float v = 0.0f;
    const int steps = std::max(1, static_cast<int>(seconds / kFrame));
    for (int i = 0; i < steps; i++) v = f.step(db, kFrame, s);
    return v;
}

/** Alternates a and b dB every beat seconds; the lowest and highest value in the last half. */
void swing(modroute::Follower& f, const modroute::Shape& s, float a, float b, float beat, float seconds,
           float* lo = nullptr, float* hi = nullptr) {
    const int steps = static_cast<int>(seconds / kFrame);
    float l = 1e9f, h = -1e9f;
    for (int i = 0; i < steps; i++) {
        const float v = f.step(static_cast<int>((i * kFrame) / beat) % 2 == 0 ? a : b, kFrame, s);
        if (i > steps / 2) { l = std::min(l, v); h = std::max(h, v); }
    }
    if (lo) *lo = l;
    if (hi) *hi = h;
}

modroute::Shape shape(float lowest = 0.0f, float highest = 1.0f) {
    modroute::Shape s;
    s.lowest = lowest;
    s.highest = highest;
    // Feel 0.35, as LinkShape's default.
    s.attackMs = 1.0f + 59.0f * 0.35f * 0.35f;
    s.releaseMs = 40.0f * std::pow(30.0f, 0.35f);
    return s;
}

void followerMatchesKotlin() {
    {
        modroute::Follower f;
        const auto s = shape(0.2f, 0.8f);
        check(std::fabs(run(f, s, -120.0f, 2.0f) - 0.2f) < 1e-4f, "silence rests at the lowest value");
    }
    {
        modroute::Follower f;
        auto s = shape(0.2f, 0.8f);
        s.way = modroute::DOWN;
        check(std::fabs(run(f, s, -120.0f, 2.0f) - 0.8f) < 1e-4f, "going down rests at the highest value");
    }
    {
        modroute::Follower f;
        const auto s = shape();
        run(f, s, -40.0f, 2.0f);
        const float hit = run(f, s, -10.0f, 0.1f);
        const float after = run(f, s, -40.0f, 2.0f);
        check(hit > 0.8f, "a kick 30 dB over the floor moves it most of the way");
        check(after < 0.05f, "two seconds after the kick it is back down");
    }
    {
        modroute::Follower f;
        const auto s = shape();
        run(f, s, -40.0f, 2.0f);
        run(f, s, -10.0f, 0.2f);
        check(run(f, s, -10.0f, 30.0f) < 0.2f, "a held note sinks into the floor");
    }
    {
        modroute::Follower f;
        auto s = shape();
        s.quietDb = -60.0f;
        run(f, s, -95.0f, 2.0f);
        for (int i = 0; i < 20; i++) {
            run(f, s, -70.0f, 0.1f);
            run(f, s, -95.0f, 0.1f);
        }
        check(f.strength == 0.0f, "anything under the quiet limit never moves it");
    }
    {
        modroute::Follower f;
        auto s = shape();
        s.quietDb = -60.0f;
        s.boostDb = 20.0f;
        run(f, s, -95.0f, 2.0f);
        run(f, s, -70.0f, 0.1f);
        check(f.strength > 0.5f, "a boost lifts a quiet band past the limit");
    }
    {
        modroute::Follower f;
        const auto s = shape();
        run(f, s, -120.0f, 2.2f);
        const bool slept = f.asleep(s);
        f.step(-10.0f, kFrame, s);
        check(slept && !f.asleep(s), "asleep after two seconds of silence, awake on sound");
    }
    {
        // After a pause the music moves it at once, not pinned at the top.
        modroute::Follower f;
        auto s = shape();
        s.attackMs = 1.0f;
        s.releaseMs = 40.0f;
        s.smoothHz = 0.0f;
        swing(f, s, -20.0f, -14.0f, 0.25f, 4.0f);
        run(f, s, -120.0f, 3.0f);
        float lo, hi;
        swing(f, s, -20.0f, -14.0f, 0.25f, 2.0f, &lo, &hi);
        check(lo < 0.2f && hi > 0.8f, "after a pause it falls back between hits");
    }
    {
        // A loud, flat master (a 4 dB swing) still moves it the whole way.
        modroute::Follower f;
        auto s = shape();
        s.attackMs = 1.0f;
        s.releaseMs = 40.0f;
        s.smoothHz = 0.0f;
        float lo, hi;
        swing(f, s, -9.0f, -5.0f, 0.25f, 12.0f, &lo, &hi);
        check(lo < 0.15f && hi > 0.85f, "a 4 dB swing sweeps the range");
    }
    {
        // A dynamic song keeps its twelve decibels: 6 dB over the floor is half.
        modroute::Follower f;
        auto s = shape();
        s.attackMs = 1.0f;
        s.releaseMs = 40.0f;
        s.smoothHz = 0.0f;
        run(f, s, -40.0f, 2.0f);
        run(f, s, -10.0f, 0.05f);
        run(f, s, -40.0f, 0.5f);
        check(std::fabs(run(f, s, -34.0f, 0.05f) - 0.5f) < 0.08f, "6 dB over the floor is half");
    }
    {
        // Every way, every curve, a formula that tries to escape: always
        // finite, always inside lowest..highest, whatever comes in.
        std::mt19937 rng(7);
        std::uniform_real_distribution<float> level(-100.0f, 20.0f), dt(0.0f, 0.2f);
        bool inside = true;
        // value + band * 10, in LinkFormula's program form.
        const float escape[] = {1, 0, 2, 0, 0, 10, 5, 0, 3, 0};
        for (int way = 0; way < 3; way++) {
            for (int curve = 0; curve < 3; curve++) {
                auto s = shape(0.3f, 0.6f);
                s.way = way;
                s.curve = curve;
                s.programLen = 10;
                std::copy(escape, escape + 10, s.program);
                modroute::Follower f;
                for (int i = 0; i < 2000; i++) {
                    const float db = i % 97 == 0 ? (i % 2 ? INFINITY : -INFINITY) : level(rng);
                    const float v = f.step(db, i % 113 == 0 ? NAN : dt(rng), s);
                    if (!dspIsFinite(v) || v < 0.3f || v > 0.6f) inside = false;
                }
            }
        }
        check(inside, "the value stays between lowest and highest whatever comes in");
    }
}

void formulaMatchesKotlin() {
    float out = 0.0f;
    // value + (fft[band] * 0.25) with value 0.2, band 0.4.
    const float spec[] = {1, 0, 2, 0, 0, 0.25f, 5, 0, 3, 0};
    check(modroute::evaluate(spec, 10, 0.2f, 0.4f, out) && std::fabs(out - 0.3f) < 1e-6f, "the spec's own example");
    // band / (value - value): dividing by zero gives zero.
    const float div[] = {2, 0, 1, 0, 1, 0, 4, 0, 6, 0};
    check(modroute::evaluate(div, 10, 1.0f, 1.0f, out) && out == 0.0f, "dividing by zero gives zero");
    // clamp(band * 3, 0, 1)
    const float clamp[] = {2, 0, 0, 3, 5, 0, 0, 0, 0, 1, 11, 0};
    check(modroute::evaluate(clamp, 12, 0.0f, 0.5f, out) && out == 1.0f, "clamp holds its range");
    const float broken[] = {3, 0};
    check(!modroute::evaluate(broken, 2, 0.0f, 0.0f, out), "a broken program is refused");
    const float extra[] = {2, 0, 2, 0};
    check(!modroute::evaluate(extra, 4, 0.0f, 0.0f, out), "a program leaving two values is refused");
}

void detectorHearsItsBand() {
    const int rate = 48000;
    modroute::Detector d;
    d.design(30.0f, 150.0f, rate);
    std::vector<float> low(4096), high(4096);
    for (int i = 0; i < 4096; i++) {
        low[i] = std::sin(2.0f * 3.1415927f * 60.0f * i / rate);
        high[i] = std::sin(2.0f * 3.1415927f * 5000.0f * i / rate);
    }
    d.blockDb(low.data(), low.data(), 4096);  // settle
    const float inBand = d.blockDb(low.data(), low.data(), 4096);
    modroute::Detector d2;
    d2.design(30.0f, 150.0f, rate);
    d2.blockDb(high.data(), high.data(), 4096);
    const float outOfBand = d2.blockDb(high.data(), high.data(), 4096);
    check(std::fabs(inBand + 3.0f) < 1.5f, "a full-scale 60 Hz tone reads about -3 dBFS in the kick band");
    check(outOfBand < -40.0f, "a 5 kHz tone barely registers in the kick band");
    std::vector<float> zero(512, 0.0f);
    check(d.blockDb(zero.data(), zero.data(), 0) == -120.0f, "an empty block is silence");
}

// The flat form KnobLinkCodec.toNative writes, one link.
std::vector<float> link(int id, int bus, int slot, int param, float lowest, float highest,
                        int flags = 1, int source = modroute::SONG) {
    std::vector<float> v = {
        static_cast<float>(id), static_cast<float>(bus), static_cast<float>(slot), static_cast<float>(param),
        static_cast<float>(source), 30.0f, 150.0f, lowest, highest, static_cast<float>(modroute::UP),
        1.0f, 40.0f, static_cast<float>(modroute::EVEN), -60.0f, 0.0f, 0.0f, static_cast<float>(flags), 0.0f,
    };
    return v;
}

std::vector<float> join(std::initializer_list<std::vector<float>> parts) {
    std::vector<float> out;
    for (const auto& p : parts) out.insert(out.end(), p.begin(), p.end());
    return out;
}

// Kick-like bursts: 60 Hz for 50 ms in every 500 ms, silence between.
void kicks(DspEngine& e, int blocks, int& t, float* peakOut = nullptr) {
    constexpr int n = 512;
    std::vector<float> l(n), r(n);
    float peak = 0.0f;
    for (int b = 0; b < blocks; b++) {
        for (int i = 0; i < n; i++, t++) {
            const bool on = (t % 24000) < 2400;
            l[i] = r[i] = on ? 0.5f * std::sin(2.0f * 3.1415927f * 60.0f * t / 48000.0f) : 0.0f;
        }
        e.process(l.data(), r.data(), n);
        for (int i = 0; i < n; i++) peak = std::max(peak, std::fabs(l[i]));
    }
    if (peakOut) *peakOut = peak;
}

// The output peak of the same kicks through a plain Gain at [gainDb], no link.
float referencePeak(float gainDb, bool stereoAfter = false) {
    auto e = std::make_unique<DspEngine>(48000, 512);
    e->addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
    if (stereoAfter) e->addPlugin(0, 1, static_cast<int>(SnapinType::STEREO));
    e->setParameter(0, 0, 0, gainDb);
    int t = 0;
    float peak = 0.0f;
    kicks(*e, 100, t);
    kicks(*e, 100, t, &peak);
    return peak;
}

float modValue(DspEngine& e, int id) {
    float v[modroute::MAX_ROUTES * 4];
    const int n = e.getModValues(v, modroute::MAX_ROUTES * 4);
    for (int i = 0; i + 3 < n; i += 4) {
        if (static_cast<int>(v[i]) == id) return v[i + 2];
    }
    return NAN;
}

bool savedParamIs(DspEngine& e, const char* expect) {
    return e.getStateJson().find(expect) != std::string::npos;
}

void engineMovesTheKnob() {
    auto e = std::make_unique<DspEngine>(48000, 512);
    e->addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
    e->setParameter(0, 0, 0, -6.0f);
    const auto data = link(7, 0, 0, 0, -24.0f, 0.0f);
    e->setModRoutes(data.data(), static_cast<int>(data.size()));
    check(e->modRouteCount() == 1, "the link is taken");

    int t = 0;
    float minSeen = 1e9f, maxSeen = -1e9f;
    for (int k = 0; k < 40; k++) {
        kicks(*e, 4, t);
        const float v = modValue(*e, 7);
        minSeen = std::min(minSeen, v);
        maxSeen = std::max(maxSeen, v);
    }
    check(maxSeen - minSeen > 10.0f, "the kick moves the knob a long way");
    check(minSeen >= -24.0f && maxSeen <= 0.0f, "and only between lowest and highest");
    check(savedParamIs(*e, "\"params\":[-6]"), "a save keeps the knob's own value");

    // Turning a linked knob sets its own value and leaves the sound to the link.
    e->setParameter(0, 0, 0, -3.0f);
    check(savedParamIs(*e, "\"params\":[-3]"), "turning a linked knob changes what is saved");

    // A plugin added before it: the link moves with its effect.
    e->addPlugin(0, 0, static_cast<int>(SnapinType::STEREO));
    check(savedParamIs(*e, "{\"type\":0,\"bypassed\":false,\"dryWet\":1,\"os\":1,\"params\":[-3]}"),
          "an effect added before it moves the link along a slot");
    // Moved to the front: still the gain's.
    e->movePlugin(0, 1, 0);
    check(savedParamIs(*e, "{\"type\":0,\"bypassed\":false,\"dryWet\":1,\"os\":1,\"params\":[-3]}"),
          "moving the effect takes its link with it");
    kicks(*e, 20, t);
    check(dspIsFinite(modValue(*e, 7)), "and it keeps moving there");

    // Paused: the knob goes back to its own value.
    const auto paused = link(7, 0, 0, 0, -24.0f, 0.0f, 1 | 2);
    e->setModRoutes(paused.data(), static_cast<int>(paused.size()));
    float peak = 0.0f;
    // Past the effect's own ramp from where the music left it.
    kicks(*e, 100, t);
    kicks(*e, 100, t, &peak);
    check(std::fabs(peak - referencePeak(-3.0f, true)) < 1e-3f, "a paused link gives the knob back");

    // Removing the effect drops its link.
    e->removePlugin(0, 0);
    check(e->modRouteCount() == 0, "removing the effect drops its link");

    // Loading a mix drops every link: they travel beside it.
    e->addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
    e->setModRoutes(data.data(), static_cast<int>(data.size()));
    e->loadStateJson(e->getStateJson());
    check(e->modRouteCount() == 0, "loading a mix drops the links");
}

void linksGoBack() {
    auto e = std::make_unique<DspEngine>(48000, 512);
    e->addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
    e->setParameter(0, 0, 0, -9.0f);
    const auto data = link(1, 0, 0, 0, -30.0f, -20.0f);
    e->setModRoutes(data.data(), static_cast<int>(data.size()));
    int t = 0;
    kicks(*e, 40, t);
    e->setModRoutes(nullptr, 0);
    float peak = 0.0f;
    kicks(*e, 100, t);
    kicks(*e, 100, t, &peak);
    check(std::fabs(peak - referencePeak(-9.0f)) < 1e-3f, "a removed link puts its knob back");
    check(savedParamIs(*e, "\"params\":[-9]"), "and the save is the knob's own value");

    // Junk in: nothing taken, nothing broken.
    std::vector<float> junk(100, NAN);
    e->setModRoutes(junk.data(), static_cast<int>(junk.size()));
    check(e->modRouteCount() == 0, "a link that is not one is refused");
    auto bad = link(2, 0, 9, 0, 0.0f, 1.0f);
    bad[17] = 4000.0f;  // a program longer than the data
    e->setModRoutes(bad.data(), static_cast<int>(bad.size()));
    check(e->modRouteCount() == 0, "a link whose program runs past the data is refused");
    const auto twice = join({link(3, 0, 0, 0, 0.0f, 1.0f), link(4, 0, 0, 0, 0.0f, 1.0f)});
    e->setModRoutes(twice.data(), static_cast<int>(twice.size()));
    check(e->modRouteCount() == 1, "one knob carries one link");
}

void busesRenumber() {
    auto e = std::make_unique<DspEngine>(48000, 512);
    const int b5 = e->addBus();
    const int b6 = e->addBus();
    e->addPlugin(b5, 0, static_cast<int>(SnapinType::GAIN));
    e->addPlugin(b6, 0, static_cast<int>(SnapinType::GAIN));
    const auto data = join({link(1, b5, 0, 0, -6.0f, 0.0f), link(2, b6, 0, 0, -6.0f, 0.0f)});
    e->setModRoutes(data.data(), static_cast<int>(data.size()));
    e->removeBus(b5);
    float v[modroute::MAX_ROUTES * 4];
    const int n = e->getModValues(v, modroute::MAX_ROUTES * 4);
    check(n == 4 && static_cast<int>(v[0]) == 2 && static_cast<int>(v[1]) == b5,
          "removing a bus drops its links and moves the ones above it down");
}

void lanesFollow() {
    auto primary = std::make_unique<DspEngine>(48000, 512);
    auto lane = std::make_unique<DspEngine>(48000, 512);
    for (auto* e : {primary.get(), lane.get()}) {
        e->addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
        const auto data = link(5, 0, 0, 0, -24.0f, 0.0f);
        e->setModRoutes(data.data(), static_cast<int>(data.size()));
    }
    lane->followMods(primary.get());
    int t = 0, quiet = 0;
    // The primary hears kicks; the lane hears nothing, and still moves with it.
    constexpr int n = 512;
    std::vector<float> l(n), r(n), sl(n, 0.0f), sr(n, 0.0f);
    bool together = true;
    for (int b = 0; b < 400; b++) {
        for (int i = 0; i < n; i++, t++) {
            const bool on = (t % 24000) < 2400;
            l[i] = r[i] = on ? 0.5f * std::sin(2.0f * 3.1415927f * 60.0f * t / 48000.0f) : 0.0f;
        }
        std::fill(sl.begin(), sl.end(), 0.0f);
        std::fill(sr.begin(), sr.end(), 0.0f);
        primary->process(l.data(), r.data(), n);
        lane->process(sl.data(), sr.data(), n);
        if (std::fabs(modValue(*primary, 5) - modValue(*lane, 5)) > 1e-5f) together = false;
        quiet++;
    }
    check(together && quiet > 0, "a following lane moves with the primary");
}

}  // namespace

int main() {
    followerMatchesKotlin();
    formulaMatchesKotlin();
    detectorHearsItsBand();
    engineMovesTheKnob();
    linksGoBack();
    busesRenumber();
    lanesFollow();
    if (failures) {
        std::printf("%d failed\n", failures);
        return 1;
    }
    std::printf("all passed\n");
    return 0;
}

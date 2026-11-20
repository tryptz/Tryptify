// Host test: the mixer's Binaural Room (snapins/binaural_room.h) is the
// spatial renderer's own room with a stereo pair in it, at every rate the
// engine runs at, and the engine knows how late it answers.
//
// Built with -ffast-math, as the phone builds it: nothing here leans on NaN,
// and finiteness is asked of dspAllFinite (util/finite.h).
#include <cmath>
#include <cstdio>
#include <vector>

#include "../dsp_engine.h"
#include "../snapins/binaural_room.h"
#include "../util/finite.h"

namespace {

int failures = 0;
void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

/** A song: two tones a side and some noise, about -9 dBFS. */
void song(std::vector<float>& l, std::vector<float>& r, double rate) {
    unsigned s = 7u;
    for (size_t i = 0; i < l.size(); i++) {
        const double t = static_cast<double>(i) / rate;
        s = s * 1664525u + 1013904223u;
        const float n = (static_cast<int>(s >> 8) / 8388608.0f - 1.0f) * 0.05f;
        l[i] = static_cast<float>(0.25 * std::sin(6.2831853 * 220.0 * t) + 0.1 * std::sin(6.2831853 * 3300.0 * t)) + n;
        r[i] = static_cast<float>(0.25 * std::sin(6.2831853 * 330.0 * t) + 0.1 * std::sin(6.2831853 * 5100.0 * t)) - n;
    }
}

double rms(const std::vector<float>& x, size_t from) {
    double e = 0.0;
    for (size_t i = from; i < x.size(); i++) e += static_cast<double>(x[i]) * x[i];
    return std::sqrt(e / static_cast<double>(x.size() - from));
}

void run(BinauralRoomProcessor& p, std::vector<float>& l, std::vector<float>& r, int block) {
    for (size_t at = 0; at < l.size(); at += static_cast<size_t>(block)) {
        const int n = static_cast<int>(std::min(l.size() - at, static_cast<size_t>(block)));
        p.process(l.data() + at, r.data() + at, n);
    }
}

// The renderer's room given the same pair, with no snapin in the way.
void theRendererItself() {
    std::printf("-- at 48 kHz and 30 degrees it is the renderer's room, sample for sample\n");
    using namespace tf::atmos;
    const int rate = 48000, n = 48000;
    std::vector<float> l(n), r(n);
    song(l, r, rate);

    const std::vector<SpeakerDef> layout = layout_speakers(OutputLayout::k15_1_12);
    std::vector<render::RoomSpeaker> speakers;
    int left = -1, right = -1;
    for (size_t s = 0; s < layout.size(); s++) {
        speakers.push_back({layout[s].azimuth, layout[s].elevation, is_lfe(layout[s])});
        if (layout[s].role == SpeakerRole::kL) left = static_cast<int>(s);
        if (layout[s].role == SpeakerRole::kR) right = static_cast<int>(s);
    }
    check(left >= 0 && right >= 0 && layout[left].azimuth == -30.0f && layout[right].azimuth == 30.0f,
          "a stereo bed's speakers stand at 30 degrees");
    auto room = std::make_unique<render::VirtualRoom>();
    room->configure(rate, speakers);
    room->set_bass_management(true, 80);
    room->set_params(1.0f, 0.25f);
    const int count = room->speaker_count();
    std::vector<float> feeds(static_cast<size_t>(256) * count), out(512), wantL(n), wantR(n);
    for (int at = 0; at < n; at += 256) {
        const int len = std::min(256, n - at);
        std::fill(feeds.begin(), feeds.end(), 0.0f);
        for (int i = 0; i < len; i++) {
            feeds[static_cast<size_t>(i) * count + left] = l[at + i];
            feeds[static_cast<size_t>(i) * count + right] = r[at + i];
        }
        room->render(feeds.data(), len, out.data());
        for (int i = 0; i < len; i++) { wantL[at + i] = out[2 * i]; wantR[at + i] = out[2 * i + 1]; }
    }

    BinauralRoomProcessor p;
    p.prepare(rate, 1024);
    std::vector<float> gotL = l, gotR = r;
    run(p, gotL, gotR, 1024);
    check(gotL == wantL && gotR == wantR, "the defaults (Room 25, Binaural 100, 30 degrees, 0 dB) are the renderer's");
    check(rms(gotL, 4800) > 0.01 && dspAllFinite(gotL.data(), n) && dspAllFinite(gotR.data(), n), "and it is a signal");

    // However the engine cuts the song into blocks, the same samples.
    BinauralRoomProcessor q;
    q.prepare(rate, 4096);
    std::vector<float> oddL = l, oddR = r;
    const int blocks[] = {1, 7, 333, 256, 1024, 4095, 3, 511};
    size_t at = 0;
    int b = 0;
    while (at < oddL.size()) {
        const int len = static_cast<int>(std::min(oddL.size() - at, static_cast<size_t>(blocks[b++ % 8])));
        q.process(oddL.data() + at, oddR.data() + at, len);
        at += static_cast<size_t>(len);
    }
    // The same to what the room's own test holds the room to against an
    // exact convolution (virtual_room_test's test_null: 1e-4 of the peak).
    // Its ear filters run through FFTs, and where a block ends moves their
    // rounding, about 90 dB down.
    float worst = 0.0f, peak = 0.0f;
    for (int i = 0; i < n; i++) {
        worst = std::max(worst, std::max(std::fabs(oddL[i] - wantL[i]), std::fabs(oddR[i] - wantR[i])));
        peak = std::max(peak, std::max(std::fabs(wantL[i]), std::fabs(wantR[i])));
    }
    std::printf("      in odd blocks: worst difference %.3g of a peak of %.3f\n", worst, peak);
    check(worst < 1e-4f * peak, "blocks of any length give the same sound");
}

void everyRate() {
    std::printf("-- every rate\n");
    struct Case { double rate; bool room; };
    const Case cases[] = {{8000, false}, {22050, false}, {32000, false}, {44100, true}, {48000, true},
                          {88200, true}, {96000, true}, {176400, true}, {192000, true}, {352800, false}, {384000, false}};
    double level48 = 0.0;
    for (const Case& c : cases) {
        const int n = static_cast<int>(c.rate * 1.5);
        std::vector<float> l(n), r(n);
        song(l, r, c.rate);
        const std::vector<float> inL = l, inR = r;
        BinauralRoomProcessor p;
        p.prepare(c.rate, 1024);
        char what[160];
        if (!c.room) {
            run(p, l, r, 1024);
            std::snprintf(what, sizeof what, "%6.0f Hz has no room: the signal is untouched, and no delay is claimed", c.rate);
            check(l == inL && r == inR && p.ownLatency() == 0, what);
            continue;
        }
        // The delay it claims is the delay it has: an impulse through the dry, time-aligned path.
        p.setParameter(BinauralRoomProcessor::ROOM, 0.0f);
        p.setParameter(BinauralRoomProcessor::BINAURAL, 0.0f);
        p.reset();
        std::vector<float> il(4096, 0.0f), ir(4096, 0.0f);
        il[0] = ir[0] = 1.0f;
        run(p, il, ir, 512);
        int peak = 0;
        for (int i = 1; i < 4096; i++) if (std::fabs(il[i]) > std::fabs(il[peak])) peak = i;
        std::snprintf(what, sizeof what, "%6.0f Hz: answers %d samples late, as it says", c.rate, p.ownLatency());
        check(peak == p.ownLatency() && p.ownLatency() <= LatencyLine::kMaxDelay, what);

        // The room at its defaults: a finite signal at about the level it has at 48 kHz.
        BinauralRoomProcessor q;
        q.prepare(c.rate, 1024);
        run(q, l, r, 777);
        const double level = rms(l, static_cast<size_t>(c.rate * 0.5));
        if (c.rate == 48000) level48 = level;
        std::snprintf(what, sizeof what, "%6.0f Hz: finite, %.1f dBFS", c.rate, 20.0 * std::log10(level));
        check(dspAllFinite(l.data(), n) && dspAllFinite(r.data(), n) && level > 0.02 && level < 1.0, what);
    }
    // 44.1 kHz and the halved rates play the same room: within a dB of 48 kHz.
    for (double rate : {44100.0, 96000.0, 176400.0}) {
        const int n = static_cast<int>(rate * 1.5);
        std::vector<float> l(n), r(n);
        song(l, r, rate);
        BinauralRoomProcessor p;
        p.prepare(rate, 1024);
        run(p, l, r, 1024);
        const double db = 20.0 * std::log10(rms(l, static_cast<size_t>(rate * 0.5)) / level48);
        char what[120];
        std::snprintf(what, sizeof what, "%6.0f Hz is within a dB of 48 kHz (%+.2f dB)", rate, db);
        check(std::fabs(db) < 1.0, what);
    }
}

void theKnobs() {
    std::printf("-- the knobs\n");
    int k;
    float gi, go;
    BinauralRoomProcessor::pairOnRing(0.0f, k, gi, go);
    check(k == 0 && gi == 1.0f && go == 0.0f, "0 degrees is the centre speaker alone");
    BinauralRoomProcessor::pairOnRing(30.0f, k, gi, go);
    check(k == 1 && gi == 0.0f && go == 1.0f, "30 degrees is the stereo pair's speakers alone");
    BinauralRoomProcessor::pairOnRing(45.0f, k, gi, go);
    check(k == 2 && std::fabs(gi - go) < 1e-6f && std::fabs(gi * gi + go * go - 1.0f) < 1e-6f,
          "45 degrees is shared equally by 30 and 60, at the same power");
    BinauralRoomProcessor::pairOnRing(500.0f, k, gi, go);
    check(k == BinauralRoomProcessor::kRing - 2 && gi == 0.0f && go == 1.0f, "past the ring is its last speaker");
    BinauralRoomProcessor::pairOnRing(-20.0f, k, gi, go);
    check(k == 0 && gi == 1.0f, "and under 0 is the centre");

    const int rate = 48000, n = 48000;
    std::vector<float> l(n), r(n);
    // Silence in, silence out, at every setting.
    BinauralRoomProcessor s;
    s.prepare(rate, 1024);
    s.setParameter(BinauralRoomProcessor::ROOM, 100.0f);
    s.setParameter(BinauralRoomProcessor::OUTPUT, 12.0f);
    std::vector<float> zl(n, 0.0f), zr(n, 0.0f);
    run(s, zl, zr, 1024);
    bool silent = true;
    for (int i = 0; i < n; i++) silent = silent && zl[i] == 0.0f && zr[i] == 0.0f;
    check(silent, "silence in is silence out");

    // Output is a plain gain; a setting out of range is held to it.
    song(l, r, rate);
    BinauralRoomProcessor a, b;
    a.prepare(rate, 1024);
    b.prepare(rate, 1024);
    b.setParameter(BinauralRoomProcessor::OUTPUT, 6.0f);
    b.reset();
    std::vector<float> al = l, ar = r, bl = l, br = r;
    run(a, al, ar, 1024);
    run(b, bl, br, 1024);
    const double gain = 20.0 * std::log10(rms(bl, 4800) / rms(al, 4800));
    check(std::fabs(gain - 6.0) < 0.01, "Output +6 dB is 6 dB");
    a.setParameter(BinauralRoomProcessor::ANGLE, 9000.0f);
    a.setParameter(BinauralRoomProcessor::ROOM, -5.0f);
    a.setParameter(BinauralRoomProcessor::OUTPUT, 90.0f);
    check(a.getParameter(BinauralRoomProcessor::ANGLE) == 110.0f && a.getParameter(BinauralRoomProcessor::ROOM) == 0.0f &&
          a.getParameter(BinauralRoomProcessor::OUTPUT) == 12.0f, "a setting past its range is held to it");

    // Turning the angle moves the pair without a jump: no step in the sound
    // bigger than the song's own.
    BinauralRoomProcessor m;
    m.prepare(rate, 512);
    std::vector<float> ml = l, mr = r;
    float still = 0.0f, moving = 0.0f;
    for (int at = 0; at + 512 <= n; at += 512) {
        if (at == 512 * 40) m.setParameter(BinauralRoomProcessor::ANGLE, 110.0f);
        if (at == 512 * 60) m.setParameter(BinauralRoomProcessor::ANGLE, 0.0f);
        m.process(ml.data() + at, mr.data() + at, 512);
        for (int i = std::max(1, at); i < at + 512; i++) {
            const float step = std::fabs(ml[i] - ml[i - 1]);
            if (at >= 512 * 10 && at < 512 * 40) still = std::max(still, step);
            if (at >= 512 * 40) moving = std::max(moving, step);
        }
    }
    std::printf("      largest step: %.3f at rest, %.3f while the knob turns\n", still, moving);
    check(moving < still * 2.0f + 0.02f && dspAllFinite(ml.data(), n), "turning Angle all the way round makes no click");
}

// In the engine: its delay is counted, so bypassed it is the song that much later and nothing else.
void inTheEngine() {
    std::printf("-- in the engine\n");
    const int rate = 48000, block = 512, n = block * 40;
    std::vector<float> l(n), r(n);
    song(l, r, rate);
    const std::vector<float> inL = l;
    auto engine = std::make_unique<DspEngine>(rate, block);
    engine->setBusInputEnabled(0, true);
    engine->addPlugin(0, 0, static_cast<int>(SnapinType::BINAURAL_ROOM));
    engine->setPluginBypassed(0, 0, true);
    for (int at = 0; at < n; at += block) engine->process(l.data() + at, r.data() + at, block);
    const int late = tf::atmos::render::VirtualRoom::kLatency;
    bool same = true;
    for (int i = late; i < n; i++) same = same && l[i] == inL[i - late];
    check(same, "bypassed, the song comes through 7 samples later and otherwise untouched");
}

}  // namespace

int main() {
    theRendererItself();
    everyRate();
    theKnobs();
    inTheEngine();
    std::puts(failures == 0 ? "all passed" : "FAILED");
    return failures == 0 ? 0 : 1;
}

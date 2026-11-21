// Host test: the mixer's Binaural Room (snapins/binaural_room.h) is the
// spatial renderer's own room with a stereo pair in it, at every rate the
// engine runs at, and the engine knows how late it answers. And on the
// master of a surround mix it is one room with every channel at its own
// speaker, not a room a channel pair.
//
// Built with -ffast-math, as the phone builds it: nothing here leans on NaN,
// and finiteness is asked of dspAllFinite (util/finite.h).
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "../dsp_engine.h"
#include "../multilane_engine.h"
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

// ── A surround mix ──────────────────────────────────────────────────────

// 9.1.6 as the upmix makes it and ChannelLayout reads it (FFmpeg's order):
// FL FR FC LFE BL BR FLC FRC SL SR TFL TFR TBL TBR TSL TSR.
constexpr int kWide = 16;
const int kWideFirst[] = {0, 2, 3, 4, 6, 8, 10, 12, 14};
const int kWideSecond[] = {1, -1, -1, 5, 7, 9, 11, 13, 15};
constexpr int kWideLanes = 9;
// ChannelLayout.roles(16), by channel.
const int kWideRoles[kWide] = {
    RoomRole::kL, RoomRole::kR, RoomRole::kC, RoomRole::kLfe, RoomRole::kLrs, RoomRole::kRrs,
    RoomRole::kLw, RoomRole::kRw, RoomRole::kLss, RoomRole::kRss, RoomRole::kLtf, RoomRole::kRtf,
    RoomRole::kLtr, RoomRole::kRtr, RoomRole::kLts, RoomRole::kRts};

/** Sixteen channels, each a tone of its own with a little noise: nothing cancels. */
std::vector<std::vector<float>> bedSong(int n, double rate) {
    std::vector<std::vector<float>> ch(kWide, std::vector<float>(static_cast<size_t>(n)));
    unsigned s = 99u;
    for (int c = 0; c < kWide; c++) {
        const double hz = c == 3 ? 55.0 : 180.0 + 97.0 * c;
        for (int i = 0; i < n; i++) {
            s = s * 1664525u + 1013904223u;
            const float noise = (static_cast<int>(s >> 8) / 8388608.0f - 1.0f) * 0.01f;
            ch[c][static_cast<size_t>(i)] = static_cast<float>(0.08 * std::sin(6.2831853 * hz * i / rate)) + noise;
        }
    }
    return ch;
}

/** The speaker [role] plays from when it has one of its own, as (azimuth, elevation); false if shared. */
bool ownSpeaker(int role, float& az, float& el) {
    int at[BinauralRoomProcessor::kMaxTaps];
    float g[BinauralRoomProcessor::kMaxTaps];
    if (BinauralRoomProcessor::roleSpeakers(role, at, g) != 1 || g[0] != 1.0f) return false;
    const auto layout = tf::atmos::layout_speakers(tf::atmos::OutputLayout::k15_1_12);
    az = layout[static_cast<size_t>(at[0])].azimuth;
    el = layout[static_cast<size_t>(at[0])].elevation;
    return true;
}

void whereEachChannelStands() {
    std::printf("-- a surround mix: where each channel stands\n");
    const auto layout = tf::atmos::layout_speakers(tf::atmos::OutputLayout::k15_1_12);
    struct Want { int role; float az, el; const char* what; };
    const Want own[] = {
        {RoomRole::kL, -30, 0, "front left at 30 degrees"}, {RoomRole::kR, 30, 0, "front right"},
        {RoomRole::kC, 0, 0, "the centre straight ahead"},
        {RoomRole::kLw, -60, 0, "a front wide at 60"}, {RoomRole::kRw, 60, 0, "and its right"},
        {RoomRole::kLss, -90, 0, "a side surround at 90"}, {RoomRole::kRss, 90, 0, "and its right"},
        {RoomRole::kLs, -110, 0, "a 5.1 surround at 110"}, {RoomRole::kRs, 110, 0, "and its right"},
        {RoomRole::kLrs, -135, 0, "a rear at 135, behind"}, {RoomRole::kRrs, 135, 0, "and its right"},
        {RoomRole::kLtf, -45, 60, "top front overhead, ahead"}, {RoomRole::kRtf, 45, 60, "and its right"},
        {RoomRole::kLtr, -135, 60, "top rear overhead, behind"}, {RoomRole::kRtr, 135, 60, "and its right"},
    };
    for (const Want& w : own) {
        float az = 0.0f, el = 0.0f;
        check(ownSpeaker(w.role, az, el) && az == w.az && el == w.el, w.what);
    }
    int at[BinauralRoomProcessor::kMaxTaps];
    float g[BinauralRoomProcessor::kMaxTaps];
    int n = BinauralRoomProcessor::roleSpeakers(RoomRole::kLfe, at, g);
    check(n == 1 && g[0] == 1.0f && tf::atmos::is_lfe(layout[static_cast<size_t>(at[0])]), "the LFE at the sub");
    // Top side has no speaker of its own in the room: the two overhead on
    // its side share it, at the power of one.
    n = BinauralRoomProcessor::roleSpeakers(RoomRole::kLts, at, g);
    bool shared = n == 2;
    float power = 0.0f;
    for (int t = 0; t < n; t++) {
        const auto& d = layout[static_cast<size_t>(at[t])];
        shared = shared && d.azimuth < 0.0f && d.elevation == 60.0f && std::fabs(g[t] - 0.70710678f) < 1e-4f;
        power += g[t] * g[t];
    }
    check(shared && std::fabs(power - 1.0f) < 1e-4f, "top side between top front and top rear, on its own side");
    n = BinauralRoomProcessor::roleSpeakers(RoomRole::kBackCentre, at, g);
    bool behind = n == 2;
    for (int t = 0; t < n; t++) {
        const auto& d = layout[static_cast<size_t>(at[t])];
        behind = behind && std::fabs(d.azimuth) == 160.0f && d.elevation == 0.0f;
    }
    check(behind && at[0] != at[1], "a back centre between the two speakers behind");
    bool all = true;
    for (int role = 0; role < RoomRole::kCount; role++) {
        n = BinauralRoomProcessor::roleSpeakers(role, at, g);
        power = 0.0f;
        for (int t = 0; t < n; t++) power += g[t] * g[t];
        all = all && n >= 1 && std::fabs(power - 1.0f) < 1e-4f;
    }
    check(all, "every role plays, at the power of one speaker");
    check(BinauralRoomProcessor::roleSpeakers(-1, at, g) == 0 && BinauralRoomProcessor::roleSpeakers(RoomRole::kCount, at, g) == 0,
          "and a channel with no role plays from nowhere");
}

/** The whole bed through one processor: the front pair in place, the rest handed over. */
void runBed(BinauralRoomProcessor& p, std::vector<std::vector<float>>& ch, int block) {
    const int n = static_cast<int>(ch[0].size());
    for (int at = 0; at < n; at += block) {
        const int len = std::min(block, n - at);
        RoomBedChannel bed[kWide];
        int width = 0;
        for (int k = 1; k < kWideLanes; k++) {
            const int a = kWideFirst[k], b = kWideSecond[k];
            if (b >= 0) {
                bed[width++] = {ch[a].data() + at, nullptr, kWideRoles[a]};
                bed[width++] = {ch[b].data() + at, nullptr, kWideRoles[b]};
            } else {
                // A mono lane travels as two equal halves.
                bed[width++] = {ch[a].data() + at, ch[a].data() + at, kWideRoles[a]};
            }
        }
        p.setBed(bed, width);
        p.process(ch[0].data() + at, ch[1].data() + at, len);
    }
}

void theBedInTheRoom() {
    std::printf("-- a surround mix: one room, every channel from its own speaker\n");
    using namespace tf::atmos;
    const int rate = 48000, n = 24000;
    const std::vector<std::vector<float>> in = bedSong(n, rate);

    // The renderer's room itself, fed each channel at the speakers above.
    const std::vector<SpeakerDef> layout = layout_speakers(OutputLayout::k15_1_12);
    std::vector<render::RoomSpeaker> speakers;
    for (const SpeakerDef& d : layout) speakers.push_back({d.azimuth, d.elevation, is_lfe(d)});
    auto room = std::make_unique<render::VirtualRoom>();
    room->configure(rate, speakers);
    room->set_bass_management(true, 80);
    room->set_params(1.0f, 0.25f);
    const int count = room->speaker_count();
    std::vector<float> feeds(static_cast<size_t>(256) * count), out(512), wantL(n), wantR(n);
    for (int at = 0; at < n; at += 256) {
        const int len = std::min(256, n - at);
        std::fill(feeds.begin(), feeds.end(), 0.0f);
        // The front pair first, then the lanes in order: as the effect adds them.
        const int order[kWide] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        for (int c : order) {
            int sp[BinauralRoomProcessor::kMaxTaps];
            float g[BinauralRoomProcessor::kMaxTaps];
            const int taps = BinauralRoomProcessor::roleSpeakers(kWideRoles[c], sp, g);
            for (int t = 0; t < taps; t++) {
                for (int i = 0; i < len; i++) {
                    feeds[static_cast<size_t>(i) * count + sp[t]] += in[c][static_cast<size_t>(at + i)] * g[t];
                }
            }
        }
        room->render(feeds.data(), len, out.data());
        for (int i = 0; i < len; i++) { wantL[at + i] = out[2 * i]; wantR[at + i] = out[2 * i + 1]; }
    }

    BinauralRoomProcessor p;
    p.prepare(rate, 1024);
    std::vector<std::vector<float>> got = in;
    runBed(p, got, 1024);
    check(got[0] == wantL && got[1] == wantR, "it is the renderer's room with the sixteen channels at their speakers, sample for sample");
    bool untouched = true;
    for (int c = 2; c < kWide; c++) untouched = untouched && got[c] == in[c];
    check(untouched, "the channels handed over are read, never written");

    // The stereo sound is in there too: without the bed, the pair alone.
    BinauralRoomProcessor alone, quiet;
    alone.prepare(rate, 1024);
    quiet.prepare(rate, 1024);
    std::vector<float> al = in[0], ar = in[1], ql = in[0], qr = in[1];
    run(alone, al, ar, 1024);
    const std::vector<float> silence(1024, 0.0f);
    for (int at = 0; at < n; at += 1024) {
        const int len = std::min(1024, n - at);
        const RoomBedChannel bed[2] = {{silence.data(), nullptr, RoomRole::kLrs}, {silence.data(), silence.data(), RoomRole::kC}};
        quiet.setBed(bed, 2);
        quiet.process(ql.data() + at, qr.data() + at, len);
    }
    check(al == ql && ar == qr, "a bed of silence is the stereo pair's room and nothing else");
    check(al != got[0], "and the bed is heard: with it the sound is another");

    // A bed is for one block: the next, given none, has none.
    BinauralRoomProcessor once;
    once.prepare(rate, 1024);
    std::vector<std::vector<float>> first = in;
    for (auto& c : first) c.resize(1024);
    runBed(once, first, 1024);
    std::vector<float> zl(48000, 0.0f), zr(48000, 0.0f);
    run(once, zl, zr, 1024);
    check(rms(zl, 43200) < 1e-5 && rms(zr, 43200) < 1e-5, "a bed handed over once is not played again: the room rings out to silence");
}

// The bed's own way through the rate change, against the pair's: a pair at
// 90 degrees stands on the side surrounds alone, so the same two signals
// handed over as a bed's side surrounds are the same sound.
void theBedAtEveryRate() {
    std::printf("-- a surround mix: the bed at every rate\n");
    for (double rate : {44100.0, 48000.0, 96000.0, 192000.0}) {
        const int n = static_cast<int>(rate * 0.4);
        std::vector<float> l(static_cast<size_t>(n)), r(static_cast<size_t>(n));
        song(l, r, rate);
        BinauralRoomProcessor pair, bed, odd;
        for (BinauralRoomProcessor* p : {&pair, &bed, &odd}) p->prepare(rate, 4096);
        pair.setParameter(BinauralRoomProcessor::ANGLE, 90.0f);
        pair.reset();
        std::vector<float> pl = l, pr = r;
        run(pair, pl, pr, 1024);

        std::vector<float> bl(static_cast<size_t>(n), 0.0f), br(static_cast<size_t>(n), 0.0f);
        for (int at = 0; at < n; at += 1024) {
            const int len = std::min(1024, n - at);
            const RoomBedChannel ch[2] = {{l.data() + at, nullptr, RoomRole::kLss}, {r.data() + at, nullptr, RoomRole::kRss}};
            bed.setBed(ch, 2);
            bed.process(bl.data() + at, br.data() + at, len);
        }
        char what[160];
        std::snprintf(what, sizeof what, "%6.0f Hz: handed over as side surrounds, the pair's sound at 90 degrees, sample for sample", rate);
        check(bl == pl && br == pr, what);

        // In blocks of any length, a mono lane's two halves among them.
        std::vector<float> ol(static_cast<size_t>(n), 0.0f), orr(static_cast<size_t>(n), 0.0f);
        const int blocks[] = {1, 7, 333, 256, 1024, 4095, 3, 511, 2};
        int at = 0, b = 0;
        while (at < n) {
            const int len = std::min(n - at, blocks[b++ % 9]);
            const RoomBedChannel ch[2] = {{l.data() + at, l.data() + at, RoomRole::kLss}, {r.data() + at, nullptr, RoomRole::kRss}};
            odd.setBed(ch, 2);
            odd.process(ol.data() + at, orr.data() + at, len);
            at += len;
        }
        // Each run is within 1e-4 of the peak of an exact convolution (the
        // room's FFTs round by where a block ends, see theRendererItself),
        // so two runs cut differently are within twice that of each other.
        float worst = 0.0f, peak = 0.0f;
        for (int i = 0; i < n; i++) {
            worst = std::max(worst, std::max(std::fabs(ol[i] - pl[i]), std::fabs(orr[i] - pr[i])));
            peak = std::max(peak, std::max(std::fabs(pl[i]), std::fabs(pr[i])));
        }
        std::snprintf(what, sizeof what, "%6.0f Hz: and in blocks of any length (worst %.2g of a peak of %.3f)", rate, worst, peak);
        check(peak > 0.05f && worst < 2e-4f * peak, what);
    }
}

/** Sixteen planar channels a block at a time through [lanes]; returns them all. */
std::vector<std::vector<float>> throughLanes(MultiLaneEngine& lanes, const std::vector<std::vector<float>>& in, int block,
                                             bool* tookBed = nullptr) {
    std::vector<std::vector<float>> out = in;
    const int n = static_cast<int>(in[0].size());
    bool took = true;
    for (int at = 0; at < n; at += block) {
        float* ptr[kWide];
        for (int c = 0; c < kWide; c++) ptr[c] = out[c].data() + at;
        lanes.processPlanar(ptr, kWide, std::min(block, n - at));
        took = took && lanes.roomTookBed();
    }
    if (tookBed) *tookBed = took;
    return out;
}

void wideLanes(MultiLaneEngine& lanes, bool withRoles) {
    int ra[kWideLanes], rb[kWideLanes];
    for (int k = 0; k < kWideLanes; k++) {
        ra[k] = kWideRoles[kWideFirst[k]];
        rb[k] = kWideSecond[k] >= 0 ? kWideRoles[kWideSecond[k]] : -1;
    }
    if (withRoles) lanes.configureLanes(kWideFirst, kWideSecond, kWideLanes, ra, rb);
    else lanes.configureLanes(kWideFirst, kWideSecond, kWideLanes);
}

bool silent(const std::vector<float>& x) {
    for (float v : x) if (v != 0.0f) return false;
    return true;
}

void onTheMasterOfASurroundMix() {
    std::printf("-- a surround mix: the room on the mixer's master\n");
    const int rate = 48000, block = 512, n = block * 40;
    const std::vector<std::vector<float>> in = bedSong(n, rate);
    const int room = static_cast<int>(SnapinType::BINAURAL_ROOM);
    const int late = tf::atmos::render::VirtualRoom::kLatency;

    // What one room makes of the sixteen channels.
    BinauralRoomProcessor p;
    p.prepare(rate, block);
    std::vector<std::vector<float>> want = in;
    runBed(p, want, block);

    MultiLaneEngine lanes(rate, block);
    lanes.forEach([&](DspEngine& e) { e.addPlugin(MASTER_BUS, 0, room); });
    wideLanes(lanes, true);
    bool took = false;
    const std::vector<std::vector<float>> got = throughLanes(lanes, in, block, &took);
    check(took, "the mixer says the room took the stream");
    check(got[0] == want[0] && got[1] == want[1], "the front two channels are that one room's binaural pair, sample for sample");
    bool rest = true;
    for (int c = 2; c < kWide; c++) rest = rest && silent(got[c]);
    check(rest, "and every other channel, the centre and the LFE too, is silence: nothing is left to be added twice");

    // The one before: a room a lane, each pair at the front, the centre and
    // the LFE through dry. What it is no longer.
    MultiLaneEngine before(rate, block);
    before.forEach([&](DspEngine& e) { e.addPlugin(MASTER_BUS, 0, room); });
    wideLanes(before, false);
    took = true;
    const std::vector<std::vector<float>> old = throughLanes(before, in, block, &took);
    check(!took && !silent(old[4]) && !silent(old[2]), "without the channels' speakers named, every lane keeps a room of its own, as before");

    // While a control change fans out the mixer cannot wait for it: that
    // block must be the same room, not a room a lane.
    {
        MultiLaneEngine busy(rate, block);
        busy.forEach([&](DspEngine& e) { e.addPlugin(MASTER_BUS, 0, room); });
        wideLanes(busy, true);
        std::atomic<bool> holding{false}, release{false};
        std::thread ui([&] {
            bool first = true;
            busy.forEach([&](DspEngine&) {
                if (!first) return;
                first = false;
                holding.store(true);
                while (!release.load()) std::this_thread::yield();
            });
        });
        while (!holding.load()) std::this_thread::yield();
        const std::vector<std::vector<float>> during = throughLanes(busy, in, block, &took);
        release.store(true);
        ui.join();
        bool same = took && during[0] == want[0] && during[1] == want[1];
        for (int c = 2; c < kWide; c++) same = same && silent(during[c]);
        check(same, "with a control change in flight it is still the one room");
    }

    // The slot's mix knob: half the room, and half of each channel where it
    // was, as late as the room.
    {
        MultiLaneEngine half(rate, block);
        half.forEach([&](DspEngine& e) {
            e.addPlugin(MASTER_BUS, 0, room);
            e.setPluginDryWet(MASTER_BUS, 0, 0.5f);
        });
        wideLanes(half, true);
        const std::vector<std::vector<float>> mixed = throughLanes(half, in, block, &took);
        bool dry = took;
        for (int c = 2; c < kWide; c++) {
            for (int i = late; i < n; i++) dry = dry && mixed[c][i] == in[c][i - late] * 0.5f;
        }
        check(dry, "at 50% mix the other channels keep half of themselves, 7 samples later");
        bool blend = true;
        for (int i = late; i < n; i++) {
            blend = blend && std::fabs(mixed[0][i] - (in[0][i - late] * 0.5f + want[0][i] * 0.5f)) < 1e-6f;
        }
        check(blend, "and the front pair is half itself and half the whole room");
    }

    // Bypassed, there is no room: every channel through, as late as the room.
    {
        MultiLaneEngine off(rate, block);
        off.forEach([&](DspEngine& e) {
            e.addPlugin(MASTER_BUS, 0, room);
            e.setPluginBypassed(MASTER_BUS, 0, true);
        });
        wideLanes(off, true);
        const std::vector<std::vector<float>> through = throughLanes(off, in, block, &took);
        bool same = !took;
        for (int c = 0; c < kWide; c++) {
            for (int i = late; i < n; i++) same = same && through[c][i] == in[c][i - late];
        }
        check(same, "bypassed, all sixteen channels come through untouched, 7 samples later");
    }

    // A stereo stream is the effect it always was.
    {
        MultiLaneEngine stereo(rate, block);
        stereo.forEach([&](DspEngine& e) { e.addPlugin(MASTER_BUS, 0, room); });
        BinauralRoomProcessor q;
        q.prepare(rate, block);
        std::vector<float> wl = in[0], wr = in[1], gl = in[0], gr = in[1];
        run(q, wl, wr, block);
        for (int at = 0; at < n; at += block) {
            float* ptr[2] = {gl.data() + at, gr.data() + at};
            stereo.processPlanar(ptr, 2, block);
        }
        check(gl == wl && gr == wr && !stereo.roomTookBed(), "a stereo stream is the pair's room, as it was");
    }

    // 2x asked of it is 1x run: the bed is at the stream's rate.
    {
        MultiLaneEngine twice(rate, block);
        twice.forEach([&](DspEngine& e) {
            e.addPlugin(MASTER_BUS, 0, room);
            e.setPluginOversampling(MASTER_BUS, 0, 2);
        });
        wideLanes(twice, true);
        const std::vector<std::vector<float>> os = throughLanes(twice, in, block, &took);
        check(took && os[0] == want[0] && os[1] == want[1], "asked to oversample, it runs as it is: the same pair");
    }
}

// ── My room ─────────────────────────────────────────────────────────────

bool waitForRoom(const BinauralRoomProcessor& p) {
    for (int i = 0; i < 6000 && !p.roomSettled(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    return p.roomSettled();
}

/** An impulse through [p], [n] samples of the left ear's answer. */
std::vector<float> answer(BinauralRoomProcessor& p, int n) {
    std::vector<float> l(static_cast<size_t>(n), 0.0f), r(static_cast<size_t>(n), 0.0f);
    l[0] = r[0] = 1.0f;
    run(p, l, r, 1024);
    return l;
}

/** The energy of [x] from [from] seconds on, at [rate]. */
double energyAfter(const std::vector<float>& x, double from, double rate) {
    double e = 0.0;
    for (size_t i = static_cast<size_t>(from * rate); i < x.size(); i++) e += static_cast<double>(x[i]) * x[i];
    return e;
}

void myRoom() {
    std::printf("-- My room: a size and a damping\n");
    using P = BinauralRoomProcessor;
    const int rate = 48000, n = 96000;

    P builtIn;
    builtIn.prepare(rate, 1024);
    builtIn.setParameter(P::ROOM, 60.0f);
    builtIn.reset();
    check(builtIn.roomSettled(), "off, nothing is worked out");
    const std::vector<float> small = answer(builtIn, n);

    // The sizes alone change nothing while it is off.
    P off;
    off.prepare(rate, 1024);
    off.setParameter(P::ROOM, 60.0f);
    off.setParameter(P::WIDTH, 20.0f);
    off.setParameter(P::DEPTH, 28.0f);
    off.setParameter(P::HEIGHT, 9.0f);
    off.setParameter(P::DAMPING, 10.0f);
    off.reset();
    check(waitForRoom(off) && answer(off, n) == small, "its knobs do nothing until it is on");

    // A hall: it rings long after the built-in room has gone quiet.
    P hall;
    hall.prepare(rate, 1024);
    hall.setParameter(P::ROOM, 60.0f);
    hall.setParameter(P::WIDTH, 20.0f);
    hall.setParameter(P::DEPTH, 28.0f);
    hall.setParameter(P::HEIGHT, 9.0f);
    hall.setParameter(P::DAMPING, 10.0f);
    hall.setParameter(P::MY_ROOM, 1.0f);
    hall.reset();
    check(waitForRoom(hall), "on, the room is worked out off the audio thread and handed over");
    const std::vector<float> big = answer(hall, n);
    const double late = energyAfter(big, 0.8, rate), lateSmall = energyAfter(small, 0.8, rate);
    std::printf("      after 0.8 s: the hall %.3g, the built-in room %.3g\n", late, lateSmall);
    check(dspAllFinite(big.data(), n) && late > 1e-6 && late > 1000.0 * (lateSmall + 1e-12),
          "a large bare room rings long after the built-in one is silent");

    // The same hall, damped: it dies sooner.
    P damped;
    damped.prepare(rate, 1024);
    damped.setParameter(P::ROOM, 60.0f);
    damped.setParameter(P::WIDTH, 20.0f);
    damped.setParameter(P::DEPTH, 28.0f);
    damped.setParameter(P::HEIGHT, 9.0f);
    damped.setParameter(P::DAMPING, 70.0f);
    damped.setParameter(P::MY_ROOM, 1.0f);
    damped.reset();
    waitForRoom(damped);
    const std::vector<float> dead = answer(damped, n);
    check(energyAfter(dead, 0.8, rate) < 0.01 * late, "Damping turned up, the same room dies away sooner");

    // A booth: nothing left after a quarter of a second.
    P booth;
    booth.prepare(rate, 1024);
    booth.setParameter(P::ROOM, 60.0f);
    booth.setParameter(P::WIDTH, 2.0f);
    booth.setParameter(P::DEPTH, 2.4f);
    booth.setParameter(P::HEIGHT, 2.3f);
    booth.setParameter(P::DAMPING, 55.0f);
    booth.setParameter(P::MY_ROOM, 1.0f);
    booth.reset();
    waitForRoom(booth);
    const std::vector<float> tight = answer(booth, n);
    check(energyAfter(tight, 0.05, rate) > 0.0 && energyAfter(tight, 0.4, rate) < 1e-4 * energyAfter(tight, 0.0, rate),
          "a small damped room is a short one");

    // Room at 0 is no room, whichever it is; and the direct sound is the same.
    P none;
    none.prepare(rate, 1024);
    none.setParameter(P::ROOM, 0.0f);
    none.setParameter(P::WIDTH, 20.0f);
    none.setParameter(P::MY_ROOM, 1.0f);
    none.reset();
    waitForRoom(none);
    P plain;
    plain.prepare(rate, 1024);
    plain.setParameter(P::ROOM, 0.0f);
    plain.reset();
    check(answer(none, 24000) == answer(plain, 24000), "with Room at 0 there is none of it, as with the built-in room");

    // Off again is the built-in room again, exactly.
    hall.setParameter(P::MY_ROOM, 0.0f);
    check(waitForRoom(hall), "switched off, the built-in room is handed back");
    hall.reset();
    check(answer(hall, n) == small, "and it is the built-in room, sample for sample");

    // The same room at 44.1 kHz lasts as long, not 9% longer.
    {
        P cd;
        cd.prepare(44100.0, 1024);
        cd.setParameter(P::ROOM, 60.0f);
        cd.setParameter(P::WIDTH, 20.0f);
        cd.setParameter(P::DEPTH, 28.0f);
        cd.setParameter(P::HEIGHT, 9.0f);
        cd.setParameter(P::DAMPING, 10.0f);
        cd.setParameter(P::MY_ROOM, 1.0f);
        cd.reset();
        waitForRoom(cd);
        const std::vector<float> at441 = answer(cd, 88200);
        // Where the tail's energy is centred, in seconds.
        auto centre = [](const std::vector<float>& x, double r) {
            double e = 0.0, m = 0.0;
            for (size_t i = static_cast<size_t>(0.1 * r); i < x.size(); i++) {
                const double v = static_cast<double>(x[i]) * x[i];
                e += v;
                m += v * static_cast<double>(i) / r;
            }
            return e > 0.0 ? m / e : 0.0;
        };
        const double a = centre(big, 48000.0), b = centre(at441, 44100.0);
        std::printf("      the tail's centre: %.3f s at 48 kHz, %.3f s at 44.1 kHz\n", a, b);
        check(a > 0.2 && std::fabs(a - b) < 0.03 * a, "at 44.1 kHz the room lasts as long as at 48");
    }

    // A rate change builds a new room: My room is put into it too.
    hall.setParameter(P::MY_ROOM, 1.0f);
    waitForRoom(hall);
    hall.prepare(96000.0, 1024);
    check(waitForRoom(hall), "after a rate change the room is worked out again");
    std::vector<float> hi = answer(hall, 192000);
    check(energyAfter(hi, 0.8, 96000.0) > 1e-6, "and it is My room that plays, not the built-in one");

    // Knobs turned fast, and an effect deleted with its room still being
    // worked out: nothing waits, nothing is used after it is gone.
    for (int round = 0; round < 3; round++) {
        auto gone = std::make_unique<P>();
        gone->prepare(rate, 1024);
        gone->setParameter(P::MY_ROOM, 1.0f);
        for (int i = 0; i < 40; i++) gone->setParameter(P::WIDTH, 2.0f + 0.7f * static_cast<float>(i));
        std::vector<float> l(512, 0.1f), r(512, 0.1f);
        gone->process(l.data(), r.data(), 512);
    }
    P after;
    after.prepare(rate, 1024);
    after.setParameter(P::MY_ROOM, 1.0f);
    check(waitForRoom(after), "an effect removed mid-work leaves the builder working for the others");

    check(P::kMinSide == 2.0f && P::kMaxSide == 30.0f && P::kMinHeight == 2.0f && P::kMaxHeight == 12.0f &&
          P::kMinDamping == 5.0f && P::kMaxDamping == 70.0f, "its limits are My room's on the renderer's page");
    P held;
    held.prepare(rate, 1024);
    held.setParameter(P::WIDTH, 500.0f);
    held.setParameter(P::HEIGHT, 0.0f);
    held.setParameter(P::DAMPING, 100.0f);
    check(held.getParameter(P::WIDTH) == 30.0f && held.getParameter(P::HEIGHT) == 2.0f && held.getParameter(P::DAMPING) == 70.0f,
          "a size past its range is held to it");
}

}  // namespace

int main() {
    theRendererItself();
    everyRate();
    theKnobs();
    inTheEngine();
    whereEachChannelStands();
    theBedInTheRoom();
    theBedAtEveryRate();
    onTheMasterOfASurroundMix();
    myRoom();
    std::puts(failures == 0 ? "all passed" : "FAILED");
    return failures == 0 ? 0 : 1;
}

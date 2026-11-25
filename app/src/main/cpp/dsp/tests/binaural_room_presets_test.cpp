// Host test: the Binaural Room's ten presets, played through the effect.
//
// The presets are Kotlin's (FxPresets); this reads them from
// binaural_room_presets.csv, which FxPresetsTest holds to that list, and
// measures what each one does:
//
// - They are level with each other on a song (EBU R128, integrated), so
//   stepping through them compares rooms and not volume, and none peaks
//   more than half a dB over the first.
// - The real rooms ring for as long as the rooms they are named for: T30 of
//   what the effect plays (the output with the room, less the output with
//   Room at 0), against EBU Tech 3276 for a listening room, the Dolby and
//   THX guideline for a cinema of that size, Adelman-Larsen's rock and pop
//   halls, and Beranek's concert halls.
// - The space enhancers do what they are named for: a sound on one side is
//   further to that side with the pair at 60 and 90 degrees, and the Deep
//   Stage has the most room of the ten.
// - And why every preset stands its pair on one of the room's speakers: an
//   angle between two of them is louder.
//
//   binaural_room_presets_test <binaural_room_presets.csv>
//
// Built with -ffast-math, as the phone builds it.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "../meter/loudness_meter.h"
#include "../snapins/binaural_room.h"
#include "../util/finite.h"

namespace {

const int kRate = 48000;
int failures = 0;
void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

struct Preset {
    std::string name;
    float v[9];
};

std::vector<Preset> read(const char* path) {
    std::vector<Preset> out;
    FILE* f = std::fopen(path, "r");
    if (!f) return out;
    char line[512];
    while (std::fgets(line, sizeof line, f)) {
        if (line[0] == '#' || std::strlen(line) < 3) continue;
        char name[64];
        Preset p;
        if (std::sscanf(line, "%63[^,],%f,%f,%f,%f,%f,%f,%f,%f,%f", name, &p.v[0], &p.v[1], &p.v[2], &p.v[3],
                        &p.v[4], &p.v[5], &p.v[6], &p.v[7], &p.v[8]) != 10) {
            continue;
        }
        p.name = name;
        out.push_back(p);
    }
    std::fclose(f);
    return out;
}

// The effect with a preset's knobs, its room worked out.
void set(BinauralRoomProcessor& p, const float* v, float room) {
    p.prepare(kRate, 4096);
    for (int i = 0; i < 9; i++) p.setParameter(i, i == 0 ? room : v[i]);
    for (int w = 0; w < 3000 && !p.roomSettled(); w++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    p.reset();
}

void play(const float* v, float room, std::vector<float>& l, std::vector<float>& r) {
    BinauralRoomProcessor p;
    set(p, v, room);
    const int n = static_cast<int>(l.size());
    for (int at = 0; at < n; at += 1024) {
        const int k = std::min(1024, n - at);
        p.process(l.data() + at, r.data() + at, k);
    }
}

// A song: a shared middle and two sides 8 dB under it, pink, clipped 3 dB
// into its peaks and brought to full scale, as a loud master is.
struct Pink {
    unsigned s;
    double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    explicit Pink(unsigned seed) : s(seed) {}
    double next() {
        s = s * 1664525u + 1013904223u;
        const double w = static_cast<int>(s >> 8) / 8388608.0 - 1.0;
        b0 = 0.99886 * b0 + w * 0.0555179;
        b1 = 0.99332 * b1 + w * 0.0750759;
        b2 = 0.96900 * b2 + w * 0.1538520;
        b3 = 0.86650 * b3 + w * 0.3104856;
        b4 = 0.55000 * b4 + w * 0.5329522;
        b5 = -0.7616 * b5 - w * 0.0168980;
        const double p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
        b6 = w * 0.115926;
        return p * 0.11;
    }
};

void song(std::vector<float>& l, std::vector<float>& r) {
    Pink m(1), a(2), b(3);
    double peak = 0.0;
    for (size_t i = 0; i < l.size(); i++) {
        const double mid = m.next();
        l[i] = static_cast<float>(mid + 0.4 * a.next());
        r[i] = static_cast<float>(mid + 0.4 * b.next());
        peak = std::max(peak, static_cast<double>(std::max(std::fabs(l[i]), std::fabs(r[i]))));
    }
    const double clip = 0.7 * peak;
    for (size_t i = 0; i < l.size(); i++) {
        l[i] = static_cast<float>(std::max(-clip, std::min(clip, static_cast<double>(l[i]))) / clip);
        r[i] = static_cast<float>(std::max(-clip, std::min(clip, static_cast<double>(r[i]))) / clip);
    }
}

double lufs(const std::vector<float>& l, const std::vector<float>& r) {
    tryptify::LoudnessMeter meter;
    meter.configure(kRate, 2);
    meter.setTruePeak(false);
    std::vector<float> both(2 * 4096);
    for (size_t at = 0; at < l.size(); at += 4096) {
        const size_t n = std::min<size_t>(4096, l.size() - at);
        for (size_t i = 0; i < n; i++) {
            both[2 * i] = l[at + i];
            both[2 * i + 1] = r[at + i];
        }
        meter.process(both.data(), static_cast<int>(n));
    }
    return meter.reading().integrated;
}

double peakDb(const std::vector<float>& l, const std::vector<float>& r) {
    double peak = 0.0;
    for (size_t i = 0; i < l.size(); i++) peak = std::max(peak, static_cast<double>(std::max(std::fabs(l[i]), std::fabs(r[i]))));
    return 20.0 * std::log10(peak);
}

// The room's own share of a preset, for an impulse on both channels: how
// loud against the direct sound (dB) and how long it rings (T30, seconds).
void room(const float* v, double& level, double& t30) {
    const int n = kRate * 4;
    std::vector<float> l(n, 0.0f), r(n, 0.0f), dl(n, 0.0f), dr(n, 0.0f);
    l[100] = r[100] = dl[100] = dr[100] = 1.0f;
    play(v, v[0], l, r);
    play(v, 0.0f, dl, dr);
    double direct = 0.0;
    std::vector<double> left(static_cast<size_t>(n) + 1, 0.0);
    for (int i = n - 1; i >= 0; i--) {
        const double a = l[i] - dl[i], b = r[i] - dr[i];
        left[i] = left[i + 1] + a * a + b * b;
        direct += static_cast<double>(dl[i]) * dl[i] + static_cast<double>(dr[i]) * dr[i];
    }
    level = 10.0 * std::log10(left[0] / direct + 1e-30);
    // Schroeder: from 5 to 35 dB down of the energy still to come, doubled.
    int t5 = -1, t35 = -1;
    for (int i = 0; i < n; i++) {
        const double db = 10.0 * std::log10(left[i] / left[0] + 1e-30);
        if (t5 < 0 && db <= -5.0) t5 = i;
        if (db <= -35.0) { t35 = i; break; }
    }
    t30 = (t5 >= 0 && t35 >= 0) ? 2.0 * (t35 - t5) / kRate : 0.0;
}

// A sound on the left alone: how much louder it is in the left ear above
// 1.5 kHz (dB), over the first 4 ms, which are the ear filters themselves.
double sidedness(const float* v) {
    const int n = kRate;
    std::vector<float> l(n, 0.0f), r(n, 0.0f);
    l[100] = 1.0f;
    play(v, v[0], l, r);
    double peak = 0.0;
    for (int i = 0; i < n; i++) peak = std::max(peak, static_cast<double>(std::max(std::fabs(l[i]), std::fabs(r[i]))));
    int on = 0;
    while (on < n && std::max(std::fabs(l[on]), std::fabs(r[on])) < peak * 1e-3) on++;
    const double a = std::exp(-6.2831853 * 1500.0 / kRate);
    double hl = 0, hr = 0, pl = 0, pr = 0, el = 0, er = 0;
    for (int i = on; i < on + kRate * 4 / 1000 && i < n; i++) {
        hl = a * (hl + l[i] - pl); pl = l[i];
        hr = a * (hr + r[i] - pr); pr = r[i];
        el += hl * hl;
        er += hr * hr;
    }
    return 10.0 * std::log10(el / (er + 1e-30));
}

const Preset* find(const std::vector<Preset>& all, const char* name) {
    for (const Preset& p : all) if (p.name == name) return &p;
    return nullptr;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("usage: binaural_room_presets_test <binaural_room_presets.csv>\n");
        return 2;
    }
    const std::vector<Preset> all = read(argv[1]);
    char what[240];
    std::snprintf(what, sizeof what, "the table has the ten presets (%zu read)", all.size());
    check(all.size() == 10, what);
    if (all.empty()) return 1;

    std::printf("-- level with each other on a song, and none peaks more than half a dB over the first\n");
    const int n = kRate * 12;
    std::vector<float> inL(n), inR(n);
    song(inL, inR);
    const double in = lufs(inL, inR);
    double first = 0.0, firstPeak = 0.0;
    for (size_t k = 0; k < all.size(); k++) {
        std::vector<float> l = inL, r = inR;
        play(all[k].v, all[k].v[0], l, r);
        const bool finite = dspAllFinite(l.data(), n) && dspAllFinite(r.data(), n);
        const double level = lufs(l, r) - in, peak = peakDb(l, r);
        if (k == 0) { first = level; firstPeak = peak; }
        std::snprintf(what, sizeof what, "%-16s %+.2f LU against the song, %+.2f against %s; peak %+.2f dB",
                      all[k].name.c_str(), level, level - first, all[0].name.c_str(), peak);
        check(finite && std::fabs(level - first) <= 0.5 && level < 0.0 && peak <= firstPeak + 0.5, what);
    }

    std::printf("-- the real rooms ring as long as the rooms they are named for\n");
    struct Want { const char* name; double low, high; const char* source; };
    const Want rooms[] = {
        {"Control Room", 0.20, 0.40, "EBU Tech 3276: 0.2 to 0.4 s"},
        {"Living Room", 0.40, 0.50, "a furnished room, about 0.45 s"},
        {"Cinema", 0.40, 0.50, "Dolby and THX: 0.45 s at 920 m3"},
        {"Club", 0.60, 0.70, "rock and pop halls: 0.6 to 1.2 s from 1000 m3"},
        {"Concert Hall", 1.80, 2.20, "Beranek: 1.8 to 2.2 s, full"},
        {"Deep Stage", 0.80, 0.95, "a live room"},
    };
    double deepest = -1000.0;
    std::string deepestName;
    for (const Preset& p : all) {
        double level = 0.0, t30 = 0.0;
        room(p.v, level, t30);
        if (level > deepest) { deepest = level; deepestName = p.name; }
        for (const Want& w : rooms) {
            if (p.name != w.name) continue;
            std::snprintf(what, sizeof what, "%-16s rings %.2f s (%s), its room %.1f dB under the direct sound",
                          w.name, t30, w.source, -level);
            check(t30 >= w.low && t30 <= w.high, what);
        }
        if (p.name == "Studio Monitors") {
            std::snprintf(what, sizeof what, "Studio Monitors' room is barely heard: %.1f dB under the direct sound", -level);
            check(level < -24.0, what);
        }
    }
    for (const Want& w : rooms) {
        std::snprintf(what, sizeof what, "%s is in the table", w.name);
        if (!find(all, w.name)) check(false, what);
    }
    std::snprintf(what, sizeof what, "the most room of the ten is the Deep Stage's (%s, %.1f dB under)", deepestName.c_str(), -deepest);
    check(deepestName == "Deep Stage", what);

    std::printf("-- the wide ones put a sound further to its side\n");
    const Preset* studio = find(all, "Studio Monitors");
    const Preset* wide = find(all, "Wide Stage");
    const Preset* wrap = find(all, "Wrap Around");
    const Preset* cinema = find(all, "Cinema");
    if (studio && wide && wrap && cinema) {
        const double s = sidedness(studio->v), w = sidedness(wide->v), a = sidedness(wrap->v), c = sidedness(cinema->v);
        std::snprintf(what, sizeof what,
                      "a left sound is louder in the left ear by %.1f dB at 30 degrees, %.1f at 60 (Wide Stage), %.1f at 90 (Wrap Around)",
                      s, w, a);
        check(w > s + 5.0 && a > s + 5.0, what);
        std::snprintf(what, sizeof what, "and by %.1f dB from the back of a cinema, at 15 degrees: a narrower screen", c);
        check(c < s - 2.0, what);
    } else {
        check(false, "Studio Monitors, Wide Stage, Wrap Around and Cinema are in the table");
    }

    std::printf("-- an angle between two of the room's speakers is louder: the presets stand on them\n");
    if (studio) {
        Preset between = *studio;
        between.v[2] = 22.0f;
        std::vector<float> l = inL, r = inR, bl = inL, br = inR;
        play(studio->v, studio->v[0], l, r);
        play(between.v, between.v[0], bl, br);
        const double more = lufs(bl, br) - lufs(l, r);
        std::snprintf(what, sizeof what, "22 degrees is %.1f LU louder than 30", more);
        check(more > 2.0, what);
    }

    if (failures) {
        std::printf("%d failed\n", failures);
        return 1;
    }
    std::printf("binaural_room_presets_test: all held\n");
    return 0;
}

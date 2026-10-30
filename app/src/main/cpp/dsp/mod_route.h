#pragma once
#include "biquad.h"
#include "finite.h"
#include <algorithm>
#include <cmath>

// A mixer knob following the music ("Move with the music"), on the audio
// thread.
//
// The arithmetic is MusicLinkFollower's and LinkFormula's (Kotlin), step for
// step, so a knob and a visual setting linked the same way move the same way:
// an adaptive floor and ceiling the band's level is measured between, attack and release,
// a smoothing low-pass, the curve, the way, the formula, and the result always
// inside lowest..highest. MusicLinkFollowerTest pins the Kotlin side;
// mod_route_test.cpp pins this one against the same numbers.
//
// Where the visual links sum their band's bins in a spectrum of their own
// (LinkBandAnalysis), a knob measures its own band with filters: a high-pass
// at the band's lower edge and a low-pass at its upper one, over the mono sum,
// as block RMS in dBFS; both read a band's RMS in dBFS. No FFT, no allocation, no lock: a
// route is plain data the engine updates under the chain lock it already
// holds while it processes.
namespace modroute {

constexpr int MAX_ROUTES = 64;
// LinkFormula.MAX_STEPS op/argument pairs.
constexpr int MAX_PROGRAM = 128;
constexpr int MAX_STACK = 16;

constexpr float RANGE_DB = 12.0f;
constexpr float MIN_RANGE_DB = 4.0f;
constexpr float FLOOR_FALL_SEC = 0.3f;
constexpr float FLOOR_RISE_SEC = 6.0f;
constexpr float CEILING_RISE_SEC = 0.02f;
constexpr float CEILING_FALL_SEC = 4.0f;
constexpr float RESTART_AFTER_SEC = 0.25f;
constexpr float SETTLE_SEC = 1.0f;
constexpr float AVERAGE_SEC = 1.5f;
constexpr float BOTH_GAIN = 2.6f;
constexpr float SILENT_PAUSE_DB = -70.0f;
constexpr float SILENT_PAUSE_SEC = 2.0f;
// The longest step the follower takes at once. A long block is measured in
// pieces of this many frames, so its timing does not depend on the block.
constexpr int DETECT_PIECE = 1024;

enum Way : int { UP = 0, DOWN = 1, BOTH = 2 };
enum Curve : int { EVEN = 0, GENTLE = 1, STRONG = 2 };
// What the knob listens to: the song as it enters the mixer, or what reaches
// the bus the knob is on (its input, before its effects).
enum Source : int { SONG = 0, BUS = 1 };

// LinkFormula's ops.
enum Op : int {
    OP_CONST = 0, OP_VALUE, OP_BAND, OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_NEG,
    OP_MIN, OP_MAX, OP_ABS, OP_CLAMP,
};

/** How a link moves, as Kotlin hands it over (KnobLinkCodec.toNative). */
struct Shape {
    float loHz = 30.0f;
    float hiHz = 150.0f;
    float lowest = 0.0f;
    float highest = 1.0f;
    float attackMs = 8.2f;
    float releaseMs = 131.0f;
    float quietDb = -60.0f;
    float boostDb = 0.0f;
    // The smoothing low-pass's cutoff; 0 = off.
    float smoothHz = 10.0f;
    int way = UP;
    int curve = EVEN;
    int source = SONG;
    bool pauseWhenSilent = true;
    int programLen = 0;
    float program[MAX_PROGRAM] = {};
};

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

/** LinkFormula.evaluate: false when the program cannot give a finite number. */
inline bool evaluate(const float* program, int len, float value, float band, float& out) {
    float stack[MAX_STACK];
    int sp = 0;
    for (int i = 0; i + 1 < len; i += 2) {
        const int op = static_cast<int>(program[i]);
        const float arg = program[i + 1];
        switch (op) {
            case OP_CONST: if (sp >= MAX_STACK) return false; stack[sp++] = arg; break;
            case OP_VALUE: if (sp >= MAX_STACK) return false; stack[sp++] = value; break;
            case OP_BAND: if (sp >= MAX_STACK) return false; stack[sp++] = band; break;
            case OP_NEG: if (sp < 1) return false; stack[sp - 1] = -stack[sp - 1]; break;
            case OP_ABS: if (sp < 1) return false; stack[sp - 1] = std::fabs(stack[sp - 1]); break;
            case OP_ADD: case OP_SUB: case OP_MUL: case OP_DIV: case OP_MIN: case OP_MAX: {
                if (sp < 2) return false;
                const float b = stack[--sp];
                const float a = stack[sp - 1];
                float r;
                switch (op) {
                    case OP_ADD: r = a + b; break;
                    case OP_SUB: r = a - b; break;
                    case OP_MUL: r = a * b; break;
                    case OP_DIV: r = b == 0.0f ? 0.0f : a / b; break;
                    case OP_MIN: r = std::min(a, b); break;
                    default: r = std::max(a, b); break;
                }
                stack[sp - 1] = r;
                break;
            }
            case OP_CLAMP: {
                if (sp < 3) return false;
                const float hi = stack[--sp];
                const float lo = stack[--sp];
                stack[sp - 1] = clampf(stack[sp - 1], std::min(lo, hi), std::max(lo, hi));
                break;
            }
            default: return false;
        }
    }
    if (sp != 1 || !dspIsFinite(stack[0])) return false;
    out = stack[0];
    return true;
}

inline float curved(float c, int curve) {
    const float x = clampf(c, 0.0f, 1.0f);
    switch (curve) {
        case GENTLE: return x * x;
        case STRONG: return std::sqrt(x);
        default: return x;
    }
}

/** MusicLinkFollower.valueFor. */
inline float valueFor(float strength, float average, const Shape& s) {
    const float lo = s.lowest, hi = s.highest;
    const float c = curved(strength, s.curve);
    float v;
    switch (s.way) {
        case DOWN: v = hi - (hi - lo) * c; break;
        case BOTH: {
            const float swing = clampf((strength - average) * BOTH_GAIN, -1.0f, 1.0f);
            v = (lo + hi) * 0.5f + (hi - lo) * 0.5f * swing;
            break;
        }
        default: v = lo + (hi - lo) * c; break;
    }
    float f;
    if (s.programLen > 0 && evaluate(s.program, s.programLen, v, c, f)) v = f;
    return clampf(v, std::min(lo, hi), std::max(lo, hi));
}

/** MusicLinkFollower: one band level in, the knob's value out. */
struct Follower {
    float floor = 0.0f;
    float ceiling = 0.0f;
    bool floorInit = false;
    float quietFor = 0.0f;
    float sinceStart = 0.0f;
    float env = 0.0f;
    float smooth = 0.0f;
    float avg = 0.3f;
    float silentFor = 0.0f;
    float strength = 0.0f;

    bool asleep(const Shape& s) const { return s.pauseWhenSilent && silentFor >= SILENT_PAUSE_SEC; }

    float step(float bandDb, float dt, const Shape& s) {
        const float dtc = dspIsFinite(dt) ? clampf(dt, 0.0005f, 0.1f) : 0.016f;
        const float db = (dspIsFinite(bandDb) ? bandDb : -120.0f) + s.boostDb;
        silentFor = db < SILENT_PAUSE_DB ? silentFor + dtc : 0.0f;
        // As MusicLinkFollower: silence moves neither the floor nor the
        // ceiling, both start again after a pause, and the band's own swing
        // (MIN_RANGE_DB..RANGE_DB) is the full range.
        float raw;
        if (db < s.quietDb) {
            quietFor += dtc;
            raw = 0.0f;
        } else {
            if (!floorInit || quietFor >= RESTART_AFTER_SEC) {
                floor = db - MIN_RANGE_DB;
                ceiling = db;
                floorInit = true;
                sinceStart = 0.0f;
            }
            quietFor = 0.0f;
            sinceStart += dtc;
            const float floorTau = (db < floor || sinceStart < SETTLE_SEC) ? FLOOR_FALL_SEC : FLOOR_RISE_SEC;
            floor += (db - floor) * (1.0f - std::exp(-dtc / floorTau));
            const float ceilingTau = db > ceiling ? CEILING_RISE_SEC : CEILING_FALL_SEC;
            ceiling += (db - ceiling) * (1.0f - std::exp(-dtc / ceilingTau));
            const float range = clampf(ceiling - floor, MIN_RANGE_DB, RANGE_DB);
            raw = clampf((db - floor) / range, 0.0f, 1.0f);
        }
        const float tau = std::max(0.0005f, (raw > env ? s.attackMs : s.releaseMs) / 1000.0f);
        env += (raw - env) * (1.0f - std::exp(-dtc / tau));
        const float fc = s.smoothHz;
        smooth = fc <= 0.0f ? env : smooth + (env - smooth) * (1.0f - std::exp(-2.0f * 3.1415927f * fc * dtc));
        strength = clampf(smooth, 0.0f, 1.0f);
        avg += (strength - avg) * (1.0f - std::exp(-dtc / AVERAGE_SEC));
        return valueFor(strength, avg, s);
    }
};

/** A band's level: high-pass at its lower edge, low-pass at its upper, block RMS in dBFS. */
struct Detector {
    Biquad hp, lp;
    float loHz = 0.0f, hiHz = 0.0f;
    double rate = 0.0;

    void design(float lo, float hi, double sampleRate) {
        if (lo == loHz && hi == hiHz && sampleRate == rate) return;
        loHz = lo;
        hiHz = hi;
        rate = sampleRate;
        // Butterworth, from the cookbook directly: Biquad::configure keeps a
        // knob's frequency above a thousandth of the rate, which would lift
        // the kick band's 30 Hz edge to 48 Hz at 48 kHz.
        butterworth(hp, true, lo, sampleRate);
        butterworth(lp, false, hi, sampleRate);
        hp.reset();
        lp.reset();
    }

    static void butterworth(Biquad& f, bool highPass, double freq, double sampleRate) {
        if (!(sampleRate > 0.0)) return;
        const double fc = std::max(5.0, std::min(0.45 * sampleRate, freq));
        const double w = 2.0 * M_PI * fc / sampleRate;
        const double cw = std::cos(w);
        const double alpha = std::sin(w) / (2.0 * 0.70710678118654752);
        const double a0 = 1.0 + alpha;
        const double b1 = highPass ? -(1.0 + cw) : (1.0 - cw);
        const double b0 = highPass ? (1.0 + cw) / 2.0 : (1.0 - cw) / 2.0;
        f.setCoefficients(static_cast<float>(b0 / a0), static_cast<float>(b1 / a0), static_cast<float>(b0 / a0),
                          static_cast<float>(-2.0 * cw / a0), static_cast<float>((1.0 - alpha) / a0));
    }

    float blockDb(const float* l, const float* r, int n) {
        if (n <= 0) return -120.0f;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            const float y = lp.process(hp.process(0.5f * (l[i] + r[i])));
            sum += static_cast<double>(y) * y;
        }
        const double ms = sum / n;
        // A filter that has gone non-finite measures silence and starts again.
        if (!(ms >= 0.0) || !dspIsFinite(static_cast<float>(ms))) {
            hp.reset();
            lp.reset();
            return -120.0f;
        }
        return static_cast<float>(10.0 * std::log10(ms + 1e-12));
    }
};

/** One link, as the engine keeps it. Plain data: copied whole under the chain lock. */
struct Route {
    int id = -1;
    int bus = -1;
    int slot = -1;
    int param = -1;
    bool paused = false;
    Shape shape;
    // The knob's own value: what is saved, shown, and put back when the link
    // pauses or goes.
    float base = 0.0f;
    // The value last handed to the effect. A flag rather than NaN for "none
    // yet": the library is built with -ffast-math, where a NaN test can fold
    // away.
    float applied = 0.0f;
    bool hasApplied = false;
    // The primary lane's update count this lane last took (see DspEngine::followMods).
    unsigned leaderTick = 0;
    Detector detector;
    Follower follower;
};

/** Where slot [slot] goes when a chain moves [from] to [to] (std::vector erase + insert). */
inline int slotAfterMove(int slot, int from, int to) {
    if (slot == from) return to;
    if (from < to && slot > from && slot <= to) return slot - 1;
    if (from > to && slot >= to && slot < from) return slot + 1;
    return slot;
}

}  // namespace modroute

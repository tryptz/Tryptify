// The spectrum as a receding waterfall: every so often the current spectrum is
// laid down as a line, and older lines fall back in perspective, rise toward
// the top of the box and fade out — the ridgeline look of an SDR sweep.
//
// Header-only and free of Android so the host tests can build it. The JNI
// wrapper (spectrum_waterfall_jni.cpp) owns one of these per overlay; it is
// only ever touched from the UI thread, so nothing here is atomic.
//
// The cost is fixed by kRows and kPoints, never by the settings: depth changes
// how much time the rows span, not how many there are, so a long tail costs
// exactly what a short one does.

#pragma once

#include <cmath>
#include <cstring>

namespace tryptify {

class SpectrumWaterfall {
public:
    /** History lines kept, not counting the live one at the front. */
    static constexpr int kRows = 48;
    /** Points per line: the analyzer's 256 bins, peak-reduced in pairs. */
    static constexpr int kPoints = 128;
    /** Floats one line writes: (kPoints − 1) segments of x0 y0 x1 y1. */
    static constexpr int kFloatsPerRow = (kPoints - 1) * 4;
    /** Floats of per-row metadata: alpha, stroke scale, baseline y. */
    static constexpr int kMetaPerRow = 3;

    static constexpr float kMinAngle = 5.f, kMaxAngle = 75.f;
    static constexpr float kMinDepth = 0.5f, kMaxDepth = 8.f;
    static constexpr float kMaxFadeStart = 0.95f;

    struct Params {
        float width = 0.f, height = 0.f;
        /** Seconds a line takes to travel from the front to fully faded. */
        float depthSeconds = 2.5f;
        /** Fraction of that trip a line keeps full strength, 0 … 0.95. */
        float fadeStart = 0.3f;
        /** How steeply the lines rise toward the back, in degrees. */
        float angleDeg = 45.f;
        /** dB at the baseline and at the top of a front line. */
        float floorDb = -24.f, headroomDb = 36.f;
    };

    /**
     * Lays down a new line if one is due, then writes every visible line back
     * to front — oldest first, so the live front line is drawn last and on top.
     * Returns the number of lines written; line i's segments start at
     * segs[i · kFloatsPerRow] and its metadata at meta[i · kMetaPerRow].
     */
    int render(const float* bins, int nBins, double nowSec, Params p, float* segs, float* meta) {
        p = clamp(p);
        float live[kPoints];
        reduce(bins, nBins, p, live);

        const double period = p.depthSeconds / kRows;
        const double sincePrev = nowSec - prevNow_;
        if (count_ == 0 || nowSec < lastPush_ || nowSec - lastPush_ > p.depthSeconds) {
            // First frame, the clock went backwards, or a stall (the overlay
            // slept through a pause): one line, not a burst — the old lines
            // have aged out with the clock anyway.
            pushRow(live, nowSec);
            lastPush_ = nowSec;
        } else {
            // On an exact cadence, not "whenever a frame lands": rounding each
            // push up to the next vsync stretched a 2 s waterfall to 2.4 s and
            // left its oldest lines past the end. A short depth can owe more
            // than one line per frame; those are laid down between the last
            // frame's spectrum and this one's, so they stay evenly spaced in
            // both time and shape instead of stacking as copies.
            while (nowSec - lastPush_ >= period) {
                lastPush_ += period;
                const float k = sincePrev > 1e-9
                    ? float((lastPush_ - prevNow_) / sincePrev)
                    : 1.f;
                float row[kPoints];
                for (int j = 0; j < kPoints; ++j) row[j] = prevLive_[j] + (live[j] - prevLive_[j]) * k;
                pushRow(row, lastPush_);
            }
        }
        std::memcpy(prevLive_, live, sizeof(live));
        prevNow_ = nowSec;

        int written = 0;
        for (int i = count_; i >= 1; --i) {  // oldest first
            const int slot = (head_ - i + kRows) % kRows;
            const float t = float((nowSec - stamps_[slot]) / p.depthSeconds);
            if (t >= 1.f || t < 0.f) continue;
            emit(rows_[slot], t, p, segs + written * kFloatsPerRow, meta + written * kMetaPerRow);
            ++written;
        }
        emit(live, 0.f, p, segs + written * kFloatsPerRow, meta + written * kMetaPerRow);
        return written + 1;
    }

    // ── The projection, public so the preview's guides use the same numbers ──

    /** How much a line at depth [t] (0 front … 1 back) is scaled toward the centre. */
    static float scaleAt(float t) { return 1.f / (1.f + kPerspective * t); }

    /** The baseline of a line at depth [t], in pixels from the top of the box. */
    static float baselineAt(float t, const Params& raw) {
        const Params p = clamp(raw);
        const float back = scaleAt(1.f);
        const float d = (1.f - scaleAt(t)) / (1.f - back);  // perspective spacing
        return p.height * kBottom - d * rise(p) * p.height;
    }

    /** A line's strength at depth [t]: full until fadeStart, then easing to nothing. */
    static float alphaAt(float t, float fadeStart) {
        if (fadeStart < 0.f) fadeStart = 0.f;
        if (fadeStart > kMaxFadeStart) fadeStart = kMaxFadeStart;
        if (t <= fadeStart) return 1.f;
        if (t >= 1.f) return 0.f;
        const float u = 1.f - (t - fadeStart) / (1.f - fadeStart);
        return u * std::sqrt(u);  // u^1.5: lingers, then goes
    }

    /** Peak height of a full-scale front line, in pixels. */
    static float amplitude(const Params& raw) {
        const Params p = clamp(raw);
        const float r = rise(p);
        // The back line's peaks have to stay inside the box too: its baseline
        // is r·h above the front's and it is scaled by scaleAt(1).
        const float roomAtBack = (kBottom - r) / scaleAt(1.f);
        float a = kBottom * 0.94f;
        if (roomAtBack * 0.96f < a) a = roomAtBack * 0.96f;
        // Seen from higher up, a bump stands up less: the vertical component of
        // a height viewed at elevation θ shrinks with cos θ.
        const float rad = p.angleDeg * float(M_PI) / 180.f;
        return a * (0.35f + 0.65f * std::cos(rad)) * p.height;
    }

    static Params clamp(Params p) {
        if (!(p.angleDeg >= kMinAngle)) p.angleDeg = kMinAngle;
        if (p.angleDeg > kMaxAngle) p.angleDeg = kMaxAngle;
        if (!(p.depthSeconds >= kMinDepth)) p.depthSeconds = kMinDepth;
        if (p.depthSeconds > kMaxDepth) p.depthSeconds = kMaxDepth;
        if (!(p.fadeStart >= 0.f)) p.fadeStart = 0.f;
        if (p.fadeStart > kMaxFadeStart) p.fadeStart = kMaxFadeStart;
        if (!(p.headroomDb > p.floorDb + 1.f)) p.headroomDb = p.floorDb + 1.f;
        if (!(p.width > 0.f)) p.width = 0.f;
        if (!(p.height > 0.f)) p.height = 0.f;
        return p;
    }

private:
    static constexpr float kPerspective = 1.25f;
    /** The front baseline sits just inside the bottom edge, so a stroke is not cut. */
    static constexpr float kBottom = 0.985f;

    /** How far up the box the back line's baseline climbs, as a fraction. */
    static float rise(const Params& p) {
        return 0.62f * std::sin(p.angleDeg * float(M_PI) / 180.f);
    }

    /** dB bins → kPoints levels in 0 … 1, keeping the larger of each pair. */
    static void reduce(const float* bins, int nBins, const Params& p, float* out) {
        const float span = p.headroomDb - p.floorDb;
        for (int j = 0; j < kPoints; ++j) {
            float level = 0.f;
            if (nBins > 0) {
                const int a = j * nBins / kPoints;
                int b = (j + 1) * nBins / kPoints;
                if (b <= a) b = a + 1;
                float db = bins[a < nBins ? a : nBins - 1];
                for (int k = a + 1; k < b && k < nBins; ++k) if (bins[k] > db) db = bins[k];
                level = (db - p.floorDb) / span;
            }
            out[j] = level < 0.f ? 0.f : (level > 1.f ? 1.f : level);
        }
    }

    static void emit(const float* levels, float t, const Params& p, float* segs, float* meta) {
        const float s = scaleAt(t);
        const float base = baselineAt(t, p);
        const float amp = amplitude(p) * s;
        const float cx = p.width * 0.5f;
        const float w = p.width * s;
        float px = 0.f, py = 0.f;
        int n = 0;
        for (int j = 0; j < kPoints; ++j) {
            const float x = cx + (float(j) / float(kPoints - 1) - 0.5f) * w;
            const float y = base - levels[j] * amp;
            if (j > 0) { segs[n++] = px; segs[n++] = py; segs[n++] = x; segs[n++] = y; }
            px = x; py = y;
        }
        meta[0] = alphaAt(t, p.fadeStart);
        meta[1] = s;
        // Where the line stands, for the ridgeline style's fill under it.
        meta[2] = base;
    }

    void pushRow(const float* row, double stamp) {
        std::memcpy(rows_[head_], row, sizeof(float) * kPoints);
        stamps_[head_] = stamp;
        head_ = (head_ + 1) % kRows;
        if (count_ < kRows) ++count_;
    }

    float rows_[kRows][kPoints] = {};
    double stamps_[kRows] = {};
    int head_ = 0;
    int count_ = 0;
    double lastPush_ = 0.0;
    float prevLive_[kPoints] = {};
    double prevNow_ = 0.0;
};

}  // namespace tryptify

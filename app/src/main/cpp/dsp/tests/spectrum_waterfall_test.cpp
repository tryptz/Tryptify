// SpectrumWaterfall's geometry: nothing drawn outside the box at any angle,
// the front line full-width, fading only past the fade start, the line count
// bounded whatever the depth, and the angle doing what it says.

#include <cmath>
#include <cstdio>
#include <vector>

#include "scope/spectrum_waterfall.h"

using tryptify::SpectrumWaterfall;

static int failures = 0;

static void expectTrue(const char* what, bool ok) {
    if (!ok) { std::printf("FAIL %s\n", what); ++failures; }
}

struct Frame {
    int lines = 0;
    std::vector<float> segs = std::vector<float>(size_t(SpectrumWaterfall::kRows + 1) * SpectrumWaterfall::kFloatsPerRow);
    std::vector<float> meta = std::vector<float>(size_t(SpectrumWaterfall::kRows + 1) * SpectrumWaterfall::kMetaPerRow);
};

// Runs [seconds] at 60 fps with every bin at [db] and returns the last frame.
static Frame run(SpectrumWaterfall& w, SpectrumWaterfall::Params p, double seconds, float db,
                 double start = 0.0) {
    std::vector<float> bins(256, db);
    Frame f;
    for (double t = start; t <= start + seconds; t += 1.0 / 60.0) {
        f.lines = w.render(bins.data(), int(bins.size()), t, p, f.segs.data(), f.meta.data());
    }
    return f;
}

int main() {
    // Inside the box: full-scale and floor-level lines, every angle and depth.
    for (float angle = 5.f; angle <= 75.f; angle += 5.f) {
        for (float depth : {0.5f, 2.5f, 8.f}) {
            for (float db : {-24.f, 6.f, 36.f, 80.f}) {
                SpectrumWaterfall w;
                SpectrumWaterfall::Params p;
                p.width = 1000.f; p.height = 400.f;
                p.angleDeg = angle; p.depthSeconds = depth; p.fadeStart = 0.3f;
                const Frame f = run(w, p, depth * 1.5, db);
                bool inside = true;
                for (int i = 0; i < f.lines * SpectrumWaterfall::kFloatsPerRow; i += 2) {
                    const float x = f.segs[i], y = f.segs[i + 1];
                    if (x < -0.01f || x > p.width + 0.01f || y < -0.01f || y > p.height + 0.01f) inside = false;
                }
                char label[96];
                std::snprintf(label, sizeof label, "inside the box at %.0f°, %.1f s, %.0f dB", angle, depth, db);
                expectTrue(label, inside);
            }
        }
    }

    SpectrumWaterfall::Params p;
    p.width = 1000.f; p.height = 400.f; p.angleDeg = 45.f; p.depthSeconds = 2.f; p.fadeStart = 0.4f;

    // Steady state: the history is full but never more than kRows + the live line.
    {
        SpectrumWaterfall w;
        const Frame f = run(w, p, 10.0, 0.f);
        expectTrue("line count bounded", f.lines <= SpectrumWaterfall::kRows + 1);
        expectTrue("history fills", f.lines >= SpectrumWaterfall::kRows - 2);

        // The live line is last, full width and full strength.
        const float* front = &f.segs[size_t(f.lines - 1) * SpectrumWaterfall::kFloatsPerRow];
        expectTrue("front line starts at the left edge", std::fabs(front[0]) < 0.01f);
        expectTrue("front line ends at the right edge",
                   std::fabs(front[SpectrumWaterfall::kFloatsPerRow - 2] - p.width) < 0.01f);
        constexpr int M = SpectrumWaterfall::kMetaPerRow;
        expectTrue("front line at full strength", f.meta[size_t(f.lines - 1) * M] == 1.f);

        // Back to front, alpha never drops, lines never get narrower and the
        // baseline only comes down.
        bool alphaRises = true, widthGrows = true, baseFalls = true;
        for (int i = 1; i < f.lines; ++i) {
            if (f.meta[i * M] + 1e-6f < f.meta[(i - 1) * M]) alphaRises = false;
            if (f.meta[i * M + 1] + 1e-6f < f.meta[(i - 1) * M + 1]) widthGrows = false;
            if (f.meta[i * M + 2] + 1e-3f < f.meta[(i - 1) * M + 2]) baseFalls = false;
        }
        expectTrue("alpha rises toward the front", alphaRises);
        expectTrue("lines widen toward the front", widthGrows);
        expectTrue("baselines come down toward the front", baseFalls);
        // Every point of a line sits on or above its own baseline, which is
        // what lets the ridgeline fill the space under it.
        bool above = true;
        for (int i = 0; i < f.lines; ++i) {
            const float base = f.meta[i * M + 2];
            for (int k = 1; k < SpectrumWaterfall::kFloatsPerRow; k += 2) {
                if (f.segs[size_t(i) * SpectrumWaterfall::kFloatsPerRow + k] > base + 0.01f) above = false;
            }
        }
        expectTrue("each line stands on its baseline", above);
    }

    // Fade start: full strength until it, gone by the end.
    for (float fs : {0.f, 0.3f, 0.8f}) {
        bool ok = true;
        for (float t = 0.f; t <= fs; t += 0.01f) ok = ok && SpectrumWaterfall::alphaAt(t, fs) == 1.f;
        ok = ok && SpectrumWaterfall::alphaAt(1.f, fs) == 0.f;
        ok = ok && SpectrumWaterfall::alphaAt(fs + (1.f - fs) * 0.5f, fs) < 1.f;
        char label[64];
        std::snprintf(label, sizeof label, "fade begins at %.1f", fs);
        expectTrue(label, ok);
    }

    // A steeper angle lifts the back line higher (smaller y) and flattens bumps.
    {
        SpectrumWaterfall::Params low = p, high = p;
        low.angleDeg = 15.f;
        high.angleDeg = 70.f;
        expectTrue("steeper angle lifts the back line",
                   SpectrumWaterfall::baselineAt(1.f, high) < SpectrumWaterfall::baselineAt(1.f, low));
        expectTrue("front baseline does not move with angle",
                   std::fabs(SpectrumWaterfall::baselineAt(0.f, high) - SpectrumWaterfall::baselineAt(0.f, low)) < 0.01f);
        expectTrue("steeper angle flattens the bumps",
                   SpectrumWaterfall::amplitude(high) < SpectrumWaterfall::amplitude(low));
    }

    // Depth sets how long a line lives, not how many there are.
    {
        SpectrumWaterfall shortW, longW;
        SpectrumWaterfall::Params a = p, b = p;
        a.depthSeconds = 0.5f;
        b.depthSeconds = 8.f;
        const Frame fa = run(shortW, a, 4.0, 0.f);
        const Frame fb = run(longW, b, 20.0, 0.f);
        expectTrue("short and long depth draw the same number of lines",
                   std::abs(fa.lines - fb.lines) <= 2);
    }

    // A stall (the overlay slept through a pause) neither bursts nor breaks.
    {
        SpectrumWaterfall w;
        run(w, p, 3.0, 0.f);
        const Frame after = run(w, p, 0.0, 0.f, 600.0);
        expectTrue("after a long stall only the newest lines remain", after.lines <= 3);
    }

    // Out-of-range settings are clamped rather than drawn.
    {
        SpectrumWaterfall w;
        SpectrumWaterfall::Params bad = p;
        bad.angleDeg = 400.f; bad.depthSeconds = -3.f; bad.fadeStart = 7.f; bad.headroomDb = bad.floorDb;
        const Frame f = run(w, bad, 2.0, 50.f);
        bool inside = true;
        for (int i = 0; i < f.lines * SpectrumWaterfall::kFloatsPerRow; i += 2) {
            if (f.segs[i + 1] < -0.01f || f.segs[i + 1] > bad.height + 0.01f) inside = false;
        }
        expectTrue("nonsense settings still draw inside the box", inside && f.lines > 0);
    }

    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("all waterfall tests passed\n");
    return 0;
}

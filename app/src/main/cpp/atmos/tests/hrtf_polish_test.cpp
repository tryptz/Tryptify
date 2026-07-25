// Host test for the renderer polish pass: the ITD-aligned dry path and the
// Linkwitz-Riley 4th-order bass-management crossover.
//
//   c++ -std=c++17 -I.. hrtf_polish_test.cpp -o polish && ./polish
//
// 1) Dry-path alignment: at binaural strength 0 the output is the dry band
//    read through the SAME per-ear fractional ITD delays as the wet image.
//    For a hard-right source the left ear must therefore lag the right ear by
//    the interaural time difference — the pre-fix dry path had zero interaural
//    delay, which is what comb-filtered every wet/dry blend.
// 2) LR4 flatness: LP4 + HP4 must sum allpass-flat. Rendered front-center at
//    strength 0 (so the HRIR's own colouration is out of the path), the level
//    of a tone with bass management ON must match bass management OFF across
//    the crossover region.
#include <cmath>
#include <cstdio>
#include <vector>

#include "render/hrir_renderer.h"

namespace {
int g_fail = 0, g_checks = 0;
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK(c) check((c), #c, __LINE__)

using tf::atmos::render::BinauralRenderer;
constexpr int kFrame = 1536;
constexpr float kPi = 3.14159265358979f;

void test_dry_path_carries_itd() {
  std::printf("BinauralRenderer: strength 0 dry path carries the per-ear ITD\n");
  BinauralRenderer r;
  r.configure(48000, 2);
  r.set_params(0.0f, true);  // pure dry band

  // An impulse from hard right; find each ear's arrival peak.
  std::vector<float> src(kFrame, 0.0f), out(2 * kFrame);
  src[200] = 1.0f;
  const float az = kPi / 2.0f, el = 0.0f;
  const float* obj[1] = {src.data()};
  r.render(obj, &az, &el, 1, nullptr, nullptr, nullptr, 0, kFrame, out.data());

  int peak_l = 0, peak_r = 0;
  float max_l = 0.0f, max_r = 0.0f;
  for (int i = 0; i < kFrame; ++i) {
    const float al = std::fabs(out[2 * i]), ar = std::fabs(out[2 * i + 1]);
    if (al > max_l) { max_l = al; peak_l = i; }
    if (ar > max_r) { max_r = ar; peak_r = i; }
  }
  std::printf("  right-ear peak @%d, left-ear peak @%d (ITD %d samples)\n",
              peak_r, peak_l, peak_l - peak_r);
  CHECK(max_l > 0.1f && max_r > 0.1f);   // both ears got the impulse
  CHECK(peak_l - peak_r >= 5);           // far ear lags by a real ITD
}

void test_lr4_crossover_flat() {
  std::printf("BinauralRenderer: LR4 bass-management split sums flat\n");
  const float freqs[] = {40.0f, 80.0f, 120.0f, 240.0f, 1000.0f};
  const float az = 0.0f, el = 0.0f;
  for (float f : freqs) {
    float rms[2] = {0.0f, 0.0f};
    for (int pass = 0; pass < 2; ++pass) {  // 0 = bass mgmt off, 1 = on
      BinauralRenderer r;
      r.configure(48000, 2);
      r.set_params(0.0f, true);  // dry path only: no HRIR colouration
      r.set_bass_management(pass == 1, 80);

      const int frames = 8;
      double sum = 0.0;
      int count = 0;
      std::vector<float> src(kFrame), out(2 * kFrame);
      for (int fr = 0; fr < frames; ++fr) {
        for (int i = 0; i < kFrame; ++i) {
          const int t = fr * kFrame + i;
          src[i] = std::sin(2.0f * kPi * f * t / 48000.0f);
        }
        const float* obj[1] = {src.data()};
        r.render(obj, &az, &el, 1, nullptr, nullptr, nullptr, 0, kFrame, out.data());
        if (fr >= 2) {  // skip filter/delay warmup
          for (int i = 0; i < kFrame; ++i) { sum += out[2 * i] * out[2 * i]; ++count; }
        }
      }
      rms[pass] = static_cast<float>(std::sqrt(sum / count));
    }
    const float db = 20.0f * std::log10(rms[1] / rms[0]);
    std::printf("  %6.0f Hz: on/off level delta %+.3f dB\n", f, db);
    CHECK(std::fabs(db) < 0.2f);
  }
}

}  // namespace

int main() {
  std::printf("=== HRIR renderer polish tests ===\n");
  test_dry_path_carries_itd();
  test_lr4_crossover_flat();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

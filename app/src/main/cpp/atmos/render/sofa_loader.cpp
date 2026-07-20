// Runtime SOFA HRTF loader. Uses libmysofa to parse a user-supplied .sofa from
// memory, then reproduces tools/bake_hrir.c's onset-aligned decomposition so the
// loaded HRTF drops into the renderer with the exact same grid, tap count and
// ITD-as-delay convention as the baked MIT KEMAR default. Runs off the audio
// thread (allocates, calls libmysofa); it only touches the renderer through the
// double-buffered set_runtime_hrir, which is safe against the audio thread.
#include "sofa_loader.h"

#include <cmath>
#include <vector>

#include "hrir_renderer.h"  // BinauralRenderer + grid constants (via hrir_table.h)
#include "mysofa.h"

namespace tf {
namespace atmos {
namespace {

// Must match tools/bake_hrir.c: onset = first sample reaching this fraction of
// the ear's peak. Kept identical so a runtime SOFA aligns like the baked table.
constexpr float kOnsetFrac = 0.10f;

int find_onset(const float* h, int n) {
  float pk = 0.0f;
  for (int i = 0; i < n; ++i) { float a = std::fabs(h[i]); if (a > pk) pk = a; }
  const float thr = kOnsetFrac * pk;
  for (int i = 0; i < n; ++i) if (std::fabs(h[i]) >= thr) return i;
  return 0;
}

}  // namespace

bool load_sofa_into_renderer(render::BinauralRenderer& renderer, const char* data,
                             long size) {
  using namespace render;
  if (data == nullptr || size <= 0) return false;

  int filterlen = 0, err = 0;
  MYSOFA_EASY* easy = mysofa_open_data(data, size, 48000.0f, &filterlen, &err);
  if (easy == nullptr || filterlen <= 0) {
    if (easy) mysofa_close(easy);
    return false;
  }

  std::vector<float> irL(filterlen), irR(filterlen);
  // Query the SOFA at grid point (e,a) in the renderer's azimuth convention
  // (0 = front, +right); libmysofa azimuth increases to the LEFT, hence 360-az.
  auto query = [&](int e, int a) {
    const float lib_az = std::fmod(360.0f - a * kHrirAzStep, 360.0f);
    float c[3] = {lib_az, kHrirElMin + e * kHrirElStep, 1.0f};
    mysofa_s2c(c);
    float dL, dR;
    mysofa_getfilter_float(easy, c[0], c[1], c[2], irL.data(), irR.data(), &dL, &dR);
  };

  // Pass 1: onset per ear + global minimum (subtracted so the smallest delay is
  // 0, dropping constant latency while preserving every ITD).
  std::vector<int> onset(static_cast<size_t>(kHrirElCount) * kHrirAzCount * 2);
  int gmin = filterlen;
  for (int e = 0; e < kHrirElCount; ++e)
    for (int a = 0; a < kHrirAzCount; ++a) {
      query(e, a);
      const float* ears[2] = {irL.data(), irR.data()};
      for (int ear = 0; ear < 2; ++ear) {
        const int o = find_onset(ears[ear], filterlen);
        onset[(static_cast<size_t>(e) * kHrirAzCount + a) * 2 + ear] = o;
        if (o < gmin) gmin = o;
      }
    }

  // Pass 2: onset-aligned taps + delays, flattened [el][az][ear][tap] to match
  // the baked table layout the renderer indexes.
  std::vector<float> tab(static_cast<size_t>(kHrirElCount) * kHrirAzCount * 2 * kHrirTaps, 0.0f);
  std::vector<float> del(static_cast<size_t>(kHrirElCount) * kHrirAzCount * 2, 0.0f);
  for (int e = 0; e < kHrirElCount; ++e)
    for (int a = 0; a < kHrirAzCount; ++a) {
      query(e, a);
      const float* ears[2] = {irL.data(), irR.data()};
      for (int ear = 0; ear < 2; ++ear) {
        const size_t idx = (static_cast<size_t>(e) * kHrirAzCount + a) * 2 + ear;
        const int o = onset[idx];
        float* dst = &tab[idx * kHrirTaps];
        for (int t = 0; t < kHrirTaps; ++t) {
          const int src = o + t;
          dst[t] = src < filterlen ? ears[ear][src] : 0.0f;
        }
        del[idx] = static_cast<float>(o - gmin);
      }
    }

  mysofa_close(easy);
  renderer.set_runtime_hrir(tab, del);
  return true;
}

}  // namespace atmos
}  // namespace tf

// Runtime SOFA HRTF loader. Parses a listener's .sofa (from memory, or where
// it is on disk) with libmysofa and reproduces tools/bake_hrir.c's onset-aligned decomposition,
// so a loaded set has the grid, tap count and delay convention of the baked
// MIT KEMAR table. Allocates and calls libmysofa: loader thread.
#include "sofa_loader.h"

#include <cmath>
#include <vector>

#include "hrir_table.h"  // the grid a loaded set is laid out on
#include "sofa_set.h"
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

// What both loaders make of an open file: the table, and `count` exact
// directions when asked, all on one clock.
bool tables_of(MYSOFA_EASY* easy, int filterlen, const float* directions, int count,
               std::vector<float>& tab, std::vector<float>& del,
               std::vector<float>& sp_tab, std::vector<float>& sp_del) {
  using namespace render;
  std::vector<float> irL(filterlen), irR(filterlen);
  // Query the SOFA at (az, el) in the renderer's azimuth convention
  // (0 = front, +right); libmysofa azimuth increases to the LEFT, hence 360-az.
  auto query = [&](float az_deg, float el_deg) {
    float lib_az = std::fmod(360.0f - az_deg, 360.0f);
    if (lib_az < 0.0f) lib_az += 360.0f;
    float c[3] = {lib_az, el_deg, 1.0f};
    mysofa_s2c(c);
    float dL, dR;
    mysofa_getfilter_float(easy, c[0], c[1], c[2], irL.data(), irR.data(), &dL, &dR);
  };
  const int grid = kHrirElCount * kHrirAzCount;
  const int total = grid + count;
  auto direction = [&](int i, float& az, float& el) {
    if (i < grid) {
      az = (i % kHrirAzCount) * kHrirAzStep;
      el = kHrirElMin + (i / kHrirAzCount) * kHrirElStep;
    } else {
      az = directions[2 * (i - grid)];
      el = directions[2 * (i - grid) + 1];
    }
  };

  // Pass 1: onset per ear + global minimum (subtracted so the smallest delay is
  // 0, dropping constant latency while preserving every ITD).
  std::vector<int> onset(static_cast<size_t>(total) * 2);
  int gmin = filterlen;
  for (int i = 0; i < total; ++i) {
    float az, el;
    direction(i, az, el);
    query(az, el);
    const float* ears[2] = {irL.data(), irR.data()};
    for (int ear = 0; ear < 2; ++ear) {
      const int o = find_onset(ears[ear], filterlen);
      onset[static_cast<size_t>(i) * 2 + ear] = o;
      if (o < gmin) gmin = o;
    }
  }

  // Pass 2: onset-aligned taps + delays. The table is flattened
  // [el][az][ear][tap] to match the baked layout the renderer indexes.
  tab.assign(static_cast<size_t>(grid) * 2 * kHrirTaps, 0.0f);
  del.assign(static_cast<size_t>(grid) * 2, 0.0f);
  sp_tab.assign(static_cast<size_t>(count) * 2 * kHrirTaps, 0.0f);
  sp_del.assign(static_cast<size_t>(count) * 2, 0.0f);
  for (int i = 0; i < total; ++i) {
    float az, el;
    direction(i, az, el);
    query(az, el);
    const float* ears[2] = {irL.data(), irR.data()};
    for (int ear = 0; ear < 2; ++ear) {
      const size_t at = static_cast<size_t>(i < grid ? i : i - grid) * 2 + ear;
      const int o = onset[static_cast<size_t>(i) * 2 + ear];
      float* dst = i < grid ? &tab[at * kHrirTaps] : &sp_tab[at * kHrirTaps];
      for (int t = 0; t < kHrirTaps; ++t) {
        const int src = o + t;
        dst[t] = src < filterlen ? ears[ear][src] : 0.0f;
      }
      (i < grid ? del : sp_del)[at] = static_cast<float>(o - gmin);
    }
  }
  return true;
}

}  // namespace

bool load_sofa_tables(const char* data, long size, std::vector<float>& tab,
                      std::vector<float>& del) {
  if (data == nullptr || size <= 0) return false;
  int filterlen = 0, err = 0;
  MYSOFA_EASY* easy = mysofa_open_data(data, size, 48000.0f, &filterlen, &err);
  if (easy == nullptr || filterlen <= 0) {
    if (easy) mysofa_close(easy);
    return false;
  }
  std::vector<float> sp_tab, sp_del;
  const bool ok = tables_of(easy, filterlen, nullptr, 0, tab, del, sp_tab, sp_del);
  mysofa_close(easy);
  return ok;
}

bool load_sofa_set(const char* path, const float* directions, int count,
                   std::vector<float>& packed) {
  if (path == nullptr || count < 0 || count > render::SofaSet::kMaxSpeakers) return false;
  if (count > 0 && directions == nullptr) return false;
  int filterlen = 0, err = 0;
  MYSOFA_EASY* easy = mysofa_open(path, 48000.0f, &filterlen, &err);
  if (easy == nullptr || filterlen <= 0) {
    if (easy) mysofa_close(easy);
    return false;
  }
  std::vector<float> tab, del, sp_tab, sp_del;
  const bool ok = tables_of(easy, filterlen, directions, count, tab, del, sp_tab, sp_del);
  mysofa_close(easy);
  if (!ok) return false;
  packed = render::SofaSet::pack(tab.data(), del.data(), count, directions, sp_tab.data(), sp_del.data());
  return true;
}

}  // namespace atmos
}  // namespace tf

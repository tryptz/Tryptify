// Host test for the renderer's runtime HRIR sets (a loaded SOFA file).
//
//   c++ -std=c++17 -I.. hrtf_runtime_set_test.cpp -o runtime_set -pthread && ./runtime_set
//
// Each source keeps its IR spectra from frame to frame and works them out
// again only when it moves or the set changes. A set is copy-assigned into
// one of two slots in place, so the slot that plays can hold new taps at the
// address it had: after two loads with no render between (two SOFA files
// picked while paused), or after going back to the built-in set and loading
// another. The spectra were kept by that address, so a still source (every
// bed channel) played on with the old set's spectra, under the new set's
// ITD, until a seek. Here a set is the baked one with its taps scaled, so the
// wrong one is ten times too loud or quiet.
//
// And a load while a render runs must not write the slot that render still
// reads: the threaded case below exercises the wait, which ThreadSanitizer
// (-fsanitize=thread) checks.
#include <atomic>
#include <cmath>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

#include "render/hrir_renderer.h"
#include "check.h"

namespace {

using namespace tf::atmos::render;
constexpr int kFrame = 1536;

// A set laid out as the baked one, its taps scaled; the ITDs are the baked ones.
struct Set {
  std::vector<float> data, delay;
  explicit Set(float scale) {
    const float* d = &kHrirData[0][0][0][0];
    data.assign(d, d + kHrirElCount * kHrirAzCount * 2 * kHrirTaps);
    for (float& v : data) v *= scale;
    const float* t = &kHrirDelay[0][0][0];
    delay.assign(t, t + kHrirElCount * kHrirAzCount * 2);
  }
};

std::unique_ptr<BinauralRenderer> renderer() {
  auto r = std::make_unique<BinauralRenderer>();
  r->configure(48000, 4);
  r->set_params(1.0f, true);
  return r;
}

// One frame of a still bed channel at azimuth 0.7 (the same tone each frame).
std::vector<float> frame(BinauralRenderer& r) {
  std::vector<float> src(kFrame), out(2 * kFrame);
  for (int i = 0; i < kFrame; ++i) src[i] = std::sin(0.05f * static_cast<float>(i));
  const float* bed[1] = {src.data()};
  const float az = 0.7f, el = 0.0f;
  r.render(nullptr, nullptr, nullptr, 0, bed, &az, &el, 1, kFrame, out.data());
  return out;
}

// The last of [frames] frames.
std::vector<float> frames(BinauralRenderer& r, int count) {
  std::vector<float> out;
  for (int f = 0; f < count; ++f) out = frame(r);
  return out;
}

// The largest difference between two frames, against the peak of [want].
float off(const std::vector<float>& got, const std::vector<float>& want) {
  float peak = 0.0f, worst = 0.0f;
  for (size_t i = 0; i < want.size(); ++i) {
    peak = std::fmax(peak, std::fabs(want[i]));
    worst = std::fmax(worst, std::fabs(got[i] - want[i]));
  }
  return worst / peak;
}

// Three frames after a change: the first crossfades to the new set and the
// second still reads some of that out of the ITD ring.
constexpr int kSettled = 3;

void test_two_loads_while_paused() {
  std::printf("BinauralRenderer: two loads with no render between play the second\n");
  const Set a(1.0f), b(0.5f), c(0.1f);
  auto r = renderer();
  r->set_runtime_hrir(a.data, a.delay);  // slot 0
  r->set_runtime_hrir(a.data, a.delay);  // slot 1, playing
  frames(*r, 4);
  r->set_runtime_hrir(b.data, b.delay);  // slot 0, paused
  r->set_runtime_hrir(c.data, c.delay);  // slot 1 again, in place
  const std::vector<float> got = frames(*r, kSettled);
  auto want = renderer();
  want->set_runtime_hrir(c.data, c.delay);
  const float e = off(got, frames(*want, 4 + kSettled));
  std::printf("  off from a renderer loaded with the second: %.2e of its peak\n", e);
  CHECK(e < 1e-5f);
}

void test_load_after_going_back_to_built_in() {
  std::printf("BinauralRenderer: built-in then a load, while paused, plays the load\n");
  const Set a(1.0f), c(0.1f);
  auto r = renderer();
  r->set_runtime_hrir(a.data, a.delay);  // slot 0, playing
  frames(*r, 4);
  r->clear_runtime_hrir();
  CHECK(!r->has_runtime_hrir());
  r->set_runtime_hrir(c.data, c.delay);  // slot 0 again, in place
  CHECK(r->has_runtime_hrir());
  const std::vector<float> got = frames(*r, kSettled);
  auto want = renderer();
  want->set_runtime_hrir(c.data, c.delay);
  const float e = off(got, frames(*want, 4 + kSettled));
  std::printf("  off from a renderer loaded with it: %.2e of its peak\n", e);
  CHECK(e < 1e-5f);
}

void test_clear_plays_built_in() {
  std::printf("BinauralRenderer: going back plays the built-in set\n");
  const Set c(0.1f);
  auto r = renderer();
  r->set_runtime_hrir(c.data, c.delay);
  frames(*r, 4);
  r->clear_runtime_hrir();
  const std::vector<float> got = frames(*r, kSettled);
  auto want = renderer();
  const float e = off(got, frames(*want, 4 + kSettled));
  std::printf("  off from a renderer that never loaded one: %.2e of its peak\n", e);
  CHECK(e < 1e-5f);
}

void test_loads_while_rendering() {
  std::printf("BinauralRenderer: loads while it renders, then the last one plays\n");
  const Set a(1.0f), c(0.1f);
  auto r = renderer();
  std::atomic<bool> done{false};
  std::thread loader([&] {
    for (int k = 0; k < 200; ++k) {
      const Set& s = (k & 1) ? c : a;
      r->set_runtime_hrir(s.data, s.delay);
    }
    done.store(true);
  });
  bool finite = true;
  int rendered = 0;
  while (!done.load() || rendered < 8) {
    for (float v : frame(*r)) finite = finite && dspIsFinite(v);
    ++rendered;
  }
  loader.join();
  std::printf("  %d frames rendered during 200 loads\n", rendered);
  CHECK(finite);
  const std::vector<float> got = frames(*r, kSettled);
  auto want = renderer();
  want->set_runtime_hrir(c.data, c.delay);
  const float e = off(got, frames(*want, 4 + kSettled));
  std::printf("  then off from a renderer loaded with the last: %.2e of its peak\n", e);
  CHECK(e < 1e-5f);
}
}  // namespace

int main() {
  test_two_loads_while_paused();
  test_load_after_going_back_to_built_in();
  test_clear_plays_built_in();
  test_loads_while_rendering();
  std::printf("%d/%d checks passed\n", g_checks - g_fail, g_checks);
  return g_fail == 0 ? 0 : 1;
}

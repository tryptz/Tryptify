// Host test for the measured-HRIR binaural renderer (plan P2, SOFA path).
//
//   c++ -std=c++17 -I.. hrtf_render_test.cpp -o hrtf && ./hrtf
//
// Feeds an impulse as a single object and checks the interaural cues the baked
// MIT KEMAR table must reproduce: the near ear leads (earlier main arrival) and
// is louder for a lateral source, a center source is L/R symmetric, strength=0
// collapses to a dry mono image, and off-grid directions interpolate to finite
// output. This exercises the table + interpolation + convolution end to end.
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

using namespace tf::atmos::render;
constexpr float kHalfPi = kPi * 0.5f;

// Onset = first sample reaching 30% of that ear's own peak — the main arrival,
// robust to the small pre-onset ripple a measured HRIR carries.
int onset(const std::vector<float>& x) {
  float peak = 0.0f;
  for (float v : x) peak = std::fabs(v) > peak ? std::fabs(v) : peak;
  const float thr = 0.3f * peak;
  for (size_t i = 0; i < x.size(); ++i)
    if (std::fabs(x[i]) >= thr) return static_cast<int>(i);
  return -1;
}
float energy(const std::vector<float>& x) {
  float e = 0.0f;
  for (float v : x) e += v * v;
  return e;
}
bool all_finite(const std::vector<float>& x) {
  for (float v : x) if (!std::isfinite(v)) return false;
  return true;
}

// Render one impulse object at (az, el) with the given strength; return L/R.
void render_impulse(float az, float el, std::vector<float>& l, std::vector<float>& r,
                    float strength = 1.0f) {
  const int n = 256;
  std::vector<float> obj(n, 0.0f);
  obj[0] = 1.0f;
  const float* objs[1] = {obj.data()};
  float aza[1] = {az}, ela[1] = {el};
  BinauralRenderer rend;
  rend.configure(48000, 4);
  rend.set_params(strength, /*height=*/true);
  std::vector<float> out(2 * n, 0.0f);
  rend.render(objs, aza, ela, 1, nullptr, nullptr, nullptr, 0, n, out.data());
  l.resize(n);
  r.resize(n);
  for (int i = 0; i < n; ++i) { l[i] = out[2 * i]; r[i] = out[2 * i + 1]; }
}

void test_hard_right() {
  std::printf("HRIR: hard-right object -> right ear leads + louder\n");
  std::vector<float> l, r;
  render_impulse(kHalfPi, 0.0f, l, r);                   // az = +90° (right)
  CHECK(all_finite(l) && all_finite(r));
  CHECK(onset(r) >= 0 && onset(l) >= 0);
  CHECK(onset(r) < onset(l));                            // right (near) leads
  CHECK(energy(r) > energy(l));                          // right (near) louder
}

void test_hard_left() {
  std::printf("HRIR: hard-left object -> left ear leads + louder\n");
  std::vector<float> l, r;
  render_impulse(-kHalfPi, 0.0f, l, r);                  // az = -90° (left)
  CHECK(onset(l) < onset(r));
  CHECK(energy(l) > energy(r));
}

void test_center_symmetric() {
  std::printf("HRIR: center object -> L/R symmetric\n");
  std::vector<float> l, r;
  render_impulse(0.0f, 0.0f, l, r);                      // az = 0 (front)
  CHECK(onset(l) == onset(r));
  CHECK(std::fabs(energy(l) - energy(r)) < 1e-4f);
}

void test_strength_zero_is_dry() {
  std::printf("HRIR: strength=0 -> dry mono (both ears equal to source)\n");
  std::vector<float> l, r;
  render_impulse(kHalfPi, 0.0f, l, r, /*strength=*/0.0f);
  // Dry: the raw impulse to both ears, no HRIR colouration -> L == R and the
  // energy equals the input impulse's (1.0).
  CHECK(std::fabs(energy(l) - energy(r)) < 1e-6f);
  CHECK(std::fabs(energy(l) - 1.0f) < 1e-4f);
}

void test_offgrid_interpolates() {
  std::printf("HRIR: off-grid azimuth interpolates to finite output\n");
  std::vector<float> l, r;
  render_impulse(0.37f, 0.21f, l, r);                    // between grid nodes
  CHECK(all_finite(l) && all_finite(r));
  CHECK(energy(l) > 0.0f && energy(r) > 0.0f);
}

}  // namespace

int main() {
  std::printf("=== Measured-HRIR render tests ===\n");
  test_hard_right();
  test_hard_left();
  test_center_symmetric();
  test_strength_zero_is_dry();
  test_offgrid_interpolates();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

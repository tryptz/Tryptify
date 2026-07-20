// Host test for the structural HRTF binaural renderer (plan P2).
//
//   c++ -std=c++17 -I.. hrtf_render_test.cpp -o hrtf && ./hrtf
//
// Feeds an impulse as a single object and checks the interaural cues: the near
// ear leads (smaller onset delay) and is louder (ILD) for a lateral source, and
// a center source is left/right symmetric. Also spot-checks the model math.
#include <cmath>
#include <cstdio>
#include <vector>

#include "render/structural_hrtf.h"

namespace {
int g_fail = 0, g_checks = 0;
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK(c) check((c), #c, __LINE__)

using namespace tf::atmos::render;

int onset(const std::vector<float>& x) {  // first sample above a small threshold
  for (size_t i = 0; i < x.size(); ++i)
    if (std::fabs(x[i]) > 0.01f) return static_cast<int>(i);
  return -1;
}
float energy(const std::vector<float>& x) {
  float e = 0.0f;
  for (float v : x) e += v * v;
  return e;
}

// Render one impulse object at (az, el); return per-ear L/R signals.
void render_impulse(float az, float el, std::vector<float>& l, std::vector<float>& r) {
  const int n = 256;
  std::vector<float> obj(n, 0.0f);
  obj[0] = 1.0f;
  const float* objs[1] = {obj.data()};
  float aza[1] = {az}, ela[1] = {el};
  BinauralRenderer rend;
  rend.configure(48000, 4);
  std::vector<float> out(2 * n, 0.0f);
  rend.render(objs, aza, ela, 1, nullptr, nullptr, nullptr, 0, n, out.data());
  l.resize(n);
  r.resize(n);
  for (int i = 0; i < n; ++i) { l[i] = out[2 * i]; r[i] = out[2 * i + 1]; }
}

void test_model_math() {
  std::printf("structural HRTF: model math\n");
  CHECK(woodworth_itd(0.0f) == 0.0f);                    // front: no ITD
  CHECK(woodworth_itd(kHalfPi) > woodworth_itd(0.3f));   // more lateral -> more ITD
  CHECK(std::fabs(ear_gain(0.0f) - 1.0f) < 1e-6f);       // facing ear: full gain
  CHECK(ear_gain(kPi) < ear_gain(0.0f));                 // far ear: quieter
  CHECK(ear_lp(0.0f) < 1e-6f);                           // facing ear: no LP
  CHECK(ear_lp(kPi) > 0.5f);                             // far ear: heavy LP
}

void test_hard_right() {
  std::printf("structural HRTF: hard-right object -> right ear leads + louder\n");
  std::vector<float> l, r;
  render_impulse(kHalfPi, 0.0f, l, r);                   // az = +90°
  CHECK(onset(r) >= 0 && onset(l) >= 0);
  CHECK(onset(r) < onset(l));                            // right (near) leads
  CHECK(energy(r) > energy(l));                          // right (near) louder
}

void test_hard_left() {
  std::printf("structural HRTF: hard-left object -> left ear leads + louder\n");
  std::vector<float> l, r;
  render_impulse(-kHalfPi, 0.0f, l, r);                  // az = -90°
  CHECK(onset(l) < onset(r));
  CHECK(energy(l) > energy(r));
}

void test_center_symmetric() {
  std::printf("structural HRTF: center object -> L/R symmetric\n");
  std::vector<float> l, r;
  render_impulse(0.0f, 0.0f, l, r);                      // az = 0 (front)
  CHECK(onset(l) == onset(r));
  CHECK(std::fabs(energy(l) - energy(r)) < 1e-4f);
}

}  // namespace

int main() {
  std::printf("=== Structural HRTF render tests ===\n");
  test_model_math();
  test_hard_right();
  test_hard_left();
  test_center_symmetric();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

// Host test for the loudspeaker renderer (speaker_renderer.h).
//
//   c++ -std=c++17 -I.. speaker_renderer_test.cpp -o spk && ./spk
//
// Checks, for every layout from 5.1 to 9.1.6: channel counts and order, that a
// source at a speaker's room position plays from that speaker, constant power
// everywhere on a grid spanning the room (no silent directions, including
// overhead on layouts without top speakers), zone constraints, elevation
// disable, channel lock, size, divergence, bed routing, the LFE path, and that
// the rendered frame ramps from the previous gains without a step.
#include <cmath>
#include <cstdio>
#include <vector>

#include "speaker_renderer.h"

namespace {
int g_fail = 0, g_checks = 0;
void check_near(float a, float b, float tol, const char* e, int line) {
  ++g_checks;
  if (!(std::fabs(a - b) <= tol)) {
    ++g_fail;
    std::printf("  FAIL %s:%d %s (%.5f vs %.5f)\n", __FILE__, line, e, a, b);
  }
}
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK_NEAR(a, b, t) check_near((a), (b), (t), #a " ~= " #b, __LINE__)
#define CHECK(c) check((c), #c, __LINE__)

using namespace tf::atmos;
using cavern::ObjectState;
using cavern::ReferenceChannel;

const OutputLayout kSpeakerLayouts[] = {OutputLayout::k5_1,   OutputLayout::k7_1,
                                        OutputLayout::k5_1_2, OutputLayout::k5_1_4,
                                        OutputLayout::k7_1_2, OutputLayout::k7_1_4,
                                        OutputLayout::k9_1_4, OutputLayout::k9_1_6};
const char* kNames[] = {"5.1", "7.1", "5.1.2", "5.1.4", "7.1.2", "7.1.4", "9.1.4", "9.1.6"};
const int kChannels[] = {6, 8, 8, 10, 10, 12, 14, 16};

ObjectState at(float x, float y, float z) {
  ObjectState s;
  s.active = true;
  s.room = {x, y, z};
  return s;
}

float power(const std::vector<float>& g) {
  float p = 0.0f;
  for (float v : g) p += v * v;
  return p;
}

int index_of(const SpeakerRenderer& r, SpeakerRole role) {
  const std::vector<SpeakerDef> sp = layout_speakers(r.layout());
  for (size_t i = 0; i < sp.size(); ++i) if (sp[i].role == role) return static_cast<int>(i);
  return -1;
}

void test_layouts() {
  std::printf("layout channel counts + Android order\n");
  for (int l = 0; l < 8; ++l) {
    SpeakerRenderer r;
    r.configure(kSpeakerLayouts[l], 16);
    CHECK(r.channels() == kChannels[l]);
    CHECK(r.lfe_channel() == 3);  // FL FR FC LFE ... in every Android mask
  }
  // 7.1 in mask order puts the rears (BACK) before the sides (SIDE).
  const std::vector<SpeakerDef> s71 = layout_speakers(OutputLayout::k7_1);
  CHECK(s71[4].role == SpeakerRole::kLrs && s71[6].role == SpeakerRole::kLss);
  // 9.1.6: top sides then wides last.
  const std::vector<SpeakerDef> s916 = layout_speakers(OutputLayout::k9_1_6);
  CHECK(s916[12].role == SpeakerRole::kLts && s916[14].role == SpeakerRole::kLw);
}

void test_point_at_speakers() {
  std::printf("front corners / front centre play from L / R / C\n");
  for (int l = 0; l < 8; ++l) {
    SpeakerRenderer r;
    r.configure(kSpeakerLayouts[l], 16);
    std::vector<float> g(r.channels());
    // Point sources on the front wall: no interior spread.
    r.object_gains(at(0.0f, 0.0f, 0.0f), false, g.data());
    CHECK_NEAR(g[0], 1.0f, 2e-3f);
    r.object_gains(at(1.0f, 0.0f, 0.0f), false, g.data());
    CHECK_NEAR(g[1], 1.0f, 2e-3f);
    r.object_gains(at(0.5f, 0.0f, 0.0f), false, g.data());
    CHECK_NEAR(g[2], 1.0f, 2e-3f);
  }
}

void test_constant_power_grid() {
  std::printf("constant power over a room grid, every layout\n");
  for (int l = 0; l < 8; ++l) {
    SpeakerRenderer r;
    r.configure(kSpeakerLayouts[l], 16);
    std::vector<float> g(r.channels());
    int bad = 0;
    for (int ix = 0; ix <= 8; ++ix)
      for (int iy = 0; iy <= 8; ++iy)
        for (int iz = -2; iz <= 4; ++iz) {
          r.object_gains(at(ix / 8.0f, iy / 8.0f, iz / 4.0f), false, g.data());
          if (std::fabs(power(g) - 1.0f) > 1e-3f || g[3] != 0.0f) ++bad;
        }
    if (bad) std::printf("  layout %s: %d bad grid points\n", kNames[l], bad);
    CHECK(bad == 0);
  }
}

void test_overhead() {
  std::printf("overhead: tops only on 7.1.4, spread over ear level on 5.1\n");
  {
    SpeakerRenderer r;
    r.configure(OutputLayout::k7_1_4, 16);
    std::vector<float> g(r.channels());
    r.object_gains(at(0.5f, 0.5f, 1.0f), false, g.data());
    for (int c = 0; c < 8; ++c) CHECK_NEAR(g[c], 0.0f, 1e-4f);
    for (int c = 8; c < 12; ++c) CHECK_NEAR(g[c], 0.5f, 1e-3f);  // 4 equal tops
  }
  {
    SpeakerRenderer r;
    r.configure(OutputLayout::k5_1, 16);
    std::vector<float> g(r.channels());
    r.object_gains(at(0.5f, 0.5f, 1.0f), false, g.data());
    for (int c : {0, 1, 2, 4, 5}) CHECK(g[c] > 0.3f);
    CHECK_NEAR(power(g), 1.0f, 1e-3f);
  }
}

void test_wides() {
  std::printf("9.1.4: a source between L and the side lands on the wide\n");
  SpeakerRenderer r;
  r.configure(OutputLayout::k9_1_4, 16);
  std::vector<float> g(r.channels());
  // Cube azimuth -63.4 deg warps to about -54.5 deg: next to Lw (-60).
  r.object_gains(at(0.0f, 0.25f, 0.0f), false, g.data());
  const int lw = index_of(r, SpeakerRole::kLw);
  CHECK(g[lw] > 0.9f);
  CHECK(g[lw] > g[0]);
}

void test_zones() {
  std::printf("zone constraints + elevation disable\n");
  SpeakerRenderer r;
  r.configure(OutputLayout::k7_1_4, 16);
  std::vector<float> g(r.channels());
  ObjectState back = at(0.5f, 1.0f, 0.0f);
  back.zone_constraints_idx = 4;  // screen only (horizontal zones)
  r.object_gains(back, false, g.data());
  // Table 20 constrains the horizontal zones only; the tops follow
  // b_enable_elevation (Table 21), so they may still play here.
  for (int c = 3; c < 8; ++c) CHECK_NEAR(g[c], 0.0f, 1e-3f);
  CHECK_NEAR(power(g), 1.0f, 1e-3f);
  back.enable_elevation = false;  // screen only AND no Top-Bottom zone: L R C
  r.object_gains(back, false, g.data());
  for (int c = 3; c < 12; ++c) CHECK_NEAR(g[c], 0.0f, 1e-3f);
  CHECK_NEAR(power(g), 1.0f, 1e-3f);

  ObjectState side = at(0.0f, 0.5f, 0.0f);
  side.zone_constraints_idx = 2;  // side zone excluded: Lss/Rss silent
  r.object_gains(side, false, g.data());
  CHECK_NEAR(g[index_of(r, SpeakerRole::kLss)], 0.0f, 1e-4f);
  CHECK_NEAR(power(g), 1.0f, 1e-3f);

  ObjectState high = at(0.2f, 0.3f, 1.0f);
  high.enable_elevation = false;  // Top-Bottom zone excluded
  r.object_gains(high, false, g.data());
  for (int c = 8; c < 12; ++c) CHECK_NEAR(g[c], 0.0f, 1e-4f);
  CHECK_NEAR(power(g), 1.0f, 1e-3f);
}

void test_snap_size_divergence() {
  std::printf("channel lock, size and divergence\n");
  SpeakerRenderer r;
  r.configure(OutputLayout::k7_1_4, 16);
  std::vector<float> g(r.channels());
  ObjectState s = at(0.1f, 0.1f, 0.0f);
  s.snap = true;
  r.object_gains(s, false, g.data());
  CHECK_NEAR(g[0], 1.0f, 1e-6f);  // nearest is L
  CHECK_NEAR(power(g), 1.0f, 1e-6f);

  ObjectState big = at(0.5f, 0.0f, 0.0f);
  big.width = big.depth = big.height = 1.0f;
  r.object_gains(big, false, g.data());
  int lit = 0;
  for (int c = 0; c < 12; ++c) if (c != 3 && g[c] > 0.05f) ++lit;
  CHECK(lit >= 9);  // a room-sized object plays from (nearly) every speaker
  CHECK_NEAR(power(g), 1.0f, 1e-3f);

  ObjectState div = at(0.5f, 0.0f, 0.0f);
  div.divergence = 1.0f;  // all energy to the two sides at x = 0 and x = 1
  r.object_gains(div, false, g.data());
  CHECK_NEAR(g[0], g[1], 1e-4f);
  CHECK(g[0] > 0.65f);
  CHECK(g[2] < 1e-3f);
  CHECK_NEAR(power(g), 1.0f, 1e-3f);
}

void test_beds_and_lfe() {
  std::printf("bed routing + LFE\n");
  SpeakerRenderer r;
  r.configure(OutputLayout::k7_1, 16);
  std::vector<float> g(r.channels());
  // A 5.1 bed's surround (+-110 deg) falls between the 7.1 side and rear.
  r.bed_channel_gains(4, 6, g.data());
  CHECK(g[index_of(r, SpeakerRole::kLss)] > 0.3f);
  CHECK(g[index_of(r, SpeakerRole::kLrs)] > 0.3f);
  CHECK_NEAR(power(g), 1.0f, 1e-3f);
  r.bed_channel_gains(0, 6, g.data());
  CHECK_NEAR(g[0], 1.0f, 1e-6f);
  r.bed_channel_gains(3, 6, g.data());
  CHECK_NEAR(g[3], 1.0f, 1e-6f);

  ObjectState bed;
  bed.active = true;
  bed.is_bed = true;
  bed.bed_channel = ReferenceChannel::kTopFrontLeft;  // not in 7.1: panned from above-left
  bed.render = cavern::ObjectAudioMetadata::bed_render_position(bed.bed_channel);
  r.object_gains(bed, false, g.data());
  CHECK_NEAR(power(g), 1.0f, 1e-3f);
  CHECK(g[0] > 0.2f);

  r.object_gains(at(0.5f, 0.5f, 0.0f), true, g.data());  // the LFE object
  CHECK_NEAR(g[3], 1.0f, 1e-6f);
}

void test_render_ramp() {
  std::printf("render: gains ramp from the previous frame\n");
  SpeakerRenderer r;
  r.configure(OutputLayout::k5_1, 4);
  const int n = 64;
  std::vector<float> one(n, 1.0f), out(static_cast<size_t>(n) * r.channels());
  const float* pcm[1] = {one.data()};
  ObjectState left = at(0.0f, 0.0f, 0.0f), right = at(1.0f, 0.0f, 0.0f);
  const ObjectState* st[1] = {&left};
  r.render(pcm, st, 1, -1, 1.0f, n, out.data());
  CHECK_NEAR(out[0], 1.0f, 2e-3f);                // L, first frame starts at target
  st[0] = &right;
  r.render(pcm, st, 1, -1, 1.0f, n, out.data());
  CHECK_NEAR(out[0], 1.0f, 2e-3f);                // starts where the last frame ended
  CHECK_NEAR(out[static_cast<size_t>(n - 1) * r.channels() + 1], 1.0f - 1.0f / n, 3e-2f);
  CHECK(out[static_cast<size_t>(n / 2) * r.channels()] < 0.6f);
}

}  // namespace

int main() {
  std::printf("=== Speaker renderer tests ===\n");
  test_layouts();
  test_point_at_speakers();
  test_constant_power_grid();
  test_overhead();
  test_wides();
  test_zones();
  test_snap_size_divergence();
  test_beds_and_lfe();
  test_render_ramp();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

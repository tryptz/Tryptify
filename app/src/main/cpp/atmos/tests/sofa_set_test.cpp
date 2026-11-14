// Host test for a loaded HRTF set's exact speakers (render/sofa_set.h,
// AtmosPipeline::set_sofa_set, VirtualRoom::set_hrir's second form).
//
//   c++ -std=c++17 -O2 -I.. sofa_set_test.cpp -o set && ./set
//
// The packed form, that a speaker with a pair of its own plays that pair and
// not the table's blend, and that the table still serves the rest. No
// libmysofa: the sets here are made from the baked table.
#include <cmath>
#include <cstdio>
#include <vector>

#include "atmos_pipeline.h"
#include "check.h"

namespace {

using namespace tf::atmos;
using render::EarTune;
using render::SofaSet;
using render::kHrirTaps;

struct Made {
  std::vector<float> directions, taps, delays;
  int speakers = 0;
};

// The room's speakers, each with the pair the baked table gives it at `scale`.
Made room_pairs(float scale) {
  Made m;
  std::vector<render::RoomSpeaker> v;
  for (const SpeakerDef& s : layout_speakers(OutputLayout::k15_1_12)) v.push_back({s.azimuth, s.elevation, is_lfe(s)});
  for (size_t s = 0; s < v.size(); ++s) {
    if (v[s].lfe) continue;
    m.directions.push_back(v[s].azimuth);
    m.directions.push_back(v[s].elevation);
    // The table's own answer here, undelayed: what a loader would have read
    // from a file that is the table.
    float hl[kHrirTaps], hr[kHrirTaps], dl, dr;
    render::VirtualRoom::table_pair(&render::kHrirData[0][0][0][0], &render::kHrirDelay[0][0][0],
                                    v[s].azimuth, v[s].elevation, hl, hr, dl, dr);
    for (int k = 0; k < kHrirTaps; ++k) m.taps.push_back(hl[k] * scale);
    for (int k = 0; k < kHrirTaps; ++k) m.taps.push_back(hr[k] * scale);
    m.delays.push_back(dl);
    m.delays.push_back(dr);
    ++m.speakers;
  }
  return m;
}

std::vector<float> packed(const Made& m) {
  return SofaSet::pack(&render::kHrirData[0][0][0][0], &render::kHrirDelay[0][0][0], m.speakers,
                       m.directions.data(), m.taps.data(), m.delays.data());
}

void curve(const AtmosPipeline& p, float az, float el, float* db) {
  const float hz[4] = {1000.0f, 3000.0f, 7000.0f, 11000.0f};
  p.ear_curve(EarTune{}, az, el, az < 0.0f ? 0 : 1, hz, 4, db);
}

void test_packing() {
  std::printf("-- the packed form\n");
  const Made m = room_pairs(1.0f);
  CHECK(m.speakers == 27);
  std::vector<float> p = packed(m);
  CHECK(p.size() == SofaSet::packed_size(27));
  SofaSet set;
  CHECK(SofaSet::unpack(p.data(), p.size(), set));
  CHECK(set.speakers == 27);
  CHECK(set.speaker_at(-45.0f, 60.0f) >= 0);
  CHECK(set.speaker_at(-46.0f, 60.0f) < 0);
  CHECK(set.taps[5] == (&render::kHrirData[0][0][0][0])[5]);
  // What is not a set is refused: a wrong mark, a wrong length, a count that is not one.
  std::vector<float> bad = p;
  bad[0] = 1.0f;
  CHECK(!SofaSet::unpack(bad.data(), bad.size(), set));
  CHECK(!SofaSet::unpack(p.data(), p.size() - 1, set));
  bad = p;
  bad[5] = 26.5f;
  CHECK(!SofaSet::unpack(bad.data(), bad.size(), set));
  bad[5] = -1.0f;
  CHECK(!SofaSet::unpack(bad.data(), bad.size(), set));
  CHECK(!SofaSet::unpack(nullptr, 0, set));
  AtmosPipeline pipe;
  pipe.configure(48000, 16);
  CHECK(!pipe.set_sofa_set(bad.data(), bad.size()));
  CHECK(pipe.exact_speakers() == 0);
}

void test_exact_speakers_play() {
  std::printf("-- a speaker with a pair of its own plays it\n");
  AtmosPipeline table, same, half;
  table.configure(48000, 16);
  same.configure(48000, 16);
  half.configure(48000, 16);
  // The table alone, as a loaded set with no speakers of its own.
  const std::vector<float> none = SofaSet::pack(&render::kHrirData[0][0][0][0], &render::kHrirDelay[0][0][0], 0,
                                                nullptr, nullptr, nullptr);
  CHECK(table.set_sofa_set(none.data(), none.size()));
  CHECK(table.exact_speakers() == 0);
  const std::vector<float> a = packed(room_pairs(1.0f)), b = packed(room_pairs(0.5f));
  CHECK(same.set_sofa_set(a.data(), a.size()));
  CHECK(half.set_sofa_set(b.data(), b.size()));
  CHECK(same.exact_speakers() == 27);
  CHECK(half.exact_speakers() == 27);
  const float where[4][2] = {{-30.0f, 0.0f}, {15.0f, 0.0f}, {-30.0f, 30.0f}, {-45.0f, 60.0f}};
  for (const auto& w : where) {
    float t[4], s[4], h[4];
    curve(table, w[0], w[1], t);
    curve(same, w[0], w[1], s);
    curve(half, w[0], w[1], h);
    for (int i = 0; i < 4; ++i) {
      CHECK_NEAR(s[i], t[i], 1e-3);          // the table's own answer, handed back, is the table
      CHECK_NEAR(h[i], t[i] - 6.0206, 1e-2); // and a pair at half the level plays at half the level
    }
  }
  // Between the speakers there is no pair: the table answers, as it did.
  float t[4], h[4];
  curve(table, -50.0f, 10.0f, t);
  curve(half, -50.0f, 10.0f, h);
  for (int i = 0; i < 4; ++i) CHECK_NEAR(h[i], t[i], 1e-3);
  // The baked set again has no pairs.
  half.clear_sofa();
  CHECK(half.exact_speakers() == 0);
}

void test_render_is_finite() {
  std::printf("-- and it renders\n");
  AtmosPipeline pipe;
  pipe.configure(48000, 16);
  const std::vector<float> a = packed(room_pairs(1.0f));
  CHECK(pipe.set_sofa_set(a.data(), a.size()));
  pipe.set_ear_tune(1.1f, 7000.0f, 6.0f, 3.0f);
  CHECK(pipe.exact_speakers() == 27);  // the tuner builds from the pairs, and keeps them
  const int samples = 1536;
  std::vector<std::vector<float>> ch(6, std::vector<float>(samples, 0.0f));
  std::vector<const float*> ptrs(6);
  for (int c = 0; c < 6; ++c) ptrs[c] = ch[c].data();
  const std::vector<uint8_t> frame(64, 0xAB);
  std::vector<float> out(2 * samples, 0.0f);
  double energy = 0.0;
  bool finite = true;
  for (int f = 0; f < 4; ++f) {
    for (int i = 0; i < samples; ++i) ch[0][i] = 0.2f * std::sin((f * samples + i) * 0.21f);
    CHECK(pipe.process_frame(frame.data(), frame.size(), ptrs.data(), 6, samples, out.data()) == 1);
    for (float x : out) { finite = finite && dspIsFinite(x); energy += x * x; }
  }
  CHECK(finite);
  CHECK(energy > 0.0);
}

}  // namespace

int main() {
  test_packing();
  test_exact_speakers_play();
  test_render_is_finite();
  std::printf("%d checks, %d failed\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

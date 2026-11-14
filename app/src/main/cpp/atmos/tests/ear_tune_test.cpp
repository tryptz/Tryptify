// Host test for the ear tuner (render::EarTune, render/virtual_room.h): the
// listener's ear size and the height band on top of an HRIR set.
//
//   c++ -std=c++17 -O2 -I.. ear_tune_test.cpp -o ear && ./ear
//
// Untuned it must change nothing at all; tuned, each control must do what
// its name says and nothing else, and what the graph draws must be what
// plays.
#include <cmath>
#include <cstdio>
#include <vector>

#include "atmos_pipeline.h"
#include "check.h"

namespace {

using namespace tf::atmos;
using render::EarTune;
using render::RoomSpeaker;
using render::VirtualRoom;

std::vector<RoomSpeaker> room_layout() {
  std::vector<RoomSpeaker> v;
  for (const SpeakerDef& s : layout_speakers(OutputLayout::k15_1_12)) {
    v.push_back({s.azimuth, s.elevation, is_lfe(s)});
  }
  return v;
}

int speaker_at(const std::vector<RoomSpeaker>& v, float az, float el) {
  for (size_t s = 0; s < v.size(); ++s) {
    if (!v[s].lfe && std::fabs(v[s].azimuth - az) < 0.5f && std::fabs(v[s].elevation - el) < 0.5f) {
      return static_cast<int>(s);
    }
  }
  return -1;
}

// A click from speaker `s`, as the room plays it: stereo, `frames` long.
std::vector<float> click(VirtualRoom& room, int count, int s, int frames = 4096) {
  std::vector<float> feeds(static_cast<size_t>(frames) * count, 0.0f), out(2 * static_cast<size_t>(frames));
  feeds[static_cast<size_t>(64) * count + s] = 0.25f;
  room.reset_history();
  room.render(feeds.data(), frames, out.data());
  return out;
}

// The level of one ear of `stereo` at `hz`, in dB.
double level_db(const std::vector<float>& stereo, int ear, double hz) {
  const double w = 2.0 * 3.14159265358979323846 * hz / 48000.0;
  double re = 0.0, im = 0.0;
  const size_t n = stereo.size() / 2;
  for (size_t t = 0; t < n; ++t) {
    re += stereo[2 * t + ear] * std::cos(w * t);
    im -= stereo[2 * t + ear] * std::sin(w * t);
  }
  return 10.0 * std::log10(re * re + im * im + 1e-20);
}

EarTune tune(float size, float hz, float db, float q) {
  EarTune t;
  t.size = size; t.freq_hz = hz; t.gain_db = db; t.q = q;
  return t;
}

void test_untuned_is_the_set() {
  std::printf("-- untuned, the room is the set's own, bit for bit\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  VirtualRoom plain, tuned;
  plain.configure(48000, v);
  tuned.configure(48000, v);
  plain.set_params(1.0f, 0.0f);
  tuned.set_params(1.0f, 0.0f);
  tuned.set_ear_tune(tune(1.1f, 7000.0f, 6.0f, 3.0f));
  tuned.set_ear_tune(EarTune{});  // and back
  const int up = speaker_at(v, -45.0f, 60.0f);
  CHECK(up >= 0);
  const std::vector<float> a = click(plain, count, up), b = click(tuned, count, up);
  bool same = true;
  for (size_t i = 0; i < a.size(); ++i) same = same && a[i] == b[i];
  CHECK(same);
}

void test_height_band() {
  std::printf("-- the height band is on the speakers above, by how far up they are\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  VirtualRoom plain, tuned;
  plain.configure(48000, v);
  tuned.configure(48000, v);
  plain.set_params(1.0f, 0.0f);
  tuned.set_params(1.0f, 0.0f);
  tuned.set_ear_tune(tune(1.0f, 8000.0f, 6.0f, 2.0f));
  const int top = speaker_at(v, -45.0f, 60.0f), mid = speaker_at(v, -30.0f, 30.0f), ear = speaker_at(v, -30.0f, 0.0f);
  CHECK(top >= 0 && mid >= 0 && ear >= 0);
  const double at_top = level_db(click(tuned, count, top), 0, 8000.0) - level_db(click(plain, count, top), 0, 8000.0);
  const double at_mid = level_db(click(tuned, count, mid), 0, 8000.0) - level_db(click(plain, count, mid), 0, 8000.0);
  const double at_ear = level_db(click(tuned, count, ear), 0, 8000.0) - level_db(click(plain, count, ear), 0, 8000.0);
  std::printf("   +6 dB at 8 kHz: %.2f dB at 60 up, %.2f at 30, %.2f at ear level\n", at_top, at_mid, at_ear);
  CHECK_NEAR(at_top, 6.0, 0.3);
  CHECK_NEAR(at_mid, 6.0 * EarTune::height_share(30.0f), 0.3);
  CHECK_NEAR(at_ear, 0.0, 1e-6);
  // Away from the band the speaker above is as it was.
  const double away = level_db(click(tuned, count, top), 0, 1000.0) - level_db(click(plain, count, top), 0, 1000.0);
  CHECK_NEAR(away, 0.0, 0.2);
  // A cut is a cut, and the right ear has it as the left does.
  tuned.set_ear_tune(tune(1.0f, 8000.0f, -9.0f, 2.0f));
  const double cut = level_db(click(tuned, count, top), 1, 8000.0) - level_db(click(plain, count, top), 1, 8000.0);
  CHECK_NEAR(cut, -9.0, 0.3);
}

void test_ear_size() {
  std::printf("-- a larger ear has every cue lower, at the same level\n");
  // What plays is what curve_of draws (held below), so the curve is read here:
  // a finer look at the spectrum than a click through the room gives.
  const int n = 240;
  std::vector<float> hz(n), base(n), large(n), small(n);
  for (int i = 0; i < n; ++i) hz[i] = 2000.0f * std::pow(8.0f, i / static_cast<float>(n - 1));  // 2k..16k
  VirtualRoom::baked_ear_curve(EarTune{}, -45.0f, 60.0f, 0, hz.data(), n, base.data());
  VirtualRoom::baked_ear_curve(tune(1.25f, 8000.0f, 0.0f, 2.0f), -45.0f, 60.0f, 0, hz.data(), n, large.data());
  VirtualRoom::baked_ear_curve(tune(0.8f, 8000.0f, 0.0f, 2.0f), -45.0f, 60.0f, 0, hz.data(), n, small.data());
  // The shift that lines a curve up with the set's own best, in steps of the log grid.
  auto best_shift = [&](const std::vector<float>& c) {
    int best = 0; double least = 1e30;
    for (int sh = -60; sh <= 60; ++sh) {
      double err = 0.0; int used = 0;
      for (int i = 40; i < n - 40; ++i) {
        const int j = i + sh;
        if (j < 0 || j >= n) continue;
        const double d = c[j] - base[i]; err += d * d; ++used;
      }
      err /= used;
      if (err < least) { least = err; best = sh; }
    }
    return std::pow(8.0, best / static_cast<double>(n - 1));  // as a ratio of pitch
  };
  const double down = best_shift(large), up = best_shift(small);
  std::printf("   a 1.25 ear's curve sits at x%.3f of the pitch, a 0.8 ear's at x%.3f\n", down, up);
  CHECK_NEAR(down, 1.0 / 1.25, 0.03);
  CHECK_NEAR(up, 1.0 / 0.8, 0.04);
  // Down low, where the ear does nothing, the level is the set's.
  float low_hz[1] = {300.0f}, a[1], b[1];
  VirtualRoom::baked_ear_curve(EarTune{}, -45.0f, 60.0f, 0, low_hz, 1, a);
  VirtualRoom::baked_ear_curve(tune(1.25f, 8000.0f, 0.0f, 2.0f), -45.0f, 60.0f, 0, low_hz, 1, b);
  CHECK_NEAR(b[0] - a[0], 0.0, 0.5);
}

void test_graph_is_what_plays() {
  std::printf("-- the graph's curve is the filter that plays\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const EarTune t = tune(1.12f, 6500.0f, 8.0f, 3.0f);
  VirtualRoom plain, tuned;
  plain.configure(48000, v);
  tuned.configure(48000, v);
  plain.set_params(1.0f, 0.0f);
  tuned.set_params(1.0f, 0.0f);
  tuned.set_ear_tune(t);
  const int top = speaker_at(v, -45.0f, 60.0f);
  const std::vector<float> a = click(plain, count, top), b = click(tuned, count, top);
  float hz[4] = {3000.0f, 5000.0f, 6500.0f, 9000.0f}, base[4], with[4];
  plain.ear_curve(EarTune{}, -45.0f, 60.0f, 0, hz, 4, base);
  tuned.ear_curve(t, -45.0f, 60.0f, 0, hz, 4, with);
  for (int i = 0; i < 4; ++i) {
    // The room adds the same equalizer and limiter trim to both: the change is what is compared.
    const double played = level_db(b, 0, hz[i]) - level_db(a, 0, hz[i]);
    CHECK_NEAR(with[i] - base[i], played, 0.25);
  }
}

void test_limits() {
  std::printf("-- out of range is brought in, and nothing is ever not a number\n");
  const EarTune wild = tune(9.0f, 50.0f, 80.0f, 0.0f).clamped();
  CHECK(wild.size == EarTune::kMaxSize && wild.freq_hz == EarTune::kMinHz);
  CHECK(wild.gain_db == EarTune::kMaxDb && wild.q == EarTune::kMinQ);
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(1.0f, 0.25f);
  const float corners[4][4] = {{0.8f, 3000.0f, 12.0f, 8.0f}, {1.25f, 14000.0f, 12.0f, 0.5f},
                               {0.8f, 14000.0f, -12.0f, 8.0f}, {1.25f, 3000.0f, -12.0f, 0.5f}};
  for (const auto& c : corners) {
    room.set_ear_tune(tune(c[0], c[1], c[2], c[3]));
    for (int s = 0; s < count; ++s) {
      if (v[s].lfe) continue;
      const std::vector<float> out = click(room, count, s, 1536);
      bool finite = true; float peak = 0.0f;
      for (float x : out) { finite = finite && dspIsFinite(x); peak = std::max(peak, std::fabs(x)); }
      CHECK(finite);
      CHECK(peak <= 1.0f);
    }
  }
}

}  // namespace

int main() {
  test_untuned_is_the_set();
  test_height_band();
  test_ear_size();
  test_graph_is_what_plays();
  test_limits();
  std::printf("%d checks, %d failed\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

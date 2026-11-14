// Host test for the 15.1.12 virtual room (render/virtual_room.h).
//
//   c++ -std=c++17 -O2 -I.. virtual_room_test.cpp -o vroom -lpthread && ./vroom
//
// The core is held to a null test: speaker feeds through the room's
// frequency-domain convolution against the same feeds convolved in the time
// domain, tap by tap, with the filters the room says it uses. Then the parts
// round it: the layout, the LFE path, the dry blend, bass management, the
// room (reflections and tail), the limiter, a reset, and a set swapped in from
// another thread while it renders.
#include <atomic>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "render/virtual_room.h"
#include "speaker_renderer.h"
// A number a test compares must be a number: under -ffast-math (the
// *_fastmath build, and the phone's) a comparison with NaN is whatever the
// compiler likes, so a NaN could pass every CHECK_NEAR. This looks at the
// bits behind a barrier the compiler cannot see through (dsp/util/finite.h).
#include "check.h"

namespace {

using namespace tf::atmos;
using render::RoomSpeaker;
using render::VirtualRoom;
using render::kDfe;
using render::kDfeTaps;

std::vector<RoomSpeaker> room_layout() {
  std::vector<RoomSpeaker> v;
  for (const SpeakerDef& s : layout_speakers(OutputLayout::k15_1_12)) {
    v.push_back({s.azimuth, s.elevation, is_lfe(s)});
  }
  return v;
}

int speaker_at(const std::vector<RoomSpeaker>& v, float az, float el) {
  for (size_t i = 0; i < v.size(); ++i) {
    if (!v[i].lfe && std::fabs(v[i].azimuth - az) < 0.5f && std::fabs(v[i].elevation - el) < 0.5f) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

int lfe_of(const std::vector<RoomSpeaker>& v) {
  for (size_t i = 0; i < v.size(); ++i) if (v[i].lfe) return static_cast<int>(i);
  return -1;
}

// A repeatable noise source, -1 .. 1.
struct Noise {
  uint32_t s;
  explicit Noise(uint32_t seed) : s(seed) {}
  float next() {
    s = s * 1664525u + 1013904223u;
    return static_cast<float>(static_cast<int32_t>(s)) / 2147483648.0f;
  }
};

// Renders `feeds` ([frame][speaker]) through `room` in calls of the given sizes
// (the last size repeats).
std::vector<float> run(VirtualRoom& room, const std::vector<float>& feeds, int count,
                       const std::vector<int>& sizes) {
  const int total = static_cast<int>(feeds.size()) / count;
  std::vector<float> out(static_cast<size_t>(2) * total, 0.0f);
  int at = 0;
  size_t k = 0;
  while (at < total) {
    int n = sizes[k < sizes.size() ? k : sizes.size() - 1];
    ++k;
    if (n > total - at) n = total - at;
    room.render(feeds.data() + static_cast<size_t>(at) * count, n, out.data() + 2 * at);
    at += n;
  }
  return out;
}

double energy(const std::vector<float>& x, int ear, int from, int to) {
  double e = 0.0;
  for (int i = from; i < to; ++i) e += static_cast<double>(x[2 * i + ear]) * x[2 * i + ear];
  return e;
}

double db(double ratio) { return 10.0 * std::log10(ratio > 1e-30 ? ratio : 1e-30); }

void test_layout() {
  std::printf("-- the 15.1.12 layout\n");
  const std::vector<RoomSpeaker> v = room_layout();
  CHECK(v.size() == 28);
  int ear = 0, low = 0, high = 0, lfe = 0;
  for (const RoomSpeaker& s : v) {
    if (s.lfe) ++lfe;
    else if (s.elevation < 1.0f) ++ear;
    else if (s.elevation < 45.0f) ++low;
    else ++high;
  }
  CHECK(ear == 15);
  CHECK(lfe == 1);
  CHECK(low == 8);
  CHECK(high == 4);
  // One speaker dead ahead; every other one has its mirror image.
  CHECK(speaker_at(v, 0.0f, 0.0f) >= 0);
  for (const RoomSpeaker& s : v) {
    if (s.lfe || std::fabs(s.azimuth) < 0.5f) continue;
    CHECK(speaker_at(v, -s.azimuth, s.elevation) >= 0);
  }
  // The object panner: every direction of the room at constant power, a
  // source at a speaker from that speaker alone, and nothing on the LFE.
  SpeakerRenderer r;
  r.configure(OutputLayout::k15_1_12, 16);
  CHECK(r.channels() == 28);
  std::vector<float> g(28);
  for (int ix = 0; ix <= 8; ++ix) {
    for (int iy = 0; iy <= 8; ++iy) {
      for (int iz = 0; iz <= 4; ++iz) {
        cavern::ObjectState st;
        st.active = true;
        st.room = {ix / 8.0f, iy / 8.0f, iz / 4.0f};
        r.object_gains(st, false, g.data());
        float p = 0.0f;
        for (float x : g) p += x * x;
        CHECK_NEAR(p, 1.0f, 1e-3f);
        CHECK(g[r.lfe_channel()] == 0.0f);
      }
    }
  }
  // The room's corners and wall centres land on a speaker each.
  const struct { float x, y, az; } spots[] = {
      {0.0f, 0.0f, -30.0f}, {1.0f, 0.0f, 30.0f}, {0.5f, 0.0f, 0.0f},
      {0.0f, 0.5f, -90.0f}, {1.0f, 0.5f, 90.0f}, {0.0f, 1.0f, -135.0f}, {1.0f, 1.0f, 135.0f}};
  for (const auto& spot : spots) {
    cavern::ObjectState st;
    st.active = true;
    st.room = {spot.x, spot.y, 0.0f};
    r.object_gains(st, false, g.data());
    const int want = speaker_at(v, spot.az, 0.0f);
    CHECK(want >= 0);
    if (want >= 0) CHECK_NEAR(g[want], 1.0f, 2e-3f);
  }
  // A 7.1 bed comes rears first (FL FR FC LFE BL BR SL SR); a 5.1 bed's
  // surrounds are at 110.
  r.bed_channel_gains(4, 8, g.data());
  CHECK_NEAR(g[speaker_at(v, -135.0f, 0.0f)], 1.0f, 1e-4f);
  r.bed_channel_gains(7, 8, g.data());
  CHECK_NEAR(g[speaker_at(v, 90.0f, 0.0f)], 1.0f, 1e-4f);
  r.bed_channel_gains(4, 6, g.data());
  CHECK_NEAR(g[speaker_at(v, -110.0f, 0.0f)], 1.0f, 1e-4f);
  r.bed_channel_gains(3, 6, g.data());
  CHECK_NEAR(g[r.lfe_channel()], 1.0f, 1e-6f);
  // A 5.1 bed and a 7.1 bed play from speakers of their own.
  for (int bed = 6; bed <= 8; bed += 2) {
    for (int c = 0; c < bed; ++c) {
      r.bed_channel_gains(c, bed, g.data());
      float peak = 0.0f, p = 0.0f;
      for (float x : g) { p += x * x; if (x > peak) peak = x; }
      CHECK_NEAR(peak, 1.0f, 1e-4f);
      CHECK_NEAR(p, 1.0f, 1e-4f);
    }
  }
}

void test_null() {
  std::printf("-- frequency-domain convolution against the time domain\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(1.0f, 0.0f);

  const int total = 5000;
  const int used[] = {speaker_at(v, 0.0f, 0.0f), speaker_at(v, -30.0f, 0.0f),
                      speaker_at(v, 110.0f, 0.0f), speaker_at(v, -70.0f, 30.0f),
                      speaker_at(v, 135.0f, 60.0f), speaker_at(v, 160.0f, 0.0f)};
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(7);
  for (int s : used) {
    CHECK(s >= 0);
    // Each speaker starts and stops somewhere else, so a speaker going
    // silent and coming back is in the test too.
    const int from = static_cast<int>((noise.next() * 0.5f + 0.5f) * 900);
    const int to = total - static_cast<int>((noise.next() * 0.5f + 0.5f) * 1500);
    for (int i = from; i < to; ++i) feeds[static_cast<size_t>(i) * count + s] = 0.05f * noise.next();
    for (int i = 2000; i < 2700; ++i) feeds[static_cast<size_t>(i) * count + s] = 0.0f;
  }
  // Whole frames, then calls that end inside a block.
  const std::vector<float> got = run(room, feeds, count, {1536, 256, 100, 257, 1, 400, 513, 77});

  // The same, the long way: each speaker through its filter, then the
  // equalizer, the trim and the limiter's delay.
  for (int ear = 0; ear < 2; ++ear) {
    std::vector<double> y(total, 0.0);
    float ir[VirtualRoom::kIrLen];
    for (int s : used) {
      room.baked_filter(s, ear, ir);
      for (int n = 0; n < total; ++n) {
        double acc = 0.0;
        const int kmax = n < VirtualRoom::kIrLen - 1 ? n : VirtualRoom::kIrLen - 1;
        for (int k = 0; k <= kmax; ++k) acc += ir[k] * feeds[static_cast<size_t>(n - k) * count + s];
        y[n] += acc;
      }
    }
    double worst = 0.0, peak = 0.0;
    float shelf[5];
    room.bass_shelf(shelf);
    std::vector<float> eq(kDfeTaps);
    room.active_equalizer(eq.data());
    double s1 = 0.0, s2 = 0.0;
    for (int n = 0; n + VirtualRoom::kLookahead < total; ++n) {
      double z = 0.0;
      const int jmax = n < kDfeTaps - 1 ? n : kDfeTaps - 1;
      for (int j = 0; j <= jmax; ++j) z += eq[j] * y[n - j];
      // The low shelf, then the trim.
      const double x = z;
      z = shelf[0] * x + s1;
      s1 = shelf[1] * x - shelf[3] * z + s2;
      s2 = shelf[2] * x - shelf[4] * z;
      z *= VirtualRoom::kTrim;
      const double d = std::fabs(got[2 * (n + VirtualRoom::kLookahead) + ear] - z);
      if (d > worst) worst = d;
      if (std::fabs(z) > peak) peak = std::fabs(z);
    }
    std::printf("   ear %d: peak %.4f, worst difference %.2e (%.1f dB)\n", ear, peak, worst,
                20.0 * std::log10(worst / peak));
    CHECK(peak > 0.01);
    CHECK(worst < 1e-4 * peak);
  }
  // Nothing comes out before the stage's delay.
  for (int i = 0; i < 2 * VirtualRoom::kLookahead; ++i) CHECK(got[i] == 0.0f);
}

void test_sides() {
  std::printf("-- left is left\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const int total = 4096;
  auto ears = [&](float az, float el, double& l, double& r) {
    VirtualRoom room;
    room.configure(48000, v);
    room.set_params(1.0f, 0.0f);
    std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
    Noise noise(3);
    const int s = speaker_at(v, az, el);
    for (int i = 0; i < total; ++i) feeds[static_cast<size_t>(i) * count + s] = 0.05f * noise.next();
    const std::vector<float> out = run(room, feeds, count, {1536});
    l = energy(out, 0, 0, total);
    r = energy(out, 1, 0, total);
  };
  double l, r;
  ears(-90.0f, 0.0f, l, r);
  CHECK(db(l / r) > 6.0);
  const double left_l = l, left_r = r;
  ears(90.0f, 0.0f, l, r);
  CHECK(db(r / l) > 6.0);
  // The baked KEMAR set is one ear measured twice: a mirror image, near enough.
  CHECK_NEAR(db(r / left_l), 0.0, 1.5);
  CHECK_NEAR(db(l / left_r), 0.0, 1.5);
  ears(0.0f, 0.0f, l, r);
  CHECK_NEAR(db(l / r), 0.0, 1.5);
  // Equalized to the diffuse field, one speaker is about -3 dB an ear, less
  // the trim: neither a tenth nor ten times that.
  const double per_ear = db((l + r) / 2.0 / (0.05 * 0.05 / 3.0 * total));
  std::printf("   a speaker ahead: %.1f dB per ear against its feed\n", per_ear);
  CHECK(per_ear > -12.0 && per_ear < 0.0);
}

void test_lfe() {
  std::printf("-- the LFE path\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const int lfe = lfe_of(v);
  CHECK(lfe >= 0);
  const int total = 48000;
  auto level = [&](double hz) {
    VirtualRoom room;
    room.configure(48000, v);
    room.set_params(1.0f, 1.0f);  // the room is on: the LFE must stay out of it
    std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
    for (int i = 0; i < total; ++i) {
      feeds[static_cast<size_t>(i) * count + lfe] = 0.02f * static_cast<float>(std::sin(2.0 * M_PI * hz * i / 48000.0));
    }
    const std::vector<float> out = run(room, feeds, count, {1536});
    for (int i = 0; i < total; ++i) CHECK(out[2 * i] == out[2 * i + 1]);  // both ears alike
    return std::sqrt(energy(out, 0, total / 2, total) / (total / 2)) * std::sqrt(2.0);
  };
  // +10 dB in band, less the trim; gone above the low-pass.
  const double want = 0.02 * VirtualRoom::kLfeGain * VirtualRoom::kTrim;
  CHECK_NEAR(level(40.0) / want, 1.0, 0.03);
  CHECK(20.0 * std::log10(level(120.0) / want) < -5.0);
  CHECK(20.0 * std::log10(level(120.0) / want) > -7.0);  // LR4: -6 dB at its corner
  CHECK(20.0 * std::log10(level(1000.0) / want) < -60.0);
}

void test_dry_and_bass() {
  std::printf("-- the dry blend and bass management\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const int total = 3000;
  const int left = speaker_at(v, -90.0f, 0.0f);
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(11);
  for (int i = 0; i < total; ++i) feeds[static_cast<size_t>(i) * count + left] = 0.05f * noise.next();
  {
    // Strength 0: the speaker panned hard left, late by the stage's delay.
    VirtualRoom room;
    room.configure(48000, v);
    room.set_params(0.0f, 0.0f);
    const std::vector<float> out = run(room, feeds, count, {1536});
    double worst = 0.0;
    for (int i = 0; i + VirtualRoom::kLatency < total; ++i) {
      const double want = VirtualRoom::kTrim * feeds[static_cast<size_t>(i) * count + left];
      worst = std::fmax(worst, std::fabs(out[2 * (i + VirtualRoom::kLatency)] - want));
      worst = std::fmax(worst, std::fabs(out[2 * (i + VirtualRoom::kLatency) + 1]));
    }
    CHECK(worst < 1e-6);
  }
  {
    // Bass management: a low tone on one side reaches both ears alike.
    VirtualRoom room;
    room.configure(48000, v);
    room.set_params(1.0f, 0.0f);
    room.set_bass_management(true, 120);
    const int n = 48000;
    std::vector<float> low(static_cast<size_t>(n) * count, 0.0f);
    for (int i = 0; i < n; ++i) {
      low[static_cast<size_t>(i) * count + left] = 0.05f * static_cast<float>(std::sin(2.0 * M_PI * 30.0 * i / 48000.0));
    }
    const std::vector<float> out = run(room, low, count, {1536});
    const double l = energy(out, 0, n / 2, n), r = energy(out, 1, n / 2, n);
    CHECK_NEAR(db(l / r), 0.0, 0.5);
    CHECK_NEAR(db(l / (n / 2) / (0.05 * 0.05 / 2.0 * VirtualRoom::kTrim * VirtualRoom::kTrim)), 0.0, 1.0);
  }
}

void test_room() {
  std::printf("-- the room\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const int total = 96000;
  const int burst = 4800;  // 100 ms of noise, then silence
  const int front = speaker_at(v, -30.0f, 0.0f);
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(5);
  for (int i = 0; i < burst; ++i) feeds[static_cast<size_t>(i) * count + front] = 0.05f * noise.next();
  auto render_at = [&](float amount) {
    VirtualRoom room;
    room.configure(48000, v);
    room.set_params(1.0f, amount);
    return run(room, feeds, count, {1536});
  };
  const std::vector<float> dry = render_at(0.0f);
  const std::vector<float> live = render_at(1.0f);
  const std::vector<float> some = render_at(0.25f);
  auto both = [](const std::vector<float>& x, int from, int to) {
    return energy(x, 0, from, to) + energy(x, 1, from, to);
  };
  // No room: when the filters have rung out there is nothing at all.
  // (A block's rounding takes one more block and the equalizer to clear,
  // and the low shelf's memory 200 ms more to fall to nothing.)
  const int rung = burst + VirtualRoom::kIrLen + 2 * kDfeTaps + 2 * VirtualRoom::kBlock;
  bool silent = true;
  for (int i = rung + 9600; i < total; ++i) if (dry[2 * i] != 0.0f || dry[2 * i + 1] != 0.0f) silent = false;
  CHECK(silent);
  // A room: sound after the burst, dying away, and no louder than the room
  // was asked to be.
  const double direct = both(dry, 0, total);
  const double added = both(live, 0, total) - direct;
  const double after = both(live, rung, total);
  std::printf("   room = 1: %.1f dB of room against the direct sound, %.1f dB of it after the burst\n",
              db(added / direct), db(after / direct));
  CHECK(db(added / direct) > -9.0 && db(added / direct) < -2.0);
  CHECK(after > 0.0);
  const double early = both(live, rung, rung + 4800);
  const double late = both(live, rung + 14400, rung + 19200);  // 300 ms on
  std::printf("   the tail falls %.1f dB in 300 ms\n", db(early / late));
  CHECK(db(early / late) > 40.0 && db(early / late) < 75.0);
  CHECK(both(live, rung + 48000, total) < 1e-9 * direct);
  // A quarter of the room is 12 dB less of it.
  const double quarter = both(some, rung, total);
  CHECK_NEAR(db(quarter / after), -12.04, 0.5);
  // The room is in both ears, and not the same in each.
  const double tl = energy(live, 0, rung, total), tr = energy(live, 1, rung, total);
  CHECK(std::fabs(db(tl / tr)) < 6.0);
  double cross = 0.0;
  for (int i = rung; i < total; ++i) cross += static_cast<double>(live[2 * i]) * live[2 * i + 1];
  CHECK(std::fabs(cross) / std::sqrt(tl * tr) < 0.5);
}

void test_limiter() {
  std::printf("-- the limiter\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(1.0f, 1.0f);
  const int total = 48000;
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(9);
  // Far too much: every speaker and the LFE at full scale, with a quiet
  // stretch in the middle the gain has to come back up through.
  for (int i = 0; i < total; ++i) {
    const float level = (i > 20000 && i < 30000) ? 0.001f : 1.0f;
    for (int s = 0; s < count; ++s) feeds[static_cast<size_t>(i) * count + s] = level * noise.next();
  }
  const std::vector<float> out = run(room, feeds, count, {1536, 300, 77});
  float peak = 0.0f, step = 0.0f;
  for (int i = 0; i < 2 * total; ++i) peak = std::fmax(peak, std::fabs(out[i]));
  for (int i = 2; i < 2 * total; ++i) step = std::fmax(step, std::fabs(out[i] - out[i - 2]));
  std::printf("   peak %.4f (ceiling %.4f)\n", peak, VirtualRoom::kCeiling);
  CHECK(peak <= VirtualRoom::kCeiling * 1.00001f);
  CHECK(peak > 0.8f * VirtualRoom::kCeiling);
  for (int i = 0; i < 2 * total; ++i) CHECK(dspIsFinite(out[i]));
}

void test_reset_and_silence() {
  std::printf("-- reset and silence\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const int total = 6000;
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(21);
  for (int i = 0; i < total; ++i) {
    for (int s = 0; s < count; ++s) feeds[static_cast<size_t>(i) * count + s] = 0.02f * noise.next();
  }
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(0.8f, 0.5f);
  room.set_bass_management(true, 80);
  const std::vector<float> first = run(room, feeds, count, {1536});
  room.reset_history();
  const std::vector<float> again = run(room, feeds, count, {1536});
  bool same = true;
  for (size_t i = 0; i < first.size(); ++i) if (first[i] != again[i]) same = false;
  CHECK(same);
  // Silence in, silence out, from the first sample.
  room.reset_history();
  std::vector<float> none(static_cast<size_t>(3072) * count, 0.0f);
  const std::vector<float> quiet = run(room, none, count, {1536});
  for (float x : quiet) CHECK(x == 0.0f);
}

// The level a steady tone from one speaker comes out at, both ears together.
double tone_level_db(VirtualRoom& room, int count, int speaker, double hz) {
  const int total = 48000;
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  for (int i = 0; i < total; ++i) {
    feeds[static_cast<size_t>(i) * count + speaker] =
        0.05f * static_cast<float>(std::sin(2.0 * 3.14159265358979323846 * hz * i / 48000.0));
  }
  room.reset_history();
  const std::vector<float> out = run(room, feeds, count, {1536});
  double e = 0.0;
  for (int i = 24000; i < total; ++i) e += out[2 * i] * out[2 * i] + out[2 * i + 1] * out[2 * i + 1];
  return 10.0 * std::log10(e / 24000.0 + 1e-30);
}

void test_low_end() {
  std::printf("-- the low end the measured set lacks is put back\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(1.0f, 0.0f);
  room.set_bass_management(false, 80);
  // The baked set is 5 dB short at 60 Hz: the shelf is there, and bounded.
  float shelf[5];
  room.bass_shelf(shelf);
  const double dc = 20.0 * std::log10((shelf[0] + shelf[1] + shelf[2]) / (1.0 + shelf[3] + shelf[4]));
  std::printf("   shelf %.2f dB\n", dc);
  CHECK(dc > 4.0 && dc < 7.0);
  // A speaker's bass comes out as loud as its low mids, with no help from
  // the crossover: before the shelf 60 Hz was 5 dB under 400 Hz.
  // The speakers ahead: a head already makes a side speaker's near ear
  // louder at 400 Hz than in the bass.
  const int used[] = {speaker_at(v, 0.0f, 0.0f), speaker_at(v, -30.0f, 0.0f), speaker_at(v, 30.0f, 0.0f)};
  for (int s : used) {
    const double mid = tone_level_db(room, count, s, 400.0);
    for (double hz : {50.0, 80.0, 120.0, 200.0}) {
      const double d = tone_level_db(room, count, s, hz) - mid;
      std::printf("   speaker %d, %3.0f Hz against 400 Hz: %+.2f dB\n", s, hz, d);
      CHECK(std::fabs(d) < 2.0);
    }
  }
  // A set whose low end is level gets no shelf: one tap for every direction.
  std::vector<float> flat(static_cast<size_t>(render::kHrirElCount) * render::kHrirAzCount * 2 * render::kHrirTaps, 0.0f);
  for (size_t i = 0; i < flat.size(); i += render::kHrirTaps) flat[i] = 1.0f;
  std::vector<float> one(kDfeTaps, 0.0f);
  one[0] = 1.0f;
  VirtualRoom::design_bass_shelf(flat.data(), one.data(), 48000.0f, shelf);
  CHECK(shelf[0] == 1.0f && shelf[1] == 0.0f && shelf[2] == 0.0f && shelf[3] == 0.0f && shelf[4] == 0.0f);
}

double fir_db(const float* h, int n, double hz) {
  double re = 0.0, im = 0.0;
  for (int t = 0; t < n; ++t) {
    const double w = 2.0 * 3.14159265358979323846 * hz * t / 48000.0;
    re += h[t] * std::cos(w);
    im -= h[t] * std::sin(w);
  }
  return 10.0 * std::log10(re * re + im * im + 1e-30);
}

void test_tone_correction() {
  std::printf("-- the tone correction: the stage ahead, and the top octave\n");
  const int taps = VirtualRoom::kToneTaps;
  float tone[VirtualRoom::kToneTaps];
  VirtualRoom::design_tone_correction(&render::kHrirData[0][0][0][0], &render::kHrirDelay[0][0][0], kDfe, tone);
  // Nothing below 500 Hz. Up to 12 kHz it is the stage's, inside its
  // bounds, lifting the two dips of a sound panned between the front pair
  // (about 1.5 kHz, and 5 to 10 kHz).
  for (double hz : {60.0, 120.0, 250.0, 450.0}) CHECK(std::fabs(fir_db(tone, taps, hz)) < 0.3);
  for (double hz = 500.0; hz < 12000.0; hz *= 1.05) {
    const double g = fir_db(tone, taps, hz);
    CHECK(g < VirtualRoom::kStageBoostDb + 0.5 && g > -VirtualRoom::kStageCutDb - 0.5);
  }
  std::printf("   1.5 kHz %+.1f dB, 7 kHz %+.1f dB, 16 kHz %+.1f dB, 18 kHz %+.1f dB\n",
              fir_db(tone, taps, 1500.0), fir_db(tone, taps, 7000.0), fir_db(tone, taps, 16000.0),
              fir_db(tone, taps, 18000.0));
  CHECK(fir_db(tone, taps, 1500.0) > 1.0);
  CHECK(fir_db(tone, taps, 7000.0) > 2.0);
  // Above it, the top octave the baked set lacks (9 dB at 16 kHz, 14 at
  // 18), never over its limit, and nothing where the set has nothing.
  CHECK(fir_db(tone, taps, 16000.0) > 6.0);
  CHECK(fir_db(tone, taps, 18000.0) > 9.0);
  for (double hz = 12000.0; hz < 24000.0; hz += 250.0) CHECK(fir_db(tone, taps, hz) < VirtualRoom::kTopBoostDb + 0.7);
  CHECK(std::fabs(fir_db(tone, taps, 23000.0)) < 1.0);
  // The render uses it: the equalizer in use is the baked one through it.
  const std::vector<RoomSpeaker> v = room_layout();
  VirtualRoom room;
  room.configure(48000, v);
  std::vector<float> eq(kDfeTaps);
  room.active_equalizer(eq.data());
  for (double hz : {300.0, 2000.0, 7000.0, 17000.0}) {
    CHECK_NEAR(fir_db(eq.data(), kDfeTaps, hz), fir_db(kDfe, kDfeTaps, hz) + fir_db(tone, taps, hz), 0.2);
  }
  // A set with no ears to it (one tap, no delay, every direction) has no
  // dips to fill and no octave missing: no correction.
  const size_t points = static_cast<size_t>(render::kHrirElCount) * render::kHrirAzCount * 2;
  std::vector<float> flat(points * render::kHrirTaps, 0.0f), none(points, 0.0f);
  for (size_t i = 0; i < flat.size(); i += render::kHrirTaps) flat[i] = 1.0f;
  std::vector<float> one(kDfeTaps, 0.0f);
  one[0] = 1.0f;
  VirtualRoom::design_tone_correction(flat.data(), none.data(), one.data(), tone);
  for (double hz = 100.0; hz < 23000.0; hz *= 1.3) CHECK(std::fabs(fir_db(tone, taps, hz)) < 0.3);
}

void test_equalizer_design() {
  std::printf("-- the equalizer a loaded set gets\n");
  // Worked out from the baked table, it is the baked equalizer.
  std::vector<float> dfe(kDfeTaps);
  VirtualRoom::design_dfe(&render::kHrirData[0][0][0][0], dfe.data());
  float worst = 0.0f;
  for (int t = 0; t < kDfeTaps; ++t) worst = std::fmax(worst, std::fabs(dfe[t] - kDfe[t]));
  std::printf("   worst tap difference from the baked one: %.2e\n", worst);
  CHECK(worst < 1e-4f);
}

void test_set_swap() {
  std::printf("-- a set loaded while it renders\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  const size_t points = static_cast<size_t>(render::kHrirElCount) * render::kHrirAzCount * 2;
  const float* base = &render::kHrirData[0][0][0][0];
  const std::vector<float> same(base, base + points * render::kHrirTaps);
  const std::vector<float> delay(&render::kHrirDelay[0][0][0], &render::kHrirDelay[0][0][0] + points);
  std::vector<float> half(same);
  for (float& x : half) x *= 0.5f;

  const int total = 1536 * 40;
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(31);
  const int s = speaker_at(v, 30.0f, 0.0f);
  for (int i = 0; i < total; ++i) feeds[static_cast<size_t>(i) * count + s] = 0.05f * noise.next();

  VirtualRoom ref;
  ref.configure(48000, v);
  ref.set_params(1.0f, 0.0f);
  const std::vector<float> want = run(ref, feeds, count, {1536});

  // The same set, loaded: the same sound.
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(1.0f, 0.0f);
  room.set_hrir(same, delay);
  const std::vector<float> loaded = run(room, feeds, count, {1536});
  double worst = 0.0;
  for (size_t i = 0; i < want.size(); ++i) worst = std::fmax(worst, std::fabs(want[i] - loaded[i]));
  CHECK(worst < 1e-4);
  room.set_hrir_baked();
  room.reset_history();
  const std::vector<float> back = run(room, feeds, count, {1536});
  bool identical = true;
  for (size_t i = 0; i < want.size(); ++i) if (want[i] != back[i]) identical = false;
  CHECK(identical);

  // Sets swapped from another thread as fast as it can while this one
  // renders: every frame is finite and sensible, whichever set it caught.
  room.reset_history();
  std::atomic<bool> stop{false};
  std::thread loader([&] {
    int n = 0;
    while (!stop.load(std::memory_order_acquire)) {
      if (++n & 1) room.set_hrir(half, delay);
      else room.set_hrir_baked();
    }
  });
  std::vector<float> out(2 * 1536);
  bool finite = true;
  float peak = 0.0f;
  for (int f = 0; f < 400; ++f) {
    room.render(feeds.data() + static_cast<size_t>(f % 40) * 1536 * count, 1536, out.data());
    for (float x : out) {
      if (!dspIsFinite(x)) finite = false;
      peak = std::fmax(peak, std::fabs(x));
    }
  }
  stop.store(true, std::memory_order_release);
  loader.join();
  CHECK(finite);
  CHECK(peak > 0.0f && peak < 1.0f);
}

}  // namespace

int main() {
  std::printf("=== Virtual room tests ===\n");
  test_layout();
  test_null();
  test_sides();
  test_lfe();
  test_dry_and_bass();
  test_room();
  test_limiter();
  test_reset_and_silence();
  test_low_end();
  test_tone_correction();
  test_equalizer_design();
  test_set_swap();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

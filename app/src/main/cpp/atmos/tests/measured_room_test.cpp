// A measured room and a room worked out from its size, as the virtual room's
// reverb (render/measured_room.h, render/box_room.h).
//
//   c++ -std=c++17 -I.. measured_room_test.cpp -o measured_room_test && ./measured_room_test
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../render/box_room.h"
#include "../render/measured_room.h"
#include "../render/virtual_room.h"
#include "../speaker_renderer.h"
#include "check.h"

using namespace tf::atmos;
using namespace tf::atmos::render;

namespace {

struct Noise {
  uint32_t s;
  explicit Noise(uint32_t seed) : s(seed) {}
  float next() {
    s = s * 1664525u + 1013904223u;
    return static_cast<float>(static_cast<int32_t>(s)) / 2147483648.0f;
  }
};

double energy(const std::vector<float>& x) {
  double e = 0.0;
  for (float v : x) e += static_cast<double>(v) * v;
  return e;
}

double magnitude_db(const std::vector<float>& h, double hz) {
  std::complex<double> s = 0.0;
  for (size_t t = 0; t < h.size(); ++t) {
    s += static_cast<double>(h[t]) * std::polar(1.0, -2.0 * 3.14159265358979323846 * hz * t / 48000.0);
  }
  return 20.0 * std::log10(std::abs(s) + 1e-12);
}

// A made-up measurement: a direct sound at `onset`, then a noise that falls
// 60 dB in `decay` seconds, different in each ear.
void measurement(int n, int onset, double decay, double rate, std::vector<float>& l, std::vector<float>& r) {
  l.assign(n, 0.0f);
  r.assign(n, 0.0f);
  Noise noise(11);
  l[onset] = 1.0f;
  r[onset + 3] = 0.8f;
  for (int i = onset + 40; i < n; ++i) {
    const double fall = std::pow(10.0, -3.0 * (i - onset) / (decay * rate));
    l[i] = static_cast<float>(0.2 * fall * noise.next());
    r[i] = static_cast<float>(0.2 * fall * noise.next());
  }
}

void test_convolver() {
  std::printf("-- the convolver is the sum it stands for, one block late\n");
  RoomResponse room;
  Noise noise(3);
  const int len = 700;  // three partitions, the last part full
  room.left.resize(len);
  room.right.resize(len);
  for (int i = 0; i < len; ++i) { room.left[i] = noise.next(); room.right[i] = noise.next(); }
  RoomConvolver::Spectra spectra;
  RoomConvolver::analyse(room, spectra);
  CHECK(spectra.parts == 3);
  RoomConvolver conv;
  conv.configure();
  const int total = 3000;
  std::vector<float> x(total), l(total), r(total);
  for (float& v : x) v = noise.next();
  for (int i = 0; i < total; ++i) conv.tick(spectra, x[i], l[i], r[i]);
  double worst = 0.0, peak = 0.0;
  for (int n = 0; n < total; ++n) {
    double yl = 0.0, yr = 0.0;
    const int m = n - RoomConvolver::kLatency;
    for (int k = 0; k < len && k <= m; ++k) { yl += room.left[k] * x[m - k]; yr += room.right[k] * x[m - k]; }
    worst = std::fmax(worst, std::fmax(std::fabs(yl - l[n]), std::fabs(yr - r[n])));
    peak = std::fmax(peak, std::fabs(yl));
  }
  std::printf("   worst difference %.2e of a peak of %.1f\n", worst, peak);
  CHECK(peak > 10.0);
  CHECK(worst < 1e-5 * peak);
  // No room: silence. And a reset forgets what it heard.
  RoomConvolver::Spectra none;
  conv.reset();
  for (int i = 0; i < 600; ++i) { conv.tick(none, 1.0f, l[i], r[i]); CHECK(l[i] == 0.0f && r[i] == 0.0f); }
  conv.reset();
  for (int i = 0; i < 600; ++i) { conv.tick(spectra, 0.0f, l[i], r[i]); CHECK(l[i] == 0.0f && r[i] == 0.0f); }
}

void test_prepare() {
  std::printf("-- a measurement is made fit: direct sound cut, trimmed, levelled\n");
  std::vector<float> l, r;
  measurement(48000, 500, 0.4, 48000.0, l, r);
  RoomResponse room;
  CHECK(prepare_room_response(l.data(), r.data(), 48000, 48000.0, room));
  CHECK(room.left.size() == room.right.size());
  // It starts one convolver block after the direct sound, so the block the
  // convolver is late by puts every reflection back where it was.
  const double decay = room_decay_seconds(room);
  std::printf("   %.2f s kept of 0.99, decay %.2f s (made with 0.40)\n", room.left.size() / 48000.0, decay);
  CHECK_NEAR(decay, 0.4, 0.06);
  CHECK(room.left.size() < 28800 && room.left.size() >= 14400);  // not the half second of nothing after it
  CHECK_NEAR(energy(room.left) + energy(room.right), 2.0 * kRoomResponseEnergy, 1e-3);
  CHECK(std::fabs(room.left[0]) < 1e-6f && std::fabs(room.left.back()) < 1e-4f);  // faded in and out
  // The same room measured at 96 kHz and at 44.1 kHz is the same room.
  for (double rate : {96000.0, 44100.0}) {
    const int n = static_cast<int>(rate);
    measurement(n, static_cast<int>(500 * rate / 48000.0), 0.4, rate, l, r);
    RoomResponse other;
    CHECK(prepare_room_response(l.data(), r.data(), n, rate, other));
    CHECK_NEAR(room_decay_seconds(other), 0.4, 0.06);
    CHECK_NEAR(static_cast<double>(other.left.size()), static_cast<double>(room.left.size()), 4800.0);
  }
  // A file with a number that is not one in it still gives a room of numbers.
  measurement(48000, 500, 0.4, 48000.0, l, r);
  uint32_t bits = 0x7FC00000u;  // a NaN, made here and not by arithmetic
  std::memcpy(&l[20000], &bits, sizeof(bits));
  RoomResponse mended;
  CHECK(prepare_room_response(l.data(), r.data(), 48000, 48000.0, mended));
  for (float v : mended.left) CHECK(dspIsFinite(v));
  // Nothing after the direct sound is not a room; neither is silence.
  std::vector<float> dry(4096, 0.0f);
  dry[100] = 1.0f;
  CHECK(!prepare_room_response(dry.data(), dry.data(), 4096, 48000.0, room));
  std::vector<float> silent(4096, 0.0f);
  CHECK(!prepare_room_response(silent.data(), silent.data(), 4096, 48000.0, room));
  CHECK(!prepare_room_response(l.data(), r.data(), 0, 48000.0, room));
}

void test_pack() {
  std::printf("-- a packed room comes back as it went in\n");
  std::vector<float> l, r;
  measurement(48000, 500, 0.3, 48000.0, l, r);
  RoomResponse room, back;
  CHECK(prepare_room_response(l.data(), r.data(), 48000, 48000.0, room));
  RoomGeometry g;
  g.sized = true;
  g.width = 4.0f; g.depth = 5.0f; g.height = 2.5f; g.roundness = 0.25f;
  g.listener[0] = 2.0f; g.listener[1] = 3.0f; g.listener[2] = 1.2f;
  g.sources = 1;
  g.source[0][0] = 1.0f; g.source[0][1] = 0.5f; g.source[0][2] = 1.4f;
  const std::vector<float> packed = pack_room(room, g);
  CHECK(packed.size() == kRoomHeader + 2 * room.left.size());
  CHECK(packed[0] == kRoomMagic && packed[3] == 1.0f && packed[4] == 4.0f && packed[6] == 2.5f);
  CHECK(packed[7] == 0.25f && packed[9] == 3.0f && packed[11] == 1.0f && packed[14] == 1.4f);
  CHECK_NEAR(packed[2], room_decay_seconds(room), 1e-6);
  CHECK(unpack_room(packed.data(), packed.size(), back));
  CHECK(back.left == room.left && back.right == room.right);
  // Not a room: too short, the wrong start, a count the data does not hold.
  CHECK(!unpack_room(packed.data(), kRoomHeader - 1, back));
  CHECK(!unpack_room(nullptr, 0, back));
  std::vector<float> bad = packed;
  bad[0] = 1.0f;
  CHECK(!unpack_room(bad.data(), bad.size(), back));
  bad = packed;
  bad[1] = static_cast<float>(room.left.size() + 10);
  CHECK(!unpack_room(bad.data(), bad.size(), back));
}

void test_box() {
  std::printf("-- a room worked out from its size\n");
  BoxRoom box;
  box.width = 5.0f; box.depth = 6.0f; box.height = 2.7f; box.absorption = 0.25f;
  float modes[3];
  box_modes(box, modes);
  CHECK_NEAR(modes[0], 34.3, 0.1);  // c / 2L: 343 / 10
  CHECK_NEAR(modes[1], 28.6, 0.1);
  CHECK_NEAR(modes[2], 63.5, 0.1);
  CHECK_NEAR(box_decay_seconds(box), 0.38, 0.02);  // Eyring
  std::vector<float> l, r;
  box_response(box, l, r);
  CHECK(l.size() == r.size() && l.size() >= 12000);
  for (float v : l) CHECK(dspIsFinite(v));
  // The direct sound comes first, when the distance says: 2.64 m to the
  // left ear from its loudspeaker a quarter in and a sixth back.
  int first = 0;
  while (first < static_cast<int>(l.size()) && std::fabs(l[first]) < 1e-4f) ++first;
  std::printf("   direct sound at %.1f ms\n", first / 48.0);
  CHECK_NEAR(first / 48000.0 * 343.0, 2.7, 0.3);
  // The room rings at its modes: the depth mode stands well over a
  // frequency half way to its second.
  const double on = magnitude_db(l, modes[1]), off = magnitude_db(l, modes[1] * 1.5);
  std::printf("   the depth mode (%.0f Hz) is %.1f dB over %.0f Hz\n", modes[1], on - off, modes[1] * 1.5);
  CHECK(on - off > 6.0);
  // It is a room the render can use, the two ears not alike.
  RoomResponse room;
  CHECK(prepare_room_response(l.data(), r.data(), static_cast<int>(l.size()), 48000.0, room));
  double cross = 0.0;
  for (size_t i = 0; i < room.left.size(); ++i) cross += static_cast<double>(room.left[i]) * room.right[i];
  const double alike = cross / std::sqrt(energy(room.left) * energy(room.right));
  std::printf("   decay %.2f s, the ears %.2f alike\n", room_decay_seconds(room), alike);
  CHECK(alike < 0.7);
  // A bigger, harder room rings for longer.
  BoxRoom hall = box;
  hall.width = 14.0f; hall.depth = 20.0f; hall.height = 7.0f; hall.absorption = 0.15f;
  std::vector<float> hl, hr;
  box_response(hall, hl, hr);
  RoomResponse big;
  CHECK(prepare_room_response(hl.data(), hr.data(), static_cast<int>(hl.size()), 48000.0, big));
  CHECK(room_decay_seconds(big) > 1.5 * room_decay_seconds(room));
  // Sizes out of all reason are brought back to one that can be worked out.
  BoxRoom mad;
  mad.width = 0.0f; mad.depth = 1e9f; mad.height = -3.0f; mad.absorption = 7.0f;
  const BoxRoom ok = sane(mad);
  CHECK(ok.width == 1.5f && ok.depth == 40.0f && ok.height == 1.8f && ok.absorption == 0.9f);
  const RoomGeometry g = box_geometry(box);
  CHECK(g.sized && g.width == 5.0f && g.depth == 6.0f && g.height == 2.7f && g.sources == 2);
  CHECK(g.listener[0] > 0.0f && g.listener[0] < 5.0f && g.listener[1] > g.source[0][1]);
}

std::vector<RoomSpeaker> room_layout() {
  std::vector<RoomSpeaker> v;
  for (const SpeakerDef& s : layout_speakers(OutputLayout::k15_1_12)) v.push_back({s.azimuth, s.elevation, is_lfe(s)});
  return v;
}

void test_in_the_room() {
  std::printf("-- the virtual room plays it in place of its own\n");
  const std::vector<RoomSpeaker> v = room_layout();
  const int count = static_cast<int>(v.size());
  int front = 0;
  for (int s = 0; s < count; ++s) if (!v[s].lfe && v[s].azimuth == -30.0f && v[s].elevation == 0.0f) front = s;
  const int total = 96000, burst = 4800;
  std::vector<float> feeds(static_cast<size_t>(total) * count, 0.0f);
  Noise noise(5);
  for (int i = 0; i < burst; ++i) feeds[static_cast<size_t>(i) * count + front] = 0.05f * noise.next();
  std::vector<float> l, r;
  measurement(48000, 300, 0.5, 48000.0, l, r);
  RoomResponse measured;
  CHECK(prepare_room_response(l.data(), r.data(), 48000, 48000.0, measured));
  auto render = [&](float amount, const RoomResponse* response) {
    VirtualRoom room;
    room.configure(48000, v);
    room.set_params(1.0f, amount);
    room.set_room_response(response);
    CHECK(room.has_room_response() == (response != nullptr));
    std::vector<float> out(static_cast<size_t>(2) * total);
    for (int at = 0; at < total; at += 1536) {
      const int n = total - at < 1536 ? total - at : 1536;
      room.render(feeds.data() + static_cast<size_t>(at) * count, n, out.data() + 2 * at);
    }
    return out;
  };
  const std::vector<float> dry = render(0.0f, &measured);
  const std::vector<float> own = render(1.0f, nullptr);
  const std::vector<float> with = render(1.0f, &measured);
  const std::vector<float> half = render(0.5f, &measured);
  // At Room 0 a measured room adds nothing; at 1 about as much as the
  // built-in one does, so the slider means the same for both.
  const double direct = energy(dry);
  const double built_in = energy(own) - direct, added = energy(with) - direct;
  std::printf("   room against the direct sound: built-in %.1f dB, measured %.1f dB\n",
              10.0 * std::log10(built_in / direct), 10.0 * std::log10(added / direct));
  CHECK_NEAR(10.0 * std::log10(added / built_in), 0.0, 2.0);
  CHECK_NEAR(10.0 * std::log10((energy(half) - direct) / added), -6.02, 0.5);
  for (float x : with) CHECK(dspIsFinite(x));
  // It rings for as long as the room did: 300 ms after the burst there is
  // still sound, and with the built-in room (a 0.32 s tail) far less.
  auto after = [&](const std::vector<float>& x) {
    double e = 0.0;
    for (int i = burst + 14400; i < burst + 19200; ++i) e += x[2 * i] * x[2 * i] + x[2 * i + 1] * x[2 * i + 1];
    return e;
  };
  CHECK(after(with) > 4.0 * after(own));
  // Taking the room away again gives the built-in one back, bit for bit.
  VirtualRoom room;
  room.configure(48000, v);
  room.set_params(1.0f, 1.0f);
  room.set_room_response(&measured);
  room.set_room_response(nullptr);
  std::vector<float> back(static_cast<size_t>(2) * 1536);
  room.render(feeds.data(), 1536, back.data());
  bool same = true;
  for (int i = 0; i < 2 * 1536; ++i) if (back[i] != own[i]) same = false;
  CHECK(same);
}

}  // namespace

int main() {
  std::printf("=== Measured and worked-out rooms ===\n");
  test_convolver();
  test_prepare();
  test_pack();
  test_box();
  test_in_the_room();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

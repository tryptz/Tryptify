// A room worked out from its size: the impulse response of a box of given
// width, depth and height whose walls take up a given share of the sound
// that meets them.
//
// The image-source model (Allen and Berkley, 1979): a wall is a mirror, so
// the room is an endless lattice of copies of the source, each heard once,
// later and quieter the further away it is. Nothing else is put in. The
// lattice is what gives a room its modes: sound going to and fro between two
// parallel walls L apart comes back in step at c / 2L and its multiples, and
// those frequencies ring.
//
// Counting images costs the cube of the time, so the lattice is followed for
// as long as kMaxImages allows; after that the reflections are too many to
// tell apart, and a noise falling at the room's own rate stands in for them.
//
// Each ear is given its own loudspeaker (front left for the left, front
// right for the right): the render sends the room one signal, and one
// loudspeaker on the room's centre line would give both ears the same tail.
//
// Header-only. Allocates; loader thread.
#ifndef TF_ATMOS_RENDER_BOX_ROOM_H
#define TF_ATMOS_RENDER_BOX_ROOM_H

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "measured_room.h"

namespace tf {
namespace atmos {
namespace render {

struct BoxRoom {
  float width = 5.0f, depth = 6.0f, height = 2.7f;  // metres
  float absorption = 0.25f;                         // the walls' share, 0..1
};

namespace box_detail {
constexpr double kSpeedOfSound = 343.0;  // m/s
constexpr double kEarHeight = 1.2;       // seated, metres
constexpr double kHeadRadius = 0.0875;
constexpr double kMaxImages = 1.5e6;
constexpr double kMaxSeconds = 2.0;
inline double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}  // namespace box_detail

inline BoxRoom sane(BoxRoom r) {
  r.width = static_cast<float>(box_detail::clamp(r.width, 1.5, 40.0));
  r.depth = static_cast<float>(box_detail::clamp(r.depth, 1.5, 40.0));
  r.height = static_cast<float>(box_detail::clamp(r.height, 1.8, 15.0));
  r.absorption = static_cast<float>(box_detail::clamp(r.absorption, 0.03, 0.9));
  return r;
}

// Where the listener sits: three fifths of the way back and a little left of
// the centre line. On it, with the two loudspeakers mirrored about it, both
// ears would hear the same tail.
inline void box_listener(const BoxRoom& room, float* at) {
  at[0] = room.width * 0.44f;
  at[1] = room.depth * 0.6f;
  at[2] = static_cast<float>(std::min(box_detail::kEarHeight, room.height * 0.5));
}

// Where the two loudspeakers that sound the room stand: a quarter in from
// each side wall, a sixth of the way back from the wall ahead, at ear height.
inline void box_source(const BoxRoom& room, int ear, float* at) {
  at[0] = room.width * (ear == 0 ? 0.25f : 0.75f);
  at[1] = room.depth / 6.0f;
  at[2] = static_cast<float>(std::min(box_detail::kEarHeight, room.height * 0.5));
}

// How long the room takes to fall 60 dB (Eyring).
inline float box_decay_seconds(const BoxRoom& room) {
  const double v = static_cast<double>(room.width) * room.depth * room.height;
  const double s = 2.0 * (static_cast<double>(room.width) * room.depth + static_cast<double>(room.width) * room.height +
                          static_cast<double>(room.depth) * room.height);
  return static_cast<float>(0.161 * v / (-s * std::log(1.0 - room.absorption)));
}

// The room's lowest mode along each of its dimensions, in Hz: width, depth, height.
inline void box_modes(const BoxRoom& room, float* hz) {
  hz[0] = static_cast<float>(box_detail::kSpeedOfSound / (2.0 * room.width));
  hz[1] = static_cast<float>(box_detail::kSpeedOfSound / (2.0 * room.depth));
  hz[2] = static_cast<float>(box_detail::kSpeedOfSound / (2.0 * room.height));
}

// The room's response at each ear, 48 kHz, the direct sound included (the
// same thing a measurement of it would be: prepare_room_response() takes it
// from here).
inline void box_response(BoxRoom room, std::vector<float>& left, std::vector<float>& right) {
  using namespace box_detail;
  room = sane(room);
  const double dims[3] = {room.width, room.depth, room.height};
  const double decay = box_decay_seconds(room);
  const double seconds = std::min(kMaxSeconds, std::max(0.25, 1.5 * decay));
  const int n = static_cast<int>(seconds * 48000.0);
  // As far as the lattice is followed: a sphere of kMaxImages rooms.
  const double volume = dims[0] * dims[1] * dims[2];
  const double reach = std::min(seconds, std::cbrt(kMaxImages * volume / 4.18879) / kSpeedOfSound);
  const double beta = std::sqrt(1.0 - room.absorption);  // a wall's reflection, in amplitude
  float listener[3];
  box_listener(room, listener);

  for (int ear = 0; ear < 2; ++ear) {
    std::vector<float>& out = ear == 0 ? left : right;
    out.assign(n, 0.0f);
    float source[3];
    box_source(room, ear, source);
    const double at[3] = {listener[0] + (ear == 0 ? -kHeadRadius : kHeadRadius), listener[1], listener[2]};
    int span[3];
    for (int c = 0; c < 3; ++c) span[c] = static_cast<int>(std::ceil(reach * kSpeedOfSound / (2.0 * dims[c]))) + 1;
    for (int nx = -span[0]; nx <= span[0]; ++nx) for (int px = 0; px < 2; ++px) {
      const double dx = (px ? -source[0] : source[0]) + 2.0 * nx * dims[0] - at[0];
      const int rx = std::abs(nx - px) + std::abs(nx);
      for (int ny = -span[1]; ny <= span[1]; ++ny) for (int py = 0; py < 2; ++py) {
        const double dy = (py ? -source[1] : source[1]) + 2.0 * ny * dims[1] - at[1];
        const int ry = std::abs(ny - py) + std::abs(ny);
        for (int nz = -span[2]; nz <= span[2]; ++nz) for (int pz = 0; pz < 2; ++pz) {
          const double dz = (pz ? -source[2] : source[2]) + 2.0 * nz * dims[2] - at[2];
          const double dist = std::sqrt(dx * dx + dy * dy + dz * dz);
          const double when = dist / kSpeedOfSound;
          if (when >= reach) continue;
          const int bounces = rx + ry + std::abs(nz - pz) + std::abs(nz);
          // The head shadows what comes from its far side, a little.
          const double side = (ear == 0 ? -dx : dx) / std::max(dist, 1e-6);
          const double gain = std::pow(beta, bounces) / std::max(dist, 0.3) * (1.0 + 0.3 * side);
          const double pos = when * 48000.0;
          const int i = static_cast<int>(pos);
          if (i + 1 >= n) continue;
          const double frac = pos - i;
          out[i] += static_cast<float>(gain * (1.0 - frac));
          out[i + 1] += static_cast<float>(gain * frac);
        }
      }
    }
    // Past the lattice: noise at the level it had reached, falling as the room does.
    const int from = static_cast<int>(reach * 48000.0);
    if (from < n) {
      const int window = std::min(from, 960);
      double energy = 0.0;
      for (int i = from - window; i < from; ++i) energy += static_cast<double>(out[i]) * out[i];
      const double rms = window > 0 ? std::sqrt(energy / window) : 0.0;
      const double fall = std::pow(10.0, -3.0 / (std::max(decay, 0.05) * 48000.0));  // 60 dB in `decay`
      uint32_t seed = 0x9E3779B9u + static_cast<uint32_t>(ear) * 7919u;
      double level = rms * 1.7320508;  // uniform noise of that RMS
      for (int i = from; i < n; ++i) {
        seed = seed * 1664525u + 1013904223u;
        out[i] = static_cast<float>(level * (static_cast<int32_t>(seed) / 2147483648.0));
        level *= fall;
      }
    }
    // Walls and air take the treble first: a low-pass that closes with time,
    // from 16 kHz at the start to 3 kHz by the time the room has died away.
    double state = 0.0;
    for (int i = 0; i < n; ++i) {
      const double t = std::min(1.0, i / (std::max(decay, 0.05) * 48000.0));
      const double hz = 16000.0 * std::pow(3000.0 / 16000.0, t);
      const double a = 1.0 - std::exp(-2.0 * 3.14159265358979323846 * hz / 48000.0);
      state += a * (out[i] - state);
      out[i] = static_cast<float>(state);
    }
  }
}

// The room as the room view draws it.
inline RoomGeometry box_geometry(BoxRoom room) {
  room = sane(room);
  RoomGeometry g;
  g.sized = true;
  g.width = room.width;
  g.depth = room.depth;
  g.height = room.height;
  box_listener(room, g.listener);
  g.sources = 2;
  box_source(room, 0, g.source[0]);
  box_source(room, 1, g.source[1]);
  return g;
}

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_BOX_ROOM_H

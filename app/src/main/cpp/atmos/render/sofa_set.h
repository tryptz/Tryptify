// A loaded HRTF set, as it is handed about: one array of floats.
//
// The renderer's own table is a grid: an HRIR every 5 degrees round and
// every 10 up, from 40 below to overhead (hrir_table.h; it was every 10 by
// every 20 and stopped at 60 up). A direction between its points gets a
// blend of its neighbours, and two impulse responses blended are not the
// response in between: their outer-ear notches sit at different pitches,
// and the sum has both, shallow.
//
// A set measured finer than that (the Aachen KEMAR has a direction every
// degree, 64,800 of them) does not have to be blended: the room has 27
// speakers and they never move, so the loader asks the file for exactly
// those directions as well. A SofaSet carries both: the table, for what is
// worked out over every direction (the diffuse-field equalizer, the tone
// correction, the bass shelf), and one HRIR pair for each speaker, which is
// what plays.
//
// Layout, all float:
//   [0] kMagic  [1] kVersion  [2] taps per ear  [3] elevations  [4] azimuths
//   [5] speakers
//   then speakers x {azimuth, elevation} in degrees (the renderer's own:
//        0 ahead, positive to the right),
//   the table's taps  [elevation][azimuth][ear][tap],
//   the table's delays [elevation][azimuth][ear],
//   the speakers' taps  [speaker][ear][tap],
//   the speakers' delays [speaker][ear].
// Every delay is in samples at 48 kHz, on one clock: the smallest is 0.
#ifndef TF_ATMOS_RENDER_SOFA_SET_H
#define TF_ATMOS_RENDER_SOFA_SET_H

#include <cstddef>
#include <vector>

#include "hrir_table.h"

namespace tf {
namespace atmos {
namespace render {

struct SofaSet {
  static constexpr float kMagic = 50724.0f;
  static constexpr float kVersion = 2.0f;   // 2: the 5 by 10 degree table, up to overhead
  static constexpr int kHeader = 6;
  static constexpr int kMaxSpeakers = 64;
  static constexpr size_t kPoints = static_cast<size_t>(kHrirElCount) * kHrirAzCount * 2;

  const float* taps = nullptr;            // the table
  const float* delays = nullptr;
  int speakers = 0;
  const float* directions = nullptr;      // speakers x {az, el}
  const float* speaker_taps = nullptr;    // speakers x 2 x kHrirTaps
  const float* speaker_delays = nullptr;  // speakers x 2

  static size_t packed_size(int speakers) {
    return kHeader + static_cast<size_t>(speakers) * 2 + kPoints * kHrirTaps + kPoints +
           static_cast<size_t>(speakers) * 2 * kHrirTaps + static_cast<size_t>(speakers) * 2;
  }

  // Reads a packed set in place: `out` points into `packed`. False when it
  // is not one, or is one for another table.
  static bool unpack(const float* packed, size_t size, SofaSet& out) {
    if (packed == nullptr || size < static_cast<size_t>(kHeader)) return false;
    if (packed[0] != kMagic || packed[1] != kVersion) return false;
    if (packed[2] != static_cast<float>(kHrirTaps) || packed[3] != static_cast<float>(kHrirElCount) ||
        packed[4] != static_cast<float>(kHrirAzCount)) return false;
    const float count = packed[5];
    if (!(count >= 0.0f) || count > static_cast<float>(kMaxSpeakers)) return false;
    const int n = static_cast<int>(count);
    if (static_cast<float>(n) != count || size != packed_size(n)) return false;
    const float* p = packed + kHeader;
    out.speakers = n;
    out.directions = p;        p += static_cast<size_t>(n) * 2;
    out.taps = p;              p += kPoints * kHrirTaps;
    out.delays = p;            p += kPoints;
    out.speaker_taps = p;      p += static_cast<size_t>(n) * 2 * kHrirTaps;
    out.speaker_delays = p;
    return true;
  }

  // The packed form of a table and `speakers` exact pairs.
  static std::vector<float> pack(const float* taps, const float* delays, int speakers,
                                 const float* directions, const float* speaker_taps,
                                 const float* speaker_delays) {
    std::vector<float> out;
    out.reserve(packed_size(speakers));
    out.push_back(kMagic);
    out.push_back(kVersion);
    out.push_back(static_cast<float>(kHrirTaps));
    out.push_back(static_cast<float>(kHrirElCount));
    out.push_back(static_cast<float>(kHrirAzCount));
    out.push_back(static_cast<float>(speakers));
    out.insert(out.end(), directions, directions + static_cast<size_t>(speakers) * 2);
    out.insert(out.end(), taps, taps + kPoints * kHrirTaps);
    out.insert(out.end(), delays, delays + kPoints);
    out.insert(out.end(), speaker_taps, speaker_taps + static_cast<size_t>(speakers) * 2 * kHrirTaps);
    out.insert(out.end(), speaker_delays, speaker_delays + static_cast<size_t>(speakers) * 2);
    return out;
  }

  // The exact pair for a speaker at (az, el), or -1 when the set has none there.
  int speaker_at(float az_deg, float el_deg) const {
    for (int s = 0; s < speakers; ++s) {
      const float da = directions[2 * s] - az_deg, de = directions[2 * s + 1] - el_deg;
      if (da > -0.01f && da < 0.01f && de > -0.01f && de < 0.01f) return s;
    }
    return -1;
  }
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_SOFA_SET_H

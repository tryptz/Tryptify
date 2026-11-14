// A measured room as the virtual room's reverb: a room impulse response (from
// a SOFA file of BRIRs, SRIRs or DRIRs, read by room_sofa.cpp) in place of
// the built-in reflections and tail.
//
// prepare_room_response() makes a measurement fit to use: 48 kHz, its direct
// sound cut away (the render plays the direct sound itself, through its own
// ear filters), the silence after its tail trimmed, and its level set, so
// that the Room slider means the same for every room. RoomConvolver then
// plays a mono send through the two ears' responses as a uniformly
// partitioned convolution.
//
// The convolver is kBlock samples late, and the prepared response starts
// kBlock samples after the measurement's direct sound, so every reflection
// arrives when it did in the room; those of the first kBlock samples (5 ms)
// are lost with the direct sound.
//
// Header-only. prepare_room_response() and RoomConvolver::analyse() allocate
// (loader thread); configure() allocates; reset() and tick() do not.
#ifndef TF_ATMOS_RENDER_MEASURED_ROOM_H
#define TF_ATMOS_RENDER_MEASURED_ROOM_H

#include <algorithm>
#include <cmath>
#include <vector>

#include "../../dsp/util/finite.h"
#include "../fft.h"

namespace tf {
namespace atmos {
namespace render {

// The two ears' responses, prepared: 48 kHz, the same length.
struct RoomResponse {
  std::vector<float> left, right;
};

// What is known of the room itself, turned to face the way the listener
// faced: x across from the listener's left wall to the right one, y from the
// wall ahead to the wall behind, z up from the floor. Metres. A measurement
// of a room that is not a box says nothing of its size: `sized` is false
// and nothing else here is set.
struct RoomGeometry {
  static constexpr int kMaxSources = 64;
  bool sized = false;
  float width = 0.0f, depth = 0.0f;
  float height = 0.0f;   // 0 when only the floor plan is known
  float roundness = 0.0f;  // 0 a box, 1 its corners rounded as far as they go
  float listener[3] = {0.0f, 0.0f, 0.0f};
  int sources = 0;       // the loudspeakers that sounded the room
  float source[kMaxSources][3] = {};
};

class RoomConvolver {
 public:
  static constexpr int kBlock = 256;
  static constexpr int kFft = 2 * kBlock;
  static constexpr int kBins = kFft / 2 + 1;
  static constexpr int kMaxParts = 375;  // 2 s at 48 kHz
  static constexpr int kMaxSamples = kMaxParts * kBlock;
  static constexpr int kLatency = kBlock;

  // A response as the convolver uses it: each partition's spectrum, per ear.
  struct Spectra {
    std::vector<fft::Complex> bins;  // [ear][part][bin], scaled by 1/kFft
    int parts = 0;                   // 0: no room
  };

  RoomConvolver() : fft_(kFft) {}

  void configure() {
    fdl_.assign(static_cast<size_t>(kMaxParts) * kBins, fft::Complex(0.0f, 0.0f));
    reset();
  }

  // Loader thread. An empty response gives no room.
  static void analyse(const RoomResponse& response, Spectra& out) {
    const int len = static_cast<int>(std::min(response.left.size(), response.right.size()));
    const int parts = std::min((len + kBlock - 1) / kBlock, kMaxParts);
    out.bins.assign(static_cast<size_t>(2) * parts * kBins, fft::Complex(0.0f, 0.0f));
    fft::RealFft fft(kFft);
    float block[kFft];
    const float scale = 1.0f / kFft;  // the inverse transform is unnormalised
    for (int ear = 0; ear < 2; ++ear) {
      const std::vector<float>& h = ear == 0 ? response.left : response.right;
      for (int p = 0; p < parts; ++p) {
        for (int k = 0; k < kFft; ++k) {
          const int at = p * kBlock + k;
          block[k] = k < kBlock && at < len ? h[at] * scale : 0.0f;
        }
        fft.forward(block, &out.bins[(static_cast<size_t>(ear) * parts + p) * kBins]);
      }
    }
    out.parts = parts;
  }

  // Clears the input history, as if it had been silent.
  void reset() {
    std::fill(fdl_.begin(), fdl_.end(), fft::Complex(0.0f, 0.0f));
    std::fill(window_, window_ + kFft, 0.0f);
    std::fill(out_l_, out_l_ + kBlock, 0.0f);
    std::fill(out_r_, out_r_ + kBlock, 0.0f);
    pos_ = 0;
    slot_ = 0;
  }

  // One sample in, the room's answer to the input of kLatency samples ago out.
  void tick(const Spectra& room, float in, float& l, float& r) {
    l = out_l_[pos_];
    r = out_r_[pos_];
    window_[kBlock + pos_] = in;
    if (++pos_ == kBlock) {
      block(room);
      pos_ = 0;
    }
  }

 private:
  // A block is in: its spectrum joins the ring, and every partition meets
  // the block it is due (partition p, the one p blocks back).
  void block(const Spectra& room) {
    fft_.forward(window_, &fdl_[static_cast<size_t>(slot_) * kBins]);
    std::copy(window_ + kBlock, window_ + kFft, window_);
    for (int ear = 0; ear < 2; ++ear) {
      for (int k = 0; k < kBins; ++k) sum_[k] = fft::Complex(0.0f, 0.0f);
      const fft::Complex* h = room.parts > 0 ? &room.bins[static_cast<size_t>(ear) * room.parts * kBins] : nullptr;
      for (int p = 0; p < room.parts; ++p, h += kBins) {
        const fft::Complex* x = &fdl_[static_cast<size_t>((slot_ - p + kMaxParts) % kMaxParts) * kBins];
        for (int k = 0; k < kBins; ++k) sum_[k] += fft::mul(h[k], x[k]);
      }
      fft_.inverse(sum_, time_);
      std::copy(time_ + kBlock, time_ + kFft, ear == 0 ? out_l_ : out_r_);
    }
    slot_ = (slot_ + 1) % kMaxParts;
  }

  fft::RealFft fft_;
  std::vector<fft::Complex> fdl_;  // the last kMaxParts blocks' spectra, a ring
  float window_[kFft] = {};        // [the block before | the one coming in]
  float out_l_[kBlock] = {}, out_r_[kBlock] = {};
  fft::Complex sum_[kBins];
  float time_[kFft] = {};
  int pos_ = 0;
  int slot_ = 0;
};

namespace room_detail {

// `in` at `rate` as 48 kHz, by a Hann-windowed sinc cut at the lower Nyquist.
inline std::vector<float> to_48k(const float* in, int n, double rate) {
  if (rate == 48000.0) return std::vector<float>(in, in + n);
  const double step = rate / 48000.0;                 // input samples an output sample
  const double cut = step > 1.0 ? 1.0 / step : 1.0;   // against aliasing, going down
  const int reach = static_cast<int>(std::ceil(16.0 / cut));
  const double kPi = 3.14159265358979323846;
  std::vector<float> out(static_cast<size_t>(n / step));
  for (size_t t = 0; t < out.size(); ++t) {
    const double at = t * step;
    const int mid = static_cast<int>(at);
    double acc = 0.0;
    for (int k = std::max(0, mid - reach); k <= std::min(n - 1, mid + reach); ++k) {
      const double d = (at - k) * cut;
      const double sinc = std::fabs(d) < 1e-9 ? 1.0 : std::sin(kPi * d) / (kPi * d);
      acc += in[k] * sinc * (0.5 + 0.5 * std::cos(kPi * (at - k) / (reach + 1)));
    }
    out[t] = static_cast<float>(acc * cut);
  }
  return out;
}

}  // namespace room_detail

// Every prepared response has this much energy in each ear (the sum of its
// squares): the Room slider then adds as much room for one as for another,
// and as much as the built-in room it replaces (measured_room_test).
constexpr float kRoomResponseEnergy = 0.11f;

// Less than this after the direct sound (50 ms) is not a room.
constexpr int kShortestRoom = 2400;

// How long the prepared response takes to fall 60 dB, in seconds: three
// times its fall from -5 to -25 dB (T20), by the energy still to come.
inline float room_decay_seconds(const RoomResponse& room) {
  const int n = static_cast<int>(std::min(room.left.size(), room.right.size()));
  std::vector<double> ahead(n + 1, 0.0);
  for (int i = n - 1; i >= 0; --i) {
    ahead[i] = ahead[i + 1] + static_cast<double>(room.left[i]) * room.left[i] +
               static_cast<double>(room.right[i]) * room.right[i];
  }
  if (!(ahead[0] > 0.0)) return 0.0f;
  int at5 = -1, at25 = -1;
  for (int i = 0; i < n && at25 < 0; ++i) {
    const double db = 10.0 * std::log10(std::max(ahead[i] / ahead[0], 1e-30));
    if (at5 < 0 && db <= -5.0) at5 = i;
    if (db <= -25.0) at25 = i;
  }
  return at5 >= 0 && at25 > at5 ? 3.0f * static_cast<float>(at25 - at5) / 48000.0f : 0.0f;
}

// Makes a measured pair of responses (`n` samples each at `rate` Hz) fit for
// the convolver. False for a measurement with nothing after its direct sound.
inline bool prepare_room_response(const float* left, const float* right, int n, double rate,
                                  RoomResponse& out) {
  if (n <= 0 || !(rate >= 8000.0) || !(rate <= 384000.0)) return false;
  std::vector<float> ears[2] = {room_detail::to_48k(left, n, rate), room_detail::to_48k(right, n, rate)};
  const int len = static_cast<int>(ears[0].size());
  // A file is not to be trusted: one number that is not one would be in
  // every sample the room touched.
  for (auto& e : ears) dspZeroNonFinite(e.data(), e.size());
  // The direct sound: the first sample, in either ear, a tenth as high as the peak.
  float peak = 0.0f;
  for (const auto& e : ears) for (float v : e) peak = std::max(peak, std::fabs(v));
  if (!(peak > 0.0f)) return false;
  int onset = len;
  for (const auto& e : ears) {
    for (int i = 0; i < onset; ++i) if (std::fabs(e[i]) >= 0.1f * peak) { onset = i; break; }
  }
  const int from = onset + RoomConvolver::kLatency;
  if (from >= len) return false;
  // Where the tail has fallen 60 dB under what follows the direct sound.
  double total = 0.0;
  for (const auto& e : ears) for (int i = from; i < len; ++i) total += static_cast<double>(e[i]) * e[i];
  if (!(total > 0.0)) return false;
  int end = len;
  double after = 0.0;
  while (end > from + 1) {
    const int i = end - 1;
    after += static_cast<double>(ears[0][i]) * ears[0][i] + static_cast<double>(ears[1][i]) * ears[1][i];
    if (after > 1e-6 * total) break;
    --end;
  }
  int kept = std::min(end - from, RoomConvolver::kMaxSamples);
  if (kept < kShortestRoom) return false;  // an anechoic measurement: no room in it
  // A measurement's noise floor never falls 60 dB: past one and a half times
  // the room's own decay there is only that, and every partition costs.
  {
    RoomResponse whole;
    whole.left.assign(ears[0].begin() + from, ears[0].begin() + from + kept);
    whole.right.assign(ears[1].begin() + from, ears[1].begin() + from + kept);
    const float decay = room_decay_seconds(whole);
    if (decay > 0.0f) kept = std::min(kept, std::max(kShortestRoom * 5, static_cast<int>(1.5f * decay * 48000.0f)));
  }
  const int fade_in = std::min(48, kept), fade_out = kept / 10;
  const double kPi = 3.14159265358979323846;
  for (int ear = 0; ear < 2; ++ear) {
    std::vector<float>& dst = ear == 0 ? out.left : out.right;
    dst.assign(ears[ear].begin() + from, ears[ear].begin() + from + kept);
    for (int i = 0; i < fade_in; ++i) dst[i] *= static_cast<float>(0.5 - 0.5 * std::cos(kPi * i / fade_in));
    for (int i = 0; i < fade_out; ++i) {
      dst[kept - 1 - i] *= static_cast<float>(0.5 - 0.5 * std::cos(kPi * i / fade_out));
    }
  }
  // One gain for both ears, so the room keeps its own left and right.
  double energy = 0.0;
  for (float v : out.left) energy += static_cast<double>(v) * v;
  for (float v : out.right) energy += static_cast<double>(v) * v;
  if (!(energy > 0.0)) return false;
  const float gain = static_cast<float>(std::sqrt(2.0 * kRoomResponseEnergy / energy));
  for (float& v : out.left) v *= gain;
  for (float& v : out.right) v *= gain;
  return true;
}

// A prepared room as one array of floats, for the app to keep (a file, a
// field) and hand back: kRoomHeader floats of what is known of it, then the
// left ear's samples, then the right's.
//   [0] kRoomMagic   [1] samples an ear   [2] decay, seconds   [3] sized (0/1)
//   [4..6] width, depth, height   [7] roundness   [8..10] the listener
//   [11] sources   [12 + 3 i ..] source i
constexpr float kRoomMagic = 1297043282.0f;  // any packed room starts with it
constexpr int kRoomHeader = 12 + 3 * RoomGeometry::kMaxSources;

inline std::vector<float> pack_room(const RoomResponse& room, const RoomGeometry& g) {
  const size_t n = std::min(room.left.size(), room.right.size());
  std::vector<float> out(kRoomHeader + 2 * n, 0.0f);
  out[0] = kRoomMagic;
  out[1] = static_cast<float>(n);
  out[2] = room_decay_seconds(room);
  out[3] = g.sized ? 1.0f : 0.0f;
  out[4] = g.width;
  out[5] = g.depth;
  out[6] = g.height;
  out[7] = g.roundness;
  for (int c = 0; c < 3; ++c) out[8 + c] = g.listener[c];
  const int sources = std::min(g.sources, static_cast<int>(RoomGeometry::kMaxSources));
  out[11] = static_cast<float>(sources);
  for (int i = 0; i < sources; ++i) for (int c = 0; c < 3; ++c) out[12 + 3 * i + c] = g.source[i][c];
  std::copy(room.left.begin(), room.left.begin() + n, out.begin() + kRoomHeader);
  std::copy(room.right.begin(), room.right.begin() + n, out.begin() + kRoomHeader + n);
  return out;
}

// The response out of a packed room. False for anything that is not one.
inline bool unpack_room(const float* packed, size_t size, RoomResponse& out) {
  if (packed == nullptr || size < static_cast<size_t>(kRoomHeader) || packed[0] != kRoomMagic) return false;
  const float count = packed[1];
  if (!(count >= 1.0f) || !(count <= static_cast<float>(RoomConvolver::kMaxSamples))) return false;
  const size_t n = static_cast<size_t>(count);
  if (size < kRoomHeader + 2 * n) return false;
  out.left.assign(packed + kRoomHeader, packed + kRoomHeader + n);
  out.right.assign(packed + kRoomHeader + n, packed + kRoomHeader + 2 * n);
  return true;
}

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_MEASURED_ROOM_H

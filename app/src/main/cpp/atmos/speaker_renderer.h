// Clean-room loudspeaker renderer for Atmos objects: the multichannel
// counterpart of render/hrir_renderer.h (which renders to binaural stereo).
//
// Objects arrive as reconstructed PCM (ObjectEngine) plus their resolved OAMD
// state (cavern::ObjectState) and leave as one interleaved PCM frame for a
// physical speaker layout — 5.1 up to 9.1.6. Per object and frame:
//
//   1. Room position -> direction. OAMD positions live in a normalized room
//      cube (clause 4.2.1), not on a sphere: the front-left corner is where the
//      mixer's L speaker is, which sits at -30 degrees, not the -45 of the raw
//      cube angle. room_to_direction() warps cube azimuth onto the Dolby home
//      speaker angles (0->0, 45->30, 90->90, 135->150) and maps height so the
//      top of a wall (z = 1 at the wall) lands on the 45-degree top speakers.
//   2. Panning. Vector Base Amplitude Panning (Pulkki 1997) over the convex
//      hull of the layout's speakers, precomputed once. Imaginary zenith and
//      nadir speakers close the hull so every direction has a triangle on every
//      layout; their gain is shared out to the real top (or ear-level) speakers.
//   3. OAMD render properties (ETSI TS 103 420 clause 5.2):
//        - size (width/depth/height): the source is sampled over its box and the
//          gains power-summed, so a room-sized object plays from everywhere;
//        - divergence: a power-preserving split into a centre and two side
//          sources along X (BS.2127-style: (1-v)/(1+v) and v/(1+v) each);
//        - zone constraints (Table 20/21 + the speaker->zone map of Table A.7):
//          each constraint gets its own hull over only the permitted speakers;
//        - channel lock (snap): the whole object goes to the nearest speaker.
//      Bed objects play from their own channel when the layout has it, and are
//      panned from that channel's position when it does not. The LFE object
//      goes straight to the LFE channel.
//   4. Gains are ramped linearly across the frame from the previous frame's,
//      so motion and zone changes never step.
//
// Output channel order is the Android AudioFormat position-mask order (ascending
// mask bits: FL FR FC LFE BL BR SL SR TFL TFR TBL TBR TSL TSR FWL FWR), so the
// Kotlin side can hand the frame to an AudioTrack channel mask unchanged.
//
// Realtime contract: configure() allocates; render()/render_bed() do not.
// Header-only, C++17 standard library only.
#ifndef TF_ATMOS_SPEAKER_RENDERER_H
#define TF_ATMOS_SPEAKER_RENDERER_H

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "cavern/object_audio_metadata.h"  // cavern::ObjectState, ReferenceChannel
#include "vbap.h"                          // Vec3, direction_from_angles, dot, normalize

namespace tf {
namespace atmos {

// Crosses JNI as a plain int: must match Kotlin ChannelLayout.nativeId.
enum class OutputLayout : int {
  kStereo = 0, k5_1 = 1, k7_1 = 2, k5_1_2 = 3, k5_1_4 = 4, k7_1_2 = 5, k7_1_4 = 6,
  k9_1_4 = 7, k9_1_6 = 8,
};

// Speaker roles, used for bed-channel routing and the Table A.7 zone map.
enum class SpeakerRole : uint8_t {
  kL, kR, kC, kLfe, kLs, kRs,  // Ls/Rs: 5.x surrounds (Android BACK_LEFT/RIGHT)
  kLss, kRss,                  // 7.x side surrounds (SIDE_LEFT/RIGHT)
  kLrs, kRrs,                  // 7.x rear surrounds (BACK_LEFT/RIGHT)
  kLtf, kRtf, kLtr, kRtr,      // top front / top rear
  kLts, kRts,                  // top side ("top middle")
  kLw, kRw,                    // front wides
};

// Zone bits (TS 103 420 clause 5.2.6 / Table A.7).
enum ZoneBit : uint8_t {
  kZoneScreen = 1, kZoneSide = 2, kZoneSurround = 4, kZoneBack = 8, kZoneTop = 16,
};

struct SpeakerDef {
  SpeakerRole role;
  float azimuth;    // degrees, 0 = front, + = right
  float elevation;  // degrees above ear level
  uint8_t zones;
};

inline bool is_lfe(const SpeakerDef& s) { return s.role == SpeakerRole::kLfe; }

// The speakers of each layout, in Android channel-mask order.
inline std::vector<SpeakerDef> layout_speakers(OutputLayout layout) {
  using R = SpeakerRole;
  const SpeakerDef L{R::kL, -30, 0, kZoneScreen}, Rr{R::kR, 30, 0, kZoneScreen},
      C{R::kC, 0, 0, kZoneScreen}, LFE{R::kLfe, 0, 0, 0};
  // Table A.7 note 2: Ls/Rs count as Back in 5.x layouts, as Side otherwise.
  const SpeakerDef Ls5{R::kLs, -110, 0, kZoneSurround | kZoneBack},
      Rs5{R::kRs, 110, 0, kZoneSurround | kZoneBack};
  const SpeakerDef Lss{R::kLss, -90, 0, kZoneSurround | kZoneSide},
      Rss{R::kRss, 90, 0, kZoneSurround | kZoneSide};
  const SpeakerDef Lrs{R::kLrs, -150, 0, kZoneBack}, Rrs{R::kRrs, 150, 0, kZoneBack};
  const SpeakerDef Ltf{R::kLtf, -45, 45, kZoneTop}, Rtf{R::kRtf, 45, 45, kZoneTop},
      Ltr{R::kLtr, -135, 45, kZoneTop}, Rtr{R::kRtr, 135, 45, kZoneTop};
  // x.1.2 has a single top pair, placed overhead ("top middle"); in 9.1.6 it is
  // the middle of three rows, level with the others.
  const SpeakerDef Ltm{R::kLts, -90, 60, kZoneTop}, Rtm{R::kRts, 90, 60, kZoneTop};
  const SpeakerDef Lts{R::kLts, -90, 45, kZoneTop}, Rts{R::kRts, 90, 45, kZoneTop};
  const SpeakerDef Lw{R::kLw, -60, 0, kZoneSide}, Rw{R::kRw, 60, 0, kZoneSide};
  switch (layout) {
    case OutputLayout::kStereo: return {L, Rr};
    case OutputLayout::k5_1: return {L, Rr, C, LFE, Ls5, Rs5};
    case OutputLayout::k7_1: return {L, Rr, C, LFE, Lrs, Rrs, Lss, Rss};
    case OutputLayout::k5_1_2: return {L, Rr, C, LFE, Ls5, Rs5, Ltm, Rtm};
    case OutputLayout::k5_1_4: return {L, Rr, C, LFE, Ls5, Rs5, Ltf, Rtf, Ltr, Rtr};
    case OutputLayout::k7_1_2: return {L, Rr, C, LFE, Lrs, Rrs, Lss, Rss, Ltm, Rtm};
    case OutputLayout::k7_1_4: return {L, Rr, C, LFE, Lrs, Rrs, Lss, Rss, Ltf, Rtf, Ltr, Rtr};
    case OutputLayout::k9_1_4:
      return {L, Rr, C, LFE, Lrs, Rrs, Lss, Rss, Ltf, Rtf, Ltr, Rtr, Lw, Rw};
    case OutputLayout::k9_1_6:
      return {L, Rr, C, LFE, Lrs, Rrs, Lss, Rss, Ltf, Rtf, Ltr, Rtr, Lts, Rts, Lw, Rw};
  }
  return {L, Rr};
}

// OAMD room coordinates (x 0..1 left->right, y 0..1 front->back, z -1..1
// floor->ceiling) -> a unit direction in the vbap.h convention (+x right,
// +y front, +z up). See the header comment for the warp.
inline Vec3 room_to_direction(const Vec3& room) {
  const float dx = room.x - 0.5f, dy = 0.5f - room.y;
  // Chebyshev radius: 1 on every wall, so a point at the top of a wall and a
  // point at the top of a corner both sit at 45 degrees elevation.
  const float h = 2.0f * std::fmax(std::fabs(dx), std::fabs(dy));
  float az = std::atan2(dx, dy) * 180.0f / 3.14159265f;  // cube azimuth
  const float a = std::fabs(az);
  float w;
  if (a <= 45.0f) w = a * (30.0f / 45.0f);
  else if (a <= 90.0f) w = 30.0f + (a - 45.0f) * (60.0f / 45.0f);
  else if (a <= 135.0f) w = 90.0f + (a - 90.0f) * (60.0f / 45.0f);
  else w = 150.0f + (a - 135.0f) * (30.0f / 45.0f);
  az = az < 0.0f ? -w : w;
  // Below ear level is rendered at ear level: home layouts have no floor speakers.
  const float z = std::fmax(room.z, 0.0f);
  const float el = (h < 1e-6f && z < 1e-6f) ? 0.0f : std::atan2(z, h) * 180.0f / 3.14159265f;
  return direction_from_angles(az, el);
}

// Render space (x right, y up, z front; ObjectState::render) -> vbap.h convention.
inline Vec3 render_to_direction(const Vec3& r) { return normalize({r.x, r.z, r.y}); }

// VBAP over the convex hull of a speaker subset plus imaginary zenith/nadir.
class HullPanner {
 public:
  // `allowed` lists speaker indices (into `speakers`) this panner may use.
  void configure(const std::vector<SpeakerDef>& speakers, const std::vector<int>& allowed) {
    n_out_ = static_cast<int>(speakers.size());
    points_.clear();
    owner_.clear();
    tops_.clear();
    ears_.clear();
    for (int idx : allowed) {
      const SpeakerDef& s = speakers[idx];
      if (is_lfe(s)) continue;
      points_.push_back(direction_from_angles(s.azimuth, s.elevation));
      owner_.push_back(idx);
      (s.elevation > 1.0f ? tops_ : ears_).push_back(idx);
    }
    real_points_ = static_cast<int>(points_.size());
    // Imaginary zenith (-1) and nadir (-2): their gain is shared out below.
    points_.push_back({0.0f, 0.0f, 1.0f});
    owner_.push_back(-1);
    points_.push_back({0.0f, 0.0f, -1.0f});
    owner_.push_back(-2);
    build_hull();
    scratch_.assign(points_.size(), 0.0f);
  }

  // Unit-power gains for a point source, ADDED into `gains` (n_out entries)
  // scaled by `weight` in power terms. Allocation-free.
  void accumulate_power(const Vec3& dir, float weight, float* gains) const {
    if (real_points_ == 0 || weight <= 0.0f) return;
    const Vec3 p = normalize(dir);
    std::fill(scratch_.begin(), scratch_.end(), 0.0f);
    bool found = false;
    for (const Face& f : faces_) {  // sorted tightest first
      const float g0 = f.inv[0] * p.x + f.inv[1] * p.y + f.inv[2] * p.z;
      const float g1 = f.inv[3] * p.x + f.inv[4] * p.y + f.inv[5] * p.z;
      const float g2 = f.inv[6] * p.x + f.inv[7] * p.y + f.inv[8] * p.z;
      if (g0 < -kEps || g1 < -kEps || g2 < -kEps) continue;
      scratch_[f.v[0]] = std::fmax(g0, 0.0f);
      scratch_[f.v[1]] = std::fmax(g1, 0.0f);
      scratch_[f.v[2]] = std::fmax(g2, 0.0f);
      found = true;
      break;
    }
    if (!found) {
      // Only possible when the permitted speakers don't surround the listener
      // (e.g. "screen only"): pull toward the nearest permitted speakers.
      for (int i = 0; i < real_points_; ++i) {
        const float d = std::fmax(dot(points_[i], p), 0.0f);
        scratch_[i] = d * d * d * d + 1e-6f;
      }
    }
    // Share imaginary speakers' power out to the real ones.
    const int zi = real_points_, ni = real_points_ + 1;
    share(scratch_[zi], tops_.empty() ? ears_ : tops_);
    share(scratch_[ni], ears_.empty() ? tops_ : ears_);
    float power = 0.0f;
    for (int i = 0; i < real_points_; ++i) power += scratch_[i] * scratch_[i];
    if (power <= 1e-12f) return;
    const float scale = weight / power;
    for (int i = 0; i < real_points_; ++i) {
      gains[owner_[i]] += scratch_[i] * scratch_[i] * scale;
    }
  }

  // The permitted speaker nearest `dir` (channel lock / snap), or -1.
  int nearest(const Vec3& dir) const {
    const Vec3 p = normalize(dir);
    int best = -1;
    float best_dot = -2.0f;
    for (int i = 0; i < real_points_; ++i) {
      const float d = dot(points_[i], p);
      if (d > best_dot) { best_dot = d; best = owner_[i]; }
    }
    return best;
  }

  bool empty() const { return real_points_ == 0; }

 private:
  struct Face {
    int v[3];
    float inv[9];  // inverse of the column matrix [p0 p1 p2]
    float tightness;
  };
  static constexpr float kEps = 1e-4f;

  // Adds `g`'s power equally to `targets` (by index into points_ via owner_).
  void share(float g, const std::vector<int>& targets) const {
    if (g <= 0.0f || targets.empty()) return;
    const float each = g / std::sqrt(static_cast<float>(targets.size()));
    for (int t : targets) {
      for (int i = 0; i < real_points_; ++i) {
        if (owner_[i] == t) { scratch_[i] = std::sqrt(scratch_[i] * scratch_[i] + each * each); break; }
      }
    }
  }

  // Convex-hull faces by brute force: a triple is a face when every other point
  // lies on the inner side of its plane (coplanar points allowed, so a flat
  // quad yields both diagonals and panning picks the tighter triangle).
  void build_hull() {
    faces_.clear();
    const int n = static_cast<int>(points_.size());
    for (int i = 0; i < n; ++i) {
      for (int j = i + 1; j < n; ++j) {
        for (int k = j + 1; k < n; ++k) {
          const Vec3 &a = points_[i], &b = points_[j], &c = points_[k];
          const Vec3 ab{b.x - a.x, b.y - a.y, b.z - a.z}, ac{c.x - a.x, c.y - a.y, c.z - a.z};
          Vec3 nrm{ab.y * ac.z - ab.z * ac.y, ab.z * ac.x - ab.x * ac.z, ab.x * ac.y - ab.y * ac.x};
          const float len = std::sqrt(dot(nrm, nrm));
          if (len < 1e-6f) continue;
          nrm = {nrm.x / len, nrm.y / len, nrm.z / len};
          float off = dot(nrm, a);
          if (off < 0.0f) { nrm = {-nrm.x, -nrm.y, -nrm.z}; off = -off; }
          if (off < 1e-5f) continue;  // plane through the listener: not a face
          bool face = true;
          for (int m = 0; m < n && face; ++m) {
            if (m == i || m == j || m == k) continue;
            if (dot(nrm, points_[m]) > off + 1e-4f) face = false;
          }
          if (!face) continue;
          Face f{{i, j, k}, {}, widest_edge(a, b, c)};
          if (!invert(a, b, c, f.inv)) continue;
          faces_.push_back(f);
        }
      }
    }
    std::sort(faces_.begin(), faces_.end(),
              [](const Face& x, const Face& y) { return x.tightness < y.tightness; });
  }

  static float widest_edge(const Vec3& a, const Vec3& b, const Vec3& c) {
    auto ang = [](const Vec3& u, const Vec3& v) { return std::acos(std::fmin(1.0f, std::fmax(-1.0f, dot(u, v)))); };
    return std::fmax(ang(a, b), std::fmax(ang(b, c), ang(c, a)));
  }

  // Inverse of the matrix whose columns are a, b, c.
  static bool invert(const Vec3& a, const Vec3& b, const Vec3& c, float* out) {
    const float m[9] = {a.x, b.x, c.x, a.y, b.y, c.y, a.z, b.z, c.z};
    const float det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                      m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::fabs(det) < 1e-9f) return false;
    const float id = 1.0f / det;
    out[0] = (m[4] * m[8] - m[5] * m[7]) * id;
    out[1] = (m[2] * m[7] - m[1] * m[8]) * id;
    out[2] = (m[1] * m[5] - m[2] * m[4]) * id;
    out[3] = (m[5] * m[6] - m[3] * m[8]) * id;
    out[4] = (m[0] * m[8] - m[2] * m[6]) * id;
    out[5] = (m[2] * m[3] - m[0] * m[5]) * id;
    out[6] = (m[3] * m[7] - m[4] * m[6]) * id;
    out[7] = (m[1] * m[6] - m[0] * m[7]) * id;
    out[8] = (m[0] * m[4] - m[1] * m[3]) * id;
    return true;
  }

  int n_out_ = 0;
  int real_points_ = 0;
  std::vector<Vec3> points_;
  std::vector<int> owner_;  // speaker index per point; -1 zenith, -2 nadir
  std::vector<int> tops_, ears_;
  std::vector<Face> faces_;
  mutable std::vector<float> scratch_;
};

class SpeakerRenderer {
 public:
  static constexpr int kMaxObjects = 128;
  static constexpr int kZoneVariants = 16;  // zone_constraints_idx (0..7) x elevation

  void configure(OutputLayout layout, int max_objects) {
    layout_ = layout;
    speakers_ = layout_speakers(layout);
    channels_ = static_cast<int>(speakers_.size());
    lfe_ = -1;
    for (int i = 0; i < channels_; ++i) if (is_lfe(speakers_[i])) lfe_ = i;
    for (int v = 0; v < kZoneVariants; ++v) {
      zone_panners_[v].configure(speakers_, allowed_for(v / 2, (v & 1) != 0));
      if (zone_panners_[v].empty()) zone_panners_[v].configure(speakers_, all_speakers());
    }
    const int objs = std::min(std::max(max_objects, 1), kMaxObjects);
    prev_gains_.assign(static_cast<size_t>(objs) * channels_, 0.0f);
    next_gains_.assign(channels_, 0.0f);
    has_prev_.assign(objs, 0);
    bed_prev_.assign(static_cast<size_t>(8) * channels_, 0.0f);
    bed_has_prev_ = false;
  }

  int channels() const { return channels_; }
  OutputLayout layout() const { return layout_; }
  int lfe_channel() const { return lfe_; }

  // Forgets gain history (seek / flush): the next frame starts at its target.
  void reset_history() {
    std::fill(has_prev_.begin(), has_prev_.end(), 0);
    bed_has_prev_ = false;
  }

  // Target gains (amplitude, one per output channel) for one object state.
  // Public for tests. Allocation-free.
  void object_gains(const cavern::ObjectState& st, bool is_lfe_object, float* gains) const {
    std::fill(gains, gains + channels_, 0.0f);
    if (is_lfe_object) {
      if (lfe_ >= 0) gains[lfe_] = 1.0f;
      return;
    }
    const HullPanner& all = zone_panners_[1];  // idx 0, elevation enabled = everything
    if (st.is_bed) {
      const int direct = channel_for(st.bed_channel);
      if (direct >= 0) { gains[direct] = 1.0f; return; }
      all.accumulate_power(render_to_direction(st.render), 1.0f, gains);
      to_amplitude(gains);
      return;
    }
    if (st.is_isf) {
      all.accumulate_power(render_to_direction(st.render), 1.0f, gains);
      to_amplitude(gains);
      return;
    }
    int zone = st.zone_constraints_idx;
    if (zone < 0 || zone > 5) zone = 0;  // 6, 7 reserved: no constraint
    const HullPanner& panner = zone_panners_[zone * 2 + (st.enable_elevation ? 1 : 0)];
    if (st.snap) {
      const int ch = panner.nearest(room_to_direction(st.room));
      if (ch >= 0) gains[ch] = 1.0f;
      return;
    }
    // Divergence: centre + two sources along X (power-preserving split).
    const float v = std::fmin(std::fmax(st.divergence, 0.0f), 1.0f);
    const float pc = (1.0f - v) / (1.0f + v), ps = v / (1.0f + v);
    spread(panner, st, st.room, pc, gains);
    if (ps > 0.0f) {
      const float off = 0.5f * v;
      spread(panner, st, {std::fmax(0.0f, st.room.x - off), st.room.y, st.room.z}, ps, gains);
      spread(panner, st, {std::fmin(1.0f, st.room.x + off), st.room.y, st.room.z}, ps, gains);
    }
    to_amplitude(gains);
  }

  // Renders `count` objects into `out` (interleaved, channels() wide,
  // `samples` frames), OVERWRITING it. `pcm[o]` is object o's samples,
  // `states[o]` its OAMD state (null = silent), `lfe_index` the LFE object.
  void render(const float* const* pcm, const cavern::ObjectState* const* states, int count,
              int lfe_index, float lfe_gain, int samples, float* out) {
    std::fill(out, out + static_cast<size_t>(samples) * channels_, 0.0f);
    const int objs = std::min(count, static_cast<int>(has_prev_.size()));
    const float inv = 1.0f / static_cast<float>(samples);
    for (int o = 0; o < objs; ++o) {
      const float* x = pcm[o];
      if (x == nullptr || states[o] == nullptr) continue;
      const bool lfe_obj = o == lfe_index;
      object_gains(*states[o], lfe_obj, next_gains_.data());
      if (lfe_obj) for (float& g : next_gains_) g *= lfe_gain;
      float* prev = &prev_gains_[static_cast<size_t>(o) * channels_];
      if (!has_prev_[o]) { std::copy(next_gains_.begin(), next_gains_.end(), prev); has_prev_[o] = 1; }
      mix_ramped(x, prev, next_gains_.data(), samples, inv, out);
      std::copy(next_gains_.begin(), next_gains_.end(), prev);
    }
  }

  // Plays a decoded channel bed (decoder order FL FR FC LFE SL SR BL BR) on the
  // layout: each bed channel to its own speaker when present, panned from its
  // nominal position when not. ADDS into `out`. The frames-without-JOC path.
  void render_bed(const float* const* bed, int bed_channels, float lfe_gain, int samples, float* out) {
    const int ch = std::min(bed_channels, 8);
    const float inv = 1.0f / static_cast<float>(samples);
    for (int c = 0; c < ch; ++c) {
      bed_channel_gains(c, bed_channels, next_gains_.data());
      if (c == 3 && bed_channels >= 4) for (float& g : next_gains_) g *= lfe_gain;
      float* prev = &bed_prev_[static_cast<size_t>(c) * channels_];
      if (!bed_has_prev_) std::copy(next_gains_.begin(), next_gains_.end(), prev);
      mix_ramped(bed[c], prev, next_gains_.data(), samples, inv, out);
      std::copy(next_gains_.begin(), next_gains_.end(), prev);
    }
    bed_has_prev_ = true;
  }

  // Gains for decoded bed channel `c` of a `bed_channels`-wide bed. Public for tests.
  void bed_channel_gains(int c, int bed_channels, float* gains) const {
    using cavern::ReferenceChannel;
    std::fill(gains, gains + channels_, 0.0f);
    static const ReferenceChannel k71[8] = {
        ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontRight, ReferenceChannel::kFrontCenter,
        ReferenceChannel::kScreenLFE, ReferenceChannel::kSideLeft,   ReferenceChannel::kSideRight,
        ReferenceChannel::kRearLeft,  ReferenceChannel::kRearRight};
    if (c == 3 && bed_channels >= 4) { if (lfe_ >= 0) gains[lfe_] = 1.0f; return; }
    // A 5.1 bed's surrounds sit at +-110 degrees; a 7.1 bed's SL/SR are sides.
    const bool bed51 = bed_channels < 8;
    if (bed51 && (c == 4 || c == 5)) {
      const SpeakerRole want = c == 4 ? SpeakerRole::kLs : SpeakerRole::kRs;
      for (int i = 0; i < channels_; ++i) if (speakers_[i].role == want) { gains[i] = 1.0f; return; }
      zone_panners_[1].accumulate_power(direction_from_angles(c == 4 ? -110.0f : 110.0f, 0.0f), 1.0f, gains);
      to_amplitude(gains);
      return;
    }
    const int direct = channel_for(k71[c]);
    if (direct >= 0) { gains[direct] = 1.0f; return; }
    const Vec3 r = cavern::ObjectAudioMetadata::bed_render_position(k71[c]);
    zone_panners_[1].accumulate_power(render_to_direction(r), 1.0f, gains);
    to_amplitude(gains);
  }

  // Output channel carrying a bed channel, or -1 when the layout lacks it.
  int channel_for(cavern::ReferenceChannel ch) const {
    using cavern::ReferenceChannel;
    auto find = [&](SpeakerRole role) {
      for (int i = 0; i < channels_; ++i) if (speakers_[i].role == role) return i;
      return -1;
    };
    switch (ch) {
      case ReferenceChannel::kFrontLeft: return find(SpeakerRole::kL);
      case ReferenceChannel::kFrontRight: return find(SpeakerRole::kR);
      case ReferenceChannel::kFrontCenter: return find(SpeakerRole::kC);
      case ReferenceChannel::kScreenLFE: return find(SpeakerRole::kLfe);
      case ReferenceChannel::kSideLeft: { int i = find(SpeakerRole::kLss); return i >= 0 ? i : find(SpeakerRole::kLs); }
      case ReferenceChannel::kSideRight: { int i = find(SpeakerRole::kRss); return i >= 0 ? i : find(SpeakerRole::kRs); }
      case ReferenceChannel::kRearLeft: return find(SpeakerRole::kLrs);
      case ReferenceChannel::kRearRight: return find(SpeakerRole::kRrs);
      case ReferenceChannel::kTopFrontLeft: return find(SpeakerRole::kLtf);
      case ReferenceChannel::kTopFrontRight: return find(SpeakerRole::kRtf);
      case ReferenceChannel::kTopSideLeft: return find(SpeakerRole::kLts);
      case ReferenceChannel::kTopSideRight: return find(SpeakerRole::kRts);
      case ReferenceChannel::kTopRearLeft: return find(SpeakerRole::kLtr);
      case ReferenceChannel::kTopRearRight: return find(SpeakerRole::kRtr);
      case ReferenceChannel::kWideLeft: return find(SpeakerRole::kLw);
      case ReferenceChannel::kWideRight: return find(SpeakerRole::kRw);
      default: return -1;
    }
  }

 private:
  std::vector<int> all_speakers() const {
    std::vector<int> v;
    for (int i = 0; i < channels_; ++i) v.push_back(i);
    return v;
  }

  // Table 20 (horizontal zones) and Table 21 (Top-Bottom zone). "Excluded"
  // constraints drop every speaker in that zone; "only X included" keeps the
  // speakers in X. Top speakers follow b_enable_elevation alone.
  std::vector<int> allowed_for(int zone_idx, bool elevation) const {
    std::vector<int> v;
    for (int i = 0; i < channels_; ++i) {
      const SpeakerDef& s = speakers_[i];
      if (is_lfe(s)) continue;
      const bool top = (s.zones & kZoneTop) != 0;
      bool ok;
      if (top) {
        ok = elevation;
      } else {
        switch (zone_idx) {
          case 1: ok = (s.zones & kZoneBack) == 0; break;
          case 2: ok = (s.zones & kZoneSide) == 0; break;
          case 3: ok = (s.zones & kZoneBack) != 0 || s.role == SpeakerRole::kC; break;
          case 4: ok = (s.zones & kZoneScreen) != 0; break;
          case 5: ok = (s.zones & kZoneSurround) != 0; break;
          default: ok = true; break;
        }
      }
      if (ok) v.push_back(i);
    }
    return v;
  }

  // Samples the object's box (plus an implicit spread for interior positions,
  // which a room-cube mix intends to be heard "inside" the speaker array) and
  // power-sums the panned gains, scaled by `weight`.
  void spread(const HullPanner& panner, const cavern::ObjectState& st, const Vec3& centre,
              float weight, float* gains) const {
    if (weight <= 0.0f) return;
    const float edge = std::fmax(std::fabs(centre.x - 0.5f), std::fabs(centre.y - 0.5f)) * 2.0f;
    const float interior = std::fmax(0.0f, 1.0f - std::fmax(edge, std::fabs(centre.z)));
    const float w = std::fmin(1.0f, st.width + interior * 0.5f);
    const float d = std::fmin(1.0f, st.depth + interior * 0.5f);
    const float h = std::fmin(1.0f, st.height + interior * 0.5f);
    const int nx = w > 1e-3f ? 3 : 1, ny = d > 1e-3f ? 3 : 1, nz = h > 1e-3f ? 3 : 1;
    const float each = weight / static_cast<float>(nx * ny * nz);
    for (int ix = 0; ix < nx; ++ix) {
      for (int iy = 0; iy < ny; ++iy) {
        for (int iz = 0; iz < nz; ++iz) {
          const float ox = nx == 1 ? 0.0f : (ix - 1) * w * 0.5f;
          const float oy = ny == 1 ? 0.0f : (iy - 1) * d * 0.5f;
          const float oz = nz == 1 ? 0.0f : (iz - 1) * h;  // z spans -1..1
          const Vec3 p{std::fmin(1.0f, std::fmax(0.0f, centre.x + ox)),
                       std::fmin(1.0f, std::fmax(0.0f, centre.y + oy)),
                       std::fmin(1.0f, std::fmax(-1.0f, centre.z + oz))};
          panner.accumulate_power(room_to_direction(p), each, gains);
        }
      }
    }
  }

  void to_amplitude(float* gains) const {
    for (int i = 0; i < channels_; ++i) gains[i] = std::sqrt(std::fmax(gains[i], 0.0f));
  }

  void mix_ramped(const float* x, const float* g0, const float* g1, int samples, float inv,
                  float* out) const {
    for (int c = 0; c < channels_; ++c) {
      const float a = g0[c], b = g1[c];
      if (a == 0.0f && b == 0.0f) continue;
      const float step = (b - a) * inv;
      float g = a;
      float* o = out + c;
      for (int i = 0; i < samples; ++i) {
        o[static_cast<size_t>(i) * channels_] += x[i] * g;
        g += step;
      }
    }
  }

  OutputLayout layout_ = OutputLayout::k5_1;
  std::vector<SpeakerDef> speakers_;
  int channels_ = 0;
  int lfe_ = -1;
  std::array<HullPanner, kZoneVariants> zone_panners_;
  std::vector<float> prev_gains_;  // [object][channel]
  std::vector<float> next_gains_;  // [channel] scratch
  std::vector<uint8_t> has_prev_;
  std::vector<float> bed_prev_;    // [bed channel][channel]
  bool bed_has_prev_ = false;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_SPEAKER_RENDERER_H

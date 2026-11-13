// Clean-room loudspeaker renderer for Atmos objects: the multichannel
// counterpart of render/hrir_renderer.h (which renders to binaural stereo).
//
// Objects arrive as reconstructed PCM (ObjectEngine) plus their resolved OAMD
// state (cavern::ObjectState) and leave as one interleaved PCM frame for a
// physical speaker layout — 5.1 up to 9.1.6. Per object and frame:
//
//   1. Panning. A balance along each axis of the room between the speakers
//      either side of the object (BalancePanner): by y between rows of
//      speakers, by x inside a row, by z between the ear-level layer and the
//      ones above. It is the law the stream's own 5.1 core follows, measured
//      (README): every 5.1 speaker of the bundled clip tracks the core at
//      0.999 and within 0.1 dB. Speakers stand at room coordinates
//      (layout_speakers), not at angles; a layout with nothing overhead plays
//      an object at its place on the floor.
//   2. Timing. An update takes hold when its metadata says and is reached
//      over its ramp duration (render()'s `delay` and `ramp`).
//   3. OAMD render properties (ETSI TS 103 420 clause 5.2):
//        - size (width/depth/height): the source is sampled over its box and the
//          gains power-summed, so a room-sized object plays from everywhere;
//        - divergence: a power-preserving split into a centre and two side
//          sources along X (BS.2127-style: (1-v)/(1+v) and v/(1+v) each);
//        - zone constraints (Table 20/21 + the speaker->zone map of Table A.7):
//          each constraint gets its own panner over only the permitted speakers;
//        - channel lock (snap): the whole object goes to the nearest speaker.
//      Bed objects play from their own channel when the layout has it, and are
//      panned from that channel's position when it does not. The LFE object
//      goes straight to the LFE channel.
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
  // Not a physical layout: the virtual room the headphone render pans onto
  // before its binaural stage (render/virtual_room.h). Never an AudioTrack's.
  k15_1_12 = 9,
};

// Speaker roles, used for bed-channel routing and the Table A.7 zone map.
enum class SpeakerRole : uint8_t {
  kL, kR, kC, kLfe, kLs, kRs,  // Ls/Rs: 5.x surrounds (Android BACK_LEFT/RIGHT)
  kLss, kRss,                  // 7.x side surrounds (SIDE_LEFT/RIGHT)
  kLrs, kRrs,                  // 7.x rear surrounds (BACK_LEFT/RIGHT)
  kLtf, kRtf, kLtr, kRtr,      // top front / top rear
  kLts, kRts,                  // top side ("top middle")
  kLw, kRw,                    // front wides
  kOther,                      // a virtual-room speaker no bed channel names
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
  // Where it stands in the room objects are mixed in (clause 4.2.1): x 0..1
  // left to right, y 0..1 front to back, z 0 at ear level, 1 at the ceiling.
  float x = 0.5f, y = 0.5f, z = 0.0f;
};

inline SpeakerDef placed(SpeakerDef s, float x, float y, float z = 0.0f) {
  s.x = x; s.y = y; s.z = z;
  return s;
}

inline bool is_lfe(const SpeakerDef& s) { return s.role == SpeakerRole::kLfe; }

// The speakers of each layout, in Android channel-mask order.
inline std::vector<SpeakerDef> layout_speakers(OutputLayout layout) {
  using R = SpeakerRole;
  // Room places: the screen wall is y = 0 with L, C, R across it, the side
  // surrounds half way back, the rears (and a 5.x layout's surrounds) on the
  // back wall, the wides a quarter back, and the top speakers a quarter in
  // from each side wall.
  const SpeakerDef L = placed({R::kL, -30, 0, kZoneScreen}, 0, 0),
      Rr = placed({R::kR, 30, 0, kZoneScreen}, 1, 0),
      C = placed({R::kC, 0, 0, kZoneScreen}, 0.5f, 0), LFE{R::kLfe, 0, 0, 0};
  // Table A.7 note 2: Ls/Rs count as Back in 5.x layouts, as Side otherwise.
  const SpeakerDef Ls5 = placed({R::kLs, -110, 0, kZoneSurround | kZoneBack}, 0, 1),
      Rs5 = placed({R::kRs, 110, 0, kZoneSurround | kZoneBack}, 1, 1);
  const SpeakerDef Lss = placed({R::kLss, -90, 0, kZoneSurround | kZoneSide}, 0, 0.5f),
      Rss = placed({R::kRss, 90, 0, kZoneSurround | kZoneSide}, 1, 0.5f);
  const SpeakerDef Lrs = placed({R::kLrs, -150, 0, kZoneBack}, 0, 1),
      Rrs = placed({R::kRrs, 150, 0, kZoneBack}, 1, 1);
  const SpeakerDef Ltf = placed({R::kLtf, -45, 45, kZoneTop}, 0.25f, 0.25f, 1),
      Rtf = placed({R::kRtf, 45, 45, kZoneTop}, 0.75f, 0.25f, 1),
      Ltr = placed({R::kLtr, -135, 45, kZoneTop}, 0.25f, 0.75f, 1),
      Rtr = placed({R::kRtr, 135, 45, kZoneTop}, 0.75f, 0.75f, 1);
  // x.1.2 has a single top pair, placed overhead ("top middle"); in 9.1.6 it is
  // the middle of three rows, level with the others.
  const SpeakerDef Ltm = placed({R::kLts, -90, 60, kZoneTop}, 0.25f, 0.5f, 1),
      Rtm = placed({R::kRts, 90, 60, kZoneTop}, 0.75f, 0.5f, 1);
  const SpeakerDef Lts = placed({R::kLts, -90, 45, kZoneTop}, 0.25f, 0.5f, 1),
      Rts = placed({R::kRts, 90, 45, kZoneTop}, 0.75f, 0.5f, 1);
  const SpeakerDef Lw = placed({R::kLw, -60, 0, kZoneSide}, 0, 0.25f),
      Rw = placed({R::kRw, 60, 0, kZoneSide}, 1, 0.25f);
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
    case OutputLayout::k15_1_12: {
      // Fifteen at ear level, closest together ahead, where hearing places
      // sounds most exactly; a lower ring of eight at 30 degrees up and an
      // upper ring of four at 60. The speakers a bed channel names keep its
      // role, so a 5.1 or 7.1 bed plays from speakers of its own: L/R at 30,
      // the wides at 60, side surrounds at 90, a 5.1 bed's surrounds at 110
      // and the rears at 135.
      // In the room: the ear-level ring round the walls (15 and 160 degrees a
      // quarter in along the front and back walls), the lower height ring on
      // the walls half way up, the upper ring a quarter in at the ceiling.
      auto ear = [](R role, float az, uint8_t zones, float x, float y) {
        return placed(SpeakerDef{role, az, 0, zones}, x, y, 0);
      };
      auto top = [](float az, float el, float x, float y, float z) {
        return placed(SpeakerDef{R::kOther, az, el, kZoneTop}, x, y, z);
      };
      const uint8_t side = kZoneSurround | kZoneSide;
      return {C, ear(R::kOther, -15, kZoneScreen, 0.25f, 0), ear(R::kOther, 15, kZoneScreen, 0.75f, 0),
              L, Rr,
              ear(R::kLw, -60, kZoneSide, 0, 0.25f), ear(R::kRw, 60, kZoneSide, 1, 0.25f),
              ear(R::kLss, -90, side, 0, 0.5f), ear(R::kRss, 90, side, 1, 0.5f),
              ear(R::kLs, -110, side, 0, 0.75f), ear(R::kRs, 110, side, 1, 0.75f),
              ear(R::kLrs, -135, kZoneBack, 0, 1), ear(R::kRrs, 135, kZoneBack, 1, 1),
              ear(R::kOther, -160, kZoneBack, 0.25f, 1), ear(R::kOther, 160, kZoneBack, 0.75f, 1),
              LFE,
              top(-30, 30, 0, 0, 0.5f), top(30, 30, 1, 0, 0.5f),
              top(-70, 30, 0, 1.0f / 3, 0.5f), top(70, 30, 1, 1.0f / 3, 0.5f),
              top(-110, 30, 0, 2.0f / 3, 0.5f), top(110, 30, 1, 2.0f / 3, 0.5f),
              top(-150, 30, 0, 1, 0.5f), top(150, 30, 1, 1, 0.5f),
              top(-45, 60, 0.25f, 0.25f, 1), top(45, 60, 0.75f, 0.25f, 1),
              top(-135, 60, 0.25f, 0.75f, 1), top(135, 60, 0.75f, 0.75f, 1)};
    }
  }
  return {L, Rr};
}

// Render space (x right, y up, z front) -> room coordinates.
inline Vec3 render_to_room(const Vec3& r) {
  return {(r.x + 1.0f) * 0.5f, (1.0f - r.z) * 0.5f, r.y};
}

// Where a bed channel's speaker stands in the room, for layouts without it:
// the same places layout_speakers() gives the speakers themselves.
inline Vec3 bed_channel_room(cavern::ReferenceChannel ch) {
  using cavern::ReferenceChannel;
  switch (ch) {
    case ReferenceChannel::kFrontLeft: return {0.0f, 0.0f, 0.0f};
    case ReferenceChannel::kFrontRight: return {1.0f, 0.0f, 0.0f};
    case ReferenceChannel::kFrontCenter: return {0.5f, 0.0f, 0.0f};
    case ReferenceChannel::kWideLeft: return {0.0f, 0.25f, 0.0f};
    case ReferenceChannel::kWideRight: return {1.0f, 0.25f, 0.0f};
    case ReferenceChannel::kSideLeft: return {0.0f, 0.5f, 0.0f};
    case ReferenceChannel::kSideRight: return {1.0f, 0.5f, 0.0f};
    case ReferenceChannel::kRearLeft: return {0.0f, 1.0f, 0.0f};
    case ReferenceChannel::kRearRight: return {1.0f, 1.0f, 0.0f};
    case ReferenceChannel::kTopFrontLeft: return {0.25f, 0.25f, 1.0f};
    case ReferenceChannel::kTopFrontRight: return {0.75f, 0.25f, 1.0f};
    case ReferenceChannel::kTopSideLeft: return {0.25f, 0.5f, 1.0f};
    case ReferenceChannel::kTopSideRight: return {0.75f, 0.5f, 1.0f};
    case ReferenceChannel::kTopRearLeft: return {0.25f, 0.75f, 1.0f};
    case ReferenceChannel::kTopRearRight: return {0.75f, 0.75f, 1.0f};
    default: return render_to_room(cavern::ObjectAudioMetadata::bed_render_position(ch));
  }
}

// The room panner: a balance along each axis of the room, between the
// speakers standing either side of the object.
//
// This is what the stream's own 5.1 is: with the trims off, Dolby's encoder
// renders an object to its core as a sin/cos balance between the front row and
// the back row by y, and inside a row between the speakers either side by x,
// to +-0.01 at every static position measured (README, "Real-content
// reference check"). Height is the same again between layers, and a layout
// with no speakers overhead plays an object at its place on the floor.
//
// Speakers are grouped into layers by z, rows by y inside a layer, and sorted
// by x inside a row. Outside the outermost speaker of an axis the object is at
// that speaker. Power adds to 1 at every point of the room.
class BalancePanner {
 public:
  // `allowed` lists speaker indices (into `speakers`) this panner may use.
  void configure(const std::vector<SpeakerDef>& speakers, const std::vector<int>& allowed) {
    points_.clear();
    layers_.clear();
    for (int idx : allowed) {
      const SpeakerDef& s = speakers[idx];
      if (is_lfe(s)) continue;
      points_.push_back({idx, s.x, s.y, s.z});
    }
    std::vector<Point> sorted = points_;
    std::sort(sorted.begin(), sorted.end(), [](const Point& a, const Point& b) {
      if (std::fabs(a.z - b.z) > kSame) return a.z < b.z;
      if (std::fabs(a.y - b.y) > kSame) return a.y < b.y;
      return a.x < b.x;
    });
    for (const Point& p : sorted) {
      if (layers_.empty() || std::fabs(layers_.back().z - p.z) > kSame) layers_.push_back({p.z, {}});
      std::vector<Row>& rows = layers_.back().rows;
      if (rows.empty() || std::fabs(rows.back().y - p.y) > kSame) rows.push_back({p.y, {}});
      rows.back().speakers.push_back({p.x, p.index});
    }
  }

  // Unit-power gains for a point of the room, ADDED into `gains` (one entry a
  // speaker of the layout) as power scaled by `weight`. Allocation-free.
  void accumulate_power(const Vec3& room, float weight, float* gains) const {
    if (layers_.empty() || weight <= 0.0f) return;
    int la, lb;
    float lt;
    bracket(layers_, room.z, [](const Layer& l) { return l.z; }, la, lb, lt);
    add_layer(layers_[la], room, weight * power_a(lt), gains);
    if (lb != la) add_layer(layers_[lb], room, weight * power_b(lt), gains);
  }

  // The permitted speaker nearest a point of the room (channel lock), or -1.
  int nearest(const Vec3& room) const {
    int best = -1;
    float best_d = 1e9f;
    for (const Point& p : points_) {
      const float dx = p.x - room.x, dy = p.y - room.y, dz = p.z - std::fmax(room.z, 0.0f);
      const float d = dx * dx + dy * dy + dz * dz;
      if (d < best_d) { best_d = d; best = p.index; }
    }
    return best;
  }

  bool empty() const { return points_.empty(); }

 private:
  struct Point { int index; float x, y, z; };
  struct Spot { float x; int index; };
  struct Row { float y; std::vector<Spot> speakers; };
  struct Layer { float z; std::vector<Row> rows; };
  static constexpr float kSame = 0.01f;
  static constexpr float kHalfPi = 1.57079632679f;

  // The power a balance at `t` (0 at the first of a pair, 1 at the second)
  // gives each: the squares of cos and sin.
  static float power_a(float t) { const float c = std::cos(t * kHalfPi); return c * c; }
  static float power_b(float t) { const float v = std::sin(t * kHalfPi); return v * v; }

  // The two neighbours of `v` along an axis and how far `v` is from the first
  // to the second; both the same one at or beyond an end.
  template <typename T, typename Key>
  static void bracket(const std::vector<T>& items, float v, Key key, int& a, int& b, float& t) {
    const int n = static_cast<int>(items.size());
    a = b = 0;
    t = 0.0f;
    if (n == 1 || v <= key(items[0])) return;
    if (v >= key(items[n - 1])) { a = b = n - 1; return; }
    for (int i = 0; i + 1 < n; ++i) {
      const float lo = key(items[i]), hi = key(items[i + 1]);
      if (v < hi) { a = i; b = i + 1; t = (v - lo) / (hi - lo); return; }
    }
    a = b = n - 1;
  }

  static void add_row(const Row& row, float x, float power, float* gains) {
    int a, b;
    float t;
    bracket(row.speakers, x, [](const Spot& s) { return s.x; }, a, b, t);
    if (a == b) { gains[row.speakers[a].index] += power; return; }
    gains[row.speakers[a].index] += power * power_a(t);
    gains[row.speakers[b].index] += power * power_b(t);
  }

  static void add_layer(const Layer& layer, const Vec3& room, float power, float* gains) {
    int a, b;
    float t;
    bracket(layer.rows, room.y, [](const Row& r) { return r.y; }, a, b, t);
    if (a == b) { add_row(layer.rows[a], room.x, power, gains); return; }
    add_row(layer.rows[a], room.x, power * power_a(t), gains);
    add_row(layer.rows[b], room.x, power * power_b(t), gains);
  }

  std::vector<Point> points_;
  std::vector<Layer> layers_;
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
    max_objects_ = objs;
    const size_t width = static_cast<size_t>(objs) * channels_;
    ramp_from_.assign(width, 0.0f);
    ramp_to_.assign(width, 0.0f);
    for (Event& e : events_) e.gains.assign(width, 0.0f);
    event_first_ = event_count_ = 0;
    has_state_ = false;
    next_gains_.assign(channels_, 0.0f);
    bed_prev_.assign(static_cast<size_t>(8) * channels_, 0.0f);
    bed_has_prev_ = false;
  }

  int channels() const { return channels_; }
  OutputLayout layout() const { return layout_; }
  int lfe_channel() const { return lfe_; }

  // Forgets gain history (seek / flush): the next frame starts at its target.
  void reset_history() {
    has_state_ = false;
    event_first_ = event_count_ = 0;
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
    const BalancePanner& all = zone_panners_[1];  // idx 0, elevation enabled = everything
    if (st.is_bed) {
      // A bed channel the layout has plays from it; one it lacks is panned
      // from that channel's place in the room, like an object standing there.
      const int direct = channel_for(st.bed_channel);
      if (direct >= 0) { gains[direct] = 1.0f; return; }
      all.accumulate_power(bed_channel_room(st.bed_channel), 1.0f, gains);
      to_amplitude(gains);
      return;
    }
    if (st.is_isf) {
      all.accumulate_power(render_to_room(st.render), 1.0f, gains);
      to_amplitude(gains);
      return;
    }
    int zone = st.zone_constraints_idx;
    if (zone < 0 || zone > 5) zone = 0;  // 6, 7 reserved: no constraint
    const BalancePanner& panner = zone_panners_[zone * 2 + (st.enable_elevation ? 1 : 0)];
    if (st.snap) {
      const int ch = panner.nearest(st.room);
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
  //
  // The states are where the objects are going, not where they are: they
  // take hold `delay` samples from the start of this frame and are reached
  // over `ramp` samples (0 is a cut), as the metadata's own timing says
  // (clause 5.6.2) plus however late the audio is by then. Until then the
  // gains go on as they were. `delay` may be longer than a frame.
  void render(const float* const* pcm, const cavern::ObjectState* const* states, int count,
              int lfe_index, float lfe_gain, int samples, float* out, int delay = 0,
              int ramp = -1) {
    std::fill(out, out + static_cast<size_t>(samples) * channels_, 0.0f);
    const int objs = std::min(count, max_objects_);
    const size_t width = static_cast<size_t>(max_objects_) * channels_;
    // Where every object is going.
    Event* ev = nullptr;
    if (!has_state_) {
      target_ = &ramp_to_;
    } else {
      if (event_count_ == kEvents) begin(samples);  // never in practice: the oldest takes hold now
      ev = &events_[(event_first_ + event_count_) % kEvents];
      target_ = &ev->gains;
    }
    std::fill(target_->begin(), target_->end(), 0.0f);
    for (int o = 0; o < objs; ++o) {
      if (pcm[o] == nullptr || states[o] == nullptr) continue;
      float* g = target_->data() + static_cast<size_t>(o) * channels_;
      const bool lfe_obj = o == lfe_index;
      object_gains(*states[o], lfe_obj, g);
      if (lfe_obj) for (int c = 0; c < channels_; ++c) g[c] *= lfe_gain;
    }
    if (!has_state_) {
      // Nothing to come from: start there.
      std::copy(ramp_to_.begin(), ramp_to_.begin() + width, ramp_from_.begin());
      ramp_pos_ = ramp_len_ = 1;
      has_state_ = true;
    } else {
      ev->wait = delay < 0 ? 0 : delay;
      ev->ramp = ramp < 0 ? samples : ramp;
      ++event_count_;
    }

    int t = 0;
    while (t < samples) {
      while (event_count_ > 0 && events_[event_first_].wait <= 0) begin(samples);
      int seg = samples - t;
      if (event_count_ > 0 && events_[event_first_].wait < seg) seg = events_[event_first_].wait;
      if (ramp_pos_ < ramp_len_ && ramp_len_ - ramp_pos_ < seg) seg = ramp_len_ - ramp_pos_;
      const float w0 = weight(ramp_pos_), w1 = weight(ramp_pos_ + seg);
      const float inv = 1.0f / static_cast<float>(seg);
      for (int o = 0; o < objs; ++o) {
        const float* x = pcm[o];
        if (x == nullptr) continue;
        const float* from = &ramp_from_[static_cast<size_t>(o) * channels_];
        const float* to = &ramp_to_[static_cast<size_t>(o) * channels_];
        for (int c = 0; c < channels_; ++c) {
          const float a = from[c] + (to[c] - from[c]) * w0;
          const float b = from[c] + (to[c] - from[c]) * w1;
          if (a == 0.0f && b == 0.0f) continue;
          const float step = (b - a) * inv;
          float g = a;
          float* dst = out + static_cast<size_t>(t) * channels_ + c;
          for (int i = 0; i < seg; ++i) {
            dst[static_cast<size_t>(i) * channels_] += x[t + i] * g;
            g += step;
          }
        }
      }
      ramp_pos_ = std::min(ramp_pos_ + seg, ramp_len_);
      for (int k = 0; k < event_count_; ++k) events_[(event_first_ + k) % kEvents].wait -= seg;
      t += seg;
    }
  }

  // Plays a decoded channel bed on the layout: each bed channel to its own
  // speaker when present, panned from its nominal position when not. ADDS
  // into `out`. The frames-without-JOC path. Decoder order: FL FR FC LFE SL SR
  // for 5.1, and FL FR FC LFE BL BR SL SR for 7.1, the rears before the sides
  // (FFmpeg's order and Android's; this had them the other way round, and a
  // 7.1 bed's side and rear surrounds changed places).
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
        ReferenceChannel::kScreenLFE, ReferenceChannel::kRearLeft,   ReferenceChannel::kRearRight,
        ReferenceChannel::kSideLeft,  ReferenceChannel::kSideRight};
    if (c == 3 && bed_channels >= 4) { if (lfe_ >= 0) gains[lfe_] = 1.0f; return; }
    // A 5.1 bed's surrounds sit at +-110 degrees; a 7.1 bed's SL/SR are sides.
    const bool bed51 = bed_channels < 8;
    if (bed51 && (c == 4 || c == 5)) {
      const SpeakerRole want = c == 4 ? SpeakerRole::kLs : SpeakerRole::kRs;
      for (int i = 0; i < channels_; ++i) if (speakers_[i].role == want) { gains[i] = 1.0f; return; }
      // No 5.x surround of its own: the side surrounds, where a 7.x room has
      // them, half way back.
      zone_panners_[1].accumulate_power({c == 4 ? 0.0f : 1.0f, 0.5f, 0.0f}, 1.0f, gains);
      to_amplitude(gains);
      return;
    }
    const int direct = channel_for(k71[c]);
    if (direct >= 0) { gains[direct] = 1.0f; return; }
    zone_panners_[1].accumulate_power(bed_channel_room(k71[c]), 1.0f, gains);
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

  // Samples the object's box and power-sums the panned gains, scaled by
  // `weight`. An object with no size is a point. (Anything inside the room
  // used to be spread as well, upward too: a floor object a quarter in from a
  // corner put a fifth of its power in the ceiling.)
  void spread(const BalancePanner& panner, const cavern::ObjectState& st, const Vec3& centre,
              float weight, float* gains) const {
    if (weight <= 0.0f) return;
    const float w = std::fmin(1.0f, st.width);
    const float d = std::fmin(1.0f, st.depth);
    const float h = std::fmin(1.0f, st.height);
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
          panner.accumulate_power(p, each, gains);
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
  std::array<BalancePanner, kZoneVariants> zone_panners_;
  // The objects' gains in time. They are on a ramp from ramp_from_ to
  // ramp_to_ ([object][channel]), ramp_pos_ of ramp_len_ samples along; the
  // updates still to take hold wait in events_, oldest first.
  struct Event {
    int wait = 0;   // samples until it takes hold
    int ramp = 0;   // samples it is reached over
    std::vector<float> gains;
  };
  static constexpr int kEvents = 4;

  float weight(int pos) const {
    return pos >= ramp_len_ ? 1.0f : static_cast<float>(pos) / static_cast<float>(ramp_len_);
  }

  // The oldest update takes hold: from wherever the gains are now, to it.
  void begin(int samples) {
    Event& e = events_[event_first_];
    const float w = weight(ramp_pos_);
    for (size_t i = 0; i < ramp_from_.size(); ++i) {
      ramp_from_[i] += (ramp_to_[i] - ramp_from_[i]) * w;
    }
    ramp_to_.swap(e.gains);
    ramp_pos_ = 0;
    ramp_len_ = e.ramp < 1 ? 1 : e.ramp;
    (void)samples;
    event_first_ = (event_first_ + 1) % kEvents;
    --event_count_;
  }

  int max_objects_ = 0;
  std::vector<float> ramp_from_, ramp_to_;
  std::vector<float>* target_ = nullptr;
  int ramp_pos_ = 1, ramp_len_ = 1;
  bool has_state_ = false;
  Event events_[kEvents];
  int event_first_ = 0, event_count_ = 0;
  std::vector<float> next_gains_;  // [channel] scratch
  std::vector<float> bed_prev_;    // [bed channel][channel]
  bool bed_has_prev_ = false;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_SPEAKER_RENDERER_H

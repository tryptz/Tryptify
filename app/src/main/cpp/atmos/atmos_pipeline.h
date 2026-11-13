// End-to-end native Atmos render pipeline (plan Option B2, P1 -> P2 glue).
//
// Chains the object reconstruction (ObjectEngine) and the binaural render
// (BinauralRenderer): a raw E-AC-3 frame + its decoded core bed PCM go in, and
// binaural stereo comes out. Each object's OAMD render-space position is mapped
// to an azimuth/elevation for the HRTF renderer. This is the single native call
// the Media3 AtmosAudioProcessor (P4) invokes per frame.
#ifndef TF_ATMOS_ATMOS_PIPELINE_H
#define TF_ATMOS_ATMOS_PIPELINE_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "cavern/enhanced_ac3.h"  // dialnorm from the syncframe header
#include "object_engine.h"
#include "render/hrir_renderer.h"
#include "render/sofa_loader.h"  // runtime SOFA HRTF (defined only in the JNI lib)
#include "render/virtual_room.h"  // the 15.1.12 virtual room's binaural stage
#include "speaker_renderer.h"     // multichannel object render (5.1 .. 9.1.6)
#include "vbap.h"  // Vec3

namespace tf {
namespace atmos {

// Maps a render-space position (x = left..right, y = down..up, z = back..front)
// to azimuth (0 = front, +right) and elevation (0 = ear level, +up), in radians.
inline void position_to_azel(const Vec3& v, float& azimuth, float& elevation) {
  azimuth = std::atan2(v.x, v.z);
  const float horiz = std::sqrt(v.x * v.x + v.z * v.z);
  elevation = std::atan2(v.y, horiz);
}

// Mirrors the Kotlin RendererMode / StereoDownmixMode ordinals so the profile
// can be pushed across JNI as plain ints.
enum RenderMode { kPassthrough = 0, kObjectRender = 1, kBedHrtf = 2 };
enum DownmixMode { kBinaural = 0, kLoRo = 1, kLtRt = 2 };

class AtmosPipeline {
 public:
  void configure(int sample_rate, int max_objects) {
    max_objects_ = max_objects < 1 ? 1 : max_objects;
    // +8 slots so the bed-HRTF path can also render bed channels as sources.
    renderer_.configure(sample_rate, max_objects_ + 8);
    // The virtual room: its panner, and the binaural stage its feeds go to.
    configure_path(room_path_, OutputLayout::k15_1_12);
    std::vector<render::RoomSpeaker> room;
    for (const SpeakerDef& s : layout_speakers(OutputLayout::k15_1_12)) {
      room.push_back({s.azimuth, s.elevation, is_lfe(s)});
    }
    room_.configure(sample_rate, room);
    room_feeds_.assign(static_cast<size_t>(kFrameSamples) * room_path_.renderer.channels(), 0.0f);
    flat_states_.assign(static_cast<size_t>(max_objects_), cavern::ObjectState{});
    dm_delay_l_.assign(kQmfLatency, 0.0f);
    dm_delay_r_.assign(kQmfLatency, 0.0f);
    dm_idx_ = 0;
    last_path_ = kPathNone;
  }

  // Clears all time history (downmix delay, renderer tails, DRC envelope, path
  // memory) without reallocating — audio-thread-safe. Called on seek/flush;
  // without it the first ~12 ms after a seek would replay pre-seek audio out
  // of the latency-matching delay line.
  void flush_state() {
    std::fill(dm_delay_l_.begin(), dm_delay_l_.end(), 0.0f);
    std::fill(dm_delay_r_.begin(), dm_delay_r_.end(), 0.0f);
    dm_idx_ = 0;
    last_path_ = kPathNone;
    drc_env_ = 0.0f;
    renderer_.reset_history();
    engine_.flush_history();
    reset_path(spk_path_);
    reset_path(room_path_);
    room_.reset_history();
    last_objects_ = 0;
  }

  // Selects the loudspeaker layout for process_frame_speakers (an OutputLayout
  // id, i.e. Kotlin ChannelLayout.nativeId). Allocates — call it off the audio
  // thread or from the processor's flush/configure, never per frame. Returns
  // the layout's channel count.
  int set_output_layout(int layout_id) {
    if (layout_id < static_cast<int>(OutputLayout::k5_1) ||
        layout_id > static_cast<int>(OutputLayout::k9_1_6)) {
      layout_id = static_cast<int>(OutputLayout::k5_1);
    }
    configure_path(spk_path_, static_cast<OutputLayout>(layout_id));
    return spk_path_.renderer.channels();
  }
  int output_channels() const { return spk_path_.renderer.channels(); }

  // Applies the user's RendererProfile. `lfe_gain_db` currently affects the
  // bed-HRTF path (in object render the objects carry their own OAMD gains).
  void set_params(int mode, int downmix, float binaural_strength,
                  bool height_virtualization, float lfe_gain_db,
                  bool bass_management, int crossover_hz, int drc_mode,
                  bool dialog_normalization) {
    mode_ = mode;
    downmix_ = downmix;
    lfe_gain_ = std::pow(10.0f, lfe_gain_db / 20.0f);
    drc_mode_ = drc_mode;
    dialog_norm_ = dialog_normalization;
    height_ = height_virtualization;
    strength_ = binaural_strength;
    renderer_.set_params(binaural_strength, height_virtualization);
    renderer_.set_bass_management(bass_management, crossover_hz);
    room_.set_params(binaural_strength, room_amount_);
    room_.set_bass_management(bass_management, crossover_hz);
  }

  // The headphone render's two ways of placing sound: with `enabled`, objects
  // and beds are panned onto the 15.1.12 virtual room and its speakers are
  // what the ears hear (render/virtual_room.h); without, each object goes
  // through the HRIR of its own direction, with no room. `room` is how much
  // of the room's reflections and tail there is, 0 to 1.
  void set_virtual_room(bool enabled, float room) {
    virtual_room_ = enabled;
    room_amount_ = room < 0.0f ? 0.0f : (room > 1.0f ? 1.0f : room);
    room_.set_params(strength_, room_amount_);
  }

  // Installs a runtime HRTF from a SOFA buffer (off the audio thread), or
  // reverts to the baked default. The load itself is defined in the JNI lib.
  bool load_sofa(const char* data, long size) {
    if (!load_sofa_into_renderer(renderer_, data, size)) return false;
    const std::vector<float>* set = nullptr;
    const std::vector<float>* delay = nullptr;
    if (renderer_.runtime_hrir(set, delay)) room_.set_hrir(*set, *delay);
    return true;
  }
  void clear_sofa() {
    renderer_.clear_runtime_hrir();
    room_.set_hrir_baked();
  }

  // Objects rendered in the last frame (the LFE included), or 0 when it was
  // the decoded bed that played: no JOC yet, a plain multichannel stream, or
  // the bed mode. What the caller reports as "objects rendered".
  int last_objects() const { return last_objects_; }

  // The last frame's objects, for a meter: kMeterFields floats an object into
  // `out`, room position (x 0..1 left to right, y 0..1 front to back, z -1..1
  // floor to ceiling), the frame's peak after its gain, and what it is
  // (0 an object, 1 a bed channel, 2 the LFE). Returns how many were written;
  // 0 when no objects were rendered. Audio thread, allocation-free.
  static constexpr int kMeterFields = 5;
  int object_meter(float* out, int max_objects) const {
    int count = last_objects_ < max_objects ? last_objects_ : max_objects;
    if (count > max_objects_) count = max_objects_;
    const int samples = engine_.frame_samples();
    const int lfe = engine_.lfe_object_index();
    for (int o = 0; o < count; ++o) {
      float* f = out + static_cast<size_t>(o) * kMeterFields;
      const cavern::ObjectState* st = engine_.object_state(o);
      f[0] = st ? st->room.x : 0.5f;
      f[1] = st ? st->room.y : 0.5f;
      f[2] = st ? st->room.z : 0.0f;
      float peak = 0.0f;
      const float* pcm = engine_.object_channel(o);
      if (pcm != nullptr) {
        for (int i = 0; i < samples; ++i) {
          const float a = pcm[i] < 0.0f ? -pcm[i] : pcm[i];
          if (a > peak) peak = a;
        }
      }
      f[3] = peak;
      f[4] = o == lfe ? 2.0f : ((st && st->is_bed) ? 1.0f : 0.0f);
    }
    return count;
  }

  // Objects beyond the configured cap in the last rendered frame (0 = none).
  int last_truncated() const { return last_truncated_; }

  // ── Audio-thread-safe marshaling scratch ─────────────────────────────────
  // The JNI layer runs on the audio thread, so it must not allocate per call.
  // These grow once and are reused; the interleaved entry point deinterleaves
  // into reusable planar storage instead of building vectors every frame.
  std::vector<uint8_t>& frame_scratch(size_t n) {
    if (frame_scratch_.size() < n) frame_scratch_.resize(n);
    return frame_scratch_;
  }
  std::vector<float>& bed_scratch(size_t n) {
    if (bed_scratch_.size() < n) bed_scratch_.resize(n);
    return bed_scratch_;
  }
  std::vector<float>& stereo_scratch(size_t n) {
    if (stereo_scratch_.size() < n) stereo_scratch_.resize(n);
    return stereo_scratch_;
  }

  // Interleaved bed in, interleaved stereo out. Same contract as process_frame.
  int process_frame_interleaved(const uint8_t* frame, size_t frame_size,
                                const float* bed_interleaved, int channels,
                                int samples, float* out_stereo) {
    if (static_cast<int>(planar_.size()) != channels) {
      planar_.assign(static_cast<size_t>(channels), std::vector<float>());
    }
    planar_ptrs_.resize(static_cast<size_t>(channels));
    for (int c = 0; c < channels; ++c) {
      if (static_cast<int>(planar_[c].size()) < samples) planar_[c].resize(samples);
      float* dst = planar_[c].data();
      for (int i = 0; i < samples; ++i) {
        dst[i] = bed_interleaved[static_cast<size_t>(i) * channels + c];
      }
      planar_ptrs_[c] = dst;
    }
    return process_frame(frame, frame_size, planar_ptrs_.data(), channels, samples,
                         out_stereo);
  }

  // Interleaved bed in, interleaved speaker feeds out (output_channels() wide).
  int process_frame_speakers_interleaved(const uint8_t* frame, size_t frame_size,
                                         const float* bed_interleaved, int channels,
                                         int samples, float* out) {
    deinterleave(bed_interleaved, channels, samples);
    return process_frame_speakers(frame, frame_size, planar_ptrs_.data(), channels, samples, out);
  }

  // Renders one E-AC-3 frame to the configured loudspeaker layout: the JOC
  // objects through the SpeakerRenderer, or — for frames without usable JOC —
  // the decoded bed mapped onto the layout, delayed by the QMF round-trip so
  // the two paths stay sample-aligned and a switch is an equal-power
  // crossfade. `out` holds output_channels() * samples interleaved floats.
  // Returns 1 when written, -1 when inactive (passthrough, or no layout set).
  int process_frame_speakers(const uint8_t* frame, size_t frame_size, const float* const* bed,
                             int bed_channels, int samples, float* out) {
    const int ch = spk_path_.renderer.channels();
    if (mode_ == kPassthrough || ch <= 0 || bed_channels <= 0 || samples <= 0) return -1;
    dialnorm_gain_ = 1.0f;
    frame_has_compr_ = false;
    if (dialog_norm_ || drc_mode_ == 3) parse_bsi(frame, frame_size);
    render_layout(spk_path_, frame, frame_size, bed, bed_channels, samples, false, out);
    apply_post(out, samples, ch);
    return 1;
  }

  // Renders one E-AC-3 frame to interleaved stereo (`out` holds 2*samples
  // floats). Returns 1 whenever the binaural render is active: the output is
  // the object render or, for frames without usable JOC, a fixed-matrix downmix
  // delayed by the QMF round-trip so both paths are sample-aligned — a path
  // switch is then an equal-power crossfade instead of a cut that also jumps
  // ~12 ms in time. Returns -1 only when the caller's own fold-down should be
  // used (passthrough mode / LoRo / LtRt). `bed` is [bed_channels][samples]
  // deinterleaved float (the decoder's channel order).
  int process_frame(const uint8_t* frame, size_t frame_size, const float* const* bed,
                    int bed_channels, int samples, float* out) {
    // Passthrough / non-binaural fold-downs are the caller's job (it already
    // has a fixed-matrix downmix); returning -1 selects that path.
    if (mode_ == kPassthrough || downmix_ != kBinaural) return -1;
    // One BSI parse serves both dialogue normalization (the stream's own
    // dialnorm) and RF-mode DRC (the stream's own compr gain word).
    dialnorm_gain_ = 1.0f;
    frame_has_compr_ = false;
    if (dialog_norm_ || drc_mode_ == 3) parse_bsi(frame, frame_size);
    if (virtual_room_) {
      // Onto the virtual room's speakers, as onto real ones (objects, the bed
      // while there are none, the crossfade between them), then its speakers
      // to the two ears.
      if (bed_channels <= 0 || samples <= 0) return -1;
      const size_t n = static_cast<size_t>(samples) * room_path_.renderer.channels();
      if (room_feeds_.size() < n) room_feeds_.assign(n, 0.0f);
      render_layout(room_path_, frame, frame_size, bed, bed_channels, samples, !height_,
                    room_feeds_.data());
      room_.render(room_feeds_.data(), samples, out);
      apply_post(out, samples);
      return 1;
    }
    if (mode_ == kBedHrtf) {
      last_objects_ = 0;
      const int rc = render_bed(bed, bed_channels, samples, out);
      if (rc == 1) apply_post(out, samples);
      return rc;
    }
    if (bed_channels <= 0 || samples <= 0) return -1;

    // The latency-matched downmix runs every frame, rendered or not: it is the
    // fallback signal, and its delay line must stay primed with real audio so
    // a render→downmix switch always has aligned history to fade into.
    if (static_cast<int>(dm_.size()) < 2 * samples) dm_.assign(2 * samples, 0.0f);
    render_downmix(bed, bed_channels, samples, dm_.data());

    const int n = engine_.upmix_frame(frame, frame_size, bed, bed_channels, samples);
    const int want = n > 0 ? kPathRender : kPathDownmix;
    last_objects_ = n > 0 ? n : 0;
    if (want == kPathRender) render_objects(n, samples, out);

    if (last_path_ == kPathNone) last_path_ = want;
    if (want != last_path_) {
      if (want == kPathDownmix) {
        // The engine still holds the last JOC/OAMD past its expiry; render one
        // fade-out frame from it so the switch is a crossfade, not a cut.
        const int m = engine_.upmix_held(bed, bed_channels, samples);
        if (m > 0) {
          render_objects(m, samples, out);
          crossfade(out, dm_.data(), samples, out);
        } else {
          std::copy(dm_.data(), dm_.data() + 2 * samples, out);
        }
      } else {
        crossfade(dm_.data(), out, samples, out);
      }
      last_path_ = want;
    } else if (want == kPathDownmix) {
      std::copy(dm_.data(), dm_.data() + 2 * samples, out);
    }
    apply_post(out, samples);
    return 1;
  }

 private:
  static constexpr int kMaxBedCh = 8;
  static constexpr int kFrameSamples = 1536;  // 6 blocks x 256 per E-AC-3 frame
  // The object render's bed passes through QMF analysis+synthesis, which the
  // fallback downmix does not. MEASURED round-trip: cavern_qmf_test's best-lag
  // cross-correlation reports 577 samples (not the textbook 640-64=576) — the
  // fallback is delayed by the same amount so the paths stay sample-aligned.
  static constexpr int kQmfLatency = 577;
  // An E-AC-3 block: how far the decoded core is behind the frame's metadata.
#ifndef TF_ATMOS_CORE_DELAY
#define TF_ATMOS_CORE_DELAY 256
#endif
  static constexpr int kCoreDelay = TF_ATMOS_CORE_DELAY;
  static constexpr float kHalfPi = 1.57079632679f;
  static constexpr float kDirectHeadroom = 0.707f;
  enum { kPathNone = 0, kPathRender = 1, kPathDownmix = 2 };

  // The object render (JOC objects -> HRIR binaural + diffuse LFE) for the
  // engine's current upmix. `object_count` is the engine's return value.
  // Applies each object's OAMD gain (dialog/ambience trims mastered into the
  // stream) to the reconstructed PCM, ramped from the previously applied value.
  // A negative gain is the "no state yet" sentinel — keep the last one. Returns
  // the number of objects to render (capped at max_objects_).
  int apply_object_gains(int object_count) {
    const int objects = object_count < max_objects_ ? object_count : max_objects_;
    // Silent truncation would be a debugging trap; the JNI layer logs this
    // once. DD+ JOC practice is <=16 objects, so it should never fire.
    last_truncated_ = object_count > max_objects_ ? object_count - max_objects_ : 0;
    if (static_cast<int>(obj_gain_.size()) < objects) obj_gain_.resize(objects, 1.0f);
    for (int o = 0; o < objects; ++o) {
      const float g = engine_.object_gain(o);
      const float g1 = g >= 0.0f ? g : obj_gain_[o];
      engine_.scale_object(o, obj_gain_[o], g1);
      obj_gain_[o] = g1;
    }
    return objects;
  }

  // One layout the objects are panned onto: the output's speakers, or the
  // virtual room's. Its renderer, the bed fallback's latency-matching delay
  // line and scratch, and which of the two produced its previous frame.
  struct LayoutPath {
    SpeakerRenderer renderer;
    std::vector<float> delay, dm, bed;
    int idx = 0;
    int last = kPathNone;
    std::vector<const float*> ptrs;
    std::vector<const cavern::ObjectState*> states;
  };

  // Allocates: never per frame.
  void configure_path(LayoutPath& p, OutputLayout layout) {
    p.renderer.configure(layout, max_objects_);
    const int ch = p.renderer.channels();
    p.delay.assign(static_cast<size_t>(kQmfLatency) * ch, 0.0f);
    p.idx = 0;
    p.last = kPathNone;
    p.ptrs.assign(static_cast<size_t>(max_objects_), nullptr);
    p.states.assign(static_cast<size_t>(max_objects_), nullptr);
    // One E-AC-3 frame is 1536 samples; size the scratch for it up front.
    p.dm.assign(static_cast<size_t>(kFrameSamples) * ch, 0.0f);
    p.bed.assign(static_cast<size_t>(kFrameSamples) * ch, 0.0f);
  }

  void reset_path(LayoutPath& p) {
    p.renderer.reset_history();
    std::fill(p.delay.begin(), p.delay.end(), 0.0f);
    p.idx = 0;
    p.last = kPathNone;
  }

  // One frame onto a layout's speakers, interleaved into `out`: the JOC
  // objects, or, for frames without usable JOC, the decoded bed, delayed by
  // the QMF round trip so the two stay sample-aligned and a switch between
  // them is an equal-power crossfade. `flat` renders everything at ear level.
  void render_layout(LayoutPath& p, const uint8_t* frame, size_t frame_size,
                     const float* const* bed, int bed_channels, int samples, bool flat,
                     float* out) {
    const int ch = p.renderer.channels();
    const size_t n = static_cast<size_t>(samples) * ch;
    if (p.dm.size() < n) p.dm.assign(n, 0.0f);  // only for non-standard frame sizes
    if (p.bed.size() < n) p.bed.assign(n, 0.0f);
    if (mode_ == kBedHrtf) {
      // "Bed" mode: the core bed on the layout, no object decode.
      last_objects_ = 0;
      std::fill(out, out + n, 0.0f);
      p.renderer.render_bed(bed, bed_channels, lfe_gain_, samples, out);
      return;
    }

    // The latency-matched bed fallback runs every frame (see process_frame).
    std::fill(p.bed.begin(), p.bed.begin() + n, 0.0f);
    p.renderer.render_bed(bed, bed_channels, lfe_gain_, samples, p.bed.data());
    delay_layout(p, p.bed.data(), samples, p.dm.data());

    // Objects are panned by their OAMD positions. JOC with no OAMD at all has
    // nowhere to put them: the bed plays, as for a frame without JOC.
    const int upmixed = engine_.upmix_frame(frame, frame_size, bed, bed_channels, samples);
    const int objects = engine_.oamd().state_count() > 0 ? upmixed : -1;
    const int want = objects > 0 ? kPathRender : kPathDownmix;
    last_objects_ = objects > 0 ? objects : 0;
    if (want == kPathRender) render_layout_objects(p, objects, samples, flat, out);

    if (p.last == kPathNone) p.last = want;
    if (want != p.last) {
      if (want == kPathDownmix) {
        const int held = engine_.oamd().state_count() > 0
            ? engine_.upmix_held(bed, bed_channels, samples) : -1;
        if (held > 0) {
          render_layout_objects(p, held, samples, flat, out);
          crossfade(out, p.dm.data(), samples, out, ch);
        } else {
          std::copy(p.dm.data(), p.dm.data() + n, out);
        }
      } else {
        crossfade(p.dm.data(), out, samples, out, ch);
      }
      p.last = want;
    } else if (want == kPathDownmix) {
      std::copy(p.dm.data(), p.dm.data() + n, out);
    }
  }

  // The object render onto a layout for the engine's current upmix.
  void render_layout_objects(LayoutPath& p, int object_count, int samples, bool flat, float* out) {
    const int objects = apply_object_gains(object_count);
    for (int o = 0; o < objects; ++o) {
      p.ptrs[o] = engine_.object_channel(o);
      const cavern::ObjectState* st = engine_.object_state(o);
      if (flat && st != nullptr) {
        // Height off: the same object on the floor of the room, kept off the
        // top speakers.
        cavern::ObjectState& f = flat_states_[o];
        f = *st;
        f.room.z = 0.0f;
        f.render.y = 0.0f;
        f.height = 0.0f;
        f.enable_elevation = false;
        st = &f;
      }
      p.states[o] = st;
    }
    // When this frame's update takes hold: where its metadata says, counted
    // from the frame the decoder is on, which the objects in hand are behind
    // by the core decoder's overlap and the QMF round trip.
    int offset = 0, ramp = samples;
    if (!engine_.oamd().update_timing(offset, ramp)) { offset = 0; ramp = samples; }
    p.renderer.render(p.ptrs.data(), p.states.data(), objects, engine_.lfe_object_index(),
                      lfe_gain_, samples, out, kCoreDelay + kQmfLatency + offset, ramp);
  }

  // Pushes `in` (interleaved, the layout's width) through the kQmfLatency
  // delay line into `out`, like render_downmix does for stereo.
  void delay_layout(LayoutPath& p, const float* in, int samples, float* out) {
    const int ch = p.renderer.channels();
    for (int i = 0; i < samples; ++i) {
      float* slot = &p.delay[static_cast<size_t>(p.idx) * ch];
      const float* src = in + static_cast<size_t>(i) * ch;
      float* dst = out + static_cast<size_t>(i) * ch;
      for (int c = 0; c < ch; ++c) {
        dst[c] = slot[c];
        slot[c] = src[c];
      }
      if (++p.idx == kQmfLatency) p.idx = 0;
    }
  }

  void deinterleave(const float* bed_interleaved, int channels, int samples) {
    if (static_cast<int>(planar_.size()) != channels) {
      planar_.assign(static_cast<size_t>(channels), std::vector<float>());
    }
    planar_ptrs_.resize(static_cast<size_t>(channels));
    for (int c = 0; c < channels; ++c) {
      if (static_cast<int>(planar_[c].size()) < samples) planar_[c].resize(samples);
      float* dst = planar_[c].data();
      for (int i = 0; i < samples; ++i) {
        dst[i] = bed_interleaved[static_cast<size_t>(i) * channels + c];
      }
      planar_ptrs_[c] = dst;
    }
  }

  void render_objects(int object_count, int samples, float* out) {
    const int objects = apply_object_gains(object_count);

    // The LFE is non-directional bass. Spatializing it through the HRIR places
    // it at a point (and, with no OAMD position, at the front-left origin corner
    // — where it was the loudest, most obviously mislocalized object). Pull it
    // out of the HRIR set and sum it to both ears below instead.
    const int lfe = engine_.lfe_object_index();

    obj_ptrs_.clear();
    az_.clear();
    el_.clear();
    for (int o = 0; o < objects; ++o) {
      if (o == lfe) continue;
      float az, el;
      position_to_azel(engine_.object_position(o), az, el);
      obj_ptrs_.push_back(engine_.object_channel(o));
      az_.push_back(az);
      el_.push_back(el);
    }
    const int rendered_objects = static_cast<int>(obj_ptrs_.size());
    renderer_.render(obj_ptrs_.data(), az_.data(), el_.data(), rendered_objects,
                     nullptr, nullptr, nullptr, 0, samples, out);

    // Sum the non-directional LFE equally to both ears (with the profile's LFE
    // trim), after the HRIR render and before post. Bass management in the
    // renderer already folds low frequencies of the spatialized objects to both
    // ears; the LFE is bass by definition, so it just goes straight through.
    if (lfe >= 0) {
      const float* lp = engine_.object_channel(lfe);
      if (lp) {
        for (int i = 0; i < samples; ++i) {
          const float v = lp[i] * lfe_gain_;
          out[2 * i] += v;
          out[2 * i + 1] += v;
        }
      }
    }
    // This path has no limiter: its headroom is 3 dB taken off the sum here
    // (it used to be taken off every object's gain, which also put the
    // speaker render 3 dB under the stream's own 5.1).
    for (int i = 0; i < 2 * samples; ++i) out[i] *= kDirectHeadroom;
  }

  // Fixed-matrix fold-down (FL FR FC LFE and two or four surrounds; sides/backs hard-panned
  // at unity, FC at 0.70710678 to both, LFE at 2.26464431 to both), pushed
  // through the kQmfLatency delay line. Matches the Kotlin fallback's
  // coefficients so pipeline-off and pipeline-on fallbacks sound identical.
  void render_downmix(const float* const* bed, int ch, int samples, float* out) {
    for (int i = 0; i < samples; ++i) {
      float l, r;
      if (ch >= 6) {
        const float mid = 0.70710678f * bed[2][i] + 2.26464431f * bed[3][i];
        l = bed[0][i] + mid + bed[4][i];
        r = bed[1][i] + mid + bed[5][i];
        if (ch >= 8) {
          l += bed[6][i];
          r += bed[7][i];
        }
      } else {
        l = bed[0][i];
        r = ch > 1 ? bed[1][i] : bed[0][i];
      }
      out[2 * i] = dm_delay_l_[dm_idx_];
      out[2 * i + 1] = dm_delay_r_[dm_idx_];
      dm_delay_l_[dm_idx_] = l;
      dm_delay_r_[dm_idx_] = r;
      if (++dm_idx_ == kQmfLatency) dm_idx_ = 0;
    }
  }

  // Equal-power fade from `from` into `to` across the frame. `out` may alias
  // either input — each index is read before it is written.
  void crossfade(const float* from, const float* to, int samples, float* out, int ch = 2) {
    for (int i = 0; i < samples; ++i) {
      const float t = (i + 0.5f) / static_cast<float>(samples);
      const float wf = std::cos(t * kHalfPi);
      const float wt = std::sin(t * kHalfPi);
      for (int c = 0; c < ch; ++c) {
        const size_t k = static_cast<size_t>(i) * ch + c;
        out[k] = wf * from[k] + wt * to[k];
      }
    }
  }

  // Speaker azimuths (radians) for the decoder's channel order. Used by the
  // bed-HRTF mode, which spatializes the core bed directly instead of
  // reconstructing objects. 5.1 is FL FR FC LFE SL SR, its surrounds at 110
  // degrees; 7.1 is FL FR FC LFE BL BR SL SR, the rears (150) before the
  // sides (90).
  static constexpr float kBedAzimuth[kMaxBedCh] = {
      -0.5236f, 0.5236f, 0.0f, 0.0f, -1.9199f, 1.9199f, -2.6180f, 2.6180f};
  static constexpr float kBedAzimuth71[kMaxBedCh] = {
      -0.5236f, 0.5236f, 0.0f, 0.0f, -2.6180f, 2.6180f, -1.5708f, 1.5708f};

  // Spatializes the decoded bed channels at their speaker positions (no JOC).
  // The LFE (channel 3 in a >=4ch bed) is non-directional bass and is summed
  // diffusely to both ears instead of being HRIR-placed at front-center —
  // mirroring the object path's LFE handling.
  int render_bed(const float* const* bed, int bed_channels, int samples, float* out) {
    const int ch = bed_channels > kMaxBedCh ? kMaxBedCh : bed_channels;
    if (ch <= 0) return -1;
    const int lfe_ch = ch >= 4 ? 3 : -1;
    bed_ptrs_.clear();
    az_.clear();
    el_.clear();
    for (int c = 0; c < ch; ++c) {
      if (c == lfe_ch) continue;
      bed_ptrs_.push_back(bed[c]);
      az_.push_back(ch >= 8 ? kBedAzimuth71[c] : kBedAzimuth[c]);
      el_.push_back(0.0f);
    }
    renderer_.render(bed_ptrs_.data(), az_.data(), el_.data(),
                     static_cast<int>(bed_ptrs_.size()),
                     nullptr, nullptr, nullptr, 0, samples, out);
    if (lfe_ch >= 0) {
      const float* lp = bed[lfe_ch];
      for (int i = 0; i < samples; ++i) {
        const float v = lp[i] * lfe_gain_;
        out[2 * i] += v;
        out[2 * i + 1] += v;
      }
    }
    return 1;
  }

  // Reads the frame's BSI for dialnorm (dialogue normalization) and compr
  // (the encoder's RF-mode compression gain word).
  void parse_bsi(const uint8_t* frame, size_t frame_size) {
    BitReader br(frame, frame_size);
    cavern::EnhancedAC3Header header;
    if (!header.decode(br)) return;
    if (dialog_norm_) {
      dialnorm_gain_ = std::pow(10.0f, header.dialnorm_gain_db() / 20.0f);
    }
    if (header.has_compr()) {
      frame_compr_ = header.compr_gain();
      frame_has_compr_ = true;
    }
  }

  // Dialogue normalization followed by DRC. The decoded bed already carries
  // Line-mode DRC (FFmpeg applies the per-block dynrng words at its default
  // drc_scale=1.0, and NextLib passes no codec options), so:
  //   OFF      -> nothing added here (the Line mode inherent to the decode).
  //   LIGHT /
  //   STANDARD -> the generic peak compressor as additional leveling.
  //   HEAVY    -> true RF/night mode: the stream's own compr gain word, ramped
  //               across the frame, plus a limiter for overload protection —
  //               falling back to the generic heavy curve for streams that
  //               never carry compr.
  void apply_post(float* out, int samples, int ch = 2) {
    if (dialnorm_gain_ != 1.0f) {
      for (int i = 0; i < ch * samples; ++i) out[i] *= dialnorm_gain_;
    }
    if (drc_mode_ == 0) return;

    float thresh, ratio;
    bool rf = false;
    if (drc_mode_ == 3) {
      if (frame_has_compr_) {
        rf_target_ = frame_compr_;
        rf_seen_ = true;
      }
      rf = rf_seen_;
    }
    if (rf) {
      // The mastered heavy-compression gain, ramped from the last applied
      // value so per-frame word changes never step.
      const float g0 = rf_gain_;
      const float step = (rf_target_ - g0) / static_cast<float>(samples);
      float g = g0;
      for (int i = 0; i < samples; ++i) {
        for (int c = 0; c < ch; ++c) out[static_cast<size_t>(i) * ch + c] *= g;
        g += step;
      }
      rf_gain_ = rf_target_;
      thresh = 0.95f;   // overload protection only — compr did the real work
      ratio = 20.0f;
    } else {
      switch (drc_mode_) {
        case 1: thresh = 0.50f; ratio = 2.0f; break;   // LIGHT
        case 2: thresh = 0.35f; ratio = 4.0f; break;   // STANDARD
        default: thresh = 0.20f; ratio = 8.0f; break;  // HEAVY without compr
      }
    }
    // One envelope across every channel (the loudest drives the gain), so the
    // compressor never shifts the image the way per-channel gains would.
    for (int i = 0; i < samples; ++i) {
      float* frame = out + static_cast<size_t>(i) * ch;
      float peak = 0.0f;
      for (int k = 0; k < ch; ++k) {
        const float a = frame[k] < 0.0f ? -frame[k] : frame[k];
        if (a > peak) peak = a;
      }
      const float c = peak > drc_env_ ? kDrcAttack : kDrcRelease;
      drc_env_ = c * drc_env_ + (1.0f - c) * peak;
      float gain = 1.0f;
      if (drc_env_ > thresh) gain = (thresh + (drc_env_ - thresh) / ratio) / drc_env_;
      for (int k = 0; k < ch; ++k) frame[k] *= gain;
    }
  }

  static constexpr float kDrcAttack = 0.30f;    // fast catch of transients
  static constexpr float kDrcRelease = 0.9995f; // slow recovery (~40 ms @ 48k)

  ObjectEngine engine_;
  render::BinauralRenderer renderer_;
  // Loudspeaker path (process_frame_speakers): renderer, the bed fallback's
  // latency-matching delay line, scratch, and its own path memory.
  LayoutPath spk_path_;
  // Headphone path through the virtual room: its panner, the room's speaker
  // feeds for one frame, and the stage that turns them into two ears.
  LayoutPath room_path_;
  render::VirtualRoom room_;
  std::vector<float> room_feeds_;
  std::vector<cavern::ObjectState> flat_states_;  // objects at ear level (height off)
  bool virtual_room_ = false;  // the caller turns it on (set_virtual_room)
  float room_amount_ = 0.25f;
  bool height_ = true;
  float strength_ = 1.0f;
  int last_objects_ = 0;
  int max_objects_ = 16;
  int mode_ = kObjectRender;
  int downmix_ = kBinaural;
  float lfe_gain_ = 1.0f;
  int drc_mode_ = 0;
  bool dialog_norm_ = false;
  float dialnorm_gain_ = 1.0f;
  float drc_env_ = 0.0f;
  // RF-mode DRC state: this frame's compr word (if any), the ramp source, and
  // whether the stream has ever carried compr (else HEAVY falls back).
  bool frame_has_compr_ = false;
  float frame_compr_ = 1.0f;
  float rf_gain_ = 1.0f;
  float rf_target_ = 1.0f;
  bool rf_seen_ = false;
  std::vector<const float*> obj_ptrs_, bed_ptrs_;
  std::vector<float> az_, el_;
  // Latency-matched fallback downmix: per-ear delay lines (kQmfLatency long),
  // the delayed stereo scratch, and which path produced the previous frame.
  std::vector<float> dm_delay_l_, dm_delay_r_, dm_;
  int dm_idx_ = 0;
  int last_path_ = kPathNone;
  int last_truncated_ = 0;
  // Last applied OAMD gain per object (survives seeks — it is stream metadata).
  std::vector<float> obj_gain_;
  // Reused marshaling / deinterleave storage (see frame_scratch etc.).
  std::vector<uint8_t> frame_scratch_;
  std::vector<float> bed_scratch_, stereo_scratch_;
  std::vector<std::vector<float>> planar_;
  std::vector<const float*> planar_ptrs_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_ATMOS_PIPELINE_H

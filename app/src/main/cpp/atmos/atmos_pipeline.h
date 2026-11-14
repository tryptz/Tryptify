// The native Atmos render, one E-AC-3 frame at a time: the frame and its
// decoded core bed go in, and what the listener hears comes out.
//
// ObjectEngine turns the frame into objects (JOC) with positions (OAMD). They
// are panned onto loudspeakers (SpeakerRenderer): for headphones the 28 of
// the 15.1.12 virtual room, which VirtualRoom then renders to two ears
// (process_frame); for a real layout, the layout's own (process_frame_speakers).
// A frame without usable objects plays the decoded bed on the same speakers.
#ifndef TF_ATMOS_ATMOS_PIPELINE_H
#define TF_ATMOS_ATMOS_PIPELINE_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "cavern/enhanced_ac3.h"  // dialnorm from the syncframe header
#include "object_engine.h"
#include "render/sofa_loader.h"  // runtime SOFA HRTF (defined only in the JNI lib)
#include "render/virtual_room.h"  // the 15.1.12 virtual room's binaural stage
#include "speaker_renderer.h"     // multichannel object render (5.1 .. 9.1.6)
#include "vbap.h"  // Vec3

namespace tf {
namespace atmos {

// Mirrors the Kotlin RendererMode / StereoDownmixMode ordinals so the profile
// can be pushed across JNI as plain ints.
enum RenderMode { kPassthrough = 0, kObjectRender = 1, kBedHrtf = 2 };
enum DownmixMode { kBinaural = 0, kLoRo = 1, kLtRt = 2 };

class AtmosPipeline {
 public:
  void configure(int sample_rate, int max_objects) {
    max_objects_ = max_objects < 1 ? 1 : max_objects;
    // The virtual room: its panner, and the binaural stage its feeds go to.
    configure_path(room_path_, OutputLayout::k15_1_12);
    std::vector<render::RoomSpeaker> room;
    for (const SpeakerDef& s : layout_speakers(OutputLayout::k15_1_12)) {
      room.push_back({s.azimuth, s.elevation, is_lfe(s)});
    }
    room_.configure(sample_rate, room);
    room_feeds_.assign(static_cast<size_t>(kFrameSamples) * room_path_.renderer.channels(), 0.0f);
    flat_states_.assign(static_cast<size_t>(max_objects_), cavern::ObjectState{});
    trims_.assign(static_cast<size_t>(max_objects_), 1.0f);
  }

  // Clears all time history (downmix delay, renderer tails, DRC envelope, path
  // memory) without reallocating — audio-thread-safe. Called on seek/flush;
  // without it the first ~12 ms after a seek would replay pre-seek audio out
  // of the latency-matching delay line.
  void flush_state() {
    drc_env_ = 0.0f;
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

  // Applies the user's RendererProfile. `lfe_gain_db` is a trim on the LFE,
  // whether it comes as an object or as the bed's channel.
  void set_params(int mode, int downmix, float binaural_strength,
                  bool height_virtualization, float lfe_gain_db,
                  bool bass_management, int crossover_hz, int drc_mode,
                  bool dialog_normalization) {
    mode_ = mode;
    downmix_ = downmix;
    lfe_gain_ = db_to_gain(lfe_gain_db);
    drc_mode_ = drc_mode;
    dialog_norm_ = dialog_normalization;
    height_ = height_virtualization;
    strength_ = binaural_strength;
    room_.set_params(binaural_strength, room_amount_);
    room_.set_bass_management(bass_management, crossover_hz);
  }

  // The space objects may use, on headphones and on loudspeakers alike
  // (PanEnvelope, speaker_renderer.h).
  void set_pan_envelope(const PanEnvelope& envelope) {
    spk_path_.renderer.set_envelope(envelope);
    room_path_.renderer.set_envelope(envelope);
  }

  // How much of the virtual room's reflections and tail the headphone render
  // has, 0 to 1.
  void set_room_amount(float room) {
    room_amount_ = room < 0.0f ? 0.0f : (room > 1.0f ? 1.0f : room);
    room_.set_params(strength_, room_amount_);
  }

  // A measured or worked-out room for the headphone render (a packed room,
  // render/measured_room.h), or the built-in one for null. False when
  // `packed` is not a room. Allocates: loader thread.
  bool set_room_response(const float* packed, size_t size) {
    if (packed == nullptr) {
      room_.set_room_response(nullptr);
      return true;
    }
    render::RoomResponse response;
    if (!render::unpack_room(packed, size, response)) return false;
    room_.set_room_response(&response);
    return true;
  }

  // Installs a runtime HRTF from a SOFA buffer (off the audio thread), or
  // reverts to the baked default. The load itself is defined in the JNI lib.
  bool load_sofa(const char* data, long size) {
    std::vector<float> taps, delays;
    if (!load_sofa_tables(data, size, taps, delays)) return false;
    room_.set_hrir(taps, delays);
    return true;
  }
  void clear_sofa() { room_.set_hrir_baked(); }

  // Reads one frame's object metadata without rendering anything (the
  // listener has the object render off): returns the object count, 0 for
  // none. track_meter() then gives where they are.
  int track_metadata(const uint8_t* frame, size_t frame_size) {
    tracked_ = engine_.track_metadata(frame, frame_size);
    return tracked_;
  }

  // As object_meter(), for objects that are tracked and not rendered: there
  // is no audio to take a level from, so the level field is -1.
  int track_meter(float* out, int max_objects) const {
    return write_meter(out, tracked_ < max_objects ? tracked_ : max_objects, engine_.metadata_lfe(), true);
  }

  // The virtual room's speakers, for the Atmos page's room: four floats a
  // speaker into `out`, where it stands (x 0..1 left to right, y 0..1 front
  // to back, z 0 at ear level to 1 at the ceiling) and 1 for the LFE.
  // Returns how many were written.
  static constexpr int kRoomFields = 4;
  static int room_layout(float* out, int max_speakers) {
    const std::vector<SpeakerDef> v = layout_speakers(OutputLayout::k15_1_12);
    const int n = static_cast<int>(v.size()) < max_speakers ? static_cast<int>(v.size()) : max_speakers;
    for (int s = 0; s < n; ++s) {
      out[s * kRoomFields] = v[s].x;
      out[s * kRoomFields + 1] = v[s].y;
      out[s * kRoomFields + 2] = v[s].z;
      out[s * kRoomFields + 3] = is_lfe(v[s]) ? 1.0f : 0.0f;
    }
    return n;
  }

  // How hard each of the room's speakers was driven in the last frame, one
  // float a speaker in room_layout()'s order. When the room rendered the
  // frame it is the peak of that speaker's feed. Otherwise (real speakers,
  // or objects only watched) it is where the objects would go on the room's
  // speakers: each object's peak through its panning gains; a watched
  // object has no level, so its gains alone, negated to say so. Returns the
  // speaker count, 0 when there is nothing to show. Audio thread,
  // allocation-free.
  static constexpr int kRoomMeterMax = 32;
  int room_meter(float* out, int max_speakers, bool tracked) const {
    const int ch = room_path_.renderer.channels();
    if (ch <= 0 || ch > kRoomMeterMax || max_speakers < ch) return 0;
    if (!tracked && room_rendered_ > 0) {
      for (int s = 0; s < ch; ++s) out[s] = 0.0f;
      const float* f = room_feeds_.data();
      for (int i = 0; i < room_rendered_; ++i, f += ch) {
        for (int s = 0; s < ch; ++s) {
          const float a = f[s] < 0.0f ? -f[s] : f[s];
          if (a > out[s]) out[s] = a;
        }
      }
      return ch;
    }
    int count = tracked ? tracked_ : last_objects_;
    if (count > max_objects_) count = max_objects_;
    if (count <= 0) return 0;
    const int lfe = tracked ? engine_.metadata_lfe() : engine_.lfe_object_index();
    const int samples = engine_.frame_samples();
    float gains[kRoomMeterMax], power[kRoomMeterMax];
    for (int s = 0; s < ch; ++s) power[s] = 0.0f;
    for (int o = 0; o < count; ++o) {
      const cavern::ObjectState* st = engine_.object_state(o);
      if (st == nullptr) continue;
      const float peak = tracked ? 1.0f : peak_abs(engine_.object_channel(o), samples);
      room_path_.renderer.object_gains(*st, o == lfe, gains);
      for (int s = 0; s < ch; ++s) power[s] += gains[s] * gains[s] * peak * peak;
    }
    for (int s = 0; s < ch; ++s) {
      const float level = std::sqrt(power[s]);
      out[s] = tracked ? -level : level;
    }
    return ch;
  }

  // The engine's view of the last frame's metadata (ObjectEngine::debug).
  void debug(int* out) const { engine_.debug(out); }

  // Objects rendered in the last frame (the LFE included), or 0 when it was
  // the decoded bed that played: no JOC yet, a plain multichannel stream, or
  // the bed mode. What the caller reports as "objects rendered".
  int last_objects() const { return last_objects_; }

  // The last frame's objects, for a meter: kMeterFields floats an object into
  // `out`, room position (x 0..1 left to right, y 0..1 front to back, z -1..1
  // floor to ceiling), the frame's peak after its gain, and what it is
  // (kMeterObject, kMeterBed or kMeterLfe). Returns how many were written;
  // 0 when no objects were rendered. Audio thread, allocation-free.
  static constexpr int kMeterFields = 5;
  static constexpr float kMeterObject = 0.0f, kMeterBed = 1.0f, kMeterLfe = 2.0f;
  int object_meter(float* out, int max_objects) const {
    int count = last_objects_ < max_objects ? last_objects_ : max_objects;
    if (count > max_objects_) count = max_objects_;
    return write_meter(out, count, engine_.lfe_object_index(), false);
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
  std::vector<float>& out_scratch(size_t n) {
    if (out_scratch_.size() < n) out_scratch_.resize(n);
    return out_scratch_;
  }

  // Interleaved bed in, interleaved stereo out. Same contract as process_frame.
  int process_frame_interleaved(const uint8_t* frame, size_t frame_size,
                                const float* bed_interleaved, int channels,
                                int samples, float* out_stereo) {
    deinterleave(bed_interleaved, channels, samples);
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
    room_rendered_ = 0;
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
    if (mode_ == kPassthrough || downmix_ != kBinaural) { room_rendered_ = 0; return -1; }
    // One BSI parse serves both dialogue normalization (the stream's own
    // dialnorm) and RF-mode DRC (the stream's own compr gain word).
    dialnorm_gain_ = 1.0f;
    frame_has_compr_ = false;
    if (dialog_norm_ || drc_mode_ == 3) parse_bsi(frame, frame_size);
    // Onto the virtual room's speakers, as onto real ones (objects, the bed
    // while there are none, the crossfade between them), then its speakers
    // to the two ears.
    if (bed_channels <= 0 || samples <= 0) { room_rendered_ = 0; return -1; }
    const size_t n = static_cast<size_t>(samples) * room_path_.renderer.channels();
    if (room_feeds_.size() < n) room_feeds_.assign(n, 0.0f);
    render_layout(room_path_, frame, frame_size, bed, bed_channels, samples, !height_,
                  room_feeds_.data());
    room_.render(room_feeds_.data(), samples, out);
    room_rendered_ = samples;
    apply_post(out, samples);
    return 1;
  }

 private:
  static constexpr int kFrameSamples = 1536;  // 6 blocks x 256 per E-AC-3 frame
  // The objects pass through QMF analysis and synthesis and the decoded bed
  // does not: the bed is delayed by the same round trip, so the two stay
  // sample-aligned.
  static constexpr int kQmfLatency = ObjectEngine::kQmfLatency;
  // An E-AC-3 block: how far the decoded core is behind the frame's metadata.
#ifndef TF_ATMOS_CORE_DELAY
#define TF_ATMOS_CORE_DELAY 256
#endif
  static constexpr int kCoreDelay = TF_ATMOS_CORE_DELAY;
  static constexpr float kHalfPi = 1.57079632679f;
  enum { kPathNone = 0, kPathRender = 1, kPathBed = 2 };

  // `count` objects' meter fields into `out`; `lfe` is the LFE's index.
  int write_meter(float* out, int count, int lfe, bool tracked) const {
    const int samples = engine_.frame_samples();
    for (int o = 0; o < count; ++o) {
      float* f = out + static_cast<size_t>(o) * kMeterFields;
      const cavern::ObjectState* st = engine_.object_state(o);
      // Where it plays from: inside the panning envelope, if there is one.
      const bool free = st != nullptr && !st->is_bed && o != lfe;
      const Vec3 at = st == nullptr ? Vec3{0.5f, 0.5f, 0.0f}
                                    : (free ? room_path_.renderer.envelope().apply(st->room) : st->room);
      f[0] = at.x;
      f[1] = at.y;
      f[2] = at.z;
      f[3] = tracked ? -1.0f : peak_abs(engine_.object_channel(o), samples);
      f[4] = o == lfe ? kMeterLfe : ((st && st->is_bed) ? kMeterBed : kMeterObject);
    }
    return count;
  }

  static float db_to_gain(float db) { return std::pow(10.0f, db / 20.0f); }
  // The largest magnitude in `pcm` (`samples` long); 0 for none.
  static float peak_abs(const float* pcm, int samples) {
    float peak = 0.0f;
    for (int i = 0; pcm != nullptr && i < samples; ++i) {
      const float a = pcm[i] < 0.0f ? -pcm[i] : pcm[i];
      if (a > peak) peak = a;
    }
    return peak;
  }

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
    std::vector<float> bed;      // the decoded bed on this layout, one frame
    std::vector<float> delay;    // the ring that holds it back kQmfLatency
    std::vector<float> delayed;  // what the ring gives back, one frame
    int idx = 0;                 // the ring's place
    int last = kPathNone;        // which of the two the frame before played
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
    p.delayed.assign(static_cast<size_t>(kFrameSamples) * ch, 0.0f);
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
    if (p.delayed.size() < n) p.delayed.assign(n, 0.0f);  // only for non-standard frame sizes
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
    delay_layout(p, p.bed.data(), samples, p.delayed.data());

    // Objects are panned by their OAMD positions. JOC with no OAMD at all has
    // nowhere to put them: the bed plays, as for a frame without JOC.
    const int upmixed = engine_.upmix_frame(frame, frame_size, bed, bed_channels, samples);
    const int objects = engine_.oamd().state_count() > 0 ? upmixed : -1;
    const int want = objects > 0 ? kPathRender : kPathBed;
    last_objects_ = objects > 0 ? objects : 0;
    if (want == kPathRender) render_layout_objects(p, objects, samples, flat, out);

    if (p.last == kPathNone) p.last = want;
    if (want != p.last) {
      if (want == kPathBed) {
        const int held = engine_.oamd().state_count() > 0
            ? engine_.upmix_held(bed, bed_channels, samples) : -1;
        if (held > 0) {
          render_layout_objects(p, held, samples, flat, out);
          crossfade(out, p.delayed.data(), samples, out, ch);
        } else {
          std::copy(p.delayed.data(), p.delayed.data() + n, out);
        }
      } else {
        crossfade(p.delayed.data(), out, samples, out, ch);
      }
      p.last = want;
    } else if (want == kPathBed) {
      std::copy(p.delayed.data(), p.delayed.data() + n, out);
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
    p.renderer.set_warp(engine_.oamd().warp_mode() == 1);
    if (static_cast<int>(trims_.size()) < objects) trims_.resize(objects, 1.0f);
    for (int o = 0; o < objects; ++o) {
      trims_[o] = db_to_gain(engine_.oamd().surround_trim_db_51(o));
    }
    p.renderer.set_surround_trims(trims_.data(), objects);
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

  // Reads the frame's BSI for dialnorm (dialogue normalization) and compr
  // (the encoder's RF-mode compression gain word).
  void parse_bsi(const uint8_t* frame, size_t frame_size) {
    BitReader br(frame, frame_size);
    cavern::EnhancedAC3Header header;
    if (!header.decode(br)) return;
    if (dialog_norm_) {
      dialnorm_gain_ = db_to_gain(header.dialnorm_gain_db());
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
  // Loudspeaker path (process_frame_speakers): renderer, the bed fallback's
  // latency-matching delay line, scratch, and its own path memory.
  LayoutPath spk_path_;
  // Headphone path through the virtual room: its panner, the room's speaker
  // feeds for one frame, and the stage that turns them into two ears.
  LayoutPath room_path_;
  render::VirtualRoom room_;
  std::vector<float> room_feeds_;
  std::vector<float> trims_;  // the stream's surround trim, linear, an object
  std::vector<cavern::ObjectState> flat_states_;  // objects at ear level (height off)
  int room_rendered_ = 0;      // frames of room_feeds_ the last frame left, 0 if it did not render
  float room_amount_ = 0.25f;
  bool height_ = true;
  float strength_ = 1.0f;
  int last_objects_ = 0;
  int tracked_ = 0;  // objects of the last track_metadata()
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
  int last_truncated_ = 0;
  // Last applied OAMD gain per object (survives seeks — it is stream metadata).
  std::vector<float> obj_gain_;
  // Reused marshaling / deinterleave storage (see frame_scratch etc.).
  std::vector<uint8_t> frame_scratch_;
  std::vector<float> bed_scratch_, out_scratch_;
  std::vector<std::vector<float>> planar_;
  std::vector<const float*> planar_ptrs_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_ATMOS_PIPELINE_H

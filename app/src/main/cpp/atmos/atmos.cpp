// Translation unit for the clean-room Atmos renderer foundation.
//
// The renderer building blocks are header-only (bit_reader / emdf / oamd / vbap)
// so they can be unit-tested on a host toolchain without the NDK. This file
// gives the CMake `monochrome_atmos` target a compilation unit so the NDK build
// type-checks the headers for every shipped ABI (arm64-v8a, armeabi-v7a,
// x86_64) on every CI run, catching portability regressions before they reach a
// device. As the JNI surface and playback wiring land (see atmos/README.md),
// the renderer control code moves here.
#include "bit_reader.h"
#include "cavern/enhanced_ac3.h"
#include "cavern/extensible_metadata_decoder.h"
#include "cavern/joint_object_coding.h"
#include "cavern/joint_object_coding_applier.h"
#include "cavern/object_audio_metadata.h"
#include "cavern/object_info_block.h"
#include "cavern/quadrature_mirror_filterbank.h"
#include "emdf.h"
#include "oamd.h"
#include "render/structural_hrtf.h"
#include "vbap.h"

namespace tf {
namespace atmos {

// Compile-time sanity: the shipped Dolby bed layouts are constructible and the
// LFE channel is flagged so the panner can exclude it.
namespace {

bool atmos_foundation_self_check() {
  LoudspeakerLayout l = LoudspeakerLayout::atmos_7_1_4();
  cavern::QuadratureMirrorFilterBank qmf;  // header type-checks on every ABI
  cavern::JointObjectCoding joc;           // JOC decoder + applier type-check
  cavern::JointObjectCodingApplier applier(joc);
  cavern::ObjectInfoBlock oib;             // OAMD object-info-block type-check
  cavern::ObjectAudioMetadata oam;         // OAMD frame layer type-check
  cavern::ExtensibleMetadataDecoder emdf;   // EMDF container walker type-check
  cavern::EnhancedAC3Header eac3;           // E-AC-3 syncframe header type-check
  render::BinauralRenderer binaural;        // HRTF render engine type-check
  binaural.configure(48000, 16);
  (void)applier;
  (void)oib;
  (void)binaural;
  return l.size() == 12 && l.has_height() && l.at(3).is_lfe &&
         cavern::QuadratureMirrorFilterBank::kSubbands == 64 &&
         cavern::JointObjectCoding::kMaxObjects == 64 &&
         oam.object_count() == 0 && oam.get_lfe_position() == -1 &&
         !emdf.has_objects() && eac3.channel_count() == 0 &&
         cavern::eac3::kSyncWord == 0x0B77 &&
         static_cast<int>(cavern::NonStandardBedChannel::kMax) == 17;
}

}  // namespace

// Referenced from nowhere at runtime yet; kept non-static-inlined so the symbol
// (and therefore the whole header set) is emitted and checked by the linker.
bool AtmosFoundationLinked() { return atmos_foundation_self_check(); }

}  // namespace atmos
}  // namespace tf

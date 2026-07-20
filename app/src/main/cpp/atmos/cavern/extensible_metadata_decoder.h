// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/ExtensibleMetadataDecoder.cs.
// EMDF (Extensible Metadata Delivery Format) rides in the reserved / skip fields
// of an E-AC-3 bitstream and carries the Atmos side-data. This decoder scans for
// the EMDF syncword, walks the payload list, and routes the two payloads a
// renderer cares about — OAMD (id 11, object metadata) and JOC (id 14, upmix
// matrices) — to the already-ported decoders, skipping everything else by size.
//
// Scope: the container framing is reproduced exactly (syncword scan, version/key
// escape, the per-payload config bits and the VariableBits payload sizing, and
// the Position seek to each payload's end). The emdf_protection MAC is not
// validated — a renderer only reads EMDF, matching Cavern.
#ifndef TF_ATMOS_CAVERN_EXTENSIBLE_METADATA_DECODER_H
#define TF_ATMOS_CAVERN_EXTENSIBLE_METADATA_DECODER_H

#include <cstddef>
#include <cstdint>

#include "../bit_reader.h"
#include "joint_object_coding.h"
#include "object_audio_metadata.h"

namespace tf {
namespace atmos {
namespace cavern {

class ExtensibleMetadataDecoder {
 public:
  // True once an EMDF frame carrying a JOC payload has been decoded.
  bool has_objects() const { return has_objects_; }

  const JointObjectCoding& joc() const { return joc_; }
  JointObjectCoding& joc() { return joc_; }
  const ObjectAudioMetadata& oamd() const { return oamd_; }
  ObjectAudioMetadata& oamd() { return oamd_; }

  // Decodes the next EMDF frame from the bitstream, scanning for the syncword.
  void decode(BitReader& br) {
    has_objects_ = false;
    uint32_t syncword = 0;
    const size_t back = br.size_bits();
    // Cavern: `while (Position < BackPosition - 32)`. BackPosition - 32 is int
    // arithmetic there; guard the size_t subtraction against underflow.
    while (back >= 32 && br.position() < back - 32) {
      syncword = ((syncword << 8) & 0xFFFFu) + br.read(8);  // syncwords are byte-padded
      if (syncword == kSyncWord && decode_block(br)) break;
    }
  }

 private:
  // Decodes one EMDF block; returns whether it was a well-formed block. Assumes
  // the syncword was already consumed.
  bool decode_block(BitReader& br) {
    const size_t back = br.size_bits();
    const uint32_t length = br.read(16);
    const size_t frame_end = br.position() + static_cast<size_t>(length) * 8;
    if (frame_end > back) return false;

    int version = static_cast<int>(br.read(2));
    if (version == 3) version += static_cast<int>(br.read_variable_bits(2));
    int key = static_cast<int>(br.read(3));
    if (key == 7) key += static_cast<int>(br.read_variable_bits(3));
    if (version != 0 || key != 0) return false;

    int payload_id;
    while (br.position() < frame_end &&
           (payload_id = static_cast<int>(br.read(5))) != 0) {
      if (payload_id == 0x1F) {
        payload_id += static_cast<int>(br.read_variable_bits(5));
      }
      if (payload_id > kJocPayloadId) return false;

      const bool has_sample_offset = br.read_bit() != 0;
      int sample_offset = 0;
      if (has_sample_offset) {
        sample_offset = static_cast<int>(br.read(12)) >> 1;  // skip 1 bit
      }

      if (br.read_bit()) br.read_variable_bits(11);
      if (br.read_bit()) br.read_variable_bits(2);
      if (br.read_bit()) br.skip(8);

      if (!br.read_bit()) {
        bool frame_aligned = false;
        if (!has_sample_offset) {
          frame_aligned = br.read_bit() != 0;
          if (frame_aligned) br.skip(2);
        }
        if (has_sample_offset || frame_aligned) br.skip(7);
      }

      const size_t payload_end =
          static_cast<size_t>(br.read_variable_bits(8)) * 8 + br.position();
      if (payload_end > back) return false;
      if (payload_id == kJocPayloadId) {
        joc_.decode(br);
        has_objects_ = true;
      } else if (payload_id == kOamdPayloadId) {
        oamd_.decode(br, sample_offset);
      }
      br.seek(payload_end);
    }
    return true;
  }

  static constexpr uint32_t kSyncWord = 0x5838;
  static constexpr int kOamdPayloadId = 11;
  static constexpr int kJocPayloadId = 14;

  bool has_objects_ = false;
  JointObjectCoding joc_;
  ObjectAudioMetadata oamd_;
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_EXTENSIBLE_METADATA_DECODER_H

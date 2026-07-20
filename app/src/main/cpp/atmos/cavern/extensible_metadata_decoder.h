// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/ExtensibleMetadataDecoder.cs —
// the EMDF (Extensible Metadata Delivery Format) container that carries Atmos
// side-data in an E-AC-3 frame. It scans for the EMDF syncword, walks the
// payload list, and hands the OAMD (id 11) and JOC (id 14) payloads to their
// decoders. This is the framing that ties JOC + OAMD together (plan A3).
#ifndef TF_ATMOS_CAVERN_EXTENSIBLE_METADATA_DECODER_H
#define TF_ATMOS_CAVERN_EXTENSIBLE_METADATA_DECODER_H

#include "../bit_reader.h"
#include "joint_object_coding.h"
#include "object_metadata.h"

namespace tf {
namespace atmos {
namespace cavern {

class ExtensibleMetadataDecoder {
 public:
  bool has_objects() const { return has_objects_; }
  JointObjectCoding& joc() { return joc_; }
  ObjectAudioMetadata& oamd() { return oamd_; }

  // Decodes the next EMDF frame from a bitstream.
  void decode(BitReader& br) {
    has_objects_ = false;
    int syncword = 0;
    while (br.position() + 32 < br.back_position()) {
      syncword = ((syncword << 8) & 0xFFFF) + static_cast<int>(br.read(8));  // byte-padded
      if (syncword == kSyncWord && decode_block(br)) break;
    }
  }

 private:
  // Tries to decode an EMDF block (sync word already consumed).
  bool decode_block(BitReader& br) {
    const int length = static_cast<int>(br.read(16));
    const size_t frame_end = br.position() + static_cast<size_t>(length) * 8;
    if (frame_end > br.back_position()) return false;

    int version = static_cast<int>(br.read(2));
    if (version == 3) version += variable_bits(br, 2);
    int key = static_cast<int>(br.read(3));
    if (key == 7) key += variable_bits(br, 3);
    if (version != 0 || key != 0) return false;

    int payload_id;
    while (br.position() < frame_end && (payload_id = static_cast<int>(br.read(5))) != 0) {
      if (payload_id == 0x1F) payload_id += variable_bits(br, 5);
      if (payload_id > kJocPayloadId) return false;

      int sample_offset = 0;
      const bool has_sample_offset = br.read_bit();
      if (has_sample_offset) sample_offset = static_cast<int>(br.read(12)) >> 1;  // skip 1 bit

      if (br.read_bit()) variable_bits(br, 11);
      if (br.read_bit()) variable_bits(br, 2);
      if (br.read_bit()) br.skip(8);

      if (!br.read_bit()) {
        bool frame_aligned = false;
        if (!has_sample_offset) {
          frame_aligned = br.read_bit();
          if (frame_aligned) br.skip(2);
        }
        if (has_sample_offset || frame_aligned) br.skip(7);
      }

      const int payload_bytes = variable_bits(br, 8);
      const size_t payload_end = static_cast<size_t>(payload_bytes) * 8 + br.position();
      if (payload_end > br.back_position()) return false;

      if (payload_id == kJocPayloadId) {
        joc_.decode(br);
        has_objects_ = true;
      } else if (payload_id == kOamdPayloadId) {
        oamd_.decode(br, sample_offset);
      }
      br.set_position(payload_end);
    }
    return true;
  }

  static constexpr int kSyncWord = 0x5838;
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

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

  // Decodes the frame's EMDF container, looking for its syncword.
  //
  // Deviation from Cavern, which decodes the E-AC-3 audio blocks and so knows
  // where the skip field that holds the container starts. Here only the raw
  // frame is in hand, and the container begins wherever the audio blocks
  // before it happened to end: at any bit. So the syncword is looked for at
  // every bit, not every byte. (Byte by byte, this found one container in
  // four, the ones that happened to start on a byte, and the objects of the
  // other three frames were rebuilt with stale matrices and positions.)
  //
  // Sixteen bits of syncword turn up by chance in audio data about once in
  // three frames, so a candidate is walked before anything is decoded from
  // it: every payload must fit, and the container must end exactly where its
  // own length says it does.
  void decode(BitReader& br) {
    has_objects_ = false;
    const size_t back = br.size_bits();
    uint32_t window = 0;
    size_t seen = 0;
    while (br.position() + 32 < back) {
      window = ((window << 1) | br.read_bit()) & 0xFFFFu;
      if (++seen < 16 || window != kSyncWord) continue;
      const size_t at = br.position();
      const bool sound = well_formed(br);
      br.seek(at);
      if (sound && decode_block(br)) break;
      br.seek(at);
    }
  }

 private:
  // One payload's header: its id (0 ends the list) and config. False when it
  // is not one this decoder knows how to pass over.
  static bool read_payload_header(BitReader& br, int& payload_id, int& sample_offset) {
    payload_id = static_cast<int>(br.read(5));
    sample_offset = 0;
    if (payload_id == 0) return true;
    if (payload_id == 0x1F) payload_id += static_cast<int>(br.read_variable_bits(5));
    if (payload_id > kJocPayloadId) return false;

    const bool has_sample_offset = br.read_bit() != 0;
    if (has_sample_offset) sample_offset = static_cast<int>(br.read(12)) >> 1;  // skip 1 bit
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
    return true;
  }

  // Walks a candidate container without decoding it: true when its payloads
  // and its protection fields fill its stated length, to the byte. Assumes
  // the syncword was already consumed.
  static bool well_formed(BitReader& br) {
    const size_t back = br.size_bits();
    const uint32_t length = br.read(16);
    const size_t start = br.position();
    const size_t frame_end = start + static_cast<size_t>(length) * 8;
    if (length == 0 || frame_end > back) return false;

    int version = static_cast<int>(br.read(2));
    if (version == 3) version += static_cast<int>(br.read_variable_bits(2));
    int key = static_cast<int>(br.read(3));
    if (key == 7) key += static_cast<int>(br.read_variable_bits(3));
    if (version != 0 || key != 0) return false;

    int payloads = 0;
    for (;;) {
      if (br.position() + 5 > frame_end) return false;
      int payload_id, sample_offset;
      if (!read_payload_header(br, payload_id, sample_offset)) return false;
      if (payload_id == 0) break;
      if (br.position() >= frame_end) return false;
      const size_t payload_end =
          static_cast<size_t>(br.read_variable_bits(8)) * 8 + br.position();
      if (payload_end > frame_end) return false;
      br.seek(payload_end);
      ++payloads;
    }
    if (payloads == 0) return false;
    // emdf_protection: two lengths, then that many bits of each, and the
    // container is padded to its last byte.
    if (br.position() + 4 > frame_end) return false;
    static const int kPrimary[4] = {-1, 8, 32, 128};
    static const int kSecondary[4] = {0, 8, 32, 128};
    const int primary = kPrimary[br.read(2)];
    const int secondary = kSecondary[br.read(2)];
    if (primary < 0) return false;
    const size_t end = br.position() + static_cast<size_t>(primary + secondary);
    return end <= frame_end && frame_end - end < 8;
  }

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

    while (br.position() < frame_end) {
      int payload_id, sample_offset;
      if (!read_payload_header(br, payload_id, sample_offset)) return false;
      if (payload_id == 0) break;
      const size_t payload_end =
          static_cast<size_t>(br.read_variable_bits(8)) * 8 + br.position();
      if (payload_end > frame_end) return false;
      if (payload_id == kJocPayloadId) {
        joc_.decode(br);
        if (joc_.valid()) has_objects_ = true;
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

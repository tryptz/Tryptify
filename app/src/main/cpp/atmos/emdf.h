// Clean-room EMDF container walker — ETSI TS 102 366 Annex H.
//
// Atmos side-data rides inside the E-AC-3 audio-block skip field as an EMDF
// (Extensible Metadata Delivery Format) container: a version/key header followed
// by a sequence of length-prefixed payloads. Two payload ids matter to a
// renderer — OAMD (object positions) and JOC (upmix matrices); everything else
// is skipped by size.
//
// SCOPE / HONESTY NOTE: this walks the container framing (version + key escape,
// the payload id/size loop, byte alignment) which is enough to *locate* the OAMD
// and JOC byte ranges and is round-trip verifiable against the matching writer
// in the tests. The per-payload *config* block (sample offset, duration, group
// id, codec data, emdf_protection) and the payload bodies themselves must be
// finished against TS 102 366 Annex H / TS 103 420 and validated with reference
// Atmos content on-device — see atmos/README.md. It is deliberately not faked to
// look complete.
//
// Header-only, depends only on bit_reader.h; builds under the NDK and on host.
#ifndef TF_ATMOS_EMDF_H
#define TF_ATMOS_EMDF_H

#include <cstdint>
#include <vector>

#include "bit_reader.h"

namespace tf {
namespace atmos {

// EMDF payload ids used by the object renderer (TS 103 420).
enum class EmdfPayloadId : uint32_t {
  kEnd = 0,   // terminates the payload loop
  kOamd = 11,  // Object Audio Metadata
  kJoc = 14,   // Joint Object Coding
};

struct EmdfPayload {
  uint32_t id = 0;
  size_t byte_offset = 0;  // start of the payload body, from the buffer base
  size_t byte_size = 0;    // body length in bytes
};

struct EmdfContainer {
  uint32_t version = 0;
  uint32_t key_id = 0;
  std::vector<EmdfPayload> payloads;

  const EmdfPayload* find(EmdfPayloadId id) const {
    for (const auto& p : payloads) {
      if (p.id == static_cast<uint32_t>(id)) return &p;
    }
    return nullptr;
  }
};

// Walks the container framing. Returns false if the stream is truncated. Payload
// bodies are not decoded here — only their {id, offset, size} are recorded so
// the OAMD/JOC decoders can be pointed at them.
inline bool walk_emdf(const uint8_t* data, size_t size_bytes,
                      EmdfContainer& out) {
  BitReader br(data, size_bytes);
  out.payloads.clear();

  out.version = br.read(2);
  if (out.version == 3) out.version += br.read_variable_bits(2);
  out.key_id = br.read(3);
  if (out.key_id == 7) out.key_id += br.read_variable_bits(3);

  while (br.remaining() >= 5) {
    uint32_t id = br.read(5);
    if (id == 0) break;                    // kEnd
    if (id == 0x1F) id += br.read_variable_bits(5);

    // Payload byte length (the container's size prefix). The per-payload config
    // block that TS 102 366 places before this is elided (see scope note); the
    // walker treats the size prefix as immediately following the id so the loop
    // stays well-defined and round-trips with the test writer.
    const uint32_t payload_bytes = br.read_variable_bits(8);
    br.byte_align();

    EmdfPayload p;
    p.id = id;
    p.byte_offset = br.position() / 8;
    p.byte_size = payload_bytes;
    out.payloads.push_back(p);

    br.skip(static_cast<size_t>(payload_bytes) * 8);
    if (br.position() > size_bytes * 8) return false;  // truncated
  }
  return true;
}

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_EMDF_H

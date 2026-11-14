// The smallest frame that carries JOC, for the pipeline tests.
#ifndef TF_ATMOS_TESTS_JOC_FRAME_H
#define TF_ATMOS_TESTS_JOC_FRAME_H

#include "bit_writer.h"

// A JOC payload of 5 channels and 1 object, every matrix entry zero.
inline void write_joc(BitWriter& bw) {
  bw.put(3, 0); bw.put(6, 0); bw.put(3, 0);
  bw.put(3, 4); bw.put(5, 0); bw.put(10, 0);
  bw.bit(1); bw.put(3, 0); bw.bit(0); bw.put(1, 0); bw.bit(0); bw.put(1, 0);
  for (int i = 0; i < 5; ++i) bw.bit(0);
}

// That payload (payload id 14) in an EMDF container, behind two bytes that
// are not one: a frame the pipeline takes for JOC with no OAMD.
inline void write_emdf_joc_frame(BitWriter& bw) {
  BitWriter probe; write_joc(probe);
  const unsigned payload_bytes = (unsigned)((probe.count + 7) / 8);
  bw.put(8, 0x00); bw.put(8, 0x11);      // garbage prefix
  bw.put(8, 0x58); bw.put(8, 0x38);      // EMDF syncword
  bw.put(16, 6 + payload_bytes);         // block length
  bw.put(2, 0); bw.put(3, 0);            // version, key
  bw.put(5, 14);                         // payload id = JOC
  bw.bit(0);                             // has_sample_offset
  bw.bit(0); bw.bit(0); bw.bit(0);       // flags
  bw.bit(1);                             // skip alignment block
  bw.put(8, payload_bytes); bw.bit(0);   // VariableBits(8) = payload size
  const size_t body_start = bw.count;
  write_joc(bw);
  while (bw.count < body_start + payload_bytes * 8) bw.bit(0);
  // The end of the container: no more payloads, then emdf_protection (an
  // 8-bit primary field, no secondary one), padded to the byte. The decoder
  // takes a container only when it ends where its length says it does.
  bw.put(5, 0); bw.put(2, 1); bw.put(2, 0); bw.put(8, 0xA5); bw.put(7, 0);
}

#endif  // TF_ATMOS_TESTS_JOC_FRAME_H

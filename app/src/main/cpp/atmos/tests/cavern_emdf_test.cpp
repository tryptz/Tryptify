// Host test for the ported Cavern EMDF container decoder
// (ExtensibleMetadataDecoder) — the syncword scan and payload walk that locates
// OAMD / JOC in an E-AC-3 skip field.
//
//   c++ -std=c++17 -I.. cavern_emdf_test.cpp -o emdf && ./emdf
//
// Wraps a known-good OAMD frame (same bits as cavern_oamd_frame_test) in a full
// EMDF block behind a garbage prefix, and checks the decoder scans to the
// syncword, sizes the payload, routes it to the OAMD decoder, and lands the
// decoded object metadata.
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "cavern/extensible_metadata_decoder.h"

namespace {
int g_fail = 0, g_checks = 0;
void check_near(float a, float b, float tol, const char* e, int line) {
  ++g_checks;
  if (std::fabs(a - b) > tol) {
    ++g_fail;
    std::printf("  FAIL %s:%d %s (%.4f vs %.4f)\n", __FILE__, line, e, a, b);
  }
}
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK_NEAR(a, b, t) check_near((a), (b), (t), #a " ~= " #b, __LINE__)
#define CHECK(c) check((c), #c, __LINE__)

struct BitWriter {
  std::vector<uint8_t> bytes;
  size_t count = 0;
  void bit(int b) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (b & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put(unsigned n, uint32_t v) {
    for (int i = (int)n - 1; i >= 0; --i) bit((v >> i) & 1u);
  }
};

using namespace tf::atmos;
using namespace tf::atmos::cavern;

// The OAMD frame body: dynamic-only program + LFE, one object element with a bed
// object (obj 0) and a dynamic object (obj 1) at an absolute position.
void write_oamd_body(BitWriter& bw) {
  bw.put(2, 0);   // OAver = 0
  bw.put(5, 1);   // object_count = 2
  bw.bit(1);      // dynamic-object-only program
  bw.bit(1);      // LFE present -> 1 bed
  bw.bit(0);      // alternate_object_present = 0
  bw.put(4, 1);   // element_count = 1
  bw.put(4, 1);   // element_index = objectElementIndex
  bw.put(4, 0);   // VariableBits(4,4) group 0 ...
  bw.bit(0);      //   ... continuation 0
  bw.bit(0);      // Skip(1)
  bw.put(2, 0);   // md offset selector 0
  bw.put(3, 0);   // num info blocks = 1
  bw.put(6, 0);   // block_offset_factor = 0
  bw.put(2, 0);   // ramp code 0
  bw.bit(1);      // reserved bit -> no skip
  // obj 0 -> bed
  bw.bit(0); bw.put(2, 0); bw.bit(1); bw.bit(0);
  // obj 1 -> dynamic, absolute (62,0,+15) -> (1.0, 0.0, 1.0)
  bw.bit(0); bw.put(2, 0); bw.bit(1);
  bw.put(6, 62); bw.put(6, 0); bw.bit(1); bw.put(4, 15);
  bw.bit(0); bw.put(4, 0); bw.put(2, 0); bw.bit(0); bw.bit(0); bw.bit(0);
}

void test_emdf_locates_oamd() {
  std::printf("EMDF: scan syncword, size payload, route to OAMD\n");
  BitWriter probe; write_oamd_body(probe);
  const unsigned payload_bytes = (unsigned)((probe.count + 7) / 8);

  BitWriter bw;
  bw.put(8, 0x00); bw.put(8, 0x11);   // garbage prefix (scan must skip it)
  bw.put(8, 0x58); bw.put(8, 0x38);   // EMDF syncword
  bw.put(16, 3 + payload_bytes);      // block length in bytes
  bw.put(2, 0);                       // version 0
  bw.put(3, 0);                       // key 0
  bw.put(5, 11);                      // payload id = OAMD
  bw.bit(0);                          // has_sample_offset = 0
  bw.bit(0); bw.bit(0); bw.bit(0);    // 3 optional-field flags = 0
  bw.bit(1);                          // !ReadBit() false -> skip alignment block
  bw.put(8, payload_bytes); bw.bit(0);  // VariableBits(8) = payload size
  const size_t body_start = bw.count;
  write_oamd_body(bw);
  while (bw.count < body_start + payload_bytes * 8) bw.bit(0);  // pad to payload end

  BitReader br(bw.bytes.data(), bw.bytes.size());
  ExtensibleMetadataDecoder emdf;
  emdf.decode(br);

  CHECK(!emdf.has_objects());  // no JOC payload in this frame
  const ObjectAudioMetadata& o = emdf.oamd();
  CHECK(o.valid());
  CHECK(o.object_count() == 2);
  CHECK(o.beds() == 1);
  CHECK(o.get_lfe_position() == 0);
  CHECK(o.element_count() == 1);
  CHECK(o.element(0).is_object_element());
  CHECK(o.element(0).info_block(1, 0).valid_position());
  CHECK_NEAR(o.element(0).info_block(1, 0).raw_position().x, 1.0f, 1e-3f);
  CHECK_NEAR(o.element(0).info_block(1, 0).raw_position().z, 1.0f, 1e-3f);
}

void test_emdf_no_syncword() {
  std::printf("EMDF: no syncword -> nothing decoded\n");
  BitWriter bw;
  for (int i = 0; i < 20; ++i) bw.put(8, 0xAB);  // never forms 0x5838
  BitReader br(bw.bytes.data(), bw.bytes.size());
  ExtensibleMetadataDecoder emdf;
  emdf.decode(br);
  CHECK(!emdf.has_objects());
  CHECK(emdf.oamd().object_count() == 0);  // default, untouched
}

}  // namespace

int main() {
  std::printf("=== Cavern EMDF (ExtensibleMetadataDecoder) tests ===\n");
  test_emdf_locates_oamd();
  test_emdf_no_syncword();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

// Host tests for the ported OAMD framing + EMDF helpers.
//
//   c++ -std=c++17 -I.. cavern_emdf_test.cpp -o emdf && ./emdf
//
// Covers the deterministic pieces: the EMDF VariableBits helpers, ReadBits'
// reversed fill, and ObjectAudioMetadata program assignment via a crafted OAMD
// header. A full EMDF+OAMD+JOC frame needs reference content to validate.
#include <cstdint>
#include <cstdio>
#include <vector>

#include "cavern/extensible_metadata_decoder.h"
#include "cavern/object_metadata.h"

namespace {
int g_fail = 0, g_checks = 0;
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK(c) check((c), #c, __LINE__)

struct BitWriter {
  std::vector<uint8_t> bytes;
  size_t count = 0;
  void put_bit(int b) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (b & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put(unsigned n, uint32_t v) { for (int i = (int)n - 1; i >= 0; --i) put_bit((v >> i) & 1u); }
  // Inverse of Cavern's VariableBits(bits): groups with the (value+1)<<bits carry.
  void put_variable_bits(unsigned n, uint32_t value) {
    const uint32_t mask = (1u << n) - 1u;
    std::vector<uint32_t> groups;
    uint32_t v = value;
    while (v > mask) { uint32_t g = v & mask; groups.push_back(g); v = ((v - g) >> n) - 1u; }
    put(n, v);
    for (auto it = groups.rbegin(); it != groups.rend(); ++it) { put_bit(1); put(n, *it); }
    put_bit(0);
  }
};

using namespace tf::atmos;
using namespace tf::atmos::cavern;

void test_variable_bits() {
  std::printf("EMDF variable_bits round-trip\n");
  for (unsigned n : {2u, 4u, 5u, 8u}) {
    for (uint32_t v : {0u, 1u, 2u, 7u, 15u, 16u, 31u, 100u, 255u, 1000u}) {
      BitWriter bw;
      bw.put_variable_bits(n, v);
      BitReader br(bw.bytes.data(), bw.bytes.size());
      int got = variable_bits(br, n);
      check(got == static_cast<int>(v), "variable_bits round-trip", __LINE__);
      if (got != static_cast<int>(v)) std::printf("    n=%u v=%u got=%d\n", n, v, got);
    }
  }
}

void test_read_bits() {
  std::printf("read_bits reversed fill\n");
  // First bit read lands in result[n-1]; last in result[0].
  BitWriter bw;
  bw.put_bit(1); bw.put_bit(0); bw.put_bit(0); bw.put_bit(1);  // reading order
  BitReader br(bw.bytes.data(), bw.bytes.size());
  std::vector<char> r = read_bits(br, 4);
  CHECK(r[3] == 1 && r[2] == 0 && r[1] == 0 && r[0] == 1);
}

void test_program_assignment_dynamic_lfe() {
  std::printf("OAMD program assignment (dynamic-only + LFE)\n");
  BitWriter bw;
  bw.put(2, 0);   // version 0
  bw.put(5, 1);   // object_count - 1 = 1 -> 2 objects
  bw.put_bit(1);  // dynamic object-only program
  bw.put_bit(1);  // LFE present
  bw.put_bit(0);  // alternate_object_present = 0
  bw.put(4, 0);   // element_count = 0

  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectAudioMetadata oamd;
  oamd.decode(br, /*offset=*/0);
  CHECK(oamd.valid());
  CHECK(oamd.object_count() == 2);
  CHECK(oamd.beds() == 1);
  CHECK(oamd.lfe_position() == 0);
  CHECK(oamd.element_count() == 0);
}

void test_program_assignment_bed_lfe_only() {
  std::printf("OAMD program assignment (bed objects, single LFE-only bed)\n");
  BitWriter bw;
  bw.put(2, 0);   // version 0
  bw.put(5, 0);   // object_count - 1 = 0 -> 1 object
  bw.put_bit(0);  // not dynamic-only
  bw.put(4, 1);   // content_description = 1 (bed objects)
  bw.put_bit(0);  // distributable skip(1)
  bw.put_bit(0);  // multi-bed flag 0 -> 1 bed
  bw.put_bit(1);  // bed 0: LFE only
  bw.put_bit(0);  // alternate_object_present = 0
  bw.put(4, 0);   // element_count = 0

  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectAudioMetadata oamd;
  oamd.decode(br, 0);
  CHECK(oamd.valid());
  CHECK(oamd.beds() == 1);
  CHECK(oamd.lfe_position() == 0);
}

}  // namespace

int main() {
  std::printf("=== Cavern OAMD framing + EMDF helper tests ===\n");
  test_variable_bits();
  test_read_bits();
  test_program_assignment_dynamic_lfe();
  test_program_assignment_bed_lfe_only();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

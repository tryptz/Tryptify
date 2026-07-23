// Host test for the ported Cavern OAMD ObjectInfoBlock (object position/gain).
//
//   c++ -std=c++17 -I.. cavern_oamd_test.cpp -o oamd && ./oamd
//
// Crafts a render-info block bit-by-bit and checks the decoded absolute
// position, gain and render-space mapping. Also locks in the faithful (buggy)
// ReadSigned behaviour.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "cavern/object_info_block.h"

namespace {
int g_fail = 0, g_checks = 0;
void check_near(float a, float b, float tol, const char* e, int line) {
  ++g_checks;
  if (std::fabs(a - b) > tol) { ++g_fail; std::printf("  FAIL %s:%d %s (%.4f vs %.4f)\n", __FILE__, line, e, a, b); }
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
  void put(unsigned n, uint32_t v) { for (int i = (int)n - 1; i >= 0; --i) bit((v >> i) & 1u); }
};

using namespace tf::atmos;
using namespace tf::atmos::cavern;

void test_read_signed() {
  std::printf("read_signed (faithful/degenerate)\n");
  for (uint32_t v : {0u, 1u, 3u, 7u}) {
    BitWriter bw; bw.put(3, v);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    CHECK(read_signed(br, 3) == 0);  // Cavern's ReadSigned collapses to 0
  }
}

void test_absolute_position() {
  std::printf("ObjectInfoBlock absolute position + gain\n");
  BitWriter bw;
  bw.bit(0);        // inactive = 0 (active)
  // basic info (blk 0 -> read_all): blocks = 3 implicit
  bw.put(2, 0);     // gain_helper 0 -> gain 1.0
  bw.bit(1);        // priority present bit -> !true, no skip
  // render info (blk 0 -> read_all): blocks = 15 implicit
  bw.put(6, 31);    // pos_x = 31 -> 0.5
  bw.put(6, 31);    // pos_y = 31 -> 0.5
  bw.bit(1);        // pos_z sign
  bw.put(4, 0);     // pos_z magnitude 0 -> z = 0
  bw.bit(0);        // distance not specified -> NaN
  bw.put(4, 0);     // zone constraints skip(4)
  bw.put(2, 0);     // size mode 0 -> size 0
  bw.bit(0);        // screen anchoring bit -> off
  bw.bit(0);        // snap skip(1)
  bw.bit(0);        // additional table data -> none

  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectInfoBlock blk;
  blk.update(br, /*blk=*/0, /*bed_or_isf=*/false);

  CHECK(blk.valid_position());
  CHECK(!blk.is_bed());
  CHECK_NEAR(blk.gain(), 0.707f, 1e-3f);          // 1.0 * 0.707 anti-clip
  CHECK_NEAR(blk.raw_position().x, 0.5f, 1e-3f);
  CHECK_NEAR(blk.raw_position().y, 0.5f, 1e-3f);
  CHECK_NEAR(blk.raw_position().z, 0.0f, 1e-3f);
  CHECK_NEAR(blk.size(), 0.0f, 1e-6f);

  // Render-space map: (0.5,0.5,0) -> (x*2-1, z, y*-2+1) = (0, 0, 0) (dead centre).
  Vec3 r = blk.resolved_position();
  CHECK_NEAR(r.x, 0.0f, 1e-3f);
  CHECK_NEAR(r.y, 0.0f, 1e-3f);
  CHECK_NEAR(r.z, 0.0f, 1e-3f);
}

void test_bed_object() {
  std::printf("ObjectInfoBlock bed object -> speaker anchor\n");
  BitWriter bw;
  bw.bit(0);      // active
  // basic info blocks=3: gain_helper + priority
  bw.put(2, 1);   // gain_helper 1 -> gain 0
  bw.bit(1);      // priority bit -> no skip
  // bed_or_isf true -> NO render info read; then additional-table bit
  bw.bit(0);      // additional table data none
  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectInfoBlock blk;
  blk.update(br, /*blk=*/0, /*bed_or_isf=*/true);
  CHECK(blk.is_bed());
  CHECK_NEAR(blk.gain(), 0.0f, 1e-6f);  // gain_helper 1 -> 0 * 0.707
}

}  // namespace

int main() {
  std::printf("=== Cavern OAMD ObjectInfoBlock tests ===\n");
  test_read_signed();
  test_absolute_position();
  test_bed_object();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

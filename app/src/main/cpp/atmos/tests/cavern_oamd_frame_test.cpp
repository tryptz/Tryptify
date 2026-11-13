// Host test for the ported Cavern OAMD frame layer (ObjectAudioMetadata /
// OAElementMD) — object count, program/bed assignment, and the element metadata
// that drives the per-object ObjectInfoBlocks.
//
//   c++ -std=c++17 -I.. cavern_oamd_frame_test.cpp -o oamdf && ./oamdf
//
// Crafts complete OAMD frames bit-by-bit and checks the decoded object count,
// bed/LFE assignment, static channel map, and the object positions that flow
// through the framing into the info blocks. Also locks in the reverse-filled
// ReadBits bit order via a standard bed assignment.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "cavern/object_audio_metadata.h"

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

// A full OAMD frame: dynamic-object-only program + LFE, one object element that
// carries a bed object (obj 0) and a dynamic object (obj 1) with an absolute
// position, one info block each.
void test_frame_object_element() {
  std::printf("ObjectAudioMetadata frame: program + element -> info blocks\n");
  BitWriter bw;
  bw.put(2, 0);   // OAver = 0
  bw.put(5, 1);   // object_count = 1 + 1 = 2

  // ProgramAssignment: dynamic-object-only program, LFE present.
  bw.bit(1);      // b_dyn_object_only_program
  bw.bit(1);      // b_lfe_present -> 1 bed (LFE)

  bw.bit(0);      // alternate_object_present = 0
  bw.put(4, 1);   // element_count = 1

  // --- element[0].Read ---
  bw.put(4, 1);   // element_index = objectElementIndex (object element)
  bw.put(4, 0);   // VariableBits(4,4): value group 0 ...
  bw.bit(0);      //   ... continuation 0 -> size 0 (end_pos is padding, unused)
  bw.bit(0);      // Skip(alternate?5:1) -> Skip(1)

  //   MDUpdateInfo
  bw.put(2, 0);   //   md offset selector 0 -> sampleOffset 0
  bw.put(3, 0);   //   num info blocks = 0 + 1 = 1
  //     BlockUpdateInfo[0]
  bw.put(6, 0);   //     block_offset_factor = 0
  bw.put(2, 0);   //     ramp_duration_code 0 -> rampDurations[0] = 0
  bw.bit(1);      //   reserved read-bit = 1 -> no Skip(5)

  //   obj 0 (< bedOrISF=1) -> BED info block
  bw.bit(0);      //     inactive = 0 (active)
  bw.put(2, 0);   //     gain helper 0 -> 1.0
  bw.bit(1);      //     priority present bit -> no skip
  bw.bit(0);      //     additional table data -> none
  //   obj 1 (>= bedOrISF) -> DYNAMIC info block, absolute position
  bw.bit(0);      //     inactive = 0
  bw.put(2, 0);   //     gain helper 0 -> 1.0
  bw.bit(1);      //     priority present bit -> no skip
  bw.put(6, 62);  //     pos_x = 62 -> 1.0
  bw.put(6, 0);   //     pos_y = 0  -> 0.0
  bw.bit(1);      //     pos_z sign = 1
  bw.put(4, 15);  //     pos_z magnitude = 15 -> z = 1.0
  bw.bit(0);      //     distance not specified
  bw.put(4, 0);   //     zone constraints skip(4)
  bw.put(2, 0);   //     size mode 0 -> 0
  bw.bit(0);      //     screen anchoring -> off
  bw.bit(0);      //     snap skip(1)
  bw.bit(0);      //     additional table data -> none

  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectAudioMetadata oam;
  oam.decode(br, /*offset=*/0);

  CHECK(oam.valid());
  CHECK(oam.object_count() == 2);
  CHECK(oam.beds() == 1);
  CHECK(oam.get_lfe_position() == 0);
  CHECK(oam.element_count() == 1);

  const OAElementMD& e = oam.element(0);
  CHECK(e.is_object_element());
  CHECK(e.block_count() == 1);
  CHECK(e.object_count() == 2);

  CHECK(e.info_block(0, 0).is_bed());       // obj 0 is a bed
  CHECK(!e.info_block(1, 0).is_bed());      // obj 1 is dynamic
  CHECK(e.info_block(1, 0).valid_position());
  CHECK_NEAR(e.info_block(1, 0).raw_position().x, 1.0f, 1e-3f);
  CHECK_NEAR(e.info_block(1, 0).raw_position().y, 0.0f, 1e-3f);
  CHECK_NEAR(e.info_block(1, 0).raw_position().z, 1.0f, 1e-3f);
  CHECK_NEAR(e.info_block(1, 0).gain(), 1.0f, 1e-3f);

  // Render-space map (x*2-1, z, y*-2+1): (1,0,1) -> (1, 1, 1).
  Vec3 r = oam.element(0).info_block(1, 0).resolved_position();
  CHECK_NEAR(r.x, 1.0f, 1e-3f);
  CHECK_NEAR(r.y, 1.0f, 1e-3f);
  CHECK_NEAR(r.z, 1.0f, 1e-3f);
}

// Standard bed assignment via the content-description path. Locks in the
// reverse-filled ReadBits: only standard slot 0 ({FL,FR}) is set, which requires
// the 10th (last) of the 10 assignment bits to be 1.
void test_standard_bed_assignment() {
  std::printf("ObjectAudioMetadata standard bed assignment (reversed ReadBits)\n");
  BitWriter bw;
  bw.put(2, 0);    // OAver = 0
  bw.put(5, 1);    // object_count = 2

  bw.bit(0);       // not dynamic-object-only
  bw.put(4, 1);    // content_description = 1 (bed objects only)
  bw.bit(0);       //   distributable flag skip(1)
  bw.bit(0);       //   b_multiple_beds = 0 -> 1 bed instance
  bw.bit(0);       //   LFE-only = 0
  bw.bit(1);       //   standard bed assignment = 1
  bw.put(10, 1);   //   ReadBits(10): value 1 -> reversed -> standard[0]=1 ({FL,FR})

  bw.bit(0);       // alternate_object_present = 0
  bw.put(4, 0);    // element_count = 0

  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectAudioMetadata oam;
  oam.decode(br, 0);

  CHECK(oam.valid());
  CHECK(oam.beds() == 2);              // FL + FR
  CHECK(oam.get_lfe_position() == -1);  // no LFE

  std::vector<ReferenceChannel> chans = oam.get_static_channels();
  CHECK(chans.size() == 2);
  CHECK(chans.size() == 2 && chans[0] == ReferenceChannel::kFrontLeft);
  CHECK(chans.size() == 2 && chans[1] == ReferenceChannel::kFrontRight);
}

// OAver != 0 must degrade to an invalid frame rather than mis-decoding.
void test_unsupported_version() {
  std::printf("ObjectAudioMetadata unsupported version -> invalid\n");
  BitWriter bw;
  bw.put(2, 1);  // OAver = 1 (unsupported)
  BitReader br(bw.bytes.data(), bw.bytes.size());
  ObjectAudioMetadata oam;
  oam.decode(br, 0);
  CHECK(!oam.valid());
}

}  // namespace

// trim_element (clause 5.5.12): warp_mode, then nine trim configurations each
// on the default trim, disabled, or with trims of their own, then the
// per-object switch.
void test_trim_element() {
  std::printf("OAMD: trim element (warp mode, trim configurations)\n");
  BitWriter bw;
  bw.put(4, 2);   // element_index = trim element
  bw.put(4, 3);   // oa_element_size: 3 + 1 = 4 bytes follow
  bw.bit(0);
  bw.bit(0);      // b_discard_unknown_element
  bw.put(2, 1);   // warp_mode 1: Y doubled
  bw.put(2, 0);   // reserved
  bw.put(2, 2);   // global_trim_mode: custom
  bw.bit(1);      // cfg 0: default
  bw.bit(0); bw.bit(1);                    // cfg 1: disabled
  bw.bit(0); bw.bit(0); bw.put(5, 0x08);   // cfg 2: its own, a surround trim only
  bw.put(4, 7);                            //        -4.5 dB (Table 36)
  for (int cfg = 3; cfg < 9; ++cfg) bw.bit(1);  // the rest: default
  bw.bit(1);      // b_disable_trim_per_obj
  bw.bit(0); bw.bit(1); bw.bit(0);
  bw.put(8, 0);   // padding
  BitReader br(bw.bytes.data(), bw.bytes.size());
  OAElementMD el;
  CHECK(el.read(br, false, 3, 1));
  CHECK(el.is_trim_element());
  CHECK(el.warp_mode() == 1);
  CHECK_NEAR(el.surround_trim_db(0, 0, -3.0f), -3.0f, 1e-6f);  // default: the renderer's own
  CHECK_NEAR(el.surround_trim_db(1, 0, -3.0f), 0.0f, 1e-6f);   // disabled
  CHECK_NEAR(el.surround_trim_db(2, 0, -3.0f), -4.5f, 1e-6f);  // transmitted
  CHECK_NEAR(el.surround_trim_db(8, 2, -3.0f), -3.0f, 1e-6f);
  CHECK_NEAR(el.surround_trim_db(0, 1, -3.0f), 0.0f, 1e-6f);   // off for object 1
  CHECK_NEAR(el.surround_trim_db(9, 0, -3.0f), 0.0f, 1e-6f);   // no such configuration
  CHECK(br.position() == 9 + 32);  // left at the element's end

  // Global modes with nothing per configuration.
  for (int mode = 0; mode < 2; ++mode) {
    BitWriter g;
    g.put(4, 2); g.put(4, 0); g.bit(0); g.bit(0);
    g.put(2, 0); g.put(2, 0); g.put(2, mode); g.bit(0);
    g.put(8, 0);
    BitReader gr(g.bytes.data(), g.bytes.size());
    OAElementMD e2;
    CHECK(e2.read(gr, false, 2, 1));
    CHECK(e2.warp_mode() == 0);
    CHECK_NEAR(e2.surround_trim_db(1, 0, -3.0f), mode == 0 ? -3.0f : 0.0f, 1e-6f);
  }
}

int main() {
  std::printf("=== Cavern OAMD frame (ObjectAudioMetadata) tests ===\n");
  test_frame_object_element();
  test_standard_bed_assignment();
  test_unsupported_version();
  test_trim_element();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

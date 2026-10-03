// Host test for the spec-driven OAMD state resolution (ObjectAudioMetadata::
// resolve / ObjectState) and the element framing it depends on.
//
//   c++ -std=c++17 -I.. cavern_oamd_resolve_test.cpp -o oamdr && ./oamdr
//
// Builds complete OAMD frames bit-by-bit, with each oa_element's size written
// in BYTES as TS 103 420 clause 5.6.4.3 defines it, and checks what a renderer
// receives: differential positions relative to the previous block, hold of
// unsignalled fields on a mixed update, object_gain_idx 3, inactive objects,
// the extended_object_element (divergence + extended precision) after the
// object element, the distance and screen transforms, and bed positions.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "cavern/object_audio_metadata.h"

namespace {
int g_fail = 0, g_checks = 0;
void check_near(float a, float b, float tol, const char* e, int line) {
  ++g_checks;
  if (!(std::fabs(a - b) <= tol)) {
    ++g_fail;
    std::printf("  FAIL %s:%d %s (%.5f vs %.5f)\n", __FILE__, line, e, a, b);
  }
}
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK_NEAR(a, b, t) check_near((a), (b), (t), #a " ~= " #b, __LINE__)
#define CHECK(c) check((c), #c, __LINE__)

// Bit list writer; bytes() packs MSB-first like the bitstream.
struct Bits {
  std::vector<int> v;
  void bit(int b) { v.push_back(b & 1); }
  void put(unsigned n, uint32_t x) { for (int i = (int)n - 1; i >= 0; --i) bit((x >> i) & 1u); }
  void append(const Bits& o) { v.insert(v.end(), o.v.begin(), o.v.end()); }
  std::vector<uint8_t> bytes() const {
    std::vector<uint8_t> out((v.size() + 7) / 8, 0);
    for (size_t i = 0; i < v.size(); ++i) if (v[i]) out[i / 8] |= (1u << (7 - i % 8));
    return out;
  }
};

using namespace tf::atmos;
using namespace tf::atmos::cavern;

// oa_element_md: id, variable_bits(4,4) byte size, b_discard_unknown_element,
// then the body, padded with zeros to the signalled size.
void element(Bits& out, int id, const Bits& body) {
  const size_t after_size_bits = 1 + body.v.size();
  const uint32_t bytes = static_cast<uint32_t>((after_size_bits + 7) / 8);
  const uint32_t coded = bytes - 1;
  out.put(4, id);
  if (coded < 16) {
    out.put(4, coded); out.bit(0);
  } else {  // two groups: ((g1 + 1) << 4) + g2
    out.put(4, (coded >> 4) - 1); out.bit(1); out.put(4, coded & 15); out.bit(0);
  }
  out.bit(0);  // b_discard_unknown_element
  out.append(body);
  for (size_t i = after_size_bits; i < bytes * 8u; ++i) out.bit(0);
}

// Frame header: OAver 0, object_count, dynamic-object-only program (+LFE bed).
void header(Bits& b, int objects, bool lfe, int elements) {
  b.put(2, 0);
  b.put(5, objects - 1);
  b.bit(1);           // b_dyn_object_only_program
  b.bit(lfe ? 1 : 0); // b_lfe_present
  b.bit(0);           // b_alternate_object_data_present
  b.put(4, elements);
}

// md_update_info with `blocks` blocks, no ramps; + b_reserved_data_not_present.
void update_info(Bits& b, int blocks) {
  b.put(2, 0);
  b.put(3, blocks - 1);
  for (int i = 0; i < blocks; ++i) { b.put(6, 0); b.put(2, 0); }
  b.bit(1);
}

// Absolute full-update info block (blk 0 form) for a dynamic object.
void abs_block(Bits& b, int x, int y, int z, int gain_idx = 0, int size_bits = -1,
               int zone = 0, bool elevation = true, bool snap = false, int distance_idx = -1,
               int screen_factor_bits = -1) {
  b.bit(0);                    // active
  b.put(2, gain_idx);          // object_gain_idx
  if (gain_idx == 2) b.put(6, 0);
  b.bit(1);                    // b_default_object_priority
  b.put(6, x); b.put(6, y);
  b.bit(z >= 0 ? 1 : 0); b.put(4, z < 0 ? -z : z);
  if (distance_idx < 0) b.bit(0); else { b.bit(1); b.bit(0); b.put(4, distance_idx); }
  b.put(3, zone); b.bit(elevation ? 1 : 0);
  if (size_bits < 0) b.put(2, 0); else { b.put(2, 1); b.put(5, size_bits); }
  if (screen_factor_bits < 0) b.bit(0); else { b.bit(1); b.put(3, screen_factor_bits); b.put(2, 2); }
  b.bit(snap ? 1 : 0);
  b.bit(0);                    // no additional table data
}

ObjectAudioMetadata decode(const Bits& frame) {
  const std::vector<uint8_t> bytes = frame.bytes();
  BitReader br(bytes.data(), bytes.size());
  ObjectAudioMetadata oam;
  oam.decode(br, 0);
  return oam;
}

// Block 1 moves the object by a differential (+3, -4, +2) — relative to block
// 0's coded position — and signals nothing else (mixed update, position only),
// so zone/size/snap hold block 0's values.
void test_differential_and_hold() {
  std::printf("differential position vs previous block; mixed update holds fields\n");
  Bits body;
  update_info(body, 2);
  // blk 0
  abs_block(body, 31, 31, 5, 0, /*size=*/31, /*zone=*/4, /*elev=*/false, /*snap=*/true);
  // blk 1: active, basic status 2 (reuse), render status 3 (mixed), flags = position only
  body.bit(0);
  body.put(2, 2);
  body.put(2, 3);
  body.put(4, 1);              // obj_render_info flags: position
  body.bit(1);                 // b_differential_position_specified
  body.put(3, 3); body.put(3, 4); body.put(3, 2);  // +3, -4, +2
  body.bit(0);                 // no distance
  body.bit(0);                 // snap = false
  body.bit(0);                 // no additional table data
  Bits frame;
  header(frame, 1, false, 1);
  element(frame, 1, body);
  ObjectAudioMetadata oam = decode(frame);
  CHECK(oam.valid());
  CHECK(oam.state_count() == 1);
  const ObjectState& st = oam.state(0);
  CHECK(st.active);
  CHECK(st.pos_bits[0] == 34);
  CHECK(st.pos_bits[1] == 27);
  CHECK(st.pos_bits[2] == 7);
  CHECK_NEAR(st.room.x, 34.0f / 62.0f, 1e-5f);
  CHECK_NEAR(st.room.y, 27.0f / 62.0f, 1e-5f);
  CHECK_NEAR(st.room.z, 7.0f / 15.0f, 1e-5f);
  CHECK_NEAR(st.width, 1.0f, 1e-5f);        // held from block 0
  CHECK(st.zone_constraints_idx == 4);      // held
  CHECK(!st.enable_elevation);              // held
  CHECK(!st.snap);                          // b_object_snap is re-sent every render info
  CHECK_NEAR(st.gain, 0.707f, 1e-4f);       // basic info reused
  // render space = (2x-1, z, 1-2y)
  CHECK_NEAR(st.render.x, 2.0f * 34 / 62 - 1, 1e-5f);
  CHECK_NEAR(st.render.y, 7.0f / 15.0f, 1e-5f);
  CHECK_NEAR(st.render.z, 1.0f - 2.0f * 27 / 62, 1e-5f);
}

// A negative differential below the floor clamps to Z = -1 (not 0).
void test_z_clamp_below_floor() {
  std::printf("differential Z clamps to [-1, 1]\n");
  Bits body;
  update_info(body, 2);
  abs_block(body, 31, 31, -13);
  body.bit(0); body.put(2, 2); body.put(2, 3); body.put(4, 1);
  body.bit(1); body.put(3, 0); body.put(3, 0); body.put(3, 4);  // dz = -4
  body.bit(0); body.bit(0); body.bit(0);
  Bits frame;
  header(frame, 1, false, 1);
  element(frame, 1, body);
  ObjectAudioMetadata oam = decode(frame);
  CHECK(oam.state(0).pos_bits[2] == -15);
  CHECK_NEAR(oam.state(0).room.z, -1.0f, 1e-5f);
}

// object_gain_idx 3 copies the PREVIOUS object's gain in the same block, and an
// inactive object is silent.
void test_gain_idx3_and_inactive() {
  std::printf("gain idx 3 = previous object; inactive object is silent\n");
  Bits body;
  update_info(body, 1);
  abs_block(body, 10, 10, 0, /*gain_idx=*/2);  // obj0: coded gain bits 0 -> +15 dB
  abs_block(body, 20, 20, 0, /*gain_idx=*/3);  // obj1: same as obj0
  body.bit(1);                                  // obj2: inactive
  body.bit(0);                                  //   no additional table data
  Bits frame;
  header(frame, 3, false, 1);
  element(frame, 1, body);
  ObjectAudioMetadata oam = decode(frame);
  CHECK(oam.valid());
  const float g0 = oam.state(0).gain;
  CHECK_NEAR(g0, std::pow(10.0f, 15.0f / 20.0f) * 0.707f, 1e-3f);
  CHECK_NEAR(oam.state(1).gain, g0, 1e-6f);
  CHECK(!oam.state(2).active);
  CHECK_NEAR(oam.state(2).gain, 0.0f, 1e-9f);
}

// Two elements: the object element, then an extended_object_element carrying
// divergence (table index 1) and extended precision (+2 fifths on X). Only
// reachable if the first element's size is honoured in bytes.
void test_extended_element() {
  std::printf("extended_object_element after the object element (byte-sized elements)\n");
  Bits obj;
  update_info(obj, 1);
  abs_block(obj, 31, 31, 0);
  Bits ext;
  ext.bit(1);             // b_obj_div_block
  ext.bit(1);             //   b_object_divergence
  ext.put(2, 0);          //   object_div_mode 0 -> table
  ext.put(2, 1);          //   object_div_table 1 -> 0.608529
  ext.bit(1);             // b_ext_prec_pos_block
  ext.bit(1);             //   b_ext_prec_pos
  ext.put(3, 4);          //   presence: X only
  ext.put(2, 2);          //   ext_prec_pos3D_X = 2
  Bits frame;
  header(frame, 1, false, 2);
  element(frame, 1, obj);
  element(frame, 5, ext);
  ObjectAudioMetadata oam = decode(frame);
  CHECK(oam.valid());
  CHECK(oam.element_count() == 2);
  CHECK(oam.element(1).is_extended_element());
  const ObjectState& st = oam.state(0);
  CHECK_NEAR(st.divergence, 0.608529f, 1e-5f);
  CHECK(st.ext_prec[0] == 2);
  CHECK_NEAR(st.room.x, 31.0f / 62.0f + 2.0f / 310.0f, 1e-6f);
}

// Distance factor 2.0 (idx 3) on an object at the front-wall centre projects it
// to twice the wall distance from O = (0.5, 0.5, 0): y = -0.5.
void test_distance_projection() {
  std::printf("distance factor projects outside the room (clause 5.2.1.2)\n");
  Bits body;
  update_info(body, 1);
  abs_block(body, 31, 0, 0, 0, -1, 0, true, false, /*distance_idx=*/3);
  Bits frame;
  header(frame, 1, false, 1);
  element(frame, 1, body);
  ObjectAudioMetadata oam = decode(frame);
  CHECK_NEAR(oam.state(0).room.x, 0.5f, 1e-5f);
  CHECK_NEAR(oam.state(0).room.y, -0.5f, 1e-5f);
  CHECK_NEAR(oam.state(0).room.z, 0.0f, 1e-5f);
}

// Screen-anchored object at the screen plane (y = 0): M2 = 0, so it sits
// exactly at C_R, the screen coordinate mapped onto the default screen.
void test_screen_anchor() {
  std::printf("screen-anchored object at the screen plane (clause 5.2.1.3)\n");
  Bits body;
  update_info(body, 1);
  // x = 62 (right screen edge), z = 0 (screen middle), screen_factor_bits 7 -> 1.0
  abs_block(body, 62, 0, 0, 0, -1, 0, true, false, -1, /*screen_factor_bits=*/7);
  Bits frame;
  header(frame, 1, false, 1);
  element(frame, 1, body);
  ObjectAudioMetadata oam = decode(frame);
  const ObjectState& st = oam.state(0);
  CHECK(st.screen_ref);
  CHECK_NEAR(st.room.x, 0.5f + kScreenWidth / 2.0f, 1e-5f);
  CHECK_NEAR(st.room.z, kScreenHeight / 2.0f, 1e-5f);
}

// Bed objects render at their channel's nominal speaker, not at the cube origin.
void test_bed_positions() {
  std::printf("bed objects take their channel position\n");
  Bits body;
  update_info(body, 1);
  body.bit(0); body.put(2, 0); body.bit(1); body.bit(0);  // obj0 = LFE bed object
  abs_block(body, 0, 0, 0);                                // obj1 dynamic
  Bits frame;
  header(frame, 2, /*lfe=*/true, 1);
  element(frame, 1, body);
  ObjectAudioMetadata oam = decode(frame);
  CHECK(oam.state(0).is_bed);
  CHECK(oam.state(0).bed_channel == ReferenceChannel::kScreenLFE);
  CHECK(!oam.state(1).is_bed);
  // Dynamic object at the front-left corner: render (-1, 0, 1).
  CHECK_NEAR(oam.state(1).render.x, -1.0f, 1e-5f);
  CHECK_NEAR(oam.state(1).render.z, 1.0f, 1e-5f);
}

}  // namespace

int main() {
  std::printf("=== OAMD state resolution tests ===\n");
  test_differential_and_hold();
  test_z_clamp_below_floor();
  test_gain_idx3_and_inactive();
  test_extended_element();
  test_distance_projection();
  test_screen_anchor();
  test_bed_positions();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

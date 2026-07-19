// Host-compilable unit tests for the clean-room Atmos foundation pieces.
//
// These use only the C++17 standard library and a tiny assert harness — no
// gtest — so they run on any host toolchain without the NDK:
//
//   c++ -std=c++17 -I.. atmos_tests.cpp -o atmos_tests && ./atmos_tests
//
// They verify the parts that are provable without reference Dolby content: the
// bit reader / variable_bits round-trip, the EMDF container walk, the OAMD
// coordinate math, and the VBAP panner's geometric invariants.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "../bit_reader.h"
#include "../emdf.h"
#include "../oamd.h"
#include "../vbap.h"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool cond, const char* expr, const char* file, int line) {
  ++g_checks;
  if (!cond) {
    ++g_failures;
    std::printf("  FAIL %s:%d  %s\n", file, line, expr);
  }
}

void check_near(float a, float b, float tol, const char* expr, const char* file,
                int line) {
  ++g_checks;
  if (std::fabs(a - b) > tol) {
    ++g_failures;
    std::printf("  FAIL %s:%d  %s  (%.6f vs %.6f)\n", file, line, expr, a, b);
  }
}

#define CHECK(c) check((c), #c, __FILE__, __LINE__)
#define CHECK_NEAR(a, b, tol) check_near((a), (b), (tol), #a " ~= " #b, __FILE__, __LINE__)

// ---- Bit writer mirroring BitReader (test-only) ----------------------------
class BitWriter {
 public:
  void put_bit(uint32_t bit) {
    if ((count_ & 7u) == 0) bytes_.push_back(0);
    if (bit & 1u) bytes_.back() |= (1u << (7u - (count_ & 7u)));
    ++count_;
  }
  void put(unsigned n, uint32_t value) {
    for (int i = static_cast<int>(n) - 1; i >= 0; --i) put_bit((value >> i) & 1u);
  }
  void byte_align() {
    while ((count_ & 7u) != 0) put_bit(0);
  }
  // Inverse of BitReader::read_variable_bits.
  void put_variable_bits(unsigned n, uint32_t value) {
    const uint32_t mask = (n >= 32) ? 0xFFFFFFFFu : ((1u << n) - 1u);
    std::vector<uint32_t> groups;
    uint32_t v = value;
    while (v > mask) {
      const uint32_t g = v & mask;
      groups.push_back(g);
      v = ((v - g) >> n) - 1u;
    }
    put(n, v);  // first group g0
    for (auto it = groups.rbegin(); it != groups.rend(); ++it) {
      put_bit(1);
      put(n, *it);
    }
    put_bit(0);
  }
  const std::vector<uint8_t>& bytes() const { return bytes_; }

 private:
  std::vector<uint8_t> bytes_;
  size_t count_ = 0;
};

// ---- Tests -----------------------------------------------------------------

void test_bit_reader_basic() {
  std::printf("bit_reader_basic\n");
  const uint8_t data[] = {0b10110100, 0b00101111};
  tf::atmos::BitReader br(data, sizeof(data));
  CHECK(br.read(1) == 1);
  CHECK(br.read(3) == 0b011);
  CHECK(br.read(4) == 0b0100);
  CHECK(br.read(8) == 0b00101111);
  CHECK(br.remaining() == 0);
}

void test_variable_bits_roundtrip() {
  std::printf("variable_bits_roundtrip\n");
  const uint32_t values[] = {0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 62, 63, 64,
                             255, 256, 1000, 65535, 100000};
  for (unsigned n : {2u, 3u, 5u, 8u}) {
    for (uint32_t v : values) {
      BitWriter bw;
      bw.put_variable_bits(n, v);
      tf::atmos::BitReader br(bw.bytes().data(), bw.bytes().size());
      const uint32_t got = br.read_variable_bits(n);
      check(got == v, "variable_bits roundtrip", __FILE__, __LINE__);
      if (got != v) std::printf("    n=%u v=%u got=%u\n", n, v, got);
    }
  }
}

void test_emdf_walk() {
  std::printf("emdf_walk\n");
  using namespace tf::atmos;
  // Build a container with a JOC then an OAMD payload, then the end marker.
  BitWriter bw;
  bw.put(2, 1);               // version (no escape)
  bw.put(3, 2);               // key_id (no escape)
  // JOC payload, id 14, 3 bytes body.
  bw.put(5, 14);
  bw.put_variable_bits(8, 3);
  bw.byte_align();
  bw.put(8, 0xAA); bw.put(8, 0xBB); bw.put(8, 0xCC);
  // OAMD payload, id 11, 2 bytes body.
  bw.put(5, 11);
  bw.put_variable_bits(8, 2);
  bw.byte_align();
  bw.put(8, 0x12); bw.put(8, 0x34);
  // End marker.
  bw.put(5, 0);
  bw.byte_align();

  EmdfContainer c;
  CHECK(walk_emdf(bw.bytes().data(), bw.bytes().size(), c));
  CHECK(c.version == 1);
  CHECK(c.key_id == 2);
  CHECK(c.payloads.size() == 2);
  const EmdfPayload* joc = c.find(EmdfPayloadId::kJoc);
  const EmdfPayload* oamd = c.find(EmdfPayloadId::kOamd);
  CHECK(joc != nullptr);
  CHECK(oamd != nullptr);
  if (joc) {
    CHECK(joc->byte_size == 3);
    CHECK(bw.bytes()[joc->byte_offset] == 0xAA);
  }
  if (oamd) {
    CHECK(oamd->byte_size == 2);
    CHECK(bw.bytes()[oamd->byte_offset] == 0x12);
  }
}

void test_oamd_coordinates() {
  std::printf("oamd_coordinates\n");
  using namespace tf::atmos;
  // Absolute decode boundaries.
  CHECK_NEAR(decode_absolute_xy(0), 0.0f, 1e-6f);
  CHECK_NEAR(decode_absolute_xy(62), 1.0f, 1e-6f);
  CHECK_NEAR(decode_absolute_z(0, 15), 1.0f, 1e-6f);
  CHECK_NEAR(decode_absolute_z(1, 15), -1.0f, 1e-6f);

  // Render-space mapping: dead center -> origin.
  ObjectPositionNorm center{0.5f, 0.5f, 0.5f};
  Vec3 rc = map_to_render_space(center);
  CHECK_NEAR(rc.x, 0.0f, 1e-6f);
  CHECK_NEAR(rc.y, 0.0f, 1e-6f);
  CHECK_NEAR(rc.z, 0.0f, 1e-6f);

  // Front-left-floor corner -> (-1 left, +1 front, -1 floor).
  ObjectPositionNorm corner{0.0f, 0.0f, 0.0f};
  Vec3 rcorner = map_to_render_space(corner);
  CHECK_NEAR(rcorner.x, -1.0f, 1e-6f);
  CHECK_NEAR(rcorner.y, 1.0f, 1e-6f);
  CHECK_NEAR(rcorner.z, -1.0f, 1e-6f);

  // Differential accumulation with clamping.
  CHECK_NEAR(apply_differential(0.5f, 2, kOamdXyScale), 0.5f + 2.0f / 62.0f, 1e-6f);
  CHECK_NEAR(clamp_unit(1.4f), 1.0f, 1e-6f);
  CHECK_NEAR(clamp_unit(-0.2f), 0.0f, 1e-6f);
}

float sumsq(const std::vector<float>& g) {
  float s = 0;
  for (float x : g) s += x * x;
  return s;
}
int nonzero(const std::vector<float>& g) {
  int c = 0;
  for (float x : g) if (x > 1e-4f) ++c;
  return c;
}

void test_vbap_2d() {
  std::printf("vbap_2d (5.1)\n");
  using namespace tf::atmos;
  auto layout = LoudspeakerLayout::surround_5_1();  // L R C LFE Ls Rs
  std::vector<float> g;

  // Source exactly at L (-30 az) -> essentially all energy in L (index 0).
  pan_vbap(layout, direction_from_angles(-30, 0), g);
  CHECK_NEAR(g[0], 1.0f, 1e-3f);
  CHECK_NEAR(sumsq(g), 1.0f, 1e-3f);
  CHECK(g[3] == 0.0f);  // LFE never panned

  // Source between C (0) and R (+30) -> only those two active, both positive.
  pan_vbap(layout, direction_from_angles(15, 0), g);
  CHECK(g[1] > 0.0f);   // R
  CHECK(g[2] > 0.0f);   // C
  CHECK(nonzero(g) == 2);
  CHECK_NEAR(sumsq(g), 1.0f, 1e-3f);

  // Energy is preserved for a sweep of arbitrary directions on the ring.
  for (int a = -180; a < 180; a += 7) {
    pan_vbap(layout, direction_from_angles(static_cast<float>(a), 0), g);
    CHECK_NEAR(sumsq(g), 1.0f, 2e-3f);
    for (float x : g) CHECK(x >= 0.0f);
  }
}

void test_vbap_3d() {
  std::printf("vbap_3d (7.1.4)\n");
  using namespace tf::atmos;
  auto layout = LoudspeakerLayout::atmos_7_1_4();
  CHECK(layout.has_height());
  std::vector<float> g;

  // Source at the front-left top speaker (index 8: -45 az / +45 el).
  pan_vbap(layout, direction_from_angles(-45, 45), g);
  CHECK_NEAR(g[8], 1.0f, 1e-3f);
  CHECK_NEAR(sumsq(g), 1.0f, 1e-3f);

  // An elevated front-center source recruits the two front top speakers and
  // stays energy-normalized; LFE stays silent.
  pan_vbap(layout, direction_from_angles(0, 40), g);
  CHECK_NEAR(sumsq(g), 1.0f, 3e-3f);
  CHECK(g[3] == 0.0f);
  CHECK(nonzero(g) <= 3);
  for (float x : g) CHECK(x >= 0.0f);
}

}  // namespace

int main() {
  std::printf("=== Atmos foundation tests ===\n");
  test_bit_reader_basic();
  test_variable_bits_roundtrip();
  test_emdf_walk();
  test_oamd_coordinates();
  test_vbap_2d();
  test_vbap_3d();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

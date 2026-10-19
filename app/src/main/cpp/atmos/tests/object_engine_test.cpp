// Host test for the Atmos ObjectEngine (plan P1): bed PCM + JOC -> object PCM.
//
//   c++ -std=c++17 -I.. object_engine_test.cpp -o oe && ./oe
//
// Drives the engine's per-frame 24-timeslot upmix loop with a valid (matrix-
// coded, 5-channel, 1-object) JOC built the same way as cavern_joc_test, plus a
// non-Atmos frame. Like the JOC test, this proves the plumbing (sizing, reuse,
// finiteness) — full numeric correctness needs reference Atmos content.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "object_engine.h"

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
  void put_bit(int bit) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (bit & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put(unsigned n, uint32_t v) {
    for (int i = (int)n - 1; i >= 0; --i) put_bit((v >> i) & 1u);
  }
};

using namespace tf::atmos;

// A valid JOC bitstream: 5 channels, 1 object, 1 band, coarse zero matrix, gain 1.
void write_joc(BitWriter& bw) {
  bw.put(3, 0);   // downmix_config 0 -> 5 channels
  bw.put(6, 0);   // object_count-1 = 0 -> 1 object
  bw.put(3, 0);   // joc_ext_config_idx = 0
  bw.put(3, 4);   // gain_power 4
  bw.put(5, 0);   // gain mantissa 0 -> gain 1.0
  bw.put(10, 0);  // sequence counter
  bw.put_bit(1);  // object 0 active
  bw.put(3, 0);   // bands_index 0 -> 1 band
  bw.put_bit(0);  // not sparse
  bw.put(1, 0);   // quant table 0 (coarse)
  bw.put_bit(0);  // not steep
  bw.put(1, 0);   // data_points = 1
  for (int i = 0; i < 5; ++i) bw.put_bit(0);  // 5 channels x 1 band, symbol 0
}

void test_upmix_smoke() {
  std::printf("ObjectEngine: bed + JOC -> objects (24-timeslot frame loop)\n");
  BitWriter bw;
  write_joc(bw);
  BitReader br(bw.bytes.data(), bw.bytes.size());
  cavern::JointObjectCoding joc;
  joc.decode(br);
  CHECK(joc.valid() && joc.channel_count() == 5 && joc.object_count() == 1);

  const int samples = 1536;
  std::vector<std::vector<float>> bed(5, std::vector<float>(samples));
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i)
      bed[ch][i] = 0.25f * std::sin((i + ch * 7) * 0.05f);  // non-zero source
  std::vector<const float*> bed_ptrs(5);
  for (int ch = 0; ch < 5; ++ch) bed_ptrs[ch] = bed[ch].data();

  ObjectEngine engine;
  const int n = engine.upmix(joc, bed_ptrs.data(), 5, samples);
  CHECK(n == 1);
  CHECK(engine.object_count() == 1);
  CHECK(engine.frame_samples() == samples);

  const float* obj0 = engine.object_channel(0);
  CHECK(obj0 != nullptr);
  bool finite = true;
  for (int i = 0; i < samples; ++i) if (!std::isfinite(obj0[i])) finite = false;
  CHECK(finite);
  CHECK(engine.object_channel(1) == nullptr);  // out of range

  // Reuse path: a second upmix must reconfigure cleanly and stay valid.
  const int n2 = engine.upmix(joc, bed_ptrs.data(), 5, samples);
  CHECK(n2 == 1 && engine.object_channel(0) != nullptr);

  // Reject a non-multiple-of-64 frame size.
  CHECK(engine.upmix(joc, bed_ptrs.data(), 5, 1000) == -1);
}

void test_no_joc_frame() {
  std::printf("ObjectEngine: non-Atmos frame -> -1\n");
  std::vector<uint8_t> frame(64, 0xAB);  // no EMDF syncword
  std::vector<float> ch(1536, 0.1f);
  const float* bed[1] = {ch.data()};
  ObjectEngine engine;
  CHECK(engine.upmix_frame(frame.data(), frame.size(), bed, 1, 1536) == -1);
}

// Wrap a JOC body (payload id 14) in an EMDF block behind a garbage prefix.
void write_emdf_joc_frame(BitWriter& bw) {
  BitWriter probe; write_joc(probe);
  const unsigned payload_bytes = (unsigned)((probe.count + 7) / 8);
  bw.put(8, 0x00); bw.put(8, 0x11);      // garbage prefix
  bw.put(8, 0x58); bw.put(8, 0x38);      // EMDF syncword
  bw.put(16, 3 + payload_bytes);         // block length
  bw.put(2, 0); bw.put(3, 0);            // version, key
  bw.put(5, 14);                         // payload id = JOC
  bw.put(1, 0);                          // has_sample_offset
  bw.put(1, 0); bw.put(1, 0); bw.put(1, 0);
  bw.put(1, 1);                          // skip alignment block
  bw.put(8, payload_bytes); bw.put(1, 0);  // VariableBits(8) = payload size
  const size_t body_start = bw.count;
  write_joc(bw);
  while (bw.count < body_start + payload_bytes * 8) bw.put(1, 0);
}

// JOC rides in only ~22% of E-AC-3 frames and is defined to hold until the next
// update, so the frames in between must keep rendering with the retained
// matrices rather than falling back — alternating the two is audible chopping.
void test_metadata_hold() {
  std::printf("ObjectEngine: JOC held across frames that carry none\n");
  BitWriter fw; write_emdf_joc_frame(fw);
  std::vector<uint8_t> bare(64, 0xAB);  // no EMDF syncword
  const int samples = 1536;
  std::vector<std::vector<float>> bed(5, std::vector<float>(samples));
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i) bed[ch][i] = 0.25f * std::sin(i * 0.05f);
  std::vector<const float*> ptrs(5);
  for (int ch = 0; ch < 5; ++ch) ptrs[ch] = bed[ch].data();

  ObjectEngine engine;
  CHECK(engine.upmix_frame(fw.bytes.data(), fw.bytes.size(), ptrs.data(), 5, samples) == 1);
  // The gap frames a real stream leaves between JOC payloads (~4-5) must render.
  for (int i = 0; i < 5; ++i) {
    CHECK(engine.upmix_frame(bare.data(), bare.size(), ptrs.data(), 5, samples) == 1);
  }
  const float* held = engine.object_channel(0);
  CHECK(held != nullptr);
  bool finite = true;
  for (int i = 0; i < samples; ++i) if (!std::isfinite(held[i])) finite = false;
  CHECK(finite);

  // ...but frames with no readable syncframe header can't vouch that the
  // stream is still Atmos, so after ~1 s of them the hold expires instead of
  // spatializing stale metadata forever.
  for (int i = 0; i < 40; ++i) {
    engine.upmix_frame(bare.data(), bare.size(), ptrs.data(), 5, samples);
  }
  CHECK(engine.upmix_frame(bare.data(), bare.size(), ptrs.data(), 5, samples) == -1);
}

// An E-AC-3 5.1 syncframe header with no metadata blocks and, when `atmos`,
// the addbsi flag_ec3_extension_type_a a JOC encoder sets on every frame.
std::vector<uint8_t> eac3_header_frame(bool atmos) {
  BitWriter bw;
  bw.put(16, 0x0B77);
  bw.put(2, 0); bw.put(3, 0); bw.put(11, 100);  // independent, substream 0, frmsiz
  bw.put(2, 0); bw.put(2, 3);                   // 48 kHz, 6 blocks
  bw.put(3, 7); bw.put_bit(1);                  // 5.0 + LFE
  bw.put(5, 16);                                // E-AC-3
  bw.put(5, 31); bw.put_bit(0);                 // dialnorm, no compr
  bw.put_bit(0); bw.put_bit(0);                 // no mixing / informational metadata
  bw.put_bit(atmos ? 1 : 0);                    // addbsie
  if (atmos) { bw.put(6, 1); bw.put(8, 0x01); bw.put(8, 16); }
  while (bw.bytes.size() < 64) bw.put(8, 0);
  return bw.bytes;
}

// Dolby's 7.1.4 channel-check tones carry JOC in 2% of their frames, with gaps
// of up to 646 frames (~20 s). Every frame is still flagged Atmos in its
// header, and the hold must last through all of them: expiring it drops each
// height and rear object onto the 5.1 bed for the rest of the gap.
void test_hold_follows_header() {
  std::printf("ObjectEngine: hold lasts while the header says Atmos\n");
  BitWriter fw; write_emdf_joc_frame(fw);
  const std::vector<uint8_t> atmos = eac3_header_frame(true);
  const std::vector<uint8_t> plain = eac3_header_frame(false);
  const int samples = 1536;
  std::vector<std::vector<float>> bed(5, std::vector<float>(samples, 0.1f));
  std::vector<const float*> ptrs(5);
  for (int ch = 0; ch < 5; ++ch) ptrs[ch] = bed[ch].data();

  ObjectEngine engine;
  // Atmos-flagged frames before any JOC payload: nothing to render yet.
  CHECK(engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == -1);
  CHECK(engine.upmix_frame(fw.bytes.data(), fw.bytes.size(), ptrs.data(), 5, samples) == 1);
  int held = 0;
  for (int i = 0; i < 700; ++i) {
    held += engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == 1;
  }
  CHECK(held == 700);

  // A few frames without a raw frame (the tap had none) inside the gap still hold.
  std::vector<uint8_t> none;
  CHECK(engine.upmix_frame(none.data(), 0, ptrs.data(), 5, samples) == 1);
  CHECK(engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == 1);

  // A gapless change into plain E-AC-3 ends the hold on its first frame, and
  // Atmos-flagged frames don't revive the stale JOC; only a new payload does.
  CHECK(engine.upmix_frame(plain.data(), plain.size(), ptrs.data(), 5, samples) == -1);
  CHECK(engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == -1);
  CHECK(engine.upmix_frame(fw.bytes.data(), fw.bytes.size(), ptrs.data(), 5, samples) == 1);
  CHECK(engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == 1);

  // After a seek the held JOC describes another point of the stream: drop it
  // and render again from the next payload.
  engine.flush_history();
  CHECK(engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == -1);
  CHECK(engine.upmix_frame(fw.bytes.data(), fw.bytes.size(), ptrs.data(), 5, samples) == 1);
  CHECK(engine.upmix_frame(atmos.data(), atmos.size(), ptrs.data(), 5, samples) == 1);

  // upmix_held still renders the fade-out frame of a render -> bed seam.
  CHECK(engine.upmix_frame(plain.data(), plain.size(), ptrs.data(), 5, samples) == -1);
  CHECK(engine.upmix_held(ptrs.data(), 5, samples) == 1);
}

}  // namespace

int main() {
  std::printf("=== Atmos ObjectEngine tests ===\n");
  test_upmix_smoke();
  test_no_joc_frame();
  test_metadata_hold();
  test_hold_follows_header();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

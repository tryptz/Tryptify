// Host test for the headphone render through the 15.1.12 virtual room
// (AtmosPipeline::set_virtual_room, render/virtual_room.h).
//
//   c++ -std=c++17 -O2 -I.. atmos_room_pipeline_test.cpp -o room -lpthread && ./room
//
// A bed with no objects on the room's speakers, a JOC frame's objects and the
// meter that reports them, the bed <-> objects seam, a flush, switching the
// room off again, and the modes that still hand the frame back.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "atmos_pipeline.h"

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
  void bit(int b) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (b & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put(unsigned n, uint32_t v) { for (int i = (int)n - 1; i >= 0; --i) bit((v >> i) & 1u); }
};

using namespace tf::atmos;

constexpr float kHalfPiC = 1.57079632679f;

// JOC body: 5 channels, 1 object, coarse zero matrix, gain 1 (as cavern_joc_test).
void write_joc(BitWriter& bw) {
  bw.put(3, 0); bw.put(6, 0); bw.put(3, 0);
  bw.put(3, 4); bw.put(5, 0); bw.put(10, 0);
  bw.bit(1); bw.put(3, 0); bw.bit(0); bw.put(1, 0); bw.bit(0); bw.put(1, 0);
  for (int i = 0; i < 5; ++i) bw.bit(0);
}

// Wrap a JOC body (payload id 14) in an EMDF block behind a garbage prefix.
void write_emdf_joc_frame(BitWriter& bw) {
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


std::vector<uint8_t> plain_frame() { return std::vector<uint8_t>(64, 0xAB); }

struct Bed {
  std::vector<std::vector<float>> ch;
  std::vector<const float*> ptrs;
  Bed(int channels, int samples) : ch(channels, std::vector<float>(samples, 0.0f)), ptrs(channels) {
    for (int c = 0; c < channels; ++c) ptrs[c] = ch[c].data();
  }
};

void test_bed_through_the_room() {
  std::printf("Room: a 5.1 bed with no objects plays on the room's speakers\n");
  const int samples = 1536;
  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_virtual_room(true, 0.0f);
  const std::vector<uint8_t> frame = plain_frame();
  std::vector<float> out(2 * samples, 0.0f);
  double l = 0.0, r = 0.0;
  int first = -1;
  for (int f = 0; f < 6; ++f) {
    Bed bed(6, samples);
    // The left front speaker alone, starting 100 samples into the first frame.
    for (int i = 0; i < samples; ++i) {
      const int t = f * samples + i;
      if (t >= 100) bed.ch[0][i] = 0.2f * std::sin(t * 0.21f);
    }
    CHECK(pipe.process_frame(frame.data(), frame.size(), bed.ptrs.data(), 6, samples, out.data()) == 1);
    CHECK(pipe.last_objects() == 0);
    for (int i = 0; i < samples; ++i) {
      CHECK(std::isfinite(out[2 * i]) && std::isfinite(out[2 * i + 1]));
      // Over the rounding a block's transform leaves ahead of the sound.
      if (first < 0 && (std::fabs(out[2 * i]) > 1e-6f || std::fabs(out[2 * i + 1]) > 1e-6f)) first = f * samples + i;
      l += out[2 * i] * out[2 * i];
      r += out[2 * i + 1] * out[2 * i + 1];
    }
  }
  // Late by the bed's QMF-matching delay and the room's own, and no earlier
  // (the first of it is the delay filter's leading edge, ahead of the onset).
  CHECK(first >= 100 + 577 + render::VirtualRoom::kLookahead);
  CHECK(first < 100 + 577 + render::VirtualRoom::kLatency + 48);
  CHECK(l > 1.5 * r);  // the left front speaker is to the left
  CHECK(l > 0.5);
}

void test_joc_without_positions() {
  std::printf("Room: JOC with no OAMD has nowhere to put objects: the bed plays\n");
  const int samples = 1536;
  BitWriter fw; write_emdf_joc_frame(fw);
  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_virtual_room(true, 0.25f);
  Bed bed(5, samples);
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i) bed.ch[ch][i] = 0.2f * std::sin((i + ch) * 0.03f);
  std::vector<float> out(2 * samples, 0.0f);
  double energy = 0.0;
  bool finite = true;
  for (int f = 0; f < 3; ++f) {
    CHECK(pipe.process_frame(fw.bytes.data(), fw.bytes.size(), bed.ptrs.data(), 5, samples, out.data()) == 1);
    for (float v : out) { if (!std::isfinite(v)) finite = false; energy += v * v; }
  }
  CHECK(finite);
  CHECK(energy > 0.0);
  CHECK(pipe.last_objects() == 0);
  std::vector<float> meter(16 * AtmosPipeline::kMeterFields, -9.0f);
  CHECK(pipe.object_meter(meter.data(), 16) == 0);
}

void test_seam_has_no_step() {
  std::printf("Room: bed -> objects -> bed without a step\n");
  const int samples = 1536;
  BitWriter fw; write_emdf_joc_frame(fw);
  const std::vector<uint8_t> plain = plain_frame();
  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_virtual_room(true, 0.0f);
  std::vector<float> out(2 * samples, 0.0f);
  float prev_l = 0.0f, worst = 0.0f, inside = 0.0f;
  for (int f = 0; f < 80; ++f) {
    Bed bed(5, samples);
    for (int ch = 0; ch < 5; ++ch)
      for (int i = 0; i < samples; ++i) bed.ch[ch][i] = 0.1f * std::sin((f * samples + i) * 0.02f + ch);
    const bool joc = f == 20;  // objects from frame 20, held ~1 s, then the bed again
    const std::vector<uint8_t>& frame = joc ? fw.bytes : plain;
    CHECK(pipe.process_frame(frame.data(), frame.size(), bed.ptrs.data(), 5, samples, out.data()) == 1);
    for (int i = 0; i < samples; ++i) {
      const float step = std::fabs(out[2 * i] - prev_l);
      if (f >= 2) { if (i == 0) worst = std::fmax(worst, step); else inside = std::fmax(inside, step); }
      prev_l = out[2 * i];
    }
  }
  // A frame's first sample follows the last one's as any two samples do.
  std::printf("   largest step inside a frame %.5f, across a frame boundary %.5f\n", inside, worst);
  CHECK(worst <= inside * 1.5f + 1e-4f);
}

void test_flush_and_switching_back() {
  std::printf("Room: a flush empties it; switched off, the first render is back\n");
  const int samples = 1536;
  const std::vector<uint8_t> plain = plain_frame();
  Bed bed(6, samples);
  for (int ch = 0; ch < 6; ++ch)
    for (int i = 0; i < samples; ++i) bed.ch[ch][i] = 0.2f * std::sin((i + ch) * 0.05f);
  Bed none(6, samples);
  std::vector<float> out(2 * samples, 0.0f), ref(2 * samples, 0.0f);

  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_virtual_room(true, 1.0f);
  for (int f = 0; f < 4; ++f) pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data());
  pipe.flush_state();
  pipe.process_frame(plain.data(), plain.size(), none.ptrs.data(), 6, samples, out.data());
  bool silent = true;
  for (float v : out) if (v != 0.0f) silent = false;
  CHECK(silent);

  pipe.set_virtual_room(false, 1.0f);
  pipe.flush_state();
  AtmosPipeline first; first.configure(48000, 16);
  bool same = true;
  for (int f = 0; f < 3; ++f) {
    pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data());
    first.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, ref.data());
    for (int i = 0; i < 2 * samples; ++i) if (out[i] != ref[i]) same = false;
  }
  CHECK(same);
}

void test_modes_still_decline() {
  std::printf("Room: passthrough and the plain folds are still the caller's\n");
  const int samples = 1536;
  const std::vector<uint8_t> plain = plain_frame();
  Bed bed(6, samples);
  std::vector<float> out(2 * samples, 0.0f);
  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_virtual_room(true, 0.25f);
  pipe.set_params(/*mode=*/0, /*downmix=*/0, 1.0f, true, 0.0f, false, 80, 0, false);
  CHECK(pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data()) == -1);
  pipe.set_params(/*mode=*/1, /*downmix=*/1, 1.0f, true, 0.0f, false, 80, 0, false);
  CHECK(pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data()) == -1);
  // The bed mode plays the bed on the room at once, with no QMF delay to match.
  pipe.set_params(/*mode=*/2, /*downmix=*/0, 1.0f, true, 0.0f, false, 80, 0, false);
  bed.ch[2][0] = 0.5f;  // the centre, one impulse
  CHECK(pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data()) == 1);
  int first = -1;
  for (int i = 0; i < samples && first < 0; ++i) if (std::fabs(out[2 * i]) > 1e-6f) first = i;
  CHECK(first >= render::VirtualRoom::kLookahead && first < render::VirtualRoom::kLatency + 48);
  CHECK(pipe.last_objects() == 0);
}

void test_room_meter() {
  std::printf("Room: the meter says which of the room's speakers play\n");
  const int samples = 1536;
  float layout[AtmosPipeline::kRoomMeterMax * AtmosPipeline::kRoomFields];
  const int count = AtmosPipeline::room_layout(layout, AtmosPipeline::kRoomMeterMax);
  CHECK(count == 28);
  int lfes = 0, left_front = -1, heights = 0;
  for (int s = 0; s < count; ++s) {
    const float* f = layout + s * AtmosPipeline::kRoomFields;
    CHECK(f[0] >= 0.0f && f[0] <= 1.0f && f[1] >= 0.0f && f[1] <= 1.0f && f[2] >= 0.0f && f[2] <= 1.0f);
    if (f[3] == 1.0f) { ++lfes; continue; }
    if (f[2] > 0.0f) ++heights;
    if (f[0] == 0.0f && f[1] == 0.0f && f[2] == 0.0f) left_front = s;
  }
  CHECK(lfes == 1);
  CHECK(heights == 12);
  CHECK(left_front >= 0);

  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_virtual_room(true, 0.0f);
  float meter[AtmosPipeline::kRoomMeterMax];
  // Nothing rendered yet, nothing watched: nothing to show.
  CHECK(pipe.room_meter(meter, AtmosPipeline::kRoomMeterMax, false) == 0);
  CHECK(pipe.room_meter(meter, AtmosPipeline::kRoomMeterMax, true) == 0);
  // The bed mode, the left front channel alone: that speaker and no other.
  pipe.set_params(/*mode=*/2, /*downmix=*/0, 1.0f, true, 0.0f, false, 80, 0, false);
  const std::vector<uint8_t> plain = plain_frame();
  Bed bed(6, samples);
  for (int i = 0; i < samples; ++i) bed.ch[0][i] = 0.2f * std::sin(i * 0.21f);
  std::vector<float> out(2 * samples, 0.0f);
  CHECK(pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data()) == 1);
  CHECK(pipe.room_meter(meter, AtmosPipeline::kRoomMeterMax, false) == count);
  for (int s = 0; s < count; ++s) {
    if (s == left_front) CHECK(meter[s] > 0.19f && meter[s] < 0.21f);
    else CHECK(meter[s] == 0.0f);
  }
  // Too small a buffer is refused, and a frame the room did not render
  // leaves nothing to show.
  CHECK(pipe.room_meter(meter, count - 1, false) == 0);
  pipe.set_params(/*mode=*/0, /*downmix=*/0, 1.0f, true, 0.0f, false, 80, 0, false);
  CHECK(pipe.process_frame(plain.data(), plain.size(), bed.ptrs.data(), 6, samples, out.data()) == -1);
  CHECK(pipe.room_meter(meter, AtmosPipeline::kRoomMeterMax, false) == 0);
}

}  // namespace

int main() {
  std::printf("=== Atmos pipeline: the virtual room ===\n");
  test_bed_through_the_room();
  test_joc_without_positions();
  test_seam_has_no_step();
  test_flush_and_switching_back();
  test_modes_still_decline();
  test_room_meter();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

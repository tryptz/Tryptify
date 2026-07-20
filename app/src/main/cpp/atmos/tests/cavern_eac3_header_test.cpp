// Host test for the ported Cavern E-AC-3 syncframe header
// (EnhancedAC3Header fixed-field parse).
//
//   c++ -std=c++17 -I.. cavern_eac3_header_test.cpp -o eac3h && ./eac3h
//
// Crafts an E-AC-3 (bsid 16) syncframe header and checks the decoded format
// fields, plus a not-a-syncword rejection.
#include <cstdint>
#include <cstdio>
#include <vector>

#include "cavern/enhanced_ac3.h"

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
  void put(unsigned n, uint32_t v) {
    for (int i = (int)n - 1; i >= 0; --i) bit((v >> i) & 1u);
  }
};

using namespace tf::atmos;
using namespace tf::atmos::cavern;

void test_eac3_independent_header() {
  std::printf("EnhancedAC3Header: E-AC-3 independent 5.1 syncframe\n");
  BitWriter bw;
  bw.put(16, 0x0B77);  // syncword
  bw.put(2, 0);        // strmtyp = Independent
  bw.put(3, 0);        // substreamid = 0
  bw.put(11, 100);     // frmsiz -> WordsPerSyncframe = 101
  bw.put(2, 0);        // fscod = 0 -> 48 kHz
  bw.put(2, 3);        // numblkscod = 3 -> 6 blocks
  bw.put(3, 7);        // acmod = 7 -> L C R SL SR
  bw.bit(1);           // lfeon = 1
  bw.put(5, 16);       // bsid = 16 -> E-AC-3
  while (bw.bytes.size() < 8) bw.put(8, 0);  // pad

  BitReader br(bw.bytes.data(), bw.bytes.size());
  EnhancedAC3Header h;
  bool ok = h.decode(br);

  CHECK(ok);
  CHECK(h.valid());
  CHECK(h.decoder() == eac3::Decoders::kEAC3);
  CHECK(h.stream_type() == eac3::StreamTypes::kIndependent);
  CHECK(h.substream_id() == 0);
  CHECK(h.words_per_syncframe() == 101);
  CHECK(h.sample_rate() == 48000);
  CHECK(h.blocks() == 6);
  CHECK(h.channel_mode() == 7);
  CHECK(h.lfe());
  CHECK(h.channel_count() == 6);  // 5 + LFE

  std::vector<ReferenceChannel> chans = h.channel_arrangement();
  CHECK(chans.size() == 5);
  CHECK(!chans.empty() && chans[0] == ReferenceChannel::kFrontLeft);
  CHECK(chans.size() == 5 && chans[1] == ReferenceChannel::kFrontCenter);
  CHECK(chans.size() == 5 && chans[4] == ReferenceChannel::kSideRight);
}

void test_dependent_substream_offset() {
  std::printf("EnhancedAC3Header: dependent substream id offset (+8)\n");
  BitWriter bw;
  bw.put(16, 0x0B77);
  bw.put(2, 1);    // strmtyp = Dependent
  bw.put(3, 2);    // substreamid = 2 -> +8 = 10
  bw.put(11, 50);
  bw.put(2, 0);
  bw.put(2, 3);
  bw.put(3, 2);    // acmod = 2 (stereo)
  bw.bit(0);
  bw.put(5, 16);   // E-AC-3
  while (bw.bytes.size() < 8) bw.put(8, 0);

  BitReader br(bw.bytes.data(), bw.bytes.size());
  EnhancedAC3Header h;
  CHECK(h.decode(br));
  CHECK(h.stream_type() == eac3::StreamTypes::kDependent);
  CHECK(h.substream_id() == 10);  // 2 + 8
}

void test_not_a_syncword() {
  std::printf("EnhancedAC3Header: bad syncword -> invalid\n");
  BitWriter bw;
  bw.put(16, 0x1234);
  while (bw.bytes.size() < 8) bw.put(8, 0);
  BitReader br(bw.bytes.data(), bw.bytes.size());
  EnhancedAC3Header h;
  CHECK(!h.decode(br));
  CHECK(!h.valid());
}

}  // namespace

int main() {
  std::printf("=== Cavern E-AC-3 header tests ===\n");
  test_eac3_independent_header();
  test_dependent_substream_offset();
  test_not_a_syncword();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

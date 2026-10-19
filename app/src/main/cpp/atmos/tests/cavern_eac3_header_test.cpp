// Host test for the ported Cavern E-AC-3 syncframe header
// (EnhancedAC3Header fixed-field parse).
//
//   c++ -std=c++17 -I.. cavern_eac3_header_test.cpp -o eac3h && ./eac3h
//
// Crafts an E-AC-3 (bsid 16) syncframe header and checks the decoded format
// fields, the addbsi Atmos flag behind every optional BSI block, plus a
// not-a-syncword rejection.
#include <cmath>
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

// One E-AC-3 header up to bsid, then dialnorm/compre/compr as given.
void write_bsi_header(BitWriter& bw, int dialnorm, bool compre, uint8_t compr) {
  bw.put(16, 0x0B77);
  bw.put(2, 0);      // independent
  bw.put(3, 0);
  bw.put(11, 100);
  bw.put(2, 0);      // 48 kHz
  bw.put(2, 3);      // 6 blocks
  bw.put(3, 7);      // 5.0
  bw.bit(1);         // +LFE
  bw.put(5, 16);     // E-AC-3
  bw.put(5, static_cast<uint32_t>(dialnorm));
  bw.bit(compre ? 1 : 0);
  if (compre) bw.put(8, compr);
  while (bw.bytes.size() < 12) bw.put(8, 0);
}

void test_compr_gain() {
  std::printf("EnhancedAC3Header: BSI compr (RF-mode compression gain word)\n");
  // 0x30: X=3, Y=0 -> (16/32)*2^4 = 8.0 (+18 dB boost floor for quiet scenes).
  {
    BitWriter bw; write_bsi_header(bw, 27, true, 0x30);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    EnhancedAC3Header h;
    CHECK(h.decode(br));
    CHECK(h.dialnorm() == 27);
    CHECK(h.has_compr());
    CHECK(std::fabs(h.compr_gain() - 8.0f) < 1e-6f);
  }
  // 0xF8: X=-1 (signed 4-bit), Y=8 -> (24/32)*2^0 = 0.75 (-2.5 dB cut).
  {
    BitWriter bw; write_bsi_header(bw, 31, true, 0xF8);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    EnhancedAC3Header h;
    CHECK(h.decode(br));
    CHECK(h.has_compr());
    CHECK(std::fabs(h.compr_gain() - 0.75f) < 1e-6f);
  }
  // compre = 0 -> no word, unity gain.
  {
    BitWriter bw; write_bsi_header(bw, 31, false, 0);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    EnhancedAC3Header h;
    CHECK(h.decode(br));
    CHECK(!h.has_compr());
    CHECK(std::fabs(h.compr_gain() - 1.0f) < 1e-6f);
  }
}

// An E-AC-3 header whose BSI carries every optional block before addbsi
// (mixing + informational metadata), so a mis-skipped field moves the Atmos
// flag. `addbsi` < 0 leaves addbsie off; otherwise it is addbsi's first byte.
struct BsiShape {
  int strmtyp = 0;
  int acmod = 7;
  bool lfe = true;
  int numblkscod = 3;
  bool mixmd = true;
  bool infomd = true;
  int mixdef = 3;
  int addbsi = 0x01;
  int complexity = 12;
};

std::vector<uint8_t> write_full_bsi(const BsiShape& s, size_t pad_to = 64) {
  BitWriter bw;
  const int blocks = s.numblkscod == 3 ? 6 : s.numblkscod + 1;
  const int programs = s.acmod == 0 ? 2 : 1;
  bw.put(16, 0x0B77);
  bw.put(2, static_cast<uint32_t>(s.strmtyp));
  bw.put(3, 0);
  bw.put(11, 100);
  bw.put(2, 0);                                  // 48 kHz
  bw.put(2, static_cast<uint32_t>(s.numblkscod));
  bw.put(3, static_cast<uint32_t>(s.acmod));
  bw.bit(s.lfe ? 1 : 0);
  bw.put(5, 16);                                 // E-AC-3
  bw.put(5, 31);                                 // dialnorm
  bw.bit(1); bw.put(8, 0x55);                    // compre / compr
  if (s.acmod == 0) { bw.put(5, 30); bw.bit(1); bw.put(8, 0xAA); }  // dialnorm2 / compr2
  if (s.strmtyp == 1) { bw.bit(1); bw.put(16, 0xFFFF); }            // chanmape / chanmap
  bw.bit(s.mixmd ? 1 : 0);
  if (s.mixmd) {
    if (s.acmod > 2) bw.put(2, 3);                                  // dmixmod
    if ((s.acmod & 1) && s.acmod > 2) bw.put(6, 0x3F);              // centre mix levels
    if (s.acmod & 4) bw.put(6, 0x3F);                               // surround mix levels
    if (s.lfe) { bw.bit(1); bw.put(5, 0x1F); }                      // lfemixlevcod
    if (s.strmtyp == 0) {
      for (int p = 0; p < programs; ++p) { bw.bit(1); bw.put(6, 0x3F); }  // pgmscl
      bw.bit(1); bw.put(6, 0x3F);                                   // extpgmscl
      bw.put(2, static_cast<uint32_t>(s.mixdef));
      if (s.mixdef == 1) bw.put(5, 0x1F);
      if (s.mixdef == 2) bw.put(12, 0xFFF);
      if (s.mixdef == 3) { bw.put(5, 1); bw.put(24, 0xFFFFFF); }    // mixdeflen 1 -> 3 bytes
      if (s.acmod < 2) {
        for (int p = 0; p < programs; ++p) { bw.bit(1); bw.put(14, 0x3FFF); }  // paninfo
      }
      bw.bit(1);                                                    // frmmixcfginfoe
      for (int b = 0; b < blocks; ++b) {
        if (blocks != 1) bw.bit(1);
        bw.put(5, 0x1F);                                            // blkmixcfginfo
      }
    }
  }
  bw.bit(s.infomd ? 1 : 0);
  if (s.infomd) {
    bw.put(5, 0x1F);                                                // bsmod, copyrightb, origbs
    if (s.acmod == 2) bw.put(4, 0xF);
    if (s.acmod >= 6) bw.put(2, 3);
    for (int p = 0; p < programs; ++p) { bw.bit(1); bw.put(8, 0xFF); }  // audprodi
    bw.bit(1);                                                      // sourcefscod
  }
  if (s.strmtyp == 0 && blocks != 6) bw.bit(1);                     // convsync
  if (s.strmtyp == 2) { if (blocks != 6) bw.bit(1); bw.put(6, 0x3F); }  // blkid / frmsizecod
  bw.bit(s.addbsi >= 0 ? 1 : 0);                                    // addbsie
  if (s.addbsi >= 0) {
    bw.put(6, 1);                                                   // addbsil: 2 bytes
    bw.put(8, static_cast<uint32_t>(s.addbsi));
    bw.put(8, static_cast<uint32_t>(s.complexity));
  }
  while (bw.bytes.size() < pad_to) bw.put(8, 0);
  return bw.bytes;
}

bool decode_shape(const BsiShape& s, EnhancedAC3Header& h, size_t pad_to = 64) {
  const std::vector<uint8_t> bytes = write_full_bsi(s, pad_to);
  BitReader br(bytes.data(), bytes.size());
  return h.decode(br);
}

void test_addbsi_atmos_flag() {
  std::printf("EnhancedAC3Header: addbsi Atmos flag behind the full BSI\n");
  {  // 5.1, every optional block, variable-length mixdef
    BsiShape s;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h));
    CHECK(h.atmos());
    CHECK(h.atmos_complexity() == 12);
    CHECK(h.has_compr());
  }
  {  // each fixed-length mixdef
    for (int mixdef = 0; mixdef < 3; ++mixdef) {
      BsiShape s; s.mixdef = mixdef; s.complexity = 16;
      EnhancedAC3Header h;
      CHECK(decode_shape(s, h) && h.atmos() && h.atmos_complexity() == 16);
    }
  }
  {  // dual mono: two programs' dialnorm, compr, pgmscl, paninfo and audprodi
    BsiShape s; s.acmod = 0; s.lfe = false;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h) && h.atmos() && h.atmos_complexity() == 12);
  }
  {  // stereo (dsurmod/dheadphonmod), one-block frame (convsync, single blkmixcfginfo)
    BsiShape s; s.acmod = 2; s.lfe = false; s.numblkscod = 0;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h) && h.atmos() && h.atmos_complexity() == 12);
  }
  {  // dependent substream: chanmap, no program mixing fields
    BsiShape s; s.strmtyp = 1;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h) && h.atmos() && h.atmos_complexity() == 12);
  }
  {  // converted from AC-3, fewer than six blocks: blkid + frmsizecod
    BsiShape s; s.strmtyp = 2; s.numblkscod = 1;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h) && h.atmos() && h.atmos_complexity() == 12);
  }
  {  // no metadata blocks at all
    BsiShape s; s.mixmd = false; s.infomd = false;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h) && h.atmos() && h.atmos_complexity() == 12);
  }
  {  // plain E-AC-3: addbsi with the flag clear, and no addbsi at all
    BsiShape s; s.addbsi = 0xFE;
    EnhancedAC3Header h;
    CHECK(decode_shape(s, h) && !h.atmos() && h.atmos_complexity() == 0);
    BsiShape t; t.addbsi = -1;
    CHECK(decode_shape(t, h) && !h.atmos());
  }
  {  // a frame cut off inside addbsi must not report Atmos
    BsiShape s;
    EnhancedAC3Header h;
    const size_t full = write_full_bsi(s, 0).size();
    CHECK(decode_shape(s, h, full) && h.atmos());
    std::vector<uint8_t> cut = write_full_bsi(s, 0);
    cut.resize(full - 2);
    BitReader br(cut.data(), cut.size());
    CHECK(!h.decode(br) || !h.atmos());
  }
  {  // the flag is per frame: a non-Atmos frame after an Atmos one clears it
    EnhancedAC3Header h;
    BsiShape a;
    CHECK(decode_shape(a, h) && h.atmos());
    BsiShape b; b.addbsi = -1;
    CHECK(decode_shape(b, h) && !h.atmos() && h.atmos_complexity() == 0);
  }
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
  test_compr_gain();
  test_addbsi_atmos_flag();
  test_not_a_syncword();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}

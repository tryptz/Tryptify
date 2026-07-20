// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Transcoders/EnhancedAC3Enums.cs, EnhancedAC3Consts.cs
// and the fixed-field parse of EnhancedAC3Header.cs (Decode). This is the E-AC-3
// syncframe header entry point: it reads the frame's format fields (decoder
// version, sample rate, channel mode, block count, LFE, frame size) that the
// core audio decode and the Atmos side-data extraction build on.
//
// Scope: the fixed syncframe header (syncword through bsid, plus the AC-3
// "repackaged" frame-size recalculation) is reproduced exactly. Cavern's
// streaming BlockBuffer/Expand plumbing is replaced by a flat BitReader over a
// caller-supplied frame. The BSI / mixing / addbsi metadata parse (which is
// where the EMDF Atmos payload lives) is the next slice — see atmos/README.md.
#ifndef TF_ATMOS_CAVERN_ENHANCED_AC3_H
#define TF_ATMOS_CAVERN_ENHANCED_AC3_H

#include <cstdint>
#include <vector>

#include "../bit_reader.h"
#include "reference_channel.h"

namespace tf {
namespace atmos {
namespace cavern {

namespace eac3 {

// Supported decoder versions (bsid). kUnsupported replaces Cavern's thrown
// UnsupportedFeatureException, consistent with the valid()-flag convention.
enum class Decoders { kAlternateAC3 = 6, kAC3 = 8, kEAC3 = 16, kUnsupported = -1 };

// Program type of a frame in the stream (strmtyp).
enum class StreamTypes { kIndependent = 0, kDependent = 1, kRepackaged = 2, kReserved = 3 };

constexpr uint32_t kSyncWord = 0x0B77;

// Frame size code -> frame size in 16-bit words at 48 kHz.
inline constexpr uint16_t kFrameSizes[19] = {
    64, 80, 96, 112, 128, 160, 192, 224, 256, 320,
    384, 448, 512, 640, 768, 896, 1024, 1152, 1280};
inline constexpr uint8_t kNumberOfBlocks[4] = {1, 2, 3, 6};
inline constexpr uint16_t kSampleRates[3] = {48000, 44100, 32000};

// Channel arrangement per acmod (index = channel mode). LFE is signalled apart.
inline const ReferenceChannel kChannelArrangements[8][5] = {
    {ReferenceChannel::kFrontCenter, ReferenceChannel::kFrontCenter},          // 0 dual mono
    {ReferenceChannel::kFrontCenter},                                          // 1 mono
    {ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontRight},             // 2 stereo
    {ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontCenter,
     ReferenceChannel::kFrontRight},                                          // 3 L C R
    {ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontRight,
     ReferenceChannel::kRearCenter},                                         // 4 L R S
    {ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontCenter,
     ReferenceChannel::kFrontRight, ReferenceChannel::kRearCenter},          // 5 L C R S
    {ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontRight,
     ReferenceChannel::kSideLeft, ReferenceChannel::kSideRight},             // 6 L R SL SR
    {ReferenceChannel::kFrontLeft, ReferenceChannel::kFrontCenter,
     ReferenceChannel::kFrontRight, ReferenceChannel::kSideLeft,
     ReferenceChannel::kSideRight},                                          // 7 L C R SL SR
};
inline constexpr uint8_t kChannelArrangementLen[8] = {2, 1, 2, 3, 3, 4, 4, 5};

}  // namespace eac3

// Reads the fixed fields of an E-AC-3 syncframe header.
class EnhancedAC3Header {
 public:
  int channel_mode() const { return channel_mode_; }
  int blocks() const { return blocks_; }
  bool lfe() const { return lfe_; }
  int sample_rate() const { return sample_rate_; }
  int sample_rate_code() const { return sample_rate_code_; }
  int substream_id() const { return substream_id_; }
  int words_per_syncframe() const { return words_per_syncframe_; }
  eac3::Decoders decoder() const { return decoder_; }
  eac3::StreamTypes stream_type() const { return stream_type_; }
  bool valid() const { return valid_; }

  // Number of full-bandwidth + LFE channels in this frame.
  int channel_count() const {
    int base = (channel_mode_ < 8)
                   ? eac3::kChannelArrangementLen[channel_mode_]
                   : 0;
    return base + (lfe_ ? 1 : 0);
  }

  // Channels in stream order (standard arrangement; a custom chanmap needs the
  // BSI parse, not yet ported, so this is the acmod-default layout).
  std::vector<ReferenceChannel> channel_arrangement() const {
    if (channel_mode_ >= 8) return {};
    const ReferenceChannel* a = eac3::kChannelArrangements[channel_mode_];
    return std::vector<ReferenceChannel>(
        a, a + eac3::kChannelArrangementLen[channel_mode_]);
  }

  // Parses the fixed syncframe header. `br` starts at the syncword. Returns
  // false on a missing syncword or a reserved/unsupported value.
  bool decode(BitReader& br) {
    valid_ = true;
    if (br.read(16) != eac3::kSyncWord) return fail();

    stream_type_ = static_cast<eac3::StreamTypes>(br.read(2));
    substream_id_ = static_cast<int>(br.read(3));
    words_per_syncframe_ = static_cast<int>(br.read(11)) + 1;
    sample_rate_code_ = static_cast<int>(br.read(2));
    blocks_ = eac3::kNumberOfBlocks[br.read(2)];
    channel_mode_ = static_cast<int>(br.read(3));
    lfe_ = br.read_bit() != 0;
    decoder_ = parse_decoder(static_cast<int>(br.read(5)));
    if (decoder_ == eac3::Decoders::kUnsupported) return fail();

    if (decoder_ != eac3::Decoders::kEAC3) {  // AC-3 repackaged layout
      stream_type_ = eac3::StreamTypes::kRepackaged;
      substream_id_ = 0;
      blocks_ = 6;
      sample_rate_code_ = br.byte_at(4) >> 6;
      frmsizecod_ = br.byte_at(4) & 63;
      words_per_syncframe_ = eac3::kFrameSizes[frmsizecod_ >> 1];
      if (sample_rate_code_ == 1) {  // 44.1 kHz
        words_per_syncframe_ = words_per_syncframe_ * 1393 / 1280;
        if ((frmsizecod_ & 1) == 1) ++words_per_syncframe_;
      } else if (sample_rate_code_ == 2) {  // 32 kHz
        words_per_syncframe_ += words_per_syncframe_ >> 1;
      }
      bsmod_ = static_cast<int>(br.read(3));
      channel_mode_ = static_cast<int>(br.read(3));
    }

    if (stream_type_ == eac3::StreamTypes::kDependent) substream_id_ += 8;
    if (stream_type_ == eac3::StreamTypes::kReserved) return fail();  // strmtyp
    if (sample_rate_code_ == 3) return fail();                        // fscod
    sample_rate_ = eac3::kSampleRates[sample_rate_code_];
    return true;
  }

 private:
  bool fail() {
    valid_ = false;
    return false;
  }

  static eac3::Decoders parse_decoder(int bsid) {
    if (bsid == static_cast<int>(eac3::Decoders::kAlternateAC3)) {
      return eac3::Decoders::kAlternateAC3;
    }
    if (bsid <= static_cast<int>(eac3::Decoders::kAC3)) return eac3::Decoders::kAC3;
    if (bsid == static_cast<int>(eac3::Decoders::kEAC3)) return eac3::Decoders::kEAC3;
    return eac3::Decoders::kUnsupported;
  }

  bool valid_ = true;
  int channel_mode_ = 0;
  int blocks_ = 0;
  bool lfe_ = false;
  int sample_rate_ = 0;
  int sample_rate_code_ = 0;
  int substream_id_ = 0;
  int words_per_syncframe_ = 0;
  int frmsizecod_ = 0;
  int bsmod_ = 0;
  eac3::Decoders decoder_ = eac3::Decoders::kUnsupported;
  eac3::StreamTypes stream_type_ = eac3::StreamTypes::kIndependent;
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_ENHANCED_AC3_H

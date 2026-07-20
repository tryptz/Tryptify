// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence — see cpp/atmos/cavern/NOTICE.md.
//
// A subset of Cavern.Channels.ReferenceChannel: the standard speaker positions
// named by the OAMD bed-channel table and the E-AC-3 channel arrangements. In
// the ported decode path these are used only as identifiers (never as indices
// into Cavern's renderer speaker arrays), so the enumerator values are a clean
// local sequence rather than Cavern's rendering indices.
#ifndef TF_ATMOS_CAVERN_REFERENCE_CHANNEL_H
#define TF_ATMOS_CAVERN_REFERENCE_CHANNEL_H

namespace tf {
namespace atmos {
namespace cavern {

enum class ReferenceChannel {
  kFrontLeft, kFrontRight, kFrontCenter, kScreenLFE, kSideLeft, kSideRight,
  kRearLeft, kRearRight, kTopFrontLeft, kTopFrontRight, kTopSideLeft,
  kTopSideRight, kTopRearLeft, kTopRearRight, kWideLeft, kWideRight,
  kRearCenter, kTopFrontCenter, kGodsVoice, kFrontLeftCenter, kFrontRightCenter
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_REFERENCE_CHANNEL_H

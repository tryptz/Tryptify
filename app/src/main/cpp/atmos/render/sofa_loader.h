// Runtime SOFA HRTF loader (declaration). Defined in sofa_loader.cpp, the only
// translation unit that pulls in libmysofa: the header-only render and the
// host tests never link it. Reads a listener's .sofa from memory, resamples
// it to 48 kHz and onset-aligns it onto the baked table's grid (the same
// steps as tools/bake_hrir.c).
#ifndef TF_ATMOS_RENDER_SOFA_LOADER_H
#define TF_ATMOS_RENDER_SOFA_LOADER_H

#include <vector>

namespace tf {
namespace atmos {

// `taps` and `delays` come out laid out as kHrirData and kHrirDelay
// ([elevation][azimuth][ear]), ready for VirtualRoom::set_hrir. False when
// the file cannot be read as an HRTF set. Allocates; loader thread.
bool load_sofa_tables(const char* data, long size, std::vector<float>& taps,
                      std::vector<float>& delays);

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_SOFA_LOADER_H

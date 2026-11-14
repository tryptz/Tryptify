// Runtime SOFA HRTF loader (declaration). Defined in sofa_loader.cpp, the only
// translation unit that pulls in libmysofa: the header-only render and the
// host tests never link it. Reads a listener's .sofa, resamples it to 48 kHz
// and onset-aligns it (the same steps as tools/bake_hrir.c).
#ifndef TF_ATMOS_RENDER_SOFA_LOADER_H
#define TF_ATMOS_RENDER_SOFA_LOADER_H

#include <vector>

namespace tf {
namespace atmos {

// From memory, onto the baked table's grid alone: `taps` and `delays` come
// out laid out as kHrirData and kHrirDelay ([elevation][azimuth][ear]), ready
// for VirtualRoom::set_hrir. False when the file cannot be read as an HRTF
// set. Allocates; loader thread.
bool load_sofa_tables(const char* data, long size, std::vector<float>& taps,
                      std::vector<float>& delays);

// From a file, as a packed render::SofaSet (render/sofa_set.h): the table,
// and the file's own answer for each of `count` directions (`directions` is
// {azimuth, elevation} pairs in the renderer's degrees), which a finely
// measured set has without blending. The file is read where it is, never
// into the caller's memory: a set measured every degree is 160 MB. That one
// takes seconds and some 400 MB while it is open, so this is called once for
// a file and its answer kept. False when the file cannot be read as an HRTF
// set. Allocates; loader thread.
bool load_sofa_set(const char* path, const float* directions, int count,
                   std::vector<float>& packed);

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_SOFA_LOADER_H

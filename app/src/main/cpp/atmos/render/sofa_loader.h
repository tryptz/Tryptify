// Runtime SOFA HRTF loader (declaration). Defined in sofa_loader.cpp, which is
// the ONLY translation unit that pulls in libmysofa — the header-only renderer
// and the static self-check lib never link it. Loads a user-supplied .sofa from
// an in-memory buffer, resamples + onset-aligns it to the baked table's grid
// (identical processing to tools/bake_hrir.c), and installs it into the renderer
// via BinauralRenderer::set_runtime_hrir. Returns false on any parse failure.
#ifndef TF_ATMOS_RENDER_SOFA_LOADER_H
#define TF_ATMOS_RENDER_SOFA_LOADER_H

namespace tf {
namespace atmos {
namespace render {
class BinauralRenderer;
}

bool load_sofa_into_renderer(render::BinauralRenderer& renderer, const char* data,
                             long size);

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_SOFA_LOADER_H

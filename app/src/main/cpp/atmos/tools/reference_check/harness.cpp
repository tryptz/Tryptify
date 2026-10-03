// Reference-check harness (driven by run.sh): runs the native Atmos code over a
// real E-AC-3 JOC stream — raw access units plus the FFmpeg-decoded core bed —
// and logs, per frame, the JOC object count and per-speaker RMS of the speaker
// render (stdout), and each object's resolved OAMD state and PCM RMS (stderr).
//
//   ./harness raw.ec3 packets.csv bed.f32 [layout_id] > frames.csv 2> objects.csv
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "atmos_pipeline.h"

using namespace tf::atmos;

int main(int argc, char** argv) {
  if (argc < 4) { std::fprintf(stderr, "usage: harness raw.ec3 packets.csv bed.f32 [layout]\n"); return 2; }
  const int layout_id = argc > 4 ? std::atoi(argv[4]) : static_cast<int>(OutputLayout::k7_1_4);
  std::ifstream raw(argv[1], std::ios::binary);
  std::vector<uint8_t> all((std::istreambuf_iterator<char>(raw)), {});
  std::vector<size_t> sizes;
  { std::ifstream pk(argv[2]); std::string l; while (std::getline(pk, l)) sizes.push_back(std::stoul(l.substr(l.find(',') + 1))); }
  std::ifstream bedf(argv[3], std::ios::binary);
  std::vector<float> bed((std::istreambuf_iterator<char>(bedf)), {});
  bed.resize(0);
  { std::ifstream b2(argv[3], std::ios::binary | std::ios::ate); const size_t n = b2.tellg(); b2.seekg(0);
    bed.resize(n / 4); b2.read(reinterpret_cast<char*>(bed.data()), n); }

  const int kCh = 6, kN = 1536;
  AtmosPipeline pipe;
  pipe.configure(48000, 16);
  pipe.set_params(/*mode=*/1, /*downmix=*/0, 1.0f, true, 0.0f, false, 80, 0, false);
  const int out_ch = pipe.set_output_layout(layout_id);
  ObjectEngine eng;  // a second engine, only to read the decoded states

  std::vector<std::vector<float>> planar(kCh, std::vector<float>(kN));
  std::vector<const float*> ptrs(kCh);
  std::vector<float> spk(static_cast<size_t>(out_ch) * kN);

  std::printf("frame,t,joc_objects");
  for (int c = 0; c < out_ch; ++c) std::printf(",ch%d", c);
  std::printf("\n");
  std::fprintf(stderr, "frame,t,obj,active,bed,x,y,z,gain,w,zone,snap,div,rms\n");

  size_t off = 0;
  for (size_t f = 0; f < sizes.size(); ++f) {
    const uint8_t* frame = all.data() + off;
    const size_t fs = sizes[f];
    off += fs;
    for (int c = 0; c < kCh; ++c) {
      for (int i = 0; i < kN; ++i) planar[c][i] = bed[(f * kN + i) * kCh + c];
      ptrs[c] = planar[c].data();
    }
    const int n = eng.upmix_frame(frame, fs, ptrs.data(), kCh, kN);
    pipe.process_frame_speakers(frame, fs, ptrs.data(), kCh, kN, spk.data());
    const double t = f * kN / 48000.0;
    std::printf("%zu,%.3f,%d", f, t, n);
    for (int c = 0; c < out_ch; ++c) {
      double e = 0;
      for (int i = 0; i < kN; ++i) { const float v = spk[static_cast<size_t>(i) * out_ch + c]; e += v * v; }
      std::printf(",%.6f", std::sqrt(e / kN));
    }
    std::printf("\n");
    for (int o = 0; o < n; ++o) {
      const cavern::ObjectState* st = eng.object_state(o);
      if (!st) continue;
      double pe = 0; const float* pp = eng.object_channel(o);
      for (int i = 0; pp && i < kN; ++i) pe += pp[i] * pp[i];
      std::fprintf(stderr, "%zu,%.3f,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.3f,%d,%d,%.3f,%.6f\n", f, t, o, st->active,
                   st->is_bed, st->room.x, st->room.y, st->room.z, st->gain, st->width,
                   st->zone_constraints_idx, st->snap, st->divergence, std::sqrt(pe / kN));
    }
  }
  return 0;
}

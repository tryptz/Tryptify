// Bakes MIT KEMAR HRIRs into a compact C++ table for the Atmos binaural
// renderer. libmysofa loads the SOFA, resamples to 48 kHz and loudness-
// normalizes; we sample a uniform azimuth/elevation grid, truncate each ear's
// FIR to kTaps (128 taps captures >=97% of the energy — measured), and emit
// hrir_table.h. The Android build embeds this header and needs no libmysofa,
// no zlib and no runtime asset — matching the previously header-only renderer.
//
// BUILD-TIME ONLY — not part of the Android build. Regenerate the table with:
//   git clone --depth 1 https://github.com/hoene/libmysofa
//   # generate libmysofa/src/config.h + hrtf/mysofa_export.h (see its CMake),
//   cc -O2 -DOUTSIDE_SPEEX -DRANDOM_PREFIX=mysofa -Ilibmysofa/src \
//      -Ilibmysofa/src/hrtf -Ilibmysofa/src/resampler bake_hrir.c \
//      libmysofa/src/hrtf/*.c libmysofa/src/hdf/*.c \
//      libmysofa/src/resampler/speex_resampler.c -lz -lm -o bake_hrir
//   ./bake_hrir libmysofa/share/MIT_KEMAR_normal_pinna.sofa ../hrir_table.h
// See ../NOTICE.md for dataset attribution.
//
// Convention baked into the table matches the renderer's:
//   azimuth 0 = front, increasing to the RIGHT (so index a -> my_az = a*step).
// libmysofa's azimuth increases to the LEFT (verified: its az=270 -> right ear
// loud), so each entry is queried at libmysofa_az = (360 - my_az) mod 360.
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include "mysofa.h"

#define TAPS 128
#define AZ_STEP 10
#define AZ_COUNT 36            // 0,10,...,350  (wraps)
#define EL_MIN (-40)
#define EL_STEP 20
#define EL_COUNT 6             // -40,-20,0,20,40,60  (clamps)

int main(int argc, char** argv) {
  const char* path = argv[1];
  int filterlen = 0, err = 0;
  struct MYSOFA_EASY* easy = mysofa_open(path, 48000.0f, &filterlen, &err);
  if (!easy) { fprintf(stderr, "open failed err=%d\n", err); return 1; }
  float* irL = (float*)malloc(sizeof(float) * filterlen);
  float* irR = (float*)malloc(sizeof(float) * filterlen);

  FILE* f = fopen(argv[2], "w");
  fprintf(f,
    "// GENERATED — do not edit. Baked from MIT KEMAR (libmysofa) at 48 kHz.\n"
    "// Source SOFA: MIT_KEMAR_normal_pinna.sofa (public-domain KEMAR set).\n"
    "// Regenerate with scratchpad/dump.c. See structural_hrtf.h's replacement.\n"
    "#ifndef TF_ATMOS_RENDER_HRIR_TABLE_H\n"
    "#define TF_ATMOS_RENDER_HRIR_TABLE_H\n\n"
    "namespace tf { namespace atmos { namespace render {\n\n"
    "constexpr int   kHrirTaps    = %d;\n"
    "constexpr int   kHrirAzCount = %d;   // uniform, wraps\n"
    "constexpr float kHrirAzStep  = %d.0f;\n"
    "constexpr int   kHrirElCount = %d;   // uniform, clamps\n"
    "constexpr float kHrirElMin   = %d.0f;\n"
    "constexpr float kHrirElStep  = %d.0f;\n\n"
    "// [el][az][ear(0=L,1=R)][tap]\n"
    "constexpr float kHrirData[%d][%d][2][%d] = {\n",
    TAPS, AZ_COUNT, AZ_STEP, EL_COUNT, EL_MIN, EL_STEP,
    EL_COUNT, AZ_COUNT, TAPS);

  for (int e = 0; e < EL_COUNT; ++e) {
    float el = EL_MIN + e * EL_STEP;
    fprintf(f, "{ // el=%.0f\n", el);
    for (int a = 0; a < AZ_COUNT; ++a) {
      float my_az = a * AZ_STEP;
      float lib_az = fmodf(360.0f - my_az, 360.0f);
      float c[3] = {lib_az, el, 1.0f};
      mysofa_s2c(c);
      float dL = 0, dR = 0;
      mysofa_getfilter_float(easy, c[0], c[1], c[2], irL, irR, &dL, &dR);
      fprintf(f, "{{");
      for (int t = 0; t < TAPS; ++t)
        fprintf(f, "%.7ef,", t < filterlen ? irL[t] : 0.0f);
      fprintf(f, "},{");
      for (int t = 0; t < TAPS; ++t)
        fprintf(f, "%.7ef,", t < filterlen ? irR[t] : 0.0f);
      fprintf(f, "}},");
      if (a % 2 == 1) fprintf(f, "\n");
    }
    fprintf(f, "},\n");
  }
  fprintf(f,
    "};\n\n"
    "}}}  // namespace tf::atmos::render\n\n"
    "#endif  // TF_ATMOS_RENDER_HRIR_TABLE_H\n");
  fclose(f);
  free(irL); free(irR);
  mysofa_close(easy);
  fprintf(stderr, "wrote %s (filterlen was %d, truncated to %d)\n",
          argv[2], filterlen, TAPS);
  return 0;
}

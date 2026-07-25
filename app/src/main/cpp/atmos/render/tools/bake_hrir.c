// Bakes MIT KEMAR HRIRs into a compact C++ table for the Atmos binaural
// renderer. libmysofa loads the SOFA, resamples to 48 kHz and loudness-
// normalizes; we sample a uniform azimuth/elevation grid.
//
// ONSET-ALIGNED decomposition (this is load-bearing — do not "simplify" it back
// to storing the raw FIR). Each ear's raw HRIR carries the interaural time
// difference as an onset delay baked into the samples. Bilinearly interpolating
// two such FIRs whose onsets differ averages two time-shifted responses, which
// comb-filters the result (measured: a -17.8 dB notch at 10 kHz between two
// nodes only 10 deg apart) and sounds "lofi/thin". So we split each HRIR into:
//   * kHrirData  — the response shifted so its onset sits at tap 0 (co-timed
//                  across all directions, so interpolation no longer combs), and
//   * kHrirDelay — the onset delay in samples (the ITD), interpolated and
//                  re-applied as a fractional delay by the renderer.
// A global minimum onset is subtracted from every delay so the smallest is 0
// (removes constant latency; preserves every inter-ear and inter-direction ITD).
//
// The Android build embeds the emitted header and needs no libmysofa, no zlib
// and no runtime asset — the renderer stays header-only.
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
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include "mysofa.h"

#define TAPS 96
#define AZ_STEP 10
#define AZ_COUNT 36            // 0,10,...,350  (wraps)
#define EL_MIN (-40)
#define EL_STEP 20
#define EL_COUNT 6             // -40,-20,0,20,40,60  (clamps)
#define ONSET_FRAC 0.10f       // onset = first sample >= 10% of the ear's peak

// First sample index whose magnitude reaches ONSET_FRAC of the ear's peak.
static int find_onset(const float* h, int n) {
  float pk = 0.0f;
  for (int i = 0; i < n; ++i) { float a = fabsf(h[i]); if (a > pk) pk = a; }
  const float thr = ONSET_FRAC * pk;
  for (int i = 0; i < n; ++i) if (fabsf(h[i]) >= thr) return i;
  return 0;
}

int main(int argc, char** argv) {
  const char* path = argv[1];
  int filterlen = 0, err = 0;
  struct MYSOFA_EASY* easy = mysofa_open(path, 48000.0f, &filterlen, &err);
  if (!easy) { fprintf(stderr, "open failed err=%d\n", err); return 1; }
  float* ir[2];
  ir[0] = (float*)malloc(sizeof(float) * filterlen);
  ir[1] = (float*)malloc(sizeof(float) * filterlen);

  // Pass 1: onsets for every grid point / ear, and the global minimum.
  static int onset[EL_COUNT][AZ_COUNT][2];
  int gmin = filterlen;
  for (int e = 0; e < EL_COUNT; ++e)
    for (int a = 0; a < AZ_COUNT; ++a) {
      float lib_az = fmodf(360.0f - a * AZ_STEP, 360.0f);
      float c[3] = {lib_az, (float)(EL_MIN + e * EL_STEP), 1.0f};
      mysofa_s2c(c);
      float dL, dR;
      mysofa_getfilter_float(easy, c[0], c[1], c[2], ir[0], ir[1], &dL, &dR);
      for (int ear = 0; ear < 2; ++ear) {
        int o = find_onset(ir[ear], filterlen);
        onset[e][a][ear] = o;
        if (o < gmin) gmin = o;
      }
    }

  FILE* f = fopen(argv[2], "w");
  fprintf(f,
    "// GENERATED — do not edit. Baked from MIT KEMAR (libmysofa) at 48 kHz.\n"
    "// Source SOFA: MIT_KEMAR_normal_pinna.sofa (public-domain KEMAR set).\n"
    "// Onset-aligned response + separate ITD delay; see tools/bake_hrir.c.\n"
    "#ifndef TF_ATMOS_RENDER_HRIR_TABLE_H\n"
    "#define TF_ATMOS_RENDER_HRIR_TABLE_H\n\n"
    "namespace tf { namespace atmos { namespace render {\n\n"
    "constexpr int   kHrirTaps    = %d;\n"
    "constexpr int   kHrirAzCount = %d;   // uniform, wraps\n"
    "constexpr float kHrirAzStep  = %d.0f;\n"
    "constexpr int   kHrirElCount = %d;   // uniform, clamps\n"
    "constexpr float kHrirElMin   = %d.0f;\n"
    "constexpr float kHrirElStep  = %d.0f;\n\n"
    "// Onset-aligned response, [el][az][ear(0=L,1=R)][tap].\n"
    "constexpr float kHrirData[%d][%d][2][%d] = {\n",
    TAPS, AZ_COUNT, AZ_STEP, EL_COUNT, EL_MIN, EL_STEP,
    EL_COUNT, AZ_COUNT, TAPS);

  double worst_e = 100.0;
  for (int e = 0; e < EL_COUNT; ++e) {
    fprintf(f, "{ // el=%d\n", EL_MIN + e * EL_STEP);
    for (int a = 0; a < AZ_COUNT; ++a) {
      float lib_az = fmodf(360.0f - a * AZ_STEP, 360.0f);
      float c[3] = {lib_az, (float)(EL_MIN + e * EL_STEP), 1.0f};
      mysofa_s2c(c);
      float dL, dR;
      mysofa_getfilter_float(easy, c[0], c[1], c[2], ir[0], ir[1], &dL, &dR);
      fprintf(f, "{");
      for (int ear = 0; ear < 2; ++ear) {
        const int o = onset[e][a][ear];
        // Aligned energy captured by TAPS (diagnostic).
        double tot = 0, cap = 0;
        for (int t = 0; t < filterlen; ++t) {
          double v = ir[ear][t]; if (t >= o) { double x = v * v; tot += x; if (t - o < TAPS) cap += x; }
        }
        if (tot > 0 && 100.0 * cap / tot < worst_e) worst_e = 100.0 * cap / tot;
        fprintf(f, "{");
        for (int t = 0; t < TAPS; ++t) {
          int src = o + t;
          fprintf(f, "%.7ef,", src < filterlen ? ir[ear][src] : 0.0f);
        }
        fprintf(f, "}%s", ear == 0 ? "," : "");
      }
      fprintf(f, "},");
      if (a % 2 == 1) fprintf(f, "\n");
    }
    fprintf(f, "},\n");
  }
  fprintf(f, "};\n\n// ITD as onset delay in samples (global minimum removed), [el][az][ear].\n"
    "constexpr float kHrirDelay[%d][%d][2] = {\n", EL_COUNT, AZ_COUNT);
  for (int e = 0; e < EL_COUNT; ++e) {
    fprintf(f, "{");
    for (int a = 0; a < AZ_COUNT; ++a)
      fprintf(f, "{%d.0f,%d.0f},", onset[e][a][0] - gmin, onset[e][a][1] - gmin);
    fprintf(f, "},\n");
  }
  fprintf(f,
    "};\n\n}}}  // namespace tf::atmos::render\n\n"
    "#endif  // TF_ATMOS_RENDER_HRIR_TABLE_H\n");
  fclose(f);
  free(ir[0]); free(ir[1]);
  mysofa_close(easy);
  fprintf(stderr, "wrote %s: taps=%d, global_min_onset=%d, worst aligned energy in TAPS=%.1f%%\n",
          argv[2], TAPS, gmin, worst_e);
  return 0;
}

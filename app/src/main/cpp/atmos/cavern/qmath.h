// Ported from Cavern (Cavern.Utilities.QMath) — C# → C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// The multiply/accumulate primitives the QMF filterbank builds on. Cavern
// vectorizes these (and has a CavernAmp native path); this is the scalar C#
// reference behaviour, which the NDK/NEON build can specialize later.
#ifndef TF_ATMOS_CAVERN_QMATH_H
#define TF_ATMOS_CAVERN_QMATH_H

namespace tf {
namespace atmos {
namespace cavern {
namespace qmath {

// out[i] = a[i] * b[i]
inline void multiply_and_set(const float* a, const float* b, float* out, int n) {
  for (int i = 0; i < n; ++i) out[i] = a[i] * b[i];
}

// out[i] += a[i] * b[i]
inline void multiply_and_add(const float* a, const float* b, float* out, int n) {
  for (int i = 0; i < n; ++i) out[i] += a[i] * b[i];
}

// returns Σ a[i] * b[i]
inline float dot(const float* a, const float* b, int n) {
  float sum = 0.0f;
  for (int i = 0; i < n; ++i) sum += a[i] * b[i];
  return sum;
}

// out[i] = a[i] * sa + b[i] * sb  (complex-multiply accumulation, set)
inline void multiply_and_set(const float* a, float sa, const float* b, float sb,
                             float* out, int n) {
  for (int i = 0; i < n; ++i) out[i] = a[i] * sa + b[i] * sb;
}

// out[i] += a[i] * sa + b[i] * sb
inline void multiply_and_add(const float* a, float sa, const float* b, float sb,
                             float* out, int n) {
  for (int i = 0; i < n; ++i) out[i] += a[i] * sa + b[i] * sb;
}

// out[i] = a[i] * b[i] + c[i] * d[i]
inline void multiply_and_set(const float* a, const float* b, const float* c,
                             const float* d, float* out, int n) {
  for (int i = 0; i < n; ++i) out[i] = a[i] * b[i] + c[i] * d[i];
}

// out[i] += a[i] * b[i] + c[i] * d[i]
inline void multiply_and_add(const float* a, const float* b, const float* c,
                             const float* d, float* out, int n) {
  for (int i = 0; i < n; ++i) out[i] += a[i] * b[i] + c[i] * d[i];
}

}  // namespace qmath
}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_QMATH_H

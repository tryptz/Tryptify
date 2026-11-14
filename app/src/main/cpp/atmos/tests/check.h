// The tests' checks: CHECK(condition) and CHECK_NEAR(a, b, tolerance), which
// count themselves in g_checks and their failures in g_fail and say where a
// failure is.
//
// Every test here is built a second time with the phone's -ffast-math, under
// which a plain comparison with NaN is folded away: CHECK_NEAR(NaN, x, t)
// would pass. CHECK_NEAR therefore refuses a NaN or an infinity through
// dspIsFinite, and a test that wants to know a value is finite asks
// dspIsFinite too, never std::isfinite.
#ifndef TF_ATMOS_TESTS_CHECK_H
#define TF_ATMOS_TESTS_CHECK_H

#include <cmath>
#include <cstdio>

#include "../../dsp/util/finite.h"

inline int g_fail = 0, g_checks = 0;

inline void check(bool ok, const char* expr, const char* file, int line) {
  ++g_checks;
  if (!ok) {
    ++g_fail;
    std::printf("  FAIL %s:%d %s\n", file, line, expr);
  }
}

inline void check_near(double a, double b, double tol, const char* expr, const char* file,
                       int line) {
  ++g_checks;
  if (!dspIsFinite(a) || !dspIsFinite(b) || !(std::fabs(a - b) <= tol)) {
    ++g_fail;
    std::printf("  FAIL %s:%d %s (%.6f vs %.6f)\n", file, line, expr, a, b);
  }
}

#define CHECK(c) check((c), #c, __FILE__, __LINE__)
#define CHECK_NEAR(a, b, t) check_near((a), (b), (t), #a " ~= " #b, __FILE__, __LINE__)

#endif  // TF_ATMOS_TESTS_CHECK_H

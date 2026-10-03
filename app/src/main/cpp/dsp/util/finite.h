#pragma once
#include <cstdint>
#include <cstring>

// Finite-number checks that survive -ffast-math.
//
// The DSP library is built with -ffast-math (CMakeLists.txt), which tells the
// compiler no NaN or infinity ever exists — so it may fold std::isfinite(x)
// to true and delete the branch. A guard written with it guards nothing on a
// phone, while the host tests, built without the flag, pass. These read the
// float's exponent bits instead: all ones means NaN or infinity, and no
// optimisation assumption can make that test disappear.
//
// engine_stress_test is built with -ffast-math for exactly this reason.

inline bool dspIsFinite(float x) {
    uint32_t b;
    std::memcpy(&b, &x, sizeof b);
    return (b & 0x7F800000u) != 0x7F800000u;
}

inline bool dspIsFinite(double x) {
    uint64_t b;
    std::memcpy(&b, &x, sizeof b);
    return (b & 0x7FF0000000000000ull) != 0x7FF0000000000000ull;
}

// Whether all [n] samples are finite. Branch-free, so it vectorises.
inline bool dspAllFinite(const float* x, int n) {
    uint32_t bad = 0;
    for (int i = 0; i < n; i++) {
        uint32_t b;
        std::memcpy(&b, &x[i], sizeof b);
        bad |= static_cast<uint32_t>((b & 0x7F800000u) == 0x7F800000u);
    }
    return bad == 0;
}

// Replaces every non-finite sample with silence, in place.
inline void dspZeroNonFinite(float* x, int n) {
    for (int i = 0; i < n; i++) {
        if (!dspIsFinite(x[i])) x[i] = 0.0f;
    }
}

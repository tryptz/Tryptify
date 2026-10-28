#pragma once
#include <cstdint>
#include <cstring>

// Finite-number checks that survive -ffast-math.
//
// The DSP library is built with -ffast-math (CMakeLists.txt), which tells the
// compiler no NaN or infinity ever exists — so it may fold std::isfinite(x)
// to true and delete the branch. A guard written with it guards nothing on a
// phone, while the host tests, built without the flag, pass. These read the
// float's exponent bits instead: all ones means NaN or infinity.
//
// Reading the bits is not enough on its own either. The same flag lets the
// compiler take any float value it can see as finite, and clang 21 (NDK r29)
// does: it marks every float a function is handed as never NaN or infinity,
// works out that its exponent bits can then never be all ones, and folds the
// test away. dspIsFinite() said a NaN was finite on the phone, so the guards
// built on it let everything through, while the host compilers kept the test.
// dspOpaque() puts an empty asm in the way, through which the compiler can see
// neither the bits nor where a pointer points, so there is nothing to fold.
//
// engine_stress_test is built with -ffast-math for exactly this reason; the
// clang 21 fold above shows only in an NDK build (checked under qemu).

// The compiler forgets what [v] holds; it costs no instruction.
template <typename T>
inline void dspOpaque(T& v) {
#if defined(__GNUC__) || defined(__clang__)
    __asm__("" : "+r"(v));
#endif
}

inline bool dspIsFinite(float x) {
    uint32_t b;
    std::memcpy(&b, &x, sizeof b);
    dspOpaque(b);
    return (b & 0x7F800000u) != 0x7F800000u;
}

inline bool dspIsFinite(double x) {
    uint64_t b;
    std::memcpy(&b, &x, sizeof b);
    dspOpaque(b);
    return (b & 0x7FF0000000000000ull) != 0x7FF0000000000000ull;
}

// Whether all [n] samples are finite. Branch-free, so it vectorises. The
// samples are read through a pointer the compiler cannot follow, or one it
// had just written them through would let it reason about them as above.
inline bool dspAllFinite(const float* x, int n) {
    dspOpaque(x);
    uint32_t bad = 0;
    for (int i = 0; i < n; i++) {
        uint32_t b;
        std::memcpy(&b, &x[i], sizeof b);
        bad |= static_cast<uint32_t>((b & 0x7F800000u) == 0x7F800000u);
    }
    return bad == 0;
}

// Replaces every non-finite sample with silence, in place, and says whether
// there was one. Branch-free, so it vectorises: each sample is masked, all
// ones when finite and so written back as it was, zero (0.0f) when not.
inline bool dspZeroNonFinite(float* x, int n) {
    dspOpaque(x);
    uint32_t bad = 0;
    for (int i = 0; i < n; i++) {
        uint32_t b;
        std::memcpy(&b, &x[i], sizeof b);
        const uint32_t nonFinite = static_cast<uint32_t>((b & 0x7F800000u) == 0x7F800000u);
        bad |= nonFinite;
        b &= nonFinite - 1u;
        std::memcpy(&x[i], &b, sizeof b);
    }
    return bad != 0;
}

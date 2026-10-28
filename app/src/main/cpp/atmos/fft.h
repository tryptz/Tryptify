// FFTs for the Atmos filterbank and the renderer's convolutions, on the
// vendored signalsmith-linear FFT (third_party/signalsmith-stretch, MIT).
//
// The library is included by its path from here because neither the app's
// Atmos targets nor the host test builds list third_party/ as an include
// directory; a path relative to this file resolves the same in all of them.
//
// Only its plain complex FFT is used, through the interleaved std::complex
// entry points: at the sizes needed here (64 to 256 points) those measured
// about twice as fast as its split-complex ones, which its own real FFT
// wrapper goes through. The real FFT is therefore done here, the usual way:
// a real signal of N samples as a complex one of N/2, then one pass to
// separate the even and odd halves.
//
// Header-only, no exceptions or RTTI. Objects allocate only when constructed;
// forward() and inverse() allocate nothing and take no locks, so they are
// safe on the audio thread. One object per thread at a time: they keep
// scratch.
#ifndef TF_ATMOS_FFT_H
#define TF_ATMOS_FFT_H

#include <cmath>
#include <complex>
#include <vector>

#include "../../../../../third_party/signalsmith-stretch/signalsmith-linear/fft.h"

namespace tf {
namespace atmos {
namespace fft {

using Complex = std::complex<float>;

// Complex products written out. std::complex's operator* goes through the
// C library's NaN-and-infinity-correct __mulsc3 unless -ffast-math is on,
// which only the JNI library has; these are the same everywhere.
inline Complex mul(Complex a, Complex b) {
  return Complex(a.real() * b.real() - a.imag() * b.imag(), a.real() * b.imag() + a.imag() * b.real());
}
// a * conj(b)
inline Complex mul_conj(Complex a, Complex b) {
  return Complex(a.real() * b.real() + a.imag() * b.imag(), a.imag() * b.real() - a.real() * b.imag());
}

// Unnormalised complex FFT of a power-of-two size, out of place. forward()
// uses e^(-2 pi i n k / N); inverse() e^(+...), so inverse(forward(x)) = N x.
class ComplexFft {
 public:
  explicit ComplexFft(int size) : fft_(static_cast<size_t>(size)) {}
  void forward(const Complex* time, Complex* freq) { fft_.fft(time, freq); }
  void inverse(const Complex* freq, Complex* time) { fft_.ifft(freq, time); }

 private:
  signalsmith::linear::SimpleFFT<float> fft_;
};

// Real FFT of a power-of-two size N >= 4. The spectrum is N/2 + 1 bins, DC to
// Nyquist. Unnormalised: inverse(forward(x)) = N x.
class RealFft {
 public:
  explicit RealFft(int size)
      : n_(size), half_(size / 2), fft_(size / 2), z_(size / 2), zf_(size / 2),
        twiddle_(size / 4 + 1) {
    // w^k = e^(-2 pi i k / N), worked out in double and rounded once.
    for (int k = 0; k <= half_ / 2; ++k) {
      const double phase = -2.0 * 3.14159265358979323846 * k / n_;
      twiddle_[k] = Complex(static_cast<float>(std::cos(phase)), static_cast<float>(std::sin(phase)));
    }
  }

  int size() const { return n_; }
  int bins() const { return half_ + 1; }

  // `time` holds N samples; `spec` gets N/2 + 1 bins.
  void forward(const float* time, Complex* spec) {
    for (int i = 0; i < half_; ++i) z_[i] = Complex(time[2 * i], time[2 * i + 1]);
    fft_.forward(z_.data(), zf_.data());
    // Z = E + i O, where E and O are the spectra of the even and odd samples;
    // X[k] = E[k] + w^k O[k], and X[M - k] = conj(E[k] - w^k O[k]).
    const int m = half_;
    for (int k = 0; k <= m / 2; ++k) {
      const Complex zk = zf_[k];
      const Complex zc = std::conj(zf_[k == 0 ? 0 : m - k]);
      const Complex e = 0.5f * (zk + zc);
      const Complex d = 0.5f * (zk - zc);
      const Complex o(d.imag(), -d.real());  // d / i
      const Complex t = mul(twiddle_[k], o);
      spec[k] = e + t;
      spec[m - k] = std::conj(e - t);
    }
  }

  // `spec` holds N/2 + 1 bins; `time` gets N samples (N times the signal).
  void inverse(const Complex* spec, float* time) {
    const int m = half_;
    for (int k = 0; k <= m / 2; ++k) {
      const Complex xk = spec[k];
      const Complex xc = std::conj(spec[m - k]);
      const Complex e = xk + xc;
      const Complex o = mul_conj(xk - xc, twiddle_[k]);
      // Z[k] = E + i O; Z[M - k] = conj(E) + i conj(O).
      zf_[k] = Complex(e.real() - o.imag(), e.imag() + o.real());
      if (k != 0) zf_[m - k] = Complex(e.real() + o.imag(), o.real() - e.imag());
    }
    fft_.inverse(zf_.data(), z_.data());
    for (int i = 0; i < half_; ++i) {
      time[2 * i] = z_[i].real();
      time[2 * i + 1] = z_[i].imag();
    }
  }

 private:
  int n_, half_;
  ComplexFft fft_;
  std::vector<Complex> z_, zf_;
  std::vector<Complex> twiddle_;
};

}  // namespace fft
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_FFT_H

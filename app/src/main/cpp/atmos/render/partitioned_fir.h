// A long FIR applied to both channels of interleaved stereo, as a uniformly
// partitioned convolution: the spatial map's equalizer (channel_placer.h).
//
// The filter is cut into partitions of kBlock taps. Overlap-save convolves
// each block of kBlock input samples (with the block before it, through
// 2 kBlock-point FFTs) against every partition; a block's input spectrum is
// kept and meets partition p again p blocks later. A block of 128 at 512
// taps then costs two FFTs and four spectral products per channel, against
// 128 x 512 multiply-adds in direct form.
//
// No latency is added. A whole block is in hand when its outputs are due, so
// it goes through the spectra at once. When a call ends inside a block (the
// player's buffers need not be multiples of kBlock), the samples that are in
// take the first partition directly, and the later partitions' share, which
// depends only on blocks already complete, from their spectra. Either way
// output n is the sum over k of h[k] x[n - k], to float rounding.
//
// Header-only. Allocates only when constructed; set_filter(), reset() and
// process() allocate nothing and take no locks. Not thread-safe: one thread
// at a time (the audio thread, once running).
#ifndef TF_ATMOS_RENDER_PARTITIONED_FIR_H
#define TF_ATMOS_RENDER_PARTITIONED_FIR_H

#include <cstring>

#include "../fft.h"

namespace tf {
namespace atmos {
namespace render {

template <int kTaps>
class StereoPartitionedFir {
 public:
  static constexpr int kBlock = 128;
  static constexpr int kFft = 2 * kBlock;
  static constexpr int kBins = kFft / 2 + 1;
  static constexpr int kParts = kTaps / kBlock;
  static_assert(kParts * kBlock == kTaps, "the filter must split into whole partitions");

  StereoPartitionedFir() : fft_(kFft) { reset(); }

  // Takes kTaps taps. It applies from the next sample on, to the history as
  // well, as a direct-form FIR whose taps were swapped would.
  void set_filter(const float* h) {
    for (int k = 0; k < kBlock; ++k) head_[k] = h[k];
    // Scaled by 1/kFft so the unnormalised inverse FFT gives the convolution.
    const float scale = 1.0f / kFft;
    for (int p = 0; p < kParts; ++p) {
      for (int k = 0; k < kBlock; ++k) y_[k] = h[p * kBlock + k] * scale;
      for (int k = kBlock; k < kFft; ++k) y_[k] = 0.0f;
      fft_.forward(y_, spec_[p]);
    }
    // A part-done block's tail was worked out with the old filter.
    tail_ready_ = false;
  }

  // Clears the input history, as if it had been silent.
  void reset() {
    for (int ch = 0; ch < 2; ++ch) {
      for (int p = 0; p < kParts; ++p) {
        for (int k = 0; k < kBins; ++k) fdl_[ch][p][k] = fft::Complex(0.0f, 0.0f);
      }
      for (int k = 0; k < kFft; ++k) win_[ch][k] = 0.0f;
    }
    pos_ = 0;
    slot_ = 0;
    tail_ready_ = false;
  }

  // Filters [m] interleaved stereo frames in place.
  void process(float* io, int m) {
    int i = 0;
    while (i < m) {
      if (pos_ == 0 && m - i >= kBlock) {
        // A whole block: every partition through the spectra.
        for (int ch = 0; ch < 2; ++ch) {
          float* w = win_[ch] + kBlock;
          for (int t = 0; t < kBlock; ++t) w[t] = io[2 * (i + t) + ch];
          fft_.forward(win_[ch], fdl_[ch][slot_]);
          partitions(ch, 0);
          for (int t = 0; t < kBlock; ++t) io[2 * (i + t) + ch] = y_[kBlock + t];
          std::memcpy(win_[ch], win_[ch] + kBlock, sizeof(float) * kBlock);
        }
        slot_ = (slot_ + 1) % kParts;
        i += kBlock;
        continue;
      }
      // Part of a block.
      if (!tail_ready_) {
        for (int ch = 0; ch < 2; ++ch) {
          partitions(ch, 1);
          std::memcpy(tail_[ch], y_ + kBlock, sizeof(float) * kBlock);
        }
        tail_ready_ = true;
      }
      const int r = (kBlock - pos_) < (m - i) ? (kBlock - pos_) : (m - i);
      for (int ch = 0; ch < 2; ++ch) {
        float* x = win_[ch] + kBlock + pos_;
        for (int t = 0; t < r; ++t) {
          x[t] = io[2 * (i + t) + ch];
          const float* w = x + t;  // w[0] newest, w[-k] k samples ago
          float y = 0.0f;
          for (int k = 0; k < kBlock; ++k) y += head_[k] * w[-k];
          io[2 * (i + t) + ch] = tail_[ch][pos_ + t] + y;
        }
      }
      pos_ += r;
      i += r;
      if (pos_ == kBlock) {
        for (int ch = 0; ch < 2; ++ch) {
          fft_.forward(win_[ch], fdl_[ch][slot_]);
          std::memcpy(win_[ch], win_[ch] + kBlock, sizeof(float) * kBlock);
        }
        slot_ = (slot_ + 1) % kParts;
        pos_ = 0;
        tail_ready_ = false;
      }
    }
  }

 private:
  // Partitions [from, kParts) times the input spectra they meet in the
  // current block, back to time: the current block's outputs' share from
  // them is y_[kBlock ..]. Partition p meets the block p blocks back.
  void partitions(int ch, int from) {
    for (int k = 0; k < kBins; ++k) z_[k] = fft::Complex(0.0f, 0.0f);
    for (int p = from; p < kParts; ++p) {
      const fft::Complex* h = spec_[p];
      const fft::Complex* x = fdl_[ch][(slot_ - p + kParts) % kParts];
      for (int k = 0; k < kBins; ++k) z_[k] += fft::mul(h[k], x[k]);
    }
    fft_.inverse(z_, y_);
  }

  fft::RealFft fft_;
  float head_[kBlock];                 // the first partition, for part blocks
  fft::Complex spec_[kParts][kBins];   // each partition's spectrum
  // Per channel: the input spectra of the last kParts blocks (a ring, the
  // current block's at slot_), and the window [previous block | current].
  fft::Complex fdl_[2][kParts][kBins];
  float win_[2][kFft];
  // Per channel: the current block's outputs' share from partitions 1..,
  // kept while calls end inside the block.
  float tail_[2][kBlock];
  bool tail_ready_ = false;
  int pos_ = 0;   // samples of the current block already in
  int slot_ = 0;  // the current block's place in the ring
  fft::Complex z_[kBins];
  float y_[kFft];
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_PARTITIONED_FIR_H

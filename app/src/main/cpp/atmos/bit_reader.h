// Clean-room MSB-first bit reader for Dolby E-AC-3 / EMDF side-data parsing.
//
// E-AC-3 syncframes, the EMDF container (ETSI TS 102 366 Annex H) and the
// OAMD/JOC payloads (ETSI TS 103 420) are all serialized big-endian, MSB-first,
// with no byte alignment between fields. This reader walks such a stream a bit
// at a time and implements the spec's `variable_bits()` escape used to encode
// unbounded values (payload ids, object counts, version numbers).
//
// Header-only and dependency-free (only <cstdint>/<cstddef>) so it builds under
// the NDK for arm64-v8a and compiles unchanged on a host toolchain for tests.
#ifndef TF_ATMOS_BIT_READER_H
#define TF_ATMOS_BIT_READER_H

#include <cstddef>
#include <cstdint>

namespace tf {
namespace atmos {

class BitReader {
 public:
  BitReader(const uint8_t* data, size_t size_bytes)
      : data_(data), size_bits_(size_bytes * 8), pos_(0) {}

  // Bits consumed so far and bits still available.
  size_t position() const { return pos_; }
  size_t remaining() const { return pos_ <= size_bits_ ? size_bits_ - pos_ : 0; }
  bool exhausted() const { return pos_ >= size_bits_; }

  // Reads a single bit; returns 0 once the buffer is exhausted (callers that
  // care about truncation should check remaining() first).
  uint32_t read_bit() {
    if (pos_ >= size_bits_) {
      pos_++;  // keep advancing so remaining()==0 stays stable
      return 0;
    }
    const uint32_t byte = data_[pos_ >> 3];
    const uint32_t shift = 7u - (pos_ & 7u);
    pos_++;
    return (byte >> shift) & 1u;
  }

  // Reads `n` bits (0..32) MSB-first into an unsigned value.
  uint32_t read(unsigned n) {
    uint32_t value = 0;
    for (unsigned i = 0; i < n; ++i) {
      value = (value << 1) | read_bit();
    }
    return value;
  }

  // Advances without materializing a value.
  void skip(size_t n) { pos_ += n; }

  // Byte-aligns the cursor to the next byte boundary.
  void byte_align() {
    const size_t rem = pos_ & 7u;
    if (rem != 0) pos_ += (8u - rem);
  }

  // ETSI variable-length integer (the `variable_bits` primitive shared by EMDF
  // and OAMD/JOC): read an `n_bits` group, and while the following continuation
  // bit is set, shift in another group with the `+1` offset the spec applies so
  // that longer encodings never alias a shorter value's range.
  uint32_t read_variable_bits(unsigned n_bits) {
    uint32_t value = read(n_bits);
    while (read_bit() == 1u) {
      value += 1u;
      value <<= n_bits;
      value += read(n_bits);
    }
    return value;
  }

 private:
  const uint8_t* data_;
  size_t size_bits_;
  size_t pos_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_BIT_READER_H

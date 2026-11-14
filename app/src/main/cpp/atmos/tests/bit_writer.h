// What the tests build frames with: an MSB-first bit writer, the mirror of
// tf::atmos::BitReader.
#ifndef TF_ATMOS_TESTS_BIT_WRITER_H
#define TF_ATMOS_TESTS_BIT_WRITER_H

#include <cstddef>
#include <cstdint>
#include <vector>

struct BitWriter {
  std::vector<uint8_t> bytes;
  size_t count = 0;
  void bit(int b) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (b & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put_bit(int b) { bit(b); }
  void put(unsigned n, uint32_t v) {
    for (int i = static_cast<int>(n) - 1; i >= 0; --i) bit((v >> i) & 1u);
  }
};

#endif  // TF_ATMOS_TESTS_BIT_WRITER_H

// Clean-room Vector Base Amplitude Panning (VBAP) for the Atmos bed back-end.
//
// VBAP (Pulkki, 1997) pans a point source to the 2 nearest loudspeakers on a
// ring (2-D) or the 3 nearest on a sphere (3-D triplet). Given the object's
// direction as a unit vector p and a loudspeaker basis L, the gains solve
// g = L^-1 p, kept only when every component is non-negative (i.e. p lies inside
// that speaker base), then normalized for constant power (||g|| = 1).
//
// This is the `VBAPBedPanner` back-end from the renderer plan: an OAMD object
// position becomes per-speaker gains for a 5.1 / 7.1 / 7.1.4 USB-DAC layout.
// The LFE channel is directionless — it is excluded from panning and always
// receives gain 0 here (bass management routes low frequencies separately).
//
// Header-only, only <array>/<vector>/<cmath>; builds under the NDK and on a host
// toolchain unchanged.
#ifndef TF_ATMOS_VBAP_H
#define TF_ATMOS_VBAP_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace tf {
namespace atmos {

struct Vec3 {
  float x = 0.0f;  // +x = right
  float y = 0.0f;  // +y = front
  float z = 0.0f;  // +z = up
};

inline float dot(const Vec3& a, const Vec3& b) {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}

inline Vec3 normalize(const Vec3& v) {
  const float n = std::sqrt(dot(v, v));
  if (n <= 1e-12f) return {0.0f, 1.0f, 0.0f};
  return {v.x / n, v.y / n, v.z / n};
}

// Azimuth measured clockwise from front (0 = front, +90 = hard right), elevation
// measured up from the horizontal plane. Matches the direction convention used
// for Dolby speaker labels (L = -30, R = +30, ...).
inline Vec3 direction_from_angles(float azimuth_deg, float elevation_deg) {
  const float az = azimuth_deg * 3.14159265358979323846f / 180.0f;
  const float el = elevation_deg * 3.14159265358979323846f / 180.0f;
  const float cos_el = std::cos(el);
  return {cos_el * std::sin(az), cos_el * std::cos(az), std::sin(el)};
}

struct Speaker {
  Vec3 dir;           // unit direction of the loudspeaker
  bool is_lfe = false;  // LFE is excluded from panning
};

class LoudspeakerLayout {
 public:
  void add(float azimuth_deg, float elevation_deg) {
    speakers_.push_back({direction_from_angles(azimuth_deg, elevation_deg), false});
    if (elevation_deg > 1.0f) has_height_ = true;
  }

  void add_lfe() { speakers_.push_back({{0.0f, 1.0f, 0.0f}, true}); }

  size_t size() const { return speakers_.size(); }
  const Speaker& at(size_t i) const { return speakers_[i]; }
  bool has_height() const { return has_height_; }

  // Standard Dolby bed layouts. Channel order matches the SMPTE/Dolby default
  // so gains[] lines up with the interleaved bed the renderer feeds the DAC.
  static LoudspeakerLayout surround_5_1() {
    LoudspeakerLayout l;               // L R C LFE Ls Rs
    l.add(-30, 0); l.add(30, 0); l.add(0, 0);
    l.add_lfe();
    l.add(-110, 0); l.add(110, 0);
    return l;
  }

  static LoudspeakerLayout surround_7_1() {
    LoudspeakerLayout l;               // L R C LFE Lss Rss Lrs Rrs
    l.add(-30, 0); l.add(30, 0); l.add(0, 0);
    l.add_lfe();
    l.add(-90, 0); l.add(90, 0); l.add(-150, 0); l.add(150, 0);
    return l;
  }

  static LoudspeakerLayout atmos_7_1_4() {
    LoudspeakerLayout l;               // L R C LFE Lss Rss Lrs Rrs + 4 tops
    l.add(-30, 0); l.add(30, 0); l.add(0, 0);
    l.add_lfe();
    l.add(-90, 0); l.add(90, 0); l.add(-150, 0); l.add(150, 0);
    l.add(-45, 45); l.add(45, 45); l.add(-135, 45); l.add(135, 45);
    return l;
  }

 private:
  std::vector<Speaker> speakers_;
  bool has_height_ = false;
};

namespace detail {

// Solve the 2x2 system [a b; c d] g = [px; py].
inline bool solve2(float a, float b, float c, float d, float px, float py,
                   float* g0, float* g1) {
  const float det = a * d - b * c;
  if (std::fabs(det) < 1e-9f) return false;
  *g0 = (px * d - py * b) / det;
  *g1 = (a * py - c * px) / det;
  return true;
}

// Solve the 3x3 system with columns c0,c1,c2 for the RHS p via Cramer's rule.
inline bool solve3(const Vec3& c0, const Vec3& c1, const Vec3& c2, const Vec3& p,
                   float* g0, float* g1, float* g2) {
  const float det =
      c0.x * (c1.y * c2.z - c1.z * c2.y) -
      c1.x * (c0.y * c2.z - c0.z * c2.y) +
      c2.x * (c0.y * c1.z - c0.z * c1.y);
  if (std::fabs(det) < 1e-9f) return false;
  const float dx =
      p.x * (c1.y * c2.z - c1.z * c2.y) -
      c1.x * (p.y * c2.z - p.z * c2.y) +
      c2.x * (p.y * c1.z - p.z * c1.y);
  const float dy =
      c0.x * (p.y * c2.z - p.z * c2.y) -
      p.x * (c0.y * c2.z - c0.z * c2.y) +
      c2.x * (c0.y * p.z - c0.z * p.y);
  const float dz =
      c0.x * (c1.y * p.z - c1.z * p.y) -
      c1.x * (c0.y * p.z - c0.z * p.y) +
      p.x * (c0.y * c1.z - c0.z * c1.y);
  *g0 = dx / det;
  *g1 = dy / det;
  *g2 = dz / det;
  return true;
}

// Angular width of the widest edge of a speaker triple — a "tightness" measure
// used to pick the smallest triangle that still contains the source.
inline float widest_edge(const Vec3& a, const Vec3& b, const Vec3& c) {
  const float ab = std::acos(std::fmin(1.0f, std::fmax(-1.0f, dot(a, b))));
  const float bc = std::acos(std::fmin(1.0f, std::fmax(-1.0f, dot(b, c))));
  const float ca = std::acos(std::fmin(1.0f, std::fmax(-1.0f, dot(c, a))));
  return std::fmax(ab, std::fmax(bc, ca));
}

}  // namespace detail

// Computes per-speaker gains for a source direction. `gains` is resized to the
// layout size; LFE and inactive speakers are 0. Gains are constant-power
// normalized (sum of squares == 1) unless no valid base is found.
inline void pan_vbap(const LoudspeakerLayout& layout, const Vec3& source,
                     std::vector<float>& gains) {
  const size_t n = layout.size();
  gains.assign(n, 0.0f);
  const Vec3 p = normalize(source);
  const float eps = -1e-4f;  // tolerance for "just inside" a base

  if (layout.has_height()) {
    // 3-D triplet VBAP. Several triplets may span the source; VBAP wants the
    // *tightest* base (the actual mesh triangle), so among valid triplets pick
    // the one with the smallest widest edge. A source at a speaker lands on a
    // vertex shared by many triplets, all of which give that speaker gain ~1.
    float best_tight = 1e30f;
    int bi = -1, bj = -1, bk = -1;
    float bg0 = 0, bg1 = 0, bg2 = 0;
    for (size_t i = 0; i < n; ++i) {
      if (layout.at(i).is_lfe) continue;
      for (size_t j = i + 1; j < n; ++j) {
        if (layout.at(j).is_lfe) continue;
        for (size_t k = j + 1; k < n; ++k) {
          if (layout.at(k).is_lfe) continue;
          float g0, g1, g2;
          if (!detail::solve3(layout.at(i).dir, layout.at(j).dir,
                              layout.at(k).dir, p, &g0, &g1, &g2)) {
            continue;
          }
          if (g0 < eps || g1 < eps || g2 < eps) continue;
          const float tight = detail::widest_edge(
              layout.at(i).dir, layout.at(j).dir, layout.at(k).dir);
          if (tight < best_tight) {
            best_tight = tight;
            bi = static_cast<int>(i);
            bj = static_cast<int>(j);
            bk = static_cast<int>(k);
            bg0 = g0; bg1 = g1; bg2 = g2;
          }
        }
      }
    }
    if (bi < 0) return;
    gains[bi] = std::fmax(bg0, 0.0f);
    gains[bj] = std::fmax(bg1, 0.0f);
    gains[bk] = std::fmax(bg2, 0.0f);
  } else {
    // 2-D pairwise VBAP on the horizontal ring. Only *adjacent* speakers (once
    // sorted by azimuth, including the back wrap-around pair) form a legal base,
    // which is exactly the pair that brackets the source — using non-adjacent
    // pairs would let a wide straddling pair win over the true neighbors.
    std::vector<int> ring;
    ring.reserve(n);
    for (size_t i = 0; i < n; ++i) {
      if (!layout.at(i).is_lfe) ring.push_back(static_cast<int>(i));
    }
    std::sort(ring.begin(), ring.end(), [&](int a, int b) {
      return std::atan2(layout.at(a).dir.x, layout.at(a).dir.y) <
             std::atan2(layout.at(b).dir.x, layout.at(b).dir.y);
    });
    float best_min = -1e30f;
    int bi = -1, bj = -1;
    float bg0 = 0, bg1 = 0;
    for (size_t r = 0; r < ring.size(); ++r) {
      const int i = ring[r];
      const int j = ring[(r + 1) % ring.size()];  // wrap closes the ring
      float g0, g1;
      if (!detail::solve2(layout.at(i).dir.x, layout.at(j).dir.x,
                          layout.at(i).dir.y, layout.at(j).dir.y, p.x, p.y,
                          &g0, &g1)) {
        continue;
      }
      if (g0 < eps || g1 < eps) continue;
      const float mn = std::fmin(g0, g1);
      if (mn > best_min) {
        best_min = mn;
        bi = i;
        bj = j;
        bg0 = g0; bg1 = g1;
      }
    }
    if (bi < 0) return;
    gains[bi] = std::fmax(bg0, 0.0f);
    gains[bj] = std::fmax(bg1, 0.0f);
  }

  // Constant-power normalization.
  float sumsq = 0.0f;
  for (float g : gains) sumsq += g * g;
  if (sumsq > 1e-12f) {
    const float inv = 1.0f / std::sqrt(sumsq);
    for (float& g : gains) g *= inv;
  }
}

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_VBAP_H

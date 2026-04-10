#pragma once

#include "ChannelLayout.h"
#include <cmath>
#include <cstring>

// ── VBAP 3D Panner ─────────────────────────────────────────────────────
// Vector Base Amplitude Panning for 7.1.4 speaker dome.
// Finds the enclosing triangle for a source direction, computes gains
// using inverted triangle matrix, and normalizes for energy preservation.

namespace atmos {

class VbapPanner {
public:
    VbapPanner() {
        precomputeInverseMatrices();
    }

    // Compute 7.1.4 speaker gains for a source at Cartesian (x, y, z)
    // x: left(+)/right(-), y: front(+)/back(-), z: up(+)/down(-)
    // Output: gains[NUM_CHANNELS], energy-normalized
    void pan(float x, float y, float z, float* gains) const {
        memset(gains, 0, NUM_CHANNELS * sizeof(float));

        // Normalize source direction
        float len = std::sqrt(x * x + y * y + z * z);
        if (len < 1e-8f) {
            // Dead center: equal power to FC
            gains[FC] = 1.0f;
            return;
        }
        float sx = x / len, sy = y / len, sz = z / len;

        // Find the enclosing VBAP triangle with all-positive gains
        float bestEnergy = -1.0f;
        int bestTri = -1;
        float bestG[3] = {0, 0, 0};

        for (int t = 0; t < NUM_VBAP_TRIANGLES; t++) {
            float g[3];
            // g = inv(L) * p  where L = [l1 l2 l3] speaker unit vectors
            g[0] = invMat_[t][0][0] * sx + invMat_[t][0][1] * sy + invMat_[t][0][2] * sz;
            g[1] = invMat_[t][1][0] * sx + invMat_[t][1][1] * sy + invMat_[t][1][2] * sz;
            g[2] = invMat_[t][2][0] * sx + invMat_[t][2][1] * sy + invMat_[t][2][2] * sz;

            // All gains must be non-negative for the source to be inside this triangle
            if (g[0] >= -1e-6f && g[1] >= -1e-6f && g[2] >= -1e-6f) {
                float energy = g[0] * g[0] + g[1] * g[1] + g[2] * g[2];
                if (energy > bestEnergy) {
                    bestEnergy = energy;
                    bestTri = t;
                    bestG[0] = g[0]; bestG[1] = g[1]; bestG[2] = g[2];
                }
            }
        }

        if (bestTri < 0) {
            // Fallback: nearest speaker (shouldn't happen with good triangulation)
            float maxDot = -2.0f;
            int nearest = FC;
            for (int c = 0; c < NUM_CHANNELS; c++) {
                if (c == LFE) continue;
                float cx, cy, cz;
                kSpeakerPositions[c].toCartesian(cx, cy, cz);
                float dot = sx * cx + sy * cy + sz * cz;
                if (dot > maxDot) { maxDot = dot; nearest = c; }
            }
            gains[nearest] = 1.0f;
            return;
        }

        // Energy normalization: scale so sum(g^2) = 1
        float norm = std::sqrt(bestG[0] * bestG[0] + bestG[1] * bestG[1] + bestG[2] * bestG[2]);
        if (norm > 1e-8f) {
            bestG[0] /= norm; bestG[1] /= norm; bestG[2] /= norm;
        }

        // Map triangle vertex gains to speaker channels
        gains[kVbapTriangles[bestTri].spk[0]] = std::fmax(0.0f, bestG[0]);
        gains[kVbapTriangles[bestTri].spk[1]] = std::fmax(0.0f, bestG[1]);
        gains[kVbapTriangles[bestTri].spk[2]] = std::fmax(0.0f, bestG[2]);
    }

    // Pan with object size/spread: wider objects activate more speakers
    // size: 0.0 = point source, 1.0 = fully diffuse
    void panWithSpread(float x, float y, float z, float size, float* gains) const {
        if (size <= 0.01f) {
            pan(x, y, z, gains);
            return;
        }

        // Point-source gains
        float pointGains[NUM_CHANNELS];
        pan(x, y, z, pointGains);

        // Diffuse gains: equal energy across all speakers (excluding LFE)
        float diffuseGain = 1.0f / std::sqrt(static_cast<float>(NUM_CHANNELS - 1));

        // Crossfade between point and diffuse based on size
        float wet = std::fmin(1.0f, size);
        float dry = 1.0f - wet;
        for (int c = 0; c < NUM_CHANNELS; c++) {
            if (c == LFE) { gains[c] = 0.0f; continue; }
            gains[c] = dry * pointGains[c] + wet * diffuseGain;
        }

        // Re-normalize energy
        float energy = 0.0f;
        for (int c = 0; c < NUM_CHANNELS; c++) {
            if (c == LFE) continue;
            energy += gains[c] * gains[c];
        }
        if (energy > 1e-8f) {
            float scale = 1.0f / std::sqrt(energy);
            for (int c = 0; c < NUM_CHANNELS; c++) {
                if (c == LFE) continue;
                gains[c] *= scale;
            }
        }
    }

private:
    float invMat_[NUM_VBAP_TRIANGLES][3][3]; // Precomputed inverse matrices

    void precomputeInverseMatrices() {
        for (int t = 0; t < NUM_VBAP_TRIANGLES; t++) {
            float L[3][3];
            for (int v = 0; v < 3; v++) {
                kSpeakerPositions[kVbapTriangles[t].spk[v]].toCartesian(
                    L[v][0], L[v][1], L[v][2]);
            }
            // Invert 3x3 matrix L^T (speakers as rows)
            invert3x3(L, invMat_[t]);
        }
    }

    static void invert3x3(const float m[3][3], float inv[3][3]) {
        float det =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);

        if (std::fabs(det) < 1e-12f) {
            memset(inv, 0, 9 * sizeof(float));
            return;
        }
        float invDet = 1.0f / det;

        inv[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) * invDet;
        inv[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * invDet;
        inv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * invDet;
        inv[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * invDet;
        inv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * invDet;
        inv[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * invDet;
        inv[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) * invDet;
        inv[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * invDet;
        inv[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * invDet;
    }
};

} // namespace atmos

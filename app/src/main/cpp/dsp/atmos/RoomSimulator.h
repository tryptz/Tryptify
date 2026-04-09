#pragma once

#include "JocReconstructor.h"
#include "ChannelLayout.h"
#include <cmath>

// ── Room profiles matching Dolby Near/Mid/Far ──────────────────────────
struct RoomProfile {
    float width, depth, height; // Room dimensions in meters
    float t60;                   // Reverb time (seconds)
    float earlyGain;            // Early reflection level
    float lateGain;             // Late reverb level
};

static constexpr RoomProfile kRoomProfiles[] = {
    // NEAR:  Small room, intimate
    { 4.0f, 3.0f, 2.5f, 0.3f, 0.25f, 0.15f },
    // MID:   Medium room, default
    { 8.0f, 6.0f, 3.5f, 0.6f, 0.20f, 0.20f },
    // FAR:   Large room, spacious
    { 16.0f, 12.0f, 5.0f, 1.2f, 0.15f, 0.30f },
};

class RoomSimulator {
public:
    RoomSimulator();
    ~RoomSimulator();

    /**
     * Apply room simulation to the 7.1.4 speaker feeds in-place.
     * Uses Image Source Method (ISM) for 2nd-order early reflections
     * and a Feedback Delay Network (FDN) for late reverb.
     *
     * The ISM generates ~30 image sources for a shoebox room,
     * each with wall-absorption filtering and proper delay.
     * The FDN uses an 8-line network with Hadamard feedback matrix
     * for smooth, colorless late reverberation.
     */
    void process(float speakerFeeds[atmos::NUM_CHANNELS][1536], int numFrames,
                 ObjectMetadata::DistanceMode mode);

private:
    // ── FDN State ──────────────────────────────────────────────────────
    static constexpr int FDN_LINES = 8;
    static constexpr int MAX_FDN_DELAY = 4800; // 100ms at 48kHz
    
    // Mutually prime delay lengths for FDN diffusion
    static constexpr int kFdnDelays[FDN_LINES] = {
        1087, 1283, 1511, 1741, 1933, 2143, 2371, 2593
    };
    
    // 8×8 Hadamard feedback matrix (normalized by 1/sqrt(8))
    static constexpr float kHadamardScale = 0.353553f; // 1/sqrt(8)
    
    float fdnDelayLines_[FDN_LINES][MAX_FDN_DELAY];
    int fdnWritePos_[FDN_LINES];
    
    // Per-line low-pass state for frequency-dependent decay
    float fdnLpState_[FDN_LINES];
    
    // ── ISM State ──────────────────────────────────────────────────────
    static constexpr int MAX_REFLECTIONS = 30;
    static constexpr int MAX_ISM_DELAY = 2400; // 50ms at 48kHz
    
    struct Reflection {
        int delaySamples;
        float gain;
        int targetChannel; // Which 7.1.4 speaker this reflection routes to
    };
    
    Reflection reflections_[MAX_REFLECTIONS];
    int numReflections_;
    
    // ISM delay buffer (shared across reflections)
    float ismDelayBuf_[atmos::NUM_CHANNELS][MAX_ISM_DELAY];
    int ismWritePos_;
    
    void computeReflections(const RoomProfile& room);
    void processFdn(float* inputL, float* inputR, float* outputL, float* outputR,
                    int numFrames, float t60, float gain);
};

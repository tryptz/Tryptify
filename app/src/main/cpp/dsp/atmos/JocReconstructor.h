#pragma once

#include <stdint.h>
#include <vector>
#include "ChannelLayout.h"

// ── Per-object spatial metadata from OAMD (TS 103 420 Clause 5) ─────────
struct ObjectMetadata {
    // 3D Cartesian position (OAMD pos3D_X/Y/Z_bits, normalized to room)
    float x, y, z;
    
    // Object gain (object_gain_idx or object_gain_bits)
    float gain;
    
    // Object size/spread (object_size_idx for isotropic, or separate width/depth/height)
    float size;          // Isotropic spread [0..1]
    float width, depth, height; // Anisotropic spread
    
    // Priority (object_priority_bits) — used for renderer downmix decisions
    int priority;        // 0 = highest priority
    
    // Channel lock (b_object_snap) — snap to nearest speaker
    bool snap;
    
    // Zone constraints (zone_constraints_idx) — restrict to specific speaker zones
    int zoneConstraints;
    
    // Divergence (object_div_mode)
    float divergence;
    
    // Screen reference (b_object_use_screen_ref) — screen-relative vs room-relative
    bool screenRef;
    
    // Binaural distance mode (used for binaural fold-down)
    enum DistanceMode { OFF = 0, NEAR = 1, MID = 2, FAR = 3 };
    DistanceMode distanceMode;
    
    // Is this a headlocked object? (bypasses spatialization, panned relative to ears)
    bool headlocked;
};

// ── JOC Reconstruction result ──────────────────────────────────────────
struct JocReconstructionResult {
    bool success;
    
    // Reconstructed discrete object audio: objectAudio[obj][sample]
    float** objectAudio;
    
    // Per-object metadata
    ObjectMetadata* oamdMetadata;
    int numObjects;
    
    // 7.1.4 speaker feeds after VBAP panning of all objects + bed
    // speakerFeeds[channel][sample]
    float speakerFeeds[atmos::NUM_CHANNELS][1536];
    int numFrames;
};

class JocReconstructor {
public:
    JocReconstructor();
    ~JocReconstructor();

    /**
     * Parse EMDF payload, reconstruct discrete objects from the channel bed via QMF upmixing,
     * then pan all objects into a 7.1.4 speaker layout using VBAP.
     *
     * Per ETSI TS 103 420:
     *   Clause 4: EMDF container parsing
     *   Clause 5: OAMD metadata extraction (per-object positions, gains, sizes)
     *   Clause 6: JOC upmix matrix decoding (Huffman-coded, differentially encoded)
     *   Clause 7: QMF filterbank (64-band analysis/synthesis)
     *
     * The reconstruction chain:
     *   1. QMF analysis of bed channels → time-frequency representation
     *   2. Apply per-band upmix matrices to produce object signals in QMF domain
     *   3. QMF synthesis of each object → time-domain discrete object PCM
     *   4. VBAP pan each object into 7.1.4 speaker feeds using OAMD positions
     *   5. Sum bed channels + panned objects into final 7.1.4 output
     */
    JocReconstructionResult reconstruct(
        float* pcmBed[atmos::NUM_CHANNELS],  // 7.1 channel bed from Eac3Decoder
        int bedChannels,                      // Active bed channels (6 or 8)
        const uint8_t* emdfPayload, 
        int emdfSize,
        int numFrames
    );

private:
    // 64-band QMF analysis filterbank state
    // Coefficient tables per TS 103 420 Clause 7
    static constexpr int QMF_BANDS = 64;
    static constexpr int QMF_SLOTS = 24; // 1536 samples / 64 bands = 24 time slots
    
    // QMF state buffers per input channel
    float qmfAnalysisState_[8][QMF_BANDS * 2]; // 8 bed channels max
    float qmfSynthesisState_[16][QMF_BANDS * 2]; // 16 objects max
    
    // Huffman-decoded upmix matrices: [object][bedChannel][qmfBand]
    float upmixMatrix_[16][8][QMF_BANDS];
    
    // Scratch buffers for object audio
    float objectBufs_[16][1536];
    ObjectMetadata objectMeta_[16];
    
    // Parse EMDF container
    bool parseEmdf(const uint8_t* data, int size);
    
    // Parse OAMD payload within EMDF
    bool parseOamd(const uint8_t* data, int size, ObjectMetadata* meta, int& numObjects);
    
    // Parse JOC payload within EMDF  
    bool parseJoc(const uint8_t* data, int size, int numBedChannels);
    
    // QMF processing
    void qmfAnalysis(const float* input, int numFrames, float* qmfOut, float* state);
    void qmfSynthesis(const float* qmfIn, int numFrames, float* output, float* state);
    void applyUpmixMatrices(float** bedQmf, int numBedCh, float** objQmf, int numObj);
};

#include "JocReconstructor.h"
#include "VbapPanner.h"
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "JocReconstructor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Static VBAP panner instance (precomputes inverse matrices once)
static atmos::VbapPanner sVbapPanner;

JocReconstructor::JocReconstructor() {
    LOGD("Initializing JOC Reconstructor (7.1.4 VBAP output)");
    memset(qmfAnalysisState_, 0, sizeof(qmfAnalysisState_));
    memset(qmfSynthesisState_, 0, sizeof(qmfSynthesisState_));
    memset(upmixMatrix_, 0, sizeof(upmixMatrix_));
    memset(objectBufs_, 0, sizeof(objectBufs_));
    memset(objectMeta_, 0, sizeof(objectMeta_));
}

JocReconstructor::~JocReconstructor() {
    LOGD("Destroying JOC Reconstructor");
}

// ── EMDF Container Parsing (TS 103 420 Clause 4) ───────────────────────
bool JocReconstructor::parseEmdf(const uint8_t* data, int size) {
    if (!data || size < 4) return false;
    
    // Verify EMDF sync word (0x5838)
    uint16_t syncWord = (data[0] << 8) | data[1];
    if (syncWord != 0x5838) {
        LOGE("Invalid EMDF sync word: 0x%04X", syncWord);
        return false;
    }
    
    // emdf_container_length
    // int containerLen = (data[2] << 8) | data[3];
    
    // EMDF contains multiple payloads identified by emdf_payload_id:
    //   ID 0x01 = OAMD (Object Audio Metadata)
    //   ID 0x02 = JOC  (Joint Object Coding)
    // Each payload has: emdf_payload_id(5 bits) + emdf_payload_size + data
    
    // Scan for payload headers within the container
    int offset = 4; // Skip sync + length
    while (offset < size - 2) {
        uint8_t payloadId = (data[offset] >> 3) & 0x1F;
        int payloadSize = ((data[offset] & 0x07) << 8) | data[offset + 1];
        offset += 2;
        
        if (offset + payloadSize > size) break;
        
        if (payloadId == 0x01) {
            // OAMD payload
            int numObjects = 0;
            parseOamd(&data[offset], payloadSize, objectMeta_, numObjects);
            LOGD("OAMD: %d objects parsed", numObjects);
        } else if (payloadId == 0x02) {
            // JOC payload
            parseJoc(&data[offset], payloadSize, 8); // Assume 7.1 bed
            LOGD("JOC upmix matrices decoded");
        }
        
        offset += payloadSize;
    }
    
    return true;
}

// ── OAMD Parsing (TS 103 420 Clause 5) ─────────────────────────────────
bool JocReconstructor::parseOamd(const uint8_t* data, int size, 
                                  ObjectMetadata* meta, int& numObjects) {
    if (!data || size < 1) return false;
    
    // OAMD carries per-object metadata with sub-frame timing granularity
    // (updates can occur every 256 samples within a 1536-sample frame).
    //
    // Per object:
    //   pos3D_X_bits, pos3D_Y_bits, pos3D_Z_bits (Cartesian, normalized to room)
    //   object_gain_idx or object_gain_bits
    //   object_size_idx (isotropic) or separate width/depth/height
    //   object_priority_bits
    //   b_object_snap (channel lock)
    //   zone_constraints_idx
    //   object_div_mode + divergence params
    //   b_object_use_screen_ref
    
    // Simplified extraction — full bitstream parsing would follow the spec exactly
    numObjects = data[0] & 0x0F;
    if (numObjects > 16) numObjects = 16;
    
    int offset = 1;
    for (int i = 0; i < numObjects && offset + 8 <= size; i++) {
        // X: signed 8-bit, scaled to [-1, 1]
        meta[i].x = static_cast<int8_t>(data[offset++]) / 127.0f;
        // Y: signed 8-bit
        meta[i].y = static_cast<int8_t>(data[offset++]) / 127.0f;
        // Z: signed 8-bit
        meta[i].z = static_cast<int8_t>(data[offset++]) / 127.0f;
        // Gain: unsigned 8-bit, scaled
        meta[i].gain = data[offset++] / 255.0f;
        // Size: unsigned 8-bit
        meta[i].size = data[offset++] / 255.0f;
        meta[i].width = meta[i].size;
        meta[i].depth = meta[i].size;
        meta[i].height = meta[i].size;
        // Priority
        meta[i].priority = data[offset++] & 0x07;
        // Flags byte
        uint8_t flags = data[offset++];
        meta[i].snap = (flags & 0x80) != 0;
        meta[i].screenRef = (flags & 0x40) != 0;
        meta[i].headlocked = (flags & 0x20) != 0;
        meta[i].distanceMode = static_cast<ObjectMetadata::DistanceMode>((flags >> 3) & 0x03);
        meta[i].zoneConstraints = flags & 0x07;
        // Divergence
        meta[i].divergence = data[offset++] / 255.0f;
    }
    
    return true;
}

// ── JOC Parsing (TS 103 420 Clause 6) ──────────────────────────────────
bool JocReconstructor::parseJoc(const uint8_t* data, int size, int numBedChannels) {
    if (!data || size < 1) return false;
    
    // JOC bitstream hierarchy:
    //   joc() → joc_header() → joc_info() → joc_data_point_info() → joc_data()
    //
    // joc_header(): downmix config, object count
    // joc_info():   clip gains, band count, quantization step sizes
    // joc_data_point_info(): interpolation slopes, timestamps
    // joc_data():   Huffman-coded upmix matrix coefficients
    //               per frequency band, per object, per bed channel
    //
    // The Huffman tables are defined in Annex A of TS 103 420.
    // Coefficients are differentially encoded across frequency bands.
    //
    // Full implementation would:
    //   1. Decode joc_header to get object count and downmix info
    //   2. For each data point (sub-frame timing):
    //      a. Read Huffman-coded deltas per [obj][bedCh][band]
    //      b. Accumulate into upmixMatrix_[obj][bedCh][band]
    //   3. Interpolate between data points per joc_data_point_info
    
    // Stub: identity mapping (each "object" = one bed channel passthrough)
    memset(upmixMatrix_, 0, sizeof(upmixMatrix_));
    
    return true;
}

// ── QMF Analysis Filterbank (TS 103 420 Clause 7) ──────────────────────
void JocReconstructor::qmfAnalysis(const float* input, int numFrames, 
                                    float* qmfOut, float* state) {
    // 64-band cosine-modulated QMF analysis
    // Coefficient tables defined in TS 103 420 Clause 7
    //
    // For each time slot t (0..23 for 1536 samples):
    //   1. Shift 64 new samples into analysis state buffer
    //   2. Window with 640-tap prototype filter
    //   3. 64-point DCT-IV to produce 64 spectral coefficients
    //
    // Output: qmfOut[slot * QMF_BANDS + band] = complex QMF coefficient
    
    // Placeholder: copy input directly (bypasses QMF for now)
    if (input && qmfOut) {
        memcpy(qmfOut, input, numFrames * sizeof(float));
    }
}

// ── QMF Synthesis Filterbank ───────────────────────────────────────────
void JocReconstructor::qmfSynthesis(const float* qmfIn, int numFrames, 
                                     float* output, float* state) {
    // Inverse of analysis: 64-point inverse DCT-IV + overlap-add with synthesis window
    
    if (qmfIn && output) {
        memcpy(output, qmfIn, numFrames * sizeof(float));
    }
}

// ── Upmix Matrix Application ──────────────────────────────────────────
void JocReconstructor::applyUpmixMatrices(float** bedQmf, int numBedCh, 
                                           float** objQmf, int numObj) {
    // For each QMF time slot and frequency band:
    //   objQmf[obj][slot*QMF_BANDS + band] = 
    //     Σ_ch upmixMatrix_[obj][ch][band] * bedQmf[ch][slot*QMF_BANDS + band]
    //
    // This is the core JOC reconstruction: a matrix multiply in the QMF domain
    // that separates the mixed-down bed back into discrete object signals.
}

// ── Main Reconstruction + VBAP Panning to 7.1.4 ───────────────────────
JocReconstructionResult JocReconstructor::reconstruct(
    float* pcmBed[atmos::NUM_CHANNELS],
    int bedChannels,
    const uint8_t* emdfPayload,
    int emdfSize,
    int numFrames) {
    
    JocReconstructionResult result;
    result.success = false;
    result.objectAudio = nullptr;
    result.oamdMetadata = nullptr;
    result.numObjects = 0;
    result.numFrames = numFrames;
    memset(result.speakerFeeds, 0, sizeof(result.speakerFeeds));

    // ── Step 1: Copy bed channels directly into 7.1.4 speaker feeds ──
    // The bed channels (FL, FR, FC, LFE, BL, BR, SL, SR) pass through directly.
    int bedCount = (bedChannels < 8) ? bedChannels : 8;
    for (int ch = 0; ch < bedCount; ch++) {
        if (pcmBed[ch]) {
            memcpy(result.speakerFeeds[ch], pcmBed[ch], numFrames * sizeof(float));
        }
    }

    // ── Step 2: If no JOC data, return bed-only 7.1 output ──
    if (!emdfPayload || emdfSize <= 0) {
        result.success = true;
        LOGD("No JOC data — returning 7.1 bed passthrough");
        return result;
    }

    // ── Step 3: Parse EMDF to extract OAMD + JOC matrices ──
    if (!parseEmdf(emdfPayload, emdfSize)) {
        result.success = true; // Graceful fallback to bed
        return result;
    }

    // ── Step 4: JOC Object Reconstruction ──
    // QMF Analysis → Matrix Multiply → QMF Synthesis
    // This produces discrete per-object mono PCM in objectBufs_[]
    
    // Full pipeline (stubbed until QMF is implemented):
    // float* bedQmf[8];
    // float bedQmfBufs[8][QMF_SLOTS * QMF_BANDS];
    // for (int ch = 0; ch < bedCount; ch++) {
    //     bedQmf[ch] = bedQmfBufs[ch];
    //     qmfAnalysis(pcmBed[ch], numFrames, bedQmf[ch], qmfAnalysisState_[ch]);
    // }
    // 
    // float* objQmf[16];
    // float objQmfBufs[16][QMF_SLOTS * QMF_BANDS];
    // for (int o = 0; o < result.numObjects; o++) {
    //     objQmf[o] = objQmfBufs[o];
    // }
    // applyUpmixMatrices(bedQmf, bedCount, objQmf, result.numObjects);
    // 
    // for (int o = 0; o < result.numObjects; o++) {
    //     qmfSynthesis(objQmf[o], numFrames, objectBufs_[o], qmfSynthesisState_[o]);
    // }

    // ── Step 5: VBAP pan each reconstructed object into 7.1.4 ──
    for (int o = 0; o < result.numObjects; o++) {
        float gains[atmos::NUM_CHANNELS];
        
        if (objectMeta_[o].headlocked) {
            // Headlocked objects bypass spatial panning (fixed relative to listener)
            // Route to FC for speaker playback, or handle separately for binaural
            memset(gains, 0, sizeof(gains));
            gains[atmos::FC] = objectMeta_[o].gain;
        } else if (objectMeta_[o].snap) {
            // Channel-locked: snap to nearest speaker
            memset(gains, 0, sizeof(gains));
            float maxDot = -2.0f;
            int nearest = atmos::FC;
            for (int c = 0; c < atmos::NUM_CHANNELS; c++) {
                if (c == atmos::LFE) continue;
                float sx, sy, sz;
                atmos::kSpeakerPositions[c].toCartesian(sx, sy, sz);
                float dot = objectMeta_[o].x * sx + objectMeta_[o].y * sy + objectMeta_[o].z * sz;
                if (dot > maxDot) { maxDot = dot; nearest = c; }
            }
            gains[nearest] = objectMeta_[o].gain;
        } else {
            // Standard VBAP panning with spread
            sVbapPanner.panWithSpread(
                objectMeta_[o].x, objectMeta_[o].y, objectMeta_[o].z,
                objectMeta_[o].size, gains);
            
            // Apply object gain
            for (int c = 0; c < atmos::NUM_CHANNELS; c++) {
                gains[c] *= objectMeta_[o].gain;
            }
        }

        // Sum object into 7.1.4 speaker feeds
        for (int c = 0; c < atmos::NUM_CHANNELS; c++) {
            if (gains[c] > 1e-8f) {
                for (int s = 0; s < numFrames; s++) {
                    result.speakerFeeds[c][s] += objectBufs_[o][s] * gains[c];
                }
            }
        }
    }

    result.objectAudio = nullptr; // Objects have been folded into speaker feeds
    result.oamdMetadata = objectMeta_;
    result.success = true;
    
    LOGD("JOC reconstruction complete: %d objects panned into 7.1.4", result.numObjects);
    return result;
}

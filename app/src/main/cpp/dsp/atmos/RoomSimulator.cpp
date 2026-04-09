#include "RoomSimulator.h"
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "RoomSimulator"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

RoomSimulator::RoomSimulator() : numReflections_(0), ismWritePos_(0) {
    LOGD("Initializing ISM + FDN Room Simulator (7.1.4)");
    memset(fdnDelayLines_, 0, sizeof(fdnDelayLines_));
    memset(fdnWritePos_, 0, sizeof(fdnWritePos_));
    memset(fdnLpState_, 0, sizeof(fdnLpState_));
    memset(ismDelayBuf_, 0, sizeof(ismDelayBuf_));
    memset(reflections_, 0, sizeof(reflections_));
    
    // Initialize with MID room profile
    computeReflections(kRoomProfiles[1]);
}

RoomSimulator::~RoomSimulator() {
    LOGD("Destroying Room Simulator");
}

// ── Compute ISM Early Reflections ──────────────────────────────────────
void RoomSimulator::computeReflections(const RoomProfile& room) {
    numReflections_ = 0;
    
    // Image Source Method for a shoebox room:
    // Generate 1st and 2nd order reflections from 6 walls.
    // Each reflection is characterized by:
    //   - Delay (based on image source distance)
    //   - Gain (inverse-square law + wall absorption)
    //   - Direction (maps to nearest 7.1.4 speaker)
    
    float wallAbsorption = 0.7f; // Typical reflection coefficient
    float speedOfSound = 343.0f;
    float sampleRate = 48000.0f;
    
    // Listener at room center
    float lx = room.width / 2.0f;
    float ly = room.depth / 2.0f;
    float lz = room.height / 2.0f;
    
    // 1st order: 6 wall reflections
    struct WallReflection {
        float imgX, imgY, imgZ;  // Image source position
        int channel;              // Nearest 7.1.4 speaker
    };
    
    WallReflection walls[] = {
        // Left wall:   image at (-lx, ly, lz) → routes to FR/SR
        { -lx, ly, lz, atmos::FR },
        // Right wall:  image at (3*lx, ly, lz) → routes to FL/SL
        { room.width + lx, ly, lz, atmos::FL },
        // Front wall:  image at (lx, -ly, lz) → routes to BL+BR
        { lx, -ly, lz, atmos::FC },
        // Back wall:   image at (lx, 2*depth+ly, lz) → routes to FL+FR
        { lx, room.depth + ly, lz, atmos::BL },
        // Floor:       image at (lx, ly, -lz) → stays ground level
        { lx, ly, -lz, atmos::FC },
        // Ceiling:     image at (lx, ly, 2*height+lz) → routes to height channels
        { lx, ly, room.height + lz, atmos::TFL },
    };
    
    for (int w = 0; w < 6 && numReflections_ < MAX_REFLECTIONS; w++) {
        float dx = walls[w].imgX - lx;
        float dy = walls[w].imgY - ly;
        float dz = walls[w].imgZ - lz;
        float dist = std::sqrt(dx * dx + dy * dy + dz * dz);
        
        if (dist < 0.1f) continue; // Skip degenerate
        
        int delaySamples = static_cast<int>(dist / speedOfSound * sampleRate);
        if (delaySamples >= MAX_ISM_DELAY) continue;
        
        float gain = wallAbsorption / (dist + 1.0f);
        
        reflections_[numReflections_].delaySamples = delaySamples;
        reflections_[numReflections_].gain = gain;
        reflections_[numReflections_].targetChannel = walls[w].channel;
        numReflections_++;
    }
    
    // 2nd order: select cross-wall combinations (up to ~24 more)
    // Each 2nd-order reflection bounces off two walls, with squared absorption
    for (int w1 = 0; w1 < 6 && numReflections_ < MAX_REFLECTIONS; w1++) {
        for (int w2 = w1 + 1; w2 < 6 && numReflections_ < MAX_REFLECTIONS; w2++) {
            // Approximate 2nd-order image distance
            float dx1 = walls[w1].imgX - lx, dy1 = walls[w1].imgY - ly, dz1 = walls[w1].imgZ - lz;
            float dx2 = walls[w2].imgX - lx, dy2 = walls[w2].imgY - ly, dz2 = walls[w2].imgZ - lz;
            float dist = std::sqrt((dx1 + dx2) * (dx1 + dx2) + (dy1 + dy2) * (dy1 + dy2) + (dz1 + dz2) * (dz1 + dz2));
            
            if (dist < 0.1f || dist / speedOfSound * sampleRate >= MAX_ISM_DELAY) continue;
            
            int delay = static_cast<int>(dist / speedOfSound * sampleRate);
            float gain = wallAbsorption * wallAbsorption / (dist + 1.0f);
            
            // Assign to a plausible speaker based on combined reflection direction
            int ch = walls[w1].channel; // Simplified assignment
            
            reflections_[numReflections_].delaySamples = delay;
            reflections_[numReflections_].gain = gain;
            reflections_[numReflections_].targetChannel = ch;
            numReflections_++;
        }
    }
    
    LOGD("Computed %d ISM reflections for room %.1fx%.1fx%.1fm", 
         numReflections_, room.width, room.depth, room.height);
}

// ── FDN Late Reverb ────────────────────────────────────────────────────
void RoomSimulator::processFdn(float* inputL, float* inputR,
                                float* outputL, float* outputR,
                                int numFrames, float t60, float gain) {
    // 8-line Feedback Delay Network with Hadamard mixing matrix
    // Frequency-dependent decay via per-line one-pole LPF
    
    // Compute per-sample feedback gain from T60
    // g = 10^(-3 * delayLength / (T60 * sampleRate))
    float fbGains[FDN_LINES];
    for (int i = 0; i < FDN_LINES; i++) {
        fbGains[i] = std::pow(10.0f, -3.0f * kFdnDelays[i] / (t60 * 48000.0f));
    }
    
    // LPF coefficient for frequency-dependent decay (~4kHz cutoff)
    float lpCoeff = 0.3f;
    
    for (int s = 0; s < numFrames; s++) {
        // Feed input to first two delay lines (L → even, R → odd)
        float inL = inputL[s] * gain;
        float inR = inputR[s] * gain;
        
        // Read from delay lines
        float readVals[FDN_LINES];
        for (int i = 0; i < FDN_LINES; i++) {
            int readPos = fdnWritePos_[i] - kFdnDelays[i];
            if (readPos < 0) readPos += MAX_FDN_DELAY;
            readVals[i] = fdnDelayLines_[i][readPos];
        }
        
        // Apply Hadamard feedback matrix (8×8, normalized)
        // H_8 has pattern: all entries are ±1/sqrt(8)
        float mixed[FDN_LINES];
        // Simplified Hadamard butterfly structure
        for (int i = 0; i < FDN_LINES; i++) {
            mixed[i] = 0.0f;
            for (int j = 0; j < FDN_LINES; j++) {
                // Hadamard sign pattern using bit count parity
                int sign = (__builtin_popcount(i & j) & 1) ? -1 : 1;
                mixed[i] += sign * readVals[j] * kHadamardScale;
            }
        }
        
        // Apply feedback gains + LPF + write back
        for (int i = 0; i < FDN_LINES; i++) {
            float val = mixed[i] * fbGains[i];
            // One-pole LPF for frequency-dependent absorption
            fdnLpState_[i] += lpCoeff * (val - fdnLpState_[i]);
            val = fdnLpState_[i];
            
            // Add input injection
            if (i < 4) val += inL;
            else        val += inR;
            
            fdnDelayLines_[i][fdnWritePos_[i]] = val;
            fdnWritePos_[i] = (fdnWritePos_[i] + 1) % MAX_FDN_DELAY;
        }
        
        // Tap output from delay lines (distribute across L/R)
        outputL[s] = (readVals[0] + readVals[2] + readVals[4] + readVals[6]) * 0.25f;
        outputR[s] = (readVals[1] + readVals[3] + readVals[5] + readVals[7]) * 0.25f;
    }
}

// ── Main Room Processing ──────────────────────────────────────────────
void RoomSimulator::process(float speakerFeeds[atmos::NUM_CHANNELS][1536], int numFrames,
                             ObjectMetadata::DistanceMode mode) {
    if (numFrames == 0 || mode == ObjectMetadata::OFF) return;
    
    // Select room profile
    int profileIdx;
    switch (mode) {
        case ObjectMetadata::NEAR: profileIdx = 0; break;
        case ObjectMetadata::FAR:  profileIdx = 2; break;
        default:                   profileIdx = 1; break; // MID
    }
    const RoomProfile& room = kRoomProfiles[profileIdx];
    
    // Recompute reflections if room changed (could cache)
    computeReflections(room);
    
    // ── ISM Early Reflections ──────────────────────────────────────────
    // Write current speaker feeds into ISM delay buffer, then sum reflections
    for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
        for (int s = 0; s < numFrames; s++) {
            ismDelayBuf_[ch][(ismWritePos_ + s) % MAX_ISM_DELAY] = speakerFeeds[ch][s];
        }
    }
    
    for (int r = 0; r < numReflections_; r++) {
        int targetCh = reflections_[r].targetChannel;
        float gain = reflections_[r].gain;
        int delay = reflections_[r].delaySamples;
        
        for (int s = 0; s < numFrames; s++) {
            int readPos = (ismWritePos_ + s - delay);
            if (readPos < 0) readPos += MAX_ISM_DELAY;
            readPos %= MAX_ISM_DELAY;
            
            // Sum reflection into target speaker channel (additive)
            // Source material is FC (center) for simplification; 
            // full impl would route from the reflection's source speaker
            speakerFeeds[targetCh][s] += ismDelayBuf_[atmos::FC][readPos] * gain;
        }
    }
    
    ismWritePos_ = (ismWritePos_ + numFrames) % MAX_ISM_DELAY;
    
    // ── FDN Late Reverb ────────────────────────────────────────────────
    // Create a mono input sum for the FDN from all ear-level channels
    float fdnInput[1536];
    memset(fdnInput, 0, numFrames * sizeof(float));
    for (int ch = 0; ch < 8; ch++) { // Ear-level channels only
        if (ch == atmos::LFE) continue;
        for (int s = 0; s < numFrames; s++) {
            fdnInput[s] += speakerFeeds[ch][s];
        }
    }
    // Normalize
    for (int s = 0; s < numFrames; s++) {
        fdnInput[s] *= 0.143f; // 1/7
    }
    
    float reverbL[1536], reverbR[1536];
    processFdn(fdnInput, fdnInput, reverbL, reverbR, numFrames, room.t60, room.lateGain);
    
    // Distribute late reverb across all non-LFE channels
    // Front channels get more, rear channels get more, height gets less
    static constexpr float reverbDistribution[atmos::NUM_CHANNELS] = {
        0.15f, 0.15f, 0.10f, 0.00f, // FL FR FC LFE
        0.15f, 0.15f, 0.10f, 0.10f, // BL BR SL SR
        0.03f, 0.03f, 0.02f, 0.02f  // TFL TFR TBL TBR
    };
    
    for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
        if (reverbDistribution[ch] < 0.001f) continue;
        float* reverb = (ch % 2 == 0) ? reverbL : reverbR;
        for (int s = 0; s < numFrames; s++) {
            speakerFeeds[ch][s] += reverb[s] * reverbDistribution[ch];
        }
    }
}

#include "BinauralRenderer.h"
#include <android/log.h>
#include <cstring>
#include <cmath>

// SOFA reader (available after CMake ExternalProject builds libmysofa)
// #include <mysofa.h>

#define LOG_TAG "BinauralRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

BinauralRenderer::BinauralRenderer(AAssetManager* assetManager) 
    : outputMode_(AtmosOutputMode::BINAURAL), hrtfLoaded_(false) {
    LOGD("Initializing Binaural Renderer (7.1.4 capable)");
    memset(hrirLeft_, 0, sizeof(hrirLeft_));
    memset(hrirRight_, 0, sizeof(hrirRight_));
    memset(hrtfSpecLeft_, 0, sizeof(hrtfSpecLeft_));
    memset(hrtfSpecRight_, 0, sizeof(hrtfSpecRight_));
    memset(overlapBuf_, 0, sizeof(overlapBuf_));
    memset(binauralL_, 0, sizeof(binauralL_));
    memset(binauralR_, 0, sizeof(binauralR_));
    
    loadSofaDataset(assetManager);
}

BinauralRenderer::~BinauralRenderer() {
    LOGD("Destroying Binaural Renderer");
}

int BinauralRenderer::getOutputChannelCount() const {
    switch (outputMode_) {
        case AtmosOutputMode::SPEAKERS_7_1_4: return atmos::NUM_CHANNELS; // 12
        case AtmosOutputMode::BINAURAL:       return 2;
        case AtmosOutputMode::STEREO_DOWNMIX: return 2;
    }
    return 2;
}

// ── SOFA Dataset Loading ───────────────────────────────────────────────
void BinauralRenderer::loadSofaDataset(AAssetManager* assetManager) {
    if (!assetManager) {
        LOGE("No AssetManager provided for HRTF loading!");
        return;
    }

    // Load the Bernschütz KU100 L2702 SOFA file from app assets.
    // Path: assets/hrtf/ku100.sofa
    AAsset* asset = AAssetManager_open(assetManager, "hrtf/ku100.sofa", AASSET_MODE_BUFFER);
    if (asset) {
        LOGD("Successfully opened HRTF asset: ku100.sofa");
        off_t length = AAsset_getLength(asset);
        const void* buffer = AAsset_getBuffer(asset);
        
        // TODO: Pass buffer to libmysofa when re-enabled
        // struct MYSOFA_EASY* hrtf = mysofa_open_data(buffer, length, 48000, ...);
        
        AAsset_close(asset);
    } else {
        LOGE("Failed to open HRTF asset: hrtf/ku100.sofa. Falling back to synthetic.");
    }
    //
    // Using libmysofa:
    //   struct MYSOFA_EASY* hrtf = mysofa_open("ku100.sofa", 48000, &filterLength, &err);
    //   
    //   For each speaker in our 7.1.4 layout:
    //     Convert speaker position to Cartesian
    //     mysofa_getfilter_float(hrtf, x, y, z, leftIR, rightIR, &leftDelay, &rightDelay);
    //     Copy leftIR → hrirLeft_[channel], rightIR → hrirRight_[channel]
    //
    // For now, generate simple synthetic HRIRs (dirac + ITD) as placeholders.
    
    for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
        if (ch == atmos::LFE) continue; // LFE has no spatial position
        
        float cx, cy, cz;
        atmos::kSpeakerPositions[ch].toCartesian(cx, cy, cz);
        
        // Synthetic ITD based on azimuth (interaural axis = x)
        // ITD ≈ (d/c) * sin(azimuth), where d ≈ 0.17m (head radius), c = 343 m/s
        // At 48kHz, max ITD ≈ 24 samples
        float itdSamples = cx * 24.0f; // Positive = left ear leads
        int itdL = static_cast<int>(std::fmax(0.0f, -itdSamples));
        int itdR = static_cast<int>(std::fmax(0.0f,  itdSamples));
        
        // Simple dirac at ITD offset with head-shadow ILD
        float ildL = (cx >= 0) ? 1.0f : 0.7f; // Ipsilateral ear louder
        float ildR = (cx <= 0) ? 1.0f : 0.7f;
        
        if (itdL < HRIR_LENGTH) hrirLeft_[ch][itdL] = ildL;
        if (itdR < HRIR_LENGTH) hrirRight_[ch][itdR] = ildR;
    }
    
    precomputeHrtfSpectra();
    hrtfLoaded_ = true;
    LOGD("HRTF loaded (synthetic placeholder — replace with KU100 SOFA)");
}

// ── Pre-compute frequency-domain HRTF ──────────────────────────────────
void BinauralRenderer::precomputeHrtfSpectra() {
    // For each speaker channel, compute the 256-pt FFT of the zero-padded 128-tap HRIR.
    // This is done once at init time, then used for all subsequent UPOLS convolutions.
    //
    // hrtfSpecLeft_[ch] = FFT(hrirLeft_[ch], 256)
    // hrtfSpecRight_[ch] = FFT(hrirRight_[ch], 256)
    //
    // TODO: Replace with actual FFT (pffft, KissFFT, or ARM Ne10)
    // For now, copy time-domain IRs as placeholder
    
    for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
        // Zero-pad HRIR to FFT_SIZE and transform
        // Placeholder: store time-domain for now
        memset(hrtfSpecLeft_[ch], 0, FFT_BINS * 2 * sizeof(float));
        memset(hrtfSpecRight_[ch], 0, FFT_BINS * 2 * sizeof(float));
        for (int i = 0; i < HRIR_LENGTH; i++) {
            hrtfSpecLeft_[ch][i * 2] = hrirLeft_[ch][i];
            hrtfSpecRight_[ch][i * 2] = hrirRight_[ch][i];
        }
    }
}

// ── UPOLS Block Convolution ────────────────────────────────────────────
void BinauralRenderer::convolveBlock(int channel, const float* input, 
                                      float* outL, float* outR) {
    // Uniformly Partitioned Overlap-Save for 128-tap HRIR:
    //   1. Construct 256-sample input: [overlap(128) | new(128)]
    //   2. FFT forward (256-pt)
    //   3. Complex multiply with pre-computed HRTF spectra (left and right ears)
    //   4. IFFT (256-pt)
    //   5. Take last 128 samples as valid output
    //   6. Save new input as next overlap
    //
    // TODO: Implement with ARM NEON FFT (Ne10 or pffft)
    // Placeholder: time-domain convolution (correct but slower)
    
    for (int n = 0; n < HRIR_LENGTH; n++) {
        float sumL = 0.0f, sumR = 0.0f;
        for (int k = 0; k <= n; k++) {
            float sample = input[n - k];
            sumL += sample * hrirLeft_[channel][k];
            sumR += sample * hrirRight_[channel][k];
        }
        // Add contribution from overlap buffer
        for (int k = n + 1; k < HRIR_LENGTH; k++) {
            float sample = overlapBuf_[channel][HRIR_LENGTH - (k - n)];
            sumL += sample * hrirLeft_[channel][k];
            sumR += sample * hrirRight_[channel][k];
        }
        outL[n] = sumL;
        outR[n] = sumR;
    }
    
    // Save current input as overlap for next block
    memcpy(overlapBuf_[channel], input, HRIR_LENGTH * sizeof(float));
}

// ── 7.1.4 Speaker Output ──────────────────────────────────────────────
int BinauralRenderer::renderSpeakers(const float feeds[atmos::NUM_CHANNELS][1536], 
                                      int numFrames, float* out) {
    // Interleave 12 channels: [FL FR FC LFE BL BR SL SR TFL TFR TBL TBR] per sample
    for (int s = 0; s < numFrames; s++) {
        for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
            out[s * atmos::NUM_CHANNELS + ch] = feeds[ch][s];
        }
    }
    return numFrames * atmos::NUM_CHANNELS * sizeof(float);
}

// ── Binaural HRTF Output ──────────────────────────────────────────────
int BinauralRenderer::renderBinaural(const float feeds[atmos::NUM_CHANNELS][1536], 
                                      int numFrames, float* out) {
    memset(binauralL_, 0, numFrames * sizeof(float));
    memset(binauralR_, 0, numFrames * sizeof(float));
    
    // Process in 128-sample blocks (matching HRIR length)
    int blocksNeeded = (numFrames + HRIR_LENGTH - 1) / HRIR_LENGTH;
    
    for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
        if (ch == atmos::LFE) {
            // LFE: mix equally to both ears with low-pass shelf
            for (int s = 0; s < numFrames; s++) {
                float lfe = feeds[ch][s] * 0.5f;
                binauralL_[s] += lfe;
                binauralR_[s] += lfe;
            }
            continue;
        }
        
        // Convolve each 128-sample block with HRTF
        for (int b = 0; b < blocksNeeded; b++) {
            int offset = b * HRIR_LENGTH;
            int blockSize = (offset + HRIR_LENGTH <= numFrames) ? HRIR_LENGTH : (numFrames - offset);
            
            float blockL[HRIR_LENGTH] = {0};
            float blockR[HRIR_LENGTH] = {0};
            
            convolveBlock(ch, &feeds[ch][offset], blockL, blockR);
            
            for (int s = 0; s < blockSize; s++) {
                binauralL_[offset + s] += blockL[s];
                binauralR_[offset + s] += blockR[s];
            }
        }
    }
    
    // Interleave L/R
    for (int s = 0; s < numFrames; s++) {
        out[s * 2]     = binauralL_[s];
        out[s * 2 + 1] = binauralR_[s];
    }
    
    return numFrames * 2 * sizeof(float);
}

// ── Stereo Downmix Output ─────────────────────────────────────────────
int BinauralRenderer::renderStereoDownmix(const float feeds[atmos::NUM_CHANNELS][1536], 
                                           int numFrames, float* out) {
    // ITU-R BS.775 downmix using kDownmixStereo coefficients
    for (int s = 0; s < numFrames; s++) {
        float left = 0.0f, right = 0.0f;
        for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
            left  += feeds[ch][s] * atmos::kDownmixStereo[ch][0];
            right += feeds[ch][s] * atmos::kDownmixStereo[ch][1];
        }
        out[s * 2]     = left;
        out[s * 2 + 1] = right;
    }
    return numFrames * 2 * sizeof(float);
}

// ── Main Render Entry Point ───────────────────────────────────────────
int BinauralRenderer::render(const float speakerFeeds[atmos::NUM_CHANNELS][1536],
                              int numFrames, float* outputPcm) {
    if (!outputPcm || numFrames == 0) return 0;
    
    switch (outputMode_) {
        case AtmosOutputMode::SPEAKERS_7_1_4:
            return renderSpeakers(speakerFeeds, numFrames, outputPcm);
        case AtmosOutputMode::BINAURAL:
            return renderBinaural(speakerFeeds, numFrames, outputPcm);
        case AtmosOutputMode::STEREO_DOWNMIX:
            return renderStereoDownmix(speakerFeeds, numFrames, outputPcm);
    }
    return 0;
}

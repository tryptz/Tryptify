#include "spatial_renderer.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define LOG_TAG "SpatialRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

SpatialRenderer::SpatialRenderer(int sampleRate, int maxBlockSize)
    : sampleRate_(sampleRate), maxBlockSize_(maxBlockSize) {
    memset(channelBufs_, 0, sizeof(channelBufs_));
    memset(speakerFeeds_, 0, sizeof(speakerFeeds_));
    LOGD("SpatialRenderer created: sr=%d, maxBlock=%d", sampleRate, maxBlockSize);
}

SpatialRenderer::~SpatialRenderer() {
    LOGD("SpatialRenderer destroyed");
}

// ── Deinterleave + channel mapping ─────────────────────────────────────
void SpatialRenderer::deinterleaveAndMap(const float* input, int inputChannels, int numFrames) {
    // Zero all channels first
    for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
        memset(speakerFeeds_[ch], 0, numFrames * sizeof(float));
    }

    // Map input channels to our 7.1.4 layout based on channel count.
    // Channel ordering follows the Dolby/SMPTE standard used by
    // Apple Music, TIDAL, and Android's MediaCodec E-AC-3 decoder.
    
    switch (inputChannels) {
        case 2: {
            // Stereo: L R
            for (int s = 0; s < numFrames; s++) {
                speakerFeeds_[atmos::FL][s] = input[s * 2];
                speakerFeeds_[atmos::FR][s] = input[s * 2 + 1];
            }
            break;
        }
        case 6: {
            // 5.1: L R C LFE Ls Rs
            for (int s = 0; s < numFrames; s++) {
                int base = s * 6;
                speakerFeeds_[atmos::FL][s]  = input[base];
                speakerFeeds_[atmos::FR][s]  = input[base + 1];
                speakerFeeds_[atmos::FC][s]  = input[base + 2];
                speakerFeeds_[atmos::LFE][s] = input[base + 3];
                speakerFeeds_[atmos::SL][s]  = input[base + 4];
                speakerFeeds_[atmos::SR][s]  = input[base + 5];
            }
            break;
        }
        case 8: {
            // 7.1: L R C LFE BL BR SL SR
            for (int s = 0; s < numFrames; s++) {
                int base = s * 8;
                speakerFeeds_[atmos::FL][s]  = input[base];
                speakerFeeds_[atmos::FR][s]  = input[base + 1];
                speakerFeeds_[atmos::FC][s]  = input[base + 2];
                speakerFeeds_[atmos::LFE][s] = input[base + 3];
                speakerFeeds_[atmos::BL][s]  = input[base + 4];
                speakerFeeds_[atmos::BR][s]  = input[base + 5];
                speakerFeeds_[atmos::SL][s]  = input[base + 6];
                speakerFeeds_[atmos::SR][s]  = input[base + 7];
            }
            break;
        }
        case 10: {
            // 7.1.2: L R C LFE BL BR SL SR TFL TFR
            for (int s = 0; s < numFrames; s++) {
                int base = s * 10;
                speakerFeeds_[atmos::FL][s]  = input[base];
                speakerFeeds_[atmos::FR][s]  = input[base + 1];
                speakerFeeds_[atmos::FC][s]  = input[base + 2];
                speakerFeeds_[atmos::LFE][s] = input[base + 3];
                speakerFeeds_[atmos::BL][s]  = input[base + 4];
                speakerFeeds_[atmos::BR][s]  = input[base + 5];
                speakerFeeds_[atmos::SL][s]  = input[base + 6];
                speakerFeeds_[atmos::SR][s]  = input[base + 7];
                speakerFeeds_[atmos::TFL][s] = input[base + 8];
                speakerFeeds_[atmos::TFR][s] = input[base + 9];
            }
            break;
        }
        case 12: {
            // 7.1.4: L R C LFE BL BR SL SR TFL TFR TBL TBR
            for (int s = 0; s < numFrames; s++) {
                int base = s * 12;
                speakerFeeds_[atmos::FL][s]  = input[base];
                speakerFeeds_[atmos::FR][s]  = input[base + 1];
                speakerFeeds_[atmos::FC][s]  = input[base + 2];
                speakerFeeds_[atmos::LFE][s] = input[base + 3];
                speakerFeeds_[atmos::BL][s]  = input[base + 4];
                speakerFeeds_[atmos::BR][s]  = input[base + 5];
                speakerFeeds_[atmos::SL][s]  = input[base + 6];
                speakerFeeds_[atmos::SR][s]  = input[base + 7];
                speakerFeeds_[atmos::TFL][s] = input[base + 8];
                speakerFeeds_[atmos::TFR][s] = input[base + 9];
                speakerFeeds_[atmos::TBL][s] = input[base + 10];
                speakerFeeds_[atmos::TBR][s] = input[base + 11];
            }
            break;
        }
        default: {
            // Unsupported channel count — take first two as stereo
            LOGD("Unsupported channel count %d, falling back to stereo", inputChannels);
            int usable = std::min(inputChannels, 2);
            for (int s = 0; s < numFrames; s++) {
                speakerFeeds_[atmos::FL][s] = input[s * inputChannels];
                if (usable > 1) {
                    speakerFeeds_[atmos::FR][s] = input[s * inputChannels + 1];
                } else {
                    speakerFeeds_[atmos::FR][s] = speakerFeeds_[atmos::FL][s];
                }
            }
            break;
        }
    }
}

// ── Main processing entry ──────────────────────────────────────────────
void SpatialRenderer::process(const float* interleavedInput, int inputChannels,
                               float* outputL, float* outputR, int numFrames) {
    if (!enabled_.load(std::memory_order_relaxed) || inputChannels <= 2) {
        // Bypass: passthrough stereo (or mono duplicated)
        if (inputChannels == 1) {
            for (int s = 0; s < numFrames; s++) {
                outputL[s] = interleavedInput[s];
                outputR[s] = interleavedInput[s];
            }
        } else {
            for (int s = 0; s < numFrames; s++) {
                outputL[s] = interleavedInput[s * inputChannels];
                outputR[s] = interleavedInput[s * inputChannels + 1];
            }
        }
        return;
    }

    // ── Step 1: Deinterleave multichannel input into 7.1.4 speaker feeds ──
    deinterleaveAndMap(interleavedInput, inputChannels, numFrames);

    // ── Step 2: Room simulation (ISM reflections + FDN reverb) ──────────
    int rm = roomMode_.load(std::memory_order_relaxed);
    if (rm > 0) {
        auto distMode = static_cast<ObjectMetadata::DistanceMode>(rm);
        // Process in chunks of 1536 to match the room simulator's buffer size
        int processed = 0;
        while (processed < numFrames) {
            int chunk = std::min(1536, numFrames - processed);
            // Build temp feed for this chunk
            float tempFeeds[atmos::NUM_CHANNELS][1536];
            for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
                memcpy(tempFeeds[ch], &speakerFeeds_[ch][processed], chunk * sizeof(float));
                if (chunk < 1536) memset(&tempFeeds[ch][chunk], 0, (1536 - chunk) * sizeof(float));
            }
            roomSimulator_.process(tempFeeds, chunk, distMode);
            for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
                memcpy(&speakerFeeds_[ch][processed], tempFeeds[ch], chunk * sizeof(float));
            }
            processed += chunk;
        }
    }

    // ── Step 3: Binaural render from 7.1.4 speaker feeds → stereo ──────
    // Process in chunks of 1536
    int processed = 0;
    while (processed < numFrames) {
        int chunk = std::min(1536, numFrames - processed);
        
        float tempFeeds[atmos::NUM_CHANNELS][1536];
        for (int ch = 0; ch < atmos::NUM_CHANNELS; ch++) {
            memcpy(tempFeeds[ch], &speakerFeeds_[ch][processed], chunk * sizeof(float));
            if (chunk < 1536) memset(&tempFeeds[ch][chunk], 0, (1536 - chunk) * sizeof(float));
        }
        
        // Render to interleaved stereo scratch
        float stereoOut[1536 * 2];
        binauralRenderer_.render(tempFeeds, chunk, stereoOut);
        
        // Deinterleave into output L/R
        for (int s = 0; s < chunk; s++) {
            outputL[processed + s] = stereoOut[s * 2];
            outputR[processed + s] = stereoOut[s * 2 + 1];
        }
        
        processed += chunk;
    }
}

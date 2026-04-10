#pragma once

#include "JocReconstructor.h"
#include "ChannelLayout.h"
#include <android/asset_manager.h>
#include <stdint.h>

// ── Output mode for the renderer ───────────────────────────────────────
enum class AtmosOutputMode {
    SPEAKERS_7_1_4,  // 12-channel discrete speaker output
    BINAURAL,        // Stereo binaural (HRTF convolution of 7.1.4 feeds)
    STEREO_DOWNMIX   // ITU-R BS.775 stereo downmix
};

class BinauralRenderer {
public:
    BinauralRenderer(AAssetManager* assetManager);
    ~BinauralRenderer();

    void setOutputMode(AtmosOutputMode mode) { outputMode_ = mode; }
    AtmosOutputMode getOutputMode() const { return outputMode_; }

    /**
     * Render the 7.1.4 speaker feeds to the selected output format.
     *
     * SPEAKERS_7_1_4: Passes through the 12-channel feeds directly.
     *   outputPcm layout: interleaved [FL FR FC LFE BL BR SL SR TFL TFR TBL TBR] × numFrames
     *   Returns: numFrames * 12 * sizeof(float)
     *
     * BINAURAL: Convolves each of the 12 speaker feeds with its corresponding
     *   HRTF pair from the Bernschütz KU100 dataset via UPOLS, then sums to stereo.
     *   outputPcm layout: interleaved [L R] × numFrames
     *   Returns: numFrames * 2 * sizeof(float)
     *
     * STEREO_DOWNMIX: Applies ITU-R BS.775 downmix coefficients.
     *   outputPcm layout: interleaved [L R] × numFrames
     *   Returns: numFrames * 2 * sizeof(float)
     */
    int render(
        const float speakerFeeds[atmos::NUM_CHANNELS][1536],
        int numFrames,
        float* outputPcm
    );

    /// Get the number of output channels for the current mode
    int getOutputChannelCount() const;

private:
    AtmosOutputMode outputMode_;
    bool hrtfLoaded_;

    // ── HRTF data for binaural mode ────────────────────────────────────
    // One HRIR pair (left ear, right ear) per speaker position in the 7.1.4 layout.
    // 128-tap FIR filters from the Bernschütz KU100 L2702 SOFA dataset.
    static constexpr int HRIR_LENGTH = 128;
    float hrirLeft_[atmos::NUM_CHANNELS][HRIR_LENGTH];   // Left-ear impulse responses
    float hrirRight_[atmos::NUM_CHANNELS][HRIR_LENGTH];  // Right-ear impulse responses

    // ── UPOLS convolution state per speaker channel ────────────────────
    // Uniformly Partitioned Overlap-Save with 256-pt FFT (128 new + 128 old)
    static constexpr int FFT_SIZE = 256;
    static constexpr int FFT_BINS = FFT_SIZE / 2 + 1; // 129 complex bins

    // Pre-computed frequency-domain HRTF spectra
    float hrtfSpecLeft_[atmos::NUM_CHANNELS][FFT_BINS * 2];  // complex: [re, im] pairs
    float hrtfSpecRight_[atmos::NUM_CHANNELS][FFT_BINS * 2];

    // Per-channel overlap buffers (previous 128 samples for overlap-save)
    float overlapBuf_[atmos::NUM_CHANNELS][HRIR_LENGTH];

    // Scratch buffers for FFT processing
    float fftInputBuf_[FFT_SIZE];
    float fftOutputBufL_[FFT_SIZE];
    float fftOutputBufR_[FFT_SIZE];

    // Binaural output accumulation
    float binauralL_[1536];
    float binauralR_[1536];

    void loadSofaDataset(AAssetManager* assetManager);
    void precomputeHrtfSpectra();

    // Process one 128-sample block for one speaker channel
    void convolveBlock(int channel, const float* input, float* outL, float* outR);

    // Render subroutines
    int renderSpeakers(const float feeds[atmos::NUM_CHANNELS][1536], int numFrames, float* out);
    int renderBinaural(const float feeds[atmos::NUM_CHANNELS][1536], int numFrames, float* out);
    int renderStereoDownmix(const float feeds[atmos::NUM_CHANNELS][1536], int numFrames, float* out);
};

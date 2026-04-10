#pragma once

#include "atmos/ChannelLayout.h"
#include "atmos/BinauralRenderer.h"
#include "atmos/RoomSimulator.h"
#include "atmos/VbapPanner.h"
#include <android/asset_manager.h>
#include <atomic>
#include <cstring>

// ── Spatial Renderer ───────────────────────────────────────────────────
// Accepts pre-decoded multichannel PCM (2ch–12ch) from any source
// (Apple Music Atmos via MediaCodec, local FLAC 7.1.4, TIDAL, etc.)
// and renders it to binaural stereo via HRTF convolution of the 7.1.4
// speaker layout.
//
// This is the "full channel input first" path — no JOC/EMDF parsing
// required. The multichannel audio arrives already decoded.

class SpatialRenderer {
public:
    SpatialRenderer(AAssetManager* assetManager, int sampleRate, int maxBlockSize);
    ~SpatialRenderer();

    // ── Main processing ────────────────────────────────────────────────
    // Takes interleaved multichannel input, outputs interleaved stereo.
    // inputChannels: number of channels in the input (2, 6, 8, 10, 12)
    // Input channel order follows the standard:
    //   2ch: L R
    //   6ch: L R C LFE Ls Rs              (5.1)
    //   8ch: L R C LFE Ls Rs Lrs Rrs      (7.1)
    //  12ch: L R C LFE Ls Rs Lrs Rrs TFL TFR TBL TBR  (7.1.4)
    void process(const float* interleavedInput, int inputChannels,
                 float* outputL, float* outputR, int numFrames);

    // ── Configuration ──────────────────────────────────────────────────
    void setEnabled(bool enabled) { enabled_.store(enabled, std::memory_order_relaxed); }
    bool isEnabled() const { return enabled_.load(std::memory_order_relaxed); }

    void setOutputMode(AtmosOutputMode mode) { binauralRenderer_.setOutputMode(mode); }
    AtmosOutputMode getOutputMode() const { return binauralRenderer_.getOutputMode(); }

    void setRoomMode(int mode) { roomMode_.store(mode, std::memory_order_relaxed); }

private:
    int sampleRate_;
    int maxBlockSize_;
    std::atomic<bool> enabled_{true};
    std::atomic<int> roomMode_{2}; // 0=OFF, 1=NEAR, 2=MID, 3=FAR

    BinauralRenderer binauralRenderer_;
    RoomSimulator roomSimulator_;

    // Deinterleaved channel buffers for up to 7.1.4
    float channelBufs_[atmos::NUM_CHANNELS][4096];

    // 7.1.4 speaker feed assembly buffer
    float speakerFeeds_[atmos::NUM_CHANNELS][4096];

    // Remap input channel indices to our 7.1.4 layout
    void deinterleaveAndMap(const float* input, int inputChannels, int numFrames);
};

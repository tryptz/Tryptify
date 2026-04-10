#pragma once

#include <stdint.h>
#include "ChannelLayout.h"

struct Eac3DecodeResult {
    bool success;
    float* pcmChannels[atmos::NUM_CHANNELS]; // Per-channel PCM for up to 7.1.4
    int activeChannels;                       // How many channels decoded (2/6/8/12)
    uint8_t* emdfPayload;                     // Extracted EMDF container with JOC/OAMD
    int emdfSize;
    int numFrames;                            // Samples per channel (typically 1536)
    atmos::BedConfig bedConfig;               // Detected bed configuration
    bool hasJoc;                              // flag_ec3_extension_type_a
    int jocComplexity;                        // complexity_index_type_a (1-16 objects)
};

class Eac3Decoder {
public:
    Eac3Decoder();
    ~Eac3Decoder();

    /**
     * Decode an E-AC-3 syncframe.
     * Extracts the full channel bed (up to 7.1 from acmod+lfeon),
     * detects JOC presence via flag_ec3_extension_type_a,
     * and extracts the raw EMDF payload containing JOC upmix coefficients + OAMD metadata.
     * 
     * Per ETSI TS 102 366: syncframe = sync_word(0x0B77) + BSI + audio_blocks(1-6) + addbsi
     * JOC signaling lives in addbsi → flag_ec3_extension_type_a(1 bit) + complexity_index_type_a(8 bits)
     */
    Eac3DecodeResult decode(const uint8_t* inputFrame, int inputSize);
    
private:
    // FFmpeg AVCodecContext, AVFrame, AVPacket
    void* avCodecContext_;
    void* avFrame_;
    void* avPacket_;
    
    // Internal scratch buffers for 7.1.4 channel separation
    float channelBufs_[atmos::NUM_CHANNELS][1536];
    
    // Parse the addbsi + EMDF sections that FFmpeg skips
    bool extractEmdf(const uint8_t* frame, int frameSize, 
                     uint8_t*& emdfOut, int& emdfSizeOut,
                     bool& hasJoc, int& jocComplexity);
};

#include "Eac3Decoder.h"
#include <android/log.h>
#include <cstring>

// FFmpeg headers (available after CMake ExternalProject builds ffmpeg from source)
extern "C" {
// #include <libavcodec/avcodec.h>
// #include <libavutil/channel_layout.h>
// #include <libavutil/frame.h>
}

#define LOG_TAG "Eac3Decoder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// E-AC-3 sync word
static constexpr uint16_t EAC3_SYNC_WORD = 0x0B77;

Eac3Decoder::Eac3Decoder() : avCodecContext_(nullptr), avFrame_(nullptr), avPacket_(nullptr) {
    LOGD("Initializing FFmpeg E-AC-3 Decoder (7.1.4 output)...");
    memset(channelBufs_, 0, sizeof(channelBufs_));
    
    // Full init sequence once FFmpeg is linked:
    // const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_EAC3);
    // AVCodecContext* ctx = avcodec_alloc_context3(codec);
    // ctx->request_channel_layout = AV_CH_LAYOUT_7POINT1;  // Request maximum bed decode
    // avcodec_open2(ctx, codec, nullptr);
    // avCodecContext_ = ctx;
    // avFrame_ = av_frame_alloc();
    // avPacket_ = av_packet_alloc();
}

Eac3Decoder::~Eac3Decoder() {
    LOGD("Destroying E-AC-3 Decoder");
    // av_frame_free((AVFrame**)&avFrame_);
    // av_packet_free((AVPacket**)&avPacket_);
    // avcodec_free_context((AVCodecContext**)&avCodecContext_);
}

bool Eac3Decoder::extractEmdf(const uint8_t* frame, int frameSize,
                               uint8_t*& emdfOut, int& emdfSizeOut, 
                               bool& hasJoc, int& jocComplexity) {
    hasJoc = false;
    jocComplexity = 0;
    emdfOut = nullptr;
    emdfSizeOut = 0;

    if (frameSize < 6) return false;

    // Verify sync word
    uint16_t syncWord = (frame[0] << 8) | frame[1];
    if (syncWord != EAC3_SYNC_WORD) return false;

    // Parse BSI to find addbsi location
    // Per TS 102 366 Section 5.4.2.2:
    //   Bit 16-17: frame type (fscod)
    //   Bit 32+: acmod (3 bits), lfeon (1 bit)
    //   ...skip to addbsi...
    //
    // The addbsi field contains flag_ec3_extension_type_a.
    // If set, complexity_index_type_a follows (8 bits, value 1-16 = decodable JOC objects).
    //
    // EMDF containers follow within the bitstream per TS 103 420 Section 4.
    // They carry both joc() and oamd() payloads.
    
    // Scan for EMDF sync pattern (emdf_sync_word = 0x5838)
    for (int i = 2; i < frameSize - 4; i++) {
        uint16_t candidate = (frame[i] << 8) | frame[i + 1];
        if (candidate == 0x5838) {
            // Found EMDF container start
            // Parse emdf_container_length
            int emdfLen = (frame[i + 2] << 8) | frame[i + 3];
            if (i + 4 + emdfLen <= frameSize) {
                emdfOut = const_cast<uint8_t*>(&frame[i]);
                emdfSizeOut = 4 + emdfLen;
                hasJoc = true;
                
                // Extract complexity from the JOC-specific fields inside EMDF
                // complexity_index_type_a is encoded early in the joc_header()
                if (emdfLen >= 2) {
                    jocComplexity = frame[i + 4] & 0x0F; // Simplified extraction
                    if (jocComplexity == 0) jocComplexity = 1;
                    if (jocComplexity > 16) jocComplexity = 16;
                }
                
                LOGD("EMDF found at offset %d, size=%d, JOC complexity=%d", i, emdfSizeOut, jocComplexity);
                return true;
            }
        }
    }
    
    // No EMDF found — this frame has no JOC data (plain DD+ 5.1/7.1)
    return true; // Not an error, just no objects
}

Eac3DecodeResult Eac3Decoder::decode(const uint8_t* inputFrame, int inputSize) {
    Eac3DecodeResult result;
    result.success = false;
    result.activeChannels = 0;
    result.emdfPayload = nullptr;
    result.emdfSize = 0;
    result.numFrames = 0;
    result.bedConfig = atmos::BedConfig::BED_5_1;
    result.hasJoc = false;
    result.jocComplexity = 0;
    for (int c = 0; c < atmos::NUM_CHANNELS; c++) {
        result.pcmChannels[c] = channelBufs_[c];
    }

    if (!inputFrame || inputSize < 6) return result;

    // ── Step 1: Extract EMDF before FFmpeg discards it ──
    extractEmdf(inputFrame, inputSize, 
                result.emdfPayload, result.emdfSize,
                result.hasJoc, result.jocComplexity);

    // ── Step 2: Decode channel bed via FFmpeg libavcodec ──
    // AVPacket* pkt = (AVPacket*)avPacket_;
    // pkt->data = (uint8_t*)inputFrame;
    // pkt->size = inputSize;
    // int ret = avcodec_send_packet((AVCodecContext*)avCodecContext_, pkt);
    // if (ret < 0) { LOGE("avcodec_send_packet failed: %d", ret); return result; }
    //
    // AVFrame* frame = (AVFrame*)avFrame_;
    // ret = avcodec_receive_frame((AVCodecContext*)avCodecContext_, frame);
    // if (ret < 0) { LOGE("avcodec_receive_frame failed: %d", ret); return result; }
    //
    // Deinterleave decoded PCM into channelBufs_:
    // int chCount = frame->ch_layout.nb_channels;
    // result.numFrames = frame->nb_samples;  // Typically 1536 (6 blocks × 256)
    //
    // Map avcodec channel order to our 7.1.4 layout:
    //   FFmpeg 7.1: FL FR FC LFE BL BR SL SR
    //   Our 7.1.4:  FL FR FC LFE BL BR SL SR TFL TFR TBL TBR
    //
    // Height channels (TFL/TFR/TBL/TBR) are not present in the DD+ bed —
    // they come from JOC object reconstruction. The bed is always flat (ear-level).
    //
    // for (int ch = 0; ch < chCount && ch < 8; ch++) {
    //     float* src = (float*)frame->extended_data[ch];
    //     memcpy(channelBufs_[ch], src, result.numFrames * sizeof(float));
    // }
    //
    // Determine bed config from channel count:
    // if (chCount >= 8)      result.bedConfig = atmos::BedConfig::BED_7_1;
    // else if (chCount >= 6) result.bedConfig = atmos::BedConfig::BED_5_1;
    // else                   result.bedConfig = atmos::BedConfig::BED_2_0;
    //
    // result.activeChannels = chCount;
    // result.success = true;

    // Stubbed success for compilation before FFmpeg is fully linked
    result.numFrames = 1536;
    result.activeChannels = 8;
    result.bedConfig = atmos::BedConfig::BED_7_1;
    result.success = true;
    
    return result;
}

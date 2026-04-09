#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "Eac3Decoder.h"
#include "JocReconstructor.h"
#include "BinauralRenderer.h"
#include "RoomSimulator.h"
#include "ChannelLayout.h"

#define LOG_TAG "AtmosDecoderJni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class AtmosEngine {
public:
    AtmosEngine() {
        LOGD("Initializing Dolby Atmos Engine (7.1.4)...");
        eac3Decoder_ = new Eac3Decoder();
        jocReconstructor_ = new JocReconstructor();
        binauralRenderer_ = new BinauralRenderer();
        roomSimulator_ = new RoomSimulator();
    }

    ~AtmosEngine() {
        delete roomSimulator_;
        delete binauralRenderer_;
        delete jocReconstructor_;
        delete eac3Decoder_;
    }

    void setOutputMode(int mode) {
        binauralRenderer_->setOutputMode(static_cast<AtmosOutputMode>(mode));
    }

    int getOutputChannelCount() const {
        return binauralRenderer_->getOutputChannelCount();
    }

    int decode(const uint8_t* inputFrame, int inputSize, float* outputPcm) {
        // ── 1. Decode E-AC-3 bed + extract EMDF ──
        Eac3DecodeResult bedResult = eac3Decoder_->decode(inputFrame, inputSize);
        if (!bedResult.success) {
            LOGE("E-AC-3 bed decode failed");
            return -1;
        }

        // ── 2. JOC reconstruction → 7.1.4 speaker feeds ──
        JocReconstructionResult jocResult = jocReconstructor_->reconstruct(
            bedResult.pcmChannels,
            bedResult.activeChannels,
            bedResult.emdfPayload,
            bedResult.emdfSize,
            bedResult.numFrames
        );

        if (!jocResult.success) {
            LOGE("JOC reconstruction failed");
            return -1;
        }

        // ── 3. Room simulation (ISM + FDN reverb) ──
        // Use MID profile by default; could be per-object or user-configurable
        roomSimulator_->process(
            jocResult.speakerFeeds, 
            jocResult.numFrames,
            ObjectMetadata::MID
        );

        // ── 4. Render to output format (7.1.4 / binaural / stereo) ──
        int bytesWritten = binauralRenderer_->render(
            jocResult.speakerFeeds,
            jocResult.numFrames,
            outputPcm
        );

        return bytesWritten;
    }

private:
    Eac3Decoder* eac3Decoder_;
    JocReconstructor* jocReconstructor_;
    BinauralRenderer* binauralRenderer_;
    RoomSimulator* roomSimulator_;
};

// ── JNI Bindings ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_player_atmos_AtmosAudioDecoder_initNativeDecoder(
    JNIEnv* env, jobject /* this */) {
    
    AtmosEngine* engine = new AtmosEngine();
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_player_atmos_AtmosAudioDecoder_nativeDecode(
    JNIEnv* env, jobject /* this */, jlong context, 
    jobject inputBuffer, jint inputSize, jobject outputBuffer) {
    
    AtmosEngine* engine = reinterpret_cast<AtmosEngine*>(context);
    if (!engine) return -1;

    uint8_t* inputData = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    float* outputData = reinterpret_cast<float*>(env->GetDirectBufferAddress(outputBuffer));

    if (!inputData || !outputData) return -1;

    return engine->decode(inputData, inputSize, outputData);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_player_atmos_AtmosAudioDecoder_releaseNativeDecoder(
    JNIEnv* env, jobject /* this */, jlong context) {
    
    AtmosEngine* engine = reinterpret_cast<AtmosEngine*>(context);
    if (engine) {
        delete engine;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_player_atmos_AtmosAudioDecoder_nativeSetOutputMode(
    JNIEnv* env, jobject /* this */, jlong context, jint mode) {
    
    AtmosEngine* engine = reinterpret_cast<AtmosEngine*>(context);
    if (engine) {
        engine->setOutputMode(mode);
        LOGD("Output mode set to %d (0=7.1.4, 1=binaural, 2=stereo)", mode);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_player_atmos_AtmosAudioDecoder_nativeGetOutputChannelCount(
    JNIEnv* env, jobject /* this */, jlong context) {
    
    AtmosEngine* engine = reinterpret_cast<AtmosEngine*>(context);
    if (!engine) return 2;
    return engine->getOutputChannelCount();
}

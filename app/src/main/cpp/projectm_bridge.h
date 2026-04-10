#pragma once

#include <string>
#include <vector>

#include "audio_ring_buffer.h"

#include "projectM-4/projectM.h"
#include "projectM-4/playlist.h"
#include "projectM-4/touch.h"

class ProjectMBridge {
public:
    ProjectMBridge(
            std::string asset_root,
            std::string preset_root,
            std::string texture_root,
            int width,
            int height,
            int mesh_width,
            int mesh_height);
    ~ProjectMBridge();

    bool IsReady() const;
    void Resize(int width, int height);
    void RenderFrame(int64_t frame_time_nanos);
    void PushPcm(const float* data, size_t count, int channel_count, int sample_rate);
    bool SetPreset(const std::string& preset_path);
    std::string NextPreset();
    void SetShuffle(bool enabled);
    void SetBeatSensitivity(int value);
    void SetBrightness(int value);
    void SetPaused(bool paused);
    void SetQuality(int mesh_width, int mesh_height);
    void SetFps(int fps);
    void SetPresetDuration(int seconds);
    std::string CurrentPreset() const;

    void Touch(float x, float y, int pressure, int touch_type);
    void TouchDrag(float x, float y, int pressure);
    void TouchDestroy(float x, float y);
    void TouchDestroyAll();

private:
    std::string FindPresetPath(const std::string& preset_path) const;
    std::string ReadCurrentPreset() const;
    void PushBufferedAudioToProjectM();

    std::string asset_root_;
    std::string preset_root_;
    std::string texture_root_;
    std::string current_preset_;
    projectm_handle projectm_ = nullptr;
    projectm_playlist_handle playlist_ = nullptr;
    AudioRingBuffer audio_buffer_;
    bool paused_ = false;
    int brightness_ = 80;
    float smoothed_rms_ = 0.0f;
};

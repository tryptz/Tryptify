#pragma once

#include <string>
#include <unordered_map>
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
    void BuildPresetIndex();
    std::string ReadCurrentPreset() const;
    void PushBufferedAudioToProjectM();

    std::string asset_root_;
    std::string preset_root_;
    std::string texture_root_;
    std::string current_preset_;
    /**
     * Playlist path -> playlist index, built once when the playlist is loaded.
     *
     * Selecting a preset used to walk the playlist twice, once to confirm the
     * path existed and once to find its index, allocating and freeing a string
     * for every entry both times. The bundled set is nearly ten thousand
     * presets, so that is around twenty thousand allocations per switch --
     * survivable when it ran on the main thread and simply wrong now that the
     * switch happens on the render thread, where it would cost frames.
     *
     * The playlist is only ever populated once, in the constructor, so the
     * indices cannot go stale. The cost is the paths held in memory for as
     * long as the visualizer is open.
     */
    std::unordered_map<std::string, uint32_t> preset_index_;
    projectm_handle projectm_ = nullptr;
    projectm_playlist_handle playlist_ = nullptr;
    AudioRingBuffer audio_buffer_;
    bool paused_ = false;
    int brightness_ = 80;
    float smoothed_rms_ = 0.0f;
};

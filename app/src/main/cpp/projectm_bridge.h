#pragma once

#include <random>
#include <string>
#include <unordered_map>
#include <unordered_set>
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
            int mesh_height,
            std::unordered_set<std::string> excluded_presets,
            std::string crash_sentinel_path);
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
    void SetPresetRotationEnabled(bool enabled);
    std::string CurrentPreset() const;

    void Touch(float x, float y, int pressure, int touch_type);
    void TouchDrag(float x, float y, int pressure);
    void TouchDestroy(float x, float y);
    void TouchDestroyAll();

private:
    static void OnSwitchRequested(bool is_hard_cut, void* user_data);
    static void OnSwitchFailed(const char* preset_filename, const char* message, void* user_data);

    void RemoveExcludedPresets();
    void BuildPresetIndex();
    uint32_t PickNextIndex();
    void PlayIndex(uint32_t index, bool hard_cut, bool retry_on_failure);
    void MarkLoading(const std::string& preset_path) const;
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
    /** The same paths by playlist index, so a pick can be named before it loads. */
    std::vector<std::string> preset_paths_;
    /**
     * Presets that must never be loaded: the ones the host scan found crashing
     * projectM, and any this device has crashed on. Removed from the playlist
     * before it is indexed, so neither rotation nor Next can reach them.
     */
    std::unordered_set<std::string> excluded_presets_;
    /**
     * Holds the preset about to be loaded, written before every load and
     * deleted when the bridge is released. Found still there at the next
     * start, it names the preset that was on screen when the process died --
     * see PresetCrashGuard on the Kotlin side.
     */
    std::string crash_sentinel_path_;
    std::mt19937 rng_{std::random_device{}()};
    bool shuffle_ = true;
    /** Set while a switch may move on to another preset if this one fails to load. */
    bool retry_on_failure_ = false;
    bool hard_cut_requested_ = true;
    int switch_failures_ = 0;
    projectm_handle projectm_ = nullptr;
    projectm_playlist_handle playlist_ = nullptr;
    AudioRingBuffer audio_buffer_;
    bool paused_ = false;
    bool preset_rotation_enabled_ = true;
    int brightness_ = 80;
    float smoothed_rms_ = 0.0f;
};

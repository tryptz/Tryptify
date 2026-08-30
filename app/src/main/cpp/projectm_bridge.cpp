#include "projectm_bridge.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {
constexpr int kDefaultFps = 60;
}

ProjectMBridge::ProjectMBridge(
        std::string asset_root,
        std::string preset_root,
        std::string texture_root,
        int width,
        int height,
        int mesh_width,
        int mesh_height)
        : asset_root_(std::move(asset_root)),
          preset_root_(std::move(preset_root)),
          texture_root_(std::move(texture_root)) {
    projectm_ = projectm_create();
    if (projectm_ == nullptr) {
        return;
    }

    playlist_ = projectm_playlist_create(projectm_);
    if (playlist_ == nullptr) {
        projectm_destroy(projectm_);
        projectm_ = nullptr;
        return;
    }

    projectm_set_window_size(projectm_, width, height);
    projectm_set_mesh_size(projectm_, mesh_width, mesh_height);
    projectm_set_fps(projectm_, kDefaultFps);
    projectm_set_aspect_correction(projectm_, true);
    projectm_set_beat_sensitivity(projectm_, 1.0f);
    projectm_set_preset_duration(projectm_, 20.0);
    projectm_set_soft_cut_duration(projectm_, 2.0);
    projectm_set_hard_cut_enabled(projectm_, true);
    projectm_set_hard_cut_duration(projectm_, 10.0);

    const char* texture_paths[] = {texture_root_.c_str()};
    projectm_set_texture_search_paths(projectm_, texture_paths, 1);
    projectm_playlist_add_path(playlist_, preset_root_.c_str(), true, false);
    projectm_playlist_set_shuffle(playlist_, true);
    BuildPresetIndex();

    if (projectm_playlist_size(playlist_) > 0) {
        projectm_playlist_set_position(playlist_, 0, true);
        current_preset_ = ReadCurrentPreset();
    }
}

ProjectMBridge::~ProjectMBridge() {
    if (playlist_ != nullptr) {
        projectm_playlist_destroy(playlist_);
        playlist_ = nullptr;
    }
    if (projectm_ != nullptr) {
        projectm_destroy(projectm_);
        projectm_ = nullptr;
    }
}

bool ProjectMBridge::IsReady() const {
    return projectm_ != nullptr && playlist_ != nullptr;
}

void ProjectMBridge::Resize(int width, int height) {
    if (projectm_ != nullptr) {
        projectm_set_window_size(projectm_, width, height);
    }
}

void ProjectMBridge::RenderFrame(int64_t /*frame_time_nanos*/) {
    if (!IsReady() || paused_) {
        return;
    }
    PushBufferedAudioToProjectM();
    projectm_opengl_render_frame(projectm_);
}

void ProjectMBridge::PushPcm(const float* data, size_t count, int channel_count, int sample_rate) {
    if (!IsReady() || data == nullptr || count == 0) {
        return;
    }
    audio_buffer_.Push(data, count, channel_count, sample_rate);
}

/**
 * Switch to [preset_path], instantly.
 *
 * Must be called with the GL context current -- switching compiles the
 * preset's shaders, so off the render thread it silently does nothing and
 * leaves whatever was on screen. See ProjectMEngineRepository, which defers
 * every call to here into renderFrame for that reason.
 *
 * The old implementation walked the playlist twice and ended with a call to
 * projectm_load_preset_file that no input could reach: a path outside the
 * playlist returned early, and a path inside it returned from the loop. Both
 * scans are now one hash lookup.
 */
bool ProjectMBridge::SetPreset(const std::string& preset_path) {
    if (!IsReady() || preset_path.empty()) {
        return false;
    }
    // Already showing it. Both the tap and the preference observer that follows
    // it ask for the same preset, so this is the common case, not an edge one.
    if (preset_path == current_preset_) {
        return true;
    }
    const auto found = preset_index_.find(preset_path);
    if (found == preset_index_.end()) {
        return false;
    }
    // hard_cut = true: the switch lands on this frame rather than being blended
    // in over the soft-cut duration, which is what makes picking a preset feel
    // immediate. The soft cut still applies to the automatic rotation.
    projectm_playlist_set_position(playlist_, found->second, true);
    current_preset_ = found->first;
    return true;
}

void ProjectMBridge::BuildPresetIndex() {
    preset_index_.clear();
    if (playlist_ == nullptr) {
        return;
    }
    const auto playlist_size = projectm_playlist_size(playlist_);
    preset_index_.reserve(playlist_size);
    for (uint32_t index = 0; index < playlist_size; ++index) {
        char* item = projectm_playlist_item(playlist_, index);
        if (item == nullptr) {
            continue;
        }
        preset_index_.emplace(item, index);
        projectm_playlist_free_string(item);
    }
}

std::string ProjectMBridge::NextPreset() {
    if (!IsReady()) {
        return {};
    }
    projectm_playlist_play_next(playlist_, true);
    current_preset_ = ReadCurrentPreset();
    return current_preset_;
}

void ProjectMBridge::SetShuffle(bool enabled) {
    if (playlist_ != nullptr) {
        projectm_playlist_set_shuffle(playlist_, enabled);
    }
}

void ProjectMBridge::SetBeatSensitivity(int value) {
    if (projectm_ != nullptr) {
        // Exponential curve: 50 % → 1.0 (default), range 0.2 – 5.0
        // Gives finer control in the mid-range where most users operate
        const float normalized = static_cast<float>(std::clamp(value, 0, 100)) / 100.0f;
        const float scaled = 0.2f * std::pow(25.0f, normalized);
        projectm_set_beat_sensitivity(projectm_, scaled);
    }
}

void ProjectMBridge::SetBrightness(int value) {
    brightness_ = std::clamp(value, 0, 100);
}

void ProjectMBridge::SetPaused(bool paused) {
    paused_ = paused;
}

void ProjectMBridge::SetQuality(int mesh_width, int mesh_height) {
    if (projectm_ != nullptr) {
        projectm_set_mesh_size(projectm_, mesh_width, mesh_height);
    }
}

void ProjectMBridge::SetFps(int fps) {
    if (projectm_ != nullptr) {
        projectm_set_fps(projectm_, fps);
    }
}

/**
 * Turn the timed preset change on or off.
 *
 * projectM's own preset lock, not a very long duration standing in for one: it
 * stops the automatic transitions, hard and soft alike, while leaving a preset
 * chosen by hand -- from the browser, or Next -- working exactly as before.
 * A duration large enough to feel like off is still a timer, and would move the
 * preset eventually on a long listen.
 */
void ProjectMBridge::SetPresetRotationEnabled(bool enabled) {
    preset_rotation_enabled_ = enabled;
    if (projectm_ != nullptr) {
        projectm_set_preset_locked(projectm_, !enabled);
    }
}

void ProjectMBridge::SetPresetDuration(int seconds) {
    if (projectm_ == nullptr) {
        return;
    }
    const auto clamped = std::clamp(seconds, 5, 120);
    projectm_set_preset_duration(projectm_, static_cast<double>(clamped));
    projectm_set_soft_cut_duration(projectm_, std::min(3.0, clamped / 4.0));
    projectm_set_hard_cut_enabled(projectm_, true);
    projectm_set_hard_cut_duration(projectm_, std::max(5.0, clamped * 0.75));
    // Re-asserted because the calls above are what rotation being *on* looks
    // like; without this, moving the duration slider while rotation is off
    // would quietly start it again.
    projectm_set_preset_locked(projectm_, !preset_rotation_enabled_);
}

std::string ProjectMBridge::CurrentPreset() const {
    return current_preset_;
}

std::string ProjectMBridge::ReadCurrentPreset() const {
    if (playlist_ == nullptr) {
        return {};
    }
    const auto index = projectm_playlist_get_position(playlist_);
    char* item = projectm_playlist_item(playlist_, index);
    if (item == nullptr) {
        return {};
    }
    std::string result(item);
    projectm_playlist_free_string(item);
    return result;
}

void ProjectMBridge::Touch(float x, float y, int pressure, int touch_type) {
    if (projectm_ != nullptr) {
        projectm_touch(projectm_, x, y, pressure, static_cast<projectm_touch_type>(touch_type));
    }
}

void ProjectMBridge::TouchDrag(float x, float y, int pressure) {
    if (projectm_ != nullptr) {
        projectm_touch_drag(projectm_, x, y, pressure);
    }
}

void ProjectMBridge::TouchDestroy(float x, float y) {
    if (projectm_ != nullptr) {
        projectm_touch_destroy(projectm_, x, y);
    }
}

void ProjectMBridge::TouchDestroyAll() {
    if (projectm_ != nullptr) {
        projectm_touch_destroy_all(projectm_);
    }
}

void ProjectMBridge::PushBufferedAudioToProjectM() {
    if (projectm_ == nullptr) {
        return;
    }

    std::vector<float> samples;
    int channel_count = 2;
    int sample_rate = 44100;
    if (!audio_buffer_.Pop(samples, channel_count, sample_rate) || samples.empty()) {
        return;
    }

    // ── RMS-based auto-gain normalization ──────────────────────────
    // Keeps visualizer reaction consistent across quiet and loud tracks.
    float sum_sq = 0.0f;
    for (const auto& s : samples) {
        sum_sq += s * s;
    }
    const float rms = std::sqrt(sum_sq / static_cast<float>(samples.size()));

    // Adaptive envelope: fast attack (~55 ms) to catch transients,
    // slow release (~550 ms) to preserve musical dynamics.
    if (rms > smoothed_rms_) {
        smoothed_rms_ = smoothed_rms_ * 0.7f + rms * 0.3f;
    } else {
        smoothed_rms_ = smoothed_rms_ * 0.97f + rms * 0.03f;
    }

    // Derive gain that brings the smoothed level to a consistent target.
    constexpr float kTargetRms = 0.18f;
    constexpr float kMinGain = 0.5f;
    constexpr float kMaxGain = 6.0f;
    float gain = 1.0f;
    if (smoothed_rms_ > 0.001f) {
        gain = std::clamp(kTargetRms / smoothed_rms_, kMinGain, kMaxGain);
    }

    // Apply gain with tanh soft-clip so transient peaks drive the
    // visualizer without harsh distortion.
    for (auto& s : samples) {
        s = std::tanh(s * gain);
    }

    (void) sample_rate;
    const unsigned int per_channel_count = static_cast<unsigned int>(samples.size() / std::max(channel_count, 1));
    projectm_pcm_add_float(
            projectm_,
            samples.data(),
            per_channel_count,
            channel_count == 1 ? PROJECTM_MONO : PROJECTM_STEREO
    );
}

#pragma once
#include "snapin_processor.h"
#include "oversampler.h"
#include "../../atmos/render/virtual_room.h"
#include "../../atmos/speaker_renderer.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

// The spatial renderer's headphone room, for any stereo signal.
//
// It is the room itself, not a copy: tf::atmos::render::VirtualRoom, laid out
// with the same 28 speakers (OutputLayout::k15_1_12), the same baked HRIR set,
// the same early reflections and tail. The Atmos renderer pans objects and
// beds onto those speakers; this plays a stereo pair from two of them, so
// with the speakers at 30 degrees it is what the renderer makes of a stereo
// bed, sample for sample (binaural_room_test holds that).
//
//   Room      how much of the room is heard: 0 none, 100 a live one
//             (VirtualRoom::set_params' room).
//   Binaural  100 is the full ear filters, 0 the two speakers panned left and
//             right with none, time-aligned so anything between is a clean
//             blend (its strength).
//   Angle     where the two speakers stand, degrees to each side of straight
//             ahead: 0 both at the centre speaker, 30 a stereo pair, 110 at
//             the surrounds. Between two of the ear-level ring's speakers the
//             signal is shared by an equal-power pan, so the knob moves the
//             sound and never rebuilds a filter.
//   Output    level after the room, dB. The room itself takes 3 dB off
//             (VirtualRoom::kTrim): it is headroom for the ear filters, which
//             raise some bands, and nothing holds peaks after it.
//
// The rate. The HRIR set and everything measured in the room are 48 kHz.
// The room is run at the stream's own rate where that is within a few
// percent of it (38.4 to 50 kHz), with the ear filters squeezed by the
// difference (EarTune::size, so their spectrum is where it should be at
// 44.1 kHz), and above that at a half or a quarter of the rate, through the
// engine's own half-band filters. A rate that lands outside that band either
// way (8 kHz, 384 kHz) is passed through untouched: there is no room to play
// it in.
//
// Delay. The room answers 7 samples late (VirtualRoom::kLatency) and the
// rate change adds its filters' delay. ownLatency() says how much, and the
// engine holds the dry signal and the other buses back by it.
//
// Memory and time. One instance is about 1.2 MB and takes a few ms to
// prepare (it builds 56 ear filters); both in prepare(), never in process().
class BinauralRoomProcessor : public SnapinProcessor {
public:
    enum Params { ROOM = 0, BINAURAL, ANGLE, OUTPUT, NUM_PARAMS };

    // The ear-level speakers a side the pair can stand at, degrees from
    // straight ahead, as OutputLayout::k15_1_12 has them.
    static constexpr int kRing = 6;
    static constexpr float kRingAz[kRing] = {0.0f, 15.0f, 30.0f, 60.0f, 90.0f, 110.0f};

    // The room's rate has to be this near 48 kHz: EarTune's own limits.
    static constexpr double kMinRoomRate = 38400.0;
    static constexpr double kMaxRoomRate = 50000.0;

    // How a pair [angle] degrees to each side is shared between the ring's
    // speakers: the index of the nearer-centre one, and the equal-power
    // gains of it and the next one out. The picture lights the same ones.
    static void pairOnRing(float angle, int& inner, float& gInner, float& gOuter) {
        const float a = std::max(0.0f, std::min(kRingAz[kRing - 1], angle));
        int k = 0;
        while (k < kRing - 2 && a > kRingAz[k + 1]) k++;
        const float t = (a - kRingAz[k]) / (kRingAz[k + 1] - kRingAz[k]);
        inner = k;
        // On a speaker, all of it there and none next door, exactly: a
        // float's cos of a quarter turn is not quite 0.
        if (t <= 0.0f) { gInner = 1.0f; gOuter = 0.0f; return; }
        if (t >= 1.0f) { gInner = 0.0f; gOuter = 1.0f; return; }
        gInner = std::cos(t * 1.57079632679f);
        gOuter = std::sin(t * 1.57079632679f);
    }

    // Which of the room's speakers (OutputLayout::k15_1_12's order, which is
    // AtmosNative.nativeRoomLayout's) stand on the ring: [left] and [right],
    // kRing each, nearest the centre first. False if the layout has lost one.
    static bool ringSpeakers(int* left, int* right) {
        const std::vector<tf::atmos::SpeakerDef> layout =
            tf::atmos::layout_speakers(tf::atmos::OutputLayout::k15_1_12);
        for (int k = 0; k < kRing; k++) left[k] = right[k] = -1;
        for (size_t s = 0; s < layout.size(); s++) {
            const tf::atmos::SpeakerDef& d = layout[s];
            if (tf::atmos::is_lfe(d) || d.elevation != 0.0f) continue;
            for (int k = 0; k < kRing; k++) {
                if (d.azimuth == -kRingAz[k]) left[k] = static_cast<int>(s);
                if (d.azimuth == kRingAz[k]) right[k] = static_cast<int>(s);
            }
        }
        for (int k = 0; k < kRing; k++) if (left[k] < 0 || right[k] < 0) return false;
        return true;
    }

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        // The slowest of the stream's rate, a half and a quarter of it that
        // the room can be played at.
        factor_ = 0;
        for (int f = 1; f <= 4; f *= 2) {
            const double low = sampleRate / f;
            if (low >= kMinRoomRate && low <= kMaxRoomRate) factor_ = f;
        }
        ownLatency_ = 0;
        room_.reset();
        if (factor_ == 0) return;
        const double roomRate = sampleRate / factor_;

        using tf::atmos::render::VirtualRoom;
        room_ = std::make_unique<VirtualRoom>();
        // Before configure(), which builds the filters once, with it.
        tf::atmos::render::EarTune ears;
        ears.size = static_cast<float>(roomRate / 48000.0);
        room_->set_ear_tune(ears);
        const std::vector<tf::atmos::SpeakerDef> layout =
            tf::atmos::layout_speakers(tf::atmos::OutputLayout::k15_1_12);
        std::vector<tf::atmos::render::RoomSpeaker> speakers;
        speakers.reserve(layout.size());
        for (const tf::atmos::SpeakerDef& d : layout) {
            speakers.push_back({d.azimuth, d.elevation, tf::atmos::is_lfe(d)});
        }
        const bool ring = ringSpeakers(left_, right_);
        room_->configure(static_cast<int>(roomRate + 0.5), speakers);
        room_->set_bass_management(true, 80);  // the renderer's own default
        count_ = room_->speaker_count();
        for (int k = 0; k < kRing; k++) {
            // A layout without one of the ring's speakers has no room for
            // this effect to stand a pair in.
            if (!ring || left_[k] >= count_ || right_[k] >= count_) {
                room_.reset();
                factor_ = 0;
                return;
            }
        }
        feeds_.assign(static_cast<size_t>(kPiece) * count_, 0.0f);
        stereo_.assign(static_cast<size_t>(kPiece) * 2, 0.0f);

        if (factor_ > 1) {
            downL_.prepare(roomRate, factor_);
            downR_.prepare(roomRate, factor_);
            const size_t high = static_cast<size_t>(kChunk) * factor_;
            inL_.assign(high, 0.0f);
            inR_.assign(high, 0.0f);
            hiL_.assign(high, 0.0f);
            hiR_.assign(high, 0.0f);
            lowL_.assign(kChunk, 0.0f);
            lowR_.assign(kChunk, 0.0f);
            fifoL_.assign(high + static_cast<size_t>(factor_) + 8, 0.0f);
            fifoR_.assign(fifoL_.size(), 0.0f);
            // The rate change's own delay, measured: an impulse down and up.
            std::fill(inL_.begin(), inL_.end(), 0.0f);
            inL_[0] = 1.0f;
            downL_.downsample(inL_.data(), lowL_.data(), kChunk);
            downL_.upsample(lowL_.data(), hiL_.data(), kChunk);
            int peak = 0;
            for (size_t i = 1; i < high; i++) {
                if (std::fabs(hiL_[i]) > std::fabs(hiL_[static_cast<size_t>(peak)])) peak = static_cast<int>(i);
            }
            downL_.reset();
            std::fill(inL_.begin(), inL_.end(), 0.0f);
            std::fill(lowL_.begin(), lowL_.end(), 0.0f);
            std::fill(hiL_.begin(), hiL_.end(), 0.0f);
            ownLatency_ = peak + VirtualRoom::kLatency * factor_ + (factor_ - 1);
        } else {
            lowL_.assign(kChunk, 0.0f);
            lowR_.assign(kChunk, 0.0f);
            ownLatency_ = VirtualRoom::kLatency;
        }
        reset();
    }

    int ownLatency() const override { return ownLatency_; }

    void process(float* left, float* right, int numFrames) override {
        if (factor_ == 0 || !room_ || numFrames <= 0) return;  // no room at this rate: untouched
        if (factor_ == 1) {
            for (int done = 0; done < numFrames; done += kChunk) {
                const int n = std::min(kChunk, numFrames - done);
                renderRoom(left + done, right + done, n);
            }
            return;
        }
        // Down to the room's rate, through it, and back up. A block need not
        // be a whole number of the room's samples: what is left over waits
        // for the next one, and the output is read from a line that starts
        // factor - 1 samples full, so there is always enough in it.
        const int cap = kChunk * factor_;
        int done = 0;
        while (done < numFrames) {
            const int take = std::min(numFrames - done, cap - have_);
            std::memcpy(inL_.data() + have_, left + done, sizeof(float) * static_cast<size_t>(take));
            std::memcpy(inR_.data() + have_, right + done, sizeof(float) * static_cast<size_t>(take));
            have_ += take;
            const int groups = have_ / factor_;
            if (groups > 0) {
                downL_.downsample(inL_.data(), lowL_.data(), groups);
                downR_.downsample(inR_.data(), lowR_.data(), groups);
                renderRoom(lowL_.data(), lowR_.data(), groups);
                downL_.upsample(lowL_.data(), hiL_.data(), groups);
                downR_.upsample(lowR_.data(), hiR_.data(), groups);
                const int used = groups * factor_;
                push(hiL_.data(), hiR_.data(), used);
                have_ -= used;
                if (have_ > 0) {
                    std::memmove(inL_.data(), inL_.data() + used, sizeof(float) * static_cast<size_t>(have_));
                    std::memmove(inR_.data(), inR_.data() + used, sizeof(float) * static_cast<size_t>(have_));
                }
            }
            pop(left + done, right + done, take);
            done += take;
        }
    }

    void reset() override {
        if (room_) room_->reset_history();
        if (factor_ > 1) {
            downL_.reset();
            downR_.reset();
            std::fill(fifoL_.begin(), fifoL_.end(), 0.0f);
            std::fill(fifoR_.begin(), fifoR_.end(), 0.0f);
            fifoRead_ = 0;
            // factor - 1 samples of silence ahead of the first output.
            fifoWrite_ = factor_ - 1;
            fifoFill_ = factor_ - 1;
        }
        have_ = 0;
        // A setting is where it was put, not on its way there.
        roomNow_ = room01_;
        strengthNow_ = strength01_;
        angleNow_ = angle_;
        gainNow_ = gainTarget_;
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case ROOM:
                roomPercent_ = std::max(0.0f, std::min(100.0f, value));
                room01_ = roomPercent_ * 0.01f;
                break;
            case BINAURAL:
                strengthPercent_ = std::max(0.0f, std::min(100.0f, value));
                strength01_ = strengthPercent_ * 0.01f;
                break;
            case ANGLE:
                angle_ = std::max(0.0f, std::min(kRingAz[kRing - 1], value));
                break;
            case OUTPUT:
                outputDb_ = std::max(-12.0f, std::min(12.0f, value));
                gainTarget_ = std::pow(10.0f, outputDb_ / 20.0f);
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case ROOM: return roomPercent_;
            case BINAURAL: return strengthPercent_;
            case ANGLE: return angle_;
            case OUTPUT: return outputDb_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Binaural Room"; }
    SnapinType getType() const override { return SnapinType::BINAURAL_ROOM; }

private:
    // The most the room is given at once, in its own samples, and the piece
    // a moving knob is followed in: the room reads its two settings once a
    // call, so a turn of Room or Binaural is heard in steps of this.
    static constexpr int kChunk = 1024;
    static constexpr int kPiece = 256;
    // How far a setting moves toward where it was put in one piece: all the
    // way in about 50 ms at 48 kHz.
    static constexpr float kFollow = 0.35f;
    // The Angle knob's speed, degrees a sample: the whole ring in 0.2 s.
    static constexpr float kAnglePerSample = 110.0f / 9600.0f;

    // [n] of the room's own samples, in place.
    void renderRoom(float* l, float* r, int n) {
        for (int done = 0; done < n; done += kPiece) {
            const int len = std::min(kPiece, n - done);
            roomNow_ = follow(roomNow_, room01_);
            strengthNow_ = follow(strengthNow_, strength01_);
            room_->set_params(strengthNow_, roomNow_);
            std::memset(feeds_.data(), 0, sizeof(float) * static_cast<size_t>(len) * count_);
            const float* inL = l + done;
            const float* inR = r + done;
            if (angleNow_ == angle_) {
                int k;
                float gi, go;
                pairOnRing(angleNow_, k, gi, go);
                place(inL, inR, len, k, gi, go);
            } else {
                // The pair is on its way round the ring: placed sample by
                // sample, so it moves and does not jump.
                for (int i = 0; i < len; i++) {
                    const float d = angle_ - angleNow_;
                    angleNow_ = std::fabs(d) <= kAnglePerSample ? angle_
                        : angleNow_ + (d > 0.0f ? kAnglePerSample : -kAnglePerSample);
                    int k;
                    float gi, go;
                    pairOnRing(angleNow_, k, gi, go);
                    float* f = feeds_.data() + static_cast<size_t>(i) * count_;
                    f[left_[k]] += inL[i] * gi;
                    f[left_[k + 1]] += inL[i] * go;
                    f[right_[k]] += inR[i] * gi;
                    f[right_[k + 1]] += inR[i] * go;
                }
            }
            room_->render(feeds_.data(), len, stereo_.data());
            const float g0 = gainNow_;
            gainNow_ = follow(gainNow_, gainTarget_);
            const float step = (gainNow_ - g0) / static_cast<float>(len);
            for (int i = 0; i < len; i++) {
                const float g = g0 + step * static_cast<float>(i + 1);
                l[done + i] = stereo_[static_cast<size_t>(2 * i)] * g;
                r[done + i] = stereo_[static_cast<size_t>(2 * i + 1)] * g;
            }
        }
    }

    void place(const float* inL, const float* inR, int len, int k, float gi, float go) {
        const int li = left_[k], lo = left_[k + 1], ri = right_[k], ro = right_[k + 1];
        for (int i = 0; i < len; i++) {
            float* f = feeds_.data() + static_cast<size_t>(i) * count_;
            // +=: at 0 degrees both sides stand at the one centre speaker.
            f[li] += inL[i] * gi;
            f[lo] += inL[i] * go;
            f[ri] += inR[i] * gi;
            f[ro] += inR[i] * go;
        }
    }

    static float follow(float now, float target) {
        const float d = target - now;
        return std::fabs(d) < 1e-4f ? target : now + d * kFollow;
    }

    void push(const float* l, const float* r, int n) {
        const int size = static_cast<int>(fifoL_.size());
        for (int i = 0; i < n; i++) {
            fifoL_[static_cast<size_t>(fifoWrite_)] = l[i];
            fifoR_[static_cast<size_t>(fifoWrite_)] = r[i];
            if (++fifoWrite_ == size) fifoWrite_ = 0;
        }
        fifoFill_ += n;
    }

    void pop(float* l, float* r, int n) {
        const int size = static_cast<int>(fifoL_.size());
        for (int i = 0; i < n; i++) {
            // Never short, by how the line starts (see process()); if it
            // ever were, silence rather than whatever is in the ring.
            if (fifoFill_ <= 0) { l[i] = 0.0f; r[i] = 0.0f; continue; }
            l[i] = fifoL_[static_cast<size_t>(fifoRead_)];
            r[i] = fifoR_[static_cast<size_t>(fifoRead_)];
            if (++fifoRead_ == size) fifoRead_ = 0;
            fifoFill_--;
        }
    }

    std::unique_ptr<tf::atmos::render::VirtualRoom> room_;
    int factor_ = 0;       // 0: no room at this rate
    int count_ = 0;
    int ownLatency_ = 0;
    int left_[kRing] = {}, right_[kRing] = {};
    std::vector<float> feeds_, stereo_;

    ChannelOversampler downL_, downR_;
    std::vector<float> inL_, inR_, lowL_, lowR_, hiL_, hiR_, fifoL_, fifoR_;
    int have_ = 0;
    int fifoRead_ = 0, fifoWrite_ = 0, fifoFill_ = 0;

    // What the knobs say (the defaults), and where the sound is.
    float roomPercent_ = 25.0f, strengthPercent_ = 100.0f, angle_ = 30.0f, outputDb_ = 0.0f;
    float room01_ = 0.25f, strength01_ = 1.0f, gainTarget_ = 1.0f;
    float roomNow_ = 0.25f, strengthNow_ = 1.0f, angleNow_ = 30.0f, gainNow_ = 1.0f;
};

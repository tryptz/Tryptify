#pragma once
#include "snapin_processor.h"
#include "oversampler.h"
#include "../../atmos/render/virtual_room.h"
#include "../../atmos/render/box_room.h"
#include "../../atmos/speaker_renderer.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
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
// My room. Off, the room is the renderer's built-in one. On, it is the room
// its page calls My room: a box of the Width, Depth and Height set, in
// metres, whose walls take up the Damping set of the sound that meets them
// (render/box_room.h, the same working out, and the same convolver plays it).
// Room is then that room's level. Working a room out takes a moment (a large
// bare one, a second on a phone), so it is never done where a knob is set,
// which is under the engine's lock with the music waiting: one thread for
// every instance (binaural_room_detail::Builder) works the room out and
// hands it over, and until then the room before is heard. roomSettled() says
// when it has.
//
// A surround mix. On the master of a stream wider than stereo each channel
// group runs its own copy of the chain, so this effect there used to be one
// room a pair, every pair stood at the front, and the fold added them: seven
// copies of the same ear filters and reflections, a comb (heard on a phone,
// 2026-11-20). There the mixer hands the front pair's room every other
// channel instead (setBed, MultiLaneEngine), and the room plays each from its
// own speaker, as the renderer plays a bed: the rears behind, the tops above,
// the centre from the centre speaker, the LFE from the sub. Angle still
// places the front pair. The other channels' rooms play nothing.
//
// Memory and time. One instance is about 1.2 MB and takes a few ms to
// prepare (it builds 56 ear filters); both in prepare(), never in process().
namespace binaural_room_detail {

// Which room: a box in tenths of a metre and its walls' share in hundredths,
// at the rate the room runs at; all zero for the built-in one.
struct RoomKey {
    int width = 0, depth = 0, height = 0, damping = 0, rate = 0;
    bool operator==(const RoomKey& o) const {
        return width == o.width && depth == o.depth && height == o.height && damping == o.damping && rate == o.rate;
    }
    bool builtIn() const { return width == 0; }
};

// What the builder and one effect share. The effect may be deleted, or build
// a new room, while a room for it is being worked out: [room] is then null,
// and only ever read or written under [install].
struct Target {
    std::mutex install;
    tf::atmos::render::VirtualRoom* room = nullptr;
    RoomKey have;       // under [install]: the room that is in
    std::mutex ask;     // held for a moment only, the engine's lock above it
    RoomKey want;       // under [ask]
    bool queued = false;  // under [ask]
    std::atomic<bool> settled{true};
};

// The one thread that works rooms out, for every Binaural Room there is.
class Builder {
public:
    static Builder& get() {
        // Never destroyed: its thread may be waiting when the process ends.
        static Builder* b = new Builder();
        return *b;
    }

    // [target]'s want has changed.
    void request(const std::shared_ptr<Target>& target) {
        {
            std::lock_guard<std::mutex> a(target->ask);
            if (target->queued) return;
            target->queued = true;
        }
        {
            std::lock_guard<std::mutex> q(queueMutex_);
            queue_.push_back(target);
        }
        wake_.notify_one();
    }

    // The room [key] names, as the convolver takes it; null for the built-in
    // one and for a box with no room in it. Allocates; a second or so.
    static std::shared_ptr<tf::atmos::render::RoomResponse> build(const RoomKey& key) {
        using namespace tf::atmos::render;
        if (key.builtIn() || key.rate <= 0) return nullptr;
        BoxRoom box;
        box.width = key.width * 0.1f;
        box.depth = key.depth * 0.1f;
        box.height = key.height * 0.1f;
        box.absorption = key.damping * 0.01f;
        std::vector<float> left, right;
        box_response(box, left, right);
        // The response is 48 kHz and the convolver plays it a sample a
        // sample at the room's rate. Called a little faster or slower than
        // it is, the resampling makes it last as long there as it should.
        const double called = 48000.0 * 48000.0 / static_cast<double>(key.rate);
        auto room = std::make_shared<RoomResponse>();
        if (!prepare_room_response(left.data(), right.data(), static_cast<int>(left.size()), called, *room)) {
            return nullptr;
        }
        return room;
    }

private:
    Builder() { std::thread([this] { run(); }).detach(); }

    void run() {
        for (;;) {
            std::shared_ptr<Target> target;
            {
                std::unique_lock<std::mutex> q(queueMutex_);
                wake_.wait(q, [this] { return !queue_.empty(); });
                target = queue_.front().lock();
                queue_.pop_front();
            }
            if (target) serve(*target);
        }
    }

    void serve(Target& t) {
        for (;;) {
            RoomKey key;
            {
                std::lock_guard<std::mutex> a(t.ask);
                key = t.want;
            }
            {
                std::lock_guard<std::mutex> in(t.install);
                if (t.room == nullptr || t.have == key) {
                    std::lock_guard<std::mutex> a(t.ask);
                    // Asked for another since: round again.
                    if (t.room != nullptr && !(t.want == key)) continue;
                    t.queued = false;
                    t.settled.store(true, std::memory_order_release);
                    return;
                }
            }
            const std::shared_ptr<tf::atmos::render::RoomResponse> room = cached(key);
            std::lock_guard<std::mutex> in(t.install);
            if (t.room == nullptr) continue;  // gone meanwhile: the top of the loop ends it
            t.room->set_room_response(room.get());
            t.have = key;
        }
    }

    std::shared_ptr<tf::atmos::render::RoomResponse> cached(const RoomKey& key) {
        for (const auto& c : cache_) if (c.first == key) return c.second;
        auto room = build(key);
        if (!key.builtIn()) {
            if (cache_.size() >= kCache) cache_.pop_front();
            cache_.emplace_back(key, room);
        }
        return room;
    }

    // A wide stream has a room a lane, all asking for the same one.
    static constexpr size_t kCache = 3;
    std::mutex queueMutex_;
    std::condition_variable wake_;
    std::deque<std::weak_ptr<Target>> queue_;
    std::deque<std::pair<RoomKey, std::shared_ptr<tf::atmos::render::RoomResponse>>> cache_;  // the thread's own
};

}  // namespace binaural_room_detail

class BinauralRoomProcessor : public SnapinProcessor {
public:
    enum Params { ROOM = 0, BINAURAL, ANGLE, OUTPUT, MY_ROOM, WIDTH, DEPTH, HEIGHT, DAMPING, NUM_PARAMS };

    // My room's limits, as the renderer's page has them (CustomRoom).
    static constexpr float kMinSide = 2.0f, kMaxSide = 30.0f;
    static constexpr float kMinHeight = 2.0f, kMaxHeight = 12.0f;
    static constexpr float kMinDamping = 5.0f, kMaxDamping = 70.0f;  // percent

    BinauralRoomProcessor() : target_(std::make_shared<binaural_room_detail::Target>()) {}
    ~BinauralRoomProcessor() override { detach(); }

    // Whether the room heard is the one the knobs describe: false while My
    // room is being worked out after a change.
    bool roomSettled() const { return target_->settled.load(std::memory_order_acquire); }

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
    // The most channels a bed can hand the room: two a lane.
    static constexpr int kMaxBed = 32;
    // A channel with no speaker of its own in the room is shared between the
    // nearest: two layers of two rows of two at most.
    static constexpr int kMaxTaps = 8;

    // Which of the room's speakers play a bed channel of [role] (RoomRole),
    // and how loud each: its own speaker where the room has one (the fronts,
    // the centre, the wides, the sides, a 5.1 bed's surrounds, the rears, the
    // LFE), else the place the renderer gives that channel
    // (tf::atmos::bed_channel_room), shared between the speakers round it by
    // the renderer's own room panner. The tops land on the upper ring. Returns
    // how many, 0 for a role the room cannot place. Allocates: never from
    // process().
    static int roleSpeakers(int role, int* speakers, float* gains) {
        using tf::atmos::SpeakerRole;
        using tf::atmos::cavern::ReferenceChannel;
        if (role < 0 || role >= RoomRole::kCount) return 0;
        const std::vector<tf::atmos::SpeakerDef> layout =
            tf::atmos::layout_speakers(tf::atmos::OutputLayout::k15_1_12);
        if (role < RoomRole::kBackCentre) {
            const SpeakerRole want = static_cast<SpeakerRole>(role);
            for (size_t s = 0; s < layout.size(); s++) {
                if (layout[s].role == want) {
                    speakers[0] = static_cast<int>(s);
                    gains[0] = 1.0f;
                    return 1;
                }
            }
        }
        tf::atmos::Vec3 at{0.5f, 1.0f, 0.0f};  // the back centre: the middle of the back wall
        switch (role) {
            case RoomRole::kBackCentre: break;
            case RoomRole::kLtf: at = tf::atmos::bed_channel_room(ReferenceChannel::kTopFrontLeft); break;
            case RoomRole::kRtf: at = tf::atmos::bed_channel_room(ReferenceChannel::kTopFrontRight); break;
            case RoomRole::kLtr: at = tf::atmos::bed_channel_room(ReferenceChannel::kTopRearLeft); break;
            case RoomRole::kRtr: at = tf::atmos::bed_channel_room(ReferenceChannel::kTopRearRight); break;
            case RoomRole::kLts: at = tf::atmos::bed_channel_room(ReferenceChannel::kTopSideLeft); break;
            case RoomRole::kRts: at = tf::atmos::bed_channel_room(ReferenceChannel::kTopSideRight); break;
            default: return 0;
        }
        std::vector<int> all;
        for (size_t s = 0; s < layout.size(); s++) all.push_back(static_cast<int>(s));
        tf::atmos::BalancePanner panner;
        panner.configure(layout, all);
        std::vector<float> power(layout.size(), 0.0f);
        panner.accumulate_power(at, 1.0f, power.data());
        int n = 0;
        for (size_t s = 0; s < layout.size() && n < kMaxTaps; s++) {
            if (power[s] <= 0.0f) continue;
            speakers[n] = static_cast<int>(s);
            gains[n] = std::sqrt(power[s]);
            n++;
        }
        return n;
    }

    // The rest of a surround mix, for the next process() only: see the notes
    // above. The samples are read during that call and not kept. A channel
    // with no role is left out.
    void setBed(const RoomBedChannel* bed, int count) {
        bedCount_ = 0;
        if (!bed) return;
        for (int j = 0; j < count && bedCount_ < kMaxBed; j++) {
            if (!bed[j].a || bed[j].role < 0 || bed[j].role >= RoomRole::kCount) continue;
            bed_[bedCount_++] = bed[j];
        }
    }

    // Whether there is a room at the rate this was prepared for. Without one
    // the signal passes untouched, and a bed must not be handed over: it
    // would be dropped.
    bool hasRoom() const { return factor_ != 0 && room_ != nullptr; }

    bool oversamples() const override { return false; }

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
        detach();
        room_.reset();
        roomRate_ = 0;
        if (factor_ == 0) return;
        const double roomRate = sampleRate / factor_;
        roomRate_ = static_cast<int>(roomRate + 0.5);

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
        // Where each kind of bed channel plays from, worked out once.
        for (int role = 0; role < RoomRole::kCount; role++) {
            int at[kMaxTaps];
            float g[kMaxTaps];
            const int n = roleSpeakers(role, at, g);
            tapCount_[role] = 0;
            for (int t = 0; t < n; t++) {
                if (at[t] < 0 || at[t] >= count_) continue;
                taps_[role][tapCount_[role]++] = {at[t], g[t]};
            }
        }
        feeds_.assign(static_cast<size_t>(kPiece) * count_, 0.0f);
        stereo_.assign(static_cast<size_t>(kPiece) * 2, 0.0f);
        low_.assign(kPiece, 0.0f);
        lowL_.assign(kPiece, 0.0f);
        lowR_.assign(kPiece, 0.0f);

        if (factor_ > 1) {
            downL_.prepare(roomRate, factor_);
            downR_.prepare(roomRate, factor_);
            bedDown_.assign(kMaxBed, ChannelOversampler());
            for (ChannelOversampler& d : bedDown_) d.prepare(roomRate, factor_);
            bedCarry_.assign(static_cast<size_t>(kMaxBed) * kMaxFactor, 0.0f);
            const size_t piece = static_cast<size_t>(kPiece) * factor_;
            hi_.assign(piece, 0.0f);
            hiL_.assign(piece, 0.0f);
            hiR_.assign(piece, 0.0f);
            fifoL_.assign(static_cast<size_t>(kChunk) * factor_ + static_cast<size_t>(factor_) + 8, 0.0f);
            fifoR_.assign(fifoL_.size(), 0.0f);
            // The rate change's own delay, measured: an impulse down and up.
            const size_t high = static_cast<size_t>(kChunk) * factor_;
            std::vector<float> in(high, 0.0f), low(kChunk, 0.0f), out(high, 0.0f);
            in[0] = 1.0f;
            downL_.downsample(in.data(), low.data(), kChunk);
            downL_.upsample(low.data(), out.data(), kChunk);
            int peak = 0;
            for (size_t i = 1; i < high; i++) {
                if (std::fabs(out[i]) > std::fabs(out[static_cast<size_t>(peak)])) peak = static_cast<int>(i);
            }
            downL_.reset();
            ownLatency_ = peak + VirtualRoom::kLatency * factor_ + (factor_ - 1);
        } else {
            bedDown_.clear();
            bedCarry_.clear();
            ownLatency_ = VirtualRoom::kLatency;
        }
        reset();
        {
            // A new room is the built-in one until My room is put in it.
            std::lock_guard<std::mutex> in(target_->install);
            target_->room = room_.get();
            target_->have = binaural_room_detail::RoomKey{};
        }
        askForRoom(/* again = */ true);
    }

    int ownLatency() const override { return ownLatency_; }

    void process(float* left, float* right, int numFrames) override {
        // The bed is for this call alone.
        const int bedCount = bedCount_;
        bedCount_ = 0;
        if (factor_ == 0 || !room_ || numFrames <= 0) return;  // no room at this rate: untouched
        if (bedCount != bedShape_) {
            // The stream has widened or narrowed: what the bed's rate
            // filters hold belongs to other channels.
            bedShape_ = bedCount;
            for (ChannelOversampler& d : bedDown_) d.reset();
            std::fill(bedCarry_.begin(), bedCarry_.end(), 0.0f);
        }
        if (factor_ == 1) {
            for (int done = 0; done < numFrames; done += kPiece) {
                const int len = std::min(kPiece, numFrames - done);
                beginPiece(len);
                placePair(left + done, right + done, len);
                for (int j = 0; j < bedCount; j++) {
                    const RoomBedChannel& c = bed_[j];
                    const float* x = c.a + done;
                    if (c.b) {
                        for (int i = 0; i < len; i++) low_[static_cast<size_t>(i)] = 0.5f * (c.a[done + i] + c.b[done + i]);
                        x = low_.data();
                    }
                    addBed(x, c.role, len);
                }
                finishPiece(left + done, right + done, len);
            }
            return;
        }
        // Down to the room's rate, through it, and back up. A block need not
        // be a whole number of the room's samples: what is left over waits
        // for the next one (carry_), and the output is read from a line that
        // starts factor - 1 samples full, so there is always enough in it.
        const int cap = kChunk * factor_;
        for (int done = 0; done < numFrames; done += cap) {
            const int take = std::min(cap, numFrames - done);
            throughAtLowRate(left + done, right + done, done, take, bedCount);
            pop(left + done, right + done, take);
        }
    }

    void reset() override {
        if (room_) room_->reset_history();
        if (factor_ > 1) {
            downL_.reset();
            downR_.reset();
            for (ChannelOversampler& d : bedDown_) d.reset();
            std::fill(bedCarry_.begin(), bedCarry_.end(), 0.0f);
            std::fill(fifoL_.begin(), fifoL_.end(), 0.0f);
            std::fill(fifoR_.begin(), fifoR_.end(), 0.0f);
            fifoRead_ = 0;
            // factor - 1 samples of silence ahead of the first output.
            fifoWrite_ = factor_ - 1;
            fifoFill_ = factor_ - 1;
        }
        carry_ = 0;
        for (int i = 0; i < kMaxFactor; i++) carryL_[i] = carryR_[i] = 0.0f;
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
            case MY_ROOM:
                myRoom_ = value >= 0.5f;
                askForRoom();
                break;
            case WIDTH:
                width_ = std::max(kMinSide, std::min(kMaxSide, value));
                askForRoom();
                break;
            case DEPTH:
                depth_ = std::max(kMinSide, std::min(kMaxSide, value));
                askForRoom();
                break;
            case HEIGHT:
                height_ = std::max(kMinHeight, std::min(kMaxHeight, value));
                askForRoom();
                break;
            case DAMPING:
                damping_ = std::max(kMinDamping, std::min(kMaxDamping, value));
                askForRoom();
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case ROOM: return roomPercent_;
            case BINAURAL: return strengthPercent_;
            case ANGLE: return angle_;
            case OUTPUT: return outputDb_;
            case MY_ROOM: return myRoom_ ? 1.0f : 0.0f;
            case WIDTH: return width_;
            case DEPTH: return depth_;
            case HEIGHT: return height_;
            case DAMPING: return damping_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Binaural Room"; }
    SnapinType getType() const override { return SnapinType::BINAURAL_ROOM; }

private:
    // The most the room is given between two reads of its output at a high
    // rate, in its own samples, and the piece a moving knob is followed in:
    // the room reads its two settings once a call, so a turn of Room or
    // Binaural is heard in steps of this.
    static constexpr int kChunk = 1024;
    static constexpr int kPiece = 256;
    static constexpr int kMaxFactor = 4;
    // How far a setting moves toward where it was put in one piece: all the
    // way in about 50 ms at 48 kHz.
    static constexpr float kFollow = 0.35f;
    // The Angle knob's speed, degrees a sample: the whole ring in 0.2 s.
    static constexpr float kAnglePerSample = 110.0f / 9600.0f;

    struct Tap {
        int speaker;
        float gain;
    };

    // The builder is told nothing of this effect's room any more: it waits
    // out a room being handed over, and what it is working out is dropped.
    void detach() {
        std::lock_guard<std::mutex> in(target_->install);
        target_->room = nullptr;
    }

    // The room the knobs describe, asked of the builder. A moment's work:
    // it is called with the engine's lock held.
    // [again]: the room is a new one, so what was wanted before is wanted of it.
    void askForRoom(bool again = false) {
        binaural_room_detail::RoomKey key;
        if (myRoom_ && room_) {
            key.width = static_cast<int>(width_ * 10.0f + 0.5f);
            key.depth = static_cast<int>(depth_ * 10.0f + 0.5f);
            key.height = static_cast<int>(height_ * 10.0f + 0.5f);
            key.damping = static_cast<int>(damping_ + 0.5f);
            key.rate = roomRate_;
        }
        if (!room_) return;
        {
            std::lock_guard<std::mutex> a(target_->ask);
            if (target_->want == key && !again) return;
            if (again && key.builtIn()) {
                // A new room is the built-in one already: nothing to work
                // out. A builder still at work for the old one ends there.
                target_->want = key;
                if (!target_->queued) target_->settled.store(true, std::memory_order_release);
                return;
            }
            target_->want = key;
            target_->settled.store(false, std::memory_order_release);
        }
        binaural_room_detail::Builder::get().request(target_);
    }

    // One piece of the room's own samples, [len] of them: the settings are
    // read and the speakers emptied (beginPiece), the pair and the bed are
    // put on their speakers (placePair, addBed), and the room plays them
    // (finishPiece).
    void beginPiece(int len) {
        roomNow_ = follow(roomNow_, room01_);
        strengthNow_ = follow(strengthNow_, strength01_);
        room_->set_params(strengthNow_, roomNow_);
        std::memset(feeds_.data(), 0, sizeof(float) * static_cast<size_t>(len) * count_);
    }

    void placePair(const float* inL, const float* inR, int len) {
        if (angleNow_ == angle_) {
            int k;
            float gi, go;
            pairOnRing(angleNow_, k, gi, go);
            place(inL, inR, len, k, gi, go);
            return;
        }
        // The pair is on its way round the ring: placed sample by sample, so
        // it moves and does not jump.
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

    void addBed(const float* x, int role, int len) {
        const int n = tapCount_[role];
        for (int t = 0; t < n; t++) {
            const int at = taps_[role][t].speaker;
            const float g = taps_[role][t].gain;
            float* f = feeds_.data() + at;
            for (int i = 0; i < len; i++) f[static_cast<size_t>(i) * count_] += x[i] * g;
        }
    }

    void finishPiece(float* outL, float* outR, int len) {
        room_->render(feeds_.data(), len, stereo_.data());
        const float g0 = gainNow_;
        gainNow_ = follow(gainNow_, gainTarget_);
        const float step = (gainNow_ - g0) / static_cast<float>(len);
        for (int i = 0; i < len; i++) {
            const float g = g0 + step * static_cast<float>(i + 1);
            outL[i] = stereo_[static_cast<size_t>(2 * i)] * g;
            outR[i] = stereo_[static_cast<size_t>(2 * i + 1)] * g;
        }
    }

    // [take] samples at the stream's rate, with what was left of the last
    // call ahead of them: every whole group of [factor_] goes down to the
    // room's rate, through the room and back up into the output line. The bed
    // is read from [offset] samples into its buffers.
    void throughAtLowRate(const float* l, const float* r, int offset, int take, int bedCount) {
        const int total = carry_ + take;
        const int groups = total / factor_;
        for (int g0 = 0; g0 < groups; g0 += kPiece) {
            const int len = std::min(kPiece, groups - g0);
            const int start = g0 * factor_;
            const int hiLen = len * factor_;
            beginPiece(len);
            gather(carryL_, l, nullptr, start, hiLen);
            downL_.downsample(hi_.data(), lowL_.data(), len);
            gather(carryR_, r, nullptr, start, hiLen);
            downR_.downsample(hi_.data(), lowR_.data(), len);
            placePair(lowL_.data(), lowR_.data(), len);
            for (int j = 0; j < bedCount; j++) {
                const RoomBedChannel& c = bed_[j];
                gather(bedCarry_.data() + static_cast<size_t>(j) * kMaxFactor, c.a + offset,
                       c.b ? c.b + offset : nullptr, start, hiLen);
                bedDown_[static_cast<size_t>(j)].downsample(hi_.data(), low_.data(), len);
                addBed(low_.data(), c.role, len);
            }
            finishPiece(lowL_.data(), lowR_.data(), len);
            downL_.upsample(lowL_.data(), hiL_.data(), len);
            downR_.upsample(lowR_.data(), hiR_.data(), len);
            push(hiL_.data(), hiR_.data(), hiLen);
        }
        const int used = groups * factor_;
        const int left = total - used;
        keep(carryL_, l, nullptr, used, left);
        keep(carryR_, r, nullptr, used, left);
        for (int j = 0; j < bedCount; j++) {
            const RoomBedChannel& c = bed_[j];
            keep(bedCarry_.data() + static_cast<size_t>(j) * kMaxFactor, c.a + offset,
                 c.b ? c.b + offset : nullptr, used, left);
        }
        carry_ = left;
    }

    // Sample [at] of a channel's line: the [carry_] left from the last call,
    // then this call's ([a], and [b] for the other half of a mono lane).
    inline float lineAt(const float* carry, const float* a, const float* b, int at) const {
        if (at < carry_) return carry[at];
        const int i = at - carry_;
        return b ? 0.5f * (a[i] + b[i]) : a[i];
    }

    void gather(const float* carry, const float* a, const float* b, int start, int count) {
        for (int i = 0; i < count; i++) hi_[static_cast<size_t>(i)] = lineAt(carry, a, b, start + i);
    }

    void keep(float* carry, const float* a, const float* b, int used, int left) {
        float next[kMaxFactor] = {};
        for (int i = 0; i < left && i < kMaxFactor; i++) next[i] = lineAt(carry, a, b, used + i);
        for (int i = 0; i < kMaxFactor; i++) carry[i] = next[i];
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
    std::shared_ptr<binaural_room_detail::Target> target_;
    int roomRate_ = 0;
    bool myRoom_ = false;
    float width_ = 5.0f, depth_ = 6.0f, height_ = 2.7f, damping_ = 25.0f;
    int factor_ = 0;       // 0: no room at this rate
    int count_ = 0;
    int ownLatency_ = 0;
    int left_[kRing] = {}, right_[kRing] = {};
    std::vector<float> feeds_, stereo_;

    // The bed: what the next process() is given, and where each kind of
    // channel plays from.
    RoomBedChannel bed_[kMaxBed];
    int bedCount_ = 0;
    int bedShape_ = 0;  // how many the last process() had
    Tap taps_[RoomRole::kCount][kMaxTaps] = {};
    int tapCount_[RoomRole::kCount] = {};

    // The rate change (factor_ > 1): a filter a channel, the samples of each
    // that did not make a whole group, and the output line.
    ChannelOversampler downL_, downR_;
    std::vector<ChannelOversampler> bedDown_;
    std::vector<float> bedCarry_;
    float carryL_[kMaxFactor] = {}, carryR_[kMaxFactor] = {};
    int carry_ = 0;
    std::vector<float> hi_, low_, lowL_, lowR_, hiL_, hiR_, fifoL_, fifoR_;
    int fifoRead_ = 0, fifoWrite_ = 0, fifoFill_ = 0;

    // What the knobs say (the defaults), and where the sound is.
    float roomPercent_ = 25.0f, strengthPercent_ = 100.0f, angle_ = 30.0f, outputDb_ = 0.0f;
    float room01_ = 0.25f, strength01_ = 1.0f, gainTarget_ = 1.0f;
    float roomNow_ = 0.25f, strengthNow_ = 1.0f, angleNow_ = 30.0f, gainNow_ = 1.0f;
};

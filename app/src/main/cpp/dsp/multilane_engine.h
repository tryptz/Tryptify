#pragma once
#include "dsp_engine.h"
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

/**
 * The mixer for streams wider than stereo: one DspEngine per lane of the
 * channel layout, stepped together.
 *
 * Every snapin is a stereo processor, so a multichannel stream runs the way a
 * surround mix is usually built from stereo tools — as its left/right pairs
 * (FL/FR, BL/BR, SL/SR, TFL/TFR…), each through its own copy of the same
 * chain, with the single channels (centre, LFE) as mono lanes. Which channels
 * pair up is decided in Kotlin (ChannelLayout) and handed in; this only runs
 * them.
 *
 * - Every lane carries the same chain: extra lanes are built from lane 0's
 *   state, and every control call reaches all of them (forEach).
 * - Lane 0 is the front pair and the one the UI sees: meters, scopes and the
 *   saved state are its. It is never replaced, so a stereo stream is exactly
 *   the single engine it always was.
 * - Master-bus dynamics are linked: at a slot whose snapin detects level, all
 *   lanes are given one key — the loudest sample across every lane — so the
 *   whole bed is turned down together. Mix-bus dynamics stay per lane.
 * - A mono lane runs its channel as dual-mono and folds it back; effects that
 *   only reshape a stereo image are skipped there (DspEngine::setMonoLane).
 * - Unless setSpread(false), the lanes are spread across the mixer: lane k feeds bus number k + 1
 *   only (front on bus 1, centre on 2, LFE on 3, …), and the mixer grows to
 *   one bus per lane. Every lane still carries every bus's chain, but a bus
 *   with no input is skipped outright, so each channel group runs through
 *   its own strip and nothing else. Meters for a bus come from the lane that
 *   feeds it; the master's are the loudest across the lanes.
 *
 * Threads: control calls fan out under laneMutex_; meter reads take only
 * shapeMutex_, which guards the lane list itself. The audio thread only
 * ever *tries* it; if a fan-out is mid-flight it runs that one block with the
 * lanes unlinked rather than wait, so it never blocks on the UI.
 *
 * configureLanes() and processPlanar() must be called from the same thread —
 * MixBusProcessor calls both from Media3's playback thread (configure/flush
 * and queueInput). That is what lets the unlinked fallback read the lane list
 * without the lock: the only concurrent caller, a UI fan-out, never adds or
 * removes a lane.
 */
class MultiLaneEngine {
public:
    static constexpr int MAX_LANES = 16;

    MultiLaneEngine(int sampleRate, int maxBlockSize)
        : sampleRate_(sampleRate), maxBlock_(maxBlockSize) {
        lanes_.push_back(std::make_unique<DspEngine>(sampleRate, maxBlockSize));
        laneFirst_[0] = 0;
        laneSecond_[0] = 1;
        allocScratch();
    }

    DspEngine& primary() { return *lanes_[0]; }

    int laneCount() const { return static_cast<int>(lanes_.size()); }

    /** Runs [f] on every lane, as one change the audio thread sees whole. */
    template <typename F>
    void forEach(F&& f) {
        std::lock_guard<std::mutex> lock(laneMutex_);
        for (auto& e : lanes_) f(*e);
    }

    void reconfigure(int sampleRate, int maxBlockSize) {
        std::lock_guard<std::mutex> lock(laneMutex_);
        sampleRate_ = sampleRate;
        maxBlock_ = maxBlockSize;
        for (auto& e : lanes_) e->reconfigure(sampleRate, maxBlockSize);
        allocScratch();
    }

    /**
     * Sets the lanes: lane k carries channel first[k], and second[k] too
     * unless it is negative (a mono lane). While spreading (setSpread), lane
     * k is routed to bus k + 1 — see the class notes. Builds or drops engines as needed —
     * allocation, so never from the audio thread. Lane 0 must be a pair.
     */
    void configureLanes(const int* first, const int* second, int count) {
        count = std::max(1, std::min(count, MAX_LANES));
        // Route (and so grow or shrink) the lanes that stay first, so that
        // the new ones below are cloned with the bus count already right.
        bool spread;
        {
            std::lock_guard<std::mutex> lock(laneMutex_);
            spread = spread_;
            routeLanesLocked(std::min(count, laneCount()), count);
        }
        // New lanes are clones of the primary, built before taking the lock.
        // Only serialised when there is a lane to build: this runs on every
        // track change, stereo included.
        std::vector<std::unique_ptr<DspEngine>> fresh;
        if (count > laneCount()) {
            const std::string state = primary().getStateJson(/* full = */ true);
            const bool mixBypass = primary().isMixBypassed();
            const int grownFrom = primary().autoGrownFrom();
            for (int k = laneCount(); k < count; k++) {
                auto e = std::make_unique<DspEngine>(sampleRate_, maxBlock_);
                e->setRouting(routeOf(k, count, spread), routedCountFor(count, spread));
                e->loadStateJson(state);
                // The state carries the grown buses as the clone's own; it
                // has to know they were grown, as lane 0 does, or it keeps
                // them when the stream narrows and lane 0 drops them.
                e->inheritGrowth(grownFrom);
                e->setMixBypassed(mixBypass);
                fresh.push_back(std::move(e));
            }
        }
        std::vector<std::unique_ptr<DspEngine>> retired;  // destroyed after unlock
        {
            std::lock_guard<std::mutex> lock(laneMutex_);
            std::lock_guard<std::mutex> shape(shapeMutex_);
            while (laneCount() > count) {
                retired.push_back(std::move(lanes_.back()));
                lanes_.pop_back();
            }
            for (auto& e : fresh) lanes_.push_back(std::move(e));
            for (int k = 0; k < count; k++) {
                laneFirst_[k] = first[k];
                laneSecond_[k] = second[k];
                lanes_[k]->setMonoLane(k > 0 && second[k] < 0);
            }
        }
    }

    /**
     * Processes one block of planar channels in place: channels[c] holds
     * [numFrames] samples of channel c. Longer blocks than the engines were
     * prepared for are run in pieces.
     */
    void processPlanar(float* const* channels, int numChannels, int numFrames) {
        for (int done = 0; done < numFrames; done += maxBlock_) {
            const int n = std::min(maxBlock_, numFrames - done);
            processChunk(channels, numChannels, done, n);
        }
    }

    /**
     * Whether a wide stream is spread across the mixer, one bus per channel
     * group (the default), or runs whole through the buses by their own input
     * switches — bus 1 alone, unless others are switched on — as stereo does.
     * Re-routes the lanes in place, without adding or dropping any, so it is
     * safe from any thread.
     */
    void setSpread(bool spread) {
        std::lock_guard<std::mutex> lock(laneMutex_);
        spread_ = spread;
        routeLanesLocked(laneCount(), laneCount());
    }

    /**
     * [peakL, peakR, holdL, holdR] per bus, as DspEngine::getBusLevels, but
     * each routed bus read from the lane that feeds it and the master from
     * the loudest lane.
     */
    void getBusLevels(float* out, int maxFloats) {
        std::lock_guard<std::mutex> lock(shapeMutex_);
        primary().getBusLevels(out, maxFloats);
        float lv[4];
        for (int k = 1; k < laneCount(); k++) {
            const int bus = lanes_[k]->routedBus();
            if (bus >= 0 && bus * 4 + 3 < maxFloats && lanes_[k]->getBusLevel(bus, lv)) {
                for (int i = 0; i < 4; i++) out[bus * 4 + i] = lv[i];
            }
            if (MASTER_BUS * 4 + 3 < maxFloats && lanes_[k]->getBusLevel(MASTER_BUS, lv)) {
                for (int i = 0; i < 4; i++) {
                    out[MASTER_BUS * 4 + i] = std::max(out[MASTER_BUS * 4 + i], lv[i]);
                }
            }
        }
    }

    /** Runs [f] on the lane whose channels feed [busIndex] (else the primary). */
    template <typename F>
    auto withBusLane(int busIndex, F&& f) {
        std::lock_guard<std::mutex> lock(shapeMutex_);
        for (auto& e : lanes_) {
            if (e->routedBus() == busIndex) return f(*e);
        }
        return f(primary());
    }

    bool getAndResetClipped() {
        std::lock_guard<std::mutex> lock(shapeMutex_);
        bool any = false;
        for (auto& e : lanes_) any = e->getAndResetClipped() || any;
        return any;
    }

private:
    // One lane is plain stereo: never routed, every bus by its own switch.
    static int routedCountFor(int lanes, bool spread) { return spread && lanes > 1 ? lanes : 0; }
    static int routeOf(int k, int lanes, bool spread) {
        return routedCountFor(lanes, spread) > 0 ? DspEngine::busIndexForNumber(k + 1) : -1;
    }
    void routeLanesLocked(int first, int lanes) {
        for (int k = 0; k < first; k++) {
            lanes_[k]->setRouting(routeOf(k, lanes, spread_), routedCountFor(lanes, spread_));
        }
    }

    void allocScratch() {
        const size_t n = static_cast<size_t>(std::max(1, maxBlock_));
        key_.assign(n, 0.0f);
        for (int k = 0; k < MAX_LANES; k++) {
            monoL_[k].assign(n, 0.0f);
            monoR_[k].assign(n, 0.0f);
        }
    }

    /** Where lane k reads and writes, and whether it is a mono lane. */
    bool lanePointers(int k, float* const* ch, int numChannels, int offset, int n,
                      float*& l, float*& r) {
        const int a = laneFirst_[k];
        const int b = laneSecond_[k];
        if (a < 0 || a >= numChannels) return false;
        if (b >= 0 && b < numChannels) {
            l = ch[a] + offset;
            r = ch[b] + offset;
            return true;
        }
        std::copy(ch[a] + offset, ch[a] + offset + n, monoL_[k].data());
        std::copy(ch[a] + offset, ch[a] + offset + n, monoR_[k].data());
        l = monoL_[k].data();
        r = monoR_[k].data();
        return true;
    }

    void foldMono(int k, float* const* ch, int offset, int n) {
        if (laneSecond_[k] >= 0) return;
        float* dst = ch[laneFirst_[k]] + offset;
        const float* l = monoL_[k].data();
        const float* r = monoR_[k].data();
        for (int i = 0; i < n; i++) dst[i] = 0.5f * (l[i] + r[i]);
    }

    void processChunk(float* const* ch, int numChannels, int offset, int n) {
        std::unique_lock<std::mutex> lanesLock(laneMutex_, std::try_to_lock);
        const int count = laneCount();
        float* laneL[MAX_LANES];
        float* laneR[MAX_LANES];
        bool active[MAX_LANES];
        for (int k = 0; k < count; k++) {
            active[k] = lanePointers(k, ch, numChannels, offset, n, laneL[k], laneR[k]);
        }

        if (!lanesLock.owns_lock()) {
            // A control change is fanning out: this block runs each lane on
            // its own, unlinked, rather than wait for it.
            for (int k = 0; k < count; k++) {
                if (!active[k]) continue;
                lanes_[k]->process(laneL[k], laneR[k], n);
                foldMono(k, ch, offset, n);
            }
            return;
        }

        std::unique_lock<std::mutex> chainLocks[MAX_LANES];
        for (int k = 0; k < count; k++) {
            chainLocks[k] = std::unique_lock<std::mutex>(lanes_[k]->chainMutex());
        }

        for (int k = 0; k < count; k++) {
            if (active[k]) lanes_[k]->processMixBusesLocked(laneL[k], laneR[k], n);
        }

        int slots = 0;
        for (int k = 0; k < count; k++) slots = std::max(slots, lanes_[k]->masterSlotCountLocked());
        for (int s = 0; s < slots; s++) {
            const float* key = nullptr;
            if (count > 1 && lanes_[0]->masterSlotLinkableLocked(s)) {
                // The loudest sample across every lane, at this slot's input.
                float* kb = key_.data();
                std::fill(kb, kb + n, 0.0f);
                for (int k = 0; k < count; k++) {
                    if (!active[k]) continue;
                    const float* sl = lanes_[k]->masterSumL();
                    const float* sr = lanes_[k]->masterSumR();
                    for (int i = 0; i < n; i++) {
                        const float v = std::max(std::fabs(sl[i]), std::fabs(sr[i]));
                        if (v > kb[i]) kb[i] = v;
                    }
                }
                key = kb;
            }
            for (int k = 0; k < count; k++) {
                if (active[k]) lanes_[k]->processMasterSlotLocked(s, n, key);
            }
        }

        for (int k = 0; k < count; k++) {
            if (!active[k]) continue;
            lanes_[k]->finishBlockLocked(laneL[k], laneR[k], n);
            foldMono(k, ch, offset, n);
        }
    }

    std::vector<std::unique_ptr<DspEngine>> lanes_;
    bool spread_ = true;  // guarded by laneMutex_
    int laneFirst_[MAX_LANES] = {};
    int laneSecond_[MAX_LANES] = {};
    int sampleRate_;
    int maxBlock_;
    std::mutex laneMutex_;
    // Held only while lanes_ itself changes (configureLanes) and by the meter
    // reads, so the UI's 60 Hz polling never takes laneMutex_: the audio
    // thread only try-locks that one, and a meter read winning it would run
    // the block with the master dynamics unlinked.
    std::mutex shapeMutex_;
    std::vector<float> key_;
    std::vector<float> monoL_[MAX_LANES];
    std::vector<float> monoR_[MAX_LANES];
};

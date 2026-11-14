package tf.monochrome.android.audio.atmos

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.ChannelLayout
import tf.monochrome.android.domain.model.RendererMode
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.StereoDownmixMode
import tf.monochrome.android.domain.model.speakers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3 [AudioProcessor] that renders Dolby Atmos to binaural stereo. It sits at
 * the FRONT of the DefaultAudioSink processor chain, so it receives the decoded
 * multichannel bed PCM (from NextLib's FfmpegAudioRenderer) before any downmix.
 * Per E-AC-3 frame (1536 samples) it pulls the matching raw frame that the sample
 * tap ([AtmosTapMediaSourceFactory]) preserved and calls
 * [AtmosNative.nativeProcessFrame], which reconstructs the objects (JOC) and
 * renders them to binaural stereo via the native pipeline. Frames without JOC
 * (plain multichannel) play their bed through the same render, so audio never drops.
 *
 * Active only for >2-channel input; stereo/mono passes straight through
 * (configure returns NOT_SET), leaving non-Atmos playback untouched.
 *
 * Speaker output: when the profile's speaker render is on and the effective
 * layout is multichannel ([RendererProfile.speakerLayout] — manual, or the
 * connected HDMI/USB device's channel count via [deviceChannelCount]), the
 * objects are rendered to that layout instead ([AtmosNative.nativeProcessFrameSpeakers])
 * and the processor outputs [ChannelLayout.sinkChannelCount] channels in
 * Android mask order; [activeLayout] tells the AudioTrack provider which mask
 * to put on the track.
 */
@Singleton
@OptIn(UnstableApi::class)
class AtmosAudioProcessor @Inject constructor(
    private val frameBuffer: AtmosFrameBuffer,
    preferences: PreferencesManager,
    private val roomStore: AtmosRoomStore,
) : AudioProcessor {

    // The user's settings from the Atmos Renderer Configuration screen. Kept in a
    // volatile snapshot so the audio thread never touches DataStore, and pushed
    // into the native pipeline whenever it changes.
    @Volatile private var profile: RendererProfile = RendererProfile.DEFAULT
    // As the listener saved it; [profile] is this for the output in use.
    @Volatile private var savedProfile: RendererProfile = RendererProfile.DEFAULT

    /**
     * Whether headphones are the output (wired, USB or Bluetooth). Set by
     * PlaybackService from its device callback; decides the binaural render
     * while the listener has not chosen ([RendererProfile.hrtfAuto]).
     */
    var headphones: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyProfile(savedProfile)
        }

    private fun applyProfile(saved: RendererProfile) {
        savedProfile = saved
        val updated = saved.effective(headphones)
        android.util.Log.i(
            TAG,
            "profile: headphones=$headphones saved(hrtf=${saved.hrtfEnabled} auto=${saved.hrtfAuto} " +
                "mode=${saved.mode} speakers=${saved.speakerRender}) -> in use(hrtf=${updated.hrtfEnabled} " +
                "mode=${updated.mode} room=${updated.roomAmount})",
        )
        profile = updated
        refreshSofa(updated.hrtfProfileId)
        refreshRoom(updated)
        setEarTune(updated.earTune)
        pushParams()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Custom HRTF (a user .sofa copied into app storage; its path is the
    // profile's hrtfProfileId). The bytes are read here on the Default
    // dispatcher — never on the audio thread — and applied to the native
    // pipeline via nativeLoadSofa. null path = the baked default HRTF.
    @Volatile private var sofaBytes: ByteArray? = null
    private var loadedSofaPath: String? = null
    // False when the live pipeline does not yet reflect [sofaBytes] — set on a
    // SOFA change or a fresh pipeline, cleared once (re)applied.
    @Volatile private var sofaApplied = false

    /**
     * Last custom-SOFA apply outcome, for the renderer UI: path → accepted.
     * Null while no custom SOFA is selected (built-in HRTF) or before the
     * first apply. A rejected file means the render is running the built-in
     * set, and the screen has to say so.
     */
    private val _sofaStatus = MutableStateFlow<Pair<String, Boolean>?>(null)
    val sofaStatus: StateFlow<Pair<String, Boolean>?> = _sofaStatus.asStateFlow()

    /** What the last frames of a multichannel stream actually got. */
    enum class Outcome {
        /** JOC objects panned onto the 15.1.12 virtual room, its speakers to the ears. */
        OBJECTS_ROOM,

        /** JOC objects panned onto the output's own speakers. */
        OBJECTS_SPEAKERS,

        /** No objects (plain multichannel, or none yet): the bed on the virtual room. */
        BED_ROOM,

        /** No objects: the bed on the output's speakers. */
        BED_SPEAKERS,

        /** No objects to render, or the HRTF is off: the bed folded to stereo. */
        BED_FOLDED,
    }

    /**
     * The objects of the frames being played, for the Atmos page's map: for
     * each of [count], [AtmosNative.OBJECT_METER_FIELDS] floats in [fields]
     * (see [AtmosNative.nativeObjectMeter]; the level is a decaying peak).
     */
    class ObjectMeter(val count: Int, val fields: FloatArray) {
        fun x(i: Int) = fields[i * AtmosNative.OBJECT_METER_FIELDS]
        fun y(i: Int) = fields[i * AtmosNative.OBJECT_METER_FIELDS + 1]
        fun z(i: Int) = fields[i * AtmosNative.OBJECT_METER_FIELDS + 2]
        /** The decaying peak, or under zero when the object is watched and not rendered. */
        fun level(i: Int) = fields[i * AtmosNative.OBJECT_METER_FIELDS + 3]
        fun isLfe(i: Int) = fields[i * AtmosNative.OBJECT_METER_FIELDS + 4] == AtmosNative.METER_KIND_LFE
    }

    /**
     * The render outcome, for the Audio Pipeline panel; null while this stage
     * is out of the chain. Published only when it changes, so the audio
     * thread does nothing per frame for it but compare.
     */
    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()
    private var lastOutcome: Outcome? = null

    /** The user chose "Direct": the bed goes on untouched. */
    val isPassthrough: Boolean get() = profile.mode == RendererMode.PASSTHROUGH

    /** Whether the native renderer loaded at all. */
    val isRendererAvailable: Boolean get() = AtmosNative.isAvailable

    private fun noteOutcome(outcome: Outcome?) {
        if (outcome == lastOutcome) return
        android.util.Log.i(TAG, "outcome: $lastOutcome -> $outcome (rendered=$renderedFrames fallback=$fallbackFrames)")
        lastOutcome = outcome
        _outcome.value = outcome
    }

    /**
     * How many objects the stream's frames are rendering (the LFE counted),
     * 0 when it is the bed that plays. Published when it changes.
     */
    private val _objectCount = MutableStateFlow(0)
    val objectCount: StateFlow<Int> = _objectCount.asStateFlow()
    private var lastObjectCount = 0

    private fun noteObjects(count: Int) {
        if (count == lastObjectCount) return
        lastObjectCount = count
        _objectCount.value = count
    }

    // The object meter, read only while something shows it (the Atmos page).
    // The audio thread writes positions and holds each object's peak in
    // meterFields; a publisher reads them thirty times a second and lets the
    // peaks fall. The unguarded float races are harmless for a meter.
    private val meterSubscribers = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var meterOn = false
    private val meterScratch = FloatArray(MAX_OBJECTS * AtmosNative.OBJECT_METER_FIELDS)
    private val meterFields = FloatArray(MAX_OBJECTS * AtmosNative.OBJECT_METER_FIELDS)
    @Volatile private var meterCount = 0
    private var meterJob: kotlinx.coroutines.Job? = null
    private val _objects = MutableStateFlow<ObjectMeter?>(null)
    private val speakerScratch = FloatArray(AtmosNative.ROOM_MAX_SPEAKERS)
    private val speakerLevels = FloatArray(AtmosNative.ROOM_MAX_SPEAKERS)
    @Volatile private var speakerCount = 0
    private val _speakers = MutableStateFlow<FloatArray?>(null)

    /**
     * How hard each speaker of the 15.1.12 room is driven, in
     * [AtmosNative.nativeRoomLayout]'s order, while [acquireObjectMeter] is
     * held: a decaying peak, or under zero for objects that are watched and
     * not rendered (how much of them the speaker would carry). Null when
     * nothing plays through or towards the room.
     */
    val speakers: StateFlow<FloatArray?> = _speakers.asStateFlow()

    /** The objects being rendered, while [acquireObjectMeter] is held; null when none are. */
    val objects: StateFlow<ObjectMeter?> = _objects.asStateFlow()

    /** Pair each call with one [releaseObjectMeter]. */
    fun acquireObjectMeter() {
        if (meterSubscribers.incrementAndGet() != 1) return
        synchronized(meterSubscribers) {
            meterOn = true
            meterJob?.cancel()
            meterJob = scope.launch {
                while (true) {
                    val count = meterCount
                    _objects.value = if (count > 0) {
                        ObjectMeter(count, meterFields.copyOf(count * AtmosNative.OBJECT_METER_FIELDS))
                    } else {
                        null
                    }
                    // Thirty times a second, a frame of the stream: objects move.
                    for (i in 0 until count) {
                        val at = i * AtmosNative.OBJECT_METER_FIELDS + 3
                        if (meterFields[at] > 0f) meterFields[at] *= METER_FALL
                    }
                    val speakers = speakerCount
                    _speakers.value = if (speakers > 0) speakerLevels.copyOf(speakers) else null
                    for (i in 0 until speakers) if (speakerLevels[i] > 0f) speakerLevels[i] *= METER_FALL
                    kotlinx.coroutines.delay(33)
                }
            }
        }
    }

    fun releaseObjectMeter() {
        if (meterSubscribers.updateAndGet { (it - 1).coerceAtLeast(0) } != 0) return
        synchronized(meterSubscribers) {
            if (meterSubscribers.get() != 0) return
            meterOn = false
            meterJob?.cancel()
            meterJob = null
            _objects.value = null
            _speakers.value = null
        }
    }

    /**
     * What a stream's objects are: how many there are, how many of them have
     * moved since it started, and whether one is the LFE. The ones that stay
     * put are the mix's bed, which a music stream sends as objects parked at
     * speaker positions rather than as bed channels.
     */
    data class ObjectMix(val count: Int, val moving: Int, val lfe: Boolean) {
        val parked: Int get() = count - moving - (if (lfe) 1 else 0)
    }

    private val _objectMix = MutableStateFlow<ObjectMix?>(null)
    val objectMix: StateFlow<ObjectMix?> = _objectMix.asStateFlow()
    private var lastMix: ObjectMix? = null
    private val firstPlace = FloatArray(MAX_OBJECTS * 3)
    private val moved = BooleanArray(MAX_OBJECTS)
    private var placed = 0

    private fun noteMix(mix: ObjectMix?) {
        if (mix == lastMix) return
        if (mix != null) {
            android.util.Log.i(TAG, "objects: ${mix.count} (${mix.parked} parked, ${mix.moving} moving, lfe=${mix.lfe})")
        }
        lastMix = mix
        _objectMix.value = mix
    }

    private fun forgetMix() {
        placed = 0
        placeFrames = 0
        java.util.Arrays.fill(placePeaks, 0f)
        java.util.Arrays.fill(moved, false)
        noteMix(null)
    }

    // The engine's metadata state, logged when it changes (not the held-frame
    // count, which ticks) so a log shows where a stream's objects went.
    private val debugNow = IntArray(AtmosNative.DEBUG_FIELDS)
    private val debugLast = IntArray(AtmosNative.DEBUG_FIELDS) { -1 }

    private fun logMetadata() {
        if (pipeline == 0L || AtmosNative.nativeDebugInfo(pipeline, debugNow) < AtmosNative.DEBUG_FIELDS) return
        var changed = false
        val held = AtmosNative.DEBUG_HELD_FRAMES
        for (i in debugNow.indices) if (i != held && debugNow[i] != debugLast[i]) changed = true
        // Held frames: only that the container went missing or came back.
        if ((debugNow[held] > 0) != (debugLast[held] > 0)) changed = true
        if (!changed) return
        System.arraycopy(debugNow, 0, debugLast, 0, 10)
        android.util.Log.i(
            TAG,
            "metadata: container=${debugNow[0]} joc(valid=${debugNow[1]} objects=${debugNow[2]} " +
                "dmx=${debugNow[3]}) oamd(valid=${debugNow[4]} objects=${debugNow[5]} states=${debugNow[6]} " +
                "warp=${debugNow[7]}) held=${debugNow[8]} lfeSlot=${debugNow[9]} rawBytes=$lastFrameBytes",
        )
    }

    /** Audio thread, after a frame: what it rendered, and the meter if it is on. */
    private fun noteFrame(objects: Int) {
        noteObjects(objects)
        logMetadata()
        if (pipeline == 0L) { meterCount = 0; speakerCount = 0; return }
        takeSpeakers(tracked = false)
        if (objects <= 0) { meterCount = 0; return }
        takeMeter(AtmosNative.nativeObjectMeter(pipeline, meterScratch).coerceIn(0, MAX_OBJECTS))
    }

    /** The room's speakers for the frame just rendered or watched, if the meter is on. */
    private fun takeSpeakers(tracked: Boolean) {
        if (!meterOn || pipeline == 0L) { speakerCount = 0; return }
        val count = AtmosNative.nativeRoomMeter(pipeline, tracked, speakerScratch)
            .coerceIn(0, AtmosNative.ROOM_MAX_SPEAKERS)
        for (i in 0 until count) {
            val now = speakerScratch[i]
            // Under zero is "would carry this much" (watched): kept as it is.
            if (now < 0f || i >= speakerCount || speakerLevels[i] < 0f || now > speakerLevels[i]) {
                speakerLevels[i] = now
            }
        }
        speakerCount = count
    }

    // Where every object is and how loud, into the log: two seconds into a
    // stream (positions have arrived, levels mean something) and then every
    // ten. Peaks are held between lines. With this a log says whether a
    // stream's objects are where the mix put them, and which carry sound.
    private val placePeaks = FloatArray(MAX_OBJECTS)
    private var placeFrames = 0

    private fun logPlaces(count: Int) {
        val n = AtmosNative.OBJECT_METER_FIELDS
        for (i in 0 until count) {
            if (meterScratch[i * n + 3] > placePeaks[i]) placePeaks[i] = meterScratch[i * n + 3]
        }
        placeFrames++
        if (placeFrames != PLACES_FIRST && placeFrames % PLACES_EVERY != 0) return
        val text = StringBuilder("places:")
        for (i in 0 until count) {
            val at = i * n
            val kind = when (meterScratch[at + 4]) {
                AtmosNative.METER_KIND_LFE -> "LFE"
                AtmosNative.METER_KIND_BED -> "bed"
                else -> "obj"
            }
            val db = if (meterScratch[at + 3] < 0f) "n/a" else {
                val peak = placePeaks[i]
                if (peak <= 1e-6f) "-inf" else String.format(java.util.Locale.ROOT, "%.0f", 20f * kotlin.math.log10(peak))
            }
            text.append(String.format(
                java.util.Locale.ROOT, " %d:%s(%.2f,%.2f,%.2f)%s%s",
                i, kind, meterScratch[at], meterScratch[at + 1], meterScratch[at + 2],
                if (i < moved.size && moved[i]) "~" else "", "[$db]",
            ))
            placePeaks[i] = 0f
        }
        android.util.Log.i(TAG, text.toString())
    }

    /** [count] objects are in meterScratch: which move, and the meter if it is on. */
    private fun takeMeter(count: Int) {
        if (count <= 0) { meterCount = 0; noteMix(null); return }
        val n = AtmosNative.OBJECT_METER_FIELDS
        // Which objects move: against where each one was first seen.
        var moving = 0
        var lfe = false
        for (i in 0 until count) {
            val at = i * n
            if (meterScratch[at + 4] == AtmosNative.METER_KIND_LFE) { lfe = true; continue }
            if (i >= placed) {
                firstPlace[i * 3] = meterScratch[at]
                firstPlace[i * 3 + 1] = meterScratch[at + 1]
                firstPlace[i * 3 + 2] = meterScratch[at + 2]
            } else if (!moved[i] && (
                    kotlin.math.abs(meterScratch[at] - firstPlace[i * 3]) > MOVED ||
                    kotlin.math.abs(meterScratch[at + 1] - firstPlace[i * 3 + 1]) > MOVED ||
                    kotlin.math.abs(meterScratch[at + 2] - firstPlace[i * 3 + 2]) > MOVED)
            ) {
                moved[i] = true
            }
            if (moved[i]) moving++
        }
        if (count > placed) placed = count
        noteMix(ObjectMix(count, moving, lfe))
        logPlaces(count)
        if (!meterOn) return
        for (i in 0 until count) {
            val at = i * n
            meterFields[at] = meterScratch[at]
            meterFields[at + 1] = meterScratch[at + 1]
            meterFields[at + 2] = meterScratch[at + 2]
            // A level under zero is "not known" (watched, not rendered): kept as it is.
            if (meterScratch[at + 3] < 0f || i >= meterCount || meterScratch[at + 3] > meterFields[at + 3]) {
                meterFields[at + 3] = meterScratch[at + 3]
            }
            meterFields[at + 4] = meterScratch[at + 4]
        }
        meterCount = count
    }

    // The ear tuner. What is wanted is taken at once; the pipeline is given
    // it from this scope, one build at a time and always the latest, since a
    // build takes tens of milliseconds and the listener drags.
    @Volatile private var wantedEar = tf.monochrome.android.domain.model.EarTune()
    // Declared ahead of the profile's collector below: its first value can
    // arrive on another thread before this object is fully made.
    private var appliedEar: tf.monochrome.android.domain.model.EarTune? = null  // under pipelineLock
    private val earSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch { for (ignored in earSignal) applyEar() }
    }

    init {
        preferences.rendererProfile
            .onEach { updated -> applyProfile(updated) }
            .launchIn(scope)
    }

    // The room the headphone render plays in, made ready by the store off
    // this thread: null is the render's own. [roomApplied] is false while the
    // live pipeline does not yet have it (a change, or a fresh pipeline).
    @Volatile private var room: tf.monochrome.android.domain.model.AtmosRoom? = null
    @Volatile private var roomApplied = false
    private var roomJob: kotlinx.coroutines.Job? = null

    /** Has the store make [profile]'s room ready, then hands it to the pipeline. */
    private fun refreshRoom(profile: RendererProfile) {
        roomJob?.cancel()
        roomJob = scope.launch {
            // A room being set by its sliders is worked out when they rest.
            if (profile.roomKind == tf.monochrome.android.domain.model.RoomKind.CUSTOM) kotlinx.coroutines.delay(300)
            val ready = roomStore.resolve(profile)
            if (ready === room && roomApplied) return@launch
            room = ready
            roomApplied = false
            applyRoom()
        }
    }

    /**
     * Gives the pipeline the room (or its own back); once per change. It is
     * called from the store's thread as well as the player's, so it holds
     * [pipelineLock] against the pipeline being replaced under it.
     */
    private fun applyRoom() = synchronized(pipelineLock) {
        val p = pipeline
        if (p == 0L || roomApplied) return@synchronized
        val ok = AtmosNative.nativeSetRoom(p, room?.packed) == 1
        android.util.Log.i(TAG, "room -> ${room?.let { "decay ${it.decaySeconds}s, sized=${it.sized}" } ?: "built-in"} (${if (ok) "set" else "REFUSED"})")
        roomApplied = true
    }

    /**
     * The listener's ears, heard as soon as the filters are built: the page
     * calls this as its pad is dragged, ahead of the profile being saved.
     */
    fun setEarTune(tune: tf.monochrome.android.domain.model.EarTune) {
        wantedEar = tune.clamped()
        earSignal.trySend(Unit)
    }

    private fun applyEar() = synchronized(pipelineLock) {
        val p = pipeline
        val tune = wantedEar
        if (p == 0L || tune == appliedEar) return@synchronized
        AtmosNative.nativeSetEarTune(p, tune.size, tune.freqHz, tune.gainDb, tune.q)
        appliedEar = tune
    }

    /**
     * The ear tuner's graph: a sound from above at the nearer ear, as the
     * HRTF in use has it and as [tune] makes it, [hz].size values of each in
     * dB. Null when the native library is not there. It may wait for a build:
     * not from the main thread.
     */
    fun earCurve(tune: tf.monochrome.android.domain.model.EarTune, hz: FloatArray): FloatArray? {
        if (!AtmosNative.isAvailable) return null
        val t = tune.clamped()
        val out = FloatArray(hz.size * 2)
        val n = synchronized(pipelineLock) {
            runCatching {
                AtmosNative.nativeEarCurve(pipeline, t.size, t.freqHz, t.gainDb, t.q, EAR_CURVE_AZ, EAR_CURVE_EL, hz, out)
            }.getOrDefault(0)
        }
        return out.takeIf { n == hz.size }
    }

    /** Held while the native pipeline is made, destroyed, or given a room from another thread. */
    private val pipelineLock = Any()

    /** Reads the selected .sofa off the audio thread; caches its bytes. */
    private fun refreshSofa(path: String?) {
        if (path == loadedSofaPath) return
        loadedSofaPath = path
        sofaBytes = path?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }
        sofaApplied = false
        _sofaStatus.value = when {
            path == null -> null                    // built-in HRTF selected
            sofaBytes == null -> path to false      // unreadable file = rejected
            else -> null                            // pending until applied
        }
    }

    /** Loads the cached SOFA into [p] (or reverts to baked); once per change. */
    private fun applySofa(p: Long) {
        if (p == 0L || sofaApplied) return
        val bytes = sofaBytes
        if (bytes != null) {
            val ok = AtmosNative.nativeLoadSofa(p, bytes) == 1
            android.util.Log.i(TAG, "custom HRTF ${if (ok) "loaded" else "REJECTED"} (${bytes.size}B)")
            loadedSofaPath?.let { _sofaStatus.value = it to ok }
        } else {
            AtmosNative.nativeClearSofa(p)
        }
        sofaApplied = true
    }

    /** Sends the current profile to the native renderer (no-op without a pipeline). */
    private fun pushParams() {
        val p = pipeline
        val cur = profile
        if (p == 0L) {
            // Worth logging: a profile change while no pipeline exists (nothing
            // playing, or PASSTHROUGH) silently does nothing until flush().
            android.util.Log.d(TAG, "profile update ignored — no pipeline (mode=${cur.mode})")
            return
        }
        // The downmix pushed to native is DERIVED from the HRTF mode, never
        // picked directly: an active HRTF (built-in or SOFA) means the
        // binaural render runs; HRTF off means the ONE fixed fold matrix
        // runs (per the peqdb Downmix Renderer spec — there is no matrix
        // choice) — the pipeline declines the frame (process() returns -1)
        // and the fixed-matrix fold-down handles it, with headphone shaping
        // left entirely to the built-in AutoEQ chain. Strength 0 alone is
        // not that fold: the room at strength 0 still pans every speaker
        // feed to the ears, so HRTF off has to demote the mode.
        val strength = if (cur.hrtfEnabled) cur.binauralStrength else 0f
        val downmix = if (cur.hrtfEnabled) {
            StereoDownmixMode.BINAURAL
        } else {
            StereoDownmixMode.LO_RO
        }
        // The speaker render replaces the stereo paths entirely, so the "HRTF
        // off = passthrough" lockstep must not switch it off.
        val mode = if (activeLayout.isMultichannel && cur.mode == RendererMode.PASSTHROUGH) {
            RendererMode.OBJECT_RENDER
        } else {
            cur.mode
        }
        // The room first: the render params below hand it the strength as well.
        AtmosNative.nativeSetRoomAmount(p, cur.roomAmount)
        val envelope = cur.panEnvelope.clamped()
        AtmosNative.nativeSetPanEnvelope(
            p, envelope.enabled, envelope.ceilingWidth, envelope.ceilingDepth, envelope.curve,
        )
        AtmosNative.nativeSetRenderParams(
            p,
            mode.ordinal,
            downmix.ordinal,
            strength,
            cur.heightVirtualization,
            cur.lfeGainDb,
            cur.bassManagement,
            cur.crossoverHz,
            cur.drc.ordinal,
            cur.dialogNormalization,
        )
        applySofa(p)
        applyRoom()
        applyEar()
        android.util.Log.i(
            TAG,
            "params -> mode=$mode layout=${activeLayout.label} downmix=$downmix " +
                "strength=$strength height=${cur.heightVirtualization} " +
                "lfe=${cur.lfeGainDb}dB bass=${cur.bassManagement}@${cur.crossoverHz}Hz " +
                "drc=${cur.drc} dialnorm=${cur.dialogNormalization} " +
                "hrtf=${when {
                    !cur.hrtfEnabled -> "off (AutoEQ only)"
                    sofaBytes != null -> "custom"
                    else -> "built-in"
                }} room=15.1.12 @ ${cur.roomAmount}",
        )
    }

    /**
     * Largest channel count the current HDMI/USB output reports, or null for
     * none / built-in outputs. Pushed by PlaybackService from an
     * AudioDeviceCallback; read in configure().
     */
    @Volatile var deviceChannelCount: Int? = null

    /** The layout the processor currently outputs; STEREO = binaural/fold path. */
    @Volatile var activeLayout: ChannelLayout = ChannelLayout.STEREO
        private set
    @Volatile private var pendingLayout: ChannelLayout = ChannelLayout.STEREO

    /**
     * The layout the AudioTrack being built is for. A stream's first track is
     * built before the flush that makes its layout the active one, so the
     * configured layout counts while one is waiting: read as [activeLayout]
     * alone, 5.1.2, 7.1.2 and 9.1.x got the mask Media3 derives from the
     * channel count, the wrong speakers, until a seek.
     */
    val layoutForTrack: ChannelLayout
        get() = if (pendingFormat != AudioFormat.NOT_SET) pendingLayout else activeLayout
    private var speakerOut = FloatArray(0)      // one frame, layout speaker order
    private var sinkFrame = FloatArray(0)       // one frame, sink slot order (padded)
    private var speakerSlots = IntArray(0)

    private var pipeline: Long = 0L
    @Volatile private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Optional LFE low-pass for the fallback fold (profile.lfeLowpass):
    // configured lazily to the stream rate on first use, reset on flush.
    private val lfeFold = tf.monochrome.android.audio.dsp.LfeLowPassFilter()
    private var lfeFoldRate = 0

    // Interleaved bed accumulation (grows to a few frames, reused).
    private var bed = FloatArray(0)
    private var bedSamples = 0
    private var frameScratch = FloatArray(0)                    // one frame, interleaved
    private val stereo = FloatArray(2 * FRAME_SAMPLES)          // native render output
    private val emptyFrame = ByteArray(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Only multichannel (an Atmos bed) is handled; ≤2ch is not Atmos. The
        // profile's PASSTHROUGH mode ("Direct") also drops us out of the
        // pipeline entirely, so the user's choice is honoured bit-perfectly.
        val speakers = profile.speakerLayout(deviceChannelCount).let {
            // Before API 32 Media3 rejects a 24-channel sink (no mask for it),
            // so 9.1.x falls back to the largest layout that still fits.
            if (android.os.Build.VERSION.SDK_INT < 32 && it.sinkChannelCount == 24) ChannelLayout.ATMOS_7_1_4 else it
        }
        val why = when {
            inputAudioFormat.channelCount <= 2 -> "stereo or mono source"
            !AtmosNative.isAvailable -> "native renderer not loaded"
            profile.mode == RendererMode.PASSTHROUGH && !speakers.isMultichannel ->
                "binaural render off (hrtf=${profile.hrtfEnabled} auto=${savedProfile.hrtfAuto} headphones=$headphones)"
            else -> null
        }
        if (inputAudioFormat.channelCount > 2 || why == null) {
            android.util.Log.i(
                TAG,
                "configure: in=${inputAudioFormat.channelCount}ch ${inputAudioFormat.sampleRate}Hz " +
                    "enc=${inputAudioFormat.encoding} device=${deviceChannelCount}ch layout=${speakers.label} -> " +
                    (why?.let { "OUT of the chain: $it" } ?: "IN the chain (mode=${profile.mode})"),
            )
        }
        if (inputAudioFormat.channelCount <= 2 || !AtmosNative.isAvailable) {
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            pendingLayout = ChannelLayout.STEREO
            activeLayout = ChannelLayout.STEREO
            pendingWatching = false
            return AudioFormat.NOT_SET
        }
        if (profile.mode == RendererMode.PASSTHROUGH && !speakers.isMultichannel) {
            // Not rendering: the sound goes on as it came, sample for sample,
            // and this stage only watches the stream's metadata go by, so the
            // Atmos page can still draw where the objects are and how they
            // move.
            pendingFormat = inputAudioFormat
            pendingLayout = ChannelLayout.STEREO
            pendingWatching = true
            return inputAudioFormat
        }
        pendingWatching = false
        pendingFormat = inputAudioFormat
        // Takes effect at flush(), like the format: the previous stream may
        // still be draining through queueInput until then. DefaultAudioSink
        // flushes the processors before it builds the new AudioTrack, so the
        // track provider sees the new layout in time.
        pendingLayout = speakers
        val outChannels = if (speakers.isMultichannel) speakers.sinkChannelCount else 2
        return AudioFormat(inputAudioFormat.sampleRate, outChannels, inputAudioFormat.encoding)
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    /**
     * Whether the stream just configured goes through this stage, for the
     * stages after it (read in their own configure, which follows this one's).
     */
    val rendersConfiguredStream: Boolean get() = pendingFormat != AudioFormat.NOT_SET && !pendingWatching

    // Watching: in the chain only to read object metadata, the sound untouched.
    @Volatile private var pendingWatching = false
    private var watching = false
    private var watchedSamples = 0

    /** The sound on as it came, and a frame's metadata read for every frame of it. */
    private fun watch(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) { outputBuffer = AudioProcessor.EMPTY_BUFFER; return }
        val frameBytes = (if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2) * inputFormat.channelCount
        val out = acquireOutput(bytes)
        out.put(inputBuffer)
        out.flip()
        outputBuffer = out
        watchedSamples += bytes / frameBytes
        while (watchedSamples >= FRAME_SAMPLES) {
            watchedSamples -= FRAME_SAMPLES
            val raw = nextRawFrame()
            val count = if (pipeline != 0L) {
                AtmosNative.nativeTrackObjects(pipeline, raw, meterScratch).coerceIn(0, MAX_OBJECTS)
            } else {
                0
            }
            logMetadata()
            takeMeter(count)
            takeSpeakers(tracked = true)
        }
    }

    /**
     * What decides, when a stream is configured, whether this stage is in the
     * chain and what it puts out. A change to it means nothing to a stream
     * already playing until the chain is configured again.
     */
    fun configurationKey(profile: RendererProfile, deviceChannels: Int?, headphones: Boolean): Any =
        profile.effective(headphones).let {
            listOf(it.mode == RendererMode.PASSTHROUGH, it.speakerLayout(deviceChannels))
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputFormat == AudioFormat.NOT_SET) return
        if (watching) { watch(inputBuffer); return }
        val channels = inputFormat.channelCount
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        val inFrames = inputBuffer.remaining() / frameBytes
        if (inFrames == 0) { outputBuffer = AudioProcessor.EMPTY_BUFFER; return }

        // Append input to the interleaved bed accumulation as float.
        ensureBed((bedSamples + inFrames) * channels)
        val start = inputBuffer.position()
        var w = bedSamples * channels
        if (isFloat) {
            for (i in 0 until inFrames * channels) bed[w++] = inputBuffer.getFloat(start + i * 4)
        } else {
            for (i in 0 until inFrames * channels) bed[w++] = inputBuffer.getShort(start + i * 2) / 32768f
        }
        inputBuffer.position(start + inFrames * frameBytes)
        bedSamples += inFrames

        val outFrames = bedSamples / FRAME_SAMPLES
        if (outFrames == 0) { outputBuffer = AudioProcessor.EMPTY_BUFFER; return }

        if (activeLayout.isMultichannel) {
            queueSpeakerFrames(outFrames, channels, isFloat, bytesPerSample)
            return
        }

        val outBytes = outFrames * FRAME_SAMPLES * 2 * bytesPerSample
        val out = acquireOutput(outBytes)
        if (frameScratch.size < FRAME_SAMPLES * channels) frameScratch = FloatArray(FRAME_SAMPLES * channels)

        for (f in 0 until outFrames) {
            System.arraycopy(bed, f * FRAME_SAMPLES * channels, frameScratch, 0, FRAME_SAMPLES * channels)
            val raw = nextRawFrame()
            val rc = if (pipeline != 0L) {
                AtmosNative.nativeProcessFrame(pipeline, raw, frameScratch, channels, FRAME_SAMPLES, stereo)
            } else -1
            // The native side answers 1 for its bed fallback too (a stream
            // with no JOC, or none yet): only its object count says which it
            // was.
            val objects = if (rc == 1) AtmosNative.nativeLastObjectCount(pipeline) else 0
            noteOutcome(
                when {
                    rc != 1 -> Outcome.BED_FOLDED
                    objects > 0 -> Outcome.OBJECTS_ROOM
                    else -> Outcome.BED_ROOM
                }
            )
            noteFrame(objects)
            if (rc == 1) {
                if (renderedFrames == 0L) {
                    android.util.Log.i(TAG, "Atmos render ACTIVE (${channels}ch bed, $objects objects)")
                }
                renderedFrames++
            } else {
                downmixToStereo(frameScratch, channels)  // non-Atmos / no pipeline / no raw frame
                fallbackFrames++
            }
            if ((renderedFrames + fallbackFrames) % STATS_EVERY == 0L) {
                android.util.Log.i(
                    TAG,
                    "frames rendered=$renderedFrames fallback=$fallbackFrames objects=$objects outcome=$lastOutcome " +
                        "tapQueue=${frameBuffer.size()} gotFrame=$gotFrameCount " +
                        "noFrame=$noFrameCount lastFrameBytes=$lastFrameBytes " +
                        "anchorUs=$anchorUs")
            }
            writeFrame(out, isFloat, stereo, 2 * FRAME_SAMPLES)
        }
        publish(out, outFrames, channels)
    }

    /** Hands [out] on and keeps the part of a frame that is left over for the next call. */
    private fun publish(out: ByteBuffer, outFrames: Int, channels: Int) {
        // Absolute puts don't move position; set the window explicitly.
        out.limit(outWritePos)
        out.position(0)
        outputBuffer = out
        val consumed = outFrames * FRAME_SAMPLES
        val remain = bedSamples - consumed
        if (remain > 0) System.arraycopy(bed, consumed * channels, bed, 0, remain * channels)
        bedSamples = remain
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        // Out of the chain for this stream (stereo, Direct mode, no
        // renderer): nothing to report until frames say otherwise.
        if (pendingFormat == AudioFormat.NOT_SET && inputFormat == AudioFormat.NOT_SET) {
            noteOutcome(null)
            noteObjects(0)
            meterCount = 0
            forgetMix()
        }
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        bedSamples = 0
        lfeFold.reset()  // no pre-seek audio in the LFE filter / dry delay
        // The tapped frames are NOT dropped here. This flush also runs when a
        // stream is first configured, at its first decoded buffer, by when
        // the decoder has been handed frames ahead: dropping them anchored
        // the clock on a later frame, and every object's metadata ran that
        // far ahead of its audio for the rest of the song. The tap drops its
        // own frames when playback really jumps (AtmosFrameBuffer.put, and a
        // seek in the tapped period).
        anchorUs = Long.MIN_VALUE
        anchorSamples = 0L
        lastRaw = null
        if (pendingFormat == AudioFormat.NOT_SET) {
            // A seek within the same format flushes without reconfiguring:
            // clear the native time history so no pre-seek audio leaks out of
            // the latency-matching delay or the convolution tails.
            if (pipeline != 0L) AtmosNative.nativePipelineFlush(pipeline)
            return
        }
        forgetMix()  // a new stream, or another part of this one: count afresh
        android.util.Log.i(
            TAG,
            "flush: applying ${pendingFormat.channelCount}ch ${pendingFormat.sampleRate}Hz layout=${pendingLayout.label} " +
                "tapQueue=${frameBuffer.size()} tapOldestUs=${frameBuffer.peekTimeUs()}",
        )
        val formatChanged = inputFormat == AudioFormat.NOT_SET ||
            inputFormat.sampleRate != pendingFormat.sampleRate
        inputFormat = pendingFormat
        watching = pendingWatching
        watchedSamples = 0
        if (watching) { noteOutcome(null); noteObjects(0) }
        val layout = pendingLayout
        activeLayout = layout
        if (formatChanged) {
            synchronized(pipelineLock) {
                if (pipeline != 0L) AtmosNative.nativePipelineDestroy(pipeline)
                pipeline = AtmosNative.nativePipelineCreate(inputFormat.sampleRate, MAX_OBJECTS)
                sofaApplied = false  // a fresh pipeline has the baked HRTF; re-apply any custom one
                roomApplied = false  // and its own room
                appliedEar = null    // and the set's own ears
            }
        } else if (pipeline != 0L) {
            AtmosNative.nativePipelineFlush(pipeline)
        }
        if (layout.isMultichannel) {
            // Off the per-frame path: sizes the native renderer and our buffers once.
            if (pipeline != 0L) AtmosNative.nativeSetOutputLayout(pipeline, layout.nativeId)
            speakerOut = FloatArray(layout.channelCount * FRAME_SAMPLES)
            sinkFrame = FloatArray(layout.sinkChannelCount * FRAME_SAMPLES)
            speakerSlots = layout.speakerSlots()
        }
        pushParams()  // (re)apply the profile: fresh pipeline, or the mode the layout needs
        pendingFormat = AudioFormat.NOT_SET
    }

    override fun reset() {
        flush()
        synchronized(pipelineLock) {
            if (pipeline != 0L) { AtmosNative.nativePipelineDestroy(pipeline); pipeline = 0L }
        }
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        pendingLayout = ChannelLayout.STEREO
        activeLayout = ChannelLayout.STEREO
        bed = FloatArray(0)
        bedSamples = 0
        renderedFrames = 0L
        fallbackFrames = 0L
    }

    // ── helpers ──────────────────────────────────────────────────────────

    // Time-keyed frame association state. The raw frames are matched to the
    // decoded PCM by presentation time, not by arrival order: an anchor is taken
    // from the first tapped frame after a flush, and each 1536-sample output
    // chunk advances an anchored clock that keys frameForTime. Ordering-based
    // poll() assumed a strict 1:1 tap↔decode correspondence, which silently
    // misaligns metadata if the decoder ever drops or merges an access unit.
    private var anchorUs = Long.MIN_VALUE  // presentation time of output sample 0
    private var anchorSamples = 0L         // samples output since the anchor
    private var lastRaw: ByteArray? = null

    /**
     * The raw E-AC-3 frame covering the next [FRAME_SAMPLES] output samples, or
     * the empty frame when none applies — the native metadata hold then renders
     * with the last-seen JOC/OAMD, which is the DD+ "hold until update" rule.
     */
    private fun nextRawFrame(): ByteArray {
        val rate = inputFormat.sampleRate.toLong()
        if (anchorUs == Long.MIN_VALUE) {
            val t = frameBuffer.peekTimeUs()
            if (t == Long.MIN_VALUE) { noFrameCount++; return emptyFrame }  // tap not started yet
            android.util.Log.i(TAG, "tap clock anchored at ${t}us (queue ${frameBuffer.size()})")
            anchorUs = t
            anchorSamples = 0L
        }
        val expectedUs = anchorUs + anchorSamples * 1_000_000L / rate
        anchorSamples += FRAME_SAMPLES
        // Query at the chunk's midpoint so container timestamp jitter in either
        // direction still resolves to the covering frame.
        val slackUs = FRAME_SAMPLES * 1_000_000L / (2 * rate)
        val entry = frameBuffer.frameForTime(expectedUs + slackUs)
        if (entry == null) {
            // Every buffered frame is newer than the anchored clock (the ring
            // overflowed, or the stream jumped). Drop the anchor; the next chunk
            // re-anchors on whatever the tap holds then.
            anchorUs = Long.MIN_VALUE
            noFrameCount++
            return emptyFrame
        }
        // A gapless track change never flushes the sink, but restarts the tapped
        // timestamps — re-anchor the clock on the entry instead of drifting.
        if (entry.timeUs - expectedUs > RESYNC_US || expectedUs - entry.timeUs > RESYNC_US) {
            android.util.Log.i(TAG, "tap clock re-anchored: expected ${expectedUs}us, frame at ${entry.timeUs}us")
            anchorUs = entry.timeUs
            anchorSamples = FRAME_SAMPLES.toLong()
        }
        gotFrameCount++
        lastFrameBytes = entry.bytes.size
        // The same frame can cover consecutive queries around a boundary; pass
        // the empty frame for the repeat so the EMDF isn't re-parsed (the native
        // hold keeps rendering with it).
        if (entry.bytes === lastRaw) return emptyFrame
        lastRaw = entry.bytes
        return entry.bytes
    }

    // ── speaker output ───────────────────────────────────────────────────

    /** Renders [outFrames] frames to [activeLayout] and publishes the output. */
    private fun queueSpeakerFrames(outFrames: Int, channels: Int, isFloat: Boolean, bytesPerSample: Int) {
        val layout = activeLayout
        val sinkChannels = layout.sinkChannelCount
        val out = acquireOutput(outFrames * FRAME_SAMPLES * sinkChannels * bytesPerSample)
        if (frameScratch.size < FRAME_SAMPLES * channels) frameScratch = FloatArray(FRAME_SAMPLES * channels)
        for (f in 0 until outFrames) {
            System.arraycopy(bed, f * FRAME_SAMPLES * channels, frameScratch, 0, FRAME_SAMPLES * channels)
            val raw = nextRawFrame()
            val rc = if (pipeline != 0L) {
                AtmosNative.nativeProcessFrameSpeakers(
                    pipeline, raw, frameScratch, channels, FRAME_SAMPLES, speakerOut, layout.channelCount,
                )
            } else -1
            val objects = if (rc == 1) AtmosNative.nativeLastObjectCount(pipeline) else 0
            noteOutcome(if (objects > 0) Outcome.OBJECTS_SPEAKERS else Outcome.BED_SPEAKERS)
            noteFrame(objects)
            if (rc == 1) {
                if (renderedFrames == 0L) {
                    android.util.Log.i(TAG, "Atmos render ACTIVE — ${layout.label} speakers (${channels}ch bed, $objects objects)")
                }
                renderedFrames++
            } else {
                bedToSpeakers(frameScratch, channels, layout)  // no pipeline: play the bed on the layout
                fallbackFrames++
            }
            toSinkOrder(layout)
            writeFrame(out, isFloat, sinkFrame, sinkChannels * FRAME_SAMPLES)
        }
        publish(out, outFrames, channels)
    }

    /** Scatters [speakerOut] (speaker order) into the padded sink frame. */
    private fun toSinkOrder(layout: ChannelLayout) {
        val speakers = layout.channelCount
        val sink = layout.sinkChannelCount
        if (speakers == sink) {
            System.arraycopy(speakerOut, 0, sinkFrame, 0, speakers * FRAME_SAMPLES)
            return
        }
        java.util.Arrays.fill(sinkFrame, 0, sink * FRAME_SAMPLES, 0f)
        for (i in 0 until FRAME_SAMPLES) {
            val src = i * speakers
            val dst = i * sink
            for (c in 0 until speakers) sinkFrame[dst + speakerSlots[c]] = speakerOut[src + c]
        }
    }

    /**
     * Pipeline-less fallback: the decoded bed placed on the layout's matching
     * speakers. A 5.1 bed is FL FR FC LFE SL SR: its surrounds go onto a 7.x
     * layout's sides. A 7.1 bed is FL FR FC LFE BL BR SL SR, the rears before
     * the sides (the decoder's order, and [ChannelDetectorProcessor]'s): on a
     * 5.x layout both pairs share its surrounds.
     */
    private fun bedToSpeakers(frame: FloatArray, channels: Int, layout: ChannelLayout) {
        val speakers = layout.channelCount
        java.util.Arrays.fill(speakerOut, 0, speakers * FRAME_SAMPLES, 0f)
        val fiveX = layout.speakers().getOrNull(4)?.label == "Ls"
        // Speaker-order index for each decoder bed channel.
        // 7.x layouts are L R C LFE Lrs Rrs Lss Rss, 5.x ones L R C LFE Ls Rs.
        val target = when {
            fiveX -> intArrayOf(0, 1, 2, 3, 4, 5, 4, 5)
            channels >= 8 -> intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
            else -> intArrayOf(0, 1, 2, 3, 6, 7, 4, 5)
        }
        for (i in 0 until FRAME_SAMPLES) {
            val b = i * channels
            val o = i * speakers
            for (c in 0 until minOf(channels, 8)) {
                speakerOut[o + target[c]] += frame[b + c]
            }
        }
    }

    private fun writeFrame(out: ByteBuffer, isFloat: Boolean, src: FloatArray, count: Int) {
        if (isFloat) {
            for (i in 0 until count) { out.putFloat(outWritePos, src[i]); outWritePos += 4 }
        } else {
            for (i in 0 until count) {
                val s = (src[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                out.putShort(outWritePos, s); outWritePos += 2
            }
        }
    }

    private var outputScratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outWritePos = 0

    private fun acquireOutput(bytes: Int): ByteBuffer {
        if (outputScratch.capacity() < bytes) {
            outputScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputScratch.clear()
        }
        outWritePos = 0
        return outputScratch
    }

    // Fold-down for frames the pipeline declines (no JOC / HRTF off /
    // passthrough): the ONE fixed matrix (the peqdb Downmix Renderer's ADC2
    // direct matrix) — sides/backs hard-panned at unity, FC at 0.70710678 to
    // both, LFE at 2.26464431 to both. Channel order follows the decoder's
    // (FL FR FC LFE SL SR BL BR).
    private fun downmixToStereo(frame: FloatArray, channels: Int) {
        val c = channels
        val cur = profile
        // Downmix preamp (--master-gain-db equivalent): one linear gain on
        // the fold output, computed once per 1536-sample frame.
        val preamp = Math.pow(10.0, cur.downmixPreampDb / 20.0).toFloat()
        // Optional LFE path: low-pass the LFE feed and delay the dry fold to
        // match. Lazily (re)configured — the toggle can flip mid-stream.
        val lfeLp = cur.lfeLowpass && c >= 6
        if (lfeLp && lfeFoldRate != inputFormat.sampleRate) {
            lfeFoldRate = inputFormat.sampleRate
            lfeFold.configure(lfeFoldRate)
        }
        for (i in 0 until FRAME_SAMPLES) {
            val b = i * c
            if (c >= 6) {
                val fc = 0.70710678f * frame[b + 2]
                val lfeIn = frame[b + 3]
                // Surround sums per side (SL+BL / SR+BR when 7.1).
                var sl = frame[b + 4]
                var sr = frame[b + 5]
                if (c >= 8) {
                    sl += frame[b + 6]
                    sr += frame[b + 7]
                }
                var l = frame[b] + fc + sl
                var r = frame[b + 1] + fc + sr
                if (lfeLp) {
                    val f = 2.26464431f * lfeFold.filterLfe(lfeIn)
                    l = lfeFold.delayDryL(l) + f
                    r = lfeFold.delayDryR(r) + f
                } else {
                    l += 2.26464431f * lfeIn
                    r += 2.26464431f * lfeIn
                }
                stereo[2 * i] = l * preamp
                stereo[2 * i + 1] = r * preamp
            } else {
                stereo[2 * i] = frame[b] * preamp
                stereo[2 * i + 1] = (if (c > 1) frame[b + 1] else frame[b]) * preamp
            }
        }
    }

    private fun ensureBed(floats: Int) {
        if (bed.size < floats) {
            val grown = FloatArray(floats)
            System.arraycopy(bed, 0, grown, 0, bedSamples * (inputFormat.channelCount.coerceAtLeast(1)))
            bed = grown
        }
    }

    // Diagnostics: how many frames actually rendered as Atmos vs fell back to a
    // downmix. The first successful render logs once; totals log periodically, so
    // a logcat during playback shows immediately whether the tap + pipeline are
    // working end to end.
    private var renderedFrames = 0L
    private var fallbackFrames = 0L
    // Split the fallback cause: no raw frame available (tap/consumer mismatch)
    // vs. a frame that simply carries no JOC.
    private var gotFrameCount = 0L
    private var noFrameCount = 0L
    private var lastFrameBytes = 0

    internal companion object {
        const val FRAME_SAMPLES = 1536   // 6 blocks * 256 samples per E-AC-3 frame
        const val MAX_OBJECTS = 16
        // The direction the ear tuner's graph shows: the layout's top front left speaker.
        const val EAR_CURVE_AZ = -45f
        const val EAR_CURVE_EL = 60f
        // A step of the position's coding is 1/62 of the room: past this it moved.
        const val MOVED = 0.02f

        /** What a held meter peak keeps of itself each 33 ms: about 29 dB a second. */
        const val METER_FALL = 0.896f
        const val PLACES_FIRST = 62    // 2 s of frames
        const val PLACES_EVERY = 312   // 10 s
        const val TAG = "AtmosProcessor"
        const val STATS_EVERY = 300L     // ~9.6 s of frames
        // Anchored-clock vs tapped-timestamp divergence that forces a re-anchor.
        // Jitter is sub-millisecond; only a real discontinuity crosses this.
        const val RESYNC_US = 250_000L
    }
}

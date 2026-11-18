package tf.monochrome.android.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.HeadphoneAutoEqApi
import tf.monochrome.android.data.api.SquiglinkApi
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.CompactCurve
import tf.monochrome.android.domain.model.Headphone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HeadphoneRepository - Aggregates headphone measurements from the AutoEq
 * GitHub repo and twelve squig.link CrinGraph instances. Each measurement
 * carries its origin source and acoustic rig so the UI can group/filter.
 *
 * Measurements saved for offline use ([HeadphoneOfflineStore]) are read
 * first: they are the same curves, with no network and no wait.
 */
@Singleton
class HeadphoneRepository @Inject constructor(
    private val autoEqApi: HeadphoneAutoEqApi,
    private val squiglinkApi: SquiglinkApi,
    private val offline: HeadphoneOfflineStore,
) {
    /**
     * Get all available headphones as a Flow. Both sources are queried; their
     * measurement lists are merged so the same physical headphone appears
     * once with measurements from every source that covers it. A source that
     * cannot be reached is taken from the list saved for offline use, if any.
     */
    fun getAllHeadphones(): Flow<List<Headphone>> = flow {
        try {
            val auto = autoEqApi.fetchHeadphones().getOrDefault(emptyList())
            val squig = squiglinkApi.fetchHeadphones().getOrDefault(emptyList())
            val autoOrSaved = auto.ifEmpty {
                withContext(Dispatchers.IO) { offline.indexPart { it.target != SQUIGLINK } }
            }
            val squigOrSaved = squig.ifEmpty {
                withContext(Dispatchers.IO) { offline.indexPart { it.target == SQUIGLINK } }
            }
            emit(mergeHeadphonesByName(autoOrSaved + squigOrSaved))
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    private suspend fun saved(measurement: AutoEqMeasurement): HeadphoneOfflineStore.StoredMeasurement? =
        withContext(Dispatchers.IO) { offline.read(measurement) }

    /**
     * Fetch the raw frequency-response text for a specific measurement.
     * Dispatches by `target` to the appropriate API; returns null on miss.
     */
    suspend fun fetchMeasurementText(measurement: AutoEqMeasurement): String? {
        saved(measurement)?.single?.let { return CompactCurve.toCsv(it) }
        return fetchMeasurementTextOnline(measurement)
    }

    private suspend fun fetchMeasurementTextOnline(measurement: AutoEqMeasurement): String? = when (measurement.target) {
        SQUIGLINK -> squiglinkApi.fetchMeasurementText(measurement.host, measurement.fileName)
        // Fetch the exact source folder the user picked so a headphone's
        // Rtings entry loads Rtings data even when oratory1990/crinacle also
        // measured it.
        else -> autoEqApi.fetchMeasurementByPath(measurement.path, measurement.fileName).getOrNull()
    }

    /**
     * Fetch one specific channel ("L"/"R") of a measurement. Only squig.link
     * publishes per-channel files; other sources return null and callers fall
     * back to the single-file path. Null also means "this channel isn't
     * published" — deliberately NOT papered over with the other channel.
     */
    suspend fun fetchMeasurementChannelText(measurement: AutoEqMeasurement, channel: String): String? {
        saved(measurement)?.channel(channel)?.let { return CompactCurve.toCsv(it.curve) }
        return when (measurement.target) {
            SQUIGLINK -> squiglinkApi.fetchMeasurementChannelText(
                measurement.host, measurement.fileName, channel,
            )
            else -> null
        }
    }

    /** Channel text + the sample name that actually answered ("L"/"L1"). */
    suspend fun fetchMeasurementChannel(
        measurement: AutoEqMeasurement,
        channel: String,
    ): Pair<String, String>? {
        saved(measurement)?.channel(channel)?.let { return CompactCurve.toCsv(it.curve) to it.sample }
        return when (measurement.target) {
            SQUIGLINK -> squiglinkApi.fetchMeasurementChannel(
                measurement.host, measurement.fileName, channel,
            )
            else -> null
        }
    }

    /** One exact sample ("L2", "R3", …); squig.link only. */
    suspend fun fetchMeasurementSampleText(measurement: AutoEqMeasurement, sample: String): String? =
        when (measurement.target) {
            SQUIGLINK -> squiglinkApi.fetchMeasurementSampleText(
                measurement.host, measurement.fileName, sample,
            )
            else -> null
        }

    /** Published sample names for a channel prefix ("L" -> [L1, L2, …]); squig.link only. */
    suspend fun listMeasurementSamples(measurement: AutoEqMeasurement, channelPrefix: String): List<String> =
        when (measurement.target) {
            SQUIGLINK -> squiglinkApi.listSamples(
                measurement.host, measurement.fileName, channelPrefix,
            )
            else -> emptyList()
        }

    /**
     * Search headphones by name
     *
     * @param query Search query
     */
    fun searchHeadphones(query: String): Flow<List<Headphone>> = flow {
        try {
            if (query.isBlank()) {
                emit(emptyList())
                return@flow
            }

            val result = autoEqApi.searchHeadphones(query)
            result.onSuccess { headphones ->
                emit(headphones)
            }.onFailure {
                emit(emptyList())
            }
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    /**
     * Get headphones filtered by type
     *
     * @param type Headphone type: "over-ear", "in-ear", or "earbud"
     */
    fun getHeadphonesByType(type: String): Flow<List<Headphone>> = flow {
        try {
            val result = autoEqApi.getHeadphonesByType(type)
            result.onSuccess { headphones ->
                emit(headphones)
            }.onFailure {
                emit(emptyList())
            }
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    /**
     * Refresh headphone cache across both source APIs. Forces a fresh fetch
     * on the next request.
     */
    fun refreshCache() {
        autoEqApi.clearCache()
        squiglinkApi.clearCache()
    }
}

private const val SQUIGLINK = "squiglink"

/**
 * One row per headphone name, with the measurements of every source that
 * covers it, sorted by name: the list the headphone browser shows, and the
 * one the offline download saves.
 */
internal fun mergeHeadphonesByName(all: List<Headphone>): List<Headphone> =
    all.groupBy { it.name }.map { (name, group) ->
        Headphone(
            id = group.first().id,
            name = name,
            type = group.first().type,
            measurements = group.flatMap { it.measurements },
        )
    }.sortedBy { it.name.lowercase() }

package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tf.monochrome.android.data.api.HeadphoneAutoEqApi
import tf.monochrome.android.data.api.SquiglinkApi
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HeadphoneRepository - Aggregates headphone measurements from the AutoEq
 * GitHub repo and twelve squig.link CrinGraph instances. Each measurement
 * carries its origin source and acoustic rig so the UI can group/filter.
 */
@Singleton
class HeadphoneRepository @Inject constructor(
    private val autoEqApi: HeadphoneAutoEqApi,
    private val squiglinkApi: SquiglinkApi,
) {
    /**
     * Get all available headphones as a Flow. Both sources are queried; their
     * measurement lists are merged so the same physical headphone appears
     * once with measurements from every source that covers it.
     */
    fun getAllHeadphones(): Flow<List<Headphone>> = flow {
        try {
            val auto = autoEqApi.fetchHeadphones().getOrDefault(emptyList())
            val squig = squiglinkApi.fetchHeadphones().getOrDefault(emptyList())
            emit(mergeByName(auto + squig))
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    /**
     * Fetch the raw frequency-response text for a specific measurement.
     * Dispatches by `target` to the appropriate API; returns null on miss.
     */
    suspend fun fetchMeasurementText(measurement: AutoEqMeasurement): String? = when (measurement.target) {
        "squiglink" -> squiglinkApi.fetchMeasurementText(measurement.host, measurement.fileName)
        else -> autoEqApi.fetchHeadphoneMeasurement(
            measurement.path.substringAfterLast('/').replace(' ', '_').lowercase(),
            measurement.fileName,
        ).getOrNull()
    }

    private fun mergeByName(all: List<Headphone>): List<Headphone> =
        all.groupBy { it.name }.map { (name, group) ->
            Headphone(
                id = group.first().id,
                name = name,
                type = group.first().type,
                measurements = group.flatMap { it.measurements },
            )
        }.sortedBy { it.name.lowercase() }

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
     * Load measurement data for a specific headphone
     *
     * @param headphoneId The normalized (lowercased, underscored) id used for cache lookup.
     * @param headphoneName The original-case display name, needed for the
     *   case-sensitive GitHub fallback URLs. Defaults to the id when unknown.
     */
    fun loadHeadphoneMeasurement(
        headphoneId: String,
        headphoneName: String = ""
    ): Flow<Result<String>> = flow {
        try {
            val result = autoEqApi.fetchHeadphoneMeasurement(headphoneId, headphoneName)
            emit(result)
        } catch (e: Exception) {
            emit(Result.failure(e))
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

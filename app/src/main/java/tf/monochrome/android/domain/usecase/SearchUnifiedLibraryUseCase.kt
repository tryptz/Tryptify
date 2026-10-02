package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import tf.monochrome.android.data.collections.repository.CollectionRepository
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject
import javax.inject.Singleton

data class UnifiedSearchResult(
    val localTracks: List<UnifiedTrack> = emptyList(),
    val collectionTracks: List<UnifiedTrack> = emptyList(),
    val apiTracks: List<UnifiedTrack> = emptyList()
) {
    val isEmpty: Boolean
        get() = localTracks.isEmpty() && collectionTracks.isEmpty() && apiTracks.isEmpty()
}

@Singleton
class SearchUnifiedLibraryUseCase @Inject constructor(
    private val localMediaRepository: LocalMediaRepository,
    private val collectionRepository: CollectionRepository
) {
    fun search(query: String): Flow<UnifiedSearchResult> {
        if (query.isBlank()) return flowOf(UnifiedSearchResult())

        val localFlow = localMediaRepository.searchTracks(query)
        val collectionFlow = collectionRepository.searchTracks(query)

        return combine(localFlow, collectionFlow) { local, collection ->
            UnifiedSearchResult(
                localTracks = local,
                collectionTracks = collection
            )
        }
    }
}

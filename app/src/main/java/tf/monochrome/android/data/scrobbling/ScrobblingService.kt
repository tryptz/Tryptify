package tf.monochrome.android.data.scrobbling

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrobblingService @Inject constructor(
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager
) {
    companion object {
        private const val LISTENBRAINZ_API_URL = "https://api.listenbrainz.org/1/submit-listens"
    }

    /**
     * The listener's Last.fm application credentials, or null if they haven't
     * set any up.
     *
     * These used to be `"dummy_api_key"` / `"dummy_secret"` constants, which
     * meant every scrobble this app ever sent was signed with a fake secret and
     * silently rejected — the enclosing `try` swallowed the failure, so
     * scrobbling appeared to work and never had. A key is per-application and
     * can't be shipped in a FOSS client without handing every install the same
     * revocable credential, so it comes from settings instead.
     */
    private data class LastFmCredentials(val apiKey: String, val apiSecret: String)

    private suspend fun lastFmCredentials(): LastFmCredentials? {
        val apiKey = preferences.lastFmApiKey.first()
        val apiSecret = preferences.lastFmApiSecret.first()
        if (apiKey.isBlank() || apiSecret.isBlank()) return null
        return LastFmCredentials(apiKey, apiSecret)
    }

    suspend fun updateNowPlaying(track: Track) {
        val lastFmEnabled = preferences.lastFmEnabled.first()
        val listenBrainzEnabled = preferences.listenBrainzEnabled.first()

        if (lastFmEnabled) {
            updateLastFmNowPlaying(track)
        }

        if (listenBrainzEnabled) {
            submitListenBrainz(track, "playing_now")
        }
    }

    suspend fun scrobbleTrack(track: Track, timestampUnix: Long = System.currentTimeMillis() / 1000) {
        val lastFmEnabled = preferences.lastFmEnabled.first()
        val listenBrainzEnabled = preferences.listenBrainzEnabled.first()

        if (lastFmEnabled) {
            scrobbleLastFmTrack(track, timestampUnix)
        }

        if (listenBrainzEnabled) {
            submitListenBrainz(track, "single")
        }
    }

    private suspend fun updateLastFmNowPlaying(track: Track) {
        val sessionKey = preferences.lastFmSessionKey.first() ?: return
        val credentials = lastFmCredentials() ?: return

        try {
            val params = mapOf(
                "method" to "track.updateNowPlaying",
                "api_key" to credentials.apiKey,
                "sk" to sessionKey,
                "track" to track.title,
                "artist" to (track.artist?.name ?: "Unknown Artist")
            ).toMutableMap()
            
            track.album?.title?.let { params["album"] = it }
            
            val sig = LastFmSigning.sign(params, credentials.apiSecret)
            params["api_sig"] = sig
            params["format"] = "json"
            
            val response = httpClient.post(LastFmSigning.API_URL) {
                setBody(FormDataContent(Parameters.build {
                    params.forEach { (k, v) -> append(k, v) }
                }))
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun scrobbleLastFmTrack(track: Track, timestampUnix: Long) {
        val sessionKey = preferences.lastFmSessionKey.first() ?: return
        val credentials = lastFmCredentials() ?: return

        try {
            val params = mapOf(
                "method" to "track.scrobble",
                "api_key" to credentials.apiKey,
                "sk" to sessionKey,
                "timestamp[0]" to timestampUnix.toString(),
                "track[0]" to track.title,
                "artist[0]" to (track.artist?.name ?: "Unknown Artist")
            ).toMutableMap()
            
            track.album?.title?.let { params["album[0]"] = it }
            
            val sig = LastFmSigning.sign(params, credentials.apiSecret)
            params["api_sig"] = sig
            params["format"] = "json"
            
            val response = httpClient.post(LastFmSigning.API_URL) {
                setBody(FormDataContent(Parameters.build {
                    params.forEach { (k, v) -> append(k, v) }
                }))
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun submitListenBrainz(track: Track, listenType: String) {
        val token = preferences.listenBrainzToken.first() ?: return
        
        try {
            val payload = buildJsonObject {
                put("listen_type", listenType)
                put("payload", buildJsonArray {
                    add(buildJsonObject {
                        put("listened_at", System.currentTimeMillis() / 1000)
                        put("track_metadata", buildJsonObject {
                            put("artist_name", track.artist?.name ?: "Unknown Artist")
                            put("track_name", track.title)
                            track.album?.title?.let { put("release_name", it) }
                        })
                    })
                })
            }
            
            val response = httpClient.post(LISTENBRAINZ_API_URL) {
                header("Authorization", "Token $token")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        } catch (_: Exception) {
        }
    }
}

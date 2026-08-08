package tf.monochrome.android.data.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/** A release on GitHub that is newer than the installed build. */
data class AvailableUpdate(
    val versionName: String,
    val releaseUrl: String,
)

/**
 * Checks GitHub Releases for a build newer than this one.
 *
 * The app isn't distributed through a store, so nothing tells a user a new
 * version exists — this does, by reading the repository's latest release and
 * comparing its tag against [BuildConfig.VERSION_NAME].
 *
 * Rate-limited to one network call a day and cached in preferences, for two
 * reasons: GitHub's unauthenticated API allows 60 requests an hour per IP and
 * this ships with no token, and an update notice is not worth a request every
 * time someone opens the app to play a song. A failed check is silent — a user
 * offline or on a captive portal should see nothing at all, not an error.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The newest release if it's ahead of this build, else null.
     *
     * Serves the cached answer until it goes stale, so this is cheap to call
     * on every app start. Pass [force] to check regardless — that's the
     * "Check for updates" button, where the user is waiting on an answer.
     */
    suspend fun check(force: Boolean = false, nowMs: Long): AvailableUpdate? =
        withContext(Dispatchers.IO) {
            val lastChecked = preferences.updateLastCheckedAt.first()
            val stale = nowMs - lastChecked >= CHECK_INTERVAL_MS
            if (!force && !stale) return@withContext cachedUpdate()

            val fetched = runCatching { fetchLatest() }.getOrNull()
            if (fetched == null) {
                // Record the attempt anyway, so a repeatedly failing check
                // (no network, rate limited) doesn't retry on every launch.
                preferences.setUpdateLastCheckedAt(nowMs)
                return@withContext cachedUpdate()
            }

            preferences.setUpdateLastCheckedAt(nowMs)
            preferences.setUpdateLatestVersion(fetched.versionName)
            preferences.setUpdateLatestUrl(fetched.releaseUrl)
            fetched.takeIf { AppVersion.isNewer(it.versionName, BuildConfig.VERSION_NAME) }
        }

    /** The last known result, without any network access. */
    suspend fun cachedUpdate(): AvailableUpdate? {
        val version = preferences.updateLatestVersion.first() ?: return null
        val url = preferences.updateLatestUrl.first() ?: return null
        if (!AppVersion.isNewer(version, BuildConfig.VERSION_NAME)) return null
        return AvailableUpdate(version, url)
    }

    private suspend fun fetchLatest(): AvailableUpdate? {
        val response = httpClient.get(LATEST_RELEASE_URL) {
            // GitHub asks for an explicit API version and a User-Agent;
            // without the latter it answers 403.
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", "Tryptify/${BuildConfig.VERSION_NAME}")
        }
        if (!response.status.isSuccess()) return null

        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        // A draft or prerelease shouldn't be pushed at everyone; /latest already
        // excludes drafts, but check anyway in case the endpoint changes.
        val prerelease = root["prerelease"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        if (prerelease == true) return null

        val tag = root["tag_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val url = root["html_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: RELEASES_PAGE_URL
        return AvailableUpdate(versionName = tag.trim().removePrefix("v"), releaseUrl = url)
    }


    private companion object {
        // Same shape as HeadphoneAutoEqApi: the repo appears once.
        const val REPO_OWNER = "tryptz"
        const val REPO_NAME = "Tryptify"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        const val RELEASES_PAGE_URL =
            "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

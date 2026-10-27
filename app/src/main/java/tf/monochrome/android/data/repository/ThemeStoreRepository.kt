package tf.monochrome.android.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePackCodec
import tf.monochrome.android.domain.model.ThemePart
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A pack as the store lists it: everything but the pack itself. */
data class StorePack(
    val id: String,
    val authorId: String,
    val authorName: String,
    val name: String,
    val description: String,
    val parts: Set<ThemePart>,
    val accent: Int,
    val background: Int,
    val listed: Boolean,
    val shareCode: String,
    val likes: Int,
    val downloads: Int,
    val createdAt: String,
    /** A short hash of the pack's store picture, or null when it has none. */
    val snapshot: String? = null,
)

/** How the store lists packs. */
enum class StoreSort { POPULAR, NEW, MINE }

/** Why a store call failed, in the terms the screen speaks. */
sealed class StoreError(message: String) : Exception(message) {
    /** No account, or the session ran out and would not refresh. */
    class SignedOut : StoreError("signed out")

    /** The store's server is up but has no database to keep packs in. */
    class NotSetUp : StoreError("store not set up")

    /** Fifty packs already. */
    class LimitReached : StoreError("limit reached")

    /** The pack is not a theme pack, or it is too big. */
    class BadPack : StoreError("bad pack")

    /** No such pack: its author deleted it. */
    class Gone : StoreError("gone")

    class Network(cause: Throwable) : StoreError(cause.message ?: "network")
}

/** A pack as the server sends it. The keys are its columns. */
@Serializable
internal data class WirePack(
    val id: String = "",
    val user_id: String = "",
    val author_name: String = "",
    val name: String = "",
    val description: String = "",
    val parts: List<String> = emptyList(),
    val accent: Int = 0,
    val background: Int = 0,
    val pack: String? = null,
    val listed: Boolean = true,
    val share_code: String = "",
    val likes: Int = 0,
    val downloads: Int = 0,
    val created_at: String = "",
    val snapshot: String? = null,
)

@Serializable
private data class WritePack(
    val author_name: String,
    val name: String,
    val description: String,
    val parts: List<String>,
    val accent: Int,
    val background: Int,
    val pack: String,
    val listed: Boolean,
)

@Serializable
private data class WriteListed(val listed: Boolean)

@Serializable
private data class WriteReport(val reason: String)

@Serializable
private data class LikedIds(val ids: List<String> = emptyList())

@Serializable
private data class SnapshotSaved(val snapshot: String = "")

/** How the store server's answers read, apart from the network that carries them. */
internal object StoreWire {
    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * The error an answer that was not a success stands for. The server names
     * its own (`{"error":"limit_reached"}`); anything else on the way — a proxy's
     * 502, a page from the host — is the network's, whatever its status.
     */
    fun errorOf(status: Int, body: String): StoreError {
        val code = runCatching {
            lenient.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when (code) {
            "signed_out" -> StoreError.SignedOut()
            "limit_reached" -> StoreError.LimitReached()
            "bad_pack", "too_big" -> StoreError.BadPack()
            "not_found" -> StoreError.Gone()
            "not_set_up" -> StoreError.NotSetUp()
            else -> if (status == 401) StoreError.SignedOut() else StoreError.Network(IOException("theme store answered $status"))
        }
    }

    fun sortName(sort: StoreSort) = when (sort) {
        StoreSort.POPULAR -> "popular"
        StoreSort.NEW -> "new"
        StoreSort.MINE -> "mine"
    }

    /** A code as typed: letters and digits only, whatever else came with it. */
    fun cleanCode(code: String) = code.filter { it.isLetterOrDigit() }.uppercase().take(16)
}

internal fun WirePack.toStore() = StorePack(
    id = id,
    authorId = user_id,
    authorName = author_name,
    name = name,
    description = description,
    parts = parts.mapNotNull { p -> ThemePart.entries.firstOrNull { it.name == p } }.toSet(),
    accent = accent,
    background = background,
    listed = listed,
    shareCode = share_code,
    likes = likes,
    downloads = downloads,
    createdAt = created_at,
    snapshot = snapshot,
)

/**
 * The Theme Store, on its own server (`tryptify-servers/theme-store`, a Railway Function
 * with its own Postgres). Every call needs an account: it carries the Supabase
 * session's access token, which the server checks against the project's public
 * keys. A token that has run out is refreshed once and the call sent again.
 */
@Singleton
class ThemeStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: SupabaseAuthManager,
    private val packs: ThemePackRepository,
    private val http: HttpClient,
    private val json: Json,
) {
    private val refreshing = Mutex()

    /**
     * Store pictures, decoded, by pack and hash: about a dozen at a time, a
     * screenful and the rows either side of it. On disk they are kept by the
     * same name, so a picture is downloaded once until its pack gets a new one.
     */
    private val pictures = object : LruCache<String, Bitmap>(PICTURE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pictureDir by lazy { File(context.cacheDir, "theme_store_pictures") }

    val user: StateFlow<UserProfile?> = auth.userProfile

    suspend fun browse(sort: StoreSort, query: String, page: Int): Result<List<StorePack>> = call {
        val q = query.trim().replace("%", "").replace("_", "").take(40)
        val res = send(
            HttpMethod.Get, "packs",
            query = buildMap {
                put("sort", StoreWire.sortName(sort))
                if (q.isNotEmpty()) put("q", q)
                put("page", page.coerceAtLeast(0).toString())
            },
        )
        json.decodeFromString<List<WirePack>>(res.bodyAsText()).map { it.toStore() }
    }

    /** The pack itself, decoded and cleaned for this build. */
    suspend fun fetch(id: String): Result<Pair<StorePack, ThemePack>> = call {
        val row = json.decodeFromString<WirePack>(send(HttpMethod.Get, "packs", id).bodyAsText())
        val pack = packs.decode(row.pack) ?: throw StoreError.BadPack()
        row.toStore() to pack
    }

    /** A pack by its share code, listed or not. Null when no pack has that code. */
    suspend fun byCode(code: String): Result<StorePack?> = call {
        val clean = StoreWire.cleanCode(code)
        if (clean.isEmpty()) return@call null
        try {
            val row = json.decodeFromString<WirePack>(send(HttpMethod.Get, "codes", clean).bodyAsText())
            row.toStore()
        } catch (_: StoreError.Gone) {
            null
        }
    }

    /** Publishes [pack] (its personal settings already stripped by [ThemePackCodec.forSharing]). */
    suspend fun publish(
        pack: ThemePack,
        name: String,
        description: String,
        listed: Boolean,
    ): Result<StorePack> = call {
        val profile = user.value ?: throw StoreError.SignedOut()
        val clean = ThemePackCodec.forSharing(pack.copy(name = name.trim().take(ThemePack.MAX_NAME)))
        val text = ThemePackCodec.encode(clean)
        if (text.toByteArray(Charsets.UTF_8).size > ThemePackCodec.MAX_BYTES || clean.parts.isEmpty()) {
            throw StoreError.BadPack()
        }
        val colours = clean.colours
        val body = json.encodeToString(
            WritePack.serializer(),
            WritePack(
                author_name = (profile.displayName ?: "").trim().take(40),
                name = clean.name,
                description = description.trim().take(MAX_DESCRIPTION),
                parts = clean.parts.map { it.name },
                accent = colours?.accent ?: 0,
                background = colours?.background ?: 0,
                pack = text,
                listed = listed,
            ),
        )
        val row = json.decodeFromString<WirePack>(send(HttpMethod.Post, "packs", body = body).bodyAsText())
        row.toStore()
    }

    suspend fun setListed(id: String, listed: Boolean): Result<Unit> = call {
        send(HttpMethod.Patch, "packs", id, body = json.encodeToString(WriteListed.serializer(), WriteListed(listed)))
        Unit
    }

    suspend fun delete(id: String): Result<Unit> = call {
        send(HttpMethod.Delete, "packs", id)
        Unit
    }

    /** The ids among [ids] this account likes. */
    suspend fun likedAmong(ids: List<String>): Result<Set<String>> = call {
        if (ids.isEmpty()) return@call emptySet()
        val res = send(HttpMethod.Get, "likes", query = mapOf("ids" to ids.take(100).joinToString(",")))
        json.decodeFromString<LikedIds>(res.bodyAsText()).ids.toSet()
    }

    suspend fun setLiked(id: String, liked: Boolean): Result<Unit> = call {
        send(if (liked) HttpMethod.Put else HttpMethod.Delete, "packs", id, "like")
        Unit
    }

    /** Counts a download, once per person; failures are not worth telling anyone about. */
    suspend fun markDownloaded(id: String) {
        call { send(HttpMethod.Post, "packs", id, "download") }
    }

    /**
     * [pack]'s store picture, or null when it has none or it cannot be had
     * now: the card then shows the pack's colours instead, so a failure here
     * is never an error on screen.
     */
    suspend fun picture(pack: StorePack): Bitmap? {
        val hash = pack.snapshot ?: return null
        // Both name a file: only an id and a hash as the server makes them.
        if (!SAFE_ID.matches(pack.id) || !SAFE_HASH.matches(hash)) return null
        val key = "${pack.id}-$hash"
        pictures.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(pictureDir, "$key.webp")
            val bytes = file.takeIf { it.isFile }?.readBytes()
                ?: call { send(HttpMethod.Get, "packs", pack.id, "snapshot").readRawBytes() }.getOrNull()
                    ?.also { got ->
                        runCatching {
                            pictureDir.mkdirs()
                            // The pack's older pictures are no use once it has this one.
                            pictureDir.listFiles { f -> f.name.startsWith("${pack.id}-") }?.forEach { it.delete() }
                            file.writeBytes(got)
                        }
                    }
                ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { pictures.put(key, it) }
        }
    }

    /** Sets [id]'s store picture to [image] (WebP); its new hash. Only the author's is taken. */
    suspend fun setPicture(id: String, image: ByteArray): Result<String> = call {
        val res = send(HttpMethod.Put, "packs", id, "snapshot", bytes = image)
        json.decodeFromString<SnapshotSaved>(res.bodyAsText()).snapshot
    }

    /** One report per person and pack; the server takes a second as the first. */
    suspend fun report(id: String, reason: String): Result<Unit> = call {
        val body = json.encodeToString(WriteReport.serializer(), WriteReport(reason.trim().take(MAX_DESCRIPTION)))
        send(HttpMethod.Post, "packs", id, "report", body = body)
        Unit
    }

    /**
     * One call to the store, under `/v1/[path]`. A 401 refreshes the session and
     * sends it once more; any other failure is thrown as the [StoreError] it means.
     */
    private suspend fun send(
        method: HttpMethod,
        vararg path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        bytes: ByteArray? = null,
    ): HttpResponse {
        suspend fun attempt(token: String) = http.request {
            this.method = method
            url {
                takeFrom(BuildConfig.THEME_STORE_URL)
                appendPathSegments("v1", *path)
                query.forEach { (k, v) -> parameters.append(k, v) }
            }
            bearerAuth(token)
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            if (bytes != null) setBody(ByteArrayContent(bytes, ContentType("image", "webp")))
        }

        val token = auth.supabase.auth.currentAccessTokenOrNull() ?: throw StoreError.SignedOut()
        var res = attempt(token)
        if (res.status == HttpStatusCode.Unauthorized) res = attempt(refreshed(token))
        if (!res.status.isSuccess()) throw StoreWire.errorOf(res.status.value, res.bodyAsText())
        return res
    }

    /**
     * A token newer than [stale]. Calls that fail together refresh once: the
     * first refreshes, the rest find its token already there.
     */
    private suspend fun refreshed(stale: String): String = refreshing.withLock {
        val now = auth.supabase.auth.currentAccessTokenOrNull()
        if (now != null && now != stale) return@withLock now
        try {
            auth.supabase.auth.refreshCurrentSession()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RestException) {
            // Supabase refused the refresh token: the session is over.
            throw StoreError.SignedOut()
        } catch (e: Exception) {
            throw StoreError.Network(e)
        }
        auth.supabase.auth.currentAccessTokenOrNull() ?: throw StoreError.SignedOut()
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: StoreError) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(StoreError.Network(e))
        }

    private companion object {
        const val MAX_DESCRIPTION = 280
        const val PICTURE_CACHE_BYTES = 32 * 1024 * 1024
        val SAFE_ID = Regex("[0-9a-fA-F-]{36}")
        val SAFE_HASH = Regex("[0-9a-f]{8,64}")
    }
}

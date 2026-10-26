package tf.monochrome.android.data.cloud

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.local.db.LocalTrackEntity
import java.io.File
import java.nio.file.Files

/**
 * The app's half of Tryptify Cloud's contract with `server/cloud`: the parts a
 * file is cut into, the error codes, the JSON the server sends, and what a
 * local track is uploaded with.
 */
class CloudTest {

    /** The app's own Json (AppModule.provideJson). */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val serverSource = File("../server/cloud/src").listFiles()!!
        .filter { it.extension == "ts" }
        .joinToString("\n") { it.readText() }

    @Test
    fun `parts cover the file exactly, all full but the last`() {
        val part = 50L shl 20
        for (size in listOf(1L, part - 1, part, part + 1, 3 * part, 3 * part + 7)) {
            val parts = ((size + part - 1) / part).toInt()
            var next = 0L
            for (n in 1..parts) {
                val r = partRange(size, part, n)
                assertEquals("part $n of $size starts where the last ended", next, r.first)
                val length = r.last - r.first + 1
                if (n < parts) assertEquals(part, length) else assertTrue(length in 1..part)
                next = r.last + 1
            }
            assertEquals(size, next)
        }
    }

    @Test
    fun `every error code the app words is one the server sends`() {
        val mapped = listOf(
            "signed_out", "no_plan", "read_only", "over_quota", "not_audio", "file_too_big", "cover_too_big",
            "trial_used", "too_many_uploads", "not_set_up", "billing_not_set_up", "storage_not_set_up",
        )
        for (code in mapped) assertTrue("server never sends $code", "\"$code\"" in serverSource)
    }

    @Test
    fun `error bodies become the errors they mean`() {
        assertTrue(CloudError.of(413, """{"error":"over_quota"}""") is CloudError.OverQuota)
        assertTrue(CloudError.of(402, """{"error":"no_plan"}""") is CloudError.NoPlan)
        assertTrue(CloudError.of(409, """{"error":"read_only"}""") is CloudError.ReadOnly)
        assertTrue(CloudError.of(429, """{"error":"too_many_uploads"}""") is CloudError.Busy)
        assertTrue(CloudError.of(401, "") is CloudError.SignedOut)
        val other = CloudError.of(500, "<html>")
        assertTrue(other is CloudError.Server && other.code == "http_500")
        val unknown = CloudError.of(400, """{"error":"size_mismatch"}""")
        assertTrue(unknown is CloudError.Server && unknown.code == "size_mismatch")
    }

    @Test
    fun `the plans and the account decode from what the server sends`() {
        val plans = json.decodeFromString<CloudPlans>(
            """{"readOnlyDays":7,"trial":{"days":7,"storageGb":20,"periods":["monthly"]},
               "plans":[{"id":"lite","storageGb":100,"monthly":3.99,"yearly":39.99},
                        {"id":"plus","storageGb":250,"monthly":9.99,"yearly":99.99}]}""",
        )
        assertEquals(7, plans.readOnlyDays)
        assertEquals(20, plans.trial.storageGb)
        assertEquals(listOf("lite", "plus"), plans.plans.map { it.id })
        assertEquals(3.99, plans.plans[0].monthly, 0.0)

        val account = json.decodeFromString<CloudAccount>(
            """{"plan":"lite","access":"read_only","paidUntil":"2026-10-01T00:00:00.000Z",
               "deleteAfter":"2026-10-08T00:00:00.000Z","usedBytes":12,"quotaBytes":100000000000,
               "trial":false,"trialAvailable":false}""",
        )
        assertTrue(account.canPlay)
        assertFalse(account.canUpload)
        assertEquals(100_000_000_000L, account.quotaBytes)

        val none = json.decodeFromString<CloudAccount>("""{"plan":null,"access":"none","paidUntil":null,"trialAvailable":true}""")
        assertFalse(none.canPlay)
        assertTrue(none.trialAvailable)
    }

    @Test
    fun `the ways to pay decode with what each can sell, and the choice is sent`() {
        val plans = json.decodeFromString<CloudPlans>(
            """{"readOnlyDays":7,"trial":{"days":7,"storageGb":20},
               "plans":[{"id":"lite","storageGb":100,"monthly":3.99,"yearly":39.99},
                        {"id":"pro","storageGb":2000,"monthly":39.99,"yearly":399.99}],
               "providers":[
                 {"name":"paypal","default":true,"offers":[
                   {"plan":"lite","monthly":true,"yearly":true,"trial":true},
                   {"plan":"pro","monthly":true,"yearly":true,"trial":true}]},
                 {"name":"creem","default":false,"offers":[
                   {"plan":"lite","monthly":true,"yearly":true,"trial":false},
                   {"plan":"pro","monthly":false,"yearly":false,"trial":false}]}]}""",
        )
        assertEquals(listOf("paypal", "creem"), plans.providers.map { it.name })
        assertTrue(plans.providers[0].default)
        val creem = plans.providers[1]
        assertTrue(creem.sells("lite", "monthly", trial = false))
        assertTrue(creem.sells("lite", "yearly", trial = false))
        assertFalse(creem.sells("lite", "monthly", trial = true))
        assertFalse(creem.sells("pro", "yearly", trial = false))
        assertFalse(creem.sells("mini", "monthly", trial = false))

        // A server from before the choice: no list, and the checkout names no provider.
        val old = json.decodeFromString<CloudPlans>("""{"plans":[]}""")
        assertTrue(old.providers.isEmpty())
        assertEquals(
            """{"plan":"lite","period":"monthly","trial":true}""",
            json.encodeToString(CheckoutRequest("lite", "monthly", true)),
        )
        assertEquals(
            """{"plan":"lite","period":"yearly","provider":"creem"}""",
            json.encodeToString(CheckoutRequest("lite", "yearly", provider = "creem")),
        )
        // The server reads the same field name.
        assertTrue(serverSource.contains("body?.provider"))
    }

    @Test
    fun `a track uploads with its tags, and without the zeros that mean unknown`() {
        val file = File("/music/Artist/Album/01 Song.flac")
        val meta = track(file.path, title = null).toMeta(file)
        assertEquals("01 Song", meta.title)
        assertEquals("Artist", meta.artist)
        assertNull(meta.track)
        assertNull(meta.bitRate)
        assertEquals(44100, meta.sampleRate)
        assertEquals(215, meta.duration)
    }

    @Test
    fun `only a cached picture is sent as the cover, never the audio file`() {
        val dir = Files.createTempDirectory("cloud").toFile()
        try {
            val audio = File(dir, "song.flac").apply { writeBytes(ByteArray(10)) }
            val jpeg = File(dir, "cover.jpg").apply { writeBytes(ByteArray(10)) }
            val huge = File(dir, "huge.png").apply { writeBytes(ByteArray((CloudRepository.MAX_COVER_BYTES + 1).toInt())) }
            assertEquals(jpeg, coverOf(track(audio.path, art = jpeg.path)))
            assertNull(coverOf(track(audio.path, art = audio.path)))
            assertNull(coverOf(track(audio.path, art = huge.path)))
            assertNull(coverOf(track(audio.path, art = "content://media/1")))
            assertNull(coverOf(track(audio.path, art = null)))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun track(path: String, title: String? = "Song", art: String? = null) = LocalTrackEntity(
        filePath = path,
        fileSizeBytes = 10,
        lastModified = 0,
        title = title,
        artist = "Artist",
        album = "Album",
        trackNumber = 0,
        codec = "FLAC",
        sampleRate = 44100,
        bitRate = 0,
        channels = 2,
        durationSeconds = 215,
        artworkCacheKey = art,
    )
}

package tf.monochrome.android.data.presence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway frames and the activity a track becomes.
 *
 * This is the part of the feature that cannot be checked by using it. The card
 * renders on somebody else's screen, and Discord answers a malformed activity
 * by dropping it rather than by complaining — so a payload that is subtly wrong
 * looks exactly like a presence that hasn't connected yet.
 */
class DiscordPresenceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun playing(
        title: String = "Bassline Junkie",
        artist: String? = "Dizzee Rascal",
        album: String? = "The Fifth",
        artwork: String? = "mp:external/abc/https/resources.tidal.com/cover.jpg",
        positionMs: Long = 30_000,
        durationMs: Long = 210_000,
        paused: Boolean = false,
    ) = DiscordPresence.NowPlaying(title, artist, album, artwork, positionMs, durationMs, paused)

    @Test
    fun `a playing track is a listening activity, not a playing one`() {
        val a = DiscordPresence.activity(playing(), "Tryptify", "123")
        // Type 0 would render "Playing Tryptify" with the track demoted to a
        // subtitle. Type 2 is what produces the layout people know.
        assertEquals(2, a["type"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Tryptify", a["name"]?.jsonPrimitive?.content)
        assertEquals("Bassline Junkie", a["details"]?.jsonPrimitive?.content)
        assertEquals("Dizzee Rascal", a["state"]?.jsonPrimitive?.content)
        assertEquals("The Fifth", a["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content)
    }

    @Test
    fun `timestamps describe the remaining track, not the elapsed one`() {
        val before = System.currentTimeMillis()
        val a = DiscordPresence.activity(playing(positionMs = 30_000, durationMs = 210_000), "T", null)
        val after = System.currentTimeMillis()

        val ts = a["timestamps"]!!.jsonObject
        val start = ts["start"]!!.jsonPrimitive.content.toLong()
        val end = ts["end"]!!.jsonPrimitive.content.toLong()

        // Discord fills the bar from (now - start) / (end - start), so start
        // has to sit 30s in the past for a track 30s in.
        assertTrue("start $start not ~30s ago", start in (before - 30_000)..(after - 30_000))
        assertEquals("the span is the track's full length", 210_000L, end - start)
    }

    @Test
    fun `a paused track sends no timestamps at all`() {
        // Discord has no paused state: leaving the timestamps in would leave a
        // bar visibly advancing through music that stopped.
        val a = DiscordPresence.activity(playing(paused = true), "T", null)
        assertNull(a["timestamps"])
        assertEquals("Paused", a["assets"]?.jsonObject?.get("small_text")?.jsonPrimitive?.content)
    }

    @Test
    fun `a track of unknown length sends no timestamps either`() {
        // A bar needs an end. Local files resolve their duration late, and a
        // zero would draw a full bar on a track that just started.
        val a = DiscordPresence.activity(playing(durationMs = 0), "T", null)
        assertNull(a["timestamps"])
    }

    @Test
    fun `one-character strings are padded rather than sent`() {
        // Discord requires 2..128 characters and drops the whole activity if
        // any field is shorter — and tracks called "4" exist.
        val a = DiscordPresence.activity(playing(title = "4", artist = "M"), "T", null)
        assertTrue(a["details"]!!.jsonPrimitive.content.length >= 2)
        assertTrue(a["state"]!!.jsonPrimitive.content.length >= 2)
    }

    @Test
    fun `a long title is truncated to what Discord accepts`() {
        val a = DiscordPresence.activity(playing(title = "x".repeat(400)), "T", null)
        assertEquals(128, a["details"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `an application id is only sent when there is one`() {
        assertNull(DiscordPresence.activity(playing(), "T", null)["application_id"])
        assertNull(DiscordPresence.activity(playing(), "T", "")["application_id"])
        assertEquals("42", DiscordPresence.activity(playing(), "T", "42")["application_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a track with no artist still produces a valid activity`() {
        val a = DiscordPresence.activity(playing(artist = null, album = null, artwork = null), "T", null)
        assertNull(a["state"])
        assertNull(a["assets"])
        assertEquals("Bassline Junkie", a["details"]?.jsonPrimitive?.content)
    }

    @Test
    fun `identify carries the token and the opening activity`() {
        val activity = DiscordPresence.activity(playing(), "Tryptify", null)
        val frame = json.parseToJsonElement(DiscordPresence.identifyFrame("tok", activity)).jsonObject
        assertEquals(2, frame["op"]?.jsonPrimitive?.content?.toInt())
        val d = frame["d"]!!.jsonObject
        assertEquals("tok", d["token"]?.jsonPrimitive?.content)
        // Sent inside IDENTIFY rather than as a follow-up, so the presence is
        // live the instant the connection is, with no visible gap.
        assertEquals(1, d["presence"]!!.jsonObject["activities"]!!.jsonArray.size)
        assertEquals("Android", d["properties"]!!.jsonObject["os"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a null activity clears the presence rather than omitting it`() {
        // An absent activities array leaves the last one standing; an empty one
        // is what actually takes the card down.
        val frame = json.parseToJsonElement(DiscordPresence.presenceFrame(null)).jsonObject
        assertEquals(3, frame["op"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, frame["d"]!!.jsonObject["activities"]!!.jsonArray.size)
    }

    @Test
    fun `the first heartbeat has a null sequence`() {
        assertEquals("""{"op":1,"d":null}""", DiscordPresence.heartbeatFrame(null))
        assertEquals("""{"op":1,"d":42}""", DiscordPresence.heartbeatFrame(42))
    }

    @Test
    fun `hello carries the interval the heartbeat must use`() {
        val f = DiscordPresence.parseFrame("""{"op":10,"d":{"heartbeat_interval":41250,"_trace":[]}}""")
        assertEquals(DiscordPresence.Frame.Hello(41_250), f)
    }

    @Test
    fun `hello without an interval still yields a usable one`() {
        // Defaulting beats not heartbeating: a connection that never beats is
        // closed by Discord inside a minute.
        val f = DiscordPresence.parseFrame("""{"op":10,"d":{}}""")
        assertTrue("$f", f is DiscordPresence.Frame.Hello && f.heartbeatIntervalMs > 0)
    }

    @Test
    fun `a dispatch reports its event and sequence`() {
        val f = DiscordPresence.parseFrame("""{"t":"READY","s":3,"op":0,"d":{"v":10}}""")
        assertEquals(DiscordPresence.Frame.Dispatch("READY", 3), f)
    }

    @Test
    fun `an unresumable invalid session is distinguished from a resumable one`() {
        // The difference decides whether reconnecting is worth doing: a refused
        // token retried forever is how an app gets its user rate limited.
        val fatal = DiscordPresence.parseFrame("""{"op":9,"d":false}""")
        assertEquals(DiscordPresence.Frame.InvalidSession(false), fatal)
        assertEquals(DiscordPresence.Frame.InvalidSession(true), DiscordPresence.parseFrame("""{"op":9,"d":true}"""))
    }

    @Test
    fun `reconnect and heartbeat frames are recognised`() {
        assertEquals(DiscordPresence.Frame.Reconnect, DiscordPresence.parseFrame("""{"op":7,"d":null}"""))
        assertEquals(DiscordPresence.Frame.HeartbeatAck, DiscordPresence.parseFrame("""{"op":11,"d":null}"""))
        assertEquals(DiscordPresence.Frame.HeartbeatRequest, DiscordPresence.parseFrame("""{"op":1,"d":null}"""))
    }

    @Test
    fun `junk on the socket is ignored rather than thrown`() {
        // A parse exception inside the receive loop would kill the connection
        // and hand the reconnect loop a permanent failure.
        assertEquals(DiscordPresence.Frame.Ignored, DiscordPresence.parseFrame("not json"))
        assertEquals(DiscordPresence.Frame.Ignored, DiscordPresence.parseFrame("""{"op":99}"""))
        assertEquals(DiscordPresence.Frame.Ignored, DiscordPresence.parseFrame(""))
    }

    @Test
    fun `the token never appears in a presence frame`() {
        // Presence updates go out on every track change; only IDENTIFY should
        // ever carry the credential.
        val frame = DiscordPresence.presenceFrame(DiscordPresence.activity(playing(), "T", "1"))
        assertFalse(frame.contains("token"))
    }

    @Test
    fun `the same track at the same place is a repeat`() {
        // Three callbacks push once per song — the queue moving, the player
        // becoming ready, the play state settling — and they read the play head
        // a few hundred milliseconds apart. Sending all three walks a held skip
        // button straight through Discord's presence rate limit.
        assertTrue(playing(positionMs = 30_000).sameAs(playing(positionMs = 30_400)))
    }

    @Test
    fun `a seek is never mistaken for a repeat`() {
        // The case that must survive the de-duplication: a seek moves the head
        // far further than the tolerance, and swallowing it would leave the bar
        // counting from where the track used to be.
        assertFalse(playing(positionMs = 30_000).sameAs(playing(positionMs = 90_000)))
    }

    @Test
    fun `a pause is never mistaken for a repeat`() {
        // Pausing changes nothing but the flag, and dropping it would leave a
        // bar advancing through music that stopped.
        assertFalse(playing(paused = false).sameAs(playing(paused = true)))
    }

    @Test
    fun `a different track is never a repeat, however close the play head`() {
        assertFalse(playing(title = "One").sameAs(playing(title = "Two")))
        assertFalse(playing(artist = "A").sameAs(playing(artist = "B")))
        // Two tracks on one album, both at the top, differ only in length.
        assertFalse(playing(durationMs = 210_000).sameAs(playing(durationMs = 240_000)))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.connect

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
 * Tryptify Connect as the phone reads and writes it, held to what the room
 * (tryptify-servers/connect/src/protocol.ts and room.ts) and the web player send.
 */
class ConnectProtocolTest {

    private val song = ConnectTrack(src = "hifi", id = "12345", title = "Song", artist = "Artist", duration = 200.0)

    @Test
    fun `the room's messages are read`() {
        val welcome = ConnectProtocol.parse(
            """{"type":"welcome","you":"phone00001","serverTime":1000,""" +
                """"devices":[{"id":"webweb0001","name":"Chrome on Windows","kind":"web","since":5,"active":true}],""" +
                """"now":{"device":"webweb0001","name":"Chrome on Windows","playing":true,""" +
                """"track":{"src":"hifi","id":"12345","title":"Song","duration":200},"position":30,"rate":1,"at":900,"since":800,"extra":1}}""",
        )!!
        assertEquals("welcome", welcome.type)
        assertEquals("Chrome on Windows", welcome.devices!!.single().name)
        assertTrue(welcome.devices!!.single().active)
        assertEquals("12345", welcome.now!!.track!!.id)
        assertEquals(800L, welcome.now!!.since)

        val takeover = ConnectProtocol.parse("""{"type":"takeover","from":"webweb0001","now":{"playing":true,"position":96.5},"queue":[{"src":"cloud","id":"s1"}]}""")!!
        assertEquals(96.5, takeover.now!!.position, 0.0)
        assertEquals("s1", takeover.queue!!.single().id)

        val command = ConnectProtocol.parse("""{"type":"command","from":"webweb0001","action":"seek","value":95}""")!!
        assertEquals("seek", command.action)
        assertEquals(95.0, command.value!!, 0.0)
    }

    @Test
    fun `the keep-alive and junk are not messages`() {
        assertNull(ConnectProtocol.parse("pong"))
        assertNull(ConnectProtocol.parse("{not json"))
        assertNull(ConnectProtocol.parse("""{"no":"type"}"""))
    }

    @Test
    fun `where a song is later`() {
        val now = ConnectNow(device = "d", playing = true, track = song.copy(duration = 100.0), position = 10.0, rate = 2.0, at = 1_000)
        assertEquals(20.0, ConnectProtocol.positionAt(now, 6_000), 1e-9) // 5 s at 2× is 10 s on
        assertEquals(100.0, ConnectProtocol.positionAt(now, 999_999), 1e-9) // never past the end
        assertEquals(10.0, ConnectProtocol.positionAt(now.copy(playing = false), 6_000), 1e-9)
        assertEquals(10.0, ConnectProtocol.positionAt(now, 0), 1e-9) // a clock behind does not run it backwards
    }

    @Test
    fun `the later start wins`() {
        val other = ConnectNow(device = "web", playing = true, at = 5_000, since = 4_000)
        assertTrue(ConnectProtocol.shouldMakeWay(other, myId = "phone", playingHere = true, mySince = 3_000))
        assertFalse("an earlier start arriving late", ConnectProtocol.shouldMakeWay(other, "phone", true, mySince = 4_500))
        assertFalse("not playing here", ConnectProtocol.shouldMakeWay(other, "phone", false, 3_000))
        assertFalse("its own echo", ConnectProtocol.shouldMakeWay(other.copy(device = "phone"), "phone", true, 3_000))
        assertFalse("paused there", ConnectProtocol.shouldMakeWay(other.copy(playing = false), "phone", true, 3_000))
        assertTrue("no start time: when it was said", ConnectProtocol.shouldMakeWay(other.copy(since = 0), "phone", true, 4_999))
    }

    @Test
    fun `only safe covers travel`() {
        assertEquals("https://resources.tidal.com/images/a/b/640x640.jpg", ConnectProtocol.shareableCover("https://resources.tidal.com/images/a/b/640x640.jpg"))
        assertNull("a music server's carries its sign-in", ConnectProtocol.shareableCover("https://music.example/rest/getCoverArt.view?id=1&u=me&t=abc&s=def"))
        assertNull("a file's own picture", ConnectProtocol.shareableCover("/storage/emulated/0/Music/cover.jpg"))
        assertNull(ConnectProtocol.shareableCover("http://plain.example/x.jpg"))
        assertEquals("https://x.example/a.jpg?size=300", ConnectProtocol.shareableCover("https://x.example/a.jpg?size=300"))
    }

    @Test
    fun `device ids`() {
        assertTrue(ConnectProtocol.isDeviceId("android-0123456789ab"))
        assertFalse(ConnectProtocol.isDeviceId("short"))
        assertFalse(ConnectProtocol.isDeviceId("../../etc/passwd"))
        assertFalse(ConnectProtocol.isDeviceId("ünïcode-id-here"))
    }

    @Test
    fun `what the phone sends`() {
        val out = ConnectProtocol.StateOut(playing = true, track = song, position = 12.5, rate = 1.0, since = 777)
        val state = Json.parseToJsonElement(ConnectProtocol.state(out)).jsonObject
        assertEquals("state", state["type"]!!.jsonPrimitive.content)
        assertEquals("12345", state["track"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("777", state["since"]!!.jsonPrimitive.content)

        val handoff = Json.parseToJsonElement(ConnectProtocol.handoff("webweb0001", out, List(150) { song.copy(id = "$it") })).jsonObject
        assertEquals("handoff", handoff["type"]!!.jsonPrimitive.content)
        assertNull("the state inside carries no type of its own", handoff["now"]!!.jsonObject["type"])
        assertEquals("12.5", handoff["now"]!!.jsonObject["position"]!!.jsonPrimitive.content)
        assertEquals(ConnectProtocol.MAX_QUEUE, handoff["queue"]!!.jsonArray.size)

        val nothing = Json.parseToJsonElement(ConnectProtocol.handoff("webweb0001", null, emptyList())).jsonObject
        assertTrue(nothing["now"]!!.jsonObject.isEmpty())

        val seek = Json.parseToJsonElement(ConnectProtocol.command("phone00001", "seek", 95.0)).jsonObject
        assertEquals("95.0", seek["value"]!!.jsonPrimitive.content)
        assertNull(Json.parseToJsonElement(ConnectProtocol.command("phone00001", "pause")).jsonObject["value"])
        assertEquals("true", Json.parseToJsonElement(ConnectProtocol.transfer("x", true)).jsonObject["force"]!!.jsonPrimitive.content)
    }
}

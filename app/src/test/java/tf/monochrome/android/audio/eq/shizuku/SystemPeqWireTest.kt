package tf.monochrome.android.audio.eq.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The app and the helper that outlives Shizuku, held to each other: what
 * may be put into the command that starts it, who is let in, and that the
 * app's end says what the helper's end reads.
 */
class SystemPeqWireTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val apk = "/data/app/~~4pfjhsSeppJhrwcmfCedWg==/tf.monotrypt.android-wBk6blyLSUrHp4alS3SZaw==/base.apk"

    @Test
    fun `a token is 32 hex digits and nothing else`() {
        assertTrue(SystemPeqWire.isToken(token))
        for (bad in listOf("", "0123", token + "0", token.uppercase(), token.dropLast(1) + "g", "$token; rm -rf /", token.dropLast(1) + "'")) {
            assertFalse(bad, SystemPeqWire.isToken(bad))
        }
    }

    @Test
    fun `an APK path is a path, and nothing a shell would read as more`() {
        assertTrue(SystemPeqWire.isApkPath(apk))
        assertTrue(SystemPeqWire.isApkPath("/data/app/tf.monochrome.android-1/base.apk"))
        for (bad in listOf(
            "", "base.apk", "/data/app/x/base.apk; reboot", "/data/app/x/base.apk'", "/data/app/\$(id)/base.apk",
            "/data/app/x y/base.apk", "/data/app/../../system/x.apk", "/data/app/x/base.dex", "/data/app/x/`id`.apk",
            "/data/app/x/base.apk\nreboot",
        )) {
            assertFalse(bad, SystemPeqWire.isApkPath(bad))
        }
    }

    @Test
    fun `the command starts the helper from the app's APK, apart from its caller`() {
        val command = SystemPeqWire.launchCommand(apk, token, SystemPeqWire.BASE_PORT)
        assertTrue(command, command.contains("CLASSPATH='$apk'"))
        assertTrue(command.contains("app_process /system/bin"))
        assertTrue(command.contains("${SystemPeqWire.DAEMON_CLASS} $token ${SystemPeqWire.BASE_PORT}"))
        // In a session of its own where the phone has setsid, and in the background.
        assertTrue(command.contains("setsid"))
        assertTrue(command.trimEnd().endsWith("&"))
        // Nothing of the caller's left open for it to die on.
        assertTrue(command.contains("</dev/null >/dev/null 2>&1"))
        // The class named is the class there is.
        assertEquals(SystemPeqWire.DAEMON_CLASS, SystemPeqDaemon::class.java.name)
        // What is not a path or a token never reaches a shell.
        assertTrue(runCatching { SystemPeqWire.launchCommand("$apk; reboot", token, 1) }.isFailure)
        assertTrue(runCatching { SystemPeqWire.launchCommand(apk, "$token; reboot", 1) }.isFailure)
    }

    private fun hello(said: String, magic: Int = SystemPeqWire.MAGIC): Boolean {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(magic); writeUTF(said) }
        return SystemPeqWire.readHello(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), token)
    }

    @Test
    fun `only who says the token is let in`() {
        val bytes = ByteArrayOutputStream()
        SystemPeqWire.writeHello(DataOutputStream(bytes), token)
        assertTrue(SystemPeqWire.readHello(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), token))
        assertFalse(hello(token.reversed()))
        assertFalse(hello(token.dropLast(1)))
        assertFalse(hello(token + "0"))
        assertFalse(hello(""))
        assertFalse(hello(token, magic = 0x47455420))  // a stray "GET "
    }

    @Test
    fun `a curve crosses whole, and a length no curve has is refused`() {
        val curve = floatArrayOf(0f, 3150f, -6f, 1.4f, 1f, 110f, 3f, 0.7f)
        val bytes = ByteArrayOutputStream()
        SystemPeqWire.writeFloats(DataOutputStream(bytes), curve)
        assertArrayEquals(curve, SystemPeqWire.readFloats(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))), 0f)
        for (n in listOf(-1, SystemPeqDsp.MAX_BANDS * SystemPeqDsp.FIELDS + 1, Int.MAX_VALUE)) {
            val wild = ByteArrayOutputStream()
            DataOutputStream(wild).writeInt(n)
            assertNull("length $n", SystemPeqWire.readFloats(DataInputStream(ByteArrayInputStream(wild.toByteArray()))))
        }
    }

    /** A helper's end of the wire with no equalizer behind it: what it was asked, in order. */
    private class FakeHelper(private val token: String, private val starts: Boolean = true) : AutoCloseable {
        val asked = CopyOnWriteArrayList<String>()
        val server: ServerSocket = (20000..60000 step 97).firstNotNullOf { port ->
            runCatching { ServerSocket(port, 2, InetAddress.getLoopbackAddress()) }.getOrNull()
        }
        val port get() = server.localPort
        private val worker = thread(isDaemon = true) {
            runCatching {
                while (true) {
                    server.accept().use { socket ->
                        runCatching {
                            val input = DataInputStream(socket.getInputStream().buffered())
                            val out = DataOutputStream(socket.getOutputStream().buffered())
                            if (!SystemPeqWire.readHello(input, token)) return@runCatching
                            out.writeBoolean(true)
                            out.flush()
                            while (true) {
                                when (input.readByte()) {
                                    SystemPeqWire.CMD_START -> {
                                        val uid = input.readInt()
                                        val l = SystemPeqWire.readFloats(input)!!
                                        val r = SystemPeqWire.readFloats(input)!!
                                        asked += "start $uid ${l.size} ${r.size} ${input.readFloat()}"
                                        out.writeBoolean(starts)
                                    }
                                    SystemPeqWire.CMD_CURVE -> {
                                        val l = SystemPeqWire.readFloats(input)!!
                                        val r = SystemPeqWire.readFloats(input)!!
                                        asked += "curve ${l.toList()} ${r.size} ${input.readFloat()}"
                                        out.writeBoolean(true)
                                    }
                                    SystemPeqWire.CMD_STOP -> { asked += "stop"; out.writeBoolean(true) }
                                    SystemPeqWire.CMD_STATUS -> { asked += "status"; out.writeUTF(SystemPeqUserService.STATUS_RUNNING) }
                                    SystemPeqWire.CMD_EXIT -> { asked += "exit"; out.writeBoolean(true); out.flush(); return@runCatching }
                                }
                                out.flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() { runCatching { server.close() } }
    }

    @Test
    fun `the app's end says what the helper's end reads`() {
        FakeHelper(token).use { helper ->
            val client = SystemPeqDaemonClient.connect(token, basePort = helper.port)
            assertNotNull(client)
            client!!
            assertEquals(SystemPeqUserService.STATUS_RUNNING, client.status())
            assertEquals(true, client.start(10123, FloatArray(8), FloatArray(4), -2.5f))
            assertEquals(true, client.setCurve(floatArrayOf(0f, 1000f, -3f, 1f), FloatArray(0), 0f))
            assertEquals(true, client.stop())
            client.exit()
            assertEquals(
                listOf("status", "start 10123 8 4 -2.5", "curve [0.0, 1000.0, -3.0, 1.0] 0 0.0", "stop", "exit"),
                helper.asked.toList(),
            )
        }
    }

    @Test
    fun `it is found on whichever of its ports it took, and not without the token`() {
        FakeHelper(token).use { helper ->
            // Two ports down from where it listens: the search reaches it.
            val found = SystemPeqDaemonClient.connect(token, basePort = helper.port - 2)
            assertNotNull(found)
            found!!.close()
            // Another app, guessing: let in nowhere.
            assertNull(SystemPeqDaemonClient.connect(token.reversed(), basePort = helper.port))
            // The helper is still there for the app after that.
            val again = SystemPeqDaemonClient.connect(token, basePort = helper.port)
            assertNotNull(again)
            again!!.close()
        }
    }

    @Test
    fun `no helper is no helper at once, and one that goes is known as gone`() {
        // Nothing listens: null, and quickly.
        val before = System.nanoTime()
        assertNull(SystemPeqDaemonClient.connect(token, basePort = 1))
        assertTrue("took ${(System.nanoTime() - before) / 1_000_000} ms", System.nanoTime() - before < 3_000_000_000L)
        // A helper that would not play says so, and one that has left answers nothing.
        FakeHelper(token, starts = false).use { helper ->
            val client = SystemPeqDaemonClient.connect(token, basePort = helper.port)!!
            assertEquals(false, client.start(1, FloatArray(0), FloatArray(0), 0f))
            client.exit()
            assertNull(client.status())
        }
    }

    @Test
    fun `the helper waits half a minute for the app, on five ports`() {
        assertEquals(30_000L, SystemPeqWire.GRACE_MS)
        assertEquals(5, SystemPeqWire.PORTS)
        assertTrue(SystemPeqWire.BASE_PORT > 1024)
    }
}

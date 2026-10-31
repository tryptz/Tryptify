package tf.monochrome.android.data.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The googlevideo fetch against a local server that plays googlevideo's
 * part: closed ranges only, a refused link, a dropped piece.
 */
class ChunkedDownloadTest {

    /**
     * Just enough HTTP/1.1 for one request per connection: the JDK's own
     * server is not on the Android classpath the tests compile against.
     */
    private class LocalServer(private val handler: (Request) -> Reply) : Closeable {
        class Request(val method: String, val query: String, val headers: Map<String, String>, val body: ByteArray)
        class Reply(val status: Int, val body: ByteArray = ByteArray(0))

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: IOException) { break }
                    client.use { serve(it) }
                }
            }
        }

        private fun serve(client: Socket) {
            val input = BufferedInputStream(client.getInputStream())
            val (method, target) = readLine(input)?.split(' ') ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var got = 0
            while (got < body.size) {
                val n = input.read(body, got, body.size - got)
                if (n < 0) break
                got += n
            }
            val reply = handler(Request(method, target.substringAfter('?', ""), headers, body))
            client.getOutputStream().apply {
                write("HTTP/1.1 ${reply.status} X\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(reply.body)
                flush()
            }
        }

        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.write(b)
            }
        }

        override fun close() = socket.close()
    }

    private class Asked(val method: String, val range: String?, val rn: String?, val userAgent: String?, val body: ByteArray)

    private lateinit var server: LocalServer
    private val asked = Collections.synchronizedList(mutableListOf<Asked>())

    /** What the next request gets instead of its piece, if anything: a status code. */
    private val failNext = Collections.synchronizedList(mutableListOf<Int>())

    /** A link (by a part of its query) refused with 403 after serving this many pieces. */
    @Volatile
    private var refuseLinkAfter: Pair<String, Int>? = null
    private var servedToRefused = 0

    /** Answers with the whole file whatever the range asked, as a server without ranges would. */
    @Volatile
    private var ignoreRange = false

    private val audio = Random(7).nextBytes(2_621_440 + 123) // 2.5 MiB and a bit
    private lateinit var part: File

    @Before
    fun setUp() {
        part = File.createTempFile("youtube", ".part")
        part.writeBytes(ByteArray(0))
        server = LocalServer { request ->
            val query = request.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val range = request.headers["range"]
            asked += Asked(request.method, range, query["rn"], request.headers["user-agent"], request.body)
            val refuse = refuseLinkAfter
            if (refuse != null && request.query.contains(refuse.first) && servedToRefused++ >= refuse.second) {
                return@LocalServer LocalServer.Reply(403)
            }
            failNext.removeFirstOrNull()?.let { return@LocalServer LocalServer.Reply(it) }
            if (ignoreRange || range == null) return@LocalServer LocalServer.Reply(200, audio)
            val (first, last) = range.removePrefix("bytes=").split('-').map { it.toLong() }
            if (first >= audio.size) return@LocalServer LocalServer.Reply(416)
            LocalServer.Reply(206, audio.copyOfRange(first.toInt(), minOf(last + 1, audio.size.toLong()).toInt()))
        }
    }

    @After
    fun tearDown() {
        server.close()
        part.delete()
    }

    private val url get() = "http://127.0.0.1:${server.port}/videoplayback?expire=1&itag=140&c=ANDROID"

    private fun download(pieceBytes: Long = ChunkedDownload.PIECE_BYTES) =
        ChunkedDownload(OkHttpClient(), pieceBytes, retryDelayMs = 1)

    @Test
    fun `a song comes whole, a MiB or less at a time, every range closed`() = runBlocking {
        val progress = mutableListOf<Long>()
        download().fetch(url, audio.size.toLong(), part) { progress += it }

        assertArrayEquals(audio, part.readBytes())
        assertEquals(3, asked.size)
        assertEquals(listOf("bytes=0-1048575", "bytes=1048576-2097151", "bytes=2097152-${audio.size - 1}"), asked.map { it.range })
        asked.forEach { a ->
            val (first, last) = a.range!!.removePrefix("bytes=").split('-').map { it.toLong() }
            assertTrue(last - first + 1 <= ChunkedDownload.PIECE_BYTES)
        }
        // As YouTube's own clients ask: numbered POSTs with the two-byte body.
        assertEquals(listOf("0", "1", "2"), asked.map { it.rn })
        asked.forEach { a ->
            assertEquals("POST", a.method)
            assertArrayEquals(byteArrayOf(0x78, 0), a.body)
            assertEquals(YoutubeMapping.BROWSER_USER_AGENT, a.userAgent)
        }
        assertEquals(audio.size.toLong(), progress.last())
    }

    @Test
    fun `with no length given it stops at the first short piece`() = runBlocking {
        download(pieceBytes = 1_000_000).fetch(url, -1, part)
        assertArrayEquals(audio, part.readBytes())
        assertEquals(3, asked.size)
    }

    @Test
    fun `with no length given, a file that ends on a piece boundary ends at the 416`() = runBlocking {
        download(pieceBytes = audio.size.toLong()).fetch(url, -1, part)
        assertArrayEquals(audio, part.readBytes())
        assertEquals(listOf("bytes=0-${audio.size - 1}", "bytes=${audio.size}-${2L * audio.size - 1}"), asked.map { it.range })
    }

    @Test
    fun `a dropped piece is asked for again`() = runBlocking {
        failNext += 503
        download().fetch(url, audio.size.toLong(), part)
        assertArrayEquals(audio, part.readBytes())
        assertEquals(4, asked.size)
        assertEquals(asked[0].range, asked[1].range)
    }

    @Test
    fun `a refused link stops with what it got, and a fresh one carries on from there`() = runBlocking {
        // The link with expire=1 serves one piece, then is refused.
        refuseLinkAfter = "expire=1" to 1
        val small = download(pieceBytes = 1_000_000)
        try {
            small.fetch(url, audio.size.toLong(), part)
            fail("the refused link should stop the fetch")
        } catch (e: ChunkedDownload.Expired) {
            assertEquals(403, e.status)
        }
        assertEquals(1_000_000L, part.length())
        // Refused at once, not asked again: retrying an expired link is no use.
        assertEquals(2, asked.size)

        small.fetch(url.replace("expire=1", "expire=2"), audio.size.toLong(), part)
        assertArrayEquals(audio, part.readBytes())
        assertEquals("bytes=1000000-1999999", asked[2].range)
    }

    @Test
    fun `a server that ignores the range is not written as a piece`() = runBlocking {
        ignoreRange = true
        try {
            download(pieceBytes = 1_000_000).fetch(url, audio.size.toLong(), part)
            fail("a whole file under one piece's offset must not be kept")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("more than was asked for"))
        }
    }

    @Test
    fun `a stream that ends early is not taken for the whole song`() = runBlocking {
        try {
            download().fetch(url, audio.size + 10L, part)
            fail("a short file must not pass for the whole song")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ended at"))
        }
    }
}

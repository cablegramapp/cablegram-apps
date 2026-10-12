package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class R2UploaderTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var bucket: MockWebServer
    private val received = ConcurrentHashMap<Int, ByteArray>()
    private val rejectOnce = ConcurrentHashMap.newKeySet<Int>()
    private val failOnce = ConcurrentHashMap.newKeySet<Int>()

    @Before fun start() {
        bucket = MockWebServer()
        bucket.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val part = request.requestUrl!!.queryParameter("partNumber")!!.toInt()
                if (rejectOnce.remove(part)) return MockResponse().setResponseCode(403)
                if (failOnce.remove(part)) return MockResponse().setResponseCode(503)
                received[part] = request.body.readByteArray()
                return MockResponse().setHeader("ETag", "\"etag-$part\"")
            }
        }
        bucket.start()
    }

    @After fun stop() = bucket.shutdown()

    /** A control plane that hands out presigned (here: plain) URLs and keeps R2's list of parts. */
    private inner class FakeApi(val partSize: Long, val held: MutableMap<Int, R2PartInfo> = mutableMapOf()) : R2UploadApi {
        var started = 0
        var completed: List<R2PartRef>? = null
        var urlRequests = 0
        var failStartWith: R2ApiException? = null
        override suspend fun start(originIdentity: String, sizeBytes: Long, contentType: String, fileName: String): R2UploadStart {
            failStartWith?.let { failStartWith = null; throw it }
            started++
            return R2UploadStart("up-1", partSize, ((sizeBytes + partSize - 1) / partSize).toInt(), resumed = held.isNotEmpty())
        }
        override suspend fun parts(uploadId: String, from: Int, count: Int): R2Parts {
            urlRequests++
            // Whatever the bucket received so far counts as held, like ListParts.
            received.forEach { (n, bytes) -> held[n] = R2PartInfo(n, "etag-$n", bytes.size.toLong()) }
            val total = ((fileSize + partSize - 1) / partSize).toInt()
            val urls = (from until minOf(from + count, total + 1)).map { R2PartUrl(it, bucket.url("/b/key?partNumber=$it&uploadId=$uploadId").toString()) }
            return R2Parts(held.values.sortedBy { it.part }, urls)
        }
        override suspend fun complete(uploadId: String, parts: List<R2PartRef>): R2Done {
            completed = parts
            return R2Done("source-1", "item-1", fileSize)
        }
        override suspend fun abort(uploadId: String) = Unit
        var fileSize = 0L
    }

    private fun file(size: Int): Pair<File, ByteArray> {
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        return folder.newFile().also { it.writeBytes(bytes) } to bytes
    }

    private fun upload(api: FakeApi, f: File, progress: MutableList<Long> = mutableListOf()) = runBlocking {
        api.fileSize = f.length()
        RandomAccessFile(f, "r").use { raf ->
            R2Uploader(api, retryDelayMs = { 0 }).upload("local-1", "film.mkv", "video/x-matroska", raf.channel) { sent, _ -> progress += sent }
        }
    }

    @Test fun `parts go to the bucket byte for byte and are completed with the bucket's own etags`() {
        val (f, bytes) = file(25_000) // partSize 10_000: 10k, 10k, 5k
        val api = FakeApi(partSize = 10_000)
        val progress = mutableListOf<Long>()

        val done = upload(api, f, progress)

        assertEquals("source-1", done.sourceId)
        assertEquals(listOf(1, 2, 3), received.keys.sorted())
        assertTrue(bytes.copyOfRange(0, 10_000).contentEquals(received[1]))
        assertTrue(bytes.copyOfRange(10_000, 20_000).contentEquals(received[2]))
        assertTrue("the short last part", bytes.copyOfRange(20_000, 25_000).contentEquals(received[3]))
        assertEquals(listOf(R2PartRef(1, "etag-1"), R2PartRef(2, "etag-2"), R2PartRef(3, "etag-3")), api.completed)
        assertEquals("progress ends at the file size", 25_000L, progress.last())
        assertTrue("progress never goes backwards", progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("a part is sent once", 3, bucket.requestCount)
    }

    @Test fun `an interrupted upload resumes and sends only the parts the bucket does not hold`() {
        val (f, bytes) = file(45_000) // 5 parts
        val held = mutableMapOf(
            1 to R2PartInfo(1, "etag-1", 10_000),
            2 to R2PartInfo(2, "etag-2", 10_000),
            3 to R2PartInfo(3, "etag-3", 10_000),
        )
        received[1] = bytes.copyOfRange(0, 10_000); received[2] = bytes.copyOfRange(10_000, 20_000); received[3] = bytes.copyOfRange(20_000, 30_000)
        val api = FakeApi(10_000, held)
        val progress = mutableListOf<Long>()

        upload(api, f, progress)

        assertEquals("only parts 4 and 5 were sent", 2, bucket.requestCount)
        assertEquals(30_000L, progress.first())
        assertEquals(45_000L, progress.last())
        assertEquals(5, api.completed!!.size)
    }

    @Test fun `a held part of the wrong size is sent again`() {
        val (f, _) = file(20_000)
        val api = FakeApi(10_000, mutableMapOf(1 to R2PartInfo(1, "stale", 9_999)))

        upload(api, f)

        assertEquals(2, bucket.requestCount)
        assertEquals("etag-1", api.completed!![0].etag)
    }

    @Test fun `a network or server error on a part is retried`() {
        val (f, bytes) = file(20_000)
        failOnce += 2

        upload(FakeApi(10_000), f)

        assertEquals(3, bucket.requestCount)
        assertTrue(bytes.copyOfRange(10_000, 20_000).contentEquals(received[2]))
    }

    @Test fun `a rejected URL is replaced with a fresh one`() {
        val (f, _) = file(20_000)
        rejectOnce += 1
        val api = FakeApi(10_000)

        upload(api, f)

        assertEquals(2, received.size)
        assertTrue("URLs were asked for again", api.urlRequests >= 3)
    }

    @Test fun `a part that keeps failing ends the upload without completing it`() {
        val (f, _) = file(20_000)
        bucket.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        val api = FakeApi(10_000)

        try { upload(api, f); fail("expected a failure") } catch (expected: java.io.IOException) { /* surfaced to the caller */ }

        assertEquals(null, api.completed)
        assertEquals("4 attempts, then give up", 4, bucket.requestCount)
    }

    @Test fun `an upload the server lost is started again once`() {
        val (f, _) = file(10_000)
        val api = FakeApi(10_000)
        var calls = 0
        val flaky = object : R2UploadApi by api {
            override suspend fun parts(uploadId: String, from: Int, count: Int): R2Parts {
                if (calls++ == 0) throw R2ApiException(410, "upload_gone")
                return api.parts(uploadId, from, count)
            }
        }
        api.fileSize = f.length()

        runBlocking { RandomAccessFile(f, "r").use { R2Uploader(flaky, retryDelayMs = { 0 }).upload("local-1", "film.mkv", "video/mp4", it.channel) { _, _ -> } } }

        assertEquals(2, api.started)
        assertEquals(1, received.size)
    }

    @Test fun `refusals are explained in words and never echo the server`() {
        assertEquals("Your Cloudflare storage is disconnected. Connect it again in Storage.", R2ApiException(409, "storage_not_connected").friendly)
        assertTrue(R2ApiException(502, "storage_unavailable").friendly.contains("Try again later"))
        assertTrue(R2ApiException(418, "strange").friendly.endsWith("(strange)."))
    }
}

package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

class DriveUploaderTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var drive: MockWebServer

    /** What the fake Drive session holds, and what the phone sent it. */
    private var stored = java.io.ByteArrayOutputStream()
    private val ranges = CopyOnWriteArrayList<String>()
    private val authHeaders = CopyOnWriteArrayList<String?>()
    private var failNext = 0
    private var expired = false
    /** Accepts only this many bytes of the next chunk, like Drive when a connection drops part way. */
    private var acceptOnly: Int? = null

    @Before fun start() {
        drive = MockWebServer()
        drive.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                authHeaders += request.getHeader("Authorization")
                val header = request.getHeader("Content-Range")!!
                ranges += header
                if (expired) return MockResponse().setResponseCode(404)
                if (failNext > 0) { failNext--; return MockResponse().setResponseCode(503) }
                val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header) ?: return MockResponse().setResponseCode(400)
                val (from, to, total) = m.destructured.toList().map { it.toLong() }
                if (from != stored.size().toLong()) return MockResponse().setResponseCode(400)
                var body = request.body.readByteArray()
                if (body.size.toLong() != to - from + 1) return MockResponse().setResponseCode(400)
                acceptOnly?.let { body = body.copyOf(it); acceptOnly = null }
                stored.write(body)
                return if (stored.size().toLong() >= total) MockResponse().setResponseCode(200).setBody("{}")
                else MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${stored.size() - 1}")
            }
        }
        drive.start()
    }

    @After fun stop() = drive.shutdown()

    private inner class FakeApi(val chunk: Long = 256 * 1024L, var received: Long = 0) : OwnCloudApi {
        var started = 0
        var sessions = 0
        var completed = 0
        var aborted = 0
        var protocol = "gdrive_resumable"
        var fileSize = 0L
        override suspend fun start(originIdentity: String, sizeBytes: Long, contentType: String, fileName: String): R2UploadStart {
            started++
            return R2UploadStart("up-1", protocol = protocol, chunkSize = chunk, partSize = chunk, partCount = ((sizeBytes + chunk - 1) / chunk).toInt())
        }
        override suspend fun session(uploadId: String): DriveSession {
            sessions++
            // The server asks Drive; here that is the bytes the fake session really holds.
            return DriveSession(drive.url("/upload/drive/v3/files?uploadType=resumable&upload_id=s1").toString(), maxOf(received, stored.size().toLong()))
        }
        override suspend fun complete(uploadId: String): R2Done { completed++; return R2Done("source-1", "item-1", fileSize) }
        override suspend fun abort(uploadId: String) { aborted++; if (expired) { expired = false; stored = java.io.ByteArrayOutputStream() } }
        override suspend fun parts(uploadId: String, from: Int, count: Int) = R2Parts()
        override suspend fun complete(uploadId: String, parts: List<R2PartRef>): R2Done = fail("S3 completion on a Drive upload") as R2Done
    }

    private fun file(size: Int): Pair<File, ByteArray> {
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        return folder.newFile().also { it.writeBytes(bytes) } to bytes
    }

    private fun upload(api: FakeApi, f: File, progress: MutableList<Long> = mutableListOf()) = runBlocking {
        api.fileSize = f.length()
        RandomAccessFile(f, "r").use { raf ->
            OwnCloudUploader(api, retryDelayMs = { 0 }).upload("local-1", "film.mkv", "video/x-matroska", raf.channel) { sent, _ -> progress += sent }
        }
    }

    @Test fun `chunks go to the session byte for byte with Content-Range, and no token is sent`() {
        val (f, bytes) = file(600 * 1024) // 256 KiB chunks: 256, 256, 88 KiB
        val api = FakeApi()
        val progress = mutableListOf<Long>()

        val done = upload(api, f, progress)

        assertEquals("source-1", done.sourceId)
        assertEquals(listOf("bytes 0-262143/614400", "bytes 262144-524287/614400", "bytes 524288-614399/614400"), ranges.toList())
        assertTrue(bytes.contentEquals(stored.toByteArray()))
        assertTrue("the session URL alone is the capability", authHeaders.all { it == null })
        assertEquals("completed once, by the server's decision", 1, api.completed)
        assertEquals(614400L, progress.last())
        assertTrue("progress never goes backwards", progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `an interrupted upload resumes from the offset the server reports`() {
        val (f, bytes) = file(600 * 1024)
        stored.write(bytes, 0, 262_144)
        val api = FakeApi()
        val progress = mutableListOf<Long>()

        upload(api, f, progress)

        assertEquals(listOf("bytes 262144-524287/614400", "bytes 524288-614399/614400"), ranges.toList())
        assertEquals(262_144L, progress.first())
        assertTrue(bytes.contentEquals(stored.toByteArray()))
    }

    @Test fun `a chunk Drive only partly kept is continued from Drive's own count`() {
        val (f, bytes) = file(600 * 1024)
        acceptOnly = 100 * 1024
        val api = FakeApi()

        upload(api, f)

        assertEquals("bytes 0-262143/614400", ranges[0])
        assertEquals("the next chunk starts where Drive says", "bytes 102400-364543/614400", ranges[1])
        assertTrue(bytes.contentEquals(stored.toByteArray()))
    }

    @Test fun `a server error is retried after asking where the upload stands`() {
        val (f, bytes) = file(300 * 1024)
        failNext = 1
        val api = FakeApi()

        upload(api, f)

        assertEquals("the failed chunk is sent again", ranges[0], ranges[1])
        assertEquals("the offset was asked for again", 2, api.sessions)
        assertTrue(bytes.contentEquals(stored.toByteArray()))
    }

    @Test fun `a chunk that keeps failing ends the upload without completing it`() {
        val (f, _) = file(300 * 1024)
        failNext = 99
        val api = FakeApi()

        try { upload(api, f); fail("expected a failure") } catch (expected: java.io.IOException) { /* surfaced to the caller */ }

        assertEquals(0, api.completed)
        assertEquals("10 attempts, then give up", 10, drive.requestCount)
    }

    @Test fun `a server that cannot be reached while recovering does not end the upload`() {
        val (f, bytes) = file(300 * 1024)
        failNext = 1
        val api = FakeApi()
        val flaky = object : OwnCloudApi by api {
            var asked = 0
            override suspend fun session(uploadId: String): DriveSession {
                // The first call starts the upload; the second is the check after the failed chunk, with the network still down.
                if (++asked == 2) throw java.io.IOException("Failed to connect to the server")
                return api.session(uploadId)
            }
        }
        api.fileSize = f.length()

        runBlocking { RandomAccessFile(f, "r").use { OwnCloudUploader(flaky, retryDelayMs = { 0 }).upload("local-1", "film.mkv", "video/mp4", it.channel) { _, _ -> } } }

        assertTrue(bytes.contentEquals(stored.toByteArray()))
        assertEquals(1, api.completed)
    }

    @Test fun `the waits between attempts grow to half a minute`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(::backoffMs))
    }

    @Test fun `an expired session is cancelled and the upload starts again once`() {
        val (f, bytes) = file(300 * 1024)
        expired = true
        val api = FakeApi()

        upload(api, f)

        assertEquals(2, api.started)
        assertEquals(1, api.aborted)
        assertTrue(bytes.contentEquals(stored.toByteArray()))
        assertEquals(1, api.completed)
    }

    @Test fun `a finished session is not sent again, only completed`() {
        val (f, bytes) = file(300 * 1024)
        stored.write(bytes)
        val api = FakeApi()

        upload(api, f)

        assertEquals(0, drive.requestCount)
        assertEquals(1, api.completed)
    }

    @Test fun `the protocol the server names picks the uploader`() {
        val (f, _) = file(10_000)
        val unknown = FakeApi().apply { protocol = "carrier_pigeon" }
        try { upload(unknown, f); fail("expected a refusal") } catch (e: R2ApiException) { assertEquals("unsupported_protocol", e.code) }
        assertEquals(0, drive.requestCount)

        // s3_multipart goes to the R2 uploader, which asks for parts, not a session.
        val s3 = FakeApi().apply { protocol = "s3_multipart" }
        try { upload(s3, f) } catch (_: Exception) { /* the fake has no bucket; only the route taken matters */ }
        assertEquals("no Drive session for an S3 upload", 0, s3.sessions)
        assertEquals(0, drive.requestCount)
    }

    @Test fun `a chunk size Drive would refuse is never used`() {
        val (f, _) = file(10_000)
        try { upload(FakeApi(chunk = 100_000), f); fail("expected a refusal") } catch (expected: IllegalArgumentException) { /* not a multiple of 256 KiB */ }
        assertEquals(0, drive.requestCount)
    }

    @Test fun `the session URL never reaches a log line`() {
        val session = DriveSession("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=SECRET", 5)
        assertFalse(session.toString().contains("SECRET"))
        assertFalse(session.toString().contains("googleapis"))
        assertNull(R2ApiException(409, "x").message?.takeIf { it.contains("googleapis") })
    }

    @Test fun `refusals name the provider the video was going to`() {
        assertTrue(R2ApiException(507, "insufficient_storage").friendly("Google Drive").contains("Google Drive doesn't have enough free space"))
        assertTrue(R2ApiException(409, "reauthorization_required").friendly("Google Drive").contains("needs you to sign in again"))
        assertTrue(R2ApiException(429, "provider_rate_limited").friendly("Google Drive").contains("Try again later"))
        assertTrue(R2ApiException(409, "upload_incomplete").friendly("Google Drive").contains("Save it again"))
        assertEquals("Your Google Drive storage is disconnected. Connect it again in Storage.", R2ApiException(409, "storage_not_connected").friendly("Google Drive"))
        assertFalse(R2ApiException(502, "storage_unavailable").friendly("Google Drive").contains("Cloudflare"))
    }
}

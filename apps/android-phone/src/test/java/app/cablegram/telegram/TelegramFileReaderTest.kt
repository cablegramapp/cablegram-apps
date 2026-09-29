package app.cablegram.telegram

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests (spec 004). Keep both copies identical.

class TelegramFileReaderTest {
    private val mb = TelegramFileReader.MB

    @Test
    fun `range header parsing covers what players send`() {
        assertEquals(ByteRange.Full(1000), ByteRange.parse(null, 1000))
        assertEquals(ByteRange.Partial(0, 999, 1000), ByteRange.parse("bytes=0-", 1000))
        assertEquals(ByteRange.Partial(100, 199, 1000), ByteRange.parse("bytes=100-199", 1000))
        assertEquals(ByteRange.Partial(900, 999, 1000), ByteRange.parse("bytes=900-5000", 1000))
        assertEquals(ByteRange.Partial(900, 999, 1000), ByteRange.parse("bytes=-100", 1000))
        assertEquals(ByteRange.Unsatisfiable(1000), ByteRange.parse("bytes=1000-", 1000))
        assertEquals(ByteRange.Unsatisfiable(1000), ByteRange.parse("bytes=500-400", 1000))
        assertEquals(ByteRange.Full(1000), ByteRange.parse("items=0-1", 1000))
        assertEquals("bytes 100-199/1000", (ByteRange.parse("bytes=100-199", 1000) as ByteRange.Partial).contentRange)
    }

    @Test
    fun `reads wait for Telegram and never ask for more than a window`() = runTest {
        val api = FakeTelegramApi()
        api.addFile(1, size = 4096)
        val reader = TelegramFileReader(api, window = 1024, refillAt = 256, clock = { testScheduler.currentTime })

        val read = async { reader.read(1, position = 0, want = 512) }
        runCurrent()
        assertEquals(listOf("download:1@0+1024"), api.calls)
        api.deliver(1, bytes = 300)
        val bytes = read.await()!!
        assertEquals("only what is downloaded is served", 300, bytes.size)
        assertArrayEquals(api.content.getValue(1).copyOfRange(0, 300), bytes)
        assertEquals(300, reader.bytesServed.get())
    }

    @Test
    fun `the window moves with the reader`() = runTest {
        val api = FakeTelegramApi()
        api.addFile(1, size = 8192)
        val reader = TelegramFileReader(api, window = 1024, refillAt = 256, clock = { testScheduler.currentTime })
        assertFalse(reader.needsMove(0, 700))
        assertTrue("past window - refillAt", reader.needsMove(0, 800))
        assertTrue("seek backwards", reader.needsMove(1000, 10))
        assertTrue(reader.needsMove(null, 0))

        api.deliver(1, 1024)
        reader.read(1, 0, 100)
        api.files[1] = api.files.getValue(1).copy(downloadedPrefixSize = 8192) // everything present
        reader.read(1, 900, 100)
        assertEquals(listOf("download:1@0+1024", "download:1@900+1024"), api.calls)
    }

    @Test
    fun `the local copy is dropped once it exceeds the storage cap`() = runTest {
        val api = FakeTelegramApi()
        api.addFile(1, size = 64 * 1024)
        val reader = TelegramFileReader(api, window = 1024, refillAt = 256, maxStored = 2048, clock = { testScheduler.currentTime })
        api.deliver(1, bytes = 1024, stored = 1024)
        reader.read(1, 0, 10)
        // A seek far ahead after 3 KB were stored: drop, then download at the new position.
        api.files[1] = api.files.getValue(1).copy(downloadedSize = 3072)
        val far = async { reader.read(1, 40_000, 10) }
        runCurrent()
        assertEquals(
            listOf("download:1@0+1024", "cancelDownload:1", "deleteLocalFile:1", "download:1@40000+1024"),
            api.calls,
        )
        api.deliver(1, bytes = 1024)
        assertEquals(10, far.await()!!.size)
    }

    @Test
    fun `a failed read is retried instead of ending the stream`() = runTest {
        val api = FakeTelegramApi()
        api.addFile(1, size = 4096)
        api.deliver(1, bytes = 4096)
        api.failReadsRemaining = 2
        val reader = TelegramFileReader(api, window = 1024, refillAt = 256, clock = { testScheduler.currentTime })
        assertEquals(64, reader.read(1, 0, 64)!!.size)
    }

    @Test
    fun `nothing arriving ends the read with null after the timeout`() = runTest {
        val api = FakeTelegramApi()
        api.addFile(1, size = 4096)
        val reader = TelegramFileReader(api, window = 1024, refillAt = 256, timeoutMs = 5_000, clock = { testScheduler.currentTime })
        assertNull(reader.read(1, 0, 64))
    }

    @Test
    fun `release cancels and deletes the local copy`() = runTest {
        val api = FakeTelegramApi()
        api.addFile(7, size = 10)
        TelegramFileReader(api).release(7)
        assertEquals(listOf("cancelDownload:7", "deleteLocalFile:7"), api.calls)
    }

    @Test
    fun `available bytes only count the contiguous prefix of the window`() {
        val f = TgFile(1, 10 * mb, "u", downloadOffset = 2 * mb, downloadedPrefixSize = mb, downloadedSize = 5 * mb, completed = false)
        assertEquals(0, TelegramFileReader.availableAt(f, 0))
        assertEquals(mb, TelegramFileReader.availableAt(f, 2 * mb))
        assertEquals(1, TelegramFileReader.availableAt(f, 3 * mb - 1))
        assertEquals(0, TelegramFileReader.availableAt(f, 3 * mb))
    }
}

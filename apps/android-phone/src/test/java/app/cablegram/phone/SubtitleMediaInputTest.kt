package app.cablegram.phone

import android.media.MediaExtractor
import android.media.MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class SubtitleMediaInputTest {
    /** Delivers at most [chunk] bytes per read, like a Telegram window that has only part of the range yet. */
    private class Chunked(private val data: ByteArray, private val chunk: Int, private val failAt: Long = -1) : SubtitleMediaInput {
        override val size = data.size.toLong()
        override fun attach(extractor: MediaExtractor) = error("not used")
        override fun attach(player: MediaPlayer) = error("not used")
        override fun readAt(position: Long, length: Int): ByteArray? {
            if (position == failAt || position >= data.size) return null
            val n = minOf(length, chunk, data.size - position.toInt())
            return data.copyOfRange(position.toInt(), position.toInt() + n)
        }
        override fun close() {}
    }

    private fun reference(data: ByteArray): String {
        var total = data.size.toLong()
        for (start in listOf(0, data.size - 65536)) {
            val buffer = ByteBuffer.wrap(data, start, 65536).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 8) total += buffer.long
        }
        return java.lang.Long.toUnsignedString(total, 16).padStart(16, '0')
    }

    @Test fun hashMatchesTheProviderAlgorithmOverShortReads() {
        val data = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
        assertEquals(reference(data), LocalSubtitleAnalysis.hash(Chunked(data, chunk = 4096)))
        assertEquals(reference(data), LocalSubtitleAnalysis.hash(Chunked(data, chunk = 1 shl 20)))
    }

    @Test fun hashIsUnavailableWhenTheTailCannotBeRead() {
        val data = ByteArray(300_000) { it.toByte() }
        assertNull(LocalSubtitleAnalysis.hash(Chunked(data, chunk = 4096, failAt = 300_000L - 65536)))
        assertNull(LocalSubtitleAnalysis.hash(Chunked(ByteArray(1000), chunk = 100)))
    }
}

class TelegramMediaInputTest {
    private class Stuck : TelegramMedia {
        val started = java.util.concurrent.CountDownLatch(1)
        override fun resolve(uniqueId: String): TelegramFileRef? = null
        override fun read(fileId: Int, position: Long, want: Int): ByteArray? { started.countDown(); Thread.sleep(60_000); return null }
    }

    @Test fun aReadThatNeverReturnsIsAbandonedAsSoonAsTheInputIsClosed() {
        val media = Stuck(); val input = TelegramMediaInput(media, TelegramFileRef(1, 1_000_000, "video/mp4"))
        val result = java.util.concurrent.atomic.AtomicReference<Any?>("pending")
        val reader = Thread { try { result.set(input.readAt(0, 1024)) } catch (t: Throwable) { result.set(t) } }.also { it.start() }
        assertEquals(true, media.started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val closedAt = System.nanoTime(); input.close(); reader.join(3_000)
        assertEquals(false, reader.isAlive); assertNull(result.get())
        assertEquals(true, (System.nanoTime() - closedAt) / 1_000_000 < 2_000)
        assertNull(input.readAt(0, 1024))
    }
}

class TelegramChunkingTest {
    private class Counting(val data: ByteArray) : TelegramMedia {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        override fun resolve(uniqueId: String): TelegramFileRef? = null
        override fun read(fileId: Int, position: Long, want: Int): ByteArray? {
            calls.incrementAndGet()
            if (position >= data.size) return null
            val n = minOf(want, data.size - position.toInt(), 300_000)  // Telegram often has only part of a range ready
            return data.copyOfRange(position.toInt(), position.toInt() + n)
        }
    }

    @Test fun thousandsOfTinyReadsBecomeAFewTelegramCalls() {
        val data = ByteArray(3 * 1024 * 1024 + 123) { (it * 7).toByte() }
        val media = Counting(data); val input = TelegramMediaInput(media, TelegramFileRef(1, data.size.toLong(), "video/mp4"))
        var position = 0L
        repeat(4_000) {
            val got = input.readAt(position, 700) ?: error("read failed at $position")
            assertEquals(data.copyOfRange(position.toInt(), position.toInt() + got.size).toList(), got.toList())
            position += 700
        }
        assertEquals(true, media.calls.get() <= 16)
        assertEquals(true, input.bytesFetched >= 2_800_000L)
        input.close()
    }

    @Test fun aReadAcrossAChunkBoundaryAndTheEndOfTheFileIsExact() {
        val data = ByteArray(1024 * 1024 + 500) { (it % 251).toByte() }
        val input = TelegramMediaInput(Counting(data), TelegramFileRef(1, data.size.toLong(), "video/mp4"))
        val across = input.readAt(1024L * 1024 - 100, 400)!!
        assertEquals(data.copyOfRange(1024 * 1024 - 100, 1024 * 1024 + 300).toList(), across.toList())
        assertEquals(data.copyOfRange(data.size - 50, data.size).toList(), input.readAt(data.size - 50L, 1000)!!.toList())
        assertNull(input.readAt(data.size.toLong(), 10))
        input.close()
    }
}

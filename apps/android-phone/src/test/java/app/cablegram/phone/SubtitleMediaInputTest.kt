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

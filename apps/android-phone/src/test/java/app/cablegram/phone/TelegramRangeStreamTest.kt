package app.cablegram.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramRangeStreamTest {
    private val data = ByteArray(3_000_000) { (it % 251).toByte() }
    private val reads = mutableListOf<Pair<Long, Int>>()
    private val source = { position: Long, want: Int ->
        reads += position to want
        data.copyOfRange(position.toInt(), minOf(data.size, position.toInt() + want))
    }

    @Test
    fun `it serves exactly the requested range, reading no more than needed`() {
        val bytes = TelegramRangeStream(source, 1_500_000, 2_500_000).readBytes()
        assertArrayEquals(data.copyOfRange(1_500_000, 2_500_001), bytes)
        assertEquals(listOf(1_500_000L to 1_000_001), reads)
    }

    @Test
    fun `it ends early when Telegram delivers nothing`() {
        var calls = 0
        val stream = TelegramRangeStream({ _, _ -> if (calls++ == 0) ByteArray(10) else null }, 0, 999)
        assertEquals(10, stream.readBytes().size)
    }
}

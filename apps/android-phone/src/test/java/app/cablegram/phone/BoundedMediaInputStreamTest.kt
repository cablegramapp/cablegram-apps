package app.cablegram.phone

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedMediaInputStreamTest {
    @Test fun forwardsBulkReadsAndStopsAtRangeBoundary() {
        var bulkReads = 0
        val source = object : ByteArrayInputStream(ByteArray(65536) { (it % 251).toByte() }) {
            override fun read(): Int = error("Media must not be read byte by byte")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                bulkReads++
                return super.read(buffer, offset, length)
            }
        }
        source.skip(100)
        val stream = BoundedMediaInputStream(source, 32768)
        val buffer = ByteArray(65536)
        assertEquals(32768, stream.read(buffer, 7, 50000))
        assertArrayEquals(ByteArray(32768) { ((it + 100) % 251).toByte() }, buffer.copyOfRange(7, 32775))
        assertEquals(-1, stream.read(buffer))
        assertEquals(0, stream.read(buffer, 0, 0))
        assertEquals(1, bulkReads)
        assertEquals(65536 - 100 - 32768, source.available())
    }

    @Test fun handlesShortReadsAndEarlyEof() {
        val stream = BoundedMediaInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 20)
        assertEquals(1, stream.read())
        assertEquals(2, stream.read(ByteArray(20)))
        assertEquals(-1, stream.read(ByteArray(20)))
        assertEquals(-1, stream.read())
    }

    @Test fun emptyRangeDoesNotReadSource() {
        val source = ByteArrayInputStream(byteArrayOf(1))
        assertEquals(-1, BoundedMediaInputStream(source, 0).read())
        assertEquals(1, source.available())
    }

    @Test fun closesDescriptorEvenIfInputCloseFails() {
        var closed = false
        val source = object : InputStream() {
            override fun read() = -1
            override fun close() { throw IOException("close failed") }
        }
        val stream = BoundedMediaInputStream(source, 1) { closed = true }
        assertThrows(IOException::class.java) { stream.close() }
        assertTrue(closed)
    }
}

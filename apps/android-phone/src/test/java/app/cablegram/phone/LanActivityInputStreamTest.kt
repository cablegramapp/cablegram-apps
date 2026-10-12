package app.cablegram.phone

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LanActivityInputStreamTest {
    @Test fun forwardsBytesAndReportsCompletionOnlyOnce() {
        var finished = 0
        val stream = LanActivityInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) { finished++ }
        val bytes = ByteArray(3)
        assertEquals(3, stream.read(bytes))
        assertEquals(2, bytes[1].toInt())
        assertEquals(0, finished)
        stream.close()
        stream.close()
        assertEquals(1, finished)
    }

    @Test fun failedCloseStillReleasesStream() {
        var finished = 0
        val stream = LanActivityInputStream(object : InputStream() {
            override fun read() = -1
            override fun close() { throw IOException("closed") }
        }) { finished++ }
        runCatching { stream.close() }
        stream.close()
        assertEquals(1, finished)
    }
}

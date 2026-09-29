package app.cablegram.phone

import java.io.InputStream

/** Keeps HTTP range boundaries while forwarding bulk reads to the file descriptor. */
internal class BoundedMediaInputStream(
    private val input: InputStream,
    length: Long,
    private val onClose: () -> Unit = {},
) : InputStream() {
    private var remaining = length.coerceAtLeast(0)

    override fun read(): Int {
        if (remaining == 0L) return -1
        return input.read().also { if (it >= 0) remaining-- else remaining = 0 }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) remaining = 0 else remaining -= count
        return count
    }

    override fun close() {
        try {
            input.close()
        } finally {
            onClose()
        }
    }
}

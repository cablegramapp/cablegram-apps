package app.cablegram.phone

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** NanoHTTPD closes the response body when sending ends or the client disconnects. */
internal class LanActivityInputStream(
    input: InputStream,
    private val onFinished: () -> Unit,
) : FilterInputStream(input) {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            super.close()
        } finally {
            onFinished()
        }
    }
}

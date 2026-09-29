package app.cablegram

import app.cablegram.data.PairLog
import app.cablegram.telegram.ByteRange
import app.cablegram.telegram.TelegramApi
import app.cablegram.telegram.TelegramFileReader
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * Serves Telegram files to LibVLC on 127.0.0.1 with HTTP Range (spec 004 FR-007):
 * `GET /tg/<fileId>`. Bytes come from [TelegramFileReader], which keeps only a window of the file
 * ahead of the player on the TV's small storage. Not reachable from the network.
 */
class TelegramStreamServer(private val api: TelegramApi) : NanoHTTPD("127.0.0.1", 0) {
    val reader = TelegramFileReader(api)
    @Volatile private var currentFileId: Int? = null

    fun urlFor(fileId: Int): String = "http://127.0.0.1:$listeningPort/tg/$fileId"

    /** Playback stopped: delete what is stored of the file being played. */
    fun releaseCurrent() {
        val fileId = currentFileId ?: return
        currentFileId = null
        runBlocking { reader.release(fileId) }
    }

    override fun serve(session: IHTTPSession): Response {
        val fileId = session.uri.removePrefix("/tg/").toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        val previous = currentFileId
        if (previous != null && previous != fileId) runBlocking { reader.release(previous) }
        currentFileId = fileId
        val size = runCatching { runBlocking { api.file(fileId).size } }.getOrDefault(0L)
        if (size <= 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown file")
        return when (val range = ByteRange.parse(session.headers["range"], size)) {
            is ByteRange.Unsatisfiable -> newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                addHeader("Content-Range", range.contentRange)
            }
            is ByteRange.Partial -> newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT, "application/octet-stream",
                FileStream(fileId, range.start, range.end), range.length,
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", range.contentRange)
            }
            is ByteRange.Full -> newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream", FileStream(fileId, 0, size - 1), size,
            ).apply { addHeader("Accept-Ranges", "bytes") }
        }
    }

    /**
     * Players read ~16 KB at a time and every TDLib read is several round-trips, so read 1 MB and serve
     * from it (spike: 7 → 15–25 Mbit/s).
     */
    private inner class FileStream(private val fileId: Int, private var position: Long, private val end: Long) : InputStream() {
        private var buffer = ByteArray(0)
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position > end) return -1
            if (offset >= buffer.size) {
                val want = minOf(end - position + 1, CHUNK).toInt()
                buffer = runBlocking { reader.read(fileId, position, want) } ?: run {
                    PairLog.e("Telegram stream: no data for file $fileId at $position")
                    return -1
                }
                offset = 0
            }
            val n = minOf(len, buffer.size - offset)
            System.arraycopy(buffer, offset, b, off, n)
            offset += n
            position += n
            return n
        }
    }

    private companion object {
        const val CHUNK = 1024L * 1024
    }
}

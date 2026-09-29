package app.cablegram.telegram

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical.

/**
 * Random-access reads of Telegram files for a local HTTP Range server (spec 004 FR-007).
 *
 * TDLib, left alone, downloads a whole file as fast as it can: on a Chromecast that filled the
 * storage and froze playback (spike, 2026-09-29). So only a window ahead of the reader is
 * requested, it moves with the reader, and once more than [maxStored] bytes of a file are on disk
 * the local copy is dropped and the window restarts at the reader's position.
 */
class TelegramFileReader(
    private val api: TelegramApi,
    private val window: Long = 48L * MB,
    private val refillAt: Long = 16L * MB,
    private val maxStored: Long = 256L * MB,
    private val timeoutMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val windowStart = ConcurrentHashMap<Int, Long>()
    val bytesServed = AtomicLong(0)

    /**
     * Up to [want] bytes of [fileId] at [position], waiting for Telegram to deliver them.
     * Returns null if nothing arrives within the timeout.
     */
    suspend fun read(fileId: Int, position: Long, want: Int): ByteArray? {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            ensureWindow(fileId, position)
            val file = api.file(fileId)
            val available = availableAt(file, position)
            if (available > 0) {
                val count = minOf(want.toLong(), available)
                val bytes = try {
                    api.readPart(fileId, position, count)
                } catch (e: TgException) {
                    // The window can move between the check and the read; look again shortly.
                    delay(RETRY_MS)
                    continue
                }
                if (bytes.isNotEmpty()) {
                    bytesServed.addAndGet(bytes.size.toLong())
                    return bytes
                }
            }
            withTimeoutOrNull(WAIT_MS) { api.fileUpdates.first { it.id == fileId } }
        }
        return null
    }

    /** Drops what is stored for [fileId] (playback stopped). */
    suspend fun release(fileId: Int) {
        windowStart.remove(fileId)
        runCatching { api.cancelDownload(fileId) }
        runCatching { api.deleteLocalFile(fileId) }
    }

    private suspend fun ensureWindow(fileId: Int, position: Long) {
        val start = windowStart[fileId]
        if (!needsMove(start, position)) return
        val file = api.file(fileId)
        if (file.downloadedSize > maxStored) {
            runCatching { api.cancelDownload(fileId) }
            runCatching { api.deleteLocalFile(fileId) }
        }
        windowStart[fileId] = position
        api.download(fileId, position, window)
    }

    internal fun needsMove(start: Long?, position: Long): Boolean =
        start == null || position < start || position > start + window - refillAt

    companion object {
        const val MB = 1024L * 1024
        private const val WAIT_MS = 500L
        private const val RETRY_MS = 250L

        /** Bytes readable at [position] from what TDLib has contiguously downloaded. */
        fun availableAt(file: TgFile, position: Long): Long {
            val start = file.downloadOffset
            val end = start + file.downloadedPrefixSize
            return if (position in start until end) end - position else 0
        }
    }
}

/** A parsed HTTP `Range` header against a file of [size] bytes. */
sealed interface ByteRange {
    data class Full(val size: Long) : ByteRange
    data class Partial(val start: Long, val end: Long, val size: Long) : ByteRange {
        val length: Long get() = end - start + 1
        val contentRange: String get() = "bytes $start-$end/$size"
    }
    data class Unsatisfiable(val size: Long) : ByteRange {
        val contentRange: String get() = "bytes */$size"
    }

    companion object {
        /** Single ranges only (what players send); anything malformed is served as the full file. */
        fun parse(header: String?, size: Long): ByteRange {
            if (header == null || !header.startsWith("bytes=") || size <= 0) return Full(size)
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            val first = spec.substringBefore('-').trim().toLongOrNull()
            val last = spec.substringAfter('-', "").trim().toLongOrNull()
            return when {
                first != null && first >= size -> Unsatisfiable(size)
                first != null -> Partial(first, minOf(last ?: (size - 1), size - 1), size).takeIf { it.start <= it.end } ?: Unsatisfiable(size)
                last != null && last > 0 -> Partial(maxOf(0, size - last), size - 1, size)
                else -> Full(size)
            }
        }
    }
}

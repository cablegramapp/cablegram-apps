package app.cablegram.phone

import android.content.Context
import app.cablegram.telegram.TelegramFileReader
import app.cablegram.telegram.TelegramState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** A Telegram video the phone can stream to a TV that holds no Telegram session itself. */
data class TelegramFileRef(val fileId: Int, val size: Long, val mime: String)

/** What [LanLibraryServer] needs to serve `/telegram/<file id>` (spec 004 US8). Blocking: the server is threaded. */
interface TelegramMedia {
    /** Resolves Telegram's unique file id (the part after `tgfile:`) to a readable file, or null. */
    fun resolve(uniqueId: String): TelegramFileRef?
    /** Up to [want] bytes at [position], or null if Telegram delivered nothing in time. */
    fun read(fileId: Int, position: Long, want: Int): ByteArray?
}

/**
 * Serves Telegram titles from this phone's own session, for temporary TVs that must not hold one.
 * Only the household's library channel is read (FR-005). The window reader keeps at most a small part
 * of the file on the phone, and it is dropped a minute after the last request.
 */
class PhoneTelegramMedia(
    private val context: Context,
    /**
     * Whether the household hid or deleted this source (`tgfile:<unique id>`). A removed title is never served,
     * even to a TV that still holds a capability for this phone (review fix 6).
     */
    private val isRemoved: suspend (stableSourceKey: String) -> Boolean,
    private val chatId: suspend () -> Long?,
) : TelegramMedia {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val known = ConcurrentHashMap<String, TelegramFileRef>()
    private val reader by lazy { TelegramFileReader(PhoneTelegram.session(context).api) }
    /** Last read per file: several TVs can stream different titles at once (review fix 7). */
    private val lastUsed = ConcurrentHashMap<Int, Long>()
    @Volatile private var releaseJobRunning = false

    override fun resolve(uniqueId: String): TelegramFileRef? = runBlocking {
        if (!PhoneTelegram.wasLinked(context)) return@runBlocking null
        if (isRemoved("tgfile:$uniqueId")) return@runBlocking null
        val session = PhoneTelegram.session(context)
        if (withTimeoutOrNull(20_000) { session.state.first { it is TelegramState.Ready } } == null) return@runBlocking null
        known[uniqueId]?.let { return@runBlocking it }
        val channel = chatId() ?: return@runBlocking null
        app.cablegram.telegram.TelegramLibrarySync.forEachVideo(session.api, channel) { video ->
            known[video.file.uniqueId] = TelegramFileRef(video.file.id, video.file.size, video.mimeType.ifBlank { "video/mp4" })
            video.file.uniqueId == uniqueId
        }
        known[uniqueId]
    }

    override fun read(fileId: Int, position: Long, want: Int): ByteArray? = runBlocking {
        touch(fileId)
        reader.read(fileId, position, want)
    }

    /** Each file's window is dropped a minute after its own last read, not when another file is read. */
    private fun touch(fileId: Int) {
        lastUsed[fileId] = System.currentTimeMillis()
        if (releaseJobRunning) return
        releaseJobRunning = true
        scope.launch {
            while (true) {
                delay(10_000)
                val now = System.currentTimeMillis()
                for ((id, at) in lastUsed) {
                    if (now - at >= IDLE_RELEASE_MS && lastUsed.remove(id, at)) reader.release(id)
                }
                if (lastUsed.isEmpty()) {
                    releaseJobRunning = false
                    // A read that arrived meanwhile saw the job still running; keep going for it.
                    if (lastUsed.isEmpty()) break
                    releaseJobRunning = true
                }
            }
        }
    }

    private companion object {
        const val IDLE_RELEASE_MS = 60_000L
    }
}

/** The bytes `[start, end]` of a Telegram file, read in [CHUNK]-sized pieces (players read ~16 KB at a time). */
class TelegramRangeStream(
    private val read: (position: Long, want: Int) -> ByteArray?,
    private var position: Long,
    private val end: Long,
) : InputStream() {
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
            buffer = read(position, want)?.takeIf { it.isNotEmpty() } ?: return -1
            offset = 0
        }
        val n = minOf(len, buffer.size - offset)
        System.arraycopy(buffer, offset, b, off, n)
        offset += n
        position += n
        return n
    }

    companion object {
        const val CHUNK = 1024L * 1024
    }
}

/**
 * The household's hidden and deleted Telegram sources, as the control plane lists them (review fix 6).
 * Refreshed at most once a minute. Until it has been loaded once, every source counts as removed: serving
 * nothing is safer than serving a video the owner hid.
 */
class RemovedTelegramSources(private val client: CatalogClient, private val tokens: AccountTokens) {
    @Volatile private var keys: Set<String>? = null
    @Volatile private var loadedAt = 0L

    suspend fun contains(stableSourceKey: String): Boolean {
        if (keys == null || System.currentTimeMillis() - loadedAt > REFRESH_MS) {
            val token = tokens.accountToken
            val latest = token?.let { client.telegramTombstones(it) }
            if (latest != null) {
                keys = latest.map { it.stableSourceKey }.toSet()
                loadedAt = System.currentTimeMillis()
            }
        }
        return keys?.contains(stableSourceKey) ?: true
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}


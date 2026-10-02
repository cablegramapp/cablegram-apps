package app.cablegram.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

// ---- Your own Google Drive storage (spec 006, contracts/cloud-storage.md) ----

/**
 * Where a Drive upload stands. The session URL is a capability for that one upload (FR-009): it is never logged,
 * so [toString] leaves it out.
 */
@Serializable
data class DriveSession(
    @SerialName("session_url") val sessionUrl: String,
    @SerialName("received_bytes") val receivedBytes: Long,
) {
    override fun toString() = "DriveSession(receivedBytes=$receivedBytes)"
}

/** The control plane's side of a Drive upload. Drive itself is asked for the offset by the server, not by the phone. */
interface DriveUploadApi {
    suspend fun session(uploadId: String): DriveSession
    /** Completion is decided by the server from Drive's answer; the phone sends no file id. */
    suspend fun complete(uploadId: String): R2Done
    suspend fun abort(uploadId: String)
}

/** The control plane's whole Save to Cloud API: one `start`, then the calls of the protocol it names. */
interface OwnCloudApi : R2UploadApi, DriveUploadApi

/** Drive refused a chunk with a client error; [status] is its HTTP code. Carries no URL. */
private class ChunkRefused(val status: Int) : IOException("Drive refused the chunk ($status)")

/**
 * No redirects: Drive answers a partly received chunk with 308, which is a status, not a redirect. The write and read
 * waits are per socket operation, not per chunk, so 30 and 60 seconds only trip on a dead connection (after airplane
 * mode, say), which then retries instead of sitting for minutes.
 */
fun uploadHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .connectTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/**
 * Uploads one video to the household's Google Drive in 16 MiB chunks straight to the resumable session, and resumes
 * from the offset the server reports (taken from Drive), so a killed app or a lost connection costs at most one chunk.
 */
class DriveUploader(
    private val api: DriveUploadApi,
    private val http: OkHttpClient = uploadHttpClient(),
    private val retryDelayMs: (attempt: Int) -> Long = ::backoffMs,
    /** Progress notes for the log. They never contain the session URL, a token or a file name. */
    private val log: (String) -> Unit = {},
) {
    /** Runs an upload the control plane has already started; [restart] begins a fresh one if the session expired. */
    suspend fun run(
        start: R2UploadStart,
        restart: suspend () -> R2UploadStart,
        channel: FileChannel,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): R2Done = withContext(Dispatchers.IO) {
        try {
            send(start, channel, size, onProgress)
        } catch (gone: R2ApiException) {
            if (gone.code != "upload_gone") throw gone
            // The session expired (Drive keeps one for a week): cancel it, so the server starts a new one, then begin again once.
            runCatching { api.abort(start.uploadId) }
            send(restart(), channel, size, onProgress)
        }
    }

    private suspend fun send(start: R2UploadStart, channel: FileChannel, size: Long, onProgress: (Long, Long) -> Unit): R2Done {
        val chunk = start.chunkSize
        // Drive wants every chunk but the last to be a multiple of 256 KiB.
        require(chunk > 0 && chunk % GRANULE == 0L) { "The server sent a chunk size Drive would refuse" }
        // The first answer also says where to begin: Drive's own count, so a resume never trusts this process's memory.
        val first = api.session(start.uploadId)
        val url = first.sessionUrl
        var offset = first.receivedBytes.coerceIn(0, size)
        log("Drive upload: ${if (start.resumed) "resumed" else "started"} at $offset of $size")
        onProgress(offset, size)
        var failures = 0
        while (offset < size) {
            coroutineContext.ensureActive()
            val from = offset
            val to = minOf(from + chunk, size)
            try {
                val next = putChunk(url, channel, from, to, size) { onProgress(from + it, size) }
                if (next <= from) throw IOException("Drive did not advance")
                offset = next
                failures = 0
                log("Drive upload: chunk ok, Drive holds $offset of $size")
                onProgress(offset, size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (failure is ChunkRefused && (failure.status == 404 || failure.status == 410)) throw R2ApiException(410, "upload_gone")
                failures++
                log("Drive upload: chunk failed ($failures/$MAX_ATTEMPTS): ${failure.javaClass.simpleName}${(failure as? ChunkRefused)?.let { " ${it.status}" }.orEmpty()}")
                if (failures >= MAX_ATTEMPTS) throw failure
                delay(retryDelayMs(failures))
                // Whether a chunk that failed half way arrived is Drive's to say: ask again and carry on from its answer.
                // If the server cannot be reached either (the network is still down), keep going: the next attempt asks again.
                try {
                    offset = api.session(start.uploadId).receivedBytes.coerceIn(0, size)
                    log("Drive upload: after the failure Drive holds $offset of $size")
                    onProgress(offset, size)
                } catch (refused: R2ApiException) {
                    throw refused
                } catch (unreachable: IOException) {
                    log("Drive upload: could not ask where Drive stands (${unreachable.javaClass.simpleName})")
                }
            }
        }
        return api.complete(start.uploadId)
    }

    /** Sends bytes [from, to) and returns how far Drive now says it has: 308 reports a `Range`, 200 or 201 means all of it. */
    private fun putChunk(url: String, channel: FileChannel, from: Long, to: Long, total: Long, onBytes: (Long) -> Unit): Long {
        val request = Request.Builder().url(url)
            .header("Content-Range", "bytes $from-${to - 1}/$total")
            .put(ChannelRegionBody(channel, from, to - from, onBytes))
            .build()
        http.newCall(request).execute().use { response ->
            return when (response.code) {
                200, 201 -> total
                308 -> Regex("^bytes=0-(\\d+)$").find(response.header("Range").orEmpty())?.groupValues?.get(1)?.toLong()?.plus(1) ?: 0L
                in 400..499 -> throw ChunkRefused(response.code)
                else -> throw IOException("Drive answered ${response.code}")
            }
        }
    }

    private companion object {
        const val GRANULE = 256L * 1024
        /**
         * With [backoffMs] this waits about two and a half minutes in all (1+2+4+8+16 and then 30 s each), enough to ride out
         * airplane mode or a lift. After that the save is marked failed and Retry continues from where Drive stopped.
         */
        const val MAX_ATTEMPTS = 10
    }
}

/** 1, 2, 4, 8, 16, then 30 seconds. */
internal fun backoffMs(attempt: Int): Long = minOf(1_000L shl (attempt - 1).coerceAtMost(5), 30_000L)

/**
 * The one uploader the phone runs. It starts the upload, then hands it to the uploader the server's `protocol`
 * names, so a provider that reuses a protocol needs no change here.
 */
class OwnCloudUploader(
    private val api: OwnCloudApi,
    http: OkHttpClient = uploadHttpClient(),
    retryDelayMs: (attempt: Int) -> Long = ::backoffMs,
    log: (String) -> Unit = {},
) {
    private val s3 = R2Uploader(api, http, retryDelayMs)
    private val drive = DriveUploader(api, http, retryDelayMs, log)

    suspend fun upload(
        originIdentity: String,
        fileName: String,
        contentType: String,
        channel: FileChannel,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): R2Done = withContext(Dispatchers.IO) {
        val size = channel.size()
        require(size > 0) { "Nothing to upload" }
        val restart: suspend () -> R2UploadStart = { api.start(originIdentity, size, contentType, fileName) }
        val start = restart()
        when (start.protocol) {
            "s3_multipart" -> s3.run(start, restart, channel, size, onProgress)
            "gdrive_resumable" -> drive.run(start, restart, channel, size, onProgress)
            else -> throw R2ApiException(0, "unsupported_protocol")
        }
    }
}

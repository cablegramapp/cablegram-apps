package app.cablegram.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

// ---- Your own Cloudflare R2 storage (spec 005, contracts/r2-storage.md) ----

@Serializable
data class R2UploadStart(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("part_size") val partSize: Long,
    @SerialName("part_count") val partCount: Int,
    val resumed: Boolean = false,
)

@Serializable
data class R2PartInfo(val part: Int, val etag: String, val size: Long)

@Serializable
data class R2PartUrl(val part: Int, val url: String)

@Serializable
data class R2Parts(val uploaded: List<R2PartInfo> = emptyList(), val urls: List<R2PartUrl> = emptyList())

@Serializable
data class R2Done(
    @SerialName("source_id") val sourceId: String,
    @SerialName("media_item_id") val mediaItemId: String,
    val bytes: Long,
)

data class R2PartRef(val part: Int, val etag: String)

/** A control-plane refusal; [code] is its stable error string, never free text from the bucket. */
class R2ApiException(val status: Int, val code: String) : IOException("R2 $status $code") {
    /** Said to the person, not logged: what to do about it. */
    val friendly: String
        get() = when (code) {
            "storage_not_connected" -> "Your Cloudflare storage is disconnected. Connect it again in Storage."
            "size_mismatch" -> "Cloudflare stored a different size than the phone sent. Save it again."
            "file_too_large" -> "This video is too large to save to Cloudflare R2."
            "storage_unavailable" -> "Cloudflare R2 isn't answering. Try again later."
            "upload_gone" -> "The upload expired. Save it again."
            else -> "Couldn't save to Cloudflare ($code)."
        }
}

/** The control plane's side of Save to Cloud. The video bytes never go through it. */
interface R2UploadApi {
    suspend fun start(originIdentity: String, sizeBytes: Long, contentType: String, fileName: String): R2UploadStart
    suspend fun parts(uploadId: String, from: Int, count: Int): R2Parts
    suspend fun complete(uploadId: String, parts: List<R2PartRef>): R2Done
    suspend fun abort(uploadId: String)
}

/**
 * Uploads one video to the household's bucket in 16 MiB parts straight to presigned URLs, and resumes from
 * the parts the bucket already holds, so a killed app or a lost connection costs at most one part.
 */
class R2Uploader(
    private val api: R2UploadApi,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build(),
    private val retryDelayMs: (attempt: Int) -> Long = { 1_000L shl (it - 1) },
) {
    suspend fun upload(
        originIdentity: String,
        fileName: String,
        contentType: String,
        channel: FileChannel,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): R2Done = withContext(Dispatchers.IO) {
        val size = channel.size()
        require(size > 0) { "Nothing to upload" }
        val start = api.start(originIdentity, size, contentType, fileName)
        try {
            send(start, channel, size, onProgress)
        } catch (gone: R2ApiException) {
            // The open upload vanished (aborted, or the bucket was disconnected meanwhile): begin again once.
            if (gone.code != "upload_gone") throw gone
            send(api.start(originIdentity, size, contentType, fileName), channel, size, onProgress)
        }
    }

    private suspend fun send(start: R2UploadStart, channel: FileChannel, size: Long, onProgress: (Long, Long) -> Unit): R2Done {
        val partSize = start.partSize
        fun partLength(part: Int) = minOf(partSize, size - (part - 1) * partSize)
        var sent = 0L
        var batchFrom = 1
        var held = emptyMap<Int, R2PartInfo>()
        while (batchFrom <= start.partCount) {
            val batch = api.parts(start.uploadId, batchFrom, BATCH)
            if (batchFrom == 1) {
                // Only parts of the right size count: a part from an older, different file is sent again.
                held = batch.uploaded.filter { it.size == partLength(it.part) }.associateBy { it.part }
                sent = held.values.sumOf { it.size }
                onProgress(sent, size)
            }
            for (target in batch.urls) {
                coroutineContext.ensureActive()
                if (target.part in held) continue
                val length = partLength(target.part)
                val offset = (target.part - 1) * partSize
                var url = target.url
                var attempt = 0
                while (true) {
                    attempt++
                    val before = sent
                    try {
                        putPart(url, channel, offset, length) { sent = before + it; onProgress(sent, size) }
                        sent = before + length
                        onProgress(sent, size)
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: IOException) {
                        sent = before
                        if (attempt >= MAX_ATTEMPTS) throw failure
                        delay(retryDelayMs(attempt))
                        // A rejected URL (expired or revoked) is replaced; a network error retries the same one.
                        if (failure is PartRejected && failure.status == 403) {
                            url = api.parts(start.uploadId, target.part, 1).urls.firstOrNull { it.part == target.part }?.url ?: throw failure
                        }
                    }
                }
            }
            batchFrom += BATCH
        }
        // The bucket's own list is the record of what arrived, not this process's memory.
        val stored = api.parts(start.uploadId, 1, 1).uploaded.associateBy { it.part }
        val refs = (1..start.partCount).map { part ->
            val info = stored[part] ?: throw IOException("Part $part did not arrive")
            R2PartRef(part, info.etag)
        }
        return api.complete(start.uploadId, refs)
    }

    private class PartRejected(val status: Int) : IOException("part rejected ($status)")

    private fun putPart(url: String, channel: FileChannel, offset: Long, length: Long, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(url).put(ChannelRegionBody(channel, offset, length, onBytes)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw if (response.code in 400..499) PartRejected(response.code) else IOException("R2 answered ${response.code}")
        }
    }

    /** Reads one part straight from the file, positionally, so retries and resumes never copy it into memory. */
    private class ChannelRegionBody(
        private val channel: FileChannel,
        private val offset: Long,
        private val length: Long,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteBuffer.allocate(256 * 1024)
            var position = offset
            var remaining = length
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = channel.read(buffer, position)
                if (read <= 0) throw IOException("The video ended early")
                sink.write(buffer.array(), 0, read)
                position += read
                remaining -= read
                onBytes(length - remaining)
            }
        }
    }

    private companion object {
        const val BATCH = 4
        const val MAX_ATTEMPTS = 4
    }
}

/** How `POST /api/storage/connect/r2` ended: [error] is null on success, else the server's stable error string. */
data class R2ConnectResult(val error: String?)

/** What to tell the owner about a failed connect. Never repeats what the server or bucket said. */
fun r2ConnectMessage(error: String): String = when (error) {
    "credentials_rejected" -> "Cloudflare refused these keys. Check the Access Key ID and the Secret, and that the token can edit this bucket."
    "bucket_not_found" -> "That bucket wasn't found in this account. Check the account ID and bucket name, and that R2 is turned on."
    "invalid_request" -> "One of the fields doesn't look right. The account ID is 32 characters; the bucket is lowercase letters, digits and dashes."
    "storage_already_connected" -> "Your storage is already connected."
    "storage_not_configured" -> "This server can't keep storage keys yet."
    "storage_unavailable" -> "Cloudflare R2 isn't answering. Try again in a moment."
    "offline" -> "Couldn't reach Cablegram. Check your connection."
    else -> "Couldn't connect ($error)."
}

// Live checks for the connect form: null means fine (or nothing typed yet). Mirror the server's own rules.
/**
 * The 32-character account ID, from what the owner typed: the ID itself, or the Endpoint address Cloudflare shows with
 * a new token (`https://<account id>.r2.cloudflarestorage.com`, with or without a bucket after it). Null if neither.
 */
fun r2AccountIdFrom(input: String): String? {
    val text = input.trim()
    Regex("[0-9a-fA-F]{32}").matchEntire(text)?.let { return text.lowercase() }
    return Regex("^(?:https?://)?([0-9a-fA-F]{32})\\.r2\\.cloudflarestorage\\.com(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.get(1)?.lowercase()
}

fun r2AccountIdProblem(value: String): String? =
    if (value.isBlank() || r2AccountIdFrom(value) != null) null
    else "That isn't an account ID. Paste the Endpoint address Cloudflare showed with your keys."

fun r2BucketProblem(value: String): String? =
    if (value.isBlank() || Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(value.trim())) null
    else "Use 3 to 63 lowercase letters, digits or dashes, as when you made the bucket."

fun r2KeyIdProblem(value: String): String? =
    if (value.isBlank() || Regex("[A-Za-z0-9]{16,128}").matches(value.trim())) null
    else "That doesn't look like an Access Key ID. Copy it again from the token page."

fun r2SecretProblem(value: String): String? =
    if (value.isBlank() || Regex("[A-Za-z0-9/+=_-]{32,128}").matches(value.trim())) null
    else "That doesn't look like a Secret Access Key. It's the long value under the Access Key ID."

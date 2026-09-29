package app.cablegram.phone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class RelayClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun signal(
        pin: String,
        host: String,
        port: Int,
        publicBaseUrl: String?,
        library: List<LibraryItem>,
        commands: List<RemoteCommand>,
    ): Boolean {
        val body = buildJsonObject {
            put("pin", pin)
            put("host", host)
            put("port", port)
            if (!publicBaseUrl.isNullOrBlank()) put("publicBaseUrl", publicBaseUrl.trim().trimEnd('/'))
            putJsonArray("library") {
                library.forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("title", item.title)
                        item.durationSeconds?.let { put("durationSeconds", it) }
                        item.fileSizeBytes?.let { put("fileSizeBytes", it) }
                        putJsonArray("genres") { item.genres.forEach(::add) }
                        item.year?.let { put("year", it) }
                        item.overview?.let { put("overview", it) }
                        put("mediaType", item.mediaType)
                        put("enhanced", item.enhanced)
                        put("importedAt", item.importedAt)
                        put("copied", item.copied)
                        put("cloudObjectPresent", item.cloudObjectPresent)
                        put("storageState", item.storageState)
                    })
                }
            }
            putJsonArray("commands") {
                commands.forEach { command ->
                    add(buildJsonObject {
                        put("id", command.id)
                        put("command", command.command)
                        command.videoId?.let { put("videoId", it) }
                    })
                }
            }
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/relay/signal")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                PairLog.i("POST /api/relay/signal HTTP ${response.code} ${PairLog.pinTail(pin)} api=$baseUrl")
                if (!response.isSuccessful) {
                    PairLog.w("signal body=${response.body?.string()?.take(200)}")
                }
                response.isSuccessful
            }
        }.onFailure { PairLog.e("signal request failed api=$baseUrl", it) }.getOrDefault(false)
    }

    fun pollWork(pin: String): RelayWork? {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/relay/work/$pin")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<RelayWorkResponse>(response.body?.string().orEmpty()).work
            }
        }.getOrNull()
    }

    fun completeWork(store: LibraryStore, work: RelayWork): Boolean {
        val item = store.get(work.videoId) ?: return false
        if (work.kind == "poster") {
            val file = store.posterFile(item) ?: return false
            val length = file.length()
            val (start, end) = parseRange(work.range, length)
            val size = (end - start + 1).toInt()
            val bytes = ByteArray(size)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(bytes)
            }
            return postWork(work, bytes, length, start, end, "image/jpeg", "200")
        }
        val pfd = store.openPfd(item) ?: return false
        val length = store.videoLength(item).takeIf { it > 0 } ?: pfd.statSize
        val (start, end) = parseRange(work.range, length)
        val size = (end - start + 1).toInt()
        val bytes = ByteArray(size)
        java.io.FileInputStream(pfd.fileDescriptor).use { input ->
            input.channel.position(start)
            var offset = 0
            while (offset < size) {
                val n = input.read(bytes, offset, size - offset)
                if (n <= 0) break
                offset += n
            }
        }
        pfd.close()
        return postWork(work, bytes, length, start, end, "video/mp4", "206")
    }

    private fun postWork(
        work: RelayWork,
        bytes: ByteArray,
        length: Long,
        start: Long,
        end: Long,
        mime: String,
        status: String,
    ): Boolean {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/relay/work/${work.id}")
            .header("Content-Range", "bytes $start-$end/$length")
            .header("X-Relay-Status", if (work.kind == "poster") "200" else "206")
            .post(bytes.toRequestBody((if (work.kind == "poster") "image/jpeg" else "video/mp4").toMediaType()))
            .build()
        return runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}

@kotlinx.serialization.Serializable
data class RelayWork(val id: String, val pin: String, val kind: String, val videoId: String, val range: String? = null)

@kotlinx.serialization.Serializable
private data class RelayWorkResponse(val work: RelayWork? = null)

private fun parseRange(header: String?, length: Long): Pair<Long, Long> {
    if (header.isNullOrBlank() || !header.startsWith("bytes=")) return 0L to (length - 1).coerceAtLeast(0)
    val spec = header.removePrefix("bytes=").substringBefore(',')
    val start = spec.substringBefore('-').toLongOrNull()?.coerceIn(0, (length - 1).coerceAtLeast(0)) ?: 0
    val end = spec.substringAfter('-').toLongOrNull()?.coerceIn(start, (length - 1).coerceAtLeast(0))
        ?: (length - 1).coerceAtLeast(0)
    return start to end
}

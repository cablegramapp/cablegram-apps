package app.cablegram.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Brand clips are downloaded once from media.cablegram.app and played from this TV's storage after that, so a play
 * never waits on the network for decoration. The first appearance of a clip streams it and keeps a copy. The copies
 * are capped at [MAX_TOTAL_BYTES]; the least recently shown go first.
 */
object SignatureClips {
    private const val MAX_TOTAL_BYTES = 80L * 1024 * 1024
    private const val MAX_CLIP_BYTES = 20L * 1024 * 1024
    @Volatile private var directory: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()
    }

    fun init(context: Context) {
        if (directory != null) return
        init(File(context.applicationContext.filesDir, "signature-clips"), prefetch = true)
    }

    internal fun init(dir: File, prefetch: Boolean) {
        dir.mkdirs()
        directory = dir
        if (prefetch) scope.launch {
            // Enough for the usual journeys: two waiting clips to vary between, one error clip, the password clip.
            val missingLoading = DEFAULT_LOADING_VIDEOS.filterNot(::isCached).shuffled()
                .take((2 - DEFAULT_LOADING_VIDEOS.count(::isCached)).coerceAtLeast(0))
            (missingLoading + ERROR_SIGNATURE_VIDEOS.take(1) + TELEGRAM_PASSWORD_VIDEO_URL).forEach(::fetch)
        }
    }

    internal fun fileFor(url: String): File? = directory?.let { dir ->
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, "${hash.take(32)}.mp4")
    }

    fun isCached(url: String): Boolean = fileFor(url)?.let { it.isFile && it.length() > 0 } == true

    /** The stored copy when there is one; otherwise the network address, and a copy is kept for next time. */
    fun playable(url: String): String {
        val file = fileFor(url) ?: return url
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return Uri.fromFile(file).toString()
        }
        scope.launch { fetch(url) }
        return url
    }

    /** A random clip, preferring stored ones once there are at least two to vary between. */
    fun pick(urls: List<String>): String {
        val stored = urls.filter(::isCached)
        return (if (stored.size >= 2) stored else urls).random()
    }

    private fun fetch(url: String) {
        val target = fileFor(url) ?: return
        if (isCached(url) || !downloading.add(url)) return
        val part = File(target.path + ".part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null || body.contentLength() > MAX_CLIP_BYTES) return
                var written = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_CLIP_BYTES) return
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (part.length() > 0 && part.renameTo(target)) trim()
        } catch (_: Exception) {
            // Decoration only: the clip streams again next time.
        } finally {
            part.delete()
            downloading.remove(url)
        }
    }

    private fun trim() {
        val clips = directory?.listFiles { file -> file.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        clips.forEach { clip -> total += clip.length(); if (total > MAX_TOTAL_BYTES) clip.delete() }
    }
}
